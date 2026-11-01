#!/usr/bin/env node
// Production modules in local workerd, with two synthetic device DOs and phone stubs.
// No cloud service, physical device, private configuration or user content is used.
// Feedback isolation/retention is covered separately by feedback-roundtrip.mjs.
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { readFile, writeFile, mkdtemp, mkdir, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
const option = key => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : undefined;
const root = fileURLToPath(new URL('../../../', import.meta.url));
const require = createRequire(new URL('../../worker/package.json', import.meta.url));
const { Miniflare, convertV4MiniflareOptions } = await import(pathToFileURL(option('--miniflare') ?? require.resolve('miniflare')).href);
const hex = n => randomBytes(n).toString('hex');
const digest = value => createHash('sha256').update(value).digest('hex');
const sources = Object.fromEntries(await Promise.all(['service/service.mjs', 'worker/worker.mjs', 'service/feedback.mjs'].map(async file => [file, await readFile(join(root, 'relay', file), 'utf8')])));
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-isolation-')));
const admin = hex(32), checks = [], sockets = [], phones = [];
let completed = false;
const bindings = { SERVICE_ID: hex(16), SERVICE_LABEL: 'Synthetic isolation test', IP_HASH_SALT: hex(32), REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true', FEEDBACK_ADMIN_SHA256: digest(admin), CLI_SHA256: 'a'.repeat(64), AGENT_SHA256: 'b'.repeat(64), ARTIFACT_VERSION: 'isolation-fixture', MAX_DEVICES: '10', DAILY_REGISTRATIONS: '10', IP_DAILY_REGISTRATIONS: '10' };
const options = { host: '127.0.0.1', port: 0, cf: false, durableObjectsPersist: join(temp, 'do'), resourcePersistencePath: join(temp, 'state'), workers: [{ name: 'isolation-test', modulesRoot: join(root, 'relay'),
  modules: Object.entries(sources).map(([file, contents]) => ({ type: 'ESModule', path: join(root, 'relay', file), contents })), compatibilityDate: '2026-10-01',
  durableObjects: { ADMISSION: { className: 'AdmissionRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
  ratelimits: { INGRESS: { namespace_id: '3487', simple: { limit: 10000, period: 60 } } },
  assets: { directory: join(temp, 'assets'), binding: 'ASSETS', run_worker_first: true, routerConfig: { has_user_worker: true } }, bindings }] };
let mf;
async function request(path, token, body, method) {
  const response = await mf.dispatchFetch('http://localhost' + path, { method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers: { 'CF-Connecting-IP': '127.0.0.1', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const text = await response.text();
  return { status: response.status, data: JSON.parse(text), text, headers: Object.fromEntries(response.headers) };
}
async function ok(path, token, body, method, status = 200) { const r = await request(path, token, body, method); assert.equal(r.status, status, r.text); return r.data; }
async function denied(path, token, body, status, error, method) {
  const before = dispatchCounts();
  const r = await request(path, token, body, method);
  assert.equal(r.status, status, r.text); assert.equal(r.data.error, error);
  assert.deepEqual(dispatchCounts(), before, 'denial must not dispatch a command or task fence');
  return r;
}
const dispatchCounts = () => phones.map(p => ({ commands: p.commands.length, fences: p.fences.length }));
const rpc = (name, args) => ({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'phone_' + name, arguments: args } });
const mcpText = data => JSON.parse(data.result.content.find(c => c.type === 'text').text);
function holdNext(p) { p.hold = true; return new Promise(resolve => { p.onCommand = resolve; }); }
function release(p, c) { p.send({ id: c.id, ok: true, result: p.result(c) }); p.hold = false; p.onCommand = null; }
async function check(name, run) {
  const started = performance.now();
  try { await run(); checks.push({ name, passed: true, elapsed_ms: Math.round(performance.now() - started) }); }
  catch (error) {
    // Aggregate JSON can be shared after review; raw stderr stays a local log.
    checks.push({ name, passed: false, error: String(error.message).replaceAll(root, '<repo>/').replaceAll(temp, '<temp>') });
    throw error;
  }
  finally { console.log(JSON.stringify(checks.at(-1))); }
}
const caps = ['multi_ai_v1', 'task_lease_v1'];
const approval = claim_id => ({ claim_id, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true });
const leaseBody = t => ({ task_id: t.task_id, lease_id: t.lease_id, lease_epoch: t.lease_epoch });
const command = (action, sequence, lease, extra = {}) => ({ id: randomUUID(), action, sequence, ...(lease ? { lease_id: lease.lease_id, lease_epoch: lease.lease_epoch } : {}), ...extra });
const withTimeout = async promise => { let timer; try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('fixture wait timeout')), 5000); })]); } finally { clearTimeout(timer); } };
async function enrolled(label) { const d = { id: hex(16), token: hex(32), ai: hex(32), label }; d.base = '/d/' + d.id; await ok('/v1/enroll', null, { device_id: d.id, device_token: d.token }); return d; }
async function phone(d, multi = false) {
  const r = await mf.dispatchFetch('http://localhost' + d.base + '/v1/device', { headers: { 'CF-Connecting-IP': '127.0.0.1', Authorization: 'Bearer ' + d.token, Upgrade: 'websocket' } });
  assert.equal(r.status, 101); const ws = r.webSocket; ws.accept(); sockets.push(ws);
  const p = { ws, commands: [], fences: [], hold: false, errors: [], closed: null }; phones.push(p);
  const image = Buffer.from('SYNTHETIC_IMAGE_' + d.label).toString('base64'), file = Buffer.from('SYNTHETIC_FILE_' + d.label).toString('base64');
  p.result = c => c.action === 'observe' ? { package: 'example.' + d.label.toLowerCase(), observation_id: hex(16), mime_type: 'image/jpeg', image_base64: image, expires_in_ms: 30000 } : c.action === 'file_read' ? { data_base64: file, offset: 0, eof: true } : { synthetic_device: d.label };
  p.send = value => ws.send(JSON.stringify(value));
  let hello; const ready = new Promise(resolve => { hello = resolve; });
  ws.addEventListener('close', event => { p.closed = event.reason; });
  ws.addEventListener('message', event => {
    try {
      const body = JSON.parse(event.data);
      if (body.type === 'hello_ack') { hello(); return; }
      if (body.type === 'task_fence') { p.fences.push(body); p.send({ type: 'task_fence_ack', operation_id: body.operation_id, lease_epoch: body.lease_epoch, ok: true }); return; }
      const c = body.command ?? body; p.commands.push(c); p.onCommand?.(c);
      if (!p.hold) p.send({ id: c.id, ok: true, result: p.result(c) });
    } catch (error) { p.errors.push(String(error)); }
  });
  ws.send(multi ? JSON.stringify({ type: 'hello', protocol_version: 4, capabilities: caps }) : 'hello');
  if (multi) await withTimeout(ready);
  return p;
}
async function websocketDenied(d, token) {
  const before = dispatchCounts();
  const r = await mf.dispatchFetch('http://localhost' + d.base + '/v1/device', { headers: { 'CF-Connecting-IP': '127.0.0.1', ...(token ? { Authorization: 'Bearer ' + token } : {}), Upgrade: 'websocket' } });
  assert.equal(r.status, 401); assert.equal((await r.json()).error, 'unauthorized'); assert.ok(!r.webSocket);
  assert.deepEqual(dispatchCounts(), before);
}
let a, b, pa, pb, ta, tb, a2;
try {
  await mkdir(join(temp, 'assets'));
  mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options);
  await mf.ready;
  await check('enrollment, ticket and claim cannot be substituted across devices', async () => {
    a = await enrolled('A'); b = await enrolled('B');
    await denied('/v1/enroll', null, { device_id: b.id, device_token: a.token }, 409, 'enrollment_conflict');
    const ia = await ok(a.base + '/v1/device/pairing', a.token, undefined, 'POST');
    const ib = await ok(b.base + '/v1/device/pairing', b.token, undefined, 'POST');
    await denied(b.base + '/v1/pair', null, { ticket: ia.ticket, controller_token: a.ai, name: 'Synthetic cross AI' }, 401, 'invalid_or_consumed_ticket');
    await denied(b.base + '/v1/pair', null, { ticket: ib.ticket, controller_token: b.token, name: 'Role confusion' }, 400, 'distinct_controller_token_required');
    a.claim = (await ok(a.base + '/v1/pair', null, { ticket: ia.ticket, controller_token: a.ai, name: 'Synthetic A' })).claim_id;
    b.claim = (await ok(b.base + '/v1/pair', null, { ticket: ib.ticket, controller_token: b.ai, name: 'Synthetic B' })).claim_id;
    await denied(b.base + '/v1/device/approve', b.token, approval(a.claim), 409, 'stale_claim');
    await denied(b.base + '/v1/status', b.ai, undefined, 403, 'phone_approval_required');
    await ok(a.base + '/v1/device/approve', a.token, approval(a.claim)); await ok(b.base + '/v1/device/approve', b.token, approval(b.claim));
    pa = await phone(a); pb = await phone(b);
  });
  await check('cross-device and wrong-role REST, MCP and WebSocket calls have zero command dispatch', async () => {
    for (const token of [undefined, a.ai, a.token, b.token, admin]) {
      await denied(b.base + '/v1/status', token, undefined, 401, 'unauthorized');
      await denied(b.base + '/v1/command', token, command('observe', 1), 401, 'unauthorized');
      await denied(b.base + '/mcp', token, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'phone_observe', arguments: { sequence: 1 } } }, 401, 'unauthorized');
    }
    for (const token of [undefined, a.token, a.ai, b.ai, admin]) { await denied(b.base + '/v1/device/pairing', token, undefined, 401, 'unauthorized'); await websocketDenied(b, token); }
    assert.equal(pa.commands.length, 0); assert.equal(pb.commands.length, 0);
    assert.equal((await ok(b.base + '/v1/status', b.ai)).command_sequence, 0);
  });
  await check('simultaneous same-ID images and MCP file responses stay with their authenticated devices', async () => {
    // Both DOs must hold an actual request before either phone returns. Reusing the
    // same UUID also proves pending/seen IDs are scoped to a device, not global.
    const sameId = randomUUID(), arrived = [holdNext(pa), holdNext(pb)];
    const pending = [a, b].map(d => request(d.base + '/v1/command', d.ai, { ...command('observe', 1), id: sameId }));
    const seen = await withTimeout(Promise.all(arrived));
    assert.deepEqual(seen.map(c => c.id), [sameId, sameId]);
    [pa, pb].forEach((p, i) => release(p, seen[i]));
    const responses = await withTimeout(Promise.all(pending));
    for (let i = 0; i < 2; i++) {
      const r = responses[i]; assert.equal(r.status, 200); assert.equal(r.data.id, sameId);
      assert.equal(Buffer.from(r.data.result.image_base64, 'base64').toString(), 'SYNTHETIC_IMAGE_' + [a, b][i].label);
      assert.match(r.headers['cache-control'], /no-store/);
    }
    const fileArrived = [holdNext(pa), holdNext(pb)];
    const files = [a, b].map(d => request(d.base + '/mcp', d.ai, rpc('file_read', {
      sequence: 2, root: 'workspace', path: 'synthetic.txt', offset: 0, length: 4096, version: 'f'.repeat(64),
    })));
    const fileCommands = await withTimeout(Promise.all(fileArrived));
    assert.deepEqual(fileCommands.map(c => c.action), ['file_read', 'file_read']);
    [pa, pb].forEach((p, i) => release(p, fileCommands[i]));
    const chunks = await withTimeout(Promise.all(files));
    for (let i = 0; i < 2; i++) {
      const r = chunks[i]; assert.equal(r.status, 200); assert.equal(r.data.result.isError, false);
      const resource = r.data.result.content.find(c => c.type === 'resource').resource;
      assert.equal(Buffer.from(resource.blob, 'base64').toString(), 'SYNTHETIC_FILE_' + [a, b][i].label);
      assert.equal(mcpText(r.data).result.data_base64, undefined);
    }
    // These positive replies synchronize both socket queues after the denials.
    for (const p of [pa, pb]) assert.deepEqual(p.commands.map(c => c.action), ['observe', 'file_read']);
  });
  await check('a device socket cannot answer another device pending request even with its exact request ID', async () => {
    const arrived = holdNext(pa); let settled = false;
    const pending = request(a.base + '/v1/command', a.ai, command('observe', 3)).then(r => { settled = true; return r; });
    const c = await withTimeout(arrived);
    const closed = new Promise(resolve => pb.ws.addEventListener('close', resolve, { once: true }));
    pb.send({ id: c.id, ok: true, result: { image_base64: Buffer.from('SYNTHETIC_FORGED_B').toString('base64') } });
    await withTimeout(closed);
    const bstatus = await ok(b.base + '/v1/status', b.ai); assert.equal(bstatus.deviceConnected, false);
    assert.equal(settled, false, 'foreign socket must not complete A pending request');
    release(pa, c);
    const r = await withTimeout(pending); assert.equal(r.status, 200); assert.equal(Buffer.from(r.data.result.image_base64, 'base64').toString(), 'SYNTHETIC_IMAGE_A');
    pa.hold = false; pa.onCommand = null;
  });
  await check('multi leases, claims and tokens cannot cross device or controller domains', async () => {
    for (const d of [a, b]) await ok(d.base + '/v1/device/multi/enable', d.token, { capabilities: caps, expected_claim_id: d.claim });
    pa = await phone(a, true); pb = await phone(b, true);
    ta = await ok(a.base + '/v1/tasks/acquire', a.ai, { task_id: hex(16) }); tb = await ok(b.base + '/v1/tasks/acquire', b.ai, { task_id: hex(16) });
    const before = (await ok(b.base + '/v1/status', b.ai)).command_sequence;
    const counts = dispatchCounts();
    for (const token of [undefined, a.ai, a.token, b.token, admin]) {
      for (const [operation, body] of [['acquire', { task_id: hex(16) }],
        ['renew', { ...leaseBody(tb), renewal_id: hex(16), renewal_sequence: 1 }], ['release', leaseBody(tb)]]) {
        await denied(b.base + '/v1/tasks/' + operation, token, body, 401, 'unauthorized');
      }
    }
    await denied(b.base + '/v1/tasks/renew', b.ai, { ...leaseBody(ta), renewal_id: hex(16), renewal_sequence: 1 }, 409, 'lease_mismatch');
    await denied(b.base + '/v1/tasks/release', b.ai, leaseBody(ta), 409, 'lease_mismatch');
    await denied(b.base + '/v1/command', b.ai, command('observe', before + 1, ta), 409, 'lease_mismatch');
    const mcp = await ok(b.base + '/mcp', b.ai, rpc('observe', { sequence: before + 1, lease_id: ta.lease_id, lease_epoch: ta.lease_epoch }));
    assert.equal(mcp.result.isError, true); assert.equal(mcpText(mcp).error, 'lease_mismatch');
    assert.equal(mcpText(mcp).admitted, false);
    await denied(b.base + '/v1/command', b.ai, command('observe', before + 1, tb, { claim_id: a.claim }), 400, 'invalid_command');
    assert.equal(pb.commands.length, 0); assert.equal((await ok(b.base + '/v1/status', b.ai)).command_sequence, before);
    const bgrants = await ok(b.base + '/v1/device/grants', b.token);
    await ok(b.base + '/v1/device/grants/revoke', b.token, { claim_id: a.claim });
    assert.deepEqual(await ok(b.base + '/v1/device/grants', b.token), bgrants);
    assert.deepEqual(dispatchCounts(), counts, 'foreign task and claim identities must not issue a fence');
    await ok(a.base + '/v1/command', a.ai, command('observe', 4, ta)); await ok(b.base + '/v1/command', b.ai, command('observe', 3, tb));
    assert.deepEqual(pa.commands.map(c => c.sequence), [4]); assert.deepEqual(pb.commands.map(c => c.sequence), [3]);
    assert.deepEqual(dispatchCounts().map(p => p.fences), counts.map(p => p.fences));
  });
  await check('same-device second approved AI sees busy metadata but cannot use another AI lease', async () => {
    const invite = { invitation_id: hex(16), invitation_sequence: 1, ticket: hex(16) }; a2 = hex(32);
    await ok(a.base + '/v1/device/invitations', a.token, invite);
    const claim = (await ok(a.base + '/v1/pair', null, { ticket: invite.ticket, controller_token: a2, name: 'Synthetic A2', capabilities: ['task_lease_v1'] })).claim_id;
    await ok(a.base + '/v1/device/grants/approve', a.token, approval(claim));
    const status = await ok(a.base + '/v1/status', a2); assert.equal(status.task.state, 'busy');
    for (const key of ['task_id', 'lease_id', 'lease_epoch', 'expires_at_ms', 'renewal_sequence']) assert.equal(status.task[key], null);
    const count = pa.commands.length, expected = dispatchCounts(), phoneIndex = phones.indexOf(pa);
    await denied(a.base + '/v1/command', a2, command('observe', 1, ta), 409, 'lease_mismatch');
    assert.equal(pa.commands.length, count); assert.equal((await ok(a.base + '/v1/status', a2)).command_sequence, 0);
    // Drain A's socket with its existing holder before any legitimate handoff
    // fences. Releases can resend the same idempotent fence; they are not denials.
    await ok(a.base + '/v1/command', a.ai, command('observe', 5, ta));
    expected[phoneIndex].commands++;
    assert.deepEqual(dispatchCounts(), expected);
    await ok(a.base + '/v1/tasks/release', a.ai, leaseBody(ta));
    const own = await ok(a.base + '/v1/tasks/acquire', a2, { task_id: hex(16) });
    const view = await ok(a.base + '/mcp', a2, rpc('observe', { sequence: 1, lease_id: own.lease_id, lease_epoch: own.lease_epoch }));
    assert.equal(view.result.isError, false);
    assert.equal(Buffer.from(view.result.content.find(c => c.type === 'image').data, 'base64').toString(), 'SYNTHETIC_IMAGE_A');
    assert.equal(mcpText(view).result.image_base64, undefined);
    assert.deepEqual(pa.commands.map(c => c.sequence), [4, 5, 1]);
    // This positive case documents shared phone pixels after lawful handoff.
    await ok(a.base + '/v1/tasks/release', a2, leaseBody(own));
  });
  for (const p of phones) assert.deepEqual(p.errors, []);
  assert.equal(checks.length, 6);
  completed = true;
} finally {
  const output = { ok: completed, scope: 'Local production workerd with synthetic devices and phone stubs; not Android, personal-AI or public-service acceptance.',
    passed: checks.filter(c => c.passed).length, total: checks.length, checks,
    source_sha256: Object.fromEntries(Object.entries(sources).map(([file, source]) => ['relay/' + file, digest(source)])) };
  try {
    if (option('--output')) await writeFile(option('--output'), JSON.stringify(output, null, 2) + '\n');
    console.log(JSON.stringify({ ok: output.ok, passed: output.passed, total: output.total }));
  } finally {
    for (const ws of sockets) try { ws.close(1000, 'test complete'); } catch {}
    try { await mf?.dispose(); }
    finally { await rm(temp, { recursive: true, force: true }); }
  }
}
