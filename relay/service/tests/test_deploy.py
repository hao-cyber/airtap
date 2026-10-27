"""Frozen deployment integrity, credential preservation and revision transitions."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('service_deploy', Path(__file__).parents[1] / 'deploy.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

class DeployTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='airtap-deploy-test-')
        self.root = Path(self.temp.name).resolve()
        self.salt = 'a' * 64
        self.state = dict(schema=1, deployment_kind=d.KIND, deployment_root=str(self.root),
            service_id='b'*32, name='airtap-service-20261026-'+'c'*32,
            hostname='airtap-service-20261026-'+'c'*32+'.example.com', ingress_namespace='123456789',
            url='https://airtap-service-20261026-'+'c'*32+'.example.com', settings=dict(d.DEFAULTS),
            revision=1, operation='create', attempted=False, deployed=True, created_at='2026-10-26T00:00:00Z')
        self.files = {name: ('fixture:'+name).encode() for name in (*d.SOURCES, *d.ASSETS.values())}
    def tearDown(self):
        self.temp.cleanup()
    def first(self, old=False, admin=None):
        files = dict(self.files)
        if old: files.pop('relay/service/feedback.mjs')
        path = d.freeze(self.root, self.state, None, self.salt, files, admin)
        d.json_write(self.root/'identity.json', d.identity(self.state, None, self.salt))
        d.json_write(self.root/'latest.json', dict(state_path=str(path), pending_state=None))
        return path
    def key(self):
        path = self.root/'admin.key'
        d.private_write(path, 'e'*64+'\n')
        return path
    def test_old_revision_valid_and_configurable(self):
        path = self.first(old=True)
        original = d.validate(path)[0]['source_sha256']
        newer = d.prepare_revision(path, 'configure', None)
        state = d.validate(newer)[0]
        self.assertEqual(original, state['source_sha256'])
        self.assertNotIn('feedback_admin_sha256', state)
        self.assertEqual(d.validate(newer)[2], self.salt)
    def test_update_adds_module_and_hash_without_identity_change(self):
        path = self.first(old=True)
        original = d.source_files
        d.source_files = lambda: self.files
        try: newer = d.prepare_revision(path, 'update', None, self.key())
        finally: d.source_files = original
        state, _, salt, latest = d.validate(newer)
        self.assertEqual(state['feedback_admin_sha256'], d.digest(('e'*64).encode()))
        self.assertEqual(state['service_id'], self.state['service_id'])
        self.assertEqual(salt, self.salt)
        self.assertEqual(latest['pending_state'], str(newer))
        self.assertNotIn('e'*64, (newer.parent/'secrets.json').read_text())
    def test_configure_preserves_hash(self):
        path = self.first(admin='f'*64)
        newer = d.prepare_revision(path, 'configure', None)
        self.assertEqual(d.validate(newer)[0]['feedback_admin_sha256'], 'f'*64)
    def test_changed_hash_rejected(self):
        path = self.first(admin='f'*64)
        d.replace_json(self.root/'secrets.json', dict(IP_HASH_SALT=self.salt, FEEDBACK_ADMIN_SHA256='d'*64))
        with self.assertRaisesRegex(ValueError, 'credential changed'): d.validate(path)
    def test_module_tampering_rejected(self):
        path = self.first()
        (self.root/'relay/service/feedback.mjs').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'source or asset changed'): d.validate(path)
    def test_unknown_module_rejected(self):
        with self.assertRaisesRegex(ValueError, 'source list'):
            d.frozen_sources([*d.SOURCES, 'unknown.mjs'], [])
    def test_public_key_file_rejected(self):
        path = self.key()
        path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'owner-only'): d.feedback_admin_hash(path)
    def test_symlink_key_rejected(self):
        path = self.key()
        link = self.root/'linked.key'
        link.symlink_to(path)
        with self.assertRaisesRegex(ValueError, 'owner-only'): d.feedback_admin_hash(link)
    def test_malformed_key_not_echoed(self):
        path = self.root/'bad.key'
        d.private_write(path, 'private-malformed-value')
        with self.assertRaises(ValueError) as caught: d.feedback_admin_hash(path)
        self.assertNotIn('private-malformed-value', str(caught.exception))
    def test_pending_revision_cannot_be_replaced(self):
        path = self.first()
        d.prepare_revision(path, 'configure', None)
        with self.assertRaisesRegex(ValueError, 'pending'): d.prepare_revision(path, 'configure', None)
    def test_old_frozen_source_cannot_enable_unimplemented_feature(self):
        path = self.first(old=True)
        with self.assertRaisesRegex(ValueError, 'feedback source'): d.prepare_revision(path, 'configure', None, self.key())

if __name__ == '__main__': unittest.main()
