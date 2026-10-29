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

    def media_fixture(self, tmp):
        source = tmp / 'source'
        (source / 'site').mkdir(parents=True)
        for name in m.SITE_FILES:
            (source / 'site' / name).write_text('public fixture')
        (source / 'site/index.html').write_text('<html>{{USAGE_DEMO}}</html>')
        (source / 'worker.mjs').write_text('// fixture')
        poster = b'reviewed poster fixture'
        (source / 'site/usage-poster.jpg').write_bytes(poster)
        video = tmp / 'private-reviewed.mp4'
        video.write_bytes(b'reviewed public video fixture')
        manifest = {'schema':'airtap-reviewed-usage-video-v1', 'filename':'usage-code39.mp4',
                    'sha256':m.digest(video.read_bytes()), 'bytes':video.stat().st_size,
                    'duration_seconds':90, 'version_code':39, 'title':'Actual task',
                    'description':'Edited footage; wait accelerated; maintainer preparation retained.',
                    'poster':{'filename':'usage-poster.jpg','sha256':m.digest(poster),'bytes':len(poster)}}
        manifest_path = source / 'usage-video.json'
        manifest_path.write_text(json.dumps(manifest))
        return source, video, manifest_path, manifest

    def test_explicit_reviewed_media_is_bound_and_no_media_build_has_no_broken_player(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, apk = self.previous(tmp)
            source, video, manifest_path, manifest = self.media_fixture(tmp)
            metadata = tmp / 'release.json'
            metadata.write_text(json.dumps(release))
            with patch.object(m, 'ROOT', source), patch.object(m, 'MEDIA_MANIFEST', manifest_path), patch.object(m, 'verify_apk', return_value=apk):
                for name, media in [('with-video', video), ('without-video', None)]:
                    out = tmp / name
                    m.build(tmp/'app.apk', metadata, out, tmp, previous, media)
                    page = (out/'site/index.html').read_text()
                    self.assertNotIn('{{USAGE_DEMO}}', page)
                    self.assertEqual('<video ' in page, media is not None)
                    self.assertEqual((out/'site/media/usage-code39.mp4').exists(), media is not None)
                    self.assertEqual((out/'site/usage-poster.jpg').exists(), media is not None)
                    if media:
                        self.assertEqual((out/'site/media/usage-code39.mp4').read_bytes(), video.read_bytes())
                        inventory = json.loads((out/'build-manifest.json').read_text())['files']
                        self.assertEqual(inventory['site/media/usage-code39.mp4'], manifest['sha256'])

    def test_media_wrong_bytes_or_symlink_are_refused_before_frozen_output(self):
        for change in ['video', 'poster', 'symlink', 'manifest-path']:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as tmp:
                tmp = Path(tmp)
                previous, release, apk = self.previous(tmp)
                source, video, manifest_path, manifest = self.media_fixture(tmp)
                if change == 'video':
                    video.write_bytes(b'x' * manifest['bytes'])
                elif change == 'poster':
                    (source/'site/usage-poster.jpg').write_bytes(b'x' * manifest['poster']['bytes'])
                elif change == 'symlink':
                    link = tmp/'linked.mp4'; link.symlink_to(video); video = link
                else:
                    manifest['filename'] = '../private.mp4'
                    manifest_path.write_text(json.dumps(manifest))
                metadata = tmp/'release.json'; metadata.write_text(json.dumps(release))
                with patch.object(m, 'ROOT', source), patch.object(m, 'MEDIA_MANIFEST', manifest_path), patch.object(m, 'verify_apk', return_value=apk), self.assertRaises(ValueError):
                    m.build(tmp/'app.apk', metadata, tmp/'refused', tmp, previous, video)
                self.assertFalse((tmp/'refused').exists())

    def test_no_media_build_cannot_copy_stray_recordings_or_symlinked_assets(self):
        for change in ['unused-poster', 'poster-symlink', 'stray-video']:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as tmp:
                tmp = Path(tmp)
                previous, release, apk = self.previous(tmp)
                source, video, manifest_path, manifest = self.media_fixture(tmp)
                poster = source/'site/usage-poster.jpg'
                if change == 'unused-poster':
                    poster.write_bytes(b'unreviewed poster must not be copied')
                elif change == 'poster-symlink':
                    poster.unlink(); poster.symlink_to(video)
                else:
                    (source/'site/raw-recording.mp4').write_bytes(b'private recording fixture')
                metadata = tmp/'release.json'; metadata.write_text(json.dumps(release))
                out = tmp/'out'
                with patch.object(m, 'ROOT', source), patch.object(m, 'MEDIA_MANIFEST', manifest_path), patch.object(m, 'verify_apk', return_value=apk):
                    if change == 'unused-poster':
                        m.build(tmp/'app.apk', metadata, out, tmp, previous)
                        self.assertFalse((out/'site/usage-poster.jpg').exists())
                    else:
                        with self.assertRaisesRegex(ValueError, 'site asset'):
                            m.build(tmp/'app.apk', metadata, out, tmp, previous)
                        self.assertFalse(out.exists())

    def install_fixture(self, source, tmp, usage):
        poster = b'reviewed installation poster fixture'
        (source/'site/install-poster.jpg').write_bytes(poster)
        video = tmp/'private-reviewed-install.mp4'
        video.write_bytes(b'reviewed installation video fixture')
        manifest = {**usage, 'schema':'airtap-reviewed-install-video-v1',
                    'filename':'install-code39.mp4', 'sha256':m.digest(video.read_bytes()),
                    'bytes':video.stat().st_size, 'duration_seconds':66,
                    'poster':{'filename':'install-poster.jpg', 'sha256':m.digest(poster), 'bytes':len(poster)}}
        path = source/'install-video.json'
        path.write_text(json.dumps(manifest))
        (source/'site/index.html').write_text('<html>{{USAGE_DEMO}}<section id="start">{{INSTALL_DEMO}}</section></html>')
        return video, path, manifest

    def test_two_independent_players_only_include_explicit_hash_bound_media(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            previous, release, apk = self.previous(tmp)
            source, usage, usage_path, usage_manifest = self.media_fixture(tmp)
            install, install_path, install_manifest = self.install_fixture(source, tmp, usage_manifest)
            metadata = tmp/'release.json'; metadata.write_text(json.dumps(release))
            with patch.object(m, 'ROOT', source), patch.object(m, 'MEDIA_MANIFEST', usage_path), \
                    patch.object(m, 'INSTALL_MEDIA_MANIFEST', install_path), patch.object(m, 'verify_apk', return_value=apk):
                for use_usage, use_install in [(True,True), (True,False), (False,True), (False,False)]:
                    out = tmp/f'players-{use_usage}-{use_install}'
                    m.build(tmp/'app.apk', metadata, out, tmp, previous,
                            usage if use_usage else None, install if use_install else None)
                    page = (out/'site/index.html').read_text()
                    self.assertNotIn('{{', page)
                    self.assertEqual(page.count('<video '), int(use_usage)+int(use_install))
                    for kind, included, fixture in [('usage',use_usage,usage_manifest), ('install',use_install,install_manifest)]:
                        identity = 'demo' if kind == 'usage' else 'install-demo'
                        self.assertEqual(f'id="{identity}"' in page, included)
                        self.assertEqual((out/f'site/media/{kind}-video.json').exists(), included)
                        self.assertEqual((out/'site'/fixture['poster']['filename']).exists(), included)
                        self.assertEqual((out/'site/media'/fixture['filename']).exists(), included)
                        if included:
                            inventory = json.loads((out/'build-manifest.json').read_text())['files']
                            self.assertEqual(inventory['site/media/'+fixture['filename']], fixture['sha256'])
                    self.assertEqual(json.loads((out/'site/updates/android.json').read_text()), release)

    def test_install_media_hash_size_symlink_and_colliding_identity_fail_closed(self):
        for fault in ['wrong-video', 'poster-symlink', 'oversize', 'duplicate-video']:
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as tmp:
                tmp = Path(tmp)
                previous, release, apk = self.previous(tmp)
                source, usage, usage_path, usage_manifest = self.media_fixture(tmp)
                install, install_path, install_manifest = self.install_fixture(source, tmp, usage_manifest)
                if fault == 'wrong-video': install.write_bytes(b'x'*install_manifest['bytes'])
                elif fault == 'poster-symlink':
                    poster = source/'site/install-poster.jpg'; poster.unlink(); poster.symlink_to(install)
                elif fault == 'oversize': install_manifest['bytes'] = m.MAX_MEDIA_BYTES+1
                else: install_manifest['filename'] = usage_manifest['filename']
                install_path.write_text(json.dumps(install_manifest))
                metadata = tmp/'release.json'; metadata.write_text(json.dumps(release))
                with patch.object(m, 'ROOT', source), patch.object(m, 'MEDIA_MANIFEST', usage_path), \
                        patch.object(m, 'INSTALL_MEDIA_MANIFEST', install_path), patch.object(m, 'verify_apk', return_value=apk), \
                        self.assertRaises(ValueError):
                    m.build(tmp/'app.apk', metadata, tmp/'refused', tmp, previous, usage, install)
                self.assertFalse((tmp/'refused').exists())

    def test_recorded_versions_are_optional_and_do_not_change_recommended_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            self.r = json.loads((m.ROOT/'android/39.json').read_text())
            previous, release, apk = self.previous(tmp)
            source, usage, usage_path, usage_manifest = self.media_fixture(tmp)
            install, install_path, install_manifest = self.install_fixture(source, tmp, usage_manifest)
            metadata = tmp/'release.json'; metadata.write_text(json.dumps(release))
            with patch.object(m, 'ROOT', source), patch.object(m, 'MEDIA_MANIFEST', usage_path), \
                    patch.object(m, 'INSTALL_MEDIA_MANIFEST', install_path), patch.object(m, 'verify_apk', return_value=apk):
                for name, recorded in [('legacy', None), ('mixed', [39, 40]), ('other-source', [40])]:
                    with self.subTest(name=name):
                        manifest = dict(install_manifest)
                        if recorded is not None:
                            manifest['recorded_version_codes'] = recorded
                        install_path.write_text(json.dumps(manifest))
                        out = tmp/name
                        result = m.build(tmp/'app.apk', metadata, out, tmp, previous, usage, install)
                        public = json.loads((out/'site/media/install-video.json').read_text())
                        self.assertEqual(public, manifest)
                        self.assertEqual(public['version_code'], 39)
                        self.assertEqual(json.loads((out/'site/updates/android.json').read_text()), release)
                        self.assertEqual((out/'site/downloads'/f"{release['apk_sha256']}.apk").read_bytes(), apk)
                        self.assertEqual(result['deployment_kind'], 'site_refresh')

    def test_invalid_recorded_versions_and_unknown_media_fields_are_refused(self):
        invalid = [None, [], [40, 39], [39, 39], [True], [False], [0], [-1],
                   [39.0], ['39'], 39, '39', {'code': 39}, [[39]]]
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            source, _, usage_path, usage_manifest = self.media_fixture(tmp)
            _, install_path, install_manifest = self.install_fixture(source, tmp, usage_manifest)
            with patch.object(m, 'INSTALL_MEDIA_MANIFEST', install_path):
                for recorded in invalid:
                    with self.subTest(recorded=recorded):
                        install_path.write_text(json.dumps({**install_manifest, 'recorded_version_codes': recorded}))
                        with self.assertRaisesRegex(ValueError, 'recorded video versions'):
                            m.reviewed_media(None, 'install')
                install_path.write_text(json.dumps({**install_manifest, 'recorded_version_codes': [39, 40],
                                                   'recorded_version_code': 40}))
                with self.assertRaisesRegex(ValueError, 'Unexpected public media manifest'):
                    m.reviewed_media(None, 'install')


if __name__ == '__main__':
    unittest.main()
