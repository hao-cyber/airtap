#!/usr/bin/env python3
"""Bounded connection probes and safe resumption of known transfer failures."""
import base64
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('airtap_rate', ROOT / 'cli/airtap.py')
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class RateWaitTest(unittest.TestCase):
    def args(self, command='file-upload', budget=120):
        return SimpleNamespace(command=command, _file_rate_wait_remaining=budget,
                               config=Path('/unused'), timeout=25, allow_local_http=False)

    def limited(self):
        return cli.Failure('ingress_rate_limited', outcome='not_dispatched', status=429)

    def test_exact_http_response_only(self):
        for body, status, code in [({'error': 'ingress_rate_limited'}, 429, 'ingress_rate_limited'),
                                  ({'error': 'quota_exceeded'}, 429, 'http_error'),
                                  ({'error': 'ingress_rate_limited', 'private': 'content'}, 429, 'http_error'),
                                  ({'error': 'ingress_rate_limited'}, 503, 'http_error')]:
            result = {'status': status, 'content_type': 'application/json',
                      'body': base64.b64encode(json.dumps(body).encode()).decode()}
            with self.subTest(body=body, status=status), patch.object(cli.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=json.dumps(result).encode())):
                with self.assertRaises(cli.Failure) as failure:
                    cli.request('https://relay.example', '/v1/command', method='POST')
                self.assertEqual(failure.exception.result['error'], code)
                self.assertEqual(failure.exception.result['outcome'], 'not_dispatched' if code == 'ingress_rate_limited' else 'unknown')
                self.assertNotIn('private', json.dumps(failure.exception.result))

    def test_retry_consumes_new_id_sequence_preserves_transfer_bytes(self):
        args = self.args()
        config = {'url': 'https://relay.example', 'controller_token': 'a' * 64, 'command_sequence': 41}
        sent = []
        def request(*unused, body, **kwargs):
            sent.append(body)
            if len(sent) == 1: raise self.limited()
            return {'data': {'id': body['id'], 'ok': True, 'result': {}}, 'elapsed_ms': 1}
        fields = {'transfer_id': 'b' * 32, 'offset': 4096, 'data_base64': 'YWJj', 'sha256': 'c' * 64}
        with patch.object(cli, 'request', side_effect=request), patch.object(cli, 'save_config'), patch.object(cli.time, 'sleep') as sleep:
            cli.file_call(args, config, 'file_upload_chunk', **fields)
        self.assertEqual([body['sequence'] for body in sent], [42, 43])
        self.assertNotEqual(sent[0]['id'], sent[1]['id'])
        for body in sent:
            self.assertEqual({k: body[k] for k in fields}, fields)
        sleep.assert_called_once_with(60)

    def test_budget_shared_between_chunks_and_bounded(self):
        args = self.args(budget=120)
        with patch.object(cli, 'command_request', side_effect=[self.limited(), {}, self.limited(), {}, self.limited()]) as request, patch.object(cli.time, 'sleep') as sleep:
            cli.file_call(args, {}, 'file_read', offset=0)
            cli.file_call(args, {}, 'file_read', offset=4096)
            with self.assertRaises(cli.Failure): cli.file_call(args, {}, 'file_read', offset=8192)
        self.assertEqual(args._file_rate_wait_remaining, 0)
        self.assertEqual(request.call_count, 5)
        self.assertEqual(sleep.call_count, 2)

    def test_unknown_results_never_retried(self):
        for failure in [cli.Failure('http_error', status=429, outcome='unknown'),
                        cli.Failure('http_error', status=503, outcome='unknown'),
                        cli.Failure('network_failure', outcome='unknown'),
                        cli.Failure('ingress_rate_limited', outcome='unknown')]:
            with self.subTest(error=failure.result), patch.object(cli, 'command_request', side_effect=failure) as request, patch.object(cli.time, 'sleep') as sleep:
                with self.assertRaises(cli.Failure): cli.file_call(self.args(), {}, 'file_upload_finish', transfer_id='a' * 32)
                self.assertEqual(request.call_count, 1)
                sleep.assert_not_called()

    def test_single_commands_and_zero_budget_never_wait(self):
        for args in [self.args('file-upload-finish'), self.args('file-read'), self.args(budget=0), self.args(budget=59)]:
            with self.subTest(command=args.command, budget=args._file_rate_wait_remaining), patch.object(cli, 'command_request', side_effect=self.limited()) as request, patch.object(cli.time, 'sleep') as sleep:
                with self.assertRaises(cli.Failure): cli.file_call(args, {}, 'file_upload_finish')
                self.assertEqual(request.call_count, 1)
                sleep.assert_not_called()

    def test_bad_budget_rejected_before_any_request(self):
        for value in [-1, 1201, True, float('nan')]:
            args = self.args(); args.rate_limit_wait = value
            with self.subTest(value=value), patch.object(cli, 'file_call') as call:
                with self.assertRaises(cli.Failure) as failure: cli.file_command(args, {})
                self.assertEqual(failure.exception.result['error'], 'invalid_rate_limit_wait')
                call.assert_not_called()

    def test_exhausted_upload_keeps_durable_transfer_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory).resolve() / 'input.txt'; file.write_bytes(b'unchanged fixture')
            args = self.args(budget=0)
            args.root = 'workspace'; args.path = 'fixture.txt'; args.input = file
            args.transfer_id = 'b' * 32; args.rate_limit_wait = 0
            config = {}
            with patch.object(cli, 'save_config') as save, patch.object(cli, 'command_request', side_effect=self.limited()), patch.object(cli.time, 'sleep') as sleep:
                with self.assertRaises(cli.Failure) as failure: cli.file_command(args, config)
            self.assertEqual(config['last_file_upload']['transfer_id'], args.transfer_id)
            self.assertEqual(failure.exception.result['transfer_id'], args.transfer_id)
            self.assertIn('same input bytes', failure.exception.result['instruction'])
            save.assert_called_once()
            sleep.assert_not_called()

    def test_wait_ready_retains_bounded_read_only_retry(self):
        args = self.args('wait-ready'); args.wait_seconds = 10
        status = {'deviceConnected': True, 'pending': 0}
        ping = {'result': {'authorized': True, 'screen_state': 'ready', 'auto_wake': True}}
        with patch.object(cli.time, 'monotonic', return_value=0), patch.object(cli.time, 'sleep') as sleep, patch.object(cli, 'status_request', side_effect=[self.limited(), status]) as request, patch.object(cli, 'command_request', return_value=ping):
            self.assertTrue(cli.wait_ready(args, {})['ready'])
        self.assertEqual(request.call_count, 2)
        sleep.assert_called_once_with(2)
        with patch.object(cli.time, 'monotonic', side_effect=[0, 0, 0, 10, 10]), patch.object(cli.time, 'sleep') as sleep, patch.object(cli, 'status_request', side_effect=self.limited()):
            with self.assertRaises(cli.Failure) as failure: cli.wait_ready(args, {})
            self.assertEqual(failure.exception.result['error'], 'ready_wait_expired')
            sleep.assert_not_called()


class ConnectionRecoveryTest(unittest.TestCase):
    """Transient recovery must not turn into business-action replay."""

    def setUp(self):
        self.args = SimpleNamespace(config=Path('/unused'), timeout=25,
                                    wait_seconds=60, allow_local_http=False)
        self.config = {'url': 'https://relay.example', 'controller_token': 'a' * 64,
                       'state': 'approved', 'command_sequence': 41}
        self.now = 0
        self.sleeps = []

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def clock(self):
        return self.now

    def status(self, connected=True, pending=0):
        return {'deviceConnected': connected, 'pending': pending}

    def ping(self, state='ready'):
        return {'result': {'authorized': True, 'screen_state': state, 'auto_wake': True}}

    def test_offline_and_pending_are_probed_before_one_ping(self):
        statuses = [self.status(False), self.status(True, 1), self.status()]
        with patch.object(cli.time, 'monotonic', self.clock), patch.object(cli.time, 'sleep', self.sleep), \
             patch.object(cli, 'status_request', side_effect=statuses), \
             patch.object(cli, 'command_request', return_value=self.ping()) as command:
            result = cli.wait_ready(self.args, self.config)
        self.assertTrue(result['ready'])
        self.assertEqual(result['attempts'], 3)
        self.assertEqual(self.sleeps, [2, 2])
        self.assertEqual(command.call_count, 1)
        self.assertEqual(command.call_args.args[2]['action'], 'ping')

    def test_wait_expires_without_dispatching_to_offline_phone(self):
        with patch.object(cli.time, 'monotonic', self.clock), patch.object(cli.time, 'sleep', self.sleep), \
             patch.object(cli, 'status_request', return_value=self.status(False)) as status, \
             patch.object(cli, 'command_request') as command:
            with self.assertRaises(cli.Failure) as failure:
                cli.wait_ready(self.args, self.config)
        self.assertEqual(failure.exception.result['error'], 'ready_wait_expired')
        self.assertEqual(self.now, 60)
        self.assertEqual(status.call_count, 30)
        command.assert_not_called()
        self.assertEqual(self.config['state'], 'approved')

    def test_lost_ping_response_uses_new_probe_identity_and_sequence(self):
        sent = []
        def request(*unused, body, **kwargs):
            sent.append(body)
            if len(sent) == 1:
                raise cli.Failure('http_error', status=503, outcome='unknown', retry_action=False)
            return {'data': {'id': body['id'], 'ok': True, **self.ping()}, 'elapsed_ms': 1}
        with patch.object(cli.time, 'monotonic', self.clock), patch.object(cli.time, 'sleep', self.sleep), \
             patch.object(cli, 'status_request', return_value=self.status()), \
             patch.object(cli, 'save_config'), patch.object(cli, 'request', side_effect=request):
            result = cli.wait_ready(self.args, self.config)
        self.assertTrue(result['ready'])
        self.assertEqual([body['action'] for body in sent], ['ping', 'ping'])
        self.assertEqual([body['sequence'] for body in sent], [42, 43])
        self.assertNotEqual(sent[0]['id'], sent[1]['id'])

    def test_unknown_business_action_is_not_replayed_during_recovery(self):
        sent = []
        def request(*unused, body, **kwargs):
            sent.append(body)
            if body['action'] == 'tap':
                raise cli.Failure('network_failure', outcome='unknown', retry_action=False)
            return {'data': {'id': body['id'], 'ok': True, **self.ping()}, 'elapsed_ms': 1}
        with patch.object(cli.time, 'monotonic', self.clock), patch.object(cli.time, 'sleep', self.sleep), \
             patch.object(cli, 'status_request', side_effect=[self.status(False), self.status()]), \
             patch.object(cli, 'save_config'), patch.object(cli, 'request', side_effect=request):
            with self.assertRaises(cli.Failure) as failure:
                cli.command_request(self.args, self.config, {
                    'id': '41ab7782-0c9a-4e51-8d6c-185fa05013fe', 'action': 'tap',
                    'package': 'com.example.fixture', 'observation_id': 'b' * 32, 'x': 0.5, 'y': 0.5})
            result = cli.wait_ready(self.args, self.config)
        self.assertEqual(failure.exception.result['outcome'], 'unknown')
        self.assertFalse(failure.exception.result['retry_action'])
        self.assertTrue(result['ready'])
        self.assertEqual([body['action'] for body in sent], ['tap', 'ping'])
        self.assertEqual([body['sequence'] for body in sent], [42, 43])
        self.assertEqual(self.config['state'], 'approved')

    def test_authorization_and_tls_failures_stop_at_status_or_ping(self):
        failures = [cli.Failure('http_error', status=status, outcome='not_available')
                    for status in (401, 403, 410)]
        failures += [cli.Failure(code, outcome='not_dispatched') for code in (
            'pairing_revoked', 'approval_required', 'phone_authorization_not_confirmed',
            'device_authentication_required', 'tls_compromised_certificate_key',
            'tls_verified_chain_unavailable', 'tls_verification_required',
            'tls_certificate_verification_failed')]
        for failure in failures:
            for phase in ('status', 'ping'):
                with self.subTest(error=failure.result, phase=phase), \
                     patch.object(cli.time, 'monotonic', self.clock), patch.object(cli.time, 'sleep') as sleep, \
                     patch.object(cli, 'status_request', side_effect=failure if phase == 'status' else None,
                                  return_value=self.status()) as status, \
                     patch.object(cli, 'command_request', side_effect=failure) as command:
                    with self.assertRaises(cli.Failure) as caught:
                        cli.wait_ready(self.args, self.config)
                    self.assertIs(caught.exception, failure)
                    self.assertEqual(status.call_count, 1)
                    self.assertEqual(command.call_count, 0 if phase == 'status' else 1)
                    sleep.assert_not_called()

    def test_readiness_never_authenticates_or_wakes_the_phone(self):
        for state, ready, prepare, auth in (
            ('authentication_required', False, False, True),
            ('screen_off', True, True, False),
            ('dismissible_keyguard', True, True, False),
            ('unavailable', False, False, None),
        ):
            with self.subTest(state=state), patch.object(cli.time, 'monotonic', self.clock), \
                 patch.object(cli.time, 'sleep') as sleep, \
                 patch.object(cli, 'status_request', return_value=self.status()), \
                 patch.object(cli, 'command_request', return_value=self.ping(state)) as command:
                result = cli.wait_ready(self.args, self.config)
                self.assertEqual(result['ready'], ready)
                self.assertEqual(result['will_prepare_screen'], prepare)
                self.assertEqual(result['needs_system_authentication'], auth)
                self.assertEqual(command.call_count, 1)
                self.assertEqual(command.call_args.args[2]['action'], 'ping')
                sleep.assert_not_called()


if __name__ == '__main__': unittest.main()
