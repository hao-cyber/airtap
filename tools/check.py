#!/usr/bin/env python3
"""Reproduce local helper/protocol/build checks without deployment or release credentials."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
HOST_SCRIPTS = (
    'test_observation_pages', 'test_atomic_input', 'test_drag', 'test_semantic_nodes', 'test_windows', 'test_session_screen',
    'test_audio_volume', 'test_file_transfer', 'test_approval_recovery',
    'test_approval_scheduling', 'test_setup_recovery', 'test_tls_policy', 'test_feedback', 'test_invitation_feedback',
)
PROTOCOL_SCRIPTS = (
    'companion-roundtrip', 'file-transfer-roundtrip', 'usage-storage',
    'pair-recovery', 'feedback-roundtrip', 'invitation-artifacts',
)


def run_check(argv, cwd, environment, timeout):
    process = subprocess.Popen(argv, cwd=cwd, env=environment, start_new_session=True)
    try:
        return process.wait(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        # A protocol check may own a workerd child: stop the process group too.
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        raise


def check_environment(suite, output):
    environment = dict(os.environ)
    # Child checks need no live account, deployment, signing, or SSH credentials.
    for name in list(environment):
        if name.startswith(('AIRTAP_RELEASE_', 'CLOUDFLARE_', 'TUNNEL_', 'CF_API_', 'WRANGLER_')) or name in {
                'SSH_AUTH_SOCK', 'GITHUB_TOKEN', 'GH_TOKEN', 'NPM_TOKEN', 'NODE_AUTH_TOKEN'}:
            environment.pop(name)
    environment.update({'CI': 'true', 'WRANGLER_SEND_METRICS': 'false',
                        'PYTHON': sys.executable, 'PYTHONDONTWRITEBYTECODE': '1'})
    # npm ci must not read a maintainer's private registry credentials/configuration.
    for key, filename in [('NPM_CONFIG_USERCONFIG', 'npm-user-empty.ini'), ('NPM_CONFIG_GLOBALCONFIG', 'npm-global-empty.ini')]:
        path = output / filename
        path.write_text('')
        environment[key] = str(path)
    versions = {'python': sys.version.split()[0]}
    if suite in ('core', 'host', 'android', 'all'):
        jdk = environment.get('JAVA_HOME')
        if not jdk or not (Path(jdk) / 'bin/javac').is_file():
            raise ValueError('Set JAVA_HOME to a JDK 17+ installation (CI uses Java 17).')
        run = subprocess.run([str(Path(jdk) / 'bin/javac'), '-version'], capture_output=True, text=True, check=True, env=environment)
        versions['javac'] = (run.stdout + run.stderr).strip()
        match = re.search(r'javac (\d+)', versions['javac'])
        if not match or int(match.group(1)) < 17:
            raise ValueError('JDK 17 or newer is required.')
    if suite in ('core', 'protocol', 'all'):
        if not shutil.which('node') or not shutil.which('npm'):
            raise ValueError('Install Node.js 22+ with npm (CI uses Node 22).')
        versions['node'] = subprocess.check_output(['node', '--version'], text=True, env=environment).strip()
        if int(versions['node'].lstrip('v').split('.')[0]) < 22:
            raise ValueError('Node.js 22 or newer is required.')
    return environment, versions


def commands(suite, output, install_deps):
    steps = []
    if suite in ('core', 'host', 'all'):
        steps.append(('source-closure', [sys.executable, str(ROOT / 'tools/source_manifest.py'), '--check'], ROOT, 60))
        steps.append(('python-source-package', [sys.executable, '-m', 'unittest', 'discover', '-s', 'tools/tests', '-p', 'test_*.py'], ROOT, 180))
        for name in HOST_SCRIPTS:
            argv = [sys.executable, str(ROOT / 'apps/android/scripts' / (name + '.py'))]
            if name != 'test_invitation_feedback':
                argv += ['--output', str(output / (name + '.json'))]
            steps.append((name, argv, ROOT, 180))
        steps.append(('connection-closing', [sys.executable, str(ROOT / 'apps/android/tests/test_connection_closing.py'),
                                             '--output', str(output / 'connection-closing.json')], ROOT, 180))
        steps.append(('python-service', [sys.executable, '-m', 'unittest', 'discover', '-s', 'relay/service/tests', '-p', 'test_*.py'], ROOT, 180))
        steps.append(('python-site', [sys.executable, '-m', 'unittest', 'discover', '-s', 'release', '-p', 'test_*.py'], ROOT, 180))
    if suite in ('core', 'protocol', 'all'):
        if install_deps:
            steps.append(('npm-ci', ['npm', 'ci', '--ignore-scripts', '--no-audit', '--no-fund'], ROOT / 'relay/worker', 300))
        elif not (ROOT / 'relay/worker/node_modules/miniflare/package.json').is_file():
            raise ValueError('Dependencies missing: rerun with --install-deps to use the existing npm lockfile.')
        for name in PROTOCOL_SCRIPTS:
            steps.append((name, ['node', str(ROOT / 'relay/service/tests' / (name + '.mjs')),
                                '--output', str(output / (name + '.json'))], ROOT, 240))
    if suite in ('android', 'all'):
        steps.append(('android-compile-lint', ['sh', './gradlew', '--no-daemon', '--console=plain',
                                             ':app:compileDebugJavaWithJavac', ':app:lintDebug'], ROOT / 'apps/android', 1200))
    return steps


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=('core', 'host', 'protocol', 'android', 'all'), default='core')
    parser.add_argument('--install-deps', action='store_true', help='Run npm ci against relay/worker/package-lock.json; no lifecycle scripts')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'dist/checks')
    args = parser.parse_args()
    os.umask(0o077)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    environment, versions = check_environment(args.suite, output)
    steps = commands(args.suite, output, args.install_deps)
    results = []
    for name, argv, cwd, timeout in steps:
        print('Running ' + name, flush=True)
        started = time.monotonic()
        try:
            code = run_check(argv, cwd, environment, timeout)
        except subprocess.TimeoutExpired:
            print(name + ' timed out', file=sys.stderr)
            code = 124
        results.append({'step': name, 'exit_code': code, 'seconds': round(time.monotonic() - started, 3)})
        report = {'suite': args.suite, 'versions': versions, 'steps': results,
                  'completed': len(results) == len(steps), 'passed': all(item['exit_code'] == 0 for item in results),
                  'evidence': 'Local host fixtures/workerd and optional Android compilation; no phone, personal AI, release signature or deployed service verification.',
                  'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        if code:
            return code
    print('All requested checks passed. This is not device or personal-AI acceptance.', flush=True)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print('Check preparation failed: ' + str(error), file=sys.stderr)
        raise SystemExit(2)
