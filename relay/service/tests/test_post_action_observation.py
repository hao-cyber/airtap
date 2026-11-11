"""CLI post-action observations through the real parser, persistence and dispatcher.

Only HTTP transport and time are synthetic. No phone, credentials or network.
"""
import base64
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('post_action_cli', ROOT / 'cli/airtap.py')
cli = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cli)
OLD, NEW, IMAGE = 'b' * 32, 'd' * 32, 'e' * 32
TOKEN, CLAIM, TASK, LEASE = 'a' * 64, '3' * 32, '1' * 32, '2' * 32
JPEG = b'\xff\xd8synthetic\xff\xd9'


class PostActionObservationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name).resolve() / 'phone.json'
        self.now = 1000.0
        self.sent = []
        self.sleeps = []
        self.read_delay = 0.0
        self.tree = {'package': 'new.app', 'snapshot_id': NEW, 'expires_in_ms': 30000,
                     'nodes': [{'node_id': '1', 'text': 'Current target'}]}
        self.config = {'url': 'https://relay.example', 'controller_token': TOKEN,
                       'state': 'approved', 'persistent': True, 'all_apps': True,
                       'allow_control': True, 'command_sequence': 41,
                       'snapshot': {'id': OLD, 'package': 'old.app', 'node_ids': ['1'],
                                    'issued_at': 999, 'expires_at': 1029},
                       'observation': {'observation_id': 'c' * 32, 'package': 'old.app',
                                       'issued_at': 999, 'expires_at': 1029}}
        cli.save_config(self.path, self.config)

    def tearDown(self):
        self.close_lock()
        self.temp.cleanup()

    def close_lock(self):
        if cli._config_lock is not None:
            os.close(cli._config_lock)
            cli._config_lock = None

    def saved(self):
        return cli.load_config(self.path)

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def transport(self, url, path, token=None, body=None, **kwargs):
        self.assertEqual((url, token), (self.config['url'], TOKEN))
        self.sent.append((path, copy.deepcopy(body)))
        if path == '/v1/tasks/renew':
            self.assertEqual(self.saved()['task_lease']['pending_renewal']['renewal_id'], body['renewal_id'])
            return {'elapsed_ms': 1, 'data': {'protocol_version': 4, 'ok': True, 'state': 'ready',
                    'task_id': TASK, 'lease_id': LEASE, 'lease_epoch': 2,
                    'renewal_sequence': body['renewal_sequence'], 'expires_in_ms': 120000,
                    'expires_at_ms': int((self.now + 120) * 1000)}}
        self.assertEqual(path, '/v1/command')
        self.assertEqual(self.saved()['command_sequence'], body['sequence'])
        self.assertEqual(uuid.UUID(body['id']).version, 4)
        action = body['action']
        if action == 'read_tree':
            self.now += self.read_delay
            value = copy.deepcopy(self.tree)
        elif action == 'observe':
            value = {'package': 'new.app', 'observation_id': IMAGE, 'expires_in_ms': 30000,
                     'coordinate_space': 'normalized_image',
                     'image_base64': base64.b64encode(JPEG).decode()}
        else:
            self.assertNotIn('snapshot', self.saved())
            self.assertNotIn('observation', self.saved())
            value = {'accepted': True, 'action': action}
        return {'data': {'id': body['id'], 'ok': True, 'result': value}, 'elapsed_ms': self.read_delay * 1000 if action == 'read_tree' else 10}

    def run_cli(self, *words, send=None):
        self.close_lock()
        with patch.object(cli.sys, 'argv', ['airtap', '--config', str(self.path), *words]), \
             patch.object(cli, 'request', side_effect=send or self.transport), \
             patch.object(cli.time, 'time', side_effect=lambda: self.now), \
             patch.object(cli.time, 'sleep', side_effect=self.sleep):
            try:
                return cli.main()
            finally:
                self.close_lock()

    def commands(self):
        return [body['action'] for path, body in self.sent if path == '/v1/command']

    def assert_accepted_error(self, result, code):
        self.assertTrue(result['ok'])
        self.assertTrue(result['result']['accepted'])
        self.assertEqual(result['observation_error']['error'], code)
        self.assertFalse(result['observation_error']['retry_action'])
        self.assertNotIn('post_action_tree', result)
        self.assertNotIn('observation', result)
        self.assertIn('do not replay', result['instruction'])

    def give_lease(self, remaining=120):
        config = self.saved()
        config.update(mode='multi', claim_id=CLAIM, lease_required=True, lease_ready=True,
                      task_needs_observation=False,
                      task_lease={'state': 'ready', 'task_id': TASK, 'lease_id': LEASE,
                                  'lease_epoch': 2, 'renewal_sequence': 0,
                                  'recorded_at': 999, 'local_expires_at': self.now + remaining})
        cli.save_config(self.path, config)

    def rootless_transport(self, *args, **kwargs):
        result = self.transport(*args, **kwargs)
        if kwargs.get('body', {}).get('action') == 'observe':
            result['data']['result'].update(package=None, target_mode='visual_window_v1',
                                          root_available=False, window_id=199, display_id=0)
        return result

    def test_rootless_visual_observation_and_tap_do_not_borrow_app_package(self):
        image = self.run_cli('observe', send=self.rootless_transport)['result']
        self.assertIsNone(image['package'])
        self.assertEqual(self.saved()['observation']['window_id'], 199)
        with self.assertRaisesRegex(cli.Failure, 'fresh_observation_required'):
            self.run_cli('tap', '--package', 'old.app', '--observation-id', IMAGE, '--x', '.5', '--y', '.7')
        result = self.run_cli('tap', '--observation-id', IMAGE, '--x', '.5', '--y', '.7', '--no-observe')
        self.assertTrue(result['result']['accepted'])
        self.assertNotIn('package', self.sent[-1][1])
        with self.assertRaisesRegex(cli.Failure, 'fresh_observation_required'):
            self.run_cli('tap', '--observation-id', IMAGE, '--x', '.5', '--y', '.7')

    def test_visual_package_can_be_inferred_only_from_current_observation(self):
        self.run_cli('observe')
        self.run_cli('tap', '--observation-id', IMAGE, '--x', '.5', '--y', '.7', '--no-observe')
        self.assertEqual(self.sent[-1][1]['package'], 'new.app')

    def test_unknown_package_requires_complete_visual_window_identity(self):
        good = dict(package=None, target_mode='visual_window_v1', root_available=False, window_id=199, display_id=0)
        self.assertTrue(cli.valid_observed_package(good))
        for field in good:
            incomplete = dict(good); incomplete.pop(field)
            self.assertFalse(cli.valid_observed_package(incomplete), field)
        for field, value in [('window_id', True), ('window_id', -1), ('display_id', False), ('display_id', 1),
                             ('root_available', True), ('package', 'io.airtap.preview')]:
            self.assertFalse(cli.valid_observed_package({**good, field: value}), (field,value))

    def test_back_and_recents_recover_failed_observation_without_unlocking_other_actions(self):
        self.give_lease()
        for command in ('back', 'recents'):
            config = self.saved(); config['task_needs_observation'] = True
            config.pop('observation', None); config.pop('snapshot', None); cli.save_config(self.path, config)
            result = self.run_cli(command, '--no-observe')
            self.assertTrue(result['result']['accepted'])
            self.assertTrue(self.saved()['task_needs_observation'])
            with self.assertRaisesRegex(cli.Failure, 'fresh_observation_required'):
                self.run_cli('tap', '--observation-id', IMAGE, '--x', '.5', '--y', '.7')

    def test_default_image_retains_output_and_consumes_two_sequences(self):
        output = self.path.parent / 'result.jpg'
        result = self.run_cli('home', '--output', str(output))
        self.assertEqual(self.commands(), ['home', 'observe'])
        self.assertEqual([body['sequence'] for _, body in self.sent], [42, 43])
        self.assertNotEqual(self.sent[0][1]['id'], self.sent[1][1]['id'])
        self.assertEqual(output.read_bytes(), JPEG)
        self.assertEqual(result['observation']['output'], str(output))
        self.assertNotIn('post_action_tree', result)
        self.assertEqual(self.saved()['observation']['observation_id'], IMAGE)
        self.assertEqual(self.saved()['controller_token'], TOKEN)
        self.assertEqual(self.sleeps, [0.35])

    def test_explicit_image_uses_existing_observation_field(self):
        result = self.run_cli('home', '--after-action', 'image')
        self.assertEqual(self.commands(), ['home', 'observe'])
        self.assertEqual(Path(result['observation']['output']).read_bytes(), JPEG)
        self.assertNotIn('post_action_tree', result)

    def test_none_and_legacy_no_observe_are_one_command_without_sleep(self):
        for option in (['--after-action', 'none'], ['--no-observe']):
            with self.subTest(option=option):
                self.sent.clear(); self.sleeps.clear()
                result = self.run_cli('home', *option)
                self.assertEqual(self.commands(), ['home'])
                self.assertTrue(result['result']['accepted'])
                self.assertNotIn('observation', result)
                self.assertNotIn('post_action_tree', result)
                self.assertNotIn('snapshot', self.saved())
                self.assertNotIn('observation', self.saved())
                self.assertFalse(self.sleeps)

    def test_tree_uses_actual_new_package_and_is_usable_in_next_process(self):
        result = self.run_cli('click', '--package', 'old.app', '--snapshot-id', OLD,
                              '--node-id', '1', '--after-action', 'tree')
        self.assertEqual(self.commands(), ['click', 'read_tree'])
        self.assertNotIn('package', self.sent[1][1])
        self.assertEqual(result['post_action_tree']['package'], 'new.app')
        self.assertNotIn('observation', result)
        self.assertFalse((self.path.parent / 'observations').exists())
        self.assertEqual(self.saved()['snapshot']['id'], NEW)
        before = len(self.sent)
        with self.assertRaises(cli.Failure) as caught:
            self.run_cli('click', '--package', 'old.app', '--snapshot-id', OLD, '--node-id', '1', '--no-observe')
        self.assertEqual(caught.exception.result['error'], 'fresh_snapshot_required')
        self.assertEqual(len(self.sent), before)
        self.run_cli('click', '--package', 'new.app', '--snapshot-id', NEW, '--node-id', '1', '--no-observe')
        self.assertEqual(self.commands(), ['click', 'read_tree', 'click'])
        self.assertEqual(self.saved()['command_sequence'], 44)

    def test_tree_ttl_starts_at_followup_request_not_response(self):
        self.read_delay = 7
        self.run_cli('home', '--after-action', 'tree')
        snapshot = self.saved()['snapshot']
        self.assertAlmostEqual(snapshot['issued_at'], 1000.35)
        self.assertAlmostEqual(snapshot['expires_at'], 1030.35)
        self.now = 1031
        before = len(self.sent)
        with self.assertRaises(cli.Failure) as caught:
            self.run_cli('click', '--package', 'new.app', '--snapshot-id', NEW, '--node-id', '1', '--no-observe')
        self.assertEqual(caught.exception.result['error'], 'fresh_snapshot_required')
        self.assertEqual(len(self.sent), before)

    def test_paged_tree_returns_only_first_page_without_implicit_paging(self):
        self.tree.update(offset=0, total_nodes=2, returned_nodes=1, total_inspected_nodes=2,
                         next_offset=1, traversal_complete=True, truncated=True)
        result = self.run_cli('home', '--after-action', 'tree')
        self.assertEqual(result['post_action_tree']['next_offset'], 1)
        self.assertEqual(self.commands(), ['home', 'read_tree'])
        self.assertEqual(self.saved()['snapshot']['node_ids'], ['1'])

    def test_invalid_tree_packages_never_become_cached_targets(self):
        for package in (None, '', 'wrong', 'io.airtap.preview', 'io.airtap.preview.child', 123):
            with self.subTest(package=package):
                self.sent.clear()
                self.tree['package'] = package
                result = self.run_cli('home', '--after-action', 'tree')
                self.assert_accepted_error(result, 'invalid_snapshot_response')
                self.assertNotIn('snapshot', self.saved())
                self.assertEqual(self.commands(), ['home', 'read_tree'])
        self.tree['package'] = 'android'
        result = self.run_cli('home', '--after-action', 'tree')
        self.assertEqual(result['post_action_tree']['package'], 'android')
        self.assertEqual(self.saved()['snapshot']['package'], 'android')

    def test_malformed_tree_preserves_acceptance_and_clears_cache(self):
        self.tree['nodes'] = [{'node_id': '1'}, {'node_id': '1'}]
        result = self.run_cli('home', '--after-action', 'tree')
        self.assert_accepted_error(result, 'invalid_snapshot_response')
        self.assertNotIn('snapshot', self.saved())
        self.assertEqual(self.saved()['command_sequence'], 43)

    def test_action_rejection_or_unknown_never_triggers_followup_or_replay(self):
        for unknown in (False, True):
            with self.subTest(unknown=unknown):
                self.sent.clear(); self.sleeps.clear()
                def reject(*args, **kwargs):
                    response = self.transport(*args, **kwargs)
                    if unknown:
                        raise cli.Failure('network_failure', outcome='unknown', retry_action=False)
                    response['data'] = {'id': kwargs['body']['id'], 'ok': False, 'error': {'code': 'app_unavailable'}}
                    return response
                with self.assertRaises(cli.Failure):
                    self.run_cli('home', '--after-action', 'tree', send=reject)
                self.assertEqual(self.commands(), ['home'])
                self.assertFalse(self.sleeps)

    def test_device_read_error_preserves_action_without_fallback(self):
        def reject_tree(*args, **kwargs):
            response = self.transport(*args, **kwargs)
            if kwargs['body']['action'] == 'read_tree':
                response['data'] = {'id': kwargs['body']['id'], 'ok': False,
                                    'error': {'code': 'inspection_incomplete'}}
            return response
        result = self.run_cli('home', '--after-action', 'tree', send=reject_tree)
        self.assert_accepted_error(result, 'inspection_incomplete')
        self.assertEqual(self.commands(), ['home', 'read_tree'])
        self.assertNotIn('snapshot', self.saved())

    def test_tree_save_error_preserves_action_and_removes_unsaved_snapshot(self):
        original = cli.save_config
        def fail_snapshot(path, value, **kwargs):
            if value.get('snapshot', {}).get('id') == NEW:
                raise OSError('synthetic private path and credential must not be echoed')
            return original(path, value, **kwargs)
        with patch.object(cli, 'save_config', side_effect=fail_snapshot):
            result = self.run_cli('home', '--after-action', 'tree')
        self.assert_accepted_error(result, 'post_action_observation_failed')
        self.assertNotIn('synthetic private', json.dumps(result))
        self.assertNotIn('snapshot', self.saved())
        self.assertEqual(self.commands(), ['home', 'read_tree'])

    def test_cleanup_disk_failure_is_explicit_without_hiding_accepted_action(self):
        original = cli.save_config
        broken = False
        def fail_after_snapshot(path, value, **kwargs):
            nonlocal broken
            broken = broken or value.get('snapshot', {}).get('id') == NEW
            if broken:
                raise OSError('do not disclose storage details')
            return original(path, value, **kwargs)
        with patch.object(cli, 'save_config', side_effect=fail_after_snapshot):
            result = self.run_cli('home', '--after-action', 'tree')
        self.assert_accepted_error(result, 'post_action_observation_failed')
        self.assertFalse(result['observation_error']['cache_reset_confirmed'])
        self.assertIn('do not use saved targets', result['instruction'])
        self.assertNotIn('storage details', json.dumps(result))
        self.assertEqual(self.commands(), ['home', 'read_tree'])

    def test_image_file_error_preserves_acceptance_and_avoids_replay(self):
        original = cli.private_write
        def fail_image(path, value, **kwargs):
            if Path(path).suffix == '.jpg':
                raise OSError('private image path')
            return original(path, value, **kwargs)
        with patch.object(cli, 'private_write', side_effect=fail_image):
            result = self.run_cli('home')
        self.assert_accepted_error(result, 'post_action_observation_failed')
        self.assertEqual(self.commands(), ['home', 'observe'])
        self.assertNotIn('observation', self.saved())

    def test_exclusive_flags_and_invalid_modes_fail_before_dispatch(self):
        before = self.path.read_bytes()
        for flags in (['--no-observe', '--after-action', 'none'], ['--after-action', 'bad']):
            with self.subTest(flags=flags), patch.object(cli.sys, 'stderr', io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    self.run_cli('home', *flags)
                self.assertEqual(caught.exception.code, 2)
        self.assertEqual(self.sent, [])
        self.assertEqual(self.path.read_bytes(), before)

    def test_output_conflict_fails_before_consuming_or_changing_config(self):
        before = self.path.read_bytes()
        for flags in (['--after-action', 'tree'], ['--after-action', 'none'], ['--no-observe']):
            with self.subTest(flags=flags), self.assertRaises(cli.Failure) as caught:
                self.run_cli('home', *flags, '--output', str(self.path.parent / 'image.jpg'))
            self.assertEqual(caught.exception.result['error'], 'output_requires_image_observation')
        self.assertEqual(self.sent, [])
        self.assertEqual(self.path.read_bytes(), before)

    def test_volume_and_lock_defaults_none_and_explicit_observation_is_rejected(self):
        commands = [('system-action', '--name', 'lock_screen'),
                    ('set-volume', '--stream', 'music', '--level', '0'),
                    ('adjust-volume', '--stream', 'music', '--direction', 'lower')]
        for words in commands:
            for suffix in ((), ('--after-action', 'none'), ('--no-observe',)):
                with self.subTest(words=words, suffix=suffix):
                    self.sent.clear(); self.sleeps.clear()
                    result = self.run_cli(*words, *suffix)
                    self.assertEqual(len(self.sent), 1)
                    self.assertTrue(result['result']['accepted'])
                    self.assertFalse(self.sleeps)
            for mode in ('image', 'tree'):
                self.sent.clear()
                before = self.path.read_bytes()
                with self.subTest(words=words, mode=mode), self.assertRaises(cli.Failure):
                    self.run_cli(*words, '--after-action', mode)
                self.assertFalse(self.sent)
                self.assertEqual(self.path.read_bytes(), before)
            with self.subTest(words=words, output=True), self.assertRaises(cli.Failure):
                self.run_cli(*words, '--output', str(self.path.parent / 'image.jpg'))
            self.assertFalse(self.sent)

    def test_followup_renews_same_task_when_action_crosses_threshold(self):
        self.give_lease(46)
        def delay_action(*args, **kwargs):
            response = self.transport(*args, **kwargs)
            if kwargs['body'].get('action') == 'home':
                self.now += 2
            return response
        result = self.run_cli('home', '--after-action', 'tree', send=delay_action)
        self.assertEqual([path for path, _ in self.sent], ['/v1/command', '/v1/tasks/renew', '/v1/command'])
        self.assertEqual(self.commands(), ['home', 'read_tree'])
        for path, body in self.sent:
            self.assertEqual((body['lease_id'], body['lease_epoch']), (LEASE, 2))
        self.assertEqual(self.saved()['task_lease']['renewal_sequence'], 1)
        self.assertFalse(self.saved()['task_needs_observation'])
        self.assertEqual(self.saved()['claim_id'], CLAIM)
        self.assertIn('post_action_tree', result)

    def test_expired_lease_between_action_and_read_does_not_reacquire(self):
        self.give_lease()
        def expire_after_action(*args, **kwargs):
            response = self.transport(*args, **kwargs)
            self.now += 121
            return response
        result = self.run_cli('home', '--after-action', 'tree', send=expire_after_action)
        self.assert_accepted_error(result, 'task_acquire_required')
        self.assertEqual(self.commands(), ['home'])
        self.assertEqual(self.saved()['task_lease']['state'], 'expired')
        self.assertTrue(self.saved()['task_needs_observation'])

    def test_unknown_followup_retains_uncertain_lease_and_consumed_sequence(self):
        self.give_lease()
        def lose_read(*args, **kwargs):
            response = self.transport(*args, **kwargs)
            if kwargs['body']['action'] == 'read_tree':
                raise cli.Failure('network_failure', outcome='unknown', retry_action=False)
            return response
        result = self.run_cli('home', '--after-action', 'tree', send=lose_read)
        self.assert_accepted_error(result, 'network_failure')
        self.assertEqual(self.commands(), ['home', 'read_tree'])
        self.assertEqual(self.saved()['task_lease']['state'], 'uncertain')
        self.assertEqual(self.saved()['command_sequence'], 43)
        self.assertTrue(self.saved()['task_needs_observation'])


if __name__ == '__main__':
    unittest.main()
