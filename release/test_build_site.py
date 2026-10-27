import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build_site as m


class Releases(unittest.TestCase):
    def setUp(self):
        self.r = json.loads((m.ROOT / 'android/25.json').read_text())

    def test_rejects_debug_and_unrecognized_metadata(self):
        for key, value in [('debuggable', True), ('version_code', True),
                           ('download_path', 'https://evil.test/app.apk'),
                           ('signer_sha256', 'x' * 64), ('version_name', '../escape'),
                           ('private_token', 'secret')]:
            with self.subTest(key=key), self.assertRaises((ValueError, TypeError)):
                m.release_metadata({**self.r, key: value})

    def test_rejects_wrong_apk_before_native_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / 'fake.apk'
            apk.write_bytes(b'wrong')
            with patch('subprocess.check_output') as native:
                with self.assertRaises(ValueError):
                    m.verify_apk(apk, self.r, Path(tmp))
                native.assert_not_called()

    def previous(self, tmp):
        data = b'previous verified APK fixture'
        release = {**self.r, 'apk_sha256': m.digest(data), 'apk_bytes': len(data)}
        previous = tmp / 'previous'
        (previous / 'site/updates').mkdir(parents=True)
        (previous / 'site/downloads').mkdir()
        (previous / 'site/updates/android.json').write_text(json.dumps(release))
        (previous / 'site/downloads' / (release['apk_sha256'] + '.apk')).write_bytes(data)
        return previous, release, data

    def build(self, tmp, release, data, previous):
        metadata = tmp / 'next.json'
        metadata.write_text(json.dumps(release))
        # Native signature/manifest verification is independent of promotion logic.
        with patch.object(m, 'verify_apk', return_value=data):
            return m.build(tmp / 'app.apk', metadata, tmp / 'new', tmp, previous)

    def test_same_apk_can_refresh_site_without_increasing_android_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, data = self.previous(tmp)
            result = self.build(tmp, release, data, previous)
            self.assertEqual(result['deployment_kind'], 'site_refresh')
            self.assertFalse(result['deployed'])
            self.assertEqual(json.loads((tmp / 'new/site/updates/android.json').read_text()), release)
            self.assertEqual((tmp / 'new/site/downloads' / (release['apk_sha256'] + '.apk')).read_bytes(), data)
            manifest = json.loads((tmp / 'new/build-manifest.json').read_text())
            self.assertEqual(manifest['previous_version_code'], release['version_code'])
            self.assertIn('site/index.html', manifest['files'])

    def test_same_code_rejects_changed_apk_or_native_identity(self):
        changes = [('apk_sha256', 'a' * 64), ('apk_bytes', 100),
                   ('version_name', 'other-build'), ('min_sdk', 35),
                   ('target_sdk', 37), ('signer_sha256', 'b' * 64)]
        for key, value in changes:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as tmp:
                tmp = Path(tmp)
                previous, release, data = self.previous(tmp)
                with self.assertRaises(ValueError):
                    self.build(tmp, {**release, key: value}, data, previous)
                self.assertFalse((tmp / 'new').exists())

    def test_android_promotion_keeps_old_immutable_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, old_data = self.previous(tmp)
            data = b'new verified APK fixture'
            new = {**release, 'version_code': release['version_code'] + 1,
                   'version_name': 'next', 'apk_sha256': m.digest(data), 'apk_bytes': len(data)}
            result = self.build(tmp, new, data, previous)
            self.assertEqual(result['deployment_kind'], 'android_update')
            for sha, content in [(new['apk_sha256'], data), (release['apk_sha256'], old_data)]:
                self.assertEqual((tmp / 'new/site/downloads' / (sha + '.apk')).read_bytes(), content)

    def retirement_policy(self, tmp, hashes):
        policy = tmp / 'retired-apks.json'
        policy.write_text(json.dumps({'schema':'airtap-retired-android-artifacts-v1', 'sha256':sorted(hashes)}))
        return patch.object(m, 'RETIRED_APKS', policy)

    def test_retirement_removes_public_binary_but_preserves_previous_frozen_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, data = self.previous(tmp)
            retired_data = b'retired historical APK fixture'
            retired = m.digest(retired_data)
            old_path = previous / 'site/downloads' / (retired + '.apk')
            old_path.write_bytes(retired_data)
            with self.retirement_policy(tmp, [retired]):
                self.build(tmp, release, data, previous)
            self.assertEqual(old_path.read_bytes(), retired_data)
            self.assertFalse((tmp / 'new/site/downloads' / (retired + '.apk')).exists())
            self.assertEqual(list((tmp / 'new/site/downloads').glob('*.apk')), [tmp / 'new/site/downloads' / (release['apk_sha256'] + '.apk')])
            self.assertEqual(json.loads((tmp / 'new/site/updates/retired-android.json').read_text())['sha256'], [retired])

    def test_retired_routes_survive_future_site_refresh_even_without_binary(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, data = self.previous(tmp)
            old_retired = '0' * 64
            (previous / 'site/updates/retired-android.json').write_text(json.dumps({'schema':'airtap-retired-android-artifacts-v1', 'sha256':[old_retired]}))
            with self.retirement_policy(tmp, []):
                self.build(tmp, release, data, previous)
            self.assertEqual(json.loads((tmp / 'new/site/updates/retired-android.json').read_text())['sha256'], [old_retired])

    def test_retired_apk_cannot_be_recommended_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, data = self.previous(tmp)
            with self.retirement_policy(tmp, [release['apk_sha256']]), self.assertRaisesRegex(ValueError, 'Recommended APK has been retired'):
                self.build(tmp, release, data, previous)
            self.assertFalse((tmp / 'new').exists())

    def test_malformed_retirement_metadata_rejected(self):
        for value in [{}, {'schema':'wrong','sha256':[]}, {'schema':'airtap-retired-android-artifacts-v1','sha256':['../escape']},
                      {'schema':'airtap-retired-android-artifacts-v1','sha256':['0'*64,'0'*64]},
                      {'schema':'airtap-retired-android-artifacts-v1','sha256':['f'*64,'0'*64]}]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                m.retirement_metadata(value)

    def test_downgrade_or_signer_change_does_not_create_output(self):
        for change in [{'version_code': 24}, {'version_code': 26, 'signer_sha256': 'b' * 64}]:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as tmp:
                tmp = Path(tmp)
                previous, release, data = self.previous(tmp)
                with self.assertRaises(ValueError):
                    self.build(tmp, {**release, **change}, data, previous)
                self.assertFalse((tmp / 'new').exists())

    def test_missing_or_modified_previous_apk_is_rejected_before_writing(self):
        for content in [None, b'tampered']:
            with self.subTest(content=content), tempfile.TemporaryDirectory() as tmp:
                tmp = Path(tmp)
                previous, release, data = self.previous(tmp)
                apk = previous / 'site/downloads' / (release['apk_sha256'] + '.apk')
                if content is None:
                    apk.unlink()
                else:
                    apk.write_bytes(content)
                with self.assertRaises(ValueError):
                    self.build(tmp, release, data, previous)
                self.assertFalse((tmp / 'new').exists())

    def test_frozen_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, data = self.previous(tmp)
            (tmp / 'new').mkdir()
            marker = tmp / 'new/marker'
            marker.write_text('keep')
            with self.assertRaises(ValueError):
                self.build(tmp, release, data, previous)
            self.assertEqual(marker.read_text(), 'keep')


if __name__ == '__main__':
    unittest.main()
