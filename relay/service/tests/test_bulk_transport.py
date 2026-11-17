"""Real loopback HTTP and child processes; synthetic data, no phone or credentials."""
import http.server
import importlib.util
import json
import pathlib
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('bulk_cli', ROOT / 'cli/airtap.py')
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def get_request(self):
        sock, address = super().get_request()
        self.connections += 1
        return sock, address


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def do_POST(self):
        self.do_GET()

    def do_GET(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        self.server.requests.append((self.path, self.headers.get('Authorization'), body))
        if self.path == '/drop':
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            self.close_connection = True
            return
        raw = json.dumps({'ok': True, 'path': self.path, 'received': len(body)}).encode()
        if self.path == '/large':
            raw = b'x' * (CLI.MAX_RESPONSE + 1)
        if self.path == '/binary':
            raw = bytes(range(256)) * 512
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream' if self.path in ('/large', '/binary') else 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        if self.path == '/close':
            self.send_header('Connection', 'close')
        self.end_headers()
        try:
            if self.path == '/drip':
                for byte in raw:
                    self.wfile.write(bytes([byte]))
                    self.wfile.flush()
                    if self.server.stop.wait(.06):
                        break
            else:
                self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass


class BulkTransport(unittest.TestCase):
    def setUp(self):
        self.server = Server(('127.0.0.1', 0), Handler)
        self.server.connections = 0
        self.server.requests = []
        self.server.stop = threading.Event()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.assertIsNone(CLI._request_session)
        self.server.stop.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, path='/', **kwargs):
        return CLI.request(self.url, path, local_test=True, **kwargs)

    def test_reuses_one_connection_and_worker_only_inside_transfer(self):
        with CLI.bulk_request_session():
            session = CLI._request_session
            self.assertIsNone(session.process)  # Input errors need no worker.
            first = self.request()
            worker = session.process
            self.request('/next', body={'chunk': 'x' * 44000}, method='POST')
            last = self.request('/last')
            self.assertIs(session.process, worker)
            self.assertTrue(first['data']['ok'] and last['data']['ok'])
            self.assertEqual(self.server.connections, 1)
        self.assertIsNotNone(worker.poll())
        self.assertIsNone(session.process)
        self.request('/ordinary')
        self.assertEqual(self.server.connections, 2)

    def test_server_close_reconnects_next_request_without_replay(self):
        with CLI.bulk_request_session():
            self.request('/close')
            self.request('/next')
            self.request('/last')
        self.assertEqual(self.server.connections, 2)
        self.assertEqual([x[0] for x in self.server.requests], ['/close', '/next', '/last'])

    def test_lost_mutation_reply_is_unknown_and_sent_once(self):
        with CLI.bulk_request_session():
            self.request('/')
            with self.assertRaises(CLI.Failure) as caught:
                self.request('/drop', method='POST', body={'chunk': 'synthetic'})
            self.assertEqual(caught.exception.result['outcome'], 'unknown')
            self.assertFalse(caught.exception.result['retry_action'])
            self.request('/inspect')  # Explicit recovery read, no automatic replay.
        self.assertEqual([x[0] for x in self.server.requests], ['/', '/drop', '/inspect'])
        self.assertEqual(self.server.connections, 2)

    def test_device_path_and_credentials_never_reuse_another_identity(self):
        with CLI.bulk_request_session():
            for device, token in [('a', 'synthetic-a'), ('a', 'synthetic-a'),
                                  ('b', 'synthetic-a'), ('b', 'synthetic-b')]:
                CLI.request(self.url + '/d/' + device * 32, '/v1/status',
                            token=token, local_test=True)
        self.assertEqual(self.server.connections, 3)
        self.assertEqual([x[0] for x in self.server.requests],
                         ['/d/' + d * 32 + '/v1/status' for d in ['a', 'a', 'b', 'b']])
        self.assertEqual([x[1] for x in self.server.requests],
                         ['Bearer synthetic-a'] * 3 + ['Bearer synthetic-b'])

    def test_total_deadline_kills_worker_even_while_body_keeps_arriving(self):
        with CLI.bulk_request_session():
            self.request('/')
            worker = CLI._request_session.process
            start = time.monotonic()
            with self.assertRaises(CLI.Failure) as caught:
                self.request('/drip', timeout=.3, method='POST')
            self.assertEqual(caught.exception.result['error'], 'total_deadline_exceeded')
            self.assertEqual(caught.exception.result['outcome'], 'unknown')
            self.assertLess(time.monotonic() - start, 1.5)
            self.assertIsNotNone(worker.poll())
            self.assertIsNone(CLI._request_session.process)
        self.assertEqual([x[0] for x in self.server.requests], ['/', '/drip'])

    def test_large_response_closes_connection_and_returns_no_partial_data(self):
        with CLI.bulk_request_session():
            with self.assertRaises(CLI.Failure) as caught:
                self.request('/large')
            self.assertEqual(caught.exception.result['error'], 'response_too_large')
            self.request('/inspect')
        self.assertEqual(self.server.connections, 2)

    def test_binary_frames_larger_than_pipe_buffer_are_complete(self):
        with CLI.bulk_request_session():
            for _ in range(2):
                self.assertEqual(self.request('/binary')['bytes'], bytes(range(256)) * 512)
        self.assertEqual(self.server.connections, 1)

    def test_failure_restores_outer_session_and_closes_inner_worker(self):
        with CLI.bulk_request_session():
            outer = CLI._request_session
            self.request('/')
            with self.assertRaises(RuntimeError):
                with CLI.bulk_request_session():
                    inner = CLI._request_session
                    self.request('/inner')
                    worker = inner.process
                    raise RuntimeError('synthetic local failure')
            self.assertIs(CLI._request_session, outer)
            self.assertIsNotNone(worker.poll())
            self.request('/outer')
        self.assertEqual(self.server.connections, 2)

    def test_stalled_worker_before_network_is_also_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            script = pathlib.Path(directory) / 'stalled.py'
            script.write_text('import time\ntime.sleep(10)\n')
            with patch.object(CLI, '__file__', str(script)), CLI.bulk_request_session():
                start = time.monotonic()
                with self.assertRaises(CLI.Failure) as caught:
                    self.request('/', timeout=.25)
                self.assertEqual(caught.exception.result['error'], 'total_deadline_exceeded')
                self.assertLess(time.monotonic() - start, 1.5)
                self.assertIsNone(CLI._request_session.process)
        self.assertEqual(self.server.requests, [])


if __name__ == '__main__':
    unittest.main()
