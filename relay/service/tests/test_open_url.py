#!/usr/bin/env python3
"""Run one URL contract against the production Python, JS and Java validators."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
CASES = json.loads((Path(__file__).parent / 'fixtures/open-url-cases.json').read_text())
SPEC = importlib.util.spec_from_file_location('airtap_cli', ROOT / 'cli/airtap.py')
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class OpenUrlContract(unittest.TestCase):
    def test_phone_cli_and_relay_agree_and_preserve_original_url(self):
        # Java runs the actual Android-independent BrowserUrl class, not a port of it.
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        self.assertTrue(javac and java, 'Set JAVA_HOME to a JDK')
        harness = '''package io.airtap.preview;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public class BrowserUrlContract {
  public static void main(String[] args) throws Exception {
    BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = input.readLine()) != null) {
      String url = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
      try { System.out.println("ok:" + Base64.getEncoder().encodeToString(BrowserUrl.validate(url).getBytes(StandardCharsets.UTF_8))); }
      catch (IllegalArgumentException rejected) { System.out.println("invalid"); }
    }
  }
}'''
        with tempfile.TemporaryDirectory(prefix='airtap-url-contract-') as directory:
            path = Path(directory)
            (path / 'BrowserUrlContract.java').write_text(harness)
            subprocess.run([javac, '-d', directory, str(ROOT / 'apps/android/app/src/main/java/io/airtap/preview/BrowserUrl.java'),
                            str(path / 'BrowserUrlContract.java')], check=True, capture_output=True, text=True)
            result = subprocess.run([java, '-cp', directory, 'io.airtap.preview.BrowserUrlContract'],
                                    input=''.join(base64.b64encode(case['url'].encode()).decode() + '\n' for case in CASES),
                                    check=True, capture_output=True, text=True)
        java_results = result.stdout.splitlines()
        # Execute the function verbatim from the Worker; Cloudflare's DurableObject import
        # is unrelated to this pure validator and is unavailable in the host Node runtime.
        script = '''import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
const source = readFileSync(process.argv[1], 'utf8');
const begin = source.indexOf('function validOpenUrl('), end = source.indexOf('function commandShape(', begin);
if (begin < 0 || end < 0) throw new Error('Production URL validator not found');
const validate = runInNewContext('(' + source.slice(begin, end) + ')', { URL });
const cases = JSON.parse(readFileSync(0, 'utf8'));
process.stdout.write(JSON.stringify(cases.map(value => validate(value.url))));'''
        result = subprocess.run([shutil.which('node') or 'node', '--input-type=module', '-e', script,
                                 str(ROOT / 'relay/worker/worker.mjs')], input=json.dumps(CASES),
                                check=True, capture_output=True, text=True)
        js_results = json.loads(result.stdout)
        self.assertEqual(len(java_results), len(CASES))
        self.assertEqual(len(js_results), len(CASES))
        for case, java_result, js_result in zip(CASES, java_results, js_results):
            with self.subTest(case=case['name']):
                try:
                    value = CLI.open_url(case['url'])
                    py_result = True
                    self.assertEqual(value, case['url'])
                except CLI.Failure:
                    py_result = False
                self.assertEqual(py_result, case['valid'], 'Python')
                self.assertEqual(js_result, case['valid'], 'Worker')
                self.assertEqual(java_result != 'invalid', case['valid'], 'Android Java')
                if case['valid']:
                    self.assertEqual(base64.b64decode(java_result[3:]).decode(), case['url'])


if __name__ == '__main__':
    unittest.main()
