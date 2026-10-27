import contextlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_source as package
import source_manifest


class PublicSitePolicy(unittest.TestCase):
    def test_exact_public_site_survives_source_document_and_json_scans(self):
        host = package.PUBLIC_INSTALL_HOST
        for value in [host, 'https://' + host, 'https://' + host + '/install',
                      'https://' + host + '/help#offline-help']:
            for kind, text in [('source', repr(value)), ('document', '[' + value + '](' + value + ')'),
                               ('result', json.dumps({'website': value}))]:
                with self.subTest(value=value, kind=kind):
                    self.assertFalse(package.scan('fixture', text.encode(), kind))

    def test_exact_host_is_not_a_relay_or_credential_url_waiver(self):
        host = package.PUBLIC_INSTALL_HOST
        urls = ['https://private-review.' + 'workers.dev',
                'https://legacy-review.' + 'memtrix.dev',
                'https://random.' + 'trycloudflare.com',
                'https://sub.' + host, 'https://' + host + '.evil.test',
                'http://' + host, 'https://' + host + ':443/',
                'https://user@' + host, 'https://' + host + '@evil.test',
                'https://' + host + '/install?token=synthetic',
                'https://' + host + '/private-export',
                'https://' + host + '/help#secret-fragment', host + '/private']
        for value in urls:
            with self.subTest(value=value):
                self.assertTrue(any(x['type'] == 'real_deployment_hostname'
                                    for x in package.scan('fixture.py', repr(value).encode(), 'source')))
                self.assertGreater(package.json_deployment_hostname_count(value), 0)

    def test_public_site_does_not_suppress_secrets_or_private_paths(self):
        prefix = repr('https://' + package.PUBLIC_INSTALL_HOST) + '\n'
        bad = [('private_key_material', '-----BEGIN ' + 'PRIVATE KEY-----'),
               ('literal_bearer_credential', 'Bearer ' + 'a' * 40),
               ('maintainer_absolute_path', '/Users/' + 'synthetic-owner/private-log.json')]
        for expected, value in bad:
            with self.subTest(rule=expected):
                self.assertTrue(any(x['type'] == expected for x in package.scan('fixture.py', (prefix + value).encode(), 'source')))
        report = json.dumps({'website': 'https://' + package.PUBLIC_INSTALL_HOST, 'ticket': 'synthetic-value'}).encode()
        for path, kind in [('results/report.json', 'result'), ('fixture.json', 'source')]:
            self.assertTrue(any(x['type'] == 'historical_pairing_or_credential_field' for x in package.scan(path, report, kind)))

    def test_document_redaction_keeps_only_the_canonical_site(self):
        public = 'https://' + package.PUBLIC_INSTALL_HOST + '/install'
        private = 'https://private-review.' + 'workers.dev/'
        original = ('[Install](' + public + ') and [old](' + private + ')').encode()
        data, changes = package.redact_document(original)
        self.assertIn(public.encode(), data)
        self.assertNotIn(private.encode(), data)
        self.assertEqual(changes, [{'rule': 'real_deployment_hostname', 'count': 1}])


class SourceClosure(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'source'
        self.root.mkdir()
        self.config = json.loads((package.ROOT / package.ALLOWLIST).read_text())
        for entry in self.config['files']:
            name = entry['path']
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(package.read_regular(package.ROOT, name))
        for tree in source_manifest.TREES:
            (self.root / tree).mkdir(parents=True, exist_ok=True)

    def test_full_declared_closure_is_byte_identical_and_deterministic(self):
        paths, manifest, findings = package.build_payload(self.root, self.config)
        self.assertFalse(findings)
        self.assertTrue(manifest['source_files_are_byte_identical'])
        for entry in manifest['entries']:
            if entry['kind'] in {'source', 'binary'}:
                self.assertEqual(paths[entry['path']], (self.root / entry['path']).read_bytes())
        archive = package.zip_bytes(paths, self.config['archive_root'])
        self.assertEqual(archive, package.zip_bytes(paths, self.config['archive_root']))
        self.assertEqual(package.verify_archive(archive, paths, self.config['archive_root'])['members'], len(paths))
        self.assertFalse(any('/beta4-muse-evidence/' in name or name.startswith('.git/') for name in paths))

    def test_missing_production_or_test_fixture_cannot_pass(self):
        for name in ['apps/android/app/src/main/java/io/airtap/preview/SessionService.java',
                     'apps/android/tests/atomicinput/platform_fakes.py', 'tools/check.py']:
            config = {**self.config, 'files': [e for e in self.config['files'] if e['path'] != name]}
            with self.subTest(name=name), self.assertRaisesRegex(package.Refused, 'missing_source_dependencies'):
                package.build_payload(self.root, config)

    def test_new_production_file_requires_reviewed_allowlist_refresh(self):
        name = 'apps/android/app/src/main/java/io/airtap/preview/NewCapability.java'
        (self.root / name).write_text('package io.airtap.preview; class NewCapability {}\n')
        with self.assertRaisesRegex(package.Refused, 'missing_source_dependencies'):
            source_manifest.validate_closure(self.root, self.config)
        proposed = source_manifest.propose(self.root, self.config)
        self.assertIn(name, {e['path'] for e in proposed['files']})
        self.assertNotIn(name, {e['path'] for e in self.config['files']})
        self.assertEqual((self.root / package.ALLOWLIST).read_bytes(), (package.ROOT / package.ALLOWLIST).read_bytes())

    def test_new_unreviewed_binary_is_not_auto_approved(self):
        (self.root / 'apps/android/app/src/main/new-resource.bin').write_bytes(b'\xff\x00\x80')
        with self.assertRaisesRegex(package.Refused, 'new_binary_requires_explicit_review'):
            source_manifest.propose(self.root, self.config)

    def test_changed_pinned_binary_is_rejected(self):
        jar = self.root / 'apps/android/gradle/wrapper/gradle-wrapper.jar'
        jar.write_bytes(jar.read_bytes() + b'changed')
        with self.assertRaisesRegex(package.Refused, 'reviewed_binary_hash_changed'):
            package.build_payload(self.root, self.config)

    def test_runtime_source_with_private_host_is_refused_not_redacted(self):
        name = 'apps/android/app/src/main/java/io/airtap/preview/SessionService.java'
        file = self.root / name
        data = file.read_bytes() + ('\n// https://private-review.' + 'workers.dev/\n').encode()
        file.write_bytes(data)
        with self.assertRaisesRegex(package.Refused, 'distribution_scan_findings'):
            package.build_payload(self.root, self.config)
        self.assertEqual(file.read_bytes(), data)

    def test_symlink_in_source_closure_is_rejected(self):
        name = self.root / 'apps/android/app/src/main/symlink.txt'
        name.symlink_to(self.root / 'README.md')
        with self.assertRaisesRegex(package.Refused, 'symlink_in_source_closure'):
            package.build_payload(self.root, self.config)

    def test_private_file_cannot_be_added_to_allowlist(self):
        config = {**self.config, 'files': self.config['files'] + [{'path': 'private/raw.json', 'kind': 'source'}]}
        with self.assertRaisesRegex(package.Refused, 'excluded_path_in_allowlist'):
            package.build_payload(self.root, config)

    def test_source_changed_between_reads_is_rejected(self):
        original = package.read_regular
        changed = False
        def change_after_read(root, name):
            nonlocal changed
            data = original(root, name)
            if name == 'README.md' and not changed:
                changed = True
                (root / '.gitignore').write_text('changed during export\n')
            return data
        with patch.object(package, 'read_regular', side_effect=change_after_read):
            with self.assertRaisesRegex(package.Refused, 'source_changed_during_packaging'):
                package.build_payload(self.root, self.config)

    def test_freeze_preserves_existing_artifacts_and_rejects_overwrite(self):
        output = Path(self.tmp.name) / 'output'
        with patch.object(package, 'ROOT', self.root), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(package.main(['--freeze', '--output', str(output)]), 0)
            archive = output / (self.config['archive_root'] + '.zip')
            expected = archive.read_bytes()
            self.assertEqual(package.main(['--freeze', '--output', str(output)]), 0)
            self.assertEqual(archive.read_bytes(), expected)
            archive.write_bytes(b'previous user-owned artifact')
            with self.assertRaisesRegex(package.Refused, 'different_frozen_output_exists'):
                package.main(['--freeze', '--output', str(output)])
            self.assertEqual(archive.read_bytes(), b'previous user-owned artifact')

    def test_old_manifest_without_closure_policy_is_not_a_current_export(self):
        config = dict(self.config)
        config.pop('closure_policy')
        with self.assertRaisesRegex(package.Refused, 'source_closure_policy_required'):
            package.build_payload(self.root, config)


if __name__ == '__main__':
    unittest.main()
