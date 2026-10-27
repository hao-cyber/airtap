#!/usr/bin/env python3
"""Adversarial local input/response checks; no live phone/network needed."""
import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

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

if __name__ == '__main__': unittest.main()
