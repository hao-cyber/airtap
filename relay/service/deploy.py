#!/usr/bin/env python3
"""Freeze and deploy an independent Airtap v4 service.

All identities, secrets, revisions and deployment output stay in an owner-only
directory outside the checkout. An uncertain deployment must be resumed with
the same state; update/configure never creates another service identity.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import tempfile
import unicodedata

ROOT = Path(__file__).resolve().parents[2]
KIND = 'airtap-multi-device-service-v4'
BASE_SOURCES = ('relay/service/service.mjs', 'relay/worker/worker.mjs')
SOURCES = (*BASE_SOURCES, 'relay/service/feedback.mjs')
ASSETS = {'cli/airtap.py': 'cli/airtap.py', 'agent.md': 'docs/agent.md'}
BINDINGS = {'bindings': [
    {'name': 'ADMISSION', 'class_name': 'AdmissionRegistry'},
    {'name': 'DEVICES', 'class_name': 'ServiceDeviceRelay'},
]}
MIGRATIONS = [{'tag': 'service-v1', 'new_sqlite_classes': ['AdmissionRegistry', 'ServiceDeviceRelay']}]
QUOTAS = {
    'MAX_DEVICES': (100, 1000), 'DAILY_REGISTRATIONS': (10, 1000),
    'IP_DAILY_REGISTRATIONS': (3, 20), 'DEVICE_DAILY_REQUESTS': (0, 1000000),
    'DEVICE_DAILY_COMMANDS': (0, 1000000),
    'DEVICE_DAILY_RESPONSE_BYTES': (0, 10737418240),
}
DEFAULTS = {'SERVICE_LABEL': 'Airtap service preview', 'REGISTRATION_OPEN': 'true',
            'CONTROL_OPEN': 'true', **{key: str(value[0]) for key, value in QUOTAS.items()}}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def private_write(path, content):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(content.encode() if isinstance(content, str) else content)


def json_write(path, value):
    private_write(path, json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def replace_json(path, value):
    temporary = path.with_name('update-' + secrets.token_hex(8))
    json_write(temporary, value)
    os.replace(temporary, path)


def owner_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise ValueError('Private state must be an owner-only regular file')
    return path.read_bytes()


def private_json(path):
    return json.loads(owner_file(path))


def state_path(value):
    path = value.absolute()
    if path.is_symlink() or path != path.resolve():
        # /tmp is a symlink on macOS; accept its canonical prefix but never a
        # symlinked state file. The actual state always records resolved paths.
        if path.is_symlink():
            raise ValueError('State file must not be a symlink')
    return path.resolve()


def settings(base, path=None):
    result = dict(base)
    if path:
        supplied = json.loads(path.read_text())
        if not isinstance(supplied, dict) or set(supplied) - set(DEFAULTS):
            raise ValueError('Settings contain unknown fields')
        result.update(supplied)
    if set(result) != set(DEFAULTS):
        raise ValueError('Incomplete service settings')
    label = result['SERVICE_LABEL']
    if not isinstance(label, str) or not 1 <= len(label) <= 80 or any(unicodedata.category(c).startswith('C') for c in label):
        raise ValueError('SERVICE_LABEL must be 1..80 visible characters')
    for key in ('REGISTRATION_OPEN', 'CONTROL_OPEN'):
        if result[key] not in ('true', 'false'):
            raise ValueError(key + ' must be the string true or false')
    for key, (_, maximum) in QUOTAS.items():
        raw = result[key]
        if not isinstance(raw, str) or not re.fullmatch(r'(?:0|[1-9][0-9]*)' if key.startswith('DEVICE_') else r'[1-9][0-9]*', raw) or int(raw) > maximum:
            raise ValueError(key + ' must be a positive decimal string within its documented hard limit')
    return result


def source_files():
    files = {}
    for relative in (*SOURCES, *ASSETS.values()):
        path = ROOT / relative
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError('Required source is missing or linked outside the checkout: ' + relative)
        data = path.read_bytes()
        if not data or len(data) > 262144:
            raise ValueError('Source size is outside 1..262144 bytes: ' + relative)
        files[relative] = data
    return files


def frozen_sources(names, assets):
    """Recognize immutable older revisions without accepting arbitrary modules."""
    sources = set(names) - set(assets)
    if sources == set(SOURCES):
        return SOURCES
    if sources == set(BASE_SOURCES):
        return BASE_SOURCES
    raise ValueError('Unexpected frozen source list')


def feedback_admin_hash(path):
    if path is None:
        return None
    raw = owner_file(path)
    # Only a private key file is accepted; secrets never belong in argv.
    if not re.fullmatch(rb'[a-f0-9]{64}\n?', raw):
        raise ValueError('Feedback admin key must be 64 lowercase hex characters in an owner-only file')
    return digest(raw.strip())


def identity(state, account_id, salt):
    return {'kind': KIND, 'service_id': state['service_id'], 'name': state['name'],
            'hostname': state['hostname'], 'account_id': account_id,
            'ingress_namespace': state['ingress_namespace'],
            'ip_salt_sha256': digest(salt.encode()), 'durable_objects': BINDINGS, 'migrations': MIGRATIONS}


def configuration(root, state, account_id):
    metadata = state['artifacts']
    config = {
        'name': state['name'], 'main': str(root / SOURCES[0]),
        'compatibility_date': '2026-10-01', 'workers_dev': not bool(state['hostname']),
        'preview_urls': False, 'observability': {'enabled': False},
        'durable_objects': BINDINGS, 'migrations': MIGRATIONS,
        'ratelimits': [{'name': 'INGRESS', 'namespace_id': state['ingress_namespace'],
                       'simple': {'limit': 120, 'period': 60}}],
        'assets': {'directory': str(root / 'assets'), 'binding': 'ASSETS', 'run_worker_first': True},
        'vars': {**state['settings'], 'SERVICE_ID': state['service_id'],
                 'CLI_SHA256': metadata['cli_sha256'], 'AGENT_SHA256': metadata['agent_sha256'],
                 'ARTIFACT_VERSION': metadata['version']},
    }
    if account_id:
        config['account_id'] = account_id
    if state['hostname']:
        config['routes'] = [{'pattern': state['hostname'], 'custom_domain': True}]
    return config


def freeze(root, state, account_id, salt, files, admin_hash=None):
    sources = frozen_sources(files, ASSETS.values())
    if admin_hash is not None and (sources != SOURCES or not re.fullmatch(r'[a-f0-9]{64}', admin_hash)):
        raise ValueError('Feedback maintenance requires the feedback source and a valid key hash')
    manifest = {}
    for relative in sources:
        path = root / relative
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        private_write(path, files[relative])
        manifest[relative] = digest(files[relative])
    for public, relative in ASSETS.items():
        path = root / 'assets' / public
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        private_write(path, files[relative])
        manifest['assets/' + public] = digest(files[relative])
    source_hash = digest(json.dumps({key: manifest[key] for key in sources}, sort_keys=True).encode())
    state['manifest'] = manifest
    state['source_sha256'] = source_hash
    state['artifacts'] = {
        'cli_sha256': manifest['assets/cli/airtap.py'], 'agent_sha256': manifest['assets/agent.md'],
        'version': 'service4-' + source_hash[:8] + '-' + manifest['assets/cli/airtap.py'][:8] + '-' + manifest['assets/agent.md'][:8],
    }
    config = configuration(root, state, account_id)
    json_write(root / 'wrangler.json', config)
    credentials = {'IP_HASH_SALT': salt}
    if admin_hash is not None:
        credentials['FEEDBACK_ADMIN_SHA256'] = admin_hash
        state['feedback_admin_sha256'] = admin_hash
    else:
        state.pop('feedback_admin_sha256', None)
    json_write(root / 'secrets.json', credentials)
    path = root / 'state.json'
    json_write(path, state)
    return path


def validate(path):
    state = private_json(path)
    root = path.parent
    if state.get('schema') != 1 or state.get('deployment_kind') != KIND or root.is_relative_to(ROOT.resolve()):
        raise ValueError('Not an external v3 service state')
    deployment_root = Path(state['deployment_root'])
    if not deployment_root.is_absolute() or deployment_root != deployment_root.resolve() or not root.is_relative_to(deployment_root):
        raise ValueError('Invalid private deployment root')
    info = deployment_root.stat()
    if info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise ValueError('Deployment directory must be owner-only')
    if not re.fullmatch(r'airtap-service-\d{8}-[a-f0-9]{32}', state['name']) or not re.fullmatch(r'[a-f0-9]{32}', state['service_id']):
        raise ValueError('Invalid frozen service identity')
    if not re.fullmatch(r'[1-9][0-9]{8,14}', state.get('ingress_namespace', '')):
        raise ValueError('Invalid frozen ingress namespace')
    credentials = private_json(root / 'secrets.json')
    if not isinstance(credentials, dict) or set(credentials) not in ({'IP_HASH_SALT'}, {'IP_HASH_SALT', 'FEEDBACK_ADMIN_SHA256'}) or not all(isinstance(v, str) and re.fullmatch(r'[a-f0-9]{64}', v) for v in credentials.values()):
        raise ValueError('Invalid private service credential')
    if state.get('feedback_admin_sha256') != credentials.get('FEEDBACK_ADMIN_SHA256'):
        raise ValueError('Frozen feedback maintenance credential changed')
    config = private_json(root / 'wrangler.json')
    original = private_json(deployment_root / 'identity.json')
    if original != identity(state, config.get('account_id'), credentials['IP_HASH_SALT']):
        raise ValueError('Service identity changed')
    settings(state['settings'])
    asset_names = {'assets/' + name for name in ASSETS}
    sources = frozen_sources(state['manifest'], asset_names)
    expected_names = {*sources, *asset_names}
    if set(state['manifest']) != expected_names:
        raise ValueError('Unexpected frozen source list')
    if credentials.get('FEEDBACK_ADMIN_SHA256') and sources != SOURCES:
        raise ValueError('Feedback maintenance requires the feedback source')
    files = {}
    for relative, expected_hash in state['manifest'].items():
        target = root / relative
        if target.resolve() != target or digest(owner_file(target)) != expected_hash:
            raise ValueError('Frozen source or asset changed: ' + relative)
        files[relative] = target.read_bytes()
    expected_source_hash = digest(json.dumps({key: state['manifest'][key] for key in sources}, sort_keys=True).encode())
    if state['source_sha256'] != expected_source_hash:
        raise ValueError('Frozen source manifest changed')
    expected_artifacts = {
        'cli_sha256': state['manifest']['assets/cli/airtap.py'],
        'agent_sha256': state['manifest']['assets/agent.md'],
        'version': 'service4-' + expected_source_hash[:8] + '-' + state['manifest']['assets/cli/airtap.py'][:8] + '-' + state['manifest']['assets/agent.md'][:8],
    }
    if state['artifacts'] != expected_artifacts or config != configuration(root, state, config.get('account_id')):
        raise ValueError('Frozen deployment configuration changed')
    if state['hostname']:
        if state['url'] != 'https://' + state['hostname']:
            raise ValueError('Service URL changed')
    elif state.get('url') is not None and not re.fullmatch(r'https://' + re.escape(state['name']) + r'\.[a-z0-9.-]+\.workers\.dev', state['url']):
        raise ValueError('workers.dev URL changed')
    latest = private_json(deployment_root / 'latest.json')
    return state, config, credentials['IP_HASH_SALT'], latest


@contextmanager
def lock(root):
    fd = os.open(root / '.deployment.lock', os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another deployment operation is running for this identity') from None
        yield
    finally:
        os.close(fd)


def new_service(args):
    if args.account_id and not re.fullmatch(r'[a-f0-9]{32}', args.account_id):
        raise ValueError('Invalid account id')
    domain = args.domain.lower() if args.domain else None
    if domain and (len(domain) > 190 or not re.fullmatch(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}', domain)):
        raise ValueError('Domain must be a zone hostname without scheme or path')
    admin_hash = feedback_admin_hash(args.feedback_admin_key_file)
    policy = settings(DEFAULTS, args.settings)
    files = source_files()
    if args.state_dir:
        root = args.state_dir.absolute()
        if root.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError('Private service state must remain outside repository')
        root.mkdir(mode=0o700)
        root = root.resolve()
    else:
        root = Path(tempfile.mkdtemp(prefix='airtap-service-')).resolve()
    name = 'airtap-service-' + datetime.now(timezone.utc).strftime('%Y%m%d') + '-' + secrets.token_hex(16)
    salt = secrets.token_hex(32)
    state = {
        'schema': 1, 'deployment_kind': KIND, 'deployment_root': str(root),
        'service_id': secrets.token_hex(16), 'name': name, 'hostname': name + '.' + domain if domain else None,
        'ingress_namespace': str(100000000 + secrets.randbelow(999999900000000)),
        'url': 'https://' + name + '.' + domain if domain else None,
        'settings': policy, 'revision': 1, 'operation': 'create', 'attempted': False, 'deployed': False,
        'created_at': datetime.now(timezone.utc).isoformat(),
    }
    path = freeze(root, state, args.account_id, salt, files, admin_hash)
    json_write(root / 'identity.json', identity(state, args.account_id, salt))
    json_write(root / 'latest.json', {'state_path': str(path), 'pending_state': None})
    return path


def prepare_revision(path, operation, policy_path, admin_key_path=None):
    state, config, salt, latest = validate(path)
    if state['deployed'] is not True:
        raise ValueError('Resume the uncertain deployment before updating it')
    if latest.get('pending_state'):
        raise ValueError('A revision is already pending; resume that exact state')
    if latest.get('state_path') != str(path):
        raise ValueError('Use the latest successful state')
    policy = settings(state['settings'], policy_path)
    admin_hash = feedback_admin_hash(admin_key_path) if admin_key_path else state.get('feedback_admin_sha256')
    if operation == 'update':
        files = source_files()
    else:
        sources = frozen_sources(state['manifest'], ('assets/' + name for name in ASSETS))
        files = {relative: (path.parent / relative).read_bytes() for relative in sources}
        files.update({relative: (path.parent / 'assets' / public).read_bytes() for public, relative in ASSETS.items()})
    root = Path(state['deployment_root']) / 'revisions' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + secrets.token_hex(6))
    root.mkdir(mode=0o700, parents=True)
    revision = dict(state, operation=operation, revision=state['revision'] + 1,
                    previous_state=str(path), settings=policy, attempted=False, deployed=False,
                    created_at=datetime.now(timezone.utc).isoformat())
    updated = freeze(root, revision, config.get('account_id'), salt, files, admin_hash)
    replace_json(Path(state['deployment_root']) / 'latest.json', {'state_path': str(path), 'pending_state': str(updated)})
    return updated


def deploy(path, wrangler, prepare_only):
    state, _, _, latest = validate(path)
    if (latest.get('pending_state') or latest['state_path']) != str(path):
        raise ValueError('This is not the current frozen revision')
    if state['deployed']:
        raise ValueError('Already deployed; choose update or configure')
    public = {key: state[key] for key in ('name', 'url', 'service_id', 'revision', 'operation', 'settings', 'source_sha256', 'artifacts')}
    public['state_path'] = str(path)
    if prepare_only:
        print(json.dumps({'prepared': True, 'deployed': False, **public}))
        return 0
    state['attempted'] = True
    replace_json(path, state)
    root = path.parent
    env = dict(os.environ, WRANGLER_LOG_PATH=str(root / 'wrangler.log'), WRANGLER_SEND_METRICS='false', CI='true')
    try:
        result = subprocess.run([wrangler, 'deploy', '--config', str(root / 'wrangler.json'),
                                 '--secrets-file', str(root / 'secrets.json')],
                                cwd=root, env=env, capture_output=True, text=True, timeout=180)
        json_write(root / ('output-' + secrets.token_hex(6) + '.json'), {'stdout': result.stdout, 'stderr': result.stderr})
        if not state['hostname']:
            matches = re.findall(r'https://' + re.escape(state['name']) + r'\.[a-z0-9.-]+\.workers\.dev', result.stdout + result.stderr)
            if matches:
                state['url'] = matches[-1]
                public['url'] = state['url']
        state['deployed'] = result.returncode == 0 and bool(state['url'])
        replace_json(path, state)
        if state['deployed']:
            replace_json(Path(state['deployment_root']) / 'latest.json', {'state_path': str(path), 'pending_state': None})
        print(json.dumps({'deployed': state['deployed'], **public,
                          'details': 'Deployment output stays private. Unknown/failed outcomes resume the same state.'}))
        return 0 if state['deployed'] else 1
    except subprocess.TimeoutExpired as error:
        def output(value):
            return value.decode(errors='replace') if isinstance(value, bytes) else value
        json_write(root / ('timeout-' + secrets.token_hex(6) + '.json'), {'stdout': output(error.stdout), 'stderr': output(error.stderr)})
        print(json.dumps({'deployed': None, **public, 'error': 'Outcome unknown; resume this exact state, never create another identity.'}))
        return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--account-id')
    parser.add_argument('--domain', help='Optional existing Cloudflare zone; generates an independent random hostname')
    parser.add_argument('--state-dir', type=Path, help='New owner-only directory outside the checkout')
    parser.add_argument('--settings', type=Path, help='JSON containing documented public settings, decimal/boolean values as strings')
    parser.add_argument('--feedback-admin-key-file', type=Path, help='Owner-only file containing a random 64-hex maintenance key; only its SHA256 is deployed')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--wrangler', default='wrangler')
    operation = parser.add_mutually_exclusive_group()
    operation.add_argument('--resume', type=Path, help='Retry precisely the same frozen deployment')
    operation.add_argument('--update', type=Path, help='Freeze current source/assets, preserving service identity')
    operation.add_argument('--configure', type=Path, help='Change settings with prior frozen source/assets, e.g. pause control')
    args = parser.parse_args()
    os.umask(0o077)
    existing = args.resume or args.update or args.configure
    if existing and (args.account_id or args.domain or args.state_dir):
        raise ValueError('Existing-state operations cannot change identity or directory')
    if args.resume and (args.settings or args.feedback_admin_key_file):
        raise ValueError('Resume cannot alter settings or credentials; use configure after a confirmed deployment')
    if args.configure and not (args.settings or args.feedback_admin_key_file):
        raise ValueError('Configure requires explicit settings or a feedback maintenance key')
    if existing:
        path = state_path(existing)
        state, _, _, _ = validate(path)
        root = Path(state['deployment_root'])
    else:
        path = new_service(args)
        root = path.parent
    with lock(root):
        if args.update or args.configure:
            path = prepare_revision(path, 'update' if args.update else 'configure', args.settings, args.feedback_admin_key_file)
        return deploy(path, args.wrangler, args.prepare_only)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, TypeError) as error:
        # Do not echo arbitrary config/secret contents in exception messages.
        print(json.dumps({'error': type(error).__name__, 'detail': str(error) if isinstance(error, ValueError) else 'Deployment state or local command could not be read; inspect private files.'}), file=sys.stderr)
        raise SystemExit(1)
