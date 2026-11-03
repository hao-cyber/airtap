#!/usr/bin/env node
// Bounded, loopback-only capacity experiment. Production HTTP/WS handlers and
// SQLite-backed workerd; synthetic phones acknowledge tasks, not Android UI.
// The fixture adds counters, never seeds authorization or exports diagnostics.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdir, mkdtemp, rm, readdir, stat } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { join, dirname } from 'node:path';
import { tmpdir, platform, arch, cpus, totalmem } from 'node:os';

const options = new Map();
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i], value = process.argv[i + 1];
  if (!['--devices', '--concurrency', '--rounds', '--idle-ms', '--output', '--miniflare'].includes(key) || !value || options.has(key)) throw new Error('Invalid option');
  options.set(key, value);
}
function integer(name, fallback, min, max) {
  const raw = options.get(name) ?? String(fallback), value = Number(raw);
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(value) || value < min || value > max) throw new Error('Invalid bounded ' + name);
  return value;
}
const count = integer('--devices', 24, 2, 1000), concurrency = integer('--concurrency', 12, 1, 1000);
const rounds = integer('--rounds', 2, 1, 10), idleMs = integer('--idle-ms', 1000, 0, 10000);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const requireWorker = createRequire(new URL('../../worker/package.json', import.meta.url));
const miniflareURL = pathToFileURL(options.get('--miniflare') ?? requireWorker.resolve('miniflare'));
const { Miniflare, convertV4MiniflareOptions } = await import(miniflareURL.href);
const miniflareVersion = JSON.parse(await readFile(new URL('../../package.json', miniflareURL), 'utf8')).version;
const workerdVersion = JSON.parse(await readFile(createRequire(miniflareURL).resolve('workerd/package.json'), 'utf8')).version;
const hex = bytes => randomBytes(bytes).toString('hex'), digest = data => createHash('sha256').update(data).digest('hex');
let temp, mf;
const fixture = `import handler, {AdmissionRegistry, ServiceDeviceRelay} from './service/service.mjs';
export class MeasuredRegistry extends AdmissionRegistry {
 async save(next) { const bytes=new TextEncoder().encode(JSON.stringify(next)).length; await super.save(next); this.directoryWrites=(this.directoryWrites??0)+1; this.directoryBytesWritten=(this.directoryBytesWritten??0)+bytes; }
 async saveUsage(id,u) { await super.saveUsage(id,u); this.usageWrites=(this.usageWrites??0)+1; }
 async measure() { return this.ctx.blockConcurrencyWhile(async()=>({directoryWrites:this.directoryWrites??0,directoryBytesWritten:this.directoryBytesWritten??0,directoryBytes:new TextEncoder().encode(JSON.stringify(this.data)).length,usageWrites:this.usageWrites??0,registered:Object.values(this.data.devices).filter(r=>r.state==='active').length,reserved:Object.values(this.data.devices).filter(r=>r.state==='reserved').length,retired:Object.values(this.data.devices).filter(r=>r.state==='retired').length})); }
}
export {ServiceDeviceRelay};
export default {async fetch(r,e) { if(new URL(r.url).pathname==='/__fixture_metrics')return Response.json(await e.ADMISSION.get(e.ADMISSION.idFromName('service')).measure()); return handler.fetch(r,e); }};`;
const paths = ['relay/service/service.mjs', 'relay/worker/worker.mjs', 'relay/service/feedback.mjs'];
let modules = [];
async function initialize() {
  modules = await Promise.all(paths.map(async p => ({ type: 'ESModule', path: join(root, p), contents: await readFile(join(root, p), 'utf8') })));
  report.production_sha256 = Object.fromEntries(modules.map((m, i) => [paths[i], digest(m.contents)]));
  temp = await mkdtemp(join(tmpdir(), 'airtap-scale-'));
  const settings = { host: '127.0.0.1', port: 0, cf: false, durableObjectsPersist: join(temp, 'do'), resourcePersistencePath: join(temp, 'state'), workers: [{
    name: 'local-relay-scale', modulesRoot: join(root, 'relay'), modules: [{ type: 'ESModule', path: join(root, 'relay/scale-fixture.mjs'), contents: fixture }, ...modules],
    compatibilityDate: '2026-10-01', durableObjects: { ADMISSION: { className: 'MeasuredRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
    ratelimits: { INGRESS: { namespace_id: '2603', simple: { limit: 10000, period: 60 } } },
    bindings: { SERVICE_ID: hex(16), SERVICE_LABEL: 'Local synthetic capacity test', IP_HASH_SALT: hex(32), REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true', MAX_DEVICES: String(count), DAILY_REGISTRATIONS: '1000', IP_DAILY_REGISTRATIONS: '20', CLI_SHA256: 'a'.repeat(64), AGENT_SHA256: 'b'.repeat(64) },
  }] };
  mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(settings), telemetry: { enabled: false } } : settings);
}
const sockets = [], devices = Array.from({ length: count }, (_, i) => ({ id: hex(16), token: hex(32), controller: hex(32), ip: '2001:db8::' + (i + 1).toString(16), sequence: 0 }));
const report = { schema: 'airtap-local-relay-scale-v1', started_at: new Date().toISOString(), parameters: { devices: count, concurrency, ping_rounds: rounds, idle_ms: idleMs },
  environment: { node: process.version, miniflare: miniflareVersion, workerd: workerdVersion, platform: platform(), architecture: arch(), logical_cpus: cpus().length, memory_bytes: totalmem() },
  harness_sha256: digest(await readFile(fileURLToPath(import.meta.url))), fixture_sha256: digest(fixture),
  production_sha256: Object.fromEntries(modules.map((m, i) => [paths[i], digest(m.contents)])), phases: [], storage: {}, guards: {},
  limits: ['Local single workerd process; synthetic in-process WebSockets and immediate phone acknowledgements. No WAN, Android, screenshot payload, personal AI or Cloudflare capacity/billing claim.', 'One controller per device migrates through the production multi-AI protocol. Measured concurrency is across distinct devices, not multiple simultaneous commands on one phone.', 'Storage counters measure successful application writes and JSON bytes, not Cloudflare billed rows, SQLite disk IO or durable-object memory. Runtime eviction may reset the in-memory fixture counters.', 'No external target option, cloud credentials, deployment, physical phone or existing configuration is used.'] };
let responseBytes = 0, requests = 0, phoneCommands = 0, errors = 0, peakSockets = 0, currentPhase;
let activeTasks = 0, peakActiveTasks = 0, activeCommands = 0, peakActiveCommands = 0;
const pingLatencies = [];
let deadline;
const helloTimers = new Set();
async function request(path, d, token, body, method) {
  if (report.deadline_exceeded) throw new Error('local_experiment_deadline_exceeded');
  requests++;
  const response = await mf.dispatchFetch('http://localhost' + path, { method: method ?? (body === undefined ? 'GET' : 'POST'), headers: { 'cf-connecting-ip': d?.ip ?? '2001:db8::ffff', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const raw = await response.text(); responseBytes += Buffer.byteLength(raw); return { status: response.status, data: JSON.parse(raw) };
}
async function ok(path, d, token, body, method) { const r = await request(path, d, token, body, method); if (r.status !== 200) { errors++; throw new Error('HTTP ' + r.status + ': ' + (r.data.error ?? 'unexpected_response')); } return r.data; }
const endpoint = (d, suffix) => '/d/' + d.id + suffix;
async function pool(items, fn) {
  let index = 0, failed;
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (!failed && index < items.length) { const item = items[index++]; try { await fn(item); } catch (error) { failed ??= error; } }
  }));
  if (failed) throw failed;
}
function percentiles(latencies) {
  const sorted = [...latencies].sort((a, b) => a - b);
  const pct = p => Number((sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)] ?? 0).toFixed(3));
  return { p50: pct(.5), p95: pct(.95), p99: pct(.99), max: pct(1) };
}
async function phase(name, items, fn) {
  currentPhase = name; const latencies = [], started = performance.now(), beforeRequests = requests;
  await pool(items, async item => { const start = performance.now(); await fn(item); latencies.push(performance.now() - start); });
  const elapsed = performance.now() - started;
  const value = { name, completed: latencies.length, elapsed_ms: Number(elapsed.toFixed(3)), operations_per_second: Number((items.length / (elapsed / 1000)).toFixed(3)), http_requests: requests - beforeRequests, latency_ms: percentiles(latencies) };
  report.phases.push(value); console.log(JSON.stringify(value));
}
async function metrics() { return (await request('/__fixture_metrics')).data; }
const caps = ['multi_ai_v1', 'task_lease_v1'];
async function runExperiment() {
  await mf.ready;
  await phase('registered', devices, d => ok('/v1/enroll', d, null, { device_id: d.id, device_token: d.token }));
  report.storage.after_registration = await metrics();
  const atCapacity = await request('/v1/enroll', null, null, { device_id: hex(16), device_token: hex(32) });
  assert.equal(atCapacity.status, 429); assert.equal(atCapacity.data.error, 'capacity_reached');
  assert.deepEqual(await metrics(), report.storage.after_registration);
  report.guards.registration_capacity_rejected_without_write = true;
  await phase('paired_multi', devices, async d => {
    const invite = await ok(endpoint(d, '/v1/device/pairing'), d, d.token, undefined, 'POST');
    const paired = await ok(endpoint(d, '/v1/pair'), d, null, { ticket: invite.ticket, controller_token: d.controller, name: 'Synthetic capacity client' });
    d.claim = paired.claim_id;
    await ok(endpoint(d, '/v1/device/approve'), d, d.token, { claim_id: d.claim, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true });
    await ok(endpoint(d, '/v1/device/multi/enable'), d, d.token, { capabilities: caps, expected_claim_id: d.claim });
  });
  await phase('connected', devices, async d => {
    const r = await mf.dispatchFetch('http://localhost' + endpoint(d, '/v1/device'), { headers: { 'cf-connecting-ip': d.ip, Authorization: 'Bearer ' + d.token, Upgrade: 'websocket' } });
    assert.equal(r.status, 101); const ws = r.webSocket; ws.accept(); sockets.push(ws); d.socket = ws; peakSockets = sockets.length;
    let resolveHello, rejectHello; const hello = new Promise((resolve, reject) => { resolveHello = resolve; rejectHello = reject; });
    const timer = setTimeout(() => rejectHello(new Error('synthetic_hello_timeout')), 10000);
    helloTimers.add(timer);
    ws.addEventListener('message', e => { const b = JSON.parse(e.data);
      if (b.type === 'hello_ack') { clearTimeout(timer); helloTimers.delete(timer); resolveHello(); return; }
      if (b.type === 'task_fence') { ws.send(JSON.stringify({ type: 'task_fence_ack', operation_id: b.operation_id, lease_epoch: b.lease_epoch, ok: true })); return; }
      phoneCommands++; ws.send(JSON.stringify({ id: b.command?.id ?? b.id, ok: true, result: { synthetic: true, authorized: true, screen_state: 'ready' } }));
    });
    ws.send(JSON.stringify({ type: 'hello', protocol_version: 4, capabilities: caps })); await hello;
  });
  if (idleMs) await new Promise(resolve => setTimeout(resolve, idleMs));
  await phase('online_status', devices, async d => { const s = await ok(endpoint(d, '/v1/status'), d, d.controller); assert.equal(s.deviceConnected, true); });
  report.storage.before_commands = await metrics();
  // Acquire, bounded commands, release per device: no lease is held while a
  // later batch queues for minutes. Different devices can execute concurrently.
  await phase('active_tasks', devices, async d => {
    const taskId = hex(16); const lease = await ok(endpoint(d, '/v1/tasks/acquire'), d, d.controller, { task_id: taskId });
    assert.equal(lease.state, 'ready');
    activeTasks++; peakActiveTasks = Math.max(peakActiveTasks, activeTasks);
    for (let n = 0; n < rounds; n++) { const start = performance.now(); activeCommands++; peakActiveCommands = Math.max(peakActiveCommands, activeCommands);
      try { const r = await ok(endpoint(d, '/v1/command'), d, d.controller, { id: crypto.randomUUID(), action: 'ping', sequence: ++d.sequence, lease_id: lease.lease_id, lease_epoch: lease.lease_epoch }); assert.equal(r.ok, true); pingLatencies.push(performance.now() - start); }
      finally { activeCommands--; }
    }
    await ok(endpoint(d, '/v1/tasks/release'), d, d.controller, { task_id: taskId, lease_id: lease.lease_id, lease_epoch: lease.lease_epoch });
    activeTasks--;
  });
  report.ping_commands = { completed: pingLatencies.length, latency_ms: percentiles(pingLatencies), commands_per_second_including_acquire_and_release: Number((pingLatencies.length / (report.phases.at(-1).elapsed_ms / 1000)).toFixed(3)) };
  report.storage.after_commands = await metrics();
  await phase('final_idle', devices, async d => { const s = await ok(endpoint(d, '/v1/status'), d, d.controller); assert.equal(s.task.state, 'idle'); assert.equal(s.pending, 0); });
  currentPhase = 'authorization_guards';
  const a = devices[0], b = devices[1], beforeGuards = phoneCommands;
  const beforeForeign = await metrics();
  const foreign = await request(endpoint(a, '/v1/status'), a, b.controller); assert.equal(foreign.status, 401);
  const foreignCommand = await request(endpoint(a, '/v1/command'), a, b.controller, { id: crypto.randomUUID(), action: 'ping', sequence: a.sequence + 1 }); assert.equal(foreignCommand.status, 401);
  assert.deepEqual(await metrics(), beforeForeign);
  const grants = await ok(endpoint(a, '/v1/device/grants'), a, a.token);
  await ok(endpoint(a, '/v1/device/revoke'), a, a.token, { expected_generation: grants.generation });
  const stopped = await request(endpoint(a, '/v1/tasks/acquire'), a, a.controller, { task_id: hex(16) }); assert.equal(stopped.status, 401);
  assert.equal(phoneCommands, beforeGuards);
  Object.assign(report.guards, { foreign_controller_rejected_without_charge: true, stopped_controller_rejected: true, rejected_operations_dispatched: 0 });
  assert.equal(phoneCommands, count * rounds);
  if (report.deadline_exceeded) throw new Error('local_experiment_deadline_exceeded');
  report.status = 'passed_local_synthetic';
}
try {
  currentPhase = 'initialization';
  await initialize();
  await Promise.race([runExperiment(), new Promise((_, reject) => {
    deadline = setTimeout(() => { report.deadline_exceeded = true; reject(new Error('local_experiment_deadline_exceeded')); }, 180000);
  })]);
} catch (error) {
  report.status = 'failed'; report.failure = { phase: currentPhase, name: error.name, message: error.message.slice(0, 240) }; process.exitCode = 1;
} finally {
  clearTimeout(deadline);
  for (const timer of helloTimers) clearTimeout(timer);
  const cleanup = { local_workerd_disposed: !mf, temporary_sqlite_removed: !temp, socket_close_requested: 0, errors: [] };
  const cleanupError = (step, error) => {
    cleanup.errors.push({ step, name: error.name, message: error.message.slice(0, 240) });
    if (report.status !== 'failed') report.failure = { phase: 'cleanup', name: error.name, message: step + '_failed' };
    report.status = 'failed'; process.exitCode = 1;
  };
  for (const ws of sockets) try { ws.close(1000, 'local experiment complete'); cleanup.socket_close_requested++; } catch (error) { cleanupError('socket_close', error); }
  if (mf) {
    let timer;
    try {
      await Promise.race([mf.dispose(), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('local_dispose_timeout')), 10000); })]);
      cleanup.local_workerd_disposed = true;
    } catch (error) { cleanupError('dispose', error); }
    finally { clearTimeout(timer); }
  }
  async function storedFiles(path) {
    let bytes = 0, files = 0;
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const name = join(path, entry.name);
      if (entry.isDirectory()) { const child = await storedFiles(name); bytes += child.bytes; files += child.files; }
      else if (entry.isFile()) { bytes += (await stat(name)).size; files++; }
    }
    return { bytes, files };
  }
  if (temp && cleanup.local_workerd_disposed) {
    try { report.local_persisted_files_before_cleanup = await storedFiles(temp); }
    catch (error) { cleanupError('storage_measurement', error); }
    try { await rm(temp, { recursive: true, force: true }); cleanup.temporary_sqlite_removed = true; }
    catch (error) { cleanupError('storage_removal', error); }
  }
  // Do not remove a live runtime's SQLite files if disposal is unconfirmed.
  cleanup.temporary_directory_retained = Boolean(temp && !cleanup.temporary_sqlite_removed);
  report.finished_at = new Date().toISOString(); report.totals = { requests, response_bytes: responseBytes, dispatched_synthetic_pings: phoneCommands, unexpected_http_errors: errors, peak_websockets: peakSockets, peak_client_observed_ready_tasks: peakActiveTasks, peak_outstanding_command_requests: peakActiveCommands };
  report.cleanup = cleanup;
  if (options.has('--output')) {
    try { await mkdir(dirname(options.get('--output')), { recursive: true }); await writeFile(options.get('--output'), JSON.stringify(report, null, 2) + '\n'); }
    catch (error) { cleanupError('report_write', error); }
  }
  console.log(JSON.stringify(report));
}
