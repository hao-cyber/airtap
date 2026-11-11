#!/usr/bin/env python3
"""Airtap personal-AI CLI. Python 3.10+, standard library only."""
import argparse
import base64
import contextlib
import getpass
import hashlib
import http.client
import ipaddress
import json
import math
import os
import pathlib
import re
import secrets
import ssl
import stat
import subprocess
import sys
import time
import urllib.parse
import unicodedata
import uuid

TOKEN = re.compile(r'^[0-9a-f]{64}$')
TICKET = re.compile(r'^[0-9a-f]{32}$')
MAX_RESPONSE = 512 * 1024
MAX_COMMAND = 65536
VERSION = '0.4.0-cli.20'
PAIRING_TTL_SECONDS = 60 * 60
PAIRING_CLOCK_TOLERANCE_SECONDS = 30
PACKAGE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$')
NODE = re.compile(r'^[1-9][0-9]{0,3}$')
VISUAL_COMMANDS = {'tap', 'long-press', 'swipe', 'drag', 'gesture', 'touch-begin', 'touch-move'}
NODE_COMMANDS = {'click', 'long-click', 'set-text', 'scroll', 'node-action', 'focus', 'set-selection', 'select-all', 'copy', 'cut', 'paste', 'insert-text', 'delete-text', 'editor-action'}
SYSTEM_ACTIONS = ('notifications', 'quick_settings', 'dismiss_notification_shade', 'all_apps', 'dpad_up', 'dpad_down', 'dpad_left', 'dpad_right', 'dpad_center', 'power_dialog', 'lock_screen', 'split_screen')
AUDIO_STREAMS = ('music', 'alarm', 'ring', 'notification', 'system', 'voice_call')
VOLUME_COMMANDS = {'set-volume', 'adjust-volume'}
CONTROL_COMMANDS = {'sim-pin-submit', 'media-capture', 'media-stop', 'touch-begin', 'touch-move', 'touch-end', 'set-volume', 'adjust-volume', 'launch', 'open-url', 'click', 'long-click', 'set-text', 'scroll', 'node-action', 'tap', 'long-press', 'swipe', 'drag', 'gesture', 'back', 'home', 'recents', 'system-action', 'focus', 'set-selection', 'select-all', 'copy', 'cut', 'paste', 'insert-text', 'delete-text', 'editor-action'}
NODE_OPERATIONS = ('accessibility_focus', 'clear_accessibility_focus', 'next_granularity', 'previous_granularity', 'custom', 'select', 'clear_selection', 'clear_focus', 'context_click', 'expand', 'collapse', 'dismiss', 'show_on_screen', 'scroll_up', 'scroll_down', 'scroll_left', 'scroll_right', 'page_up', 'page_down', 'page_left', 'page_right', 'scroll_to_position', 'set_progress', 'drag_start', 'drag_drop', 'drag_cancel')
FILE_LIMIT = 64 * 1024 * 1024
FILE_CHUNK_LIMIT = 32768
FILE_COMMANDS = ('file-publish-media', 'file-mkdir', 'file-rename', 'file-delete', 'file-share', 'file-roots', 'file-list', 'file-stat', 'file-read', 'file-download', 'file-upload', 'file-upload-status', 'file-upload-finish', 'file-upload-abort')
DEFAULT_CONFIG = pathlib.Path.home() / '.config/airtap/preview.json'
MAX_INTEGER = 9007199254740991
EVENT_COMMANDS = ('event-status', 'event-read', 'event-wait')
CONNECTION_COMMANDS = ('connection-status', 'connection-invite', 'connection-approve', 'connection-cancel', 'connection-revoke')
CONNECTION_ACTIONS = {name.replace('-', '_') for name in CONNECTION_COMMANDS}
CONNECTION_MUTATIONS = set(CONNECTION_COMMANDS) - {'connection-status'}
TASK_COMMANDS = ('task-acquire', 'task-renew', 'task-release')
TASK_ERRORS = {
    'lease_required': 409, 'lease_mismatch': 409, 'lease_renew_required': 409,
    'task_closed': 409, 'lease_expired': 410, 'device_busy': 429,
    'task_fence_pending': 503, 'device_disconnected': 503,
    'client_upgrade_required': 409, 'device_upgrade_required': 409,
    'stale_renewal': 409,
}
TASK_BEFORE_OBSERVE = {'ping', 'apps', 'windows', 'foreground', 'system_actions', 'audio_state', 'battery_status',
              'observe', 'read_tree', 'home', 'back', 'recents', 'file_roots', 'file_list', 'file_stat',
              'file_read', 'file_upload_status', 'file_upload_begin', 'file_upload_chunk', 'file_upload_finish', 'file_upload_abort',
              'file_mkdir', 'file_rename', 'file_delete', 'file_publish_media',
              'media_status', 'media_stop', 'sim_pin_prepare', 'sim_pin_submit', *CONNECTION_ACTIONS}

class Failure(Exception):
    def __init__(self, code, **details):
        self.result = {'ok': False, 'error': code, **details}
        super().__init__(code)

def origin(value, local_test=False):
    try:
        u = urllib.parse.urlsplit(value)
        valid = (u.scheme == 'https' and u.port in (None, 443)) or (
            local_test and u.scheme == 'http' and u.hostname in ('127.0.0.1', 'localhost', '::1'))
        if (not valid or not u.hostname or len(value) > 2048 or u.username is not None or u.password is not None
                or not (u.path in ('', '/') or re.fullmatch(r'/d/[a-f0-9]{32}', u.path))
                or u.query or u.fragment or '?' in value or '#' in value
                or any(ord(c) < 33 or ord(c) > 126 for c in value) or '\\' in value):
            raise ValueError()
        return value.rstrip('/')
    except (ValueError, TypeError):
        raise Failure('invalid_relay_origin')

def private_directory(path):
    path = pathlib.Path(os.path.abspath(path))
    # Reject symlinks in all directory components, not just the final filename.
    parts = [path, *path.parents]
    for item in reversed(parts):
        if item.is_symlink():
            raise Failure('symlink_directory_not_allowed')
        if not item.exists():
            item.mkdir(mode=0o700)
        if not item.is_dir():
            raise Failure('config_parent_not_directory')
    return path

def private_write(path, data, *, replace=True):
    path = pathlib.Path(path)
    directory = private_directory(path.parent)
    dfd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    temp = '.airtap-' + secrets.token_hex(12)
    try:
        try:
            info = os.stat(path.name, dir_fd=dfd, follow_symlinks=False)
        except FileNotFoundError:
            info = None
        if info is not None:
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
                raise Failure('unsafe_existing_file')
            if not replace:
                raise Failure('file_already_exists')
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=dfd)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if replace:
            os.replace(temp, path.name, src_dir_fd=dfd, dst_dir_fd=dfd)
        else:
            try:
                os.link(temp, path.name, src_dir_fd=dfd, dst_dir_fd=dfd, follow_symlinks=False)
            except FileExistsError:
                raise Failure('file_already_exists')
            os.unlink(temp, dir_fd=dfd)
        os.fsync(dfd)
    finally:
        try:
            os.unlink(temp, dir_fd=dfd)
        except FileNotFoundError:
            pass
        os.close(dfd)

def save_config(path, value, *, replace=True):
    private_write(path, json.dumps(value, separators=(',', ':')).encode(), replace=replace)

def load_config(path, local_test=False):
    path = pathlib.Path(path)
    if any(p.is_symlink() for p in [path, *path.parents]):
        raise Failure('symlink_config_not_allowed')
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
                raise Failure('config_must_be_owned_regular_file_mode_0600')
            raw = stream.read(8193)
        if len(raw) > 8192:
            raise Failure('config_too_large')
        value = json.loads(raw)
        value['url'] = origin(value['url'], local_test)
        if not TOKEN.fullmatch(value.get('controller_token', '')):
            raise ValueError()
        if 'credential_claim_id' in value and not task_hex(value['credential_claim_id']):
            raise ValueError()
        if 'restore_fresh_task_required' in value and (
                type(value['restore_fresh_task_required']) is not bool or 'credential_claim_id' not in value):
            raise ValueError()
        return value
    except Failure:
        raise
    except FileNotFoundError:
        raise Failure('config_missing', outcome='not_dispatched')
    except (OSError, ValueError, KeyError, TypeError):
        raise Failure('config_unavailable_or_invalid')

# BEGIN proxy transport (also embedded verbatim in the download bootstrap).
class ProxyConfigurationError(ValueError):
    """Fixed error codes only; never include environment values or credentials."""

# Known compromised eMudhra intermediate keys, including all cross-signs.
# Source: https://emudhra.we-have-a-problem.com/impacted-intermediates.json
# Snapshot: 2026-10-27. Block SPKI, not certificate fingerprints or one root.
# This supplements, never replaces, normal system chain/hostname validation.
COMPROMISED_SPKI_SHA256 = frozenset({
    '43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b',
    '49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f',
    '5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0',
    '63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8',
    '92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30',
    'e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41',
    'ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105',
    'ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6',
})


class TLSVerificationError(ssl.SSLCertVerificationError):
    """A local pre-request gate; only a fixed non-sensitive code is exposed."""
    def __init__(self, code):
        self.code = code
        super().__init__(1, code)


def _der_item(data, start, limit, expected):
    """Read one bounded DER TLV, without interpreting unneeded certificate data."""
    if start + 2 > limit or data[start] != expected:
        raise ValueError()
    length = data[start + 1]
    content = start + 2
    if length & 128:
        count = length & 127
        if not 1 <= count <= 4 or content + count > limit or data[content] == 0:
            raise ValueError()
        length = int.from_bytes(data[content:content + count], 'big')
        content += count
        if length < 128:
            raise ValueError()
    end = content + length
    if end > limit:
        raise ValueError()
    return content, end


def certificate_spki_sha256(der):
    """Hash the exact SPKI in an already system-validated X.509 certificate."""
    if not isinstance(der, bytes) or not 1 <= len(der) <= 65536:
        raise ValueError()
    outer, end = _der_item(der, 0, len(der), 0x30)
    if end != len(der):
        raise ValueError()
    cursor, tbs_end = _der_item(der, outer, end, 0x30)
    if cursor < tbs_end and der[cursor] == 0xa0:  # Optional explicit version.
        _, cursor = _der_item(der, cursor, tbs_end, 0xa0)
    # serial, signature algorithm, issuer, validity, subject.
    for tag in (0x02, 0x30, 0x30, 0x30, 0x30):
        _, cursor = _der_item(der, cursor, tbs_end, tag)
    spki_start = cursor
    algorithm, spki_end = _der_item(der, spki_start, tbs_end, 0x30)
    _, key_start = _der_item(der, algorithm, spki_end, 0x30)
    key, key_end = _der_item(der, key_start, spki_end, 0x03)
    if key_end != spki_end or key_end - key < 2 or der[key] > 7:
        raise ValueError()
    return hashlib.sha256(der[spki_start:spki_end]).hexdigest()


def verify_peer_chain(sock):
    """Reject known compromised keys before any HTTP request or bearer is sent.

    CPython 3.13+ exposes DER chains publicly. The isolated 3.10-3.12 adapter
    uses the same OpenSSL verified-chain API through CPython's _ssl object.
    Missing/incomplete/invalid chains fail closed; never fall back to a leaf.
    """
    try:
        if sock.context.verify_mode != ssl.CERT_REQUIRED or not sock.context.check_hostname:
            raise TLSVerificationError('tls_verification_required')
        getter = getattr(sock, 'get_verified_chain', None)
        legacy = not callable(getter)
        if legacy:
            getter = getattr(getattr(sock, '_sslobj', None), 'get_verified_chain', None)
            if sys.implementation.name != 'cpython' or not callable(getter):
                raise TLSVerificationError('tls_verified_chain_unavailable')
        chain = getter()
        if not isinstance(chain, (list, tuple)) or not 1 <= len(chain) <= 16:
            raise TLSVerificationError('tls_verified_chain_unavailable')
        for certificate in chain:
            if legacy:
                pem = certificate.public_bytes()
                if not isinstance(pem, str) or len(pem) > 100000:
                    raise ValueError()
                certificate = ssl.PEM_cert_to_DER_cert(pem)
            if certificate_spki_sha256(certificate) in COMPROMISED_SPKI_SHA256:
                raise TLSVerificationError('tls_compromised_certificate_key')
    except TLSVerificationError:
        raise
    except Exception:
        raise TLSVerificationError('tls_verified_chain_unavailable') from None


class VerifiedHTTPSConnection(http.client.HTTPSConnection):
    def connect(self):
        try:
            super().connect()
            verify_peer_chain(self.sock)
        except Exception:
            self.close()
            raise


def _proxy_host(value):
    if not value or len(value) > 253 or '%' in value:
        raise ValueError()
    try:
        return str(ipaddress.ip_address(value))
    except ValueError:
        host = value.removesuffix('.').lower()
        if not host or any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', label)
                           for label in host.split('.')):
            raise ValueError()
        return host

def _no_proxy_matches(hostname, port, value):
    """Comma-separated hosts/IPs, domain suffixes, optional ports, or '*'.

    Invalid entries, CIDR, and wildcard domains never bypass the proxy. IPs
    match exactly; a domain matches itself and subdomains on label boundaries.
    IPv6 may be bare, or bracketed with an optional port. No DNS is performed.
    """
    host = _proxy_host(hostname)
    try:
        host_ip = ipaddress.ip_address(host)
    except ValueError:
        host_ip = None
    for entry in value.split(','):
        entry = entry.strip(' \t')
        if entry == '*':
            return True
        try:
            if not entry or any(ord(c) < 33 or ord(c) > 126 for c in entry):
                continue
            entry_port = None
            if entry.startswith('['):
                matched = re.fullmatch(r'\[([^\]]+)\](?::([0-9]+))?', entry)
                if not matched:
                    continue
                candidate, entry_port = matched.groups()
                ipaddress.IPv6Address(candidate)
            elif entry.count(':') == 1:
                candidate, entry_port = entry.rsplit(':', 1)
                if not re.fullmatch(r'[0-9]+', entry_port):
                    continue
            else:
                candidate = entry
            if entry_port is not None and (not 1 <= int(entry_port) <= 65535 or int(entry_port) != port):
                continue
            candidate = _proxy_host(candidate.removeprefix('.'))
            try:
                candidate_ip = ipaddress.ip_address(candidate)
            except ValueError:
                candidate_ip = None
            if host_ip is not None or candidate_ip is not None:
                if host_ip is not None and host_ip == candidate_ip:
                    return True
            elif host == candidate or host.endswith('.' + candidate):
                return True
        except (ValueError, TypeError):
            continue
    return False

def proxy_for_https(hostname, port=443, environ=None):
    """Resolve only HTTPS_PROXY, with lowercase precedence including empty.

    NO_PROXY follows the same precedence. HTTP_PROXY and ALL_PROXY are not
    used. Only a plaintext HTTP CONNECT proxy is supported, never TLS-to-proxy.
    A configured proxy failure never causes an automatic direct connection.
    """
    environ = os.environ if environ is None else environ
    value = environ.get('https_proxy', environ.get('HTTPS_PROXY', ''))
    if not value:
        return None
    bypass = environ.get('no_proxy', environ.get('NO_PROXY', ''))
    if _no_proxy_matches(hostname, port, bypass):
        return None
    try:
        if (len(value) > 4096 or any(ord(c) < 33 or ord(c) > 126 for c in value)
                or '\\' in value or '?' in value or '#' in value):
            raise ValueError()
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme != 'http':
            raise ProxyConfigurationError('unsupported_https_proxy_scheme')
        if (not parsed.netloc or parsed.path not in ('', '/') or parsed.query or parsed.fragment
                or parsed.netloc.count('@') > 1):
            raise ValueError()
        host = _proxy_host(parsed.hostname)
        authority = parsed.netloc.rsplit('@', 1)[-1]
        if authority.endswith(':'):
            raise ValueError()
        proxy_port = parsed.port if parsed.port is not None else 80
        if not 1 <= proxy_port <= 65535:
            raise ValueError()
        headers = {}
        if parsed.username is not None:
            userinfo = parsed.netloc.rsplit('@', 1)[0]
            if re.search(r'%(?![0-9a-fA-F]{2})', userinfo):
                raise ValueError()
            username = urllib.parse.unquote_to_bytes(parsed.username)
            password = urllib.parse.unquote_to_bytes(parsed.password or '')
            if (not username or b':' in username or len(username) + len(password) > 1024
                    or any(c < 32 or c == 127 for c in username + password)):
                raise ValueError()
            headers['Proxy-Authorization'] = 'Basic ' + base64.b64encode(username + b':' + password).decode('ascii')
        return host, proxy_port, headers
    except ProxyConfigurationError:
        raise
    except (ValueError, TypeError, UnicodeError):
        raise ProxyConfigurationError('invalid_https_proxy') from None

def connection_for_url(parsed, timeout):
    if parsed.scheme != 'https':
        # HTTP exists only for explicitly allowed loopback tests; ignore proxy env.
        return http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    proxy = proxy_for_https(parsed.hostname, parsed.port or 443)
    context = ssl.create_default_context()
    if proxy is None:
        return VerifiedHTTPSConnection(parsed.hostname, parsed.port, timeout=timeout, context=context)
    host, port, headers = proxy
    conn = VerifiedHTTPSConnection(host, port, timeout=timeout, context=context)
    # HTTPSConnection wraps the established tunnel with target SNI and hostname
    # validation. Proxy credentials are confined to CONNECT, outside that TLS.
    conn.set_tunnel(parsed.hostname, parsed.port or 443, headers=headers)
    return conn
# END proxy transport.

def _request_child():
    """Secrets arrive via stdin. Parent enforces deadline including DNS and download."""
    request = json.loads(sys.stdin.buffer.read(MAX_COMMAND + 8193))
    parsed = urllib.parse.urlsplit(request['url'])
    conn = None
    http_attempted = False
    try:
        # Retry only connection establishment, before any application request is sent.
        # A stalled TLS/proxy handshake must not spend the entire operation deadline.
        for attempt in range(3):
            conn = connection_for_url(parsed, min(5, request['timeout']))
            try:
                conn.connect()
                if conn.sock is not None:
                    conn.sock.settimeout(request['timeout'])
                break
            except ssl.SSLCertVerificationError:
                # A definitive pre-request rejection is not a transient network failure.
                conn.close()
                raise
            except (OSError, TimeoutError):
                conn.close()
                if attempt == 2:
                    raise
        headers = {'Accept': 'application/json, image/jpeg', 'User-Agent': 'airtap-preview/' + VERSION}
        if request.get('token'):
            headers['Authorization'] = 'Bearer ' + request['token']
        body = None
        if request.get('body') is not None:
            headers['Content-Type'] = 'application/json'
            body = json_bytes(request['body'])
        # A public service gives each phone a canonical, non-secret base path.
        # Never drop that path: doing so could route a credential to another
        # phone namespace. The same connection/proxy/TLS checks still apply.
        http_attempted = True
        conn.request(request['method'], parsed.path.rstrip('/') + request['path'], body, headers)
        response = conn.getresponse()
        raw = response.read(MAX_RESPONSE + 1)
        if len(raw) > MAX_RESPONSE:
            result = {'transport_error': 'response_too_large'}
        else:
            result = {'status': response.status, 'content_type': response.getheader('Content-Type', '').split(';')[0],
                      'body': base64.b64encode(raw).decode()}
    except ProxyConfigurationError as error:
        result = {'transport_error': str(error)}
    except TLSVerificationError as error:
        result = {'transport_error': error.code, 'outcome': 'unknown' if http_attempted else 'not_dispatched'}
    except ssl.SSLCertVerificationError:
        result = {'transport_error': 'tls_certificate_verification_failed',
                  'outcome': 'unknown' if http_attempted else 'not_dispatched'}
    except Exception:
        result = {'transport_error': 'network_failure'}
    finally:
        if conn is not None:
            conn.close()
    sys.stdout.write(json.dumps(result))

def request(url, path, token=None, body=None, method='GET', timeout=25, local_test=False):
    url = origin(url, local_test)
    if body is not None and len(json_bytes(body)) > MAX_COMMAND:
        raise Failure('request_body_too_large', max_bytes=MAX_COMMAND, retry_action=False)
    started = time.monotonic()
    payload = dict(url=url, path=path, token=token, body=body, method=method, timeout=timeout)
    try:
        completed = subprocess.run([sys.executable, str(pathlib.Path(__file__).resolve()), '_request'],
                                   input=json_bytes(payload), capture_output=True, timeout=timeout,
                                   check=False)
    except subprocess.TimeoutExpired:
        raise Failure('total_deadline_exceeded', outcome='unknown', retry_action=False)
    try:
        result = json.loads(completed.stdout)
        if completed.returncode or result.get('transport_error'):
            raise Failure(result.get('transport_error', 'request_failed'),
                          outcome=result.get('outcome', 'unknown'), retry_action=False)
        raw = base64.b64decode(result.pop('body'), validate=True)
    except (ValueError, KeyError, TypeError):
        raise Failure('invalid_transport_response', outcome='unknown', retry_action=False)
    result['elapsed_ms'] = round((time.monotonic() - started) * 1000, 2)
    if result['content_type'] == 'application/json':
        try:
            body = json.loads(raw)
        except ValueError:
            raise Failure('invalid_json_response', outcome='unknown', retry_action=False)
        if not isinstance(body, dict):
            raise Failure('invalid_json_response', outcome='unknown', retry_action=False)
        result['data'] = body
    else:
        result['bytes'] = raw
    if result['status'] != 200:
        # This exact service response is emitted before command dispatch. Other
        # 429s (including opaque proxies/quota errors) do not have that guarantee.
        if result['status'] == 429 and result.get('data') == {'error': 'ingress_rate_limited'}:
            raise Failure('ingress_rate_limited', status=429, elapsed_ms=result['elapsed_ms'],
                          outcome='not_dispatched', retry_action=False, wait_seconds=60)
        # Only the bounded multi-AI error contract may cross this boundary.
        data = result.get('data', {})
        code = data.get('error')
        if (data.get('protocol_version') == 4 and isinstance(code, str)
                and TASK_ERRORS.get(code) == result['status']
                and set(data) <= {'protocol_version', 'error', 'admitted', 'retry_after_ms'}
                and ('admitted' not in data or data['admitted'] is False)):
            details = {}
            delay = data.get('retry_after_ms')
            if type(delay) is int and 100 <= delay <= 15000:
                details['retry_after_ms'] = delay
            raise Failure(code, status=result['status'], elapsed_ms=result['elapsed_ms'],
                          outcome='not_dispatched' if data.get('admitted') is False else 'unknown',
                          retry_action=False, **details)
        # Do not echo arbitrary relay content: it could reflect credentials.
        raise Failure('http_error', status=result['status'], elapsed_ms=result['elapsed_ms'],
                      outcome='unknown' if method == 'POST' else 'not_available', retry_action=False)
    return result

def json_bytes(value):
    try:
        return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
    except (ValueError, UnicodeError):
        raise Failure('invalid_unicode_or_json')

def valid_package(value):
    return (isinstance(value, str) and len(value) <= 200 and (value == 'android' or PACKAGE.fullmatch(value))
            and value != 'io.airtap.preview' and not value.startswith('io.airtap.preview.'))

def valid_observed_package(value):
    if valid_package(value.get('package')):
        return True
    return ('package' in value and value['package'] is None and value.get('target_mode') == 'visual_window_v1'
            and value.get('root_available') is False and type(value.get('window_id')) is int
            and value['window_id'] >= 0 and type(value.get('display_id')) is int and value['display_id'] == 0)


def valid_node(value):
    return isinstance(value, str) and NODE.fullmatch(value) and int(value) <= 2048

def open_url(value):
    """HTTP(S) browser navigation on the authorized phone, never a relay fetch.

    Keep the URL unchanged; LAN hosts, IP literals, ports and fragments are valid.
    Shared cases: relay/service/tests/fixtures/open-url-cases.json.
    """
    if (not isinstance(value, str) or not 1 <= len(value) <= 2048
            or any(ord(c) < 33 or ord(c) > 126 or c in '\\"<>^`{|}' for c in value)
            or re.search(r'%(?![0-9a-fA-F]{2})', value)
            or re.search(r'%(?:[01][0-9a-fA-F]|7[fF])', value)):
        raise Failure('invalid_open_url', retry_action=False)
    url = re.fullmatch(r'(https?)://([^/?#]+)(.*)', value, re.I)
    if not url or '[' in url[3] or ']' in url[3]:
        raise Failure('invalid_open_url', retry_action=False)
    authority = re.fullmatch(r'(?:\[([0-9a-fA-F:.]+)\]|([A-Za-z0-9.-]+))(?::([0-9]{1,5}))?', url[2])
    if not authority or (authority[3] is not None and not 1 <= int(authority[3]) <= 65535):
        raise Failure('invalid_open_url', retry_action=False)
    if authority[1] is not None:
        try: ipaddress.IPv6Address(authority[1])
        except ValueError: raise Failure('invalid_open_url', retry_action=False)
    else:
        host = authority[2]
        labels = host.removesuffix('.').split('.')
        valid = len(host) <= 253 and all(re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?', label) for label in labels)
        if re.fullmatch(r'(?:[0-9]+|0x[0-9a-f]+)', labels[-1], re.I):
            valid = valid and not host.endswith('.') and len(labels) == 4 and all(
                re.fullmatch(r'0|[1-9][0-9]{0,2}', label) and int(label) <= 255 for label in labels)
        if not valid: raise Failure('invalid_open_url', retry_action=False)
    return value

def safe_scope(data):
    if data.get('persistent') is not True or data.get('all_apps') is not True or data.get('allow_control') is not True:
        raise Failure('complete_phone_approval_required')
    sequence = data.get('command_sequence', 0)
    if type(sequence) is not int or not 0 <= sequence <= 9007199254740991:
        raise Failure('invalid_sequence')
    return {'persistent': True, 'all_apps': True, 'allow_control': True, 'allowed_packages': [], 'command_sequence': sequence}

def remember_approval(args, config, status):
    check_credential_identity(config, status)
    if 'session_expires_at' not in status or status['session_expires_at'] is not None:
        raise Failure('invalid_pairing_response')
    scope = safe_scope(status)
    scope['command_sequence'] = max(scope['command_sequence'], config.get('command_sequence', 0))
    config.update(scope)
    config.update(state='approved', session_expires_at=None, allow_tap=False)
    for key in ('mode', 'claim_id', 'lease_required', 'lease_ready'):
        if key in status: config[key] = status[key]
    save_config(args.config, config)

def redact(value, token):
    if isinstance(value, str):
        return value.replace(token, '[REDACTED]')
    if isinstance(value, list):
        return [redact(item, token) for item in value]
    if isinstance(value, dict):
        return {redact(key, token): redact(item, token) for key, item in value.items()}
    return value

def safe_pair_status(data):
    if not isinstance(data, dict) or data.get('protocol_version') != 4:
        raise Failure('protocol_v4_required')
    state = data.get('state')
    if state not in ('idle', 'waiting', 'pending', 'approved', 'revoked'):
        raise Failure('invalid_pairing_response')
    result = {'state': state, 'protocol_version': 4}
    if 'mode' in data:
        if data['mode'] != 'multi': raise Failure('invalid_pairing_response')
        result['mode'] = 'multi'
    for key in ('lease_required', 'lease_ready'):
        if key in data:
            if type(data[key]) is not bool: raise Failure('invalid_pairing_response')
            result[key] = data[key]
    for key, pattern in [('claim_id', TICKET), ('verification_code', re.compile(r'^\d{6}$'))]:
        value = data.get(key)
        if value is not None:
            if not isinstance(value, str) or not pattern.fullmatch(value):
                raise Failure('invalid_pairing_response')
            result[key] = value
    if 'pairing_expires_at' in data:
        if type(data['pairing_expires_at']) is not int:
            raise Failure('invalid_pairing_response')
        result['pairing_expires_at'] = data['pairing_expires_at']
    if state == 'approved':
        if 'session_expires_at' not in data or data['session_expires_at'] is not None:
            raise Failure('invalid_pairing_response')
        result.update(safe_scope(data), session_expires_at=None, allow_tap=False)
    return result

def poll_pair(args, config):
    deadline = time.monotonic() + PAIRING_TTL_SECONDS
    while True:
        result = request(config['url'], '/v1/pair/status', config['controller_token'], timeout=args.timeout,
                         local_test=args.allow_local_http)
        check_credential_identity(config, result['data'])
        status = safe_pair_status(result['data'])
        if status['state'] == 'approved':
            remember_approval(args, config, status)
            return {'ok': True, **status}
        if status['state'] == 'revoked':
            raise Failure('pairing_revoked')
        if not args.wait:
            return {'ok': True, **status, 'instruction': 'Compare the code on the phone, then approve there.'}
        if time.monotonic() >= deadline or time.time() >= status.get('pairing_expires_at', 0):
            raise Failure('pairing_wait_expired')
        time.sleep(2)

def check_credential_identity(config, data, *, require_mode=False):
    """A restored credential stays bound to its original approved claim."""
    expected = config.get('credential_claim_id')
    if expected is None:
        return
    if config.get('state') == 'revoked':
        raise Failure('pairing_revoked')
    if (not isinstance(data, dict) or not task_hex(expected) or data.get('claim_id') != expected
            or require_mode and data.get('mode') != 'multi'):
        raise Failure('credential_identity_mismatch', retry_action=False)
    if not task_integer(data.get('command_sequence'), 0):
        raise Failure('invalid_sequence')


def checked_status(config, result):
    data = result.get('data', {})
    if (not isinstance(data, dict) or data.get('protocol_version') != 4
            or type(data.get('deviceConnected')) is not bool
            or type(data.get('pending')) is not int or data['pending'] not in (0, 1)):
        raise Failure('invalid_status_response')
    check_credential_identity(config, data, require_mode=True)
    status = safe_pair_status({**data, 'state': 'approved'})
    for key in ('deviceConnected', 'pending', 'connection_id', 'command_budget_remaining'):
        status[key] = data.get(key)
    if data.get('mode') == 'multi':
        # Validate the task response before changing any saved connection state.
        status['task'] = safe_task_status(data.get('task'), result.get('elapsed_ms'))
    return status


def status_request(args, config, timeout=None):
    result = request(config['url'], '/v1/status', config['controller_token'], timeout=timeout or args.timeout,
                     local_test=args.allow_local_http)
    status = checked_status(config, result)
    if config.get('connection_id') != status['connection_id']:
        config.pop('snapshot', None); config.pop('observation', None)
        if config.get('task_lease'): clear_task_views(config)
    config['connection_id'] = status['connection_id']
    if status.get('mode') == 'multi':
        if config.get('restore_fresh_task_required'):
            # A GET may report a prior runtime's lease, including one acquired
            # after restore. Only our new explicit acquire can lift this barrier.
            status['restore_fresh_task_required'] = True
        else:
            status['task'] = reconcile_task(args, config, status['task'], result.get('elapsed_ms'))
    remember_approval(args, config, status)
    return {'ok': True, **status, 'elapsed_ms': result['elapsed_ms']}


def restore_identity(args):
    if not args.config.is_absolute():
        raise Failure('absolute_config_path_required')
    try:
        args.config.lstat()
    except FileNotFoundError:
        pass
    else:
        raise Failure('config_exists_reuse_connection_or_choose_new_path')
    parent = args.config.parent.stat()
    if parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
        raise Failure('restore_parent_must_be_private_0700')

    def unique_fields(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError()
            value[key] = item
        return value

    try:
        raw = sys.stdin.read(8193)
        if len(raw.encode('utf-8')) > 8192:
            raise ValueError()
        credential = json.loads(raw, object_pairs_hook=unique_fields)
        if (not isinstance(credential, dict) or set(credential) != {'website', 'username', 'password'}
                or not all(isinstance(value, str) for value in credential.values())
                or not task_hex(credential['username']) or not TOKEN.fullmatch(credential['password'])):
            raise ValueError()
    except (ValueError, TypeError, UnicodeError):
        raise Failure('invalid_restore_credential', retry_action=False)
    config = {'url': origin(credential['website'], args.allow_local_http),
              'controller_token': credential['password'], 'credential_claim_id': credential['username'],
              'restore_fresh_task_required': True}
    pair = request(config['url'], '/v1/pair/status', config['controller_token'],
                   timeout=args.timeout, local_test=args.allow_local_http)
    paired = safe_pair_status(pair.get('data'))
    if paired['state'] != 'approved':
        raise Failure('approval_required', retry_action=False)
    check_credential_identity(config, pair['data'])
    result = request(config['url'], '/v1/status', config['controller_token'],
                     timeout=args.timeout, local_test=args.allow_local_http)
    if isinstance(result.get('data'), dict) and result['data'].get('mode') != 'multi':
        raise Failure('restore_mode_unsupported', retry_action=False)
    status = checked_status(config, result)
    if status['command_sequence'] < paired['command_sequence']:
        raise Failure('identity_sequence_regressed', retry_action=False)
    config.update(safe_scope(status), state='approved', session_expires_at=None, allow_tap=False,
                  mode='multi', claim_id=status['claim_id'], task_needs_observation=True)
    for key in ('lease_required', 'lease_ready'):
        if type(status.get(key)) is not bool:
            raise Failure('invalid_status_response')
        config[key] = status[key]
    save_config(args.config, config, replace=False)
    return {'ok': True, 'state': 'approved', 'mode': 'multi', 'identity_restored': True,
            'restore_fresh_task_required': True, 'deviceConnected': status['deviceConnected'],
            'pending': status['pending'],
            'instruction': 'Original identity restored, not a phone task. Stop the previous runner. Run task-acquire explicitly for a new task, then observe. Do not replay unknown prior actions.'}


def require_restored_task(config):
    if config.get('restore_fresh_task_required'):
        raise Failure('restored_identity_requires_fresh_task', retry_action=False,
                      instruction='Run task-acquire explicitly for a new task. Wait if a prior task is active; do not renew or release it. Keep the original restored identity.')

def clear_task_views(config):
    config.pop('snapshot', None)
    config.pop('observation', None)
    config['task_needs_observation'] = True


def invalidate_task(config, state):
    if config.get('task_lease'):
        config['task_lease']['state'] = state
        clear_task_views(config)


def task_integer(value, minimum=1):
    return type(value) is int and minimum <= value <= MAX_INTEGER


def task_hex(value):
    return isinstance(value, str) and TICKET.fullmatch(value) is not None


def same_lease(a, b):
    return all(a.get(key) == b.get(key) for key in ('task_id', 'lease_id', 'lease_epoch'))


def task_live(lease):
    now = time.time()
    return (lease.get('state') == 'ready' and type(lease.get('recorded_at')) in (int, float)
            and type(lease.get('local_expires_at')) in (int, float)
            and lease['recorded_at'] <= now < lease['local_expires_at'])


def safe_task_status(value, elapsed_ms=0):
    if not isinstance(value, dict) or value.get('state') not in ('idle', 'busy', 'fencing', 'ready'):
        raise Failure('invalid_task_status')
    if type(elapsed_ms) not in (int, float) or not math.isfinite(elapsed_ms) or elapsed_ms < 0:
        raise Failure('invalid_task_status')
    result = {key: value.get(key) for key in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms', 'expires_in_ms')}
    if result['state'] in ('idle', 'busy'):
        if any(result[key] is not None for key in result if key != 'state'):
            raise Failure('invalid_task_status')
    elif result['state'] == 'ready':
        if (not task_hex(result['task_id']) or not task_hex(result['lease_id'])
                or not task_integer(result['lease_epoch']) or not task_integer(result['expires_at_ms'])
                or not task_integer(result['expires_in_ms'], 0) or result['expires_in_ms'] > 120000):
            raise Failure('invalid_task_status')
    elif (result['lease_id'] is not None or (result['task_id'] is not None and not task_hex(result['task_id']))
          or (result['lease_epoch'] is not None and not task_integer(result['lease_epoch']))
          or (result['expires_at_ms'] is not None and not task_integer(result['expires_at_ms']))
          or (result['expires_in_ms'] is not None and
              (not task_integer(result['expires_in_ms'], 0) or result['expires_in_ms'] > 120000))):
        raise Failure('invalid_task_status')
    if 'renewal_sequence' in value:
        if result['state'] in ('idle', 'busy'):
            if value['renewal_sequence'] is not None: raise Failure('invalid_task_status')
        elif not task_integer(value['renewal_sequence'], 0): raise Failure('invalid_task_status')
        else: result['renewal_sequence'] = value['renewal_sequence']
    return result


def reconcile_task(args, config, value, elapsed_ms=0):
    result = safe_task_status(value, elapsed_ms)
    previous = config.get('task_lease', {})
    if result['state'] == 'ready':
        # Status is scoped to the authenticated controller; never adopt busy metadata.
        if previous.get('state') in ('releasing', 'closed', 'expired') and same_lease(previous, result):
            pending = previous.get('pending_renewal')
            confirmed_pending = (previous.get('state') == 'expired' and isinstance(pending, dict)
                and set(pending) == {'renewal_id', 'renewal_sequence'}
                and task_hex(pending.get('renewal_id')) and task_integer(pending.get('renewal_sequence'))
                and result.get('renewal_sequence') == pending['renewal_sequence']
                and result['expires_in_ms'] > elapsed_ms)
            if not confirmed_pending:
                return result  # Status cannot undo release or revive a terminal lease.
            # Only recover a remotely confirmed, previously unresolved renewal.
            # Keep its intent: ensure_task must retry that exact renewal before
            # any new phone command. Never replay the refused phone command.
        changed = not same_lease(previous, result)
        now = time.time()
        # Server and client wall clocks need not agree. Subtract the whole HTTP
        # round trip conservatively; the absolute server expiry is metadata only.
        remaining = (result['expires_in_ms'] - elapsed_ms) / 1000
        if not changed and not previous.get('pending_renewal') and previous.get('local_expires_at') is not None:
            # A status read must not extend a locally known lease deadline.
            remaining = min(remaining, previous['local_expires_at'] - now)
            if now < previous.get('recorded_at', now):
                remaining = 0  # Do not reset the clock-regression guard via status.
        lease = {**result, 'recorded_at': now, 'local_expires_at': now + max(0, remaining)}
        if 'renewal_sequence' not in lease:
            lease['renewal_sequence'] = previous.get('renewal_sequence') if not changed else None
        if not changed:
            if 'pending_renewal' in previous: lease['pending_renewal'] = previous['pending_renewal']
            if task_integer(previous.get('renewal_sequence'), 0):
                lease['renewal_sequence'] = max(previous['renewal_sequence'], lease.get('renewal_sequence') or 0)
        if remaining <= 0:
            lease['state'] = 'expired'
        config['task_lease'] = lease
        if changed or previous.get('state') != 'ready' or remaining <= 0:
            clear_task_views(config)
    elif previous:
        # A scoped idle/busy status proves the old lease no longer owns this
        # device, even after its bounded release receipt has been evicted.
        if previous.get('state') == 'releasing' and result['state'] in ('idle', 'busy'):
            invalidate_task(config, 'closed')
        # Otherwise keep the acquire/release intent for explicit recovery.
        elif previous.get('state') not in ('acquiring', 'releasing', 'closed', 'expired'):
            invalidate_task(config, 'lost')
        clear_task_views(config)
    save_config(args.config, config)
    return result


def checked_task_ready(result, expected):
    data = result.get('data', {})
    if (data.get('protocol_version') != 4 or data.get('ok') is not True or data.get('state') != 'ready'
            or not task_hex(data.get('task_id')) or not task_hex(data.get('lease_id'))
            or not task_integer(data.get('lease_epoch')) or not task_integer(data.get('expires_at_ms'))
            or not task_integer(data.get('expires_in_ms')) or data['expires_in_ms'] > 120000
            or data['task_id'] != expected['task_id']):
        raise Failure('invalid_task_response', retry_action=False)
    if 'lease_id' in expected and not same_lease(expected, data):
        raise Failure('invalid_task_response', retry_action=False)
    elapsed = result.get('elapsed_ms')
    if type(elapsed) not in (int, float) or not math.isfinite(elapsed) or elapsed < 0:
        raise Failure('invalid_task_response', retry_action=False)
    if 'renewal_sequence' in data and not task_integer(data['renewal_sequence'], 0):
        raise Failure('invalid_task_response', retry_action=False)
    now = time.time()
    return {key: data[key] for key in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')} | {
        'recorded_at': now, 'local_expires_at': now + max(0, data['expires_in_ms'] - elapsed) / 1000,
        'renewal_sequence': data.get('renewal_sequence', 0)}


def task_call(args, config, operation, body, timeout=None):
    return request(config['url'], '/v1/tasks/' + operation, config['controller_token'], body=body,
                   method='POST', timeout=timeout or args.timeout, local_test=args.allow_local_http)


def ready_task_output(lease):
    return {'ok': True, **{key: lease[key] for key in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')},
            'expires_in_ms': max(0, int((lease['local_expires_at'] - time.time()) * 1000)),
            'instruction': 'Keep this task through final result verification, then task-release. Observe/read-tree before actions on a new task. Unknown actions must not be replayed.'}


def acquire_task(args, config):
    requested = getattr(args, 'task_id', None)
    if requested is not None and not task_hex(requested):
        raise Failure('invalid_task_id', retry_action=False)
    # Read before creating an intent, including when local task state was lost.
    status = status_request(args, config)
    if config.get('mode') != 'multi':
        raise Failure('task_leases_not_supported', instruction='Keep the existing single-AI connection; do not re-pair to enable task leases.')
    previous = config.get('task_lease', {})
    restoring = config.get('restore_fresh_task_required') is True
    if restoring:
        # A fresh identity restore never imports the old runtime's task ID.
        # Once sent, however, our own acquire intent must survive a lost reply.
        retrying = previous.get('state') == 'acquiring' and task_hex(previous.get('task_id'))
        if requested is not None and (not retrying or requested != previous['task_id']):
            raise Failure('restore_requires_generated_task_id', retry_action=False)
        if previous and (previous.get('state') not in ('acquiring', 'closed', 'expired')
                         or previous.get('state') == 'acquiring' and not retrying):
            raise Failure('invalid_restored_task_state', retry_action=False)
    if previous.get('state') == 'releasing':
        raise Failure('task_release_pending', instruction='Resolve task-release using the saved identity before starting another task.')
    if previous.get('state') == 'ready' and task_live(previous):
        if requested is not None and requested != previous['task_id']:
            raise Failure('task_already_active', retry_action=False)
        return ready_task_output(previous)
    if previous.get('state') == 'acquiring':
        if requested is not None and requested != previous['task_id']:
            raise Failure('task_acquire_pending', retry_action=False)
        task_id = previous['task_id']
    else:
        if requested is not None and requested == previous.get('task_id'):
            raise Failure('task_closed', retry_action=False)
        task_id = requested or secrets.token_hex(16)
        if restoring and task_id in (previous.get('task_id'), status.get('task', {}).get('task_id')):
            raise Failure('fresh_task_identity_collision', retry_action=False)
    config['task_lease'] = {'state': 'acquiring', 'task_id': task_id}
    clear_task_views(config)
    save_config(args.config, config)  # The same intent survives a lost response/process exit.
    try:
        result = task_call(args, config, 'acquire', {'task_id': task_id})
        lease = checked_task_ready(result, {'task_id': task_id})
    except Failure as failure:
        if failure.result['error'] in ('task_closed', 'lease_expired'):
            invalidate_task(config, 'closed'); save_config(args.config, config)
        raise
    if restoring and not task_live(lease):
        config['task_lease'] = {**lease, 'state': 'expired'}
        save_config(args.config, config)
        raise Failure('lease_expired', retry_action=False)
    ready = {**config, 'task_lease': lease, 'lease_required': True, 'lease_ready': True}
    if restoring:
        ready.pop('restore_fresh_task_required', None)
        clear_task_views(ready)
    # Failure to save leaves the durable acquire intent and restore barrier.
    save_config(args.config, ready)
    config.clear(); config.update(ready)
    if not task_live(lease): raise Failure('lease_expired', retry_action=False)
    return ready_task_output(lease)


def renew_task(args, config, timeout=None):
    require_restored_task(config)
    lease = config.get('task_lease', {})
    if lease.get('state') == 'releasing':
        raise Failure('task_release_pending', retry_action=False,
                      instruction='Resolve task-release using the saved identity before renewing or starting another task.')
    if lease.get('state') == 'uncertain':
        status_request(args, config, timeout)
        lease = config.get('task_lease', {})
    if lease.get('state') != 'ready':
        raise Failure('task_acquire_required', retry_action=False,
                      instruction='Run task-acquire explicitly, then observe again. Do not replay the previous action.')
    if not task_live(lease) and not lease.get('pending_renewal'):
        invalidate_task(config, 'expired'); save_config(args.config, config)
        raise Failure('task_acquire_required', instruction='Run task-acquire explicitly, then observe again. Do not replay the previous action.')
    pending = lease.get('pending_renewal')
    if pending is None:
        sequence = lease.get('renewal_sequence', 0)
        if sequence is None:
            raise Failure('renewal_sequence_unknown', instruction='The recovered task lacks its renewal sequence. Do not guess it; check updated task status or release this task before starting a new task and observing again.')
        if not task_integer(sequence, 0) or sequence == MAX_INTEGER: raise Failure('renewal_sequence_exhausted')
        pending = {'renewal_id': secrets.token_hex(16), 'renewal_sequence': sequence + 1}
        lease['pending_renewal'] = pending
        save_config(args.config, config)
    if (not isinstance(pending, dict) or not task_hex(pending.get('renewal_id'))
            or not task_integer(pending.get('renewal_sequence'))):
        raise Failure('invalid_saved_renewal')
    body = {key: lease[key] for key in ('task_id', 'lease_id', 'lease_epoch')}
    body.update(pending)
    try:
        result = task_call(args, config, 'renew', body, timeout)
        renewed = checked_task_ready(result, lease)
        if 'renewal_sequence' in result['data'] and result['data']['renewal_sequence'] != pending['renewal_sequence']:
            raise Failure('invalid_task_response', retry_action=False)
    except Failure as failure:
        if failure.result['error'] in ('lease_expired', 'lease_mismatch', 'task_closed', 'device_disconnected'):
            invalidate_task(config, 'lost')
        elif failure.result.get('outcome') == 'unknown':
            clear_task_views(config)
        save_config(args.config, config)
        raise
    config['task_lease'] = {**renewed, 'renewal_sequence': pending['renewal_sequence']}
    save_config(args.config, config)
    if not task_live(config['task_lease']):
        invalidate_task(config, 'expired'); save_config(args.config, config)
        raise Failure('lease_expired', retry_action=False)
    return ready_task_output(config['task_lease'])


def release_task(args, config):
    require_restored_task(config)
    lease = config.get('task_lease', {})
    if lease.get('state') == 'closed': return {'ok': True, 'state': 'released'}
    if not all(key in lease for key in ('task_id', 'lease_id', 'lease_epoch')):
        status_request(args, config)
        lease = config.get('task_lease', {})
    if not task_hex(lease.get('task_id')) or not task_hex(lease.get('lease_id')) or not task_integer(lease.get('lease_epoch')):
        raise Failure('task_identity_unavailable', instruction='Check status. A pending acquire cannot be reported released without a matching lease.')
    body = {key: lease[key] for key in ('task_id', 'lease_id', 'lease_epoch')}
    lease['state'] = 'releasing'
    clear_task_views(config)
    save_config(args.config, config)
    result = task_call(args, config, 'release', body)
    data = result.get('data', {})
    if data.get('protocol_version') != 4 or data.get('ok') is not True or data.get('state') != 'released':
        raise Failure('task_release_not_confirmed', retry_action=False)
    invalidate_task(config, 'closed'); save_config(args.config, config)
    return {'ok': True, 'state': 'released'}


def task_command(args, config):
    if args.command == 'task-acquire': return acquire_task(args, config)
    if args.command == 'task-renew': return renew_task(args, config)
    return release_task(args, config)


def ensure_task(args, config, action, timeout=None):
    lease = config.get('task_lease', {})
    if lease.get('state') == 'uncertain':
        raise Failure('task_status_required', instruction='Check status to reconcile the existing task, then observe. Never replay an uncertain action.')
    if not task_live(lease):
        if lease.get('state') == 'ready':
            invalidate_task(config, 'expired'); save_config(args.config, config)
        raise Failure('task_acquire_required', instruction='Run task-acquire once for the whole task, then observe. Do not acquire/release around each command.')
    if lease.get('pending_renewal') or lease['local_expires_at'] - time.time() < 45:
        renew_task(args, config, timeout)
    if config.get('task_needs_observation') and action not in TASK_BEFORE_OBSERVE:
        raise Failure('fresh_task_observation_required', retry_action=False,
                      instruction='Observe or read-tree in the current task before selecting a new action; do not replay an old target.')


def command_request(args, config, body, timeout=None):
    if body['action'] in ('event_status', 'event_read'):
        return _command_request(args, config, body, timeout)
    require_restored_task(config)
    if body['action'] in CONNECTION_ACTIONS and body['action'] != 'connection_invite' and config.get('mode') != 'multi':
        raise Failure('multi_api_required', retry_action=False)
    leased = (body['action'] in CONNECTION_ACTIONS and config.get('mode') == 'multi') or config.get('lease_required') is True or 'task_lease' in config
    if not leased:
        return _command_request(args, config, body, timeout)
    try:
        ensure_task(args, config, body['action'], timeout)
        lease = config['task_lease']
        body = {**body, 'lease_id': lease['lease_id'], 'lease_epoch': lease['lease_epoch']}
        return _command_request(args, config, body, timeout)
    except Failure as failure:
        code = failure.result['error']
        if code in ('lease_expired', 'lease_mismatch', 'lease_required', 'task_closed', 'device_disconnected'):
            invalidate_task(config, 'lost')
        elif failure.result.get('outcome') == 'unknown':
            clear_task_views(config)
            if config.get('task_lease', {}).get('state') == 'ready':
                config['task_lease']['state'] = 'uncertain'
        elif code == 'lease_renew_required':
            clear_task_views(config)
        save_config(args.config, config)
        raise

def _command_request(args, config, body, timeout=None):
    sequence = config.get('command_sequence', 0) + 1
    if sequence > 9007199254740991:
        raise Failure('sequence_exhausted')
    body = {**body, 'sequence': sequence}
    if len(json_bytes(body)) > (MAX_COMMAND if body['action']=='file_upload_chunk' else 8192): raise Failure('request_body_too_large', retry_action=False)
    config['command_sequence'] = sequence
    save_config(args.config, config)  # Consume before sending; unknown results are never replayed.
    result = request(config['url'], '/v1/command', config['controller_token'], body=body,
                     method='POST', timeout=timeout or args.timeout, local_test=args.allow_local_http)
    if result.get('data') is not None:
        data = result['data']
        if data.get('id') != body['id'] or type(data.get('ok')) is not bool:
            raise Failure('invalid_command_response', outcome='unknown', retry_action=False)
        if not data['ok']:
            if body['action'] in ('read_tree', 'read_tree_page', 'read_node_text'):
                config.pop('snapshot', None); save_config(args.config, config)
            error = data.get('error')
            code = error.get('code', 'device_rejected') if isinstance(error, dict) else 'device_rejected'
            if not isinstance(code, str) or not re.fullmatch(r'[a-z_]{1,64}', code):
                code = 'device_rejected'
            if code.endswith('_outcome_unknown') or (code == 'launch_unconfirmed' and body['action'] in ('launch', 'open_url')):
                raise Failure(code, request_id=body['id'], elapsed_ms=result['elapsed_ms'],
                              outcome='unknown', retry_action=False)
            next_step = error.get('next_step') if isinstance(error, dict) else None
            if isinstance(next_step, str) and len(next_step) <= 500 and not any(ord(c) < 32 for c in next_step):
                raise Failure(code, request_id=body['id'], elapsed_ms=result['elapsed_ms'], retry_action=False,
                              instruction=redact(next_step, config['controller_token']))
            raise Failure(code, request_id=body['id'], elapsed_ms=result['elapsed_ms'], retry_action=False)
        if body['action'] == 'screenshot':
            raise Failure('invalid_screenshot_response')
        value = data.get('result')
        if not isinstance(value, dict):
            raise Failure('invalid_command_response', outcome='unknown', retry_action=False)
        if body['action'].replace('_', '-') in CONTROL_COMMANDS and value.get('accepted') is not True:
            raise Failure('action_not_confirmed', request_id=body['id'], outcome='unknown', retry_action=False)
        if body['action'] == 'read_node_text':
            text = value.get('text'); offset = value.get('offset_utf16'); size = value.get('length_utf16')
            total = value.get('total_length_utf16'); next_offset = value.get('next_offset_utf16')
            try: actual = len(text.encode('utf-16-le')) // 2 if isinstance(text, str) else -1
            except UnicodeError: actual = -1
            if (any(value.get(key) != body[key] for key in ('package', 'snapshot_id', 'node_id', 'field'))
                    or type(offset) is not int or offset != body['offset'] or type(size) is not int or size != actual
                    or not 0 <= size <= body['length'] or type(total) is not int or not offset + size <= total <= 2147483647
                    or (offset + size < total and (type(next_offset) is not int or next_offset != offset + size or not size))
                    or (offset + size == total and next_offset is not None)
                    or type(value.get('eof')) is not bool or value['eof'] != (next_offset is None)
                    or value.get('offset_unit') != 'utf16'):
                config.pop('snapshot', None); save_config(args.config, config)
                raise Failure('invalid_text_response')
        if body['action'] == 'observe':
            try:
                raw = base64.b64decode(value.pop('image_base64'), validate=True)
                observation = value['observation_id']
                if not TICKET.fullmatch(observation) or not valid_observed_package(value) or value.get('coordinate_space') != 'normalized_image':
                    raise ValueError()
                if not raw.startswith(b'\xff\xd8') or not raw.endswith(b'\xff\xd9') or len(raw) > 360000:
                    raise ValueError()
                if type(value.get('expires_in_ms')) is not int or not 0 < value['expires_in_ms'] <= 30000:
                    raise ValueError()
            except (ValueError, KeyError, TypeError):
                raise Failure('invalid_observation')
            output = getattr(args, 'output', None) or args.config.parent / 'observations' / (body['id'] + '.jpg')
            private_write(output, raw, replace=False)
            config['observation'] = {**value, 'issued_at': time.time(), 'expires_at': time.time() + max(0, value['expires_in_ms'] - result['elapsed_ms']) / 1000}
            if config.get('task_lease'): config['task_needs_observation'] = False
            save_config(args.config, config)
            value.update(output=str(output.absolute()), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        return {'ok': True, 'request_id': body['id'], 'elapsed_ms': result['elapsed_ms'],
                'result': redact(value, config['controller_token'])}
    raw = result.get('bytes', b'')
    if (body['action'] != 'screenshot' or result['content_type'] != 'image/jpeg'
            or not raw.startswith(b'\xff\xd8') or not raw.endswith(b'\xff\xd9')):
        raise Failure('invalid_image_response')
    private_write(args.output, raw, replace=False)
    return {'ok': True, 'request_id': body['id'], 'elapsed_ms': result['elapsed_ms'], 'bytes': len(raw),
            'sha256': hashlib.sha256(raw).hexdigest(), 'output': str(args.output.absolute())}

def screen_readiness(result):
    """Report task readiness separately from the screen's current state.

    A device advertising automatic preparation can accept a task while its screen
    is off. A ping never wakes it; preparation happens with the next UI command.
    Older devices may only report device_unlocked. Preserve connection-only
    readiness only when neither screen-state field is available.
    """
    if 'screen_state' not in result:
        unlocked = result.get('device_unlocked')
        if 'device_unlocked' in result and type(unlocked) is not bool:
            raise Failure('invalid_screen_state', retry_action=False)
        readiness = {'ready': unlocked is not False, 'connection_ready': True, 'screen_state': 'unknown',
                     'screen_ready': unlocked, 'will_prepare_screen': False, 'needs_system_authentication': None}
        if unlocked is False:
            readiness['instruction'] = 'Pairing is retained, but this older device reports that its screen is not ready. Update Airtap for automatic screen preparation or restore screen readiness; do not pair again. The device has not reported whether system authentication is required.'
        return readiness
    state = result['screen_state']
    if state not in ('ready', 'screen_off', 'dismissible_keyguard', 'authentication_required', 'unavailable'):
        raise Failure('invalid_screen_state', retry_action=False)
    auto_wake = result.get('auto_wake', False)
    if type(auto_wake) is not bool:
        raise Failure('invalid_screen_state', retry_action=False)
    will_prepare = state in ('screen_off', 'dismissible_keyguard') and auto_wake
    readiness = {'ready': state == 'ready' or will_prepare, 'connection_ready': True,
                 'screen_state': state, 'screen_ready': state == 'ready', 'will_prepare_screen': will_prepare,
                 'needs_system_authentication': None if state == 'unavailable' else state == 'authentication_required'}
    if state == 'authentication_required':
        readiness['instruction'] = 'Pairing is retained. Android requires system authentication before screen operations can continue; do not pair again.'
    elif state == 'unavailable':
        readiness['instruction'] = 'Pairing is retained, but the device could not determine its screen state. Check device recovery; do not pair again.'
    elif will_prepare:
        readiness['instruction'] = 'Continue the requested task. The next UI command will prepare the screen automatically; this ping has not woken it.'
    elif state != 'ready':
        readiness['instruction'] = 'Pairing is retained. This device does not advertise automatic screen preparation; restore screen readiness before continuing.'
    return readiness


def wait_ready(args, config):
    deadline = time.monotonic() + args.wait_seconds
    attempts = 0
    transient = {'network_failure', 'total_deadline_exceeded', 'request_failed'}
    while time.monotonic() < deadline:
        attempts += 1
        try:
            status = status_request(args, config, min(args.timeout, deadline - time.monotonic()))
            if status['deviceConnected'] and status['pending'] == 0:
                if status.get('command_budget_remaining') == 0:
                    raise Failure('preview_command_budget_exhausted')
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                ping = command_request(args, config, {'id': str(uuid.uuid4()), 'action': 'ping'}, min(args.timeout, remaining))
                if ping['result'].get('authorized') is not True:
                    raise Failure('phone_authorization_not_confirmed')
                return {'ok': True, **screen_readiness(ping['result']), 'attempts': attempts, 'status': status, 'ping': ping}
        except Failure as failure:
            code = failure.result['error']
            admission_wait = code == 'ingress_rate_limited' and failure.result.get('outcome') == 'not_dispatched'
            if code not in transient and not admission_wait and not (code == 'http_error' and failure.result.get('status') in (429, 502, 503, 504)):
                raise
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(2, remaining))
    raise Failure('ready_wait_expired', attempts=attempts, retry_action=False)

def remember_snapshot(args, config, value, package, request_started, continuation=False):
    nodes = value.get('nodes')
    expiry = value.get('expires_in_ms')
    snapshot = value.get('snapshot_id')
    if (not valid_package(package) or value.get('package') != package or not isinstance(snapshot, str) or not TICKET.fullmatch(snapshot)
            or type(expiry) is not int or not 0 < expiry <= 30000 or not isinstance(nodes, list) or len(nodes) > 128):
        raise Failure('invalid_snapshot_response')
    ids = [node.get('node_id') if isinstance(node, dict) else None for node in nodes]
    if any(not valid_node(node) for node in ids) or len(set(ids)) != len(ids):
        raise Failure('invalid_snapshot_response')
    if continuation or 'next_offset' in value:
        offset, total, returned = value.get('offset'), value.get('total_nodes'), value.get('returned_nodes')
        inspected, next_offset = value.get('total_inspected_nodes'), value.get('next_offset')
        if (type(offset) is not int or type(total) is not int or type(returned) is not int or type(inspected) is not int
                or not 0 <= offset <= total <= inspected <= 2048 or returned != len(ids) or offset + returned > total
                or value.get('traversal_complete') is not True
                or ids != [str(index + 1) for index in range(offset, offset + returned)]
                or (offset + returned < total and (type(next_offset) is not int or next_offset != offset + returned or not returned))
                or (offset + returned == total and next_offset is not None)
                or type(value.get('truncated')) is not bool or value['truncated'] != (next_offset is not None)):
            raise Failure('invalid_snapshot_response')
    # Start at dispatch rather than response arrival: network time cannot extend the phone's 30s grant.
    if continuation:
        previous = config.get('snapshot', {})
        if (previous.get('id') != snapshot or previous.get('package') != package
                or snapshot != args.snapshot_id or value.get('offset') != args.offset):
            raise Failure('invalid_snapshot_response')
        ids = list(dict.fromkeys([*previous.get('node_ids', []), *ids]))
        if len(ids) > 2048: raise Failure('invalid_snapshot_response')
        config['snapshot'] = {**previous, 'node_ids': ids,
                              'expires_at': min(previous['expires_at'], request_started + expiry / 1000)}
    else:
        config['snapshot'] = {'id': snapshot, 'package': package, 'node_ids': ids,
                              'issued_at': request_started, 'expires_at': request_started + expiry / 1000}
    if config.get('task_lease'): config['task_needs_observation'] = False
    save_config(args.config, config)

_config_lock = None

def lock_config(path):
    import fcntl
    global _config_lock
    private_directory(path.parent)
    lock_path = path.with_name(path.name + '.lock')
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
        os.close(fd); raise Failure('unsafe_config_lock')
    try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd); raise Failure('another_phone_operation_in_progress')
    _config_lock = fd

def unlock_config():
    global _config_lock
    if _config_lock is not None:
        os.close(_config_lock)
        _config_lock = None

def drag_path(hold_ms, waypoints):
    if type(hold_ms) is not int or not 100 <= hold_ms <= 3000 or not 1 <= len(waypoints) <= 16:
        raise Failure('invalid_drag')
    path = []
    total = hold_ms
    for waypoint in waypoints:
        if len(waypoint) != 3: raise Failure('invalid_drag')
        try:
            x, y = float(waypoint[0]), float(waypoint[1])
            duration = int(waypoint[2])
        except (TypeError, ValueError, OverflowError): raise Failure('invalid_drag')
        if not 0 <= x < 1 or not 0 <= y < 1 or not 1 <= duration <= 5000:
            raise Failure('invalid_drag')
        total += duration
        if total > 10000: raise Failure('invalid_drag')
        path.append({'x': x, 'y': y, 'duration_ms': duration})
    return path


def gesture_tracks(tracks):
    def point(n): return type(n) in (int, float) and 0 <= n < 1
    def integer(n, low, high): return type(n) in (int, float) and low <= n <= high and n == int(n)
    if not isinstance(tracks, list) or not 1 <= len(tracks) <= 10: raise Failure('invalid_gesture')
    result = []
    for track in tracks:
        if not isinstance(track, dict) or set(track) != {'start_ms', 'x', 'y', 'path'}:
            raise Failure('invalid_gesture')
        if not integer(track['start_ms'], 0, 9999) or not point(track['x']) or not point(track['y']):
            raise Failure('invalid_gesture')
        if not isinstance(track['path'], list) or not 1 <= len(track['path']) <= 32: raise Failure('invalid_gesture')
        end = int(track['start_ms']); path = []
        for step in track['path']:
            if not isinstance(step, dict) or set(step) != {'x', 'y', 'duration_ms'}:
                raise Failure('invalid_gesture')
            if not point(step['x']) or not point(step['y']) or not integer(step['duration_ms'], 1, 5000):
                raise Failure('invalid_gesture')
            end += int(step['duration_ms'])
            if end > 10000: raise Failure('invalid_gesture')
            path.append({'x': step['x'], 'y': step['y'], 'duration_ms': int(step['duration_ms'])})
        result.append({'start_ms': int(track['start_ms']), 'x': track['x'], 'y': track['y'], 'path': path})
    return result


def file_path(value, empty=False):
    if (not isinstance(value, str) or not (empty and value == '' or value and len(value.encode('utf-8')) <= 1024
            and len(value.split('/')) <= 32 and all(part not in ('', '.', '..') and len(part) <= 255 for part in value.split('/')))
            or any(c in '\\:' or unicodedata.category(c) in ('Cc', 'Cs') for c in value)):
        raise Failure('invalid_file_path')
    return value


@contextlib.contextmanager
def local_file_stream(path):
    """Open a regular file without following symlinks; bound size before any read."""
    path = pathlib.Path(os.path.abspath(path))
    directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory); directory = child
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, 'rb') as stream:
            before=os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or not 0 <= before.st_size <= FILE_LIMIT:
                raise Failure('input_must_be_regular_file_within_limit')
            yield stream, before
    finally:
        os.close(directory)


def stable_file(stream, before):
    after=os.fstat(stream.fileno())
    identity=lambda st:(st.st_dev,st.st_ino,st.st_size,st.st_mtime_ns,st.st_ctime_ns)
    if identity(before)!=identity(after): raise Failure('input_changed_during_read')


def stream_hash(stream):
    digest=hashlib.sha256(); stream.seek(0)
    while True:
        chunk=stream.read(65536)
        if not chunk: break
        digest.update(chunk)
    return digest.hexdigest()


def local_file_bytes(path):
    with local_file_stream(path) as (stream,before):
        data=stream.read(FILE_LIMIT+1);stable_file(stream,before)
        if len(data)!=before.st_size: raise Failure('input_changed_during_read')
        return data


def file_call(args, config, action, **fields):
    while True:
        try:
            return command_request(args, config, {'id': str(uuid.uuid4()), 'action': action, **fields})
        except Failure as failure:
            # Only the convenience transfer loops wait automatically. Unknown
            # outcomes never enter this branch; every retry consumes a new
            # command sequence and ID, while upload identity/spec stay fixed.
            remaining = getattr(args, '_file_rate_wait_remaining', 0)
            if (args.command not in ('file-upload', 'file-download')
                    or failure.result.get('error') != 'ingress_rate_limited'
                    or failure.result.get('outcome') != 'not_dispatched'
                    or remaining < 60):
                raise
            args._file_rate_wait_remaining = remaining - 60
            print('Airtap file transfer: waiting 60 seconds for the relay request limit.', file=sys.stderr, flush=True)
            time.sleep(60)


def checked_file_chunk(value, root, path, offset, length, version, size=None):
    try:
        if set(value) != {'root', 'path', 'offset', 'next_offset', 'data_base64', 'sha256', 'eof', 'size', 'version'}:
            raise ValueError()
        encoded = value.pop('data_base64')
        raw = base64.b64decode(encoded, validate=True)
        if (base64.b64encode(raw).decode('ascii') != encoded or len(raw) > length
                or value.get('root') != root or value.get('path') != path or value.get('version') != version
                or type(value.get('offset')) is not int or value['offset'] != offset
                or type(value.get('next_offset')) is not int or value['next_offset'] != offset + len(raw)
                or type(value.get('size')) is not int or not 0 <= value['size'] <= FILE_LIMIT
                or size is not None and value['size'] != size
                or type(value.get('eof')) is not bool or value['eof'] != (value['next_offset'] == value['size'])
                or value['next_offset'] > value['size'] or not raw and not value['eof']
                or value.get('sha256') != hashlib.sha256(raw).hexdigest()):
            raise ValueError()
        return raw
    except (ValueError, KeyError, TypeError):
        raise Failure('invalid_file_chunk', retry_action=False)


def checked_upload(value, expected):
    allowed = {'transfer_id', 'root', 'path', 'state', 'offset', 'size', 'sha256', 'expires_at', 'publication_atomic', 'file', 'next_step', 'cleanup_required'}
    if (not isinstance(value, dict) or set(value) - allowed or any(value.get(k) != v for k, v in expected.items())
            or value.get('state') not in ('receiving', 'publishing', 'commit_unknown', 'finished', 'aborted', 'expired')
            or type(value.get('offset')) is not int or not 0 <= value['offset'] <= expected['size']
            or type(value.get('expires_at')) is not int
            or type(value.get('publication_atomic')) is not bool
            or value['publication_atomic'] != (expected['root'] == 'workspace')
            or value['state'] in ('publishing', 'commit_unknown') and (value['publication_atomic'] or value['offset'] != expected['size'])
            or 'next_step' in value and (not isinstance(value['next_step'], str) or len(value['next_step']) > 1024)
            or 'cleanup_required' in value and (type(value['cleanup_required']) is not bool or value['state'] not in ('publishing', 'expired'))
            or 'file' in value and value['state'] != 'finished'):
        raise Failure('invalid_upload_state', retry_action=False)
    if value['state'] == 'finished':
        metadata = value.get('file', {})
        if (not isinstance(metadata, dict) or value['offset'] != expected['size'] or metadata.get('sha256') != expected['sha256']
                or metadata.get('size') != expected['size'] or metadata.get('root') != expected['root']
                or metadata.get('path') != expected['path'] or metadata.get('kind') != 'file'):
            raise Failure('invalid_upload_completion', retry_action=False)
    result = {k: value[k] for k in allowed - {'file'} if k in value}
    if value['state'] == 'finished':
        result['file'] = {k: metadata.get(k) for k in ('root', 'path', 'kind', 'size', 'modified_ms', 'version', 'sha256')}
    return result


def file_command(args, config):
    command = args.command
    if command in ('file-upload', 'file-download'):
        wait = getattr(args, 'rate_limit_wait', 1200)
        if type(wait) is not int or not 0 <= wait <= 1200:
            raise Failure('invalid_rate_limit_wait')
        args._file_rate_wait_remaining = wait
    if hasattr(args, 'root') and not re.fullmatch(r'(?:workspace|saf_[a-f0-9]{32})', args.root):
        raise Failure('invalid_file_root')
    if hasattr(args, 'path'): file_path(args.path, command in ('file-list', 'file-stat'))
    if hasattr(args, 'transfer_id') and args.transfer_id is not None and not TICKET.fullmatch(args.transfer_id):
        raise Failure('invalid_transfer_id')
    if command in ('file-mkdir','file-rename','file-delete'):
        if args.root!='workspace': raise Failure('file_scope_unavailable')
        fields={'root':args.root,'path':args.path}
        if command!='file-mkdir':
            if not TOKEN.fullmatch(args.version): raise Failure('invalid_file_version')
            fields['version']=args.version
        if command=='file-rename': fields['destination']=file_path(args.destination)
        return file_call(args,config,command.replace('-','_'),**fields)
    if command == 'file-publish-media':
        if args.root != 'workspace' or not TOKEN.fullmatch(args.version) or args.mime_type not in ('image/jpeg', 'image/png', 'image/webp'): raise Failure('invalid_media_publication')
        return file_call(args, config, 'file_publish_media', root=args.root, path=args.path, version=args.version, mime_type=args.mime_type)
    if command == 'file-share':
        if args.root != 'workspace' or not re.fullmatch(r'[a-f0-9]{64}', args.version) or len(args.mime_type) > 127 or not re.fullmatch(r'[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+', args.mime_type): raise Failure('invalid_file_share')
        if args.target_package and (args.target_package != 'android' and not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+', args.target_package) or args.target_package == 'io.airtap.preview' or args.target_package.startswith('io.airtap.preview.')): raise Failure('invalid_file_share_target')
        return file_call(args, config, 'file_share', root=args.root, path=args.path, version=args.version, mime_type=args.mime_type, target_package=args.target_package)
    if command == 'file-roots': return file_call(args, config, 'file_roots')
    if command in ('file-upload-status', 'file-upload-finish', 'file-upload-abort'):
        return file_call(args, config, command.replace('-', '_'), transfer_id=args.transfer_id)
    if command == 'file-list':
        if not 0 <= args.offset <= 4096 or not 1 <= args.limit <= 64: raise Failure('invalid_file_range')
        return file_call(args, config, 'file_list', root=args.root, path=args.path, offset=args.offset, limit=args.limit)
    if command == 'file-stat': return file_call(args, config, 'file_stat', root=args.root, path=args.path)
    if command == 'file-read':
        if not 0 <= args.offset <= FILE_LIMIT or not 1 <= args.length <= FILE_CHUNK_LIMIT or not TOKEN.fullmatch(args.version):
            raise Failure('invalid_file_range')
        response = file_call(args, config, 'file_read', root=args.root, path=args.path, offset=args.offset, length=args.length, version=args.version)
        data = checked_file_chunk(response['result'], args.root, args.path, args.offset, args.length, args.version)
        private_write(args.output, data, replace=False)
        response['result'].update(output=str(args.output.absolute()), bytes=len(data))
        return response
    if command == 'file-download':
        if args.output.exists() or args.output.is_symlink(): raise Failure('file_already_exists')
        metadata = file_call(args, config, 'file_stat', root=args.root, path=args.path)['result']
        size, version, expected_hash = metadata.get('size'), metadata.get('version'), metadata.get('sha256')
        if (metadata.get('root') != args.root or metadata.get('path') != args.path or metadata.get('kind') != 'file'
                or type(size) is not int or not 0 <= size <= FILE_LIMIT
                or not isinstance(version, str) or not TOKEN.fullmatch(version)
                or not isinstance(expected_hash, str) or not TOKEN.fullmatch(expected_hash)):
            raise Failure('file_not_downloadable_with_verified_hash')
        spec={'identity':job_identity(config),'root':args.root,'path':args.path,'size':size,'version':version,'sha256':expected_hash}
        identity=hashlib.sha256(json_bytes(spec)).hexdigest()
        directory=private_directory(args.config.parent/'downloads')
        part=directory/(identity+'.part')
        if not part.exists(): private_write(part,b'',replace=False)
        fd=os.open(part,os.O_RDWR|os.O_NOFOLLOW|os.O_NONBLOCK)
        with os.fdopen(fd,'r+b') as stream:
            info=os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode&0o077 or info.st_size>size:
                raise Failure('unsafe_download_partial')
            offset=info.st_size;stream.seek(offset)
            while offset<size:
                length=min(FILE_CHUNK_LIMIT,size-offset)
                response=file_call(args,config,'file_read',root=args.root,path=args.path,offset=offset,length=length,version=version)['result']
                chunk=checked_file_chunk(response,args.root,args.path,offset,length,version,size)
                stream.write(chunk);stream.flush();os.fsync(stream.fileno());offset+=len(chunk)
            if stream_hash(stream)!=expected_hash:
                raise Failure('file_hash_mismatch',retry_action=False,partial=str(part),instruction='Inspect and remove this corrupt private partial before a fresh download.')
            output=pathlib.Path(args.output);target=private_directory(output.parent)
            dfd=os.open(target,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            temp='.airtap-'+secrets.token_hex(12)
            try:
                out=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=dfd)
                with os.fdopen(out,'wb') as destination:
                    stream.seek(0)
                    while True:
                        chunk=stream.read(65536)
                        if not chunk:break
                        destination.write(chunk)
                    destination.flush();os.fsync(destination.fileno())
                try:os.link(temp,output.name,src_dir_fd=dfd,dst_dir_fd=dfd,follow_symlinks=False)
                except FileExistsError:raise Failure('file_already_exists')
                os.fsync(dfd)
            finally:
                try:os.unlink(temp,dir_fd=dfd)
                except FileNotFoundError:pass
                os.close(dfd)
        part.unlink()
        return {'ok':True,'result':{'root':args.root,'path':args.path,'bytes':size,'sha256':expected_hash,'output':str(args.output.absolute())}}
    if command == 'file-upload':
        with local_file_stream(args.input) as (stream, before):
            return file_upload_stream(args,config,stream,before)
    raise Failure('unknown_file_command')


def file_upload_stream(args,config,stream,before):
    digest=stream_hash(stream);stable_file(stream,before)
    spec={'root':args.root,'path':args.path,'size':before.st_size,'sha256':digest}
    prior = config.get('last_file_upload', {})
    transfer = args.transfer_id
    if transfer is None and prior:
        if all(prior.get(k) == v for k, v in spec.items()): transfer = prior.get('transfer_id')
        elif prior.get('root') == args.root and prior.get('path') == args.path:
            raise Failure('upload_spec_changed', instruction='Inspect the saved transfer first. Use an explicit new transfer ID only for an intentionally new upload.')
    if transfer is None: transfer = secrets.token_hex(16)
    if not isinstance(transfer, str) or not TICKET.fullmatch(transfer): raise Failure('invalid_transfer_id')
    spec['transfer_id'] = transfer
    # Persist identity BEFORE dispatch so an uncertain begin is resumable.
    config['last_file_upload'] = spec; save_config(args.config, config)
    try:
        if prior == spec:
            try:
                state = checked_upload(file_call(args, config, 'file_upload_status', transfer_id=transfer)['result'], spec)
            except Failure as failure:
                if failure.result.get('error') != 'file_transfer_not_found': raise
                if args.root != 'workspace':
                    raise Failure('file_external_transfer_not_found', instruction='This external-directory transfer has no retained receipt. Inspect the authorized directory before intentionally starting a new upload; do not assume no file was created.')
                state = checked_upload(file_call(args, config, 'file_upload_begin', **spec)['result'], spec)
        else:
            state = checked_upload(file_call(args, config, 'file_upload_begin', **spec)['result'], spec)
        if state['state'] == 'expired': raise Failure('file_transfer_expired', instruction='Internal staging was reclaimed. Inspect the original folder; do not recreate an uncertain provider document. A recorded known document may still be explicitly aborted under the current folder permission.')
        if state['state'] == 'aborted': raise Failure('file_transfer_aborted')
        if state['state'] == 'commit_unknown': raise Failure('file_commit_unknown', instruction='The file provider may have created a file before its identity was saved. Inspect the authorized directory; do not retry finish, begin or abort to guess the result.')
        if state.get('cleanup_required'): raise Failure('file_cleanup_required', instruction='The provider created a document with a conflicting name. Inspect status; explicitly abort this transfer to remove only its recorded created document, then decide a new destination.')
        while state['state'] == 'receiving' and state['offset'] < before.st_size:
            offset = state['offset']; stable_file(stream,before); stream.seek(offset); chunk = stream.read(min(FILE_CHUNK_LIMIT,before.st_size-offset))
            if not chunk: raise Failure('input_changed_during_read')
            state = checked_upload(file_call(args, config, 'file_upload_chunk', transfer_id=transfer, offset=offset,
                data_base64=base64.b64encode(chunk).decode('ascii'), sha256=hashlib.sha256(chunk).hexdigest())['result'], spec)
            if state['state'] != 'receiving' or state['offset'] != offset + len(chunk):
                raise Failure('invalid_upload_progress')
        stable_file(stream,before)
        if state['state'] != 'finished':
            state = checked_upload(file_call(args, config, 'file_upload_finish', transfer_id=transfer)['result'], spec)
        if state['state'] == 'commit_unknown': raise Failure('file_commit_unknown', instruction='The file provider may have created a file before its identity was saved. Inspect the authorized directory; do not retry finish, begin or abort to guess the result.')
        if state.get('cleanup_required'): raise Failure('file_cleanup_required', instruction='The provider created a document with a conflicting name. Inspect status; explicitly abort this transfer to remove only its recorded created document, then decide a new destination.')
        if state['state'] != 'finished': raise Failure('upload_not_finished')
        # A durable completion receipt does not prove that another app has not changed the target later.
        current = file_call(args, config, 'file_stat', root=args.root, path=args.path)['result']
        if (current.get('root') != args.root or current.get('path') != args.path or current.get('kind') != 'file'
                or current.get('size') != spec['size'] or current.get('sha256') != spec['sha256']):
            raise Failure('finished_file_changed', retry_action=False)
        return {'ok': True, 'result': {**state, 'verification': 'current_stat_sha256'}}
    except Failure as failure:
        failure.result.update(transfer_id=transfer, retry_action=False)
        failure.result.setdefault('instruction', 'Keep this transfer ID and the same input bytes. After reconnection inspect file-upload-status. A receiving or publishing transfer can resume with the same spec; commit_unknown requires checking the authorized directory. Do not create a new transfer for an unknown outcome.')
        raise


def start_pairing(args, supplied_ticket=None):
    url = origin(args.url, args.allow_local_http)
    if not 1 <= len(args.name) <= 48 or not args.name.strip() or any(unicodedata.category(c).startswith('C') for c in args.name): raise Failure('invalid_controller_name')
    existing = args.config.exists()
    if existing:
        config = load_config(args.config, args.allow_local_http)
        if config.get('state') != 'pending':
            raise Failure('config_exists_reuse_connection_or_choose_new_path')
        if config['url'] != url or config.get('pairing_name') != args.name or config.get('pairing_capabilities', []) != (['task_lease_v1'] if args.task_lease else []):
            raise Failure('pairing_identity_mismatch', instruction='Use the same URL, name and private config. Do not replace an uncertain pairing identity.')
    ticket = supplied_ticket if supplied_ticket is not None else sys.stdin.readline(128).strip()
    if not TICKET.fullmatch(ticket): raise Failure('invalid_pairing_ticket')
    if existing:
        # A lost response can hide a pending or already-approved claim.
        # Only an explicit unauthorized response permits the idempotent POST.
        try:
            known = request(url, '/v1/pair/status', config['controller_token'], timeout=args.timeout,
                            local_test=args.allow_local_http)
        except Failure as failure:
            if failure.result.get('error') != 'http_error' or failure.result.get('status') != 401:
                raise
        else:
            status = safe_pair_status(known['data'])
            if status['state'] == 'approved':
                remember_approval(args, config, status)
                return {'ok': True, **status, 'instruction': 'The owner already approved this connection. Run wait-ready and keep this private config.'}
            if status['state'] != 'pending':
                raise Failure('pairing_not_pending', retry_action=False)
            config.update(status); save_config(args.config, config)
            return {'ok': True, **status, 'instruction': 'Tell the owner the code now. Wait for approval on their phone, then run wait-ready. Keep this private config for future tasks.'}
    else:
        config = {'url': url, 'controller_token': secrets.token_hex(32), 'state': 'pending', 'pairing_name': args.name}
        if args.task_lease: config['pairing_capabilities'] = ['task_lease_v1']
        save_config(args.config, config, replace=False)
    pair_body = {'ticket': ticket, 'controller_token': config['controller_token'], 'name': args.name}
    if config.get('pairing_capabilities'): pair_body['capabilities'] = config['pairing_capabilities']
    result = request(url, '/v1/pair', body=pair_body, method='POST', timeout=args.timeout, local_test=args.allow_local_http)
    status = safe_pair_status(result['data']); config.update(status); save_config(args.config, config)
    return {'ok': True, **status, 'instruction': 'Tell the owner the code now. Wait for approval on their phone, then run wait-ready. Keep this private config for future tasks.'}


def pair_with_code(args):
    if not 1 <= len(args.name) <= 48 or not args.name.strip() or any(unicodedata.category(c).startswith('C') for c in args.name):
        raise Failure('invalid_controller_name')
    service = origin(args.service, args.allow_local_http)
    if urllib.parse.urlsplit(service).path not in ('', '/'):
        raise Failure('service_root_required')
    raw = sys.stdin.readline(64).strip()
    code = raw.replace(' ', '').replace('-', '')
    if not re.fullmatch(r'[0-9]{8}', code):
        raise Failure('invalid_invitation_code')
    existing = load_config(args.config, args.allow_local_http) if args.config.exists() else None
    if existing:
        if existing.get('state') != 'pending':
            raise Failure('config_exists_reuse_connection_or_choose_new_path')
        # Preserve the exact saved pairing after a lost reply, even if the short
        # code has since expired. Never apply a new code to an existing identity.
        if existing.get('short_invitation_service') != service or existing.get('short_invitation_code') != code or existing.get('pairing_name') != args.name:
            raise Failure('pairing_identity_mismatch')
        try:
            found = request(existing['url'], '/v1/pair/status', existing['controller_token'],
                            timeout=args.timeout, local_test=args.allow_local_http)
        except Failure as failure:
            if failure.result.get('error') != 'http_error' or failure.result.get('status') != 401:
                raise
        else:
            status = safe_pair_status(found['data'])
            if status['state'] == 'approved':
                remember_approval(args, existing, status)
            elif status['state'] == 'pending':
                existing.update(status); save_config(args.config, existing)
            else:
                raise Failure('pairing_not_pending', retry_action=False)
            return {'ok': True, **status, 'instruction': 'Keep this original identity. Wait for the owner or their authorized paired AI to approve if pending; do not request another invitation.'}
    result = request(service, '/v1/invitations/resolve', body={'code': code}, method='POST',
                     timeout=args.timeout, local_test=args.allow_local_http)['data']
    required = {'protocol_version', 'device_base_path', 'ticket', 'pairing_expires_at', 'cli_sha256', 'agent_sha256', 'task_lease_required'}
    if (not isinstance(result, dict) or set(result) != required or type(result.get('protocol_version')) is not int or result.get('protocol_version') != 4
            or not isinstance(result.get('device_base_path'), str) or not re.fullmatch(r'/d/[a-f0-9]{32}', result['device_base_path'])
            or not isinstance(result.get('ticket'), str) or not TICKET.fullmatch(result['ticket'])
            or type(result.get('pairing_expires_at')) is not int or not time.time() < result['pairing_expires_at'] <= time.time() + PAIRING_TTL_SECONDS + PAIRING_CLOCK_TOLERANCE_SECONDS
            or any(not isinstance(result.get(k), str) or not TOKEN.fullmatch(result[k]) for k in ('cli_sha256', 'agent_sha256'))
            or type(result.get('task_lease_required')) is not bool):
        raise Failure('invalid_short_invitation_response')
    args.url = origin(service + result['device_base_path'], args.allow_local_http)
    args.task_lease = result['task_lease_required']
    if existing:
        if existing['url'] != args.url or existing.get('pairing_name') != args.name or existing.get('pairing_capabilities', []) != (['task_lease_v1'] if args.task_lease else []):
            raise Failure('pairing_identity_mismatch')
    else:
        config = {'url': args.url, 'controller_token': secrets.token_hex(32), 'state': 'pending', 'pairing_name': args.name,
                  'short_invitation_service': service, 'short_invitation_code': code}
        if args.task_lease: config['pairing_capabilities'] = ['task_lease_v1']
        save_config(args.config, config, replace=False)
    # The existing pairing path checks a saved candidate before any POST. Short
    # code resolution itself does not create an identity or grant phone access.
    return start_pairing(args, result['ticket'])


JOB_COMMANDS = ('job-create', 'job-show', 'job-resolve', 'job-finish')


def job_identity(config):
    # Pair identity only; no credential or phone content is copied to the journal.
    return hashlib.sha256((config['url'] + '\n' + config.get('claim_id', '')).encode()).hexdigest()


def job_path(args):
    if not args.job_id or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', args.job_id):
        raise Failure('invalid_job_id')
    return args.config.parent / 'jobs' / (args.job_id + '.json')


def job_load(args, config):
    path = job_path(args)
    info=path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode&0o077 or info.st_size>256*1024:raise Failure('unsafe_job_file')
    data = local_file_bytes(path)
    if len(data) > 256*1024: raise Failure('job_too_large')
    try: job = json.loads(data)
    except (ValueError, UnicodeError): raise Failure('invalid_job')
    if not isinstance(job, dict): raise Failure('invalid_job')
    if job.get('schema') != 1 or job.get('identity') != job_identity(config):
        raise Failure('job_identity_mismatch')
    steps = job.get('steps')
    if (job.get('job_id') != args.job_id or job.get('state') not in ('active', 'complete')
            or not isinstance(steps, list) or not 1 <= len(steps) <= 100
            or any(not isinstance(s, dict) or not isinstance(s.get('id'), str)
                   or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', s['id'])
                   or s.get('state') not in ('planned', 'outcome_unknown', 'needs_verification', 'verified', 'skipped')
                   or not isinstance(s.get('attempts'), list) or len(s['attempts']) > 20
                   or any(not isinstance(a, dict) for a in s['attempts']) for s in steps)
            or len({s['id'] for s in steps}) != len(steps)):
        raise Failure('invalid_job')
    return job


def job_save(args, job, replace=True):
    data = json_bytes(job)
    if len(data) > 256*1024: raise Failure('job_too_large')
    private_write(job_path(args), data, replace=replace)


def job_step(args, job):
    matches = [step for step in job['steps'] if step['id'] == args.step_id]
    if len(matches) != 1: raise Failure('job_step_not_found')
    return matches[0]


def job_command(args, config):
    if args.command == 'job-create':
        raw = sys.stdin.buffer.read(32769)
        try: plan = json.loads(raw)
        except (ValueError, UnicodeError): raise Failure('invalid_job_plan')
        if (len(raw) > 32768 or not isinstance(plan, list) or not 1 <= len(plan) <= 100
                or any(not isinstance(s, dict) or set(s) != {'id','title'} or not isinstance(s['id'], str)
                       or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}',s['id']) or not isinstance(s['title'],str)
                       or not 1 <= len(s['title']) <= 240 for s in plan)
                or len({s['id'] for s in plan}) != len(plan)):
            raise Failure('invalid_job_plan')
        job = {'schema':1, 'identity':job_identity(config), 'job_id':args.job_id, 'state':'active',
               'steps':[{**s, 'state':'planned', 'attempts':[]} for s in plan]}
        job_save(args,job,replace=False)
    else:
        job = job_load(args,config)
        if args.command == 'job-resolve':
            if job['state'] != 'active': raise Failure('job_closed')
            step = job_step(args,job)
            raw=sys.stdin.buffer.read(4097)
            try: evidence=raw.decode('utf-8').strip()
            except UnicodeError: raise Failure('invalid_evidence')
            if not evidence or len(raw)>4096: raise Failure('evidence_required')
            step['state'] = 'planned' if args.resolution == 'not_applied' else args.resolution
            step['resolution']={'at':time.time(),'result':args.resolution,'evidence':evidence}
            job_save(args,job)
        elif args.command == 'job-finish':
            if any(s['state'] not in ('verified','skipped') for s in job['steps']):
                raise Failure('job_has_unverified_steps')
            job['state']='complete'; job_save(args,job)
    return {'ok':True,'job':job}


def job_before(args, config, body):
    if not getattr(args,'job_id',None) or body['action'].replace('_','-') not in CONTROL_COMMANDS | CONNECTION_MUTATIONS: return None
    job=job_load(args,config); step=job_step(args,job)
    if job['state']!='active' or step['state']!='planned':
        raise Failure('job_step_requires_verification', retry_action=False,
                      instruction='Inspect the phone before resolving this step. A dispatched or uncertain action is never automatically replayed.')
    if len(step['attempts'])>=20: raise Failure('job_attempt_limit')
    step['state']='outcome_unknown'
    step['attempts'].append({'request_id':body['id'],'action':body['action'],'at':time.time(),'outcome':'unknown'})
    job_save(args,job)  # A crash after this line is conservative, never an implicit retry.
    return job


def job_after(args, job, result=None, failure=None):
    if job is None: return
    step=job_step(args,job); attempt=step['attempts'][-1]
    if result is not None:
        step['state']='needs_verification'; attempt['outcome']='device_acknowledged'
    elif isinstance(failure,Failure):
        attempt['error']=failure.result['error']
        # Even a known rejection doesn't prove a multi-part UI effect had no side effects.
        step['state']='outcome_unknown'; attempt['outcome']='needs_inspection'
    job_save(args,job)


def event_command(args, config):
    if args.command == 'event-status':
        return command_request(args, config, {'id': str(uuid.uuid4()), 'action': 'event_status'})
    if ((args.epoch and not TICKET.fullmatch(args.epoch)) or not 0 <= args.after <= MAX_INTEGER
            or not 1 <= args.limit <= 16 or (not args.epoch and args.after != 0)):
        raise Failure('invalid_event_cursor')
    deadline = time.monotonic() + (args.wait_seconds if args.command == 'event-wait' else 0)
    while True:
        result = command_request(args, config, {'id': str(uuid.uuid4()), 'action': 'event_read',
                                  'epoch': args.epoch, 'after': args.after, 'limit': args.limit})
        page = result['result']
        if (not isinstance(page.get('epoch'), str) or not TICKET.fullmatch(page['epoch'])
                or type(page.get('next')) is not int or not 0 <= page['next'] <= MAX_INTEGER
                or type(page.get('gap')) is not bool or not isinstance(page.get('events'), list)
                or len(page['events']) > args.limit or not isinstance(page.get('status'), dict)):
            raise Failure('invalid_event_response')
        previous = args.after if args.epoch == page['epoch'] else 0
        for event in page['events']:
            if (not isinstance(event, dict) or type(event.get('sequence')) is not int
                    or not previous < event['sequence'] <= page['next']):
                raise Failure('invalid_event_response')
            previous = event['sequence']
        if page['events'] or page['gap'] or not page['status'].get('listener_connected') or time.monotonic() >= deadline:
            return result
        time.sleep(min(5, max(0, deadline-time.monotonic())))


def wait_for_events(args):
    # Polls are read-only, but the sequence/config must be locked for each request.
    # Release the lock between polls so waiting for a message doesn't block a task.
    deadline = time.monotonic() + args.wait_seconds
    single = argparse.Namespace(**vars(args)); single.command = 'event-read'
    transient = {'another_phone_operation_in_progress', 'busy', 'device_disconnected',
                 'connection_changed', 'device_disconnected_outcome_unknown',
                 'connection_changed_outcome_unknown', 'device_timeout', 'ingress_rate_limited'}
    while True:
        acquired = False
        try:
            lock_config(args.config); acquired = True
            config = load_config(args.config, args.allow_local_http)
            if config.get('state') != 'approved': raise Failure('approval_required')
            safe_scope(config)
            result = event_command(single, config)
        except Failure as failure:
            if failure.result['error'] not in transient or time.monotonic() >= deadline:
                raise
        else:
            page = result['result']
            if page['events'] or page['gap'] or not page['status'].get('listener_connected') or time.monotonic() >= deadline:
                return result
        finally:
            if acquired: unlock_config()
        time.sleep(min(5, max(0, deadline-time.monotonic())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', action='version', version='airtap ' + VERSION)
    parser.add_argument('--config', type=pathlib.Path, default=DEFAULT_CONFIG)
    parser.add_argument('--job-id', help='Private durable task plan ID; with --step-id, journal a control action before sending.')
    parser.add_argument('--step-id', help='Step in the durable plan; unknown outcomes require inspection before reuse.')
    parser.add_argument('--timeout', type=float, default=25)
    parser.add_argument('--allow-local-http', action='store_true', help='TEST ONLY: HTTP loopback')
    sub = parser.add_subparsers(dest='command', required=True)
    pair = sub.add_parser('pair-start')
    pair.add_argument('--url', required=True); pair.add_argument('--name', default='My AI')
    pair.add_argument('--ticket-stdin', action='store_true', required=True)
    pair.add_argument('--task-lease', action='store_true', help='Declare task_lease_v1 for a multi-AI invitation; preserve this choice on pairing retries.')
    by_code = sub.add_parser('pair-code', help='Request pairing from an eight-digit invitation; approval by the owner or an already authorized paired AI is still required.')
    by_code.add_argument('--service', required=True, help='Owner-provided HTTPS service root; not a device URL.')
    by_code.add_argument('--name', default='My AI')
    by_code.add_argument('--code-stdin', action='store_true', required=True)
    restore = sub.add_parser('restore', help='Restore an original multi-AI identity from a private credential record; never starts a phone task.')
    restore.add_argument('--credential-stdin', action='store_true', required=True,
                         help='Read JSON website/username/password from secure storage through stdin; never put credentials in arguments.')
    for name in TASK_COMMANDS:
        task = sub.add_parser(name)
        if name == 'task-acquire': task.add_argument('--task-id', help='Optional32hex task identity; an uncertain acquire reuses its saved identity.')
    pair_status = sub.add_parser('pair-status'); pair_status.add_argument('--wait', action='store_true')
    for command in ('status', 'wait-ready', 'revoke', 'ping', 'apps', 'windows', 'foreground', 'observe', 'read-tree', 'read-tree-page', 'read-node-text', 'system-actions', 'audio-state', 'battery-status', 'media-status', 'sim-pin-prepare', *CONNECTION_COMMANDS, *sorted(CONTROL_COMMANDS)):
        child = sub.add_parser(command)
        if command in ('status', 'wait-ready'):
            child.add_argument('--wait-seconds', type=float, default=60)
        if command not in ('status', 'wait-ready', 'revoke'):
            child.add_argument('--request-id', type=uuid.UUID)
        if command in ('connection-approve', 'connection-cancel'):
            child.add_argument('--invitation-id', required=True)
        if command in ('connection-approve', 'connection-revoke'):
            child.add_argument('--claim-id', required=True)
        if command == 'connection-approve':
            child.add_argument('--code-stdin', required=True, action='store_true', help='Read the intended new AI’s six-digit comparison code from stdin; do not put it in arguments or logs.')
        if command in NODE_COMMANDS or command in ('read-tree', 'read-tree-page', 'read-node-text', 'launch', 'open-url', 'tap', 'long-press', 'swipe', 'drag', 'gesture', 'touch-begin', 'touch-move'):
            child.add_argument('--package', required=command != 'read-tree' and command not in VISUAL_COMMANDS)
        if command == 'open-url': child.add_argument('--url', required=True)
        if command == 'read-tree-page':
            child.add_argument('--snapshot-id', required=True); child.add_argument('--offset', type=int, required=True); child.add_argument('--limit', type=int, default=128)
        if command == 'read-node-text':
            child.add_argument('--field', choices=('text', 'description', 'hint_text'), default='text')
            child.add_argument('--offset', type=int, default=0); child.add_argument('--length', type=int, default=4096)
        if command in NODE_COMMANDS or command == 'read-node-text':
            child.add_argument('--snapshot-id', required=True); child.add_argument('--node-id', required=True)
        if command in ('set-text', 'insert-text'): child.add_argument('--text-stdin', required=True, action='store_true')
        if command in VOLUME_COMMANDS: child.add_argument('--stream', choices=AUDIO_STREAMS, required=True)
        if command == 'set-volume': child.add_argument('--level', type=int, required=True)
        if command == 'adjust-volume': child.add_argument('--direction', choices=('raise', 'lower', 'mute', 'unmute'), required=True)
        if command == 'system-action': child.add_argument('--name', choices=SYSTEM_ACTIONS, required=True)
        if command == 'set-selection':
            child.add_argument('--start', type=int, required=True); child.add_argument('--end', type=int, required=True)
        if command == 'delete-text':
            child.add_argument('--before', type=int, required=True); child.add_argument('--after', type=int, required=True)
        if command == 'editor-action': child.add_argument('--editor-action', choices=('default', 'done', 'go', 'next', 'previous', 'search', 'send'), default='default')
        if command == 'node-action':
            child.add_argument('--operation', choices=NODE_OPERATIONS, required=True)
            child.add_argument('--row', type=int); child.add_argument('--column', type=int); child.add_argument('--value', type=float)
            child.add_argument('--action-id', type=int)
            child.add_argument('--granularity', choices=('character', 'word', 'line', 'paragraph', 'page'))
            child.add_argument('--extend-selection', choices=('true', 'false'))
        if command == 'scroll': child.add_argument('--direction', choices=('forward', 'backward'), required=True)
        if command == 'observe' or command in CONTROL_COMMANDS:
            child.add_argument('--output', type=pathlib.Path, help='New JPEG path for observation (private default if omitted)')
        if command in CONTROL_COMMANDS:
            after = child.add_mutually_exclusive_group()
            after.add_argument('--no-observe', action='store_true', help='Do not capture after action; caller must observe separately')
            after.add_argument('--after-action', choices=('image', 'tree', 'none'),
                               help='Post-action observation (default: image; volume and lock_screen: none)')
        if command in ('tap', 'long-press', 'swipe', 'drag', 'touch-begin', 'touch-move'):
            child.add_argument('--observation-id', required=True)
            child.add_argument('--x', type=float, required=True); child.add_argument('--y', type=float, required=True)
        if command=='sim-pin-submit':
            child.add_argument('--challenge',required=True);child.add_argument('--pin-stdin',action='store_true',required=True)
        if command in ('media-status','media-stop'): child.add_argument('--capture-id',required=True)
        if command=='media-capture':
            child.add_argument('--kind',choices=('photo','video','audio','screen'),required=True)
            child.add_argument('--seconds',type=int,default=30)
        if command in ('touch-move','touch-end'): child.add_argument('--touch-id',required=True)
        if command == 'touch-move': child.add_argument('--duration-ms',type=int,default=500)
        if command == 'swipe':
            child.add_argument('--end-x', type=float, required=True); child.add_argument('--end-y', type=float, required=True)
            child.add_argument('--duration-ms', type=int, default=500)
        if command == 'drag':
            child.add_argument('--hold-ms', type=int, default=700, help='Hold before moving (100–3000ms)')
            child.add_argument('--to', nargs=3, action='append', required=True, metavar=('X', 'Y', 'MS'),
                               help='Move to normalized X Y over MS; repeat for a path, repeat coordinates to dwell. Total <=10000ms.')
        if command == 'gesture':
            child.add_argument('--observation-id', required=True)
            child.add_argument('--gesture-stdin', action='store_true', required=True, help='Read JSON touch-track array from stdin; requires phone gesture_v1')
    for command in JOB_COMMANDS:
        child=sub.add_parser(command)
        if command=='job-create': child.add_argument('--plan-stdin',action='store_true',required=True)
        if command=='job-resolve':
            child.add_argument('--resolution',choices=('verified','not_applied','skipped'),required=True)
            child.add_argument('--evidence-stdin',action='store_true',required=True)
    for command in EVENT_COMMANDS:
        child = sub.add_parser(command, help='Owner-enabled phone notifications; no screen wake or task lease. The cursor is not consumed.')
        if command != 'event-status':
            child.add_argument('--epoch', default=''); child.add_argument('--after', type=int, default=0)
            child.add_argument('--limit', type=int, default=16)
        if command == 'event-wait': child.add_argument('--wait-seconds', type=float, default=60)
    for command in FILE_COMMANDS:
        child = sub.add_parser(command)
        if command in ('file-publish-media','file-mkdir','file-rename','file-delete','file-share', 'file-list', 'file-stat', 'file-read', 'file-download', 'file-upload'):
            child.add_argument('--root', required=True)
            child.add_argument('--path', default='', required=command in ('file-publish-media','file-mkdir','file-rename','file-delete','file-share', 'file-read', 'file-download', 'file-upload'))
        if command in ('file-rename','file-delete'): child.add_argument('--version',required=True)
        if command == 'file-rename': child.add_argument('--destination',required=True)
        if command == 'file-publish-media':
            child.add_argument('--version', required=True)
            child.add_argument('--mime-type', required=True, choices=('image/jpeg', 'image/png', 'image/webp'), help='Publish this exact image to Pictures/Airtap. Same file/version reuses the existing photo.')
        if command == 'file-share':
            child.add_argument('--version', required=True); child.add_argument('--mime-type', required=True)
            child.add_argument('--target-package', default='', help='Optional App package; empty opens the normal system share chooser. Not proof of sending.')
        if command == 'file-list':
            child.add_argument('--offset', type=int, default=0); child.add_argument('--limit', type=int, default=64)
        if command == 'file-read':
            child.add_argument('--offset', type=int, required=True); child.add_argument('--length', type=int, default=4096)
            child.add_argument('--version', required=True)
        if command in ('file-read', 'file-download'): child.add_argument('--output', type=pathlib.Path, required=True)
        if command in ('file-upload', 'file-download'):
            child.add_argument('--rate-limit-wait', type=int, default=1200,
                               help='Total seconds to wait for known pre-dispatch relay rate limits (0–1200; default 1200). Unknown outcomes are never retried.')
        if command == 'file-upload':
            child.add_argument('--input', type=pathlib.Path, required=True)
            child.add_argument('--transfer-id', help='Explicit32hex ID; otherwise reuse/persist the matching last transfer before sending')
        if command in ('file-upload-status', 'file-upload-finish', 'file-upload-abort'):
            child.add_argument('--transfer-id', required=True)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 60: raise Failure('invalid_timeout')
    if hasattr(args, 'wait_seconds') and not 1 <= args.wait_seconds <= 300: raise Failure('invalid_wait')
    if args.command in CONTROL_COMMANDS:
        no_followup = args.command in VOLUME_COMMANDS or args.command in ('media-capture','media-stop','sim-pin-submit') or (args.command == 'system-action' and args.name == 'lock_screen')
        if no_followup and args.after_action in ('image', 'tree'):
            raise Failure('post_action_observation_unavailable', retry_action=False)
        after_action = 'none' if args.no_observe or no_followup else args.after_action or 'image'
        if args.output is not None and after_action != 'image':
            raise Failure('output_requires_image_observation', retry_action=False)
    if args.command == 'restore' and not args.config.is_absolute():
        raise Failure('absolute_config_path_required')
    if args.command == 'event-wait': return wait_for_events(args)
    lock_config(args.config)
    if args.command == 'restore': return restore_identity(args)
    if args.command == 'pair-start': return start_pairing(args)
    if args.command == 'pair-code': return pair_with_code(args)
    config = load_config(args.config, args.allow_local_http)
    if args.command in JOB_COMMANDS: return job_command(args, config)
    if args.command == 'pair-status': return poll_pair(args, config)
    if args.command == 'wait-ready': return wait_ready(args, config)
    if args.command == 'status': return status_request(args, config)
    if args.command == 'revoke':
        result = request(config['url'], '/v1/revoke', config['controller_token'], method='POST', timeout=args.timeout, local_test=args.allow_local_http)
        if result.get('data', {}).get('ok') is not True: raise Failure('revocation_not_confirmed')
        config.update(state='revoked'); invalidate_task(config, 'closed'); config.pop('snapshot', None); config.pop('observation', None); save_config(args.config, config)
        return {'ok': True, 'state': 'revoked'}
    if config.get('state') != 'approved': raise Failure('approval_required', instruction='Run pair-status after the owner or their authorized paired AI approves.')
    safe_scope(config)
    if args.command in EVENT_COMMANDS: return event_command(args, config)
    if args.command != 'task-acquire': require_restored_task(config)
    if args.command in TASK_COMMANDS: return task_command(args, config)
    if args.command in FILE_COMMANDS: return file_command(args, config)
    request_id = args.request_id or uuid.uuid4()
    if request_id.version != 4 or request_id.variant != uuid.RFC_4122: raise Failure('invalid_request_id')
    body = {'id': str(request_id), 'action': args.command.replace('-', '_')}
    if args.command in ('connection-approve', 'connection-cancel'):
        if not TICKET.fullmatch(args.invitation_id): raise Failure('invalid_invitation_id')
        body['invitation_id'] = args.invitation_id
    if args.command in ('connection-approve', 'connection-revoke'):
        if not TICKET.fullmatch(args.claim_id): raise Failure('invalid_claim_id')
        body['claim_id'] = args.claim_id
    if args.command == 'connection-approve':
        try: code = sys.stdin.buffer.read(16).decode('ascii').strip()
        except UnicodeError: raise Failure('invalid_verification_code')
        if not re.fullmatch(r'[0-9]{6}', code): raise Failure('invalid_verification_code')
        body.update(claim_id=args.claim_id, verification_code=code)
    package = getattr(args, 'package', None)
    if args.command in VISUAL_COMMANDS and package is None:
        package = config.get('observation', {}).get('package')
    if package is not None:
        if not valid_package(package): raise Failure('invalid_or_protected_package')
        body['package'] = package
    if args.command=='sim-pin-submit':
        if not TICKET.fullmatch(args.challenge):raise Failure('invalid_challenge')
        raw=sys.stdin.buffer.read(16)
        try:pin=raw.decode('ascii').strip()
        except UnicodeError:raise Failure('invalid_sim_pin_format')
        if not re.fullmatch(r'[0-9]{4,8}',pin):raise Failure('invalid_sim_pin_format')
        body.update(challenge=args.challenge,pin=pin)
    if args.command in ('media-status','media-stop'):
        if not TICKET.fullmatch(args.capture_id):raise Failure('invalid_capture_id')
        body['capture_id']=args.capture_id
    if args.command=='media-capture':
        if not 1<=args.seconds<=60:raise Failure('invalid_duration')
        body.update(kind=args.kind,seconds=args.seconds)
    if args.command in ('touch-move','touch-end'):
        if not TICKET.fullmatch(args.touch_id):raise Failure('invalid_touch_id')
        body['touch_id']=args.touch_id
    if args.command == 'touch-move':
        if not 1<=args.duration_ms<=5000:raise Failure('invalid_duration')
        body['duration_ms']=args.duration_ms
    if args.command in VOLUME_COMMANDS:
        body['stream'] = args.stream
        if args.command == 'set-volume':
            if not 0 <= args.level <= 2147483647: raise Failure('invalid_volume_level')
            body['level'] = args.level
        else: body['direction'] = args.direction
    if args.command == 'open-url': body['url'] = open_url(args.url)
    if args.command in ('read-tree-page', 'read-node-text'):
        snapshot = config.get('snapshot', {})
        if (snapshot.get('id') != args.snapshot_id or snapshot.get('package') != package
                or not snapshot.get('issued_at', 0) <= time.time() < snapshot.get('expires_at', 0)):
            raise Failure('fresh_snapshot_required')
        body['snapshot_id'] = args.snapshot_id
        if args.command == 'read-tree-page':
            if not 0 <= args.offset <= 2048 or not 1 <= args.limit <= 128: raise Failure('invalid_page')
            body.update(offset=args.offset, limit=args.limit)
        else:
            if args.node_id not in snapshot.get('node_ids', []): raise Failure('fresh_snapshot_required')
            if not 0 <= args.offset <= 2147483647 or not 2 <= args.length <= 4096: raise Failure('invalid_text_range')
            body.update(node_id=args.node_id, field=args.field, offset=args.offset, length=args.length)
    if args.command in NODE_COMMANDS:
        snapshot = config.get('snapshot', {})
        if (snapshot.get('id') != args.snapshot_id or snapshot.get('package') != package or args.node_id not in snapshot.get('node_ids', [])
                or not snapshot.get('issued_at', 0) <= time.time() < snapshot.get('expires_at', 0)):
            raise Failure('fresh_snapshot_required')
        body.update(snapshot_id=args.snapshot_id, node_id=args.node_id)
        if args.command in ('set-text', 'insert-text'):
            raw = sys.stdin.buffer.read(8001)
            try: value = raw.decode('utf-8', errors='strict')
            except UnicodeError: raise Failure('invalid_utf8_text')
            if len(raw) > 8000 or len(value) > 2000: raise Failure('text_too_large')
            body['text'] = value
        if args.command == 'scroll': body['direction'] = args.direction
        if args.command == 'node-action':
            body['operation'] = args.operation
            extended = (args.action_id, args.granularity, args.extend_selection)
            if args.operation == 'custom':
                if args.action_id is None or not 33554432 <= args.action_id <= 2147483647 or any(value is not None for value in (args.row, args.column, args.value, args.granularity, args.extend_selection)):
                    raise Failure('invalid_custom_action')
                body['action_id'] = args.action_id
            elif args.operation in ('next_granularity', 'previous_granularity'):
                if args.granularity is None or args.extend_selection is None or any(value is not None for value in (args.row, args.column, args.value, args.action_id)):
                    raise Failure('invalid_node_granularity')
                body.update(granularity=args.granularity, extend_selection=args.extend_selection == 'true')
            elif any(value is not None for value in extended):
                raise Failure('unexpected_node_argument')
            elif args.operation == 'scroll_to_position':
                if args.value is not None or any(value is None or not 0 <= value <= 2147483647 for value in (args.row, args.column)):
                    raise Failure('invalid_node_position')
                body.update(row=args.row, column=args.column)
            elif args.operation == 'set_progress':
                if args.row is not None or args.column is not None or args.value is None or not math.isfinite(args.value) or abs(args.value) > 3.4028234663852886e38:
                    raise Failure('invalid_node_progress')
                body['value'] = args.value
            elif args.row is not None or args.column is not None or args.value is not None:
                raise Failure('unexpected_node_argument')
        if args.command == 'set-selection':
            if not all(0 <= value <= 2147483647 for value in (args.start, args.end)): raise Failure('invalid_selection')
            body.update(start=args.start, end=args.end)
        if args.command == 'delete-text':
            if not all(0 <= value <= 2000 for value in (args.before, args.after)) or args.before + args.after == 0: raise Failure('invalid_delete_range')
            body.update(before=args.before, after=args.after)
        if args.command == 'editor-action': body['editor_action'] = args.editor_action
    if args.command == 'system-action': body['name'] = args.name
    if args.command in ('tap', 'long-press', 'swipe', 'drag', 'gesture', 'touch-begin', 'touch-move'):
        observation = config.get('observation', {})
        if (not valid_observed_package(observation) or observation.get('observation_id') != args.observation_id or observation.get('package') != package
                or not observation.get('issued_at', 0) <= time.time() < observation.get('expires_at', 0)):
            raise Failure('fresh_observation_required')
        fields = () if args.command == 'gesture' else ('x', 'y', 'end_x', 'end_y') if args.command == 'swipe' else ('x', 'y')
        for key in fields:
            value = getattr(args, key)
            if not 0 <= value < 1: raise Failure('invalid_coordinate')
            body[key] = value
        body['observation_id'] = args.observation_id
        if args.command == 'swipe':
            if not 100 <= args.duration_ms <= 5000: raise Failure('invalid_duration')
            body['duration_ms'] = args.duration_ms
        if args.command == 'drag':
            body.update(hold_ms=args.hold_ms, path=drag_path(args.hold_ms, args.to))
        if args.command == 'gesture':
            raw = sys.stdin.buffer.read(8193)
            if len(raw) > 8192: raise Failure('request_too_large')
            try: tracks = json.loads(raw.decode('utf-8', errors='strict'))
            except (ValueError, UnicodeError, RecursionError): raise Failure('invalid_gesture')
            body['tracks'] = gesture_tracks(tracks)
    if len(json_bytes(body)) > 8192: raise Failure('request_too_large')
    if args.command in CONTROL_COMMANDS:
        config.pop('snapshot', None); config.pop('observation', None); save_config(args.config, config)
    job = job_before(args,config,body)
    started = time.time()
    try:
        result = command_request(args, config, body)
    except BaseException as failure:
        job_after(args,job,failure=failure)
        raise
    job_after(args,job,result=result)
    if args.command == 'connection-invite' and result.get('result', {}).get('state') == 'migration_pending':
        result['instruction'] = 'Original pairing is preserved. Check status until mode is multi, then task-acquire and resume connection-invite. Do not re-pair or replay an unknown request.'
    if args.command in ('read-tree', 'read-tree-page'): remember_snapshot(args, config, result['result'], result['result'].get('package'), started, continuation=args.command == 'read-tree-page')
    if args.command in CONTROL_COMMANDS and after_action != 'none':
        try:
            time.sleep(0.35)
            observation_started = time.time()
            followup = command_request(args, config, {'id': str(uuid.uuid4()),
                                       'action': 'read_tree' if after_action == 'tree' else 'observe'})
            if after_action == 'tree':
                value = followup['result']
                remember_snapshot(args, config, value, value.get('package'), observation_started)
                result['post_action_tree'] = value
            else:
                result['observation'] = followup['result']
        except Failure as failure:
            observation_error = {**failure.result, 'retry_action': False}
        except (OSError, ValueError, TypeError, KeyError, KeyboardInterrupt):
            observation_error = {'ok': False, 'error': 'post_action_observation_failed', 'retry_action': False}
        else:
            return result
        # The action is already accepted. Invalid or unsaved follow-up data must
        # not become a usable target, and cleanup failure must not hide acceptance.
        clear_task_views(config)
        try:
            save_config(args.config, config)
        except (Failure, OSError, ValueError, TypeError, KeyError, KeyboardInterrupt):
            observation_error['cache_reset_confirmed'] = False
        result['observation_error'] = observation_error
        result['instruction'] = ('Action accepted, visual result unconfirmed. Observe before deciding the next action; do not replay.'
                                 if after_action == 'image' else
                                 'Action accepted, post-action tree unconfirmed. Observe or read-tree before deciding the next action; do not replay.')
        if observation_error.get('cache_reset_confirmed') is False:
            result['instruction'] += ' Local state could not be reliably saved; do not use saved targets. Resolve local storage before observing again.'
    return result

if __name__ == '__main__':
    if sys.argv[1:] == ['_request']:
        _request_child()
    else:
        try:
            print(json.dumps(main(), ensure_ascii=False))
        except Failure as error:
            print(json.dumps(error.result, ensure_ascii=False)); sys.exit(1)
        except (OSError, ValueError, KeyboardInterrupt):
            print(json.dumps({'ok': False, 'error': 'local_operation_failed_or_interrupted', 'retry_action': False})); sys.exit(1)
