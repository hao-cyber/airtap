"""CLI lease intents, lost responses and process locking. Synthetic records only."""
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('task_cli', ROOT / 'cli/airtap.py')
cli = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cli)
TASK, LEASE, CLAIM = '1' * 32, '2' * 32, '3' * 32
TOKEN = 'a' * 64


class TaskTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name).resolve() / 'phone.json'
        self.args = SimpleNamespace(config=self.path, timeout=25, allow_local_http=False,
                                    task_id=None, command='task-acquire', wait_seconds=10)
        self.config = {'url': 'https://relay.example', 'controller_token': TOKEN,
                       'state': 'approved', 'session_expires_at': None, 'persistent': True,
                       'all_apps': True, 'allow_control': True, 'command_sequence': 41,
                       'mode': 'multi', 'claim_id': CLAIM, 'lease_required': True, 'lease_ready': True}
        self.now = 1000.0
        self.clock = patch.object(cli.time, 'time', side_effect=lambda: self.now)
        self.clock.start()
        cli.save_config(self.path, self.config)

    def tearDown(self):
        self.clock.stop()
        self.temp.cleanup()

    def saved(self):
        return cli.load_config(self.path)

    def ready(self, task=TASK, lease=LEASE, epoch=2, ttl=120000):
        return {'data': {'protocol_version': 4, 'ok': True, 'state': 'ready',
                         'task_id': task, 'lease_id': lease, 'lease_epoch': epoch,
                         'expires_at_ms': int(self.now * 1000 + ttl), 'expires_in_ms': ttl}, 'elapsed_ms': 10}

    def give_lease(self, ttl=120000):
        self.config['task_lease'] = cli.checked_task_ready(self.ready(ttl=ttl), {'task_id': TASK})
        self.config['snapshot'] = {'id': 'b' * 32}
        self.config['observation'] = {'observation_id': 'c' * 32}
        cli.save_config(self.path, self.config)

    def status(self, task=None):
        return {'data': {**{k: self.config[k] for k in ('persistent', 'all_apps', 'allow_control', 'mode', 'claim_id', 'lease_required', 'lease_ready')},
                         'protocol_version': 4, 'session_expires_at': None, 'command_sequence': 40,
                         'deviceConnected': True, 'pending': 0, 'connection_id': 'd' * 32,
                         'task': task or {'state': 'idle', 'task_id': None, 'lease_id': None,
                                          'lease_epoch': None, 'expires_at_ms': None}}, 'elapsed_ms': 5}

    def failure(self, error, call):
        with self.assertRaises(cli.Failure) as failure: call()
        self.assertEqual(failure.exception.result['error'], error)
        return failure.exception.result

    def test_acquire_persisted_before_send_and_original_identity_preserved(self):
        def send(*unused, body, **kwargs):
            saved = self.saved()
            self.assertEqual(saved['task_lease'], {'state': 'acquiring', 'task_id': body['task_id']})
            return self.ready(task=body['task_id'])
        with patch.object(cli, 'status_request', return_value={}), patch.object(cli, 'request', side_effect=send):
            result = cli.acquire_task(self.args, self.config)
        self.assertEqual(result['state'], 'ready')
        self.assertEqual(self.saved()['controller_token'], TOKEN)
        self.assertEqual(self.saved()['command_sequence'], 41)
        self.assertEqual(self.saved()['claim_id'], CLAIM)
        self.assertTrue(self.saved()['task_needs_observation'])
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)

    def test_lost_acquire_reuses_intent_after_config_reload(self):
        sent = []
        def send(*unused, body, **kwargs):
            sent.append(body)
            if len(sent) == 1: raise cli.Failure('network_failure', outcome='unknown')
            return self.ready(task=body['task_id'])
        with patch.object(cli, 'status_request', return_value={}), patch.object(cli, 'request', side_effect=send):
            self.failure('network_failure', lambda: cli.acquire_task(self.args, self.config))
            cli.acquire_task(self.args, self.saved())
        self.assertEqual(sent[0], sent[1])
        self.assertEqual(len(sent), 2)

    def test_acquire_status_recovers_own_ready_without_another_post(self):
        self.config['task_lease'] = {'state': 'acquiring', 'task_id': TASK}
        state = {k: v for k, v in self.ready()['data'].items() if k in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')}
        with patch.object(cli, 'request', return_value=self.status(state)) as request:
            self.assertEqual(cli.acquire_task(self.args, self.config)['task_id'], TASK)
        self.assertEqual(request.call_count, 1)
        self.assertEqual(request.call_args.args[1], '/v1/status')
        self.assertEqual(self.saved()['command_sequence'], 41)

    def test_busy_status_never_adopts_another_task_and_does_not_acquire_implicitly(self):
        state = {'state': 'busy', 'task_id': None, 'lease_id': None, 'lease_epoch': None, 'expires_at_ms': None}
        with patch.object(cli, 'request', return_value=self.status(state)) as request:
            cli.status_request(self.args, self.config)
            self.failure('task_acquire_required', lambda: cli.command_request(self.args, self.config, {'id': 'id', 'action': 'home'}))
        self.assertNotIn('task_lease', self.config)
        self.assertEqual(request.call_count, 1)

    def test_fence_pending_persists_acquire_not_ready(self):
        with patch.object(cli, 'status_request', return_value={}), patch.object(cli, 'request', side_effect=cli.Failure('task_fence_pending')):
            self.failure('task_fence_pending', lambda: cli.acquire_task(self.args, self.config))
        self.assertEqual(self.saved()['task_lease']['state'], 'acquiring')

    def test_competing_explicit_task_not_replace_active_or_uncertain_intent(self):
        self.args.task_id = 'f' * 32
        for state in ('ready', 'acquiring'):
            self.give_lease(); self.config['task_lease']['state'] = state
            with self.subTest(state=state), patch.object(cli, 'status_request', return_value={}), patch.object(cli, 'request') as request:
                self.failure('task_already_active' if state == 'ready' else 'task_acquire_pending', lambda: cli.acquire_task(self.args, self.config))
                request.assert_not_called()

    def test_recovered_status_renewal_sequence_is_used_without_guessing(self):
        state = {k: v for k, v in self.ready()['data'].items() if k in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')}
        state['renewal_sequence'] = 9
        cli.reconcile_task(self.args, self.config, state)
        response = self.ready(); response['data']['renewal_sequence'] = 10
        with patch.object(cli, 'request', return_value=response) as request:
            cli.renew_task(self.args, self.config)
        self.assertEqual(request.call_args.kwargs['body']['renewal_sequence'], 10)
        self.assertEqual(self.saved()['task_lease']['renewal_sequence'], 10)

    def test_recovered_status_without_renewal_sequence_never_guesses_zero(self):
        state = {k: v for k, v in self.ready()['data'].items() if k in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')}
        cli.reconcile_task(self.args, self.config, state)
        with patch.object(cli, 'request') as request:
            self.failure('renewal_sequence_unknown', lambda: cli.renew_task(self.args, self.config))
            request.assert_not_called()

    def test_renew_response_sequence_must_match_persisted_intent(self):
        self.give_lease()
        response = self.ready(); response['data']['renewal_sequence'] = 2
        with patch.object(cli, 'request', return_value=response):
            self.failure('invalid_task_response', lambda: cli.renew_task(self.args, self.config))
        self.assertEqual(self.saved()['task_lease']['pending_renewal']['renewal_sequence'], 1)

    def test_parameters_and_forged_task_responses_rejected(self):
        for value in ('bad', True, '', 'A' * 32):
            self.args.task_id = value
            with patch.object(cli, 'status_request') as status:
                self.failure('invalid_task_id', lambda: cli.acquire_task(self.args, self.config)); status.assert_not_called()
        for field, value in [('lease_epoch', True), ('expires_in_ms', 120001), ('expires_in_ms', 0),
                             ('expires_at_ms', True), ('task_id', '9' * 32), ('lease_id', 'bad'), ('ok', 1)]:
            response = self.ready(); response['data'][field] = value
            with self.subTest(field=field, value=value):
                self.failure('invalid_task_response', lambda: cli.checked_task_ready(response, {'task_id': TASK}))

    def test_status_watermark_is_null_when_other_task_private_and_integer_when_fencing(self):
        for state in ('idle', 'busy'):
            value = {'state': state, 'task_id': None, 'lease_id': None, 'lease_epoch': None,
                     'expires_at_ms': None, 'renewal_sequence': None}
            self.assertEqual(cli.safe_task_status(value)['state'], state)
            self.failure('invalid_task_status', lambda: cli.safe_task_status({**value, 'renewal_sequence': 3}))
        value = {'state': 'fencing', 'task_id': TASK, 'lease_id': None, 'lease_epoch': 2,
                 'expires_at_ms': 1120000, 'renewal_sequence': 7}
        self.assertEqual(cli.safe_task_status(value)['renewal_sequence'], 7)
        self.failure('invalid_task_status', lambda: cli.safe_task_status({**value, 'renewal_sequence': True}))

    def test_acquire_does_not_enable_old_single_mode(self):
        self.config.pop('mode')
        with patch.object(cli, 'status_request', return_value={}), patch.object(cli, 'request') as request:
            self.failure('task_leases_not_supported', lambda: cli.acquire_task(self.args, self.config))
            request.assert_not_called()

    def test_renew_persisted_monotonic_and_lost_response_same_body(self):
        self.give_lease(ttl=35000)
        sent = []
        def send(*unused, body, **kwargs):
            sent.append(body)
            self.assertEqual(self.saved()['task_lease']['pending_renewal']['renewal_id'], body['renewal_id'])
            if len(sent) == 1: raise cli.Failure('network_failure', outcome='unknown')
            return self.ready()
        with patch.object(cli, 'request', side_effect=send):
            self.failure('network_failure', lambda: cli.renew_task(self.args, self.config))
            self.config = self.saved()
            cli.renew_task(self.args, self.config)
            cli.renew_task(self.args, self.config)
        self.assertEqual(sent[0], sent[1])
        self.assertEqual([x['renewal_sequence'] for x in sent], [1, 1, 2])
        self.assertNotEqual(sent[1]['renewal_id'], sent[2]['renewal_id'])
        self.assertNotIn('snapshot', self.saved())  # Unknown renewal transport invalidates local visual assumptions.
        self.assertNotIn('pending_renewal', self.saved()['task_lease'])

    def test_confirmed_renewal_keeps_current_snapshot_and_epoch(self):
        self.give_lease()
        with patch.object(cli, 'request', return_value=self.ready()):
            cli.renew_task(self.args, self.config)
        self.assertEqual(self.saved()['snapshot']['id'], 'b' * 32)
        self.assertEqual(self.saved()['task_lease']['lease_epoch'], 2)

    def test_explicit_renew_retry_can_resolve_ack_lost_after_local_old_deadline(self):
        self.give_lease(ttl=1000)
        self.config['task_lease']['pending_renewal'] = {'renewal_id': '4' * 32, 'renewal_sequence': 1}
        self.now += 2
        with patch.object(cli, 'request', return_value=self.ready()) as request:
            cli.renew_task(self.args, self.config)
        self.assertEqual(request.call_args.kwargs['body']['renewal_id'], '4' * 32)

    def test_expired_or_clock_regressed_task_does_not_renew_or_dispatch(self):
        for jump in (-1, 130):
            self.now = 1000; self.give_lease(); self.now += jump
            with patch.object(cli, 'request') as request:
                self.failure('task_acquire_required', lambda: cli.command_request(self.args, self.config, {'id': 'id', 'action': 'home'}))
                request.assert_not_called()
            self.assertNotIn('snapshot', self.saved())

    def test_automatic_renew_happens_once_before_command_and_keeps_lease(self):
        self.give_lease(ttl=35000)
        sent = []
        def send(url, path, token=None, body=None, **kwargs):
            sent.append((path, body))
            if path.endswith('/renew'): return self.ready()
            return {'data': {'id': body['id'], 'ok': True, 'result': {'accepted': True}}, 'elapsed_ms': 5}
        with patch.object(cli, 'request', side_effect=send):
            cli.command_request(self.args, self.config, {'id': 'id', 'action': 'home'})
            cli.command_request(self.args, self.config, {'id': 'id2', 'action': 'back'})
        self.assertEqual([p for p, _ in sent], ['/v1/tasks/renew', '/v1/command', '/v1/command'])
        for _, body in sent[1:]: self.assertEqual((body['lease_id'], body['lease_epoch']), (LEASE, 2))
        self.assertEqual(self.saved()['command_sequence'], 43)
        self.assertEqual(self.saved()['task_lease']['state'], 'ready')

    def test_unknown_command_not_retried_consumes_sequence_and_requires_status(self):
        self.give_lease()
        with patch.object(cli, 'request', side_effect=cli.Failure('network_failure', outcome='unknown')) as request:
            self.failure('network_failure', lambda: cli.command_request(self.args, self.config, {'id': 'id', 'action': 'home'}))
            self.failure('task_status_required', lambda: cli.command_request(self.args, self.config, {'id': 'id2', 'action': 'home'}))
        self.assertEqual(request.call_count, 1)
        self.assertEqual(self.saved()['command_sequence'], 42)
        self.assertNotIn('snapshot', self.saved())
        self.assertTrue(self.saved()['task_needs_observation'])

    def test_expired_server_lease_blocks_old_targets_without_reacquiring(self):
        self.give_lease()
        with patch.object(cli, 'request', side_effect=cli.Failure('lease_expired', outcome='not_dispatched')) as request:
            self.failure('lease_expired', lambda: cli.command_request(self.args, self.config, {'id': 'id', 'action': 'home'}))
        self.assertEqual(request.call_count, 1)
        self.assertEqual(self.saved()['task_lease']['state'], 'lost')
        self.assertNotIn('snapshot', self.saved())

    def test_new_task_requires_fresh_observation_not_just_ping(self):
        self.give_lease(); self.config['task_needs_observation'] = True
        def send(url, path, token=None, body=None, **kwargs):
            return {'data': {'id': body['id'], 'ok': True, 'result': {}}, 'elapsed_ms': 1}
        with patch.object(cli, 'request', side_effect=send) as request:
            cli.command_request(self.args, self.config, {'id': 'id', 'action': 'ping'})
            self.failure('fresh_task_observation_required', lambda: cli.command_request(self.args, self.config, {'id': 'id2', 'action': 'back'}))
        self.assertEqual(request.call_count, 1)
        value = {'snapshot_id': 'd' * 32, 'package': 'example.app', 'expires_in_ms': 30000, 'nodes': []}
        cli.remember_snapshot(self.args, self.config, value, 'example.app', self.now)
        self.assertFalse(self.saved()['task_needs_observation'])

    def test_home_escape_does_not_remove_new_task_observation_requirement(self):
        self.give_lease(); self.config['task_needs_observation'] = True
        def send(*unused, body=None, **kwargs):
            return {'data': {'id': body['id'], 'ok': True, 'result': {'accepted': True}}, 'elapsed_ms': 1}
        with patch.object(cli, 'request', side_effect=send):
            cli.command_request(self.args, self.config, {'id': 'id', 'action': 'home'})
        self.assertTrue(self.saved()['task_needs_observation'])

    def test_direct_renew_expiration_clears_cached_targets(self):
        self.give_lease()
        with patch.object(cli, 'request', side_effect=cli.Failure('lease_expired')):
            self.failure('lease_expired', lambda: cli.renew_task(self.args, self.config))
        self.assertNotIn('snapshot', self.saved())
        self.assertEqual(self.saved()['task_lease']['state'], 'lost')

    def test_connection_change_requires_new_observation_even_same_lease_metadata(self):
        self.give_lease(); self.config['connection_id'] = 'e' * 32
        self.config['task_needs_observation'] = False
        state = {k: v for k, v in self.ready()['data'].items() if k in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')}
        with patch.object(cli, 'request', return_value=self.status(state)):
            cli.status_request(self.args, self.config)
        self.assertTrue(self.saved()['task_needs_observation'])
        self.assertNotIn('snapshot', self.saved())

    def test_oversize_final_envelope_does_not_consume_sequence_or_dispatch(self):
        self.give_lease()
        with patch.object(cli, 'request') as request:
            self.failure('request_body_too_large', lambda: cli.command_request(self.args, self.config, {'id': 'id', 'action': 'home', 'x': 'x' * 8150}))
            request.assert_not_called()
        self.assertEqual(self.saved()['command_sequence'], 41)

    def test_release_lost_response_retries_exactly_and_never_revokes(self):
        self.give_lease()
        sent = []
        def send(url, path, token=None, body=None, **kwargs):
            self.assertTrue(path.endswith('/tasks/release'))
            sent.append(body)
            self.assertEqual(self.saved()['task_lease']['state'], 'releasing')
            if len(sent) == 1: raise cli.Failure('network_failure', outcome='unknown')
            return {'data': {'protocol_version': 4, 'ok': True, 'state': 'released'}, 'elapsed_ms': 1}
        with patch.object(cli, 'request', side_effect=send):
            self.failure('network_failure', lambda: cli.release_task(self.args, self.config))
            cli.release_task(self.args, self.saved())
            cli.release_task(self.args, self.saved())
        self.assertEqual(sent[0], sent[1]); self.assertEqual(len(sent), 2)
        self.assertEqual(self.saved()['state'], 'approved')
        self.assertEqual(self.saved()['controller_token'], TOKEN)
        self.assertNotIn('snapshot', self.saved())

    def test_status_resolves_old_release_without_needing_evicted_server_receipt(self):
        for state in ('idle', 'busy'):
            self.give_lease(); self.config['task_lease']['state'] = 'releasing'
            cli.reconcile_task(self.args, self.config, {'state': state, 'task_id': None,
                'lease_id': None, 'lease_epoch': None, 'expires_at_ms': None, 'renewal_sequence': None})
            self.assertEqual(self.saved()['task_lease']['state'], 'closed')
            with patch.object(cli, 'request') as request:
                self.assertEqual(cli.release_task(self.args, self.saved())['state'], 'released')
                request.assert_not_called()

    def test_status_does_not_undo_pending_release_or_extend_known_expiry(self):
        self.give_lease()
        state = {k: v for k, v in self.ready()['data'].items() if k in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')}
        deadline = self.config['task_lease']['local_expires_at']
        self.now += 5
        state['expires_at_ms'] += 60000
        cli.reconcile_task(self.args, self.config, state)
        self.assertEqual(self.config['task_lease']['local_expires_at'], deadline)
        self.config['task_lease']['state'] = 'releasing'
        cli.reconcile_task(self.args, self.config, state)
        self.assertEqual(self.config['task_lease']['state'], 'releasing')

    def test_status_changed_lease_invalidates_both_visual_caches(self):
        self.give_lease()
        state = {k: v for k, v in self.ready(lease='f' * 32, epoch=3)['data'].items() if k in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')}
        cli.reconcile_task(self.args, self.config, state)
        self.assertNotIn('snapshot', self.config); self.assertNotIn('observation', self.config)
        self.assertTrue(self.config['task_needs_observation'])

    def test_legacy_single_and_migrated_compatible_grant_keep_old_command_format(self):
        self.config.pop('lease_required'); self.config.pop('lease_ready')
        for mode in (None, 'multi'):
            if mode is None: self.config.pop('mode', None)
            else: self.config.update(mode=mode, lease_required=False, lease_ready=False)
            def send(*unused, body=None, **kwargs):
                self.assertNotIn('lease_id', body); self.assertNotIn('lease_epoch', body)
                return {'data': {'id': body['id'], 'ok': True, 'result': {}}, 'elapsed_ms': 1}
            with patch.object(cli, 'request', side_effect=send) as request:
                cli.command_request(self.args, self.config, {'id': 'id', 'action': 'ping'})
                self.assertEqual(request.call_count, 1)

    def test_noncontract_http_content_is_not_echoed_or_treated_as_safe_retry(self):
        for data, expected in [({'protocol_version': 4, 'error': 'lease_expired', 'admitted': False}, 'lease_expired'),
                               ({'protocol_version': 4, 'error': 'lease_expired', 'admitted': False, 'secret': TOKEN}, 'http_error'),
                               ({'protocol_version': 4, 'error': 'lease_expired', 'admitted': True}, 'http_error')]:
            raw = {'status': 410, 'content_type': 'application/json', 'body': base64.b64encode(json.dumps(data).encode()).decode()}
            with patch.object(cli.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=json.dumps(raw).encode())):
                error = self.failure(expected, lambda: cli.request('https://relay.example', '/v1/command', method='POST'))
            self.assertNotIn(TOKEN, json.dumps(error))
            self.assertFalse(error['retry_action'])


class PairAndLockTest(unittest.TestCase):
    def invoke_pair(self, path, request, task_lease=False):
        argv = ['airtap', '--config', str(path), 'pair-start', '--url', 'https://relay.example',
                '--name', 'Fixture AI', '--ticket-stdin', *(['--task-lease'] if task_lease else [])]
        with patch.object(sys, 'argv', argv), patch.object(sys, 'stdin', io.StringIO('b' * 32 + '\n')), \
             patch.object(cli, 'request', side_effect=request), patch.object(cli, 'lock_config'):
            return cli.main()

    def test_new_pair_capability_is_opt_in_and_lost_response_identity_is_stable(self):
        with tempfile.TemporaryDirectory() as directory:
            for capability in (False, True):
                path = Path(directory).resolve() / ('multi.json' if capability else 'single.json')
                bodies = []
                def request(url, endpoint, token=None, body=None, **kwargs):
                    if endpoint.endswith('/status'): raise cli.Failure('http_error', status=401)
                    bodies.append(body)
                    if len(bodies) == 1: raise cli.Failure('network_failure', outcome='unknown')
                    return {'data': {'protocol_version': 4, 'state': 'pending', 'claim_id': CLAIM,
                                     'verification_code': '123456', 'pairing_expires_at': 2000000000}, 'elapsed_ms': 1}
                with self.assertRaises(cli.Failure): self.invoke_pair(path, request, capability)
                self.invoke_pair(path, request, capability)
                self.assertEqual(bodies[0], bodies[1])
                self.assertEqual(bodies[0].get('capabilities', []), ['task_lease_v1'] if capability else [])
                self.assertEqual(set(bodies[0]), {'ticket', 'controller_token', 'name', *(['capabilities'] if capability else [])})
                with self.assertRaises(cli.Failure) as failure: self.invoke_pair(path, request, not capability)
                self.assertEqual(failure.exception.result['error'], 'pairing_identity_mismatch')

    def test_actual_concurrent_process_is_rejected_without_config_write(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / 'phone.json'
            cli.save_config(path, {'synthetic': True})
            before = path.read_bytes()
            program = '''import importlib.util, pathlib, sys
spec=importlib.util.spec_from_file_location('cli',sys.argv[1]); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.lock_config(pathlib.Path(sys.argv[2])); print('locked',flush=True); sys.stdin.readline()
'''
            holder = subprocess.Popen([sys.executable, '-c', program, str(ROOT / 'cli/airtap.py'), str(path)],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertEqual(holder.stdout.readline().strip(), 'locked')
                result = subprocess.run([sys.executable, str(ROOT / 'cli/airtap.py'), '--config', str(path), 'task-acquire'],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(json.loads(result.stdout)['error'], 'another_phone_operation_in_progress')
                self.assertEqual(path.read_bytes(), before)
            finally:
                holder.communicate('\n', timeout=5)



class ProcessRoundtripTest(unittest.TestCase):
    """Actual CLI subprocesses and a loopback-only HTTP fixture, not a relay/device."""
    def test_processes_keep_task_and_recover_lost_acquire_then_release(self):
        import http.server
        import socket
        import threading
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / 'phone.json'
            state = {'task': None, 'sequence': 7, 'requests': [], 'lost': False}
            def response_task():
                task = state['task']
                if task is None:
                    return {'state': 'idle', 'task_id': None, 'lease_id': None, 'lease_epoch': None, 'expires_at_ms': None}
                return {key: task[key] for key in ('state', 'task_id', 'lease_id', 'lease_epoch', 'expires_at_ms')}
            class Handler(http.server.BaseHTTPRequestHandler):
                def log_message(self, *args): pass
                def do_GET(self):
                    state['requests'].append((self.path, None))
                    self.send_json({'protocol_version': 4, 'mode': 'multi', 'claim_id': CLAIM,
                                    'persistent': True, 'all_apps': True, 'allow_control': True,
                                    'session_expires_at': None, 'command_sequence': state['sequence'],
                                    'deviceConnected': True, 'pending': 0, 'connection_id': 'd' * 32,
                                    'lease_required': True, 'lease_ready': state['task'] is not None,
                                    'task': response_task()})
                def do_POST(self):
                    body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    state['requests'].append((self.path, body))
                    if self.path == '/v1/tasks/acquire':
                        saved = json.loads(path.read_text())
                        state['intent_saved_before_send'] = saved['task_lease']['task_id'] == body['task_id']
                        state['task'] = {'state': 'ready', 'task_id': body['task_id'], 'lease_id': LEASE,
                                         'lease_epoch': 1, 'expires_at_ms': int(time.time() * 1000) + 120000}
                        state['lost'] = True
                        self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
                    if self.path == '/v1/command':
                        if body['lease_id'] != LEASE or body['lease_epoch'] != 1: raise AssertionError('wrong lease')
                        state['sequence'] = body['sequence']
                        self.send_json({'id': body['id'], 'ok': True, 'result': {'authorized': True, 'screen_state': 'ready', 'auto_wake': True}})
                        return
                    if self.path == '/v1/tasks/release':
                        state['task'] = None
                        self.send_json({'protocol_version': 4, 'ok': True, 'state': 'released'}); return
                    raise AssertionError('unexpected POST')
                def send_json(self, data):
                    raw = json.dumps(data).encode()
                    self.send_response(200); self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            cli.save_config(path, {'url': 'http://127.0.0.1:' + str(server.server_port), 'controller_token': TOKEN,
                                  'state': 'approved', 'persistent': True, 'all_apps': True, 'allow_control': True,
                                  'session_expires_at': None, 'command_sequence': 7, 'mode': 'multi', 'lease_required': True})
            def invoke(command, success=True):
                run = subprocess.run([sys.executable, str(ROOT / 'cli/airtap.py'), '--allow-local-http', '--config', str(path), command],
                                     capture_output=True, text=True, timeout=10)
                self.assertEqual(run.returncode, 0 if success else 1, run.stdout + run.stderr)
                self.assertNotIn(TOKEN, run.stdout + run.stderr)
                return json.loads(run.stdout)
            try:
                invoke('task-acquire', False)
                acquired = invoke('task-acquire')
                self.assertEqual(acquired['lease_id'], LEASE)
                invoke('ping'); invoke('ping')
                released = invoke('task-release')
                self.assertEqual(released['state'], 'released')
                self.assertEqual(sum(p == '/v1/tasks/acquire' for p, _ in state['requests']), 1)
                self.assertEqual(sum(p == '/v1/tasks/release' for p, _ in state['requests']), 1)
                self.assertTrue(state['intent_saved_before_send'])
                final = cli.load_config(path, True)
                self.assertEqual(final['state'], 'approved')
                self.assertEqual(final['controller_token'], TOKEN)
                self.assertEqual(final['command_sequence'], 9)
            finally:
                server.shutdown(); server.server_close(); thread.join(5)


if __name__ == '__main__': unittest.main()
