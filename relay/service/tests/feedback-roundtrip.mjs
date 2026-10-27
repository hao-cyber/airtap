#!/usr/bin/env node
// Real workerd + production service/storage modules. Test-only subclass enables a
// storage clock, failure injection and inspection; none are exported by deployment.
import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { readFile, writeFile, mkdtemp, mkdir, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
const option = key => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : undefined;
const requireWorker = createRequire(new URL('../../worker/package.json', import.meta.url));
const modulePath = option('--miniflare') ?? requireWorker.resolve('miniflare');
const { Miniflare, convertV4MiniflareOptions, Log, LogLevel } = await import(pathToFileURL(modulePath).href);
const WebSocket = createRequire(modulePath)('ws');
const root = fileURLToPath(new URL('../../../', import.meta.url));
const hex = bytes => randomBytes(bytes).toString('hex');
const digest = value => createHash('sha256').update(value).digest('hex');
const sources = Object.fromEntries(await Promise.all(['relay/service/service.mjs', 'relay/service/feedback.mjs', 'relay/worker/worker.mjs'].map(async path => [path, await readFile(join(root, path), 'utf8')])));
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-feedback-')));
const adminToken = hex(32), ownerA = { id: hex(16), token: hex(32) }, ownerB = { id: hex(16), token: hex(32) };
const logs = [], checks = [], secrets = [adminToken, ownerA.token, ownerB.token];
class MemoryLog extends Log { log(message) { logs.push(String(message)); } }
const testEntry = `import service, { AdmissionRegistry, ServiceDeviceRelay } from './service/service.mjs';
import { FeedbackStore } from './service/feedback.mjs';
export { ServiceDeviceRelay };
export class TestAdmissionRegistry extends AdmissionRegistry {
  async test(body) {
    if (body.operation === 'time') this.feedbackStore = new FeedbackStore(this.ctx.storage, () => body.time, this.env.IP_HASH_SALT);
    if (body.operation === 'fail') this.failFeedback = body.value;
    if (body.operation === 'alarm') await super.alarm();
    if (body.operation === 'hold') this.holdFeedback = true;
    if (body.operation === 'release') { this.holdFeedback = false; for (const done of this.waiters ?? []) done(); this.waiters = []; }
    return { waiting: this.waiters?.length ?? 0, rows: Object.fromEntries(await this.ctx.storage.list({prefix: 'feedback:'})), alarm: await this.ctx.storage.getAlarm() };
  }
  async feedbackOperation(actor, command) { if (this.failFeedback) throw new Error('test feedback storage unavailable'); if (this.holdFeedback) await new Promise(resolve => { (this.waiters ??= []).push(resolve); }); return super.feedbackOperation(actor, command); }
}
export default { async fetch(request, env) {
  if (new URL(request.url).pathname === '/_test') return Response.json(await env.ADMISSION.get(env.ADMISSION.idFromName('service')).test(await request.json()));
  return service.fetch(request, env);
} };`;
const options = { host: '127.0.0.1', port: 0, cf: false, resourcePersistencePath: join(temp, 'state'), log: new MemoryLog(LogLevel.VERBOSE),
  workers: [{ name: 'airtap-feedback-test', modulesRoot: join(root, 'relay'), modules: [
    { type: 'ESModule', path: join(root, 'relay/test-entry.mjs'), contents: testEntry },
    ...Object.entries(sources).map(([path, contents]) => ({ type: 'ESModule', path: join(root, path), contents })),
  ], compatibilityDate: '2026-10-01',
  durableObjects: { ADMISSION: { className: 'TestAdmissionRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
  ratelimits: { INGRESS: { namespace_id: '2401', simple: { limit: 10000, period: 60 } } },
  bindings: { SERVICE_ID: hex(16), SERVICE_LABEL: 'Local feedback test', IP_HASH_SALT: hex(32), REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true', FEEDBACK_ADMIN_SHA256: digest(adminToken), CLI_SHA256: 'a'.repeat(64), AGENT_SHA256: 'b'.repeat(64) },
}] };
let mf, origin, phone;
async function start() { mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options); origin = String(await mf.ready).replace(/\/$/, ''); }
async function http(path, { token, body, method, headers = {}, raw, ip = '127.0.0.1' } = {}) {
  const response = await fetch(origin + path, { method: method ?? (body !== undefined || raw !== undefined ? 'POST' : 'GET'), headers: {
    Connection: 'close', 'CF-Connecting-IP': ip, ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body !== undefined || raw !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers },
    ...(body !== undefined || raw !== undefined ? { body: raw ?? JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10000) });
  const text = await response.text(); let data; try { data = JSON.parse(text); } catch { data = null; }
  return { status: response.status, data, text, headers: response.headers };
}
async function test(body) { return (await http('/_test', { body })).data; }
async function check(name, fn) { const started = performance.now(); await fn(); checks.push({ name, ok: true, elapsed_ms: Math.round(performance.now() - started) }); console.log(JSON.stringify(checks.at(-1))); }
const base = owner => '/d/' + owner.id + '/v1';
const report = (extra = {}) => ({ report_id: randomUUID(), title: 'Task recovery problem', description: 'Synthetic redacted feedback only.', phase: 'observe', screen_state: 'screen_off', ...extra });
async function pair(owner, approve = true) {
  const invitation = await http(base(owner) + '/device/pairing', { token: owner.token, method: 'POST' }); assert.equal(invitation.status, 200);
  const token = hex(32); secrets.push(token);
  const paired = await http(base(owner) + '/pair', { body: { ticket: invitation.data.ticket, controller_token: token, name: 'Feedback test agent' } }); assert.equal(paired.status, 200);
  if (approve) assert.equal((await http(base(owner) + '/device/approve', { token: owner.token, body: { claim_id: paired.data.claim_id, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true } })).status, 200);
  return token;
}
async function connect(owner) {
  const ws = new WebSocket(origin.replace('http:', 'ws:') + base(owner) + '/device', { headers: { Authorization: 'Bearer ' + owner.token, 'CF-Connecting-IP': '127.0.0.1' } });
  ws.on('message', raw => { const command = JSON.parse(raw); ws.send(JSON.stringify({ id: command.id, ok: true, result: { authorized: true, persistent: true, all_apps: true } })); });
  await new Promise((resolve, reject) => { ws.once('open', () => { ws.send('hello'); resolve(); }); ws.once('error', reject); }); return ws;
}
let aiA, aiB, first, firstReceipt, deletedId, anonymous, anonymousToken, time;
try {
  await start();
  await check('disabled and malformed administrator configuration do not expose feedback capability or routes', async () => {
    const good = options.workers[0].bindings.FEEDBACK_ADMIN_SHA256;
    for (const value of ['', 'not-a-digest', good + '\n']) {
      await mf.dispose(); options.workers[0].bindings.FEEDBACK_ADMIN_SHA256 = value; await start();
      assert.equal((await http('/v1/service')).data.capabilities.includes('feedback_v1'), false);
      assert.equal((await http('/v1/reports', { body: report() })).status, 404);
      assert.equal((await http('/v1/admin/reports/query', { token: adminToken, body: {} })).status, 404);
    }
    await mf.dispose(); options.workers[0].bindings.FEEDBACK_ADMIN_SHA256 = good; await start();
    assert.equal((await http('/v1/service')).data.capabilities.includes('feedback_v1'), true);
    for (const owner of [ownerA, ownerB]) assert.equal((await http('/v1/enroll', { body: { device_id: owner.id, device_token: owner.token } })).status, 200);
    aiA = await pair(ownerA, false); aiB = await pair(ownerB);
  });
  await check('authentication roles, pending denial and unknown body fields never consume report write quota', async () => {
    for (const token of [undefined, hex(32), ownerA.token, adminToken]) assert.equal((await http(base(ownerA) + '/reports', { token, body: report() })).status, 401);
    assert.equal((await http(base(ownerA) + '/reports', { token: aiA, body: report() })).status, 403);
    for (const token of [undefined, aiA, ownerA.token]) assert.equal((await http('/v1/admin/reports/query', { token, body: {} })).status, 401);
    assert.equal((await http(base(ownerA) + '/device/reports', { token: aiA })).status, 401);
    const candidate = (await http(base(ownerA) + '/device/pairing', { token: ownerA.token })).data.candidate;
    assert.equal((await http(base(ownerA) + '/device/approve', { token: ownerA.token, body: { claim_id: candidate.claim_id, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true } })).status, 200);
    const before = (await test({ operation: 'inspect' })).rows['feedback:rates'];
    for (const extra of [{ screenshot: 'NO' }, { token: 'NO' }, { headers: {} }, { report_id: 'bad' }, { report_id: [randomUUID()] }, { report_id: randomUUID() + '\n' }, { screen_state: 'bad' }, { description: '\u0000' }, { steps: [] }, { agent: {} }]) {
      const response = await http(base(ownerA) + '/reports', { token: aiA, body: report(extra) }); assert.equal(response.status, 400); assert.equal(response.data.error, 'invalid_report');
    }
    assert.deepEqual((await test({ operation: 'inspect' })).rows['feedback:rates'], before);
  });
  await check('creation returns only receipt; idempotency, same-scope deduplication and private details persist', async () => {
    first = report({ description: 'PRIVATE_BODY_CANARY_1', expected: 'Screen wakes', actual: 'No new observation', steps: '1. Send observe\n2. Check state', app_version: '0.4.0-beta.4', agent: 'Example agent / work / 1.0' });
    const created = await http(base(ownerA) + '/reports', { token: aiA, body: first }); assert.equal(created.status, 201); firstReceipt = created.data;
    assert.equal(created.text.includes(first.description), false); assert.equal(created.text.includes(first.title), false);
    assert.equal(firstReceipt.status, 'new'); assert.equal(firstReceipt.expires_at - firstReceipt.created_at, 30 * 86400); time = firstReceipt.created_at;
    const retry = await http(base(ownerA) + '/reports', { token: aiA, body: first }); assert.equal(retry.status, 200); assert.equal(retry.data.replayed, true);
    const conflict = await http(base(ownerA) + '/reports', { token: aiA, body: { ...first, description: 'different' } }); assert.equal(conflict.status, 409); assert.equal(conflict.text.includes('different'), false);
    const duplicate = await http(base(ownerA) + '/reports', { token: aiA, body: { ...first, report_id: randomUUID() } }); assert.equal(duplicate.data.duplicate_of, first.report_id);
    const cross = await http(base(ownerB) + '/reports', { token: aiB, body: { ...first, report_id: randomUUID() } }); assert.equal(cross.data.duplicate_of, null);
    const detail = await http(base(ownerA) + '/reports/' + first.report_id, { token: aiA }); assert.equal(detail.data.report.description, first.description); assert.equal(detail.data.report.receipt_hash, undefined);
    for (const path of [base(ownerB) + '/reports/', base(ownerB) + '/device/reports/']) assert.equal((await http(path + first.report_id, { token: path.includes('/device/') ? ownerB.token : aiB })).status, 404);
    assert.equal((await http(base(ownerA) + '/reports/' + first.report_id, { token: aiB })).status, 401);
  });
  await check('pagination cursors bind authorization domain and filters; lists never include body', async () => {
    const one = await http(base(ownerA) + '/reports?limit=1', { token: aiA }); assert.equal(one.status, 200); assert.equal(one.data.reports.length, 1); assert.ok(one.data.next_cursor); assert.equal(one.text.includes(first.description), false);
    const next = await http(base(ownerA) + '/reports?limit=1&cursor=' + one.data.next_cursor, { token: aiA }); assert.equal(next.status, 200); assert.notEqual(next.data.reports[0].report_id, one.data.reports[0].report_id);
    assert.equal((await http(base(ownerB) + '/reports?cursor=' + one.data.next_cursor, { token: aiB })).status, 400);
    assert.equal((await http(base(ownerA) + '/device/reports?cursor=' + one.data.next_cursor, { token: ownerA.token })).status, 400);
    for (const query of ['limit=0', 'limit=51', 'cursor=bad', 'limit=1&limit=2', 'unknown=x']) assert.equal((await http(base(ownerA) + '/reports?' + query, { token: aiA })).status, 400);
    const adminPage = await http('/v1/admin/reports/query', { token: adminToken, body: { limit: 1 } }); assert.ok(adminPage.data.next_cursor);
    assert.equal((await http('/v1/admin/reports/query', { token: adminToken, body: { cursor: adminPage.data.next_cursor, status: 'new' } })).status, 400);
  });
  await check('supplements and administrator responses are private, bounded and owner-readable', async () => {
    assert.equal((await http(base(ownerA) + '/reports/' + first.report_id, { token: aiA, method: 'PATCH', body: { supplement: 'PRIVATE_SUPPLEMENT_1' } })).status, 200);
    const updated = await http('/v1/admin/reports/' + first.report_id, { token: adminToken, method: 'PATCH', body: { status: 'needs_info', maintenance_reply: 'PRIVATE_MAINTENANCE_REPLY' } }); assert.equal(updated.status, 200);
    const owner = (await http(base(ownerA) + '/device/reports/' + first.report_id, { token: ownerA.token })).data.report;
    assert.equal(owner.status, 'needs_info'); assert.equal(owner.maintenance_reply, 'PRIVATE_MAINTENANCE_REPLY'); assert.equal(owner.supplements[0].text, 'PRIVATE_SUPPLEMENT_1');
    const admin = (await http('/v1/admin/reports/' + first.report_id, { token: adminToken })).data.report; assert.equal(admin.device_id, ownerA.id); assert.ok(admin.claim_id);
    assert.equal((await http(base(ownerA) + '/reports/' + first.report_id, { token: aiA, method: 'PATCH', body: { status: 'resolved' } })).status, 400);
    assert.equal((await http('/v1/admin/reports/' + first.report_id, { token: adminToken, method: 'PATCH', body: { maintenance_reply: 'a'.repeat(4001) } })).status, 400);
    assert.equal((await http('/v1/admin/reports/' + first.report_id, { token: adminToken, method: 'PATCH', body: { status: 'invented' } })).status, 400);
    for (let i = 1; i < 5; i++) assert.equal((await http(base(ownerA) + '/reports/' + first.report_id, { token: aiA, method: 'PATCH', body: { supplement: 'more' + i } })).status, 200);
    assert.equal((await http(base(ownerA) + '/reports/' + first.report_id, { token: aiA, method: 'PATCH', body: { supplement: 'too many' } })).status, 429);
  });
  await check('deletion erases body and indexes; idempotent retries cannot resurrect original content', async () => {
    const submitted = report({ description: 'DELETED_BODY_CANARY' }); deletedId = submitted.report_id;
    assert.equal((await http(base(ownerA) + '/reports', { token: aiA, body: submitted })).status, 201);
    for (let i = 0; i < 2; i++) assert.equal((await http(base(ownerA) + '/device/reports/' + deletedId, { token: ownerA.token, method: 'DELETE' })).status, 200);
    assert.equal((await http(base(ownerA) + '/reports/' + deletedId, { token: aiA })).status, 410);
    assert.equal((await http(base(ownerA) + '/reports', { token: aiA, body: submitted })).data.error, 'report_deleted');
    const stored = (await test({ operation: 'inspect' })).rows; assert.equal(JSON.stringify(stored).includes(submitted.description), false);
    assert.equal(stored['feedback:report:' + deletedId].deleted, true); assert.equal(stored['feedback:list:' + deletedId], undefined);
  });
  await check('revocation denies all old AI feedback operations while owner retains management across new claim', async () => {
    await test({ operation: 'hold' });
    const inFlight = http(base(ownerA) + '/reports/' + first.report_id, { token: aiA });
    for (let i = 0; i < 100 && !(await test({ operation: 'inspect' })).waiting; i++) await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal((await test({ operation: 'inspect' })).waiting, 1);
    assert.equal((await http(base(ownerA) + '/device/revoke', { token: ownerA.token, method: 'POST' })).status, 200);
    await test({ operation: 'release' });
    const revokedResult = await inFlight; assert.equal(revokedResult.status, 410); assert.equal(revokedResult.text.includes(first.description), false);
    for (const [suffix, method, body] of [['', 'GET'], ['', 'POST', report()], ['/' + first.report_id, 'GET'], ['/' + first.report_id, 'DELETE'], ['/' + first.report_id, 'PATCH', { supplement: 'no' }]]) assert.equal((await http(base(ownerA) + '/reports' + suffix, { token: aiA, method, body })).status, 410);
    assert.equal((await http(base(ownerA) + '/device/reports/' + first.report_id, { token: ownerA.token })).status, 200);
    const old = aiA; aiA = await pair(ownerA);
    assert.equal((await http(base(ownerA) + '/reports', { token: old })).status, 401);
    assert.equal((await http(base(ownerA) + '/reports/' + first.report_id, { token: aiA })).status, 404);
    assert.deepEqual((await http(base(ownerA) + '/reports', { token: aiA })).data.reports, []);
    const fresh = await http(base(ownerA) + '/reports', { token: aiA, body: { ...first, report_id: randomUUID() } }); assert.equal(fresh.data.duplicate_of, null);
    assert.equal((await http(base(ownerA) + '/device/reports/' + first.report_id, { token: ownerA.token, method: 'DELETE' })).status, 200);
  });
  await check('anonymous receipts support lost-response retry but cannot enumerate or cross-authorize', async () => {
    anonymous = report({ description: 'ANONYMOUS_BODY_CANARY', phase: 'pair' }); anonymousToken = hex(32); secrets.push(anonymousToken);
    const submitted = await http('/v1/reports', { token: anonymousToken, body: anonymous }); assert.equal(submitted.status, 201); assert.equal(submitted.data.access_token, anonymousToken);
    const repeat = await http('/v1/reports', { token: anonymousToken, body: anonymous }); assert.equal(repeat.status, 200); assert.equal(repeat.data.replayed, true);
    assert.equal((await http('/v1/reports', { body: anonymous })).status, 409);
    for (const token of [undefined, hex(32), aiA, ownerA.token, adminToken]) assert.equal((await http('/v1/reports/' + anonymous.report_id, { token })).status, token ? 404 : 401);
    assert.equal((await http('/v1/reports/' + anonymous.report_id, { token: anonymousToken })).data.report.description, anonymous.description);
    assert.equal((await http('/v1/reports', { token: anonymousToken })).status, 404);
    assert.equal((await http('/v1/admin/reports/query', { token: anonymousToken, body: {} })).status, 401);
    const generated = await http('/v1/reports', { body: report() }); assert.equal(generated.status, 201); assert.match(generated.data.access_token, /^[a-f0-9]{64}$/); secrets.push(generated.data.access_token);
    assert.equal((await http('/v1/admin/reports/' + generated.data.report_id, { token: adminToken, method: 'DELETE' })).status, 200);
    assert.equal((await http('/v1/reports/' + generated.data.report_id, { token: generated.data.access_token })).status, 410);
    const disposable = report({ description: 'ANONYMOUS_DELETED_CANARY' }), disposableToken = hex(32); secrets.push(disposableToken);
    assert.equal((await http('/v1/reports', { token: disposableToken, body: disposable })).status, 201);
    assert.equal((await http('/v1/reports/' + disposable.report_id, { token: disposableToken, method: 'DELETE' })).status, 200);
    assert.equal((await http('/v1/reports', { token: disposableToken, body: disposable })).data.error, 'report_deleted');
    const stored = JSON.stringify((await test({ operation: 'inspect' })).rows); for (const secret of secrets) assert.equal(stored.includes(secret), false);
    assert.equal(stored.includes(disposable.description), false);
    assert.equal(stored.includes('127.0.0.1'), false);
  });
  await check('origin, JSON size, malformed text and disallowed methods give static errors without input reflection', async () => {
    const target = base(ownerA) + '/reports';
    assert.equal((await http(target, { token: aiA, body: report(), headers: { Origin: 'https://other.invalid' } })).status, 403);
    assert.equal((await http('/v1/reports', { body: report(), headers: { Origin: 'null' } })).status, 403);
    assert.equal((await http(target, { token: aiA, body: report(), headers: { Origin: origin } })).status, 201);
    for (const raw of ['{malformed_PRIVATE_CANARY', '"' + 'x'.repeat(25000) + '"', Buffer.from([0xff])]) {
      const result = await http(target, { token: aiA, raw }); assert.ok([400, 413].includes(result.status)); assert.equal(result.text.includes('PRIVATE_CANARY'), false);
    }
    assert.equal((await http(target, { token: aiA, body: report(), headers: { 'Content-Type': 'text/plain' } })).status, 415);
    assert.equal((await http(base(ownerA) + '/device/reports', { token: ownerA.token, body: report() })).status, 404);
  });
  await check('feedback rate/failure does not alter phone sequence or command admission', async () => {
    phone = await connect(ownerA);
    const before = await http(base(ownerA) + '/status', { token: aiA }); assert.equal(before.data.command_sequence, 0);
    let limited = false;
    for (let i = 0; i < 25; i++) { const result = await http(base(ownerA) + '/reports', { token: aiA, body: report({ title: 'rate' + i }) }); if (result.status === 429) { assert.equal(result.data.error, 'feedback_rate_limited'); limited = true; break; } assert.equal(result.status, 201); }
    assert.equal(limited, true);
    assert.equal((await http(base(ownerA) + '/status', { token: aiA })).data.command_sequence, 0);
    await test({ operation: 'fail', value: true });
    assert.equal((await http(base(ownerA) + '/reports', { token: aiA })).status, 503);
    const command = await http(base(ownerA) + '/command', { token: aiA, body: { id: randomUUID(), sequence: 1, action: 'ping' } }); assert.equal(command.status, 200); assert.equal(command.data.ok, true);
    await test({ operation: 'fail', value: false }); phone.close(); phone = null;
    for (let i = 0; i < 6; i++) { const response = await http('/v1/reports', { body: report(), ip: '127.0.0.2' }); assert.equal(response.status, i < 5 ? 201 : 429); }
    assert.equal((await http(base(ownerA) + '/status', { token: aiA })).data.command_sequence, 1);
  });
  await check('report content, tombstones and hashed receipts survive workerd restart', async () => {
    await mf.dispose(); await start();
    assert.equal((await http('/v1/reports/' + anonymous.report_id, { token: anonymousToken })).data.report.description, anonymous.description);
    assert.equal((await http(base(ownerA) + '/device/reports/' + deletedId, { token: ownerA.token })).status, 410);
    const stored = await test({ operation: 'inspect' }); assert.ok(stored.alarm); assert.ok(stored.rows['feedback:report:' + anonymous.report_id]);
  });
  await check('retention deletes actual stored content on read and alarm, removes private receipt hashes and renews cleanup', async () => {
    const stored = await test({ operation: 'inspect' }); const expiration = stored.rows['feedback:report:' + anonymous.report_id].expires_at;
    // A later report proves the first expiry does not delete the only future alarm.
    await test({ operation: 'time', time: expiration - 100 });
    const later = report({ description: 'LATER_REPORT_CANARY' }); const laterToken = hex(32); secrets.push(laterToken);
    assert.equal((await http('/v1/reports', { token: laterToken, body: later })).status, 201);
    await test({ operation: 'time', time: expiration + 10 });
    assert.equal((await http('/v1/reports/' + anonymous.report_id, { token: anonymousToken })).status, 404);
    const afterRead = await test({ operation: 'inspect' }); assert.equal(JSON.stringify(afterRead.rows).includes(anonymous.description), false); assert.equal(afterRead.rows['feedback:report:' + anonymous.report_id], undefined);
    assert.equal(afterRead.rows['feedback:report:' + deletedId], undefined); assert.ok(afterRead.alarm > (expiration + 10) * 1000);
    await test({ operation: 'time', time: expiration + 30 * 86400 });
    const afterAlarm = await test({ operation: 'alarm' }); assert.equal(JSON.stringify(afterAlarm.rows).includes(later.description), false); assert.equal(afterAlarm.rows['feedback:report:' + later.report_id], undefined); assert.equal(afterAlarm.alarm, null);
    assert.equal(afterAlarm.rows['feedback:counts'].total, 0);
  });
  await check('bounded claim capacity includes deletion tombstones and cannot be bypassed by retrying deletes', async () => {
    let nextTime = time + 70 * 86400;
    for (let group = 0; group < 5; group++) {
      await test({ operation: 'time', time: nextTime + group * 3600 });
      for (let i = 0; i < 20; i++) {
        const body = report({ title: 'capacity ' + group + ':' + i });
        assert.equal((await http(base(ownerA) + '/reports', { token: aiA, body })).status, 201);
        assert.equal((await http(base(ownerA) + '/reports/' + body.report_id, { token: aiA, method: 'DELETE' })).status, 200);
      }
    }
    await test({ operation: 'time', time: nextTime + 5 * 3600 });
    const excess = await http(base(ownerA) + '/reports', { token: aiA, body: report() }); assert.equal(excess.status, 429); assert.equal(excess.data.error, 'feedback_capacity_reached');
    assert.deepEqual((await http(base(ownerA) + '/reports', { token: aiA })).data.reports, []);
  });
  await check('alarm cleanup drains multiple bounded batches without dropping its next wakeup', async () => {
    const at = time + 70 * 86400 + 6 * 3600;
    await test({ operation: 'time', time: at });
    assert.equal((await http(base(ownerB) + '/reports', { token: aiB, body: report({ description: 'BATCH_101_CANARY' }) })).status, 201);
    await test({ operation: 'time', time: at + 31 * 86400 });
    const firstBatch = await test({ operation: 'alarm' });
    assert.equal(firstBatch.rows['feedback:counts'].total, 1); assert.ok(firstBatch.alarm);
    const lastBatch = await test({ operation: 'alarm' });
    assert.equal(lastBatch.rows['feedback:counts'].total, 0); assert.equal(lastBatch.alarm, null);
    assert.equal(JSON.stringify(lastBatch.rows).includes('BATCH_101_CANARY'), false);
  });
  await check('feedback implementation and runtime logs never emit submitted bodies or credentials', async () => {
    assert.equal(/console\.|logger\./.test(sources['relay/service/feedback.mjs']), false);
    const captured = logs.join('\n'); for (const value of [...secrets, first.description, 'PRIVATE_SUPPLEMENT_1', 'PRIVATE_MAINTENANCE_REPLY', anonymous.description]) assert.equal(captured.includes(value), false);
    assert.equal((await http(base(ownerB) + '/command', { token: adminToken, body: { id: randomUUID(), sequence: 1, action: 'ping' } })).status, 401);
  });
} finally {
  phone?.terminate(); await mf?.dispose(); await rm(temp, { recursive: true, force: true });
  const result = { checked_at: new Date().toISOString(), scope: 'Production relay and FeedbackStore in real local workerd; test-only DO subclass injects storage time/failure and inspects stored records. Fake phone. No production deployment or physical device.', passed: checks.length, checks, source_sha256: Object.fromEntries(Object.entries(sources).map(([path, source]) => [path, digest(source)])) };
  if (option('--output')) { const output = resolve(option('--output')); await mkdir(join(output, '..'), { recursive: true }); await writeFile(output, JSON.stringify(result, null, 2) + '\n', { mode: 0o600 }); }
}
