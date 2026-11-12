"""Remote connection CLI parser, lease gate and journal; synthetic transport only."""
import importlib.util
import io
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('connection_cli', Path(__file__).resolve().parents[3] / 'cli/airtap.py')
cli = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cli)


class ConnectionCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name).resolve() / 'phone.json'
        self.config = dict(url='https://example.invalid/d/'+'a'*32, controller_token='b'*64,
                           state='approved', persistent=True, all_apps=True, allow_control=True,
                           mode='multi', lease_required=True, task_needs_observation=True,
                           command_sequence=0, task_lease=dict(state='ready', task_id='c'*32,
                           lease_id='d'*32, lease_epoch=1, recorded_at=time.time()-1, local_expires_at=time.time()+120))
        cli.save_config(self.path, self.config)

    def tearDown(self):
        cli.unlock_config()
        self.temp.cleanup()

    def invoke(self, *words, code=b'012345\n'):
        cli.unlock_config()
        stream = io.TextIOWrapper(io.BytesIO(code), encoding='ascii')
        with patch.object(cli.sys, 'argv', ['airtap', '--config', str(self.path), *words]), \
             patch.object(cli.sys, 'stdin', stream), \
             patch.object(cli, '_command_request', return_value={'ok': True, 'result': {'state': 'pending'}}) as send:
            try:
                result = cli.main()
                return result, send.call_args.args[2], send.call_count
            finally:
                cli.unlock_config()

    def test_management_does_not_require_or_follow_up_with_observation(self):
        for name in ('connection-status', 'connection-invite', 'battery-status'):
            result, body, calls = self.invoke(name)
            self.assertTrue(result['ok'])
            self.assertEqual(body['action'], name.replace('-', '_'))
            self.assertEqual(body['lease_id'], 'd'*32)
            self.assertEqual(calls, 1)
            self.assertTrue(cli.load_config(self.path)['task_needs_observation'])

    def test_approve_preserves_leading_zero_code_from_stdin_without_persistence(self):
        _, body, calls = self.invoke('connection-approve', '--invitation-id', 'e'*32,
                                    '--claim-id', 'f'*32, '--code-stdin')
        self.assertEqual(body['verification_code'], '012345')
        self.assertEqual(body['invitation_id'], 'e'*32)
        self.assertEqual(body['claim_id'], 'f'*32)
        self.assertEqual(calls, 1)
        self.assertNotIn('012345', self.path.read_text())

    def test_cancel_sends_exact_invitation(self):
        _, body, calls = self.invoke('connection-cancel', '--invitation-id', 'e'*32)
        self.assertEqual(body['invitation_id'], 'e'*32)
        self.assertNotIn('claim_id', body)
        self.assertEqual(calls, 1)

    def test_revoke_targets_one_claim(self):
        _, body, calls = self.invoke('connection-revoke', '--claim-id', 'f'*32)
        self.assertEqual(body['claim_id'], 'f'*32)
        self.assertNotIn('invitation_id', body)
        self.assertEqual(calls, 1)

    def test_single_invite_can_start_migration_without_repairing(self):
        config = {k: v for k, v in self.config.items() if k not in ('task_lease', 'lease_required')}
        config['mode'] = 'single'
        cli.save_config(self.path, config)
        _, body, calls = self.invoke('connection-invite')
        self.assertNotIn('lease_id', body)
        self.assertEqual(calls, 1)

    def test_notification_setup_requires_lease_and_sends_explicit_boolean_without_screenshot(self):
        for option, enabled in [('--enable', True), ('--disable', False)]:
            _, body, calls = self.invoke('event-configure', option)
            self.assertEqual(body['action'], 'event_configure')
            self.assertIs(body['enabled'], enabled)
            self.assertEqual(body['lease_id'], 'd'*32)
            self.assertEqual(calls, 1)
        config = cli.load_config(self.path)
        config['task_lease'] = {}
        cli.save_config(self.path, config)
        with self.assertRaises(cli.Failure) as failure:
            self.invoke('event-configure', '--enable')
        self.assertEqual(failure.exception.result['error'], 'task_acquire_required')

    def test_notification_setup_does_not_allow_implicit_capture(self):
        with self.assertRaises(cli.Failure) as failure:
            self.invoke('event-configure', '--enable', '--after-action', 'image')
        self.assertEqual(failure.exception.result['error'], 'post_action_observation_unavailable')

    def test_bad_code_and_identity_never_reach_transport(self):
        for code in (b'12345', b'1234567', b'123 45', '１２３４５６'.encode()):
            with self.assertRaises(cli.Failure) as caught:
                self.invoke('connection-approve', '--invitation-id', 'e'*32, '--claim-id', 'f'*32,
                            '--code-stdin', code=code)
            self.assertEqual(caught.exception.result['error'], 'invalid_verification_code')
        with self.assertRaises(cli.Failure) as caught:
            self.invoke('connection-cancel', '--invitation-id', 'not-an-id')
        self.assertEqual(caught.exception.result['error'], 'invalid_invitation_id')

    def test_single_or_missing_lease_refused_even_for_read_status(self):
        for fields, expected in [({'mode': 'single'}, 'multi_api_required'),
                                 ({'task_lease': {}, 'lease_required': False}, 'task_acquire_required')]:
            cli.save_config(self.path, {**self.config, **fields})
            with self.assertRaises(cli.Failure) as caught:
                self.invoke('connection-status')
            self.assertEqual(caught.exception.result['error'], expected)

    def test_mutation_journal_records_only_action_identity(self):
        args = type('Args', (), dict(config=self.path, job_id='connection', step_id='approve'))()
        job = dict(schema=1, job_id='connection', identity=cli.job_identity(self.config), state='active',
                   steps=[dict(id='approve', title='Connect own AI', state='planned', attempts=[])])
        cli.job_save(args, job, replace=False)
        body = dict(id='00000000-0000-4000-8000-000000000001', action='connection_approve',
                    verification_code='012345', invitation_id='e'*32, claim_id='f'*32)
        self.assertIsNotNone(cli.job_before(args, self.config, body))
        text = cli.job_path(args).read_text()
        self.assertNotIn('012345', text)
        self.assertNotIn('invitation_id', text)
        with self.assertRaises(cli.Failure):
            cli.job_before(args, self.config, body)


if __name__ == '__main__':
    unittest.main()
