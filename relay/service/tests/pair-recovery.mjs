#!/usr/bin/env node
// Production relay modules, real workerd and real Python CLI subprocess; no phone.
// A loopback-only edge adapter adds CF-Connecting-IP as Cloudflare does in service.
// No test/admin endpoints, storage edits, physical phone or personal AI are used.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdtemp, mkdir, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
const option = key => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : undefined;
const requireWorker = createRequire(new URL('../../worker/package.json', import.meta.url));
const miniflareURL = pathToFileURL(option('--miniflare') ?? requireWorker.resolve('miniflare')).href;
const { Miniflare, convertV4MiniflareOptions } = await import(miniflareURL);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const source = await readFile(join(root, 'relay/service/service.mjs'), 'utf8');
const core = await readFile(join(root, 'relay/worker/worker.mjs'), 'utf8');
const cliPath = join(root, 'cli/airtap.py'), cli = await readFile(cliPath);
const guide = await readFile(join(root, 'docs/agent.md'));
const digest = value => createHash('sha256').update(value).digest('hex');
const hex = bytes => randomBytes(bytes).toString('hex');
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-pair-recovery-')));
await mkdir(join(temp, 'assets/cli'), { recursive: true });
await writeFile(join(temp, 'assets/cli/airtap.py'), cli);
await writeFile(join(temp, 'assets/agent.md'), guide);
const options = { host: '127.0.0.1', port: 0, cf: false, workers: [{ name: 'airtap-companion-v4',
  modulesRoot: join(root, 'relay'), modules: [
    { type: 'ESModule', path: join(root, 'relay/service/service.mjs'), contents: source },
    { type: 'ESModule', path: join(root, 'relay/worker/worker.mjs'), contents: core },
    { type: 'ESModule', path: join(root, 'relay/service/feedback.mjs'), contents: await readFile(join(root, 'relay/service/feedback.mjs'), 'utf8') },
  ], compatibilityDate: '2026-10-01',
  durableObjects: { ADMISSION: { className: 'AdmissionRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
  ratelimits: { INGRESS: { namespace_id: '2401', simple: { limit: 10000, period: 60 } } },
  assets: { directory: join(temp, 'assets'), binding: 'ASSETS', run_worker_first: true, routerConfig: { has_user_worker: true } },
  bindings: { SERVICE_ID: hex(16), SERVICE_LABEL: 'Local CLI roundtrip', IP_HASH_SALT: hex(32), REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true',
    CLI_SHA256: digest(cli), AGENT_SHA256: digest(guide), ARTIFACT_VERSION: '0.4.0-companion-preview' },
}] };
const mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options);
const backend = await mf.ready;
let fault = null; const observedPairRequests = [];
const edge = createServer(async (request, response) => {
  try {
    const chunks = []; let size = 0;
    for await (const chunk of request) { size += chunk.length; if (size > 8192) throw new Error('test input limit'); chunks.push(chunk); }
    const headers = { ...request.headers, 'cf-connecting-ip': '127.0.0.1', connection: 'close' }; delete headers.host; delete headers['transfer-encoding'];
    const isPair = request.url.endsWith('/v1/pair');
    if (isPair) observedPairRequests.push(JSON.parse(Buffer.concat(chunks).toString()));
    if ((isPair && fault === 'pair-before') || (request.url.endsWith('/v1/pair/status') && fault === 'status-before')) { fault = null; request.socket.destroy(); return; }
    const upstream = await fetch(new URL(request.url, backend), { method: request.method, headers,
      ...(chunks.length ? { body: Buffer.concat(chunks) } : {}), signal: AbortSignal.timeout(20000) });
    const bytes = Buffer.from(await upstream.arrayBuffer()), outgoing = Object.fromEntries(upstream.headers);
    delete outgoing['content-encoding']; delete outgoing['transfer-encoding']; outgoing['content-length'] = String(bytes.length);
    if (isPair && fault === 'pair-after') { fault = null; request.socket.destroy(); return; }
    response.writeHead(upstream.status, outgoing); response.end(bytes);
  } catch { response.writeHead(502); response.end(); }
});
await new Promise(resolve => edge.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + edge.address().port;
const d = { id: hex(16), token: hex(32) }, base = origin + '/d/' + d.id;
let config = join(temp, 'private', 'profile.json');
const checks = [];
async function http(path, { token, body, method } = {}) {
  const r = await fetch(origin + path, { method: method ?? (body === undefined ? 'GET' : 'POST'), headers: {
    ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json; charset=utf-8' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(22000) });
  return new Response(await r.arrayBuffer(), { status: r.status, headers: r.headers });
}
async function python(args, stdin = '', expectedExit = 0, program = cliPath) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.env.PYTHON || 'python3', [program, '--allow-local-http', '--config', config, ...args], { stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '', stderr = ''; const timer = setTimeout(() => child.kill('SIGKILL'), 30000);
    child.stdout.on('data', bytes => { stdout += bytes; }); child.stderr.on('data', bytes => { stderr += bytes; });
    child.on('error', reject); child.on('close', code => {
      clearTimeout(timer);
      try { assert.equal(code, expectedExit, 'CLI exit mismatch: ' + stdout); assert.equal(stderr, ''); assert.equal(stdout.includes(d.token), false); for (const p of observedPairRequests) { assert.equal(stdout.includes(p.controller_token), false); assert.equal(stdout.includes(p.ticket), false); } resolve(JSON.parse(stdout)); }
      catch (error) { reject(error); }
    }); child.stdin.end(stdin);
  });
}
async function check(name, fn) {
  const start = performance.now();
  try { await fn(); checks.push({ name, ok: true, elapsedMs: Math.round(performance.now() - start) }); }
  catch (error) { checks.push({ name, ok: false, error: String(error.stack) }); }
  console.log(JSON.stringify(checks.at(-1))); assert.equal(checks.at(-1).ok, true);
}
const baselineCli = option('--baseline-cli') ? resolve(option('--baseline-cli')) : null;
const pairArgs = (name = 'Pair recovery test', url = base) => ['pair-start', '--url', url, '--name', name, '--ticket-stdin'];
async function prepare(tag) {
  config = join(temp, 'private', tag + '.json');
  const response = await http('/d/' + d.id + '/v1/device/pairing', { token: d.token, method: 'POST' });
  assert.equal(response.status, 200);
  return (await response.json()).ticket;
}
const stored = () => readFile(config).then(bytes => ({bytes, json: JSON.parse(bytes)}));
const unchangedToken = (a,b) => assert.equal(digest(a), digest(b));
async function ownerApprove() {
  const candidate = (await (await http('/d/' + d.id + '/v1/device/pairing', { token: d.token })).json()).candidate;
  const response = await http('/d/' + d.id + '/v1/device/approve', { token: d.token, body: {
    claim_id: candidate.claim_id, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true
  }});
  assert.equal(response.status, 200);
  return candidate;
}
try {
  assert.equal((await http('/v1/enroll', { body: { device_id: d.id, device_token: d.token } })).status, 200);
  if (baselineCli) await check('baseline reproduces first request not admitted followed by config_exists and status401', async () => {
    const ticket = await prepare('baseline'); fault = 'pair-before';
    const first = await python(pairArgs(), ticket + '\n', 1, baselineCli);
    assert.equal(first.ok, false);
    const original = await stored();
    const next = await python(pairArgs(), ticket + '\n', 1, baselineCli);
    assert.equal(next.error, 'config_exists_reuse_connection_or_choose_new_path');
    const status = await python(['pair-status'], '', 1, baselineCli); assert.equal(status.status, 401);
    assert.deepEqual((await stored()).bytes, original.bytes);
  });
  await check('not-admitted transport failure resumes same private token and name after actual status401', async () => {
    const ticket = await prepare('before'); fault = 'pair-before';
    const first = await python(pairArgs(), ticket + '\n', 1); assert.equal(first.ok, false);
    const original = await stored(); assert.equal(original.json.pairing_name, 'Pair recovery test');
    const before = observedPairRequests.length;
    const result = await python(pairArgs(), ticket + '\n'); assert.equal(result.state, 'pending');
    const latest = await stored(); unchangedToken(original.json.controller_token, latest.json.controller_token);
    assert.equal(observedPairRequests.length, before + 1);
    unchangedToken(observedPairRequests.at(-2).controller_token, observedPairRequests.at(-1).controller_token);
    assert.equal(observedPairRequests.at(-2).name, observedPairRequests.at(-1).name);
    assert.equal(latest.json.verification_code, result.verification_code);
    assert.equal('ticket' in latest.json, false);
  });
  await check('server-admitted response loss recovers original code with GET only', async () => {
    const ticket = await prepare('after'); fault = 'pair-after';
    const first = await python(pairArgs(), ticket + '\n', 1); assert.equal(first.ok, false);
    const original = await stored();
    const candidate = (await (await http('/d/' + d.id + '/v1/device/pairing', { token: d.token })).json()).candidate;
    const before = observedPairRequests.length;
    const result = await python(pairArgs(), ticket + '\n'); assert.equal(result.verification_code, candidate.verification_code);
    assert.equal(result.claim_id, candidate.claim_id); assert.equal(observedPairRequests.length, before);
    unchangedToken(original.json.controller_token, (await stored()).json.controller_token);
  });
  await check('production relay accepts same token/name idempotently and rejects either identity mismatch', async () => {
    const original = await stored(), ticket = observedPairRequests.at(-1).ticket;
    const body = { ticket, controller_token: original.json.controller_token, name: original.json.pairing_name };
    const exact = await http('/d/' + d.id + '/v1/pair', { body }); assert.equal(exact.status, 200);
    assert.equal((await exact.json()).claim_id, original.json.claim_id);
    assert.equal((await http('/d/' + d.id + '/v1/pair', { body: {...body, name:'Other name'} })).status, 409);
    assert.equal((await http('/d/' + d.id + '/v1/pair', { body: {...body, controller_token:hex(32)} })).status, 409);
    const before = observedPairRequests.length;
    for (const args of [pairArgs('Other name'), pairArgs('Pair recovery test', origin + '/d/' + hex(16))]) {
      const error = await python(args, ticket + '\n', 1); assert.equal(error.error, 'pairing_identity_mismatch');
      assert.deepEqual((await stored()).bytes, original.bytes);
    }
    assert.equal(observedPairRequests.length, before);
  });
  await check('approval after lost response is recovered without a second pair POST or new identity', async () => {
    const ticket = await prepare('approved-after-loss'); fault = 'pair-after';
    await python(pairArgs(), ticket + '\n', 1); const original = await stored();
    const candidate = await ownerApprove(), before = observedPairRequests.length;
    const result = await python(pairArgs(), ticket + '\n'); assert.equal(result.state, 'approved');
    assert.equal(result.claim_id, candidate.claim_id); assert.equal(result.persistent, true); assert.equal(result.session_expires_at, null);
    assert.equal(observedPairRequests.length, before); unchangedToken(original.json.controller_token, (await stored()).json.controller_token);
    const approved = await stored();
    const repeated = await python(pairArgs(), ticket + '\n', 1); assert.equal(repeated.error, 'config_exists_reuse_connection_or_choose_new_path');
    assert.deepEqual((await stored()).bytes, approved.bytes);
  });
  await check('uncertain status check never falls through to pairing POST or rewrites config', async () => {
    const ticket = await prepare('status-lost'); fault = 'pair-before'; await python(pairArgs(), ticket + '\n', 1);
    const original = await stored(), before = observedPairRequests.length; fault = 'status-before';
    const error = await python(pairArgs(), ticket + '\n', 1); assert.equal(error.ok, false);
    assert.equal(observedPairRequests.length, before); assert.deepEqual((await stored()).bytes, original.bytes);
    assert.equal((await python(pairArgs(), ticket + '\n')).state, 'pending');
  });
  await check('remote revocation refuses retry and preserves original pending config', async () => {
    const ticket = await prepare('revoked'); fault = 'pair-after'; await python(pairArgs(), ticket + '\n', 1);
    const original = await stored(); assert.equal((await http('/d/' + d.id + '/v1/device/revoke', {token:d.token, method:'POST'})).status, 200);
    const before = observedPairRequests.length;
    const error = await python(pairArgs(), ticket + '\n', 1); assert.equal(error.status, 410);
    assert.equal(observedPairRequests.length, before); assert.deepEqual((await stored()).bytes, original.bytes);
  });
  await check('legacy config without recorded name is left intact and can still use pair-status', async () => {
    const ticket = await prepare('legacy'); await python(pairArgs(), ticket + '\n');
    const legacy = (await stored()).json; delete legacy.pairing_name;
    await writeFile(config, JSON.stringify(legacy), {mode:0o600});
    const original = await stored(), before = observedPairRequests.length;
    assert.equal(original.json.pairing_name, undefined);
    const error = await python(pairArgs(), ticket + '\n', 1); assert.equal(error.error, 'pairing_identity_mismatch');
    assert.deepEqual((await stored()).bytes, original.bytes); assert.equal(observedPairRequests.length, before);
    assert.equal((await python(['pair-status'])).state, 'pending');
  });
} finally {
  edge.closeAllConnections(); await new Promise(resolve => edge.close(resolve)); await mf.dispose(); await rm(temp, { recursive: true, force: true });
  const report = JSON.stringify({ checkedAt: new Date().toISOString(), scope: 'Production relay modules in local workerd, real CLI subprocess, fault-injected loopback HTTP adapter; no phone, cloud deployment or personal AI.', baselineCliSha256: baselineCli ? digest(await readFile(baselineCli)) : null, cliSha256: digest(cli), serviceSourceSha256:digest(source), coreSourceSha256:digest(core), passed: checks.filter(c => c.ok).length, total:checks.length, checks }, null, 2) + '\n';
  if (option('--output')) {
    const output = resolve(option('--output')); await mkdir(join(output, '..'), { recursive: true });
    await writeFile(output, report, {mode:0o600});
  } else {
    console.log(report);
  }
}
