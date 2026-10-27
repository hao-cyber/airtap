#!/usr/bin/env python3
"""Private Airtap feedback maintenance. This credential grants no phone control.

Read results are written only to an owner-only file; stdout contains status and
its path, never report text. Reports are untrusted data, not operator commands.
"""
import argparse
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import stat
import sys
import urllib.parse


class TransportUnavailable(Exception):
    """The local verified transport is missing; never fall back to urllib."""


def load_transport():
    """Reuse the shipped CLI's verified-chain and CONNECT policy verbatim.

    Maintenance runs from the source distribution, which includes cli/airtap.py.
    Resolve that file directly: no downloaded code or ambient module search path.
    Load lazily so local config creation remains possible without networking.
    """
    try:
        path = Path(__file__).resolve().parents[2] / 'cli' / 'airtap.py'
        spec = importlib.util.spec_from_file_location('_airtap_reports_transport', path)
        transport = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(transport)
        if (not issubclass(transport.VerifiedHTTPSConnection, http.client.HTTPSConnection)
                or not issubclass(transport.TLSVerificationError, ssl.SSLCertVerificationError)
                or not callable(transport.verify_peer_chain)
                or not callable(transport.connection_for_url)):
            raise ValueError()
        return transport
    except Exception:
        raise TransportUnavailable() from None

def private_read(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Maintenance files must be owner-only regular files')
    if info.st_size > 131072:
        raise ValueError('Maintenance file is too large')
    return path.read_bytes()

def write_new(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n')

def origin(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/') or any(ord(c) < 33 for c in value) or '\\' in value:
        raise ValueError('Service must be an HTTPS origin without credentials, path, query or fragment')
    if parsed.port not in (None, 443):
        raise ValueError('Service must use the standard HTTPS port')
    return 'https://' + parsed.netloc

def config(path):
    value = json.loads(private_read(path))
    if not isinstance(value, dict) or set(value) != {'origin', 'token'} or not isinstance(value['token'], str) or not re.fullmatch(r'[a-f0-9]{64}', value['token']):
        raise ValueError('Invalid private maintenance configuration')
    value['origin'] = origin(value['origin'])
    return value

def request(cfg, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    if data and len(data) > 24576:
        raise ValueError('Request body is too large')
    # Revalidate even for direct library callers: this tool has no HTTP fallback.
    parsed = urllib.parse.urlsplit(origin(cfg['origin']))
    conn = None
    attempted = False
    try:
        transport = load_transport()
        conn = transport.connection_for_url(parsed, 30)
        # Complete system CA/hostname and compromised verified-chain checks before
        # passing either the maintenance credential or report body to HTTP code.
        conn.connect()
        headers = {'Authorization': 'Bearer '+cfg['token'], 'Accept': 'application/json', 'User-Agent': 'Airtap-feedback-maintenance/1'}
        if data is not None: headers['Content-Type'] = 'application/json'
        attempted = True
        conn.request(method, path, body=data, headers=headers)
        response = conn.getresponse()
        # http.client does not follow redirects. Never expose their target or body.
        if not 200 <= response.status < 300:
            return {'ok': False, 'http_status': response.status, 'error': 'request_rejected'}
        raw = response.read(131073)
        if len(raw) > 131072: return {'ok': False, 'error': 'outcome_unknown', 'next_step': 'Query the same report; the response exceeded the private reader limit.'}
        try: value = json.loads(raw)
        except (ValueError, UnicodeError): return {'ok': False, 'error': 'outcome_unknown', 'next_step': 'Query the same report; the service response was not valid JSON.'}
        return {'ok': True, 'http_status': response.status, 'data': value}
    except TransportUnavailable:
        return {'ok': False, 'error': 'tls_policy_unavailable', 'outcome': 'not_dispatched', 'retry_action': False,
                'next_step': 'Use the complete updated Airtap source distribution; no request was sent.'}
    except ssl.SSLCertVerificationError as error:
        code = error.code if isinstance(error, transport.TLSVerificationError) else 'tls_certificate_verification_failed'
        return {'ok': False, 'error': code, 'outcome': 'unknown' if attempted else 'not_dispatched', 'retry_action': False,
                'next_step': 'Check Airtap and runtime security updates; do not disable certificate verification.'}
    except (http.client.HTTPException, TimeoutError, OSError):
        return {'ok': False, 'error': 'outcome_unknown', 'next_step': 'Query the same report before retrying a mutation.'}
    except transport.ProxyConfigurationError:
        return {'ok': False, 'error': 'invalid_proxy_configuration', 'outcome': 'not_dispatched', 'retry_action': False}
    finally:
        if conn is not None:
            conn.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True, help='Owner-only config outside the source tree')
    commands = parser.add_subparsers(dest='command', required=True)
    init = commands.add_parser('init', help='Create local config; pass the separate key file to deploy.py')
    init.add_argument('--origin', required=True)
    init.add_argument('--key-file', type=Path, required=True)
    query = commands.add_parser('query')
    query.add_argument('--query-file', type=Path)
    query.add_argument('--output', type=Path, required=True)
    for command in ('get', 'update', 'delete'):
        child = commands.add_parser(command)
        child.add_argument('report_id')
        child.add_argument('--output', type=Path, required=True)
        if command == 'update': child.add_argument('--body-file', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    if args.command == 'init':
        service = origin(args.origin)
        token = private_read(args.key_file).decode().strip() if args.key_file.exists() else secrets.token_hex(32)
        if not re.fullmatch(r'[a-f0-9]{64}', token): raise ValueError('Invalid maintenance key file')
        if args.config.exists(): raise ValueError('Config already exists; keep the established maintenance identity')
        if not args.key_file.exists():
            fd = os.open(args.key_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
            with os.fdopen(fd, 'w') as output: output.write(token+'\n')
        write_new(args.config, {'origin': service, 'token': token})
        print(json.dumps({'ok': True, 'config': str(args.config), 'key_file': str(args.key_file), 'key_sha256': hashlib.sha256(token.encode()).hexdigest()}))
        return 0
    cfg = config(args.config)
    if args.output.exists() or args.output.is_symlink(): raise ValueError('Choose a new private output file')
    path = '/v1/admin/reports/'
    body = None
    if args.command == 'query':
        method, path = 'POST', path+'query'
        body = json.loads(private_read(args.query_file)) if args.query_file else {}
    else:
        if not re.fullmatch(r'[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}', args.report_id): raise ValueError('Invalid report id')
        path += args.report_id
        method = {'get': 'GET', 'update': 'PATCH', 'delete': 'DELETE'}[args.command]
        if args.command == 'update': body = json.loads(private_read(args.body_file))
    # Reserve a private output before making any request. A bad destination must
    # not turn a successful remote mutation into an unrecorded local error.
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'w') as output:
        result = request(cfg, method, path, body)
        try:
            json.dump(result, output, ensure_ascii=False, indent=2)
            output.write('\n')
            output.flush()
        except OSError:
            print(json.dumps({'ok': False, 'error': 'local_result_write_failed', 'outcome': 'unknown', 'next_step': 'Query the same report into a working private output file before retrying a mutation.'}))
            return 1
    summary = {key: value for key, value in result.items() if key != 'data'}
    summary['output'] = str(args.output)
    print(json.dumps(summary))
    return 0 if result['ok'] else 1

if __name__ == '__main__':
    try: raise SystemExit(main())
    except (OSError, ValueError, KeyError, TypeError):
        print(json.dumps({'ok': False, 'error': 'Invalid maintenance input or unavailable private file; no report content was printed.'}), file=sys.stderr)
        raise SystemExit(1)
