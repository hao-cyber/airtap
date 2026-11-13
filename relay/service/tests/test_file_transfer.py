#!/usr/bin/env python3
"""Adversarial local input/response checks; no live phone/network needed."""
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('airtap', ROOT / 'cli/airtap.py')
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)

class FilesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
    def tearDown(self): self.temporary.cleanup()
    def test_input_rejects_parent_symlink_and_fifo_without_blocking(self):
        real = self.root / 'real'; real.mkdir(); (real / 'file').write_bytes(b'private fixture')
        (self.root / 'alias').symlink_to(real, target_is_directory=True)
        with self.assertRaises((OSError, cli.Failure)): cli.local_file_bytes(self.root / 'alias/file')
        pipe = self.root / 'pipe'; os.mkfifo(pipe)
        with self.assertRaises(cli.Failure): cli.local_file_bytes(pipe)
    def test_input_limit_checked_before_read_and_empty_valid(self):
        file = self.root / 'file'; file.write_bytes(b'')
        self.assertEqual(cli.local_file_bytes(file), b'')
        with file.open('wb') as stream: stream.truncate(cli.FILE_LIMIT + 1)
        with self.assertRaises(cli.Failure): cli.local_file_bytes(file)
    def test_path_unicode_and_traversal_contract(self):
        for path in ['中文/🫶.txt', 'a b.txt', '100%.txt']: self.assertEqual(cli.file_path(path), path)
        for path in ['', '../private', '/absolute', 'a//b', 'a/./b', 'a\\b', 'a:b', 'bad\0name', 'x\ud800', '/'.join(['x'] * 33), '名' * 342]:
            with self.subTest(path=repr(path)), self.assertRaises((cli.Failure, UnicodeError)): cli.file_path(path)
        self.assertEqual(cli.file_path('', True), '')
    def chunk(self):
        return {'root':'workspace','path':'fixture','offset':0,'next_offset':3,'data_base64':base64.b64encode(b'abc').decode(),
                'sha256':hashlib.sha256(b'abc').hexdigest(),'eof':True,'size':3,'version':'a'*64}
    def test_read_rejects_offset_eof_identity_hash_canonical_errors(self):
        invalid = [{'offset':True}, {'next_offset':2}, {'eof':False}, {'root':'other'}, {'version':'b'*64}, {'sha256':'0'*64},
                   {'data_base64':'YWJj\n'}, {'data_base64':'YR=='}, {'size':4}, {'unexpected':'private content'}]
        for override in invalid:
            with self.subTest(override=override), self.assertRaises(cli.Failure):
                cli.checked_file_chunk({**self.chunk(),**override},'workspace','fixture',0,3,'a'*64,3)
        value=self.chunk(); self.assertEqual(cli.checked_file_chunk(value,'workspace','fixture',0,3,'a'*64,3),b'abc')
        self.assertNotIn('data_base64',value)
    def test_upload_state_rejects_unexpected_payload_and_false_completion(self):
        expected={'transfer_id':'a'*32,'root':'workspace','path':'fixture','size':3,'sha256':hashlib.sha256(b'abc').hexdigest()}
        receiving={**expected,'state':'receiving','offset':0,'expires_at':1,'publication_atomic':True}
        self.assertEqual(cli.checked_upload(receiving,expected)['state'],'receiving')
        for extra in [{'data_base64':'PRIVATE'}, {'publication_atomic':False}, {'publication_atomic':1}, {'state':'publishing','offset':3}, {'next_step':[]}, {'offset':True}, {'offset':4}, {'sha256':'b'*64}, {'state':'finished','file':None}]:
            with self.subTest(extra=extra), self.assertRaises(cli.Failure): cli.checked_upload({**receiving,**extra},expected)
    def test_external_upload_state_contract(self):
        expected={'transfer_id':'a'*32,'root':'saf_'+'b'*32,'path':'fixture','size':3,'sha256':hashlib.sha256(b'abc').hexdigest()}
        publishing={**expected,'state':'publishing','offset':3,'expires_at':1,'publication_atomic':False}
        self.assertEqual(cli.checked_upload(publishing,expected)['state'],'publishing')
        self.assertTrue(cli.checked_upload({**publishing,'cleanup_required':True},expected)['cleanup_required'])
        unknown={**publishing,'state':'commit_unknown','next_step':'Inspect the selected directory.'}
        self.assertEqual(cli.checked_upload(unknown,expected)['state'],'commit_unknown')
        for extra in [{'offset':0}, {'publication_atomic':True}, {'publication_atomic':None}, {'next_step':'x'*1025}, {'cleanup_required':1}, {'cleanup_required':True,'state':'finished'}]:
            with self.subTest(extra=extra), self.assertRaises(cli.Failure): cli.checked_upload({**publishing,**extra},expected)
    def test_output_no_clobber_and_no_symlink(self):
        file=self.root/'file';file.write_bytes(b'keep')
        with self.assertRaises(cli.Failure): cli.private_write(file,b'new',replace=False)
        self.assertEqual(file.read_bytes(),b'keep')
        link=self.root/'link';link.symlink_to(file)
        with self.assertRaises(cli.Failure): cli.private_write(link,b'new',replace=False)
        self.assertEqual(file.read_bytes(),b'keep')

    def upload_fixture(self, data):
        source = self.root / 'input'; source.write_bytes(data)
        args = types.SimpleNamespace(root='workspace', path='private-name-not-for-progress.png',
                                     transfer_id=None, config=self.root/'config.json', command='file-upload')
        state = {}
        calls = []
        def server(args, config, action, **fields):
            calls.append((action, fields))
            if action == 'file_upload_begin':
                state.update(fields, state='receiving', offset=0, expires_at=100, publication_atomic=True)
            elif action == 'file_upload_chunk':
                self.assertEqual(fields['offset'], state['offset'])
                chunk = base64.b64decode(fields['data_base64'])
                self.assertEqual(fields['sha256'], hashlib.sha256(chunk).hexdigest())
                state['offset'] += len(chunk)
            elif action == 'file_upload_finish':
                state.update(state='finished', file={'root':args.root, 'path':args.path, 'kind':'file',
                             'size':len(data), 'sha256':hashlib.sha256(data).hexdigest(), 'version':'c'*64})
            elif action == 'file_stat':
                return {'ok':True, 'result':dict(state['file'])}
            elif action != 'file_upload_status':
                self.fail('unexpected action')
            return {'ok':True, 'result':dict(state)}
        return source, args, state, calls, server

    def test_upload_unknown_chunk_retains_identity_and_resumes_from_server_ack(self):
        data = b'x' * (cli.FILE_CHUNK_LIMIT * 2)
        source, args, state, calls, server = self.upload_fixture(data)
        config = {'controller_token':'secret-not-for-progress'}
        stderr, stdout = io.StringIO(), io.StringIO()
        def lost_reply(*a, **kw):
            result = server(*a, **kw)
            if a[2] == 'file_upload_chunk' and state['offset'] == len(data):
                raise cli.Failure('total_deadline_exceeded', outcome='unknown')
            return result
        with patch.object(cli, 'file_call', side_effect=lost_reply), \
             patch.object(cli.sys, 'stderr', stderr), patch.object(cli.sys, 'stdout', stdout):
            with source.open('rb') as stream, self.assertRaises(cli.Failure) as caught:
                cli.file_upload_stream(args, config, stream, os.fstat(stream.fileno()))
        failure = caught.exception.result
        self.assertEqual(failure['transfer_metrics']['last_confirmed_offset'], cli.FILE_CHUNK_LIMIT)
        self.assertEqual(state['offset'], len(data))  # The lost response did not undo the accepted chunk.
        self.assertFalse(failure['retry_action'])
        self.assertEqual(config['last_file_upload']['transfer_id'], failure['transfer_id'])
        self.assertNotIn('"stage": "verified"', stderr.getvalue())
        for private in [args.path, config['controller_token'], failure['transfer_id']]:
            self.assertNotIn(private, stderr.getvalue())
        self.assertEqual(stdout.getvalue(), '')
        with patch.object(cli, 'file_call', side_effect=server), patch.object(cli.sys, 'stderr', stderr):
            with source.open('rb') as stream:
                result = cli.file_upload_stream(args, config, stream, os.fstat(stream.fileno()))
        self.assertTrue(result['ok'])
        self.assertEqual(result['transfer_metrics']['initial_offset'], len(data))
        self.assertEqual(result['transfer_metrics']['file_commands'], 3)  # status, finish, current stat
        self.assertEqual([x[0] for x in calls].count('file_upload_begin'), 1)
        self.assertEqual([x[0] for x in calls].count('file_upload_chunk'), 2)
        progress = [json.loads(line.removeprefix('Airtap upload: ')) for line in stderr.getvalue().splitlines()]
        self.assertEqual(progress[-1]['stage'], 'verified')
        self.assertEqual(progress[-1]['last_confirmed_offset'], len(data))

    def test_full_staging_is_not_reported_as_verified_if_current_file_changed(self):
        source, args, state, calls, server = self.upload_fixture(b'abc')
        def changed(*a, **kw):
            result = server(*a, **kw)
            if a[2] == 'file_stat': result['result']['sha256'] = '0'*64
            return result
        stderr = io.StringIO()
        with patch.object(cli, 'file_call', side_effect=changed), patch.object(cli.sys, 'stderr', stderr):
            with source.open('rb') as stream, self.assertRaises(cli.Failure) as caught:
                cli.file_upload_stream(args, {}, stream, os.fstat(stream.fileno()))
        self.assertEqual(caught.exception.result['error'], 'finished_file_changed')
        self.assertEqual(caught.exception.result['transfer_metrics']['last_confirmed_offset'], 3)
        self.assertNotIn('"stage": "verified"', stderr.getvalue())

    def test_empty_upload_and_closed_progress_stream_do_not_break_completion(self):
        source, args, state, calls, server = self.upload_fixture(b'')
        stderr = io.StringIO(); stderr.close()
        with patch.object(cli, 'file_call', side_effect=server), patch.object(cli.sys, 'stderr', stderr):
            with source.open('rb') as stream:
                result = cli.file_upload_stream(args, {}, stream, os.fstat(stream.fileno()))
        self.assertTrue(result['ok'])
        self.assertEqual(result['transfer_metrics']['last_confirmed_offset'], 0)
        self.assertEqual([x[0] for x in calls], ['file_upload_begin', 'file_upload_finish', 'file_stat'])

    def download_fixture(self, data):
        args = types.SimpleNamespace(root='workspace', path='private-recording.mp4',
                                     output=self.root/'result.mp4', config=self.root/'config.json',
                                     command='file-download')
        config = {'url':'https://fixture.example', 'claim_id':'a'*32, 'controller_token':'not-for-progress'}
        metadata = {'root':args.root, 'path':args.path, 'kind':'file', 'size':len(data),
                    'sha256':hashlib.sha256(data).hexdigest(), 'version':'b'*64}
        reads = []
        def server(args, config, action, **fields):
            if action == 'file_stat': return {'ok':True, 'result':dict(metadata)}
            self.assertEqual(action, 'file_read')
            reads.append(fields['offset'])
            offset = fields['offset']; chunk = data[offset:offset+fields['length']]
            return {'ok':True, 'result':{'root':args.root, 'path':args.path, 'offset':offset,
                    'next_offset':offset+len(chunk), 'data_base64':base64.b64encode(chunk).decode(),
                    'sha256':hashlib.sha256(chunk).hexdigest(), 'eof':offset+len(chunk)==len(data),
                    'size':len(data), 'version':metadata['version']}}
        return args, config, metadata, reads, server

    def test_download_unknown_chunk_reports_durable_partial_and_resumes_without_replaying(self):
        data = b'x' * (cli.FILE_CHUNK_LIMIT*2+13)
        args, config, metadata, reads, server = self.download_fixture(data)
        stderr = io.StringIO(); stdout = io.StringIO()
        def lose_second(*a, **kw):
            result = server(*a, **kw)
            if a[2] == 'file_read' and kw['offset'] == cli.FILE_CHUNK_LIMIT:
                raise cli.Failure('http_error', status=504, outcome='unknown')
            return result
        with patch.object(cli, 'file_call', side_effect=lose_second), patch.object(cli.sys, 'stderr', stderr), patch.object(cli.sys, 'stdout', stdout):
            with self.assertRaises(cli.Failure) as caught: cli.file_command(args, config)
        failure = caught.exception.result
        self.assertEqual(reads, [0, cli.FILE_CHUNK_LIMIT])  # No implicit network retry.
        self.assertEqual(failure['download_stage'], 'read')
        self.assertEqual(failure['transfer_metrics']['last_confirmed_offset'], cli.FILE_CHUNK_LIMIT)
        self.assertTrue(failure['partial_preserved']); self.assertFalse(failure['output_published'])
        self.assertFalse(args.output.exists()); self.assertFalse(failure['retry_action'])
        self.assertEqual(next((self.root/'downloads').glob('*.part')).stat().st_size, cli.FILE_CHUNK_LIMIT)
        self.assertEqual(stdout.getvalue(), '')
        self.assertNotIn('"stage": "verified"', stderr.getvalue())
        for private in (args.path, config['claim_id'], config['controller_token'], str(args.config)):
            self.assertNotIn(private, stderr.getvalue())
        with patch.object(cli, 'file_call', side_effect=server), patch.object(cli.sys, 'stderr', stderr):
            result = cli.file_command(args, config)
        self.assertEqual(result['transfer_metrics']['initial_offset'], cli.FILE_CHUNK_LIMIT)
        self.assertEqual(reads[2:], [cli.FILE_CHUNK_LIMIT, cli.FILE_CHUNK_LIMIT*2])
        self.assertEqual(args.output.read_bytes(), data)
        self.assertEqual(args.output.stat().st_mode & 0o777, 0o600)
        self.assertFalse(list((self.root/'downloads').glob('*.part')))
        self.assertEqual(json.loads(stderr.getvalue().splitlines()[-1].removeprefix('Airtap download: '))['stage'], 'verified')

    def test_download_stat_failure_does_not_claim_zero_downloaded_or_delete_partial(self):
        args, config, metadata, reads, server = self.download_fixture(b'abc')
        directory = self.root/'downloads'; directory.mkdir(mode=0o700)
        previous = directory/'previous.part'; previous.write_bytes(b'keep')
        with patch.object(cli, 'file_call', side_effect=cli.Failure('http_error', status=503, outcome='unknown')), patch.object(cli.sys, 'stderr', io.StringIO()):
            with self.assertRaises(cli.Failure) as caught: cli.file_command(args, config)
        self.assertEqual(caught.exception.result['download_stage'], 'stat')
        self.assertIsNone(caught.exception.result['transfer_metrics']['last_confirmed_offset'])
        self.assertIsNone(caught.exception.result['transfer_metrics']['total_bytes'])
        self.assertEqual(previous.read_bytes(), b'keep')

    def test_download_full_checksum_failure_is_never_published_or_reported_verified(self):
        args, config, metadata, reads, server = self.download_fixture(b'abc')
        metadata['sha256'] = '0'*64; stderr = io.StringIO()
        with patch.object(cli, 'file_call', side_effect=server), patch.object(cli.sys, 'stderr', stderr):
            with self.assertRaises(cli.Failure) as caught: cli.file_command(args, config)
        self.assertEqual(caught.exception.result['error'], 'file_hash_mismatch')
        self.assertEqual(caught.exception.result['download_stage'], 'verify')
        self.assertEqual(caught.exception.result['transfer_metrics']['last_confirmed_offset'], 3)
        self.assertIn('corrupt', caught.exception.result['resume_instruction'])
        self.assertFalse(args.output.exists())
        self.assertNotIn('"stage": "verified"', stderr.getvalue())

    def test_download_changed_metadata_does_not_reuse_another_versions_partial(self):
        args, config, metadata, reads, server = self.download_fixture(b'a'*(cli.FILE_CHUNK_LIMIT+3))
        def lose_last(*a, **kw):
            if a[2]=='file_read' and kw['offset']>0: raise cli.Failure('http_error', status=504, outcome='unknown')
            return server(*a, **kw)
        with patch.object(cli, 'file_call', side_effect=lose_last), patch.object(cli.sys, 'stderr', io.StringIO()):
            with self.assertRaises(cli.Failure): cli.file_command(args, config)
        old_part = next((self.root/'downloads').glob('*.part'))
        args, config, metadata, reads, server = self.download_fixture(b'new')
        metadata['version'] = 'c'*64
        closed = io.StringIO(); closed.close()
        with patch.object(cli, 'file_call', side_effect=server), patch.object(cli.sys, 'stderr', closed):
            result = cli.file_command(args, config)
        self.assertEqual(result['transfer_metrics']['initial_offset'], 0)
        self.assertEqual(args.output.read_bytes(), b'new')
        self.assertTrue(old_part.exists())

if __name__ == '__main__': unittest.main()
