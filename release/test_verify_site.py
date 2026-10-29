import json
import tempfile
import unittest
from pathlib import Path
from urllib.parse import urlsplit
from unittest.mock import patch

import build_site
import verify_site


class FrozenSiteVerification(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.apk = b'verified APK fixture'
        self.release = json.loads((build_site.ROOT / 'android/25.json').read_text())
        self.release.update(apk_sha256=verify_site.sha(self.apk), apk_bytes=len(self.apk))
        metadata = self.root / 'release.json'
        metadata.write_text(json.dumps(self.release))
        self.build = self.root / 'site'
        with patch.object(build_site, 'verify_apk', return_value=self.apk):
            build_site.build(self.root / 'app.apk', metadata, self.build, self.root)

    def response(self, url, method, max_bytes):
        path = urlsplit(url).path
        apk_path = '/downloads/' + self.release['apk_sha256'] + '.apk'
        retired = json.loads((self.build / 'site/updates/retired-android.json').read_text())['sha256']
        if path in ['/downloads/' + value + '.apk' for value in retired]:
            return 410, {'Content-Type':'text/html; charset=utf-8', 'Cache-Control':'no-store, no-transform',
                         'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer',
                         'Link':'</install>; rel="alternate"'}, b'' if method == 'HEAD' else b'<a href="/install">Update</a>'
        if path == '/download/android':
            return 302, {'Location': apk_path, 'Cache-Control': 'no-store'}, b''
        path = {'/': '/index.html', '/install': '/index.html', '/help': '/index.html',
                '/privacy': '/privacy.html', '/release-notes': '/release-notes.html'}.get(path, path)
        data = (self.build / 'site' / path.lstrip('/')).read_bytes()
        headers = {'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
                   'Cache-Control': 'public, no-cache, no-transform'}
        if path in ('/updates/android.json', '/updates/retired-android.json'):
            headers['Cache-Control'] = 'no-store'
        if path.endswith('.apk'):
            headers.update({'Cache-Control': 'public, max-age=31536000, immutable',
                            'Content-Type': 'application/vnd.android.package-archive',
                            'Content-Disposition': 'attachment; filename="Airtap.apk"'})
        return 200, headers, b'' if method == 'HEAD' else data

    def test_frozen_build_and_public_recommendation_pass(self):
        result = verify_site.verify(self.build, 'https://example.test', self.response)
        self.assertTrue(result['passed'])
        self.assertIn('/download/android', result['public_checks'])
        self.assertIn('/help', result['public_checks'])
        self.assertEqual(result['apk_sha256'], self.release['apk_sha256'])

    def test_modified_or_extra_local_file_is_rejected(self):
        page = self.build / 'site/index.html'
        original = page.read_bytes()
        page.write_bytes(original + b'changed')
        with self.assertRaisesRegex(ValueError, 'content differs'):
            verify_site.verify(self.build)
        page.write_bytes(original)
        (self.build / 'private-token').write_text('must not deploy')
        with self.assertRaisesRegex(ValueError, 'inventory'):
            verify_site.verify(self.build)

    def test_stale_live_page_is_not_reported_as_success(self):
        def stale(url, method, limit):
            status, headers, data = self.response(url, method, limit)
            return status, headers, b'old site' if urlsplit(url).path == '/help' else data
        with self.assertRaisesRegex(ValueError, 'Public content differs'):
            verify_site.verify(self.build, 'https://example.test', stale)

    def test_wrong_recommendation_redirect_is_rejected(self):
        def wrong(url, method, limit):
            status, headers, data = self.response(url, method, limit)
            if url.endswith('/download/android'):
                headers['Location'] = '/downloads/' + '0' * 64 + '.apk'
            return status, headers, data
        with self.assertRaisesRegex(ValueError, 'Fixed download entry'):
            verify_site.verify(self.build, 'https://example.test', wrong)

    def test_old_apk_still_downloadable_or_redirected_is_not_reported_as_retired(self):
        retired = json.loads((self.build / 'site/updates/retired-android.json').read_text())['sha256'][0]
        for wrong_status in (200, 302, 404):
            def wrong(url, method, limit):
                status, headers, data = self.response(url, method, limit)
                if urlsplit(url).path == '/downloads/' + retired + '.apk':
                    status = wrong_status
                return status, headers, data
            with self.subTest(status=wrong_status), self.assertRaisesRegex(ValueError, 'Retired APK'):
                verify_site.verify(self.build, 'https://example.test', wrong)

    def test_manifest_cannot_hide_retired_binary_inside_asset_inventory(self):
        retired = self.release['apk_sha256']
        path = self.build / 'site/updates/retired-android.json'
        path.write_text(json.dumps({'schema':'airtap-retired-android-artifacts-v1', 'sha256':[retired]}))
        manifest_path = self.build / 'build-manifest.json'
        manifest = json.loads(manifest_path.read_text())
        manifest['files']['site/updates/retired-android.json'] = verify_site.sha(path.read_bytes())
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'Retired APK'):
            verify_site.verify(self.build)

    def test_cdn_injection_and_unexpected_cache_are_detected(self):
        for mutation in ['body', 'cache']:
            def changed(url, method, limit):
                status, headers, data = self.response(url, method, limit)
                if urlsplit(url).path == '/':
                    if mutation == 'body':
                        data += b'<script src="analytics"></script>'
                    else:
                        headers['Cache-Control'] = 'public, max-age=3600'
                return status, headers, data
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                verify_site.verify(self.build, 'https://example.test', changed)

    def test_network_failure_reports_the_public_route_without_raw_error(self):
        def failed(url, method, limit):
            if urlsplit(url).path == '/release-notes':
                raise OSError('synthetic transport detail must not be echoed')
            return self.response(url, method, limit)
        with self.assertRaises(ValueError) as raised:
            verify_site.verify(self.build, 'https://example.test', failed)
        self.assertEqual(str(raised.exception), 'Public request failed: GET /release-notes (OSError)')

    def test_managed_robots_requires_the_explicit_reviewed_exact_prefix(self):
        prefix = b'# Reviewed hosting policy\nUser-agent: ExampleBot\nDisallow: /\n\n'
        def managed(url, method, limit):
            status, headers, data = self.response(url, method, limit)
            if urlsplit(url).path == '/robots.txt':
                headers['Content-Type'] = 'text/plain; charset=utf-8'
                data = prefix + data
            return status, headers, data
        with self.assertRaisesRegex(ValueError, 'Public content differs'):
            verify_site.verify(self.build, 'https://example.test', managed)
        result = verify_site.verify(self.build, 'https://example.test', managed, prefix)
        change, = result['public_transformations']
        self.assertEqual(change['path'], '/robots.txt')
        self.assertEqual(change['prefix_sha256'], verify_site.sha(prefix))
        self.assertEqual(change['source_sha256'], verify_site.sha((self.build / 'site/robots.txt').read_bytes()))
        self.assertEqual(change['public_sha256'], verify_site.sha(prefix + (self.build / 'site/robots.txt').read_bytes()))

    def test_managed_robots_does_not_allow_other_changes(self):
        prefix = b'# Reviewed hosting policy\n'
        for mutation in ('prefix', 'source', 'suffix', 'html', 'type'):
            def changed(url, method, limit):
                status, headers, data = self.response(url, method, limit)
                if urlsplit(url).path == '/robots.txt':
                    headers['Content-Type'] = 'text/html' if mutation == 'type' else 'text/plain'
                    data = prefix + data
                    if mutation == 'prefix':
                        data = b'# Changed hosting policy\n' + data
                    elif mutation == 'source':
                        data = data.replace(b'Allow: /', b'Disallow: /')
                    elif mutation == 'suffix':
                        data += b'\nUser-agent: *\nDisallow: /\n'
                elif mutation == 'html' and urlsplit(url).path == '/':
                    data += b'<script>injected</script>'
                return status, headers, data
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                verify_site.verify(self.build, 'https://example.test', changed, prefix)

    def test_unused_managed_robots_prefix_does_not_change_plain_verification(self):
        result = verify_site.verify(self.build, 'https://example.test', self.response, b'# Reviewed policy\n')
        self.assertEqual(result['public_transformations'], [])

    def test_invalid_managed_robots_prefix_is_rejected(self):
        for prefix in (b'', b'x' * 16385, 'not bytes', b'\x00', b'\xff'):
            with self.subTest(prefix_type=type(prefix).__name__), self.assertRaises(ValueError):
                verify_site.verify(self.build, 'https://example.test', self.response, prefix)
        with self.assertRaises(ValueError):
            verify_site.verify(self.build, managed_robots_prefix=b'# Reviewed policy\n')

    def test_private_or_non_https_origin_is_rejected_before_fetch(self):
        for origin in ['http://example.test', 'https://user:pass@example.test',
                       'https://example.test/private', 'https://example.test/?token=secret']:
            with self.subTest(origin=origin), patch('verify_site.request') as fetch:
                with self.assertRaises(ValueError):
                    verify_site.verify(self.build, origin, fetch)
                fetch.assert_not_called()

    def test_media_full_bytes_head_and_range_are_required(self):
        video = bytes(range(256)) * 8
        target = self.build/'site/media/usage-code39.mp4'
        target.parent.mkdir(); target.write_bytes(video)
        manifest_path = self.build/'build-manifest.json'
        manifest = json.loads(manifest_path.read_text())
        manifest['files']['site/media/usage-code39.mp4'] = verify_site.sha(video)
        manifest_path.write_text(json.dumps(manifest))
        def media_response(url, method, limit, request_headers=None):
            if urlsplit(url).path != '/media/usage-code39.mp4':
                return self.response(url, method, limit)
            headers = {'Content-Type':'video/mp4','Content-Length':str(len(video)),
                       'Cache-Control':'public, no-cache, no-transform',
                       'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}
            if method == 'HEAD': return 200, headers, b''
            if request_headers:
                self.assertEqual(request_headers, {'Range':'bytes=0-1023'})
                headers.update({'Content-Length':'1024','Content-Range':'bytes 0-1023/2048'})
                return 206, headers, video[:1024]
            return 200, headers, video
        passed = verify_site.verify(self.build, 'https://example.test', media_response)
        self.assertIn('RANGE /media/usage-code39.mp4', passed['public_checks'])
        for fault in ['ignored-range', 'wrong-range', 'wrong-head', 'wrong-mime']:
            def faulty(url, method, limit, request_headers=None):
                status, headers, data = media_response(url, method, limit, request_headers)
                if urlsplit(url).path == '/media/usage-code39.mp4':
                    if request_headers and fault == 'ignored-range': status = 200
                    if request_headers and fault == 'wrong-range': headers['Content-Range'] = 'bytes 0-1023/9999'
                    if method == 'HEAD' and fault == 'wrong-head': headers['Content-Length'] = '1'
                    if fault == 'wrong-mime': headers['Content-Type'] = 'text/plain'
                return status, headers, data
            with self.subTest(fault=fault), self.assertRaisesRegex(ValueError, 'video|Video'):
                verify_site.verify(self.build, 'https://example.test', faulty)


if __name__ == '__main__':
    unittest.main()
