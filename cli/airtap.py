#!/usr/bin/env python3
"""Airtap personal-AI CLI. Python 3.10+, standard library only."""
import argparse
import base64
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
MAX_COMMAND = 8192
VERSION = '0.4.0-cli.8'
PACKAGE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$')
NODE = re.compile(r'^[1-9][0-9]{0,3}$')
NODE_COMMANDS = {'click', 'long-click', 'set-text', 'scroll', 'node-action', 'focus', 'set-selection', 'select-all', 'copy', 'cut', 'paste', 'insert-text', 'delete-text', 'editor-action'}
SYSTEM_ACTIONS = ('notifications', 'quick_settings', 'dismiss_notification_shade', 'all_apps', 'dpad_up', 'dpad_down', 'dpad_left', 'dpad_right', 'dpad_center', 'power_dialog', 'lock_screen', 'split_screen')
AUDIO_STREAMS = ('music', 'alarm', 'ring', 'notification', 'system', 'voice_call')
VOLUME_COMMANDS = {'set-volume', 'adjust-volume'}
CONTROL_COMMANDS = {'set-volume', 'adjust-volume', 'launch', 'open-url', 'click', 'long-click', 'set-text', 'scroll', 'node-action', 'tap', 'long-press', 'swipe', 'drag', 'gesture', 'back', 'home', 'recents', 'system-action', 'focus', 'set-selection', 'select-all', 'copy', 'cut', 'paste', 'insert-text', 'delete-text', 'editor-action'}
NODE_OPERATIONS = ('accessibility_focus', 'clear_accessibility_focus', 'next_granularity', 'previous_granularity', 'custom', 'select', 'clear_selection', 'clear_focus', 'context_click', 'expand', 'collapse', 'dismiss', 'show_on_screen', 'scroll_up', 'scroll_down', 'scroll_left', 'scroll_right', 'page_up', 'page_down', 'page_left', 'page_right', 'scroll_to_position', 'set_progress', 'drag_start', 'drag_drop', 'drag_cancel')
FILE_LIMIT = 8 * 1024 * 1024
FILE_CHUNK_LIMIT = 4096
FILE_COMMANDS = ('file-share', 'file-roots', 'file-list', 'file-stat', 'file-read', 'file-download', 'file-upload', 'file-upload-status', 'file-upload-finish', 'file-upload-abort')
DEFAULT_CONFIG = pathlib.Path.home() / '.config/airtap/preview.json'

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
        return value
    except Failure:
        raise
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
    request = json.loads(sys.stdin.buffer.read(16385))
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
    if 'session_expires_at' not in status or status['session_expires_at'] is not None:
        raise Failure('invalid_pairing_response')
    scope = safe_scope(status)
    scope['command_sequence'] = max(scope['command_sequence'], config.get('command_sequence', 0))
    config.update(scope)
    config.update(state='approved', session_expires_at=None, allow_tap=False)
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
    deadline = time.monotonic() + 300
    while True:
        result = request(config['url'], '/v1/pair/status', config['controller_token'], timeout=args.timeout,
                         local_test=args.allow_local_http)
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

def status_request(args, config, timeout=None):
    result = request(config['url'], '/v1/status', config['controller_token'], timeout=timeout or args.timeout,
                     local_test=args.allow_local_http)
    data = result.get('data', {})
    if data.get('protocol_version') != 4 or type(data.get('deviceConnected')) is not bool or data.get('pending') not in (0, 1):
        raise Failure('invalid_status_response')
    status = safe_pair_status({**data, 'state': 'approved'})
    for key in ('deviceConnected', 'pending', 'connection_id', 'command_budget_remaining'):
        status[key] = data.get(key)
    if config.get('connection_id') != status['connection_id']:
        config.pop('snapshot', None); config.pop('observation', None)
    config['connection_id'] = status['connection_id']
    remember_approval(args, config, status)
    return {'ok': True, **status, 'elapsed_ms': result['elapsed_ms']}

def command_request(args, config, body, timeout=None):
    sequence = config.get('command_sequence', 0) + 1
    if sequence > 9007199254740991:
        raise Failure('sequence_exhausted')
    body = {**body, 'sequence': sequence}
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
                if not TICKET.fullmatch(observation) or not valid_package(value['package']) or value.get('coordinate_space') != 'normalized_image':
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
    if (value.get('package') != package or not isinstance(snapshot, str) or not TICKET.fullmatch(snapshot)
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


def local_file_bytes(path):
    """Read only an explicitly supplied regular file, without following any symlink."""
    path = pathlib.Path(os.path.abspath(path))
    directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory); directory = child
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, 'rb') as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or not 0 <= before.st_size <= FILE_LIMIT:
                raise Failure('input_must_be_regular_file_within_limit')
            data = stream.read(FILE_LIMIT + 1)
            after = os.fstat(stream.fileno())
            identity = lambda st: (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)
            if len(data) != before.st_size or identity(before) != identity(after):
                raise Failure('input_changed_during_read')
            return data
    finally:
        os.close(directory)


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
        metadata = file_call(args, config, 'file_stat', root=args.root, path=args.path)['result']
        size, version, expected_hash = metadata.get('size'), metadata.get('version'), metadata.get('sha256')
        if (metadata.get('root') != args.root or metadata.get('path') != args.path or metadata.get('kind') != 'file'
                or type(size) is not int or not 0 <= size <= FILE_LIMIT
                or not isinstance(version, str) or not TOKEN.fullmatch(version)
                or not isinstance(expected_hash, str) or not TOKEN.fullmatch(expected_hash)):
            raise Failure('file_not_downloadable_with_verified_hash')
        data = bytearray()
        while len(data) < size:
            response = file_call(args, config, 'file_read', root=args.root, path=args.path,
                                 offset=len(data), length=min(FILE_CHUNK_LIMIT, size-len(data)), version=version)['result']
            data.extend(checked_file_chunk(response, args.root, args.path, len(data), min(FILE_CHUNK_LIMIT, size-len(data)), version, size))
        if hashlib.sha256(data).hexdigest() != expected_hash:
            raise Failure('file_hash_mismatch', retry_action=False)
        private_write(args.output, data, replace=False)
        return {'ok': True, 'result': {'root': args.root, 'path': args.path, 'bytes': len(data), 'sha256': expected_hash, 'output': str(args.output.absolute())}}
    if command == 'file-upload':
        data = local_file_bytes(args.input)
        spec = {'root': args.root, 'path': args.path, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
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
            while state['state'] == 'receiving' and state['offset'] < len(data):
                offset = state['offset']; chunk = data[offset:offset+FILE_CHUNK_LIMIT]
                state = checked_upload(file_call(args, config, 'file_upload_chunk', transfer_id=transfer, offset=offset,
                    data_base64=base64.b64encode(chunk).decode('ascii'), sha256=hashlib.sha256(chunk).hexdigest())['result'], spec)
                if state['state'] != 'receiving' or state['offset'] != offset + len(chunk):
                    raise Failure('invalid_upload_progress')
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
    raise Failure('unknown_file_command')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', action='version', version='airtap ' + VERSION)
    parser.add_argument('--config', type=pathlib.Path, default=DEFAULT_CONFIG)
    parser.add_argument('--timeout', type=float, default=25)
    parser.add_argument('--allow-local-http', action='store_true', help='TEST ONLY: HTTP loopback')
    sub = parser.add_subparsers(dest='command', required=True)
    pair = sub.add_parser('pair-start')
    pair.add_argument('--url', required=True); pair.add_argument('--name', default='My AI')
    pair.add_argument('--ticket-stdin', action='store_true', required=True)
    pair_status = sub.add_parser('pair-status'); pair_status.add_argument('--wait', action='store_true')
    for command in ('status', 'wait-ready', 'revoke', 'ping', 'apps', 'windows', 'foreground', 'observe', 'read-tree', 'read-tree-page', 'read-node-text', 'system-actions', 'audio-state', *sorted(CONTROL_COMMANDS)):
        child = sub.add_parser(command)
        if command in ('status', 'wait-ready'):
            child.add_argument('--wait-seconds', type=float, default=60)
        if command not in ('status', 'wait-ready', 'revoke'):
            child.add_argument('--request-id', type=uuid.UUID)
        if command in NODE_COMMANDS or command in ('read-tree', 'read-tree-page', 'read-node-text', 'launch', 'open-url', 'tap', 'long-press', 'swipe', 'drag', 'gesture'):
            child.add_argument('--package', required=command != 'read-tree')
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
            child.add_argument('--no-observe', action='store_true', help='Do not capture after action; caller must observe separately')
        if command in ('tap', 'long-press', 'swipe', 'drag'):
            child.add_argument('--observation-id', required=True)
            child.add_argument('--x', type=float, required=True); child.add_argument('--y', type=float, required=True)
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
    for command in FILE_COMMANDS:
        child = sub.add_parser(command)
        if command in ('file-share', 'file-list', 'file-stat', 'file-read', 'file-download', 'file-upload'):
            child.add_argument('--root', required=True)
            child.add_argument('--path', default='', required=command in ('file-share', 'file-read', 'file-download', 'file-upload'))
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
    lock_config(args.config)
    if args.command == 'pair-start':
        url = origin(args.url, args.allow_local_http)
        if not 1 <= len(args.name) <= 48 or not args.name.strip() or any(unicodedata.category(c).startswith('C') for c in args.name): raise Failure('invalid_controller_name')
        existing = args.config.exists()
        if existing:
            config = load_config(args.config, args.allow_local_http)
            if config.get('state') != 'pending':
                raise Failure('config_exists_reuse_connection_or_choose_new_path')
            if config['url'] != url or config.get('pairing_name') != args.name:
                raise Failure('pairing_identity_mismatch', instruction='Use the same URL, name and private config. Do not replace an uncertain pairing identity.')
        ticket = sys.stdin.readline(128).strip()
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
            save_config(args.config, config, replace=False)
        result = request(url, '/v1/pair', body={'ticket': ticket, 'controller_token': config['controller_token'], 'name': args.name}, method='POST', timeout=args.timeout, local_test=args.allow_local_http)
        status = safe_pair_status(result['data']); config.update(status); save_config(args.config, config)
        return {'ok': True, **status, 'instruction': 'Tell the owner the code now. Wait for approval on their phone, then run wait-ready. Keep this private config for future tasks.'}
    config = load_config(args.config, args.allow_local_http)
    if args.command == 'pair-status': return poll_pair(args, config)
    if args.command == 'wait-ready': return wait_ready(args, config)
    if args.command == 'status': return status_request(args, config)
    if args.command == 'revoke':
        result = request(config['url'], '/v1/revoke', config['controller_token'], method='POST', timeout=args.timeout, local_test=args.allow_local_http)
        if result.get('data', {}).get('ok') is not True: raise Failure('revocation_not_confirmed')
        config.update(state='revoked'); config.pop('snapshot', None); config.pop('observation', None); save_config(args.config, config)
        return {'ok': True, 'state': 'revoked'}
    if config.get('state') != 'approved': raise Failure('approval_required', instruction='Run pair-status after the owner approves.')
    safe_scope(config)
    if args.command in FILE_COMMANDS: return file_command(args, config)
    request_id = args.request_id or uuid.uuid4()
    if request_id.version != 4 or request_id.variant != uuid.RFC_4122: raise Failure('invalid_request_id')
    body = {'id': str(request_id), 'action': args.command.replace('-', '_')}
    package = getattr(args, 'package', None)
    if package is not None:
        if not valid_package(package): raise Failure('invalid_or_protected_package')
        body['package'] = package
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
    if args.command in ('tap', 'long-press', 'swipe', 'drag', 'gesture'):
        observation = config.get('observation', {})
        if (observation.get('observation_id') != args.observation_id or observation.get('package') != package
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
            raw = sys.stdin.buffer.read(MAX_COMMAND + 1)
            if len(raw) > MAX_COMMAND: raise Failure('request_too_large')
            try: tracks = json.loads(raw.decode('utf-8', errors='strict'))
            except (ValueError, UnicodeError, RecursionError): raise Failure('invalid_gesture')
            body['tracks'] = gesture_tracks(tracks)
    if len(json_bytes(body)) > MAX_COMMAND: raise Failure('request_too_large')
    if args.command in CONTROL_COMMANDS:
        config.pop('snapshot', None); config.pop('observation', None); save_config(args.config, config)
    started = time.time(); result = command_request(args, config, body)
    if args.command in ('read-tree', 'read-tree-page'): remember_snapshot(args, config, result['result'], result['result'].get('package'), started, continuation=args.command == 'read-tree-page')
    if args.command in CONTROL_COMMANDS and args.command not in VOLUME_COMMANDS and not args.no_observe and not (args.command == 'system-action' and args.name == 'lock_screen'):
        time.sleep(0.35)
        try:
            result['observation'] = command_request(args, config, {'id': str(uuid.uuid4()), 'action': 'observe'})['result']
        except Failure as failure:
            result['observation_error'] = failure.result
            result['instruction'] = 'Action accepted, visual result unconfirmed. Observe before deciding the next action; do not replay.'
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
