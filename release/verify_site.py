#!/usr/bin/env python3
"""Verify a frozen site locally and, optionally, its actual public HTTP responses.

Read-only: never deploys, changes the recommendation, or requests private endpoints.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, HTTPRedirectHandler, build_opener

from build_site import release_metadata, retirement_metadata


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_build(build):
    build = build.resolve()
    manifest = json.loads((build / 'build-manifest.json').read_text())
    if manifest.get('schema') != 'airtap-release-site-build-v1':
        raise ValueError('Unknown frozen build schema')
    release = release_metadata(manifest['release'])
    files = manifest.get('files')
    if not isinstance(files, dict) or not files:
        raise ValueError('Missing frozen file inventory')
    actual = {str(p.relative_to(build)) for p in build.rglob('*') if p.is_file()}
    if actual != set(files) | {'build-manifest.json'}:
        raise ValueError('Frozen file inventory does not exactly cover the directory')
    contents = {}
    for name, expected in files.items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name or not re.fullmatch('[a-f0-9]{64}', expected):
            raise ValueError('Invalid frozen file entry')
        file = build / name
        if not file.resolve().is_relative_to(build) or file.is_symlink():
            raise ValueError('Frozen file must be local and non-symlinked')
        data = file.read_bytes()
        if sha(data) != expected:
            raise ValueError('Frozen file content differs: ' + name)
        contents[name] = data
    if json.loads(contents['site/updates/android.json']) != release:
        raise ValueError('Recommendation and build manifest disagree')
    apk = contents.get('site/downloads/' + release['apk_sha256'] + '.apk')
    if apk is None or len(apk) != release['apk_bytes'] or sha(apk) != release['apk_sha256']:
        raise ValueError('Recommended APK differs')
    for name, data in contents.items():
        if name.startswith('site/downloads/') and (not name.endswith('.apk') or name != 'site/downloads/' + sha(data) + '.apk'):
            raise ValueError('Immutable APK name differs from its content')
    if 'site/updates/retired-android.json' in contents:
        retired = retirement_metadata(json.loads(contents['site/updates/retired-android.json']))['sha256']
        if release['apk_sha256'] in retired or any('site/downloads/' + value + '.apk' in contents for value in retired):
            raise ValueError('Retired APK must not be recommended or included in public assets')
    return release, contents


def request(url, method, max_bytes):
    opener = build_opener(NoRedirect())
    try:
        response = opener.open(Request(url, method=method), timeout=30)
    except HTTPError as error:
        response = error
    with response:
        data = response.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise ValueError('Public response exceeded its expected size')
        return response.status, dict(response.headers.items()), data


def verify(build, base_url=None, fetch=request, managed_robots_prefix=None):
    release, contents = read_build(build)
    checked = []
    transformations = []
    if managed_robots_prefix is not None:
        if (not isinstance(managed_robots_prefix, bytes) or not 1 <= len(managed_robots_prefix) <= 16384
                or not base_url or 'site/robots.txt' not in contents):
            raise ValueError('A reviewed robots prefix requires public robots.txt and at most 16384 bytes')
        if b'\x00' in managed_robots_prefix:
            raise ValueError('A reviewed robots prefix must be UTF-8 text without NUL bytes')
        managed_robots_prefix.decode('utf-8')
    if base_url:
        parsed = urlsplit(base_url)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
            raise ValueError('Use a public HTTPS origin without credentials or a path')
        base_url = base_url.rstrip('/')
        def public_request(path, method, limit):
            try:
                return fetch(base_url + path, method, limit)
            except (OSError, ValueError) as failure:
                # Only the public relative route and failure type enter diagnostics.
                raise ValueError('Public request failed: ' + method + ' ' + path + ' (' + type(failure).__name__ + ')') from None
        paths = {'/': 'site/index.html', '/install': 'site/index.html', '/help': 'site/index.html',
                 '/privacy': 'site/privacy.html', '/release-notes': 'site/release-notes.html'}
        # Includes CSS, JS, QR code, the update JSON, and retained immutable APKs.
        paths.update({'/' + name.removeprefix('site/'): name for name in contents if name.startswith('site/')})
        for path, name in paths.items():
            expected = contents[name]
            prefix = managed_robots_prefix if path == '/robots.txt' else None
            status, headers, data = public_request(path, 'GET', len(expected) + len(prefix or b''))
            headers = {key.lower(): value for key, value in headers.items()}
            # A host may prepend managed crawler policy. Only an explicitly
            # reviewed, byte-for-byte prefix is accepted; never strip arbitrary
            # comments or ignore rules. Frozen source bytes remain unchanged.
            transformed = prefix is not None and data == prefix + expected
            if status != 200 or (data != expected and not transformed):
                raise ValueError('Public content differs from the frozen build: ' + path)
            if transformed:
                if headers.get('content-type', '').split(';', 1)[0].strip().lower() != 'text/plain':
                    raise ValueError('Unexpected managed robots content type')
                transformations.append({'path': path, 'kind': 'reviewed_managed_robots_prefix',
                                        'prefix_bytes': len(prefix), 'prefix_sha256': sha(prefix),
                                        'source_sha256': sha(expected), 'public_sha256': sha(data)})
            apk = path.endswith('.apk')
            cache = 'public, max-age=31536000, immutable' if apk else 'no-store' if path in ('/updates/android.json', '/updates/retired-android.json') else 'public, no-cache, no-transform'
            if headers.get('cache-control') != cache:
                raise ValueError('Unexpected cache policy: ' + path)
            if headers.get('x-content-type-options') != 'nosniff' or headers.get('referrer-policy') != 'no-referrer':
                raise ValueError('Missing public response protection: ' + path)
            if apk and (headers.get('content-type') != 'application/vnd.android.package-archive' or headers.get('content-disposition') != 'attachment; filename="Airtap.apk"'):
                raise ValueError('Unexpected APK download headers')
            checked.append(path)
        status, headers, data = public_request('/download/android', 'GET', 0)
        headers = {key.lower(): value for key, value in headers.items()}
        if status != 302 or headers.get('location') != '/downloads/' + release['apk_sha256'] + '.apk' or headers.get('cache-control') != 'no-store' or data:
            raise ValueError('Fixed download entry does not select the verified APK')
        checked.append('/download/android')
        path = '/downloads/' + release['apk_sha256'] + '.apk'
        status, headers, data = public_request(path, 'HEAD', 0)
        headers = {key.lower(): value for key, value in headers.items()}
        if status != 200 or data or headers.get('content-type') != 'application/vnd.android.package-archive':
            raise ValueError('APK HEAD response is invalid')
        checked.append('HEAD ' + path)
        retired = json.loads(contents.get('site/updates/retired-android.json', b'{"sha256":[]}'))['sha256']
        for value in retired:
            path = '/downloads/' + value + '.apk'
            for method in ('GET', 'HEAD'):
                status, headers, data = public_request(path, method, 4096 if method == 'GET' else 0)
                headers = {key.lower(): value for key, value in headers.items()}
                if (status != 410 or headers.get('cache-control') != 'no-store, no-transform'
                        or headers.get('content-type') != 'text/html; charset=utf-8'
                        or headers.get('content-disposition') is not None
                        or headers.get('x-content-type-options') != 'nosniff'
                        or headers.get('referrer-policy') != 'no-referrer'
                        or headers.get('link') != '</install>; rel="alternate"'
                        or (method == 'GET' and b'href="/install"' not in data)
                        or (method == 'HEAD' and data)):
                    raise ValueError('Retired APK must provide an explicit upgrade response: ' + method + ' ' + path)
                checked.append((method + ' ' if method == 'HEAD' else '') + path)
    return {'schema': 'airtap-site-verification-v1', 'version_code': release['version_code'],
            'apk_sha256': release['apk_sha256'], 'local_files_verified': len(contents),
            'public_origin': base_url, 'public_checks': checked, 'passed': True,
            'public_transformations': transformations,
            'scope': 'Frozen site integrity and optional public HTTP content; not phone or personal-AI acceptance.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--base-url', help='Optional deployed public HTTPS origin')
    parser.add_argument('--managed-robots-prefix', type=Path,
                        help='Optional local file containing a separately reviewed exact public robots.txt prefix')
    args = parser.parse_args()
    try:
        prefix = None
        if args.managed_robots_prefix:
            with args.managed_robots_prefix.open('rb') as file:
                prefix = file.read(16385)
        print(json.dumps(verify(args.build, args.base_url, managed_robots_prefix=prefix), ensure_ascii=False, indent=2))
    except (ValueError, KeyError, OSError, TypeError) as failure:
        parser.exit(1, 'Verification failed: ' + str(failure) + '\n')
