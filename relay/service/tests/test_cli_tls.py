"""Offline TLS policy and real loopback CONNECT tests; no production credentials."""
import base64
import hashlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import select
import shutil
import socket
import socketserver
import ssl
import subprocess
import tempfile
import threading
import types
import unittest
from unittest.mock import patch
import urllib.parse

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('airtap_cli_tls', ROOT / 'cli/airtap.py')
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)
FIXTURE = json.loads((Path(__file__).parent / 'fixtures/emudhra-20261027.json').read_text())


class ChainPolicy(unittest.TestCase):
    def socket(self, chain, legacy=False):
        context = types.SimpleNamespace(verify_mode=ssl.CERT_REQUIRED, check_hostname=True)
        if legacy:
            entries = [types.SimpleNamespace(public_bytes=lambda der=der: ssl.DER_cert_to_PEM_cert(der)) for der in chain]
            return types.SimpleNamespace(context=context, _sslobj=types.SimpleNamespace(get_verified_chain=lambda: entries))
        return types.SimpleNamespace(context=context, get_verified_chain=lambda: chain)

    def test_all_eight_keys_and_sixteen_cross_signed_certificates(self):
        fingerprints = set()
        for entry in FIXTURE['certificates']:
            with self.subTest(name=entry['name'], issuer=entry['issued_by']):
                der = ssl.PEM_cert_to_DER_cert(entry['pem'])
                self.assertEqual(hashlib.sha256(der).hexdigest(), entry['sha256'].lower())
                spki = CLI.certificate_spki_sha256(der)
                self.assertEqual(spki, entry['spki_sha256'].lower())
                fingerprints.add(spki)
                for legacy in (False, True):
                    with self.assertRaises(CLI.TLSVerificationError) as caught:
                        CLI.verify_peer_chain(self.socket([der], legacy))
                    self.assertEqual(caught.exception.code, 'tls_compromised_certificate_key')
        self.assertEqual(len(FIXTURE['certificates']), 16)
        self.assertEqual(len(fingerprints), 8)
        self.assertEqual(fingerprints, CLI.COMPROMISED_SPKI_SHA256)

    def test_rejects_missing_empty_oversize_and_malformed_chains(self):
        for chain in (None, [], [b'bad'], [b'bad'] * 17, 'not a chain'):
            with self.subTest(chain_type=type(chain).__name__):
                with self.assertRaises(CLI.TLSVerificationError) as caught:
                    CLI.verify_peer_chain(self.socket(chain))
                self.assertEqual(caught.exception.code, 'tls_verified_chain_unavailable')
        unsupported = types.SimpleNamespace(context=self.socket([]).context)
        with self.assertRaises(CLI.TLSVerificationError) as caught:
            CLI.verify_peer_chain(unsupported)
        self.assertEqual(caught.exception.code, 'tls_verified_chain_unavailable')

    def test_disabled_system_or_hostname_verification_never_falls_back(self):
        for mode, hostname in ((ssl.CERT_NONE, True), (ssl.CERT_REQUIRED, False)):
            sock = self.socket([])
            sock.context.verify_mode, sock.context.check_hostname = mode, hostname
            with self.assertRaises(CLI.TLSVerificationError) as caught:
                CLI.verify_peer_chain(sock)
            self.assertEqual(caught.exception.code, 'tls_verification_required')

    def test_der_truncation_trailing_data_indefinite_and_noncanonical_lengths(self):
        der = ssl.PEM_cert_to_DER_cert(FIXTURE['certificates'][0]['pem'])
        for bad in (b'', der[:-1], der+b'extra', b'\x30\x80'+der[4:], b'\x30\x83\x00'+der[2:], b'\x30\x81\x01\x00'):
            with self.assertRaises(ValueError):
                CLI.certificate_spki_sha256(bad)

    def test_parent_preserves_definitive_not_dispatched(self):
        response = {'transport_error': 'tls_compromised_certificate_key', 'outcome': 'not_dispatched'}
        with patch.object(CLI.subprocess, 'run', return_value=types.SimpleNamespace(returncode=0, stdout=json.dumps(response).encode())):
            with self.assertRaises(CLI.Failure) as caught:
                CLI.request('https://example.invalid', '/test', token='dummy-not-real')
        self.assertEqual(caught.exception.result['outcome'], 'not_dispatched')
        self.assertFalse(caught.exception.result['retry_action'])

    def test_failure_after_http_attempt_stays_unknown(self):
        def late_failure():
            raise ssl.SSLCertVerificationError('simulated late TLS failure')
        conn = types.SimpleNamespace(connect=lambda: None, sock=None, request=lambda *a: None,
                                     getresponse=late_failure, close=lambda: None)
        payload = {'url': 'https://example.invalid', 'path': '/', 'method': 'POST',
                   'body': {'test': True}, 'timeout': 3}
        output = io.StringIO()
        with patch.object(CLI, 'connection_for_url', return_value=conn), \
             patch.object(CLI.sys, 'stdin', types.SimpleNamespace(buffer=io.BytesIO(json.dumps(payload).encode()))), \
             patch.object(CLI.sys, 'stdout', output):
            CLI._request_child()
        self.assertEqual(json.loads(output.getvalue()),
                         {'transport_error': 'tls_certificate_verification_failed', 'outcome': 'unknown'})


class QuietHTTP(http.server.BaseHTTPRequestHandler):
    def handle(self):
        try:
            super().handle()
        except ConnectionResetError:
            # The client deliberately closes immediately when the TLS gate rejects.
            pass

    def do_GET(self):
        self.server.received.append(dict(self.headers.items()))
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, *args):
        pass


class ConnectProxy(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(4)
        data = b''
        while b'\r\n\r\n' not in data and len(data) < 8192:
            part = self.request.recv(1)
            if not part:
                return
            data += part
        self.server.received.append(data)
        if not data.startswith(b'CONNECT '):
            return
        with socket.create_connection(('127.0.0.1', self.server.target_port), timeout=4) as upstream:
            self.request.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            while True:
                ready, _, _ = select.select([self.request, upstream], [], [], 4)
                if not ready:
                    return
                for current in ready:
                    try:
                        chunk = current.recv(65536)
                        if not chunk:
                            return
                        (upstream if current is self.request else self.request).sendall(chunk)
                    except (OSError, TimeoutError):
                        return


class LocalTLSAndProxy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='airtap-tls-test-')
        cls.directory = Path(cls.temp.name)
        openssl = shutil.which('openssl')
        if not openssl:
            raise RuntimeError('OpenSSL CLI is required only for ephemeral loopback TLS test certificates')
        config = cls.directory / 'cert.cnf'
        config.write_text('[req]\nprompt=no\ndistinguished_name=dn\n[dn]\nCN=Airtap Local TLS Test\n[ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n[leaf]\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\nauthorityKeyIdentifier=keyid,issuer\n')
        def run(*args):
            subprocess.run([openssl, *args], cwd=cls.directory, check=True, capture_output=True, timeout=30)
        run('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'ca.key', '-out', 'ca.pem', '-days', '2', '-config', str(config), '-extensions', 'ca')
        run('req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'leaf.key', '-out', 'leaf.csr', '-subj', '/CN=localhost')
        run('x509', '-req', '-in', 'leaf.csr', '-CA', 'ca.pem', '-CAkey', 'ca.key', '-CAcreateserial', '-out', 'leaf.pem', '-days', '2', '-extfile', str(config), '-extensions', 'leaf')
        cls.ca = cls.directory / 'ca.pem'
        cls.leaf_spki = CLI.certificate_spki_sha256(ssl.PEM_cert_to_DER_cert((cls.directory/'leaf.pem').read_text()))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.directory/'leaf.pem', self.directory/'leaf.key')
        self.http = http.server.ThreadingHTTPServer(('127.0.0.1', 0), QuietHTTP)
        self.http.received = []
        self.http.socket = context.wrap_socket(self.http.socket, server_side=True)
        self.http.daemon_threads = True
        self.proxy = socketserver.ThreadingTCPServer(('127.0.0.1', 0), ConnectProxy)
        self.proxy.daemon_threads = True
        self.proxy.received = []
        self.proxy.target_port = self.http.server_port
        for server in (self.http, self.proxy):
            threading.Thread(target=server.serve_forever, daemon=True).start()
        self.real_context = ssl.create_default_context
        self.environ = patch.dict(os.environ, {'https_proxy': '', 'no_proxy': ''})
        self.environ.start()

    def tearDown(self):
        self.environ.stop()
        for server in (self.proxy, self.http):
            server.shutdown()
            server.server_close()

    def request(self, proxy=False, blocked=False, hostname='localhost', trusted=True):
        if proxy:
            os.environ['https_proxy'] = 'http://test-user:test-proxy-password@127.0.0.1:' + str(self.proxy.server_address[1])
        payload = {'url': 'https://'+hostname+':'+str(self.http.server_port), 'path': '/', 'method': 'GET',
                   'token': 'dummy-test-bearer-never-real', 'body': None, 'timeout': 3}
        output = io.StringIO()
        context = lambda: self.real_context(cafile=str(self.ca)) if trusted else self.real_context()
        blocked_keys = CLI.COMPROMISED_SPKI_SHA256 | ({self.leaf_spki} if blocked else set())
        with patch.object(CLI.ssl, 'create_default_context', context), patch.object(CLI, 'COMPROMISED_SPKI_SHA256', blocked_keys), \
             patch.object(CLI.sys, 'stdin', types.SimpleNamespace(buffer=io.BytesIO(json.dumps(payload).encode()))), \
             patch.object(CLI.sys, 'stdout', output):
            CLI._request_child()
        return json.loads(output.getvalue())

    def test_valid_chain_get_succeeds_with_bearer(self):
        result = self.request()
        self.assertEqual(result['status'], 200)
        self.assertEqual(len(self.http.received), 1)
        self.assertEqual(self.http.received[0].get('Authorization'), 'Bearer dummy-test-bearer-never-real')

    def test_blocked_key_sends_no_http_request_or_bearer(self):
        result = self.request(blocked=True)
        self.assertEqual(result, {'transport_error': 'tls_compromised_certificate_key', 'outcome': 'not_dispatched'})
        self.assertEqual(self.http.received, [])

    def test_connect_keeps_proxy_and_target_credentials_separate(self):
        result = self.request(proxy=True)
        self.assertEqual(result['status'], 200)
        self.assertEqual(len(self.proxy.received), 1)
        connect = self.proxy.received[0]
        self.assertIn(b'Proxy-Authorization: Basic '+base64.b64encode(b'test-user:test-proxy-password'), connect)
        self.assertNotIn(b'dummy-test-bearer', connect)
        self.assertEqual(self.http.received[0].get('Authorization'), 'Bearer dummy-test-bearer-never-real')
        self.assertNotIn('Proxy-Authorization', self.http.received[0])

    def test_connect_blocked_key_never_sends_bearer_and_does_not_retry(self):
        result = self.request(proxy=True, blocked=True)
        self.assertEqual(result, {'transport_error': 'tls_compromised_certificate_key', 'outcome': 'not_dispatched'})
        self.assertEqual(len(self.proxy.received), 1)
        self.assertNotIn(b'dummy-test-bearer', self.proxy.received[0])
        self.assertEqual(self.http.received, [])

    def test_system_trust_and_hostname_failures_are_still_enforced(self):
        for settings in ({'trusted': False}, {'hostname': '127.0.0.1'}, {'proxy': True, 'hostname': 'wrong.example.invalid'}):
            with self.subTest(settings=settings):
                result = self.request(**settings)
                self.assertEqual(result, {'transport_error': 'tls_certificate_verification_failed', 'outcome': 'not_dispatched'})
        self.assertEqual(self.http.received, [])


if __name__ == '__main__':
    unittest.main()
