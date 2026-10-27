#!/usr/bin/env node
// Actual v4 routing and Durable Objects, synthetic identities; no private devices.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
const option = key => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : undefined;
const requireWorker = createRequire(new URL('../../worker/package.json', import.meta.url));
const { Miniflare, convertV4MiniflareOptions } = await import(pathToFileURL(option('--miniflare') ?? requireWorker.resolve('miniflare')).href);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const temp = await mkdtemp(join(tmpdir(), 'airtap-invitation-artifacts-'));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const hex = length => randomBytes(length).toString('hex');
const cli = await readFile(join(root, 'cli/airtap.py')), guide = await readFile(join(root, 'docs/agent.md'));
await mkdir(join(temp, 'assets/cli'), { recursive: true });
await writeFile(join(temp, 'assets/cli/airtap.py'), cli);
await writeFile(join(temp, 'assets/agent.md'), guide);
const modules = await Promise.all(['service/service.mjs', 'worker/worker.mjs', 'service/feedback.mjs'].map(async path => ({
  type: 'ESModule', path: join(root, 'relay', path), contents: await readFile(join(root, 'relay', path), 'utf8'),
})));
let checks = 0;
try {
  for (const [label, artifacts] of [
    ['valid', { CLI_SHA256: digest(cli), AGENT_SHA256: digest(guide) }],
    ['missing', {}],
    ['malformed', { CLI_SHA256: digest(cli), AGENT_SHA256: 'a'.repeat(64) + '\n' }],
  ]) {
    const options = { host: '127.0.0.1', port: 0, cf: false, workers: [{ name: 'invitation-' + label,
      modulesRoot: join(root, 'relay'), modules, compatibilityDate: '2026-10-01',
      durableObjects: { ADMISSION: { className: 'AdmissionRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
      ratelimits: { INGRESS: { namespace_id: '2412', simple: { limit: 10000, period: 60 } } },
      assets: { directory: join(temp, 'assets'), binding: 'ASSETS', run_worker_first: true, routerConfig: { has_user_worker: true } },
      bindings: { SERVICE_ID: hex(16), SERVICE_LABEL: 'Invitation integrity test', IP_HASH_SALT: hex(32),
        REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true', ARTIFACT_VERSION: 'invitation-test', ...artifacts },
    }] };
    const mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options);
    try {
      const device = { device_id: hex(16), device_token: hex(32) };
      const call = (path, method = 'GET', token, body) => mf.dispatchFetch('http://localhost' + path, {
        method, headers: { 'CF-Connecting-IP': '127.0.0.1', ...(token ? { Authorization: 'Bearer ' + token } : {}),
          ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}),
      });
      assert.equal((await call('/v1/enroll', 'POST', null, device)).status, 200);
      const path = '/d/' + device.device_id + '/v1/device/pairing';
      assert.equal((await (await call(path, 'GET', device.device_token)).json()).state, 'idle');
      assert.equal((await call(path, 'POST', hex(32))).status, 401);
      const response = await call(path, 'POST', device.device_token);
      if (label === 'valid') {
        assert.equal(response.status, 200);
        const invitation = await response.json();
        assert.equal(invitation.protocol_version, 4);
        assert.match(invitation.ticket, /^[a-f0-9]{32}$/);
        for (const [suffix, key, expected] of [['/cli/airtap.py', 'cli_sha256', cli], ['/agent.md', 'agent_sha256', guide]]) {
          const download = await call('/d/' + device.device_id + suffix);
          assert.equal(download.status, 200);
          assert.equal(invitation[key], digest(expected));
          assert.equal(digest(Buffer.from(await download.arrayBuffer())), invitation[key]);
        }
        assert.equal((await (await call(path, 'GET', device.device_token)).json()).state, 'waiting');
      } else {
        assert.equal(response.status, 503);
        assert.deepEqual(await response.json(), { protocol_version: 4, error: 'artifact_metadata_unavailable' });
        assert.equal((await (await call(path, 'GET', device.device_token)).json()).state, 'idle', 'invalid artifact metadata must not mint a ticket');
      }
      checks++;
      console.log(JSON.stringify({ name: label, ok: true }));
    } finally { await mf.dispose(); }
  }
  console.log(JSON.stringify({ ok: true, checks, scope: 'Actual v4 service routing and DOs, no phone or personal AI acceptance' }));
} finally { await rm(temp, { recursive: true, force: true }); }
