#!/usr/bin/env python3
"""Check the current source allowlist or write a reviewable refresh proposal.

Never reads Git history, publishes files, or changes the active allowlist implicitly.
The declared source/test trees are a bounded dependency closure, not a general
static dependency resolver. New dependency locations require a reviewed policy change.
"""
from __future__ import annotations
import argparse
import ast
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
POLICY = 'airtap-source-closure-v1'
ALLOWLIST = 'tools/source-package-files.json'
TREES = ('apps/android/app/src/main', 'apps/android/scripts', 'apps/android/tests',
         'cli', 'relay/service/tests', 'release/site', 'release/android', 'tools/ci', 'tools/tests')
DIRECT_SOURCE = {'relay/service': {'.py', '.mjs', '.json'}, 'tools': {'.py'},
                 'release': {'.py', '.mjs'}}
FIXED = {'apps/android/app/build.gradle', 'apps/android/build.gradle',
         'apps/android/app/tls-proguard-rules.pro',
         'apps/android/gradle.properties', 'apps/android/settings.gradle',
         'apps/android/gradlew', 'apps/android/gradlew.bat',
         'apps/android/gradle/wrapper/gradle-wrapper.jar',
         'apps/android/gradle/wrapper/gradle-wrapper.properties',
         'relay/worker/package.json', 'relay/worker/package-lock.json',
         'relay/worker/worker.mjs', 'docs/agent.md', 'docs/checks.md', 'docs/source-distribution.md', 'tools/ci/github-actions-check.yml.disabled',
         'release/retired-apks.json', 'tools/check.py', 'tools/package_source.py', 'tools/package_download.py', 'tools/source_manifest.py', ALLOWLIST}
IGNORED = {'__pycache__', 'node_modules', '.gradle', '.wrangler', 'build', '.pytest_cache'}


def required_paths(root: Path) -> set[str]:
    from package_source import Refused, safe_path
    required = set(FIXED)
    for tree in TREES:
        directory = root / tree
        if not directory.is_dir() or directory.is_symlink():
            raise Refused('missing_or_symlinked_source_tree: ' + tree)
        for path in directory.rglob('*'):
            relative = path.relative_to(root)
            if set(relative.parts) & IGNORED:
                continue
            if path.is_symlink():
                raise Refused('symlink_in_source_closure: ' + relative.as_posix())
            if path.is_file():
                required.add(safe_path(relative.as_posix()))
    for directory, suffixes in DIRECT_SOURCE.items():
        for path in (root / directory).iterdir():
            if path.suffix in suffixes:
                if path.is_symlink():
                    raise Refused('symlink_in_source_closure: ' + path.relative_to(root).as_posix())
                if path.is_file():
                    required.add(safe_path(path.relative_to(root).as_posix()))
    # Read the check entrypoint without importing or executing repository code.
    check = ast.parse((root / 'tools/check.py').read_text())
    expected = {'HOST_SCRIPTS': ('apps/android/scripts/', '.py'),
                'PROTOCOL_SCRIPTS': ('relay/service/tests/', '.mjs')}
    found = set()
    for statement in check.body:
        if not isinstance(statement, ast.Assign):
            continue
        for target in statement.targets:
            if isinstance(target, ast.Name) and target.id in expected:
                values = ast.literal_eval(statement.value)
                if not isinstance(values, (list, tuple)) or not all(isinstance(x, str) and re.fullmatch(r'[a-z0-9_-]+', x) for x in values):
                    raise Refused('unsupported_check_recipe')
                prefix, suffix = expected[target.id]
                required.update(prefix + value + suffix for value in values)
                found.add(target.id)
    if found != set(expected):
        raise Refused('missing_check_recipe')
    return required


def validate_closure(root: Path, config: dict) -> dict:
    from package_source import Refused, read_regular
    if config.get('closure_policy') != POLICY:
        raise Refused('source_closure_policy_required; refresh the current allowlist')
    entries = {entry['path']: entry for entry in config['files']}
    required = required_paths(root)
    missing = sorted(required - entries.keys())
    if missing:
        raise Refused('missing_source_dependencies: ' + json.dumps(missing, ensure_ascii=False))
    for name in sorted(required):
        read_regular(root, name)
        if name not in {'docs/checks.md', 'docs/source-distribution.md'} and entries[name]['kind'] not in {'source', 'binary'}:
            raise Refused('required_code_cannot_be_transformed: ' + name)
    return {'policy': POLICY, 'required_files': len(required), 'missing_files': [],
            'scope': 'Declared Android main/build, CLI/relay, release site, current check scripts/fixtures, and CI dependencies. Not a universal static dependency analysis.'}


def propose(root: Path, config: dict) -> dict:
    from package_source import Refused, read_regular, validate_entry
    entries = {entry['path']: entry for entry in config['files']}
    for name in sorted(required_paths(root) - entries.keys()):
        data = read_regular(root, name)
        try:
            data.decode('utf-8')
        except UnicodeError:
            raise Refused('new_binary_requires_explicit_review: ' + name) from None
        entry = {'path': name, 'kind': 'document' if name in {'docs/checks.md', 'docs/source-distribution.md'} else 'source'}
        validate_entry(entry)
        entries[name] = entry
    gradle = read_regular(root, 'apps/android/app/build.gradle').decode()
    versions = re.findall(r'\bversionName\s+["\']([^"\']+)', gradle)
    codes = re.findall(r'\bversionCode\s+(\d+)', gradle)
    cli = re.findall(r'^VERSION\s*=\s*["\']([^"\']+)["\']\s*$', read_regular(root, 'cli/airtap.py').decode(), re.M)
    if len(versions) != 1 or len(codes) != 1 or len(cli) != 1:
        raise Refused('ambiguous_source_version')
    version = versions[0] + '-code' + codes[0]
    result = {**config, 'version': version, 'android_version': versions[0], 'version_code': int(codes[0]),
              'cli_version': cli[0], 'allowlist_path': ALLOWLIST, 'closure_policy': POLICY,
              'archive_root': 'airtap-source-' + version, 'files': [entries[name] for name in sorted(entries)]}
    validate_closure(root, result)
    return result


def main():
    from package_source import Refused, encoded, read_regular, safe_path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate the active allowlist (the default)')
    parser.add_argument('--write-candidate', type=Path, help='Write a NEW proposal file for review; does not replace the active allowlist')
    args = parser.parse_args()
    config = json.loads(read_regular(ROOT, ALLOWLIST))
    if args.write_candidate:
        target = args.write_candidate.resolve()
        if target == (ROOT / ALLOWLIST).resolve() or target.exists() or target.is_symlink():
            raise Refused('candidate_must_be_new_and_separate_from_active_allowlist')
        result = propose(ROOT, config)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(encoded(result))
        target.chmod(0o600)
        print(json.dumps({'status': 'proposal_only_review_required', 'files': len(result['files']),
                          'version_code': result['version_code'], 'active_allowlist_changed': False}))
    else:
        print(json.dumps(validate_closure(ROOT, config), ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, SyntaxError) as failure:
        print(str(failure) if type(failure).__name__ == 'Refused' else 'source_manifest_failed_io_or_format')
        raise SystemExit(1)
