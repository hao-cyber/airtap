"""Task recovery uses the real CLI journal; event polling uses a bounded synthetic phone response."""
import importlib.util,json,tempfile,types,unittest,uuid
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('airtap',Path(__file__).resolve().parents[3]/'cli/airtap.py')
cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)
class JobsEventsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name).resolve()
        self.args=types.SimpleNamespace(config=self.root/'private'/'p.json',job_id='demo',step_id='submit')
        self.config={'url':'https://example.invalid/d/'+'a'*32,'claim_id':'b'*32}
        self.job={'schema':1,'job_id':'demo','identity':cli.job_identity(self.config),'state':'active','steps':[{'id':'submit','title':'synthetic task','state':'planned','attempts':[]}]}
        cli.job_save(self.args,self.job,replace=False)
    def tearDown(self):self.temp.cleanup()
    def test_crash_after_write_blocks_second_submission(self):
        body={'id':str(uuid.uuid4()),'action':'click'}
        job=cli.job_before(self.args,self.config,body)
        self.assertEqual(cli.job_load(self.args,self.config)['steps'][0]['state'],'outcome_unknown')
        with self.assertRaises(cli.Failure):cli.job_before(self.args,self.config,{'id':str(uuid.uuid4()),'action':'click'})
        cli.job_after(self.args,job,result={'ok':True})
        self.assertEqual(cli.job_load(self.args,self.config)['steps'][0]['state'],'needs_verification')
        with self.assertRaises(cli.Failure):cli.job_before(self.args,self.config,body)
        self.assertNotIn('controller_token',cli.job_path(self.args).read_text())
    def test_new_phone_or_pair_cannot_resume_old_job(self):
        with self.assertRaises(cli.Failure):cli.job_load(self.args,{**self.config,'claim_id':'c'*32})
    def test_damaged_journal_does_not_allow_dispatch(self):
        for changed in [[], {**self.job,'steps':[{}]}, {**self.job,'steps':self.job['steps']*2}, {**self.job,'state':'unknown'}]:
            cli.job_save(self.args,changed)
            with self.assertRaises(cli.Failure):cli.job_before(self.args,self.config,{'id':str(uuid.uuid4()),'action':'click'})
    def test_read_does_not_change_control_step(self):
        self.assertIsNone(cli.job_before(self.args,self.config,{'action':'observe'}))
        self.assertEqual(cli.job_load(self.args,self.config)['steps'][0]['state'],'planned')

    def test_file_step_is_journaled_through_real_command_entry(self):
        self.config.update(controller_token='d' * 64, state='approved', all_apps=True,
                           allow_control=True, persistent=True)
        cli.save_config(self.args.config, self.config)
        for command, extra in [
            ('file-share', ['--version', 'f' * 64, '--mime-type', 'text/plain']),
            ('file-publish-media', ['--version', 'f' * 64, '--mime-type', 'image/png']),
            ('file-mkdir', []), ('file-delete', ['--version', 'f' * 64]),
            ('file-rename', ['--version', 'f' * 64, '--destination', 'new']),
        ]:
            for lost in (False, True):
                with self.subTest(command=command, lost=lost):
                    cli.job_save(self.args, self.job)
                    argv = ['airtap', '--config', str(self.args.config), '--job-id', 'demo', '--step-id',
                            'submit', command, '--root', 'workspace', '--path', 'sample', *extra]
                    def transport(url, path, token, body, **options):
                        self.assertEqual(cli.job_load(self.args, self.config)['steps'][0]['state'], 'outcome_unknown')
                        if lost:
                            raise cli.Failure('network_failure', outcome='unknown')
                        return {'data': {'id': body['id'], 'ok': True, 'result': {'accepted': True}}, 'elapsed_ms': 1}
                    with patch.object(cli.sys, 'argv', argv), patch.object(cli, 'request', side_effect=transport) as sent:
                        try:
                            if lost:
                                with self.assertRaisesRegex(cli.Failure, 'network_failure'): cli.main()
                            else:
                                self.assertTrue(cli.main()['ok'])
                        finally:
                            cli.unlock_config()
                        step = cli.job_load(self.args, self.config)['steps'][0]
                        self.assertEqual(step['state'], 'outcome_unknown' if lost else 'needs_verification')
                        self.assertEqual(len(step['attempts']), 1)
                        try:
                            with self.assertRaisesRegex(cli.Failure, 'job_step_requires_verification'): cli.main()
                        finally:
                            cli.unlock_config()
                        sent.assert_called_once()
    def test_event_status_skips_lease_and_screen_preparation(self):
        with patch.object(cli,'_command_request',return_value={'ok':True}) as request,patch.object(cli,'ensure_task',side_effect=AssertionError('lease was acquired')):
            result=cli.command_request(self.args,{'lease_required':True},{'action':'event_status'})
            self.assertTrue(result['ok']);request.assert_called_once()
    def test_event_cursor_not_auto_consumed_and_gap_is_returned(self):
        args=types.SimpleNamespace(command='event-read',epoch='a'*32,after=4,limit=16)
        page={'ok':True,'result':{'epoch':'b'*32,'next':0,'gap':True,'events':[],'status':{'listener_connected':True}}}
        with patch.object(cli,'command_request',return_value=page):self.assertEqual(cli.event_command(args,self.config),page)
        self.assertEqual(args.after,4)
    def test_event_malformed_sequence_refused(self):
        args=types.SimpleNamespace(command='event-read',epoch='a'*32,after=4,limit=16)
        page={'ok':True,'result':{'epoch':'a'*32,'next':4,'gap':False,'events':[{'sequence':3}],'status':{}}}
        with patch.object(cli,'command_request',return_value=page),self.assertRaises(cli.Failure):cli.event_command(args,self.config)
    def test_event_wait_unlocks_between_polls_and_reloads_config(self):
        args=types.SimpleNamespace(config=self.args.config,wait_seconds=20,allow_local_http=False)
        calls=[]
        empty={'result':{'events':[],'gap':False,'status':{'listener_connected':True}}}
        ready={'result':{'events':[{'sequence':1}],'gap':False,'status':{'listener_connected':True}}}
        with patch.object(cli,'lock_config',side_effect=lambda p:calls.append('lock')),patch.object(cli,'unlock_config',side_effect=lambda:calls.append('unlock')),patch.object(cli,'load_config',return_value={'state':'approved'}) as reload,patch.object(cli,'safe_scope'),patch.object(cli,'event_command',side_effect=[empty,ready]),patch.object(cli.time,'sleep',side_effect=lambda d:calls.append('sleep')):
            self.assertEqual(cli.wait_for_events(args),ready)
            self.assertEqual(reload.call_count,2)
        self.assertEqual(calls,['lock','unlock','sleep','lock','unlock'])
if __name__=='__main__':unittest.main()
