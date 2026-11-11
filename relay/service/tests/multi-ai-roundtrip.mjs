#!/usr/bin/env node
// Real local workerd, production modules, synthetic identities and fake phone.
// No cloud service, personal AI, private config or physical device is contacted.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdtemp, mkdir, rm, realpath, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
const option = k => process.argv.includes(k) ? process.argv[process.argv.indexOf(k) + 1] : undefined;
const credentialRestoreOnly = process.argv.includes('--credential-restore-only');
const fenceRecoveryOnly = process.argv.includes('--fence-recovery-only');
const require = createRequire(new URL('../../worker/package.json', import.meta.url));
const { Miniflare, convertV4MiniflareOptions } = await import(pathToFileURL(option('--miniflare') ?? require.resolve('miniflare')).href);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-multi-')));
await mkdir(join(temp, 'assets'));
const hex = n => randomBytes(n).toString('hex');
const bindings = { SERVICE_ID: hex(16), SERVICE_LABEL: 'Synthetic multi AI test', IP_HASH_SALT: hex(32), REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true',
  FEEDBACK_ADMIN_SHA256: 'c'.repeat(64), CLI_SHA256: 'a'.repeat(64), AGENT_SHA256: 'b'.repeat(64), ARTIFACT_VERSION: 'candidate', MAX_DEVICES: '100', DAILY_REGISTRATIONS: '100', IP_DAILY_REGISTRATIONS: '20' };
const options = { host: '127.0.0.1', port: 0, cf: false, durableObjectsPersist: join(temp, 'do'), resourcePersistencePath: join(temp, 'state'), workers: [{ name: 'multi-ai-test', modulesRoot: join(root, 'relay'),
  modules: await Promise.all(['service/service.mjs', 'worker/worker.mjs', 'service/feedback.mjs'].map(async f => ({ type: 'ESModule', path: join(root, 'relay', f), contents: await readFile(join(root, 'relay', f), 'utf8') }))),
  compatibilityDate: '2026-10-01', durableObjects: { ADMISSION: { className: 'AdmissionRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
  ratelimits: { INGRESS: { namespace_id: '3401', simple: { limit: 10000, period: 60 } } },
  assets: { directory: join(temp, 'assets'), binding: 'ASSETS', run_worker_first: true, routerConfig: { has_user_worker: true } }, bindings }] };
let mf;
async function start() { mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options); await mf.ready; }
await start();
const checks = [], sockets = [];
let syntheticEnrollmentIp = 0; // One independent caller per fixture device; quota behavior has dedicated tests.
async function request(path, token, body, method) {
  const response = await mf.dispatchFetch('http://localhost' + path, { method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers: { 'cf-connecting-ip': path === '/v1/enroll' ? `192.0.2.${++syntheticEnrollmentIp}` : '127.0.0.1', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  return { status: response.status, data: await response.json() };
}
async function ok(path, token, body, method) { const r = await request(path, token, body, method); assert.equal(r.status, 200, JSON.stringify(r)); return r.data; }
async function denied(path, token, body, status, error, method) { const r = await request(path, token, body, method); assert.equal(r.status, status, JSON.stringify(r)); assert.equal(r.data.error, error); return r.data; }
async function check(name, fn, group = 'core') { if (credentialRestoreOnly && group !== 'credential-restore' || fenceRecoveryOnly && group !== 'fence-recovery') return; const started = performance.now(); await fn(); const r = { name, ok: true, elapsed_ms: Math.round(performance.now() - started) }; checks.push(r); console.log(JSON.stringify(r)); }
async function enroll() { const d = { id: hex(16), token: hex(32) }; d.base = '/d/' + d.id; await ok('/v1/enroll', null, { device_id: d.id, device_token: d.token }); return d; }
const caps = ['multi_ai_v1', 'task_lease_v1'];
const migration = claim => ({ capabilities: caps, expected_claim_id: claim });
const approval = id => ({ claim_id: id, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true });
async function stopAll(d) { const state=await ok(d.base+'/v1/device/grants',d.token);return ok(d.base+'/v1/device/revoke',d.token,{expected_generation:state.generation}); }
async function single(d) {
  const invite = await ok(d.base + '/v1/device/pairing', d.token, undefined, 'POST');
  const a = { token: hex(32), name: 'Synthetic A' };
  a.claim = (await ok(d.base + '/v1/pair', null, { ticket: invite.ticket, controller_token: a.token, name: a.name })).claim_id;
  await ok(d.base + '/v1/device/approve', d.token, approval(a.claim)); return a;
}
async function phone(d, multi = true, initialAck = 'success') {
  const r = await mf.dispatchFetch('http://localhost' + d.base + '/v1/device', { headers: { 'cf-connecting-ip': '127.0.0.1', Authorization: 'Bearer ' + d.token, Upgrade: 'websocket' } });
  assert.equal(r.status, 101); const ws = r.webSocket; ws.accept(); sockets.push(ws);
  const p = { ws, messages: [], ack: initialAck, commands: 'success' };
  let hello; const ready = new Promise(resolve => { hello = resolve; });
  p.reply = b => ws.send(JSON.stringify(b));
  p.ackFence = f => p.reply({ type: 'task_fence_ack', operation_id: f.operation_id, lease_epoch: f.lease_epoch, ok: true });
  ws.addEventListener('message', e => {
    const b = JSON.parse(e.data); p.messages.push(b);
    if (b.type === 'hello_ack') { hello(); return; }
    if (b.type === 'task_fence') {
      if (p.ack === 'success') p.ackFence(b);
      else if (p.ack === 'negative') p.reply({ type: 'task_fence_ack', operation_id: b.operation_id, lease_epoch: b.lease_epoch, ok: false, error: 'command_in_progress' });
      return;
    }
    p.onCommand?.(b);
    if (p.commands === 'success') p.reply({ id: b.command?.id ?? b.id, ok: true, result: { synthetic: true, authorized: true, screen_state: 'ready', auto_wake: true } });
  });
  ws.send(multi ? JSON.stringify({ type: 'hello', protocol_version: 4, capabilities: caps }) : 'hello');
  if (multi) await ready;
  return p;
}
const command = (n, t) => ({ id: crypto.randomUUID(), action: 'ping', sequence: n, ...(t ? { lease_id: t.lease_id, lease_epoch: t.lease_epoch } : {}) });
const leaseBody = t => ({ task_id: t.task_id, lease_id: t.lease_id, lease_epoch: t.lease_epoch });
async function cliLoopback(device, controller, { profileName, clockOffsetSeconds = 0, drop = null, createProfile = true }) {
  const profile = join(temp, profileName), cliPath = option('--cli') ?? join(root, 'cli/airtap.py'), requests = [];
  const edge = createServer(async (req, res) => {
    try {
      const chunks = []; for await (const bytes of req) chunks.push(bytes);
      const raw = Buffer.concat(chunks), endpoint = req.url.slice(device.base.length);
      const upstream = await mf.dispatchFetch('http://localhost' + req.url, { method: req.method,
        headers: { ...req.headers, 'cf-connecting-ip': '127.0.0.1' }, ...(raw.length ? { body: raw } : {}) });
      const bytes = Buffer.from(await upstream.arrayBuffer());
      requests.push({ endpoint, method: req.method, status: upstream.status, body: raw.length ? JSON.parse(raw.toString()) : null });
      if (drop && endpoint === '/v1/tasks/' + drop) { drop = null; req.socket.destroy(); return; }
      res.writeHead(upstream.status, { 'content-type': 'application/json', 'content-length': bytes.length }); res.end(bytes);
    } catch { res.writeHead(502); res.end(); }
  });
  const close = async () => { if (edge.listening) await new Promise(resolve => edge.close(resolve)); };
  try {
    await new Promise((resolve, reject) => { edge.once('error', reject); edge.listen(0, '127.0.0.1', resolve); });
    if (createProfile) await writeFile(profile, JSON.stringify({ url: 'http://127.0.0.1:' + edge.address().port + device.base,
      controller_token: controller.token, state: 'approved', persistent: true, all_apps: true, allow_control: true,
      session_expires_at: null, command_sequence: 0 }), { mode: 0o600 });
  } catch (error) { await close(); throw error; }
  // Only this independent Python process sees the shifted wall clock. The
  // production CLI, HTTP child, relay and system clock are otherwise unchanged.
  const clockShim = `import runpy,sys,time
offset=float(sys.argv.pop(1)); cli=sys.argv.pop(1); mode=sys.argv.pop(1)
real_time=time.time; time.time=lambda:real_time()+offset
sys.argv[0]=cli
if mode == 'invoke':
    runpy.run_path(cli,run_name='__main__')
else:
    import json
    namespace=runpy.run_path(cli,run_name='airtap_clock_probe')
    with open(sys.argv[1],encoding='utf8') as stream: config=json.load(stream)
    lease=config.get('task_lease',{})
    print(json.dumps({'live':namespace['task_live'](lease),'state':lease.get('state'),
        'remaining_ms':round((lease.get('local_expires_at',time.time())-time.time())*1000)}))
`;
  const run = (mode, args, input = null) => new Promise((resolve, reject) => {
    const child = spawn(process.env.PYTHON || 'python3', ['-c', clockShim, String(clockOffsetSeconds), cliPath, mode, ...args],
      { stdio: [input === null ? 'ignore' : 'pipe', 'pipe', 'pipe'] });
    if (input !== null) child.stdin.end(input);
    let out = '', err = ''; const timer = setTimeout(() => child.kill('SIGKILL'), 25000);
    child.stdout.on('data', bytes => out += bytes); child.stderr.on('data', bytes => err += bytes);
    child.on('error', error => { clearTimeout(timer); reject(error); });
    child.on('close', code => {
      clearTimeout(timer);
      try { assert.equal(err.includes(controller.token), false); assert.equal(err, ''); assert.equal(out.includes(controller.token), false); resolve({ code, value: JSON.parse(out) }); }
      catch (error) { reject(error); }
    });
  });
  return { requests, close, profile, url: 'http://127.0.0.1:' + edge.address().port + device.base, setDrop: operation => { drop = operation; },
    invoke: (command, args = [], input = null) => run('invoke', ['--allow-local-http', '--config', profile, command, ...args], input),
    probe: () => run('probe', [profile]), saved: async () => JSON.parse(await readFile(profile, 'utf8')) };
}
let d, a, b, p, tA, tB, invite;
try {
  await check('expired releasing with negative or absent ACK recovers without new consent or action replay', async () => {
    await Promise.all(['negative','ignore'].map(async ack => {
      const device=await enroll(),controller=await single(device);
      await ok(device.base+'/v1/device/multi/enable',device.token,migration(controller.claim));
      const original=await phone(device),task=await ok(device.base+'/v1/tasks/acquire',controller.token,{task_id:hex(16)});
      await ok(device.base+'/v1/command',controller.token,command(1,task));original.ack=ack;
      const closed=new Promise(resolve=>original.ws.addEventListener('close',resolve,{once:true}));
      // Keep the real DO warm with ordinary status reads through the actual
      // 120-second lease. Then stop requests: release retries/recovery must be
      // driven by its alarm, not by another controller asking to take over.
      let releasing;
      do {
        await new Promise(resolve=>setTimeout(resolve,Math.max(1,Math.min(5000,task.expires_at_ms+100-Date.now()))));
        releasing=await ok(device.base+'/v1/status',controller.token);
      } while(Date.now()<task.expires_at_ms);
      assert.equal(releasing.task.state,'fencing');assert.equal(releasing.task.expires_in_ms,0);assert.equal(releasing.task.lease_id,null);
      assert.equal(releasing.command_sequence,1);assert.equal(releasing.pending,0);
      const freshId=hex(16);
      await denied(device.base+'/v1/tasks/acquire',controller.token,{task_id:freshId},429,'device_busy');
      let timeout;
      try { await Promise.race([closed,new Promise((resolve,reject)=>{timeout=setTimeout(()=>reject(new Error('release did not recover in its bounded window')),40000);})]); }
      finally { clearTimeout(timeout); }
      const stopped=await ok(device.base+'/v1/status',controller.token);
      assert.equal(stopped.task.state,'idle');assert.equal(stopped.deviceConnected,false);assert.equal(stopped.command_sequence,1);
      assert.equal((await ok(device.base+'/v1/pair/status',controller.token)).state,'approved');
      const replacement=await phone(device,true,'negative');
      await denied(device.base+'/v1/command',controller.token,command(2,task),410,'lease_expired');
      // A still-running native action is represented by the real phone protocol's negative ACK.
      await denied(device.base+'/v1/tasks/acquire',controller.token,{task_id:freshId},503,'task_fence_pending');
      const freshFence=replacement.messages.at(-1);assert.ok(freshFence.lease_epoch>task.lease_epoch);
      const newLease={lease_id:freshFence.holder.lease_id,lease_epoch:freshFence.lease_epoch};
      await denied(device.base+'/v1/command',controller.token,command(2,newLease),503,'task_fence_pending');
      assert.equal(replacement.messages.filter(message=>message.type==='command').length,0);
      assert.equal((await ok(device.base+'/v1/status',controller.token)).command_sequence,1);
      replacement.ack='success';const ready=await ok(device.base+'/v1/tasks/acquire',controller.token,{task_id:freshId});
      await ok(device.base+'/v1/command',controller.token,command(2,ready));
      assert.equal(replacement.messages.filter(message=>message.type==='command').length,1);
      assert.equal(original.messages.filter(message=>message.type==='command').length,1);
      await ok(device.base+'/v1/tasks/release',controller.token,leaseBody(ready));
      await stopAll(device);
    }));
  },'fence-recovery');
  for (const approveFirst of [false, true]) await check('delegated approval respects inviter revocation: ' + (approveFirst ? 'completed child survives exact recovery' : 'revoked inviter cannot approve'), async () => {
    const device = await enroll(), actor = await single(device);
    await ok(device.base + '/v1/device/multi/enable', device.token, migration(actor.claim));
    await phone(device);
    const task = await ok(device.base + '/v1/tasks/acquire', actor.token, { task_id: hex(16) });
    await ok(device.base + '/v1/tasks/release', actor.token, leaseBody(task));
    const invitation = { invitation_id: hex(16), invitation_sequence: 1, ticket: hex(16) };
    await ok(device.base + '/v1/device/invitations', device.token, invitation);
    const child = { token: hex(32), name: 'Synthetic delegated AI' };
    child.claim = (await ok(device.base + '/v1/pair', null, { ticket: invitation.ticket, controller_token: child.token, name: child.name, capabilities: ['task_lease_v1'] })).claim_id;
    const delegated = { ...approval(child.claim), delegated_by: actor.claim };
    const before = await ok(device.base + '/v1/device/grants', device.token);
    for (const invalid of [null, 42, '', 'A'.repeat(32), 'a'.repeat(64)])
      await denied(device.base + '/v1/device/grants/approve', device.token, { ...delegated, delegated_by: invalid }, 400, 'invalid_approval');
    await denied(device.base + '/v1/device/grants/approve', device.token, { ...delegated, delegator_approval_id: hex(16) }, 400, 'invalid_approval');
    assert.deepEqual(await ok(device.base + '/v1/device/grants', device.token), before);
    if (approveFirst) await ok(device.base + '/v1/device/grants/approve', device.token, delegated);
    // This is the controller's normal revoke route, not an owner-side fixture.
    await ok(device.base + '/v1/revoke', actor.token, undefined, 'POST');
    if (approveFirst) {
      const completed = await ok(device.base + '/v1/device/grants', device.token);
      assert.equal(completed.grants.length, 1); assert.equal(completed.grants[0].claim_id, child.claim);
      await ok(device.base + '/v1/device/grants/approve', device.token, delegated);
      assert.deepEqual(await ok(device.base + '/v1/device/grants', device.token), completed);
      await denied(device.base + '/v1/device/grants/approve', device.token, { ...delegated, delegated_by: hex(16) }, 409, 'approval_delegation_conflict');
      assert.equal((await ok(device.base + '/v1/pair/status', child.token)).state, 'approved');
    } else {
      const rejected = await denied(device.base + '/v1/device/grants/approve', device.token, delegated, 403, 'delegator_revoked');
      assert.equal(rejected.admitted, false);
      const stopped = await ok(device.base + '/v1/device/grants', device.token);
      assert.equal(stopped.grants.length, 0);
      assert.equal(stopped.invitation.candidate.claim_id, child.claim);
      assert.equal((await ok(device.base + '/v1/pair/status', child.token)).state, 'pending');
      await denied(device.base + '/v1/status', child.token, undefined, 403, 'phone_approval_required');
    }
  });
  for (const approveFirst of [false, true]) await check('pending grant cancellation prevents late approval: ' + (approveFirst ? 'approve then revoke' : 'revoke then approve'), async () => {
    const device = await enroll(), original = await single(device);
    await ok(device.base + '/v1/device/multi/enable', device.token, migration(original.claim));
    await phone(device);
    const lease = await ok(device.base + '/v1/tasks/acquire', original.token, { task_id: hex(16) });
    await ok(device.base + '/v1/command', original.token, command(1, lease));
    const invitation = { invitation_id: hex(16), invitation_sequence: 1, ticket: hex(16) };
    await ok(device.base + '/v1/device/invitations', device.token, invitation);
    const candidate = { token: hex(32), name: 'Synthetic canceled candidate' };
    const pairBody = { ticket: invitation.ticket, controller_token: candidate.token, name: candidate.name, capabilities: ['task_lease_v1'] };
    candidate.claim = (await ok(device.base + '/v1/pair', null, pairBody)).claim_id;
    const originalBefore = (await ok(device.base + '/v1/device/grants', device.token)).grants[0];
    if (approveFirst) await ok(device.base + '/v1/device/grants/approve', device.token, approval(candidate.claim));
    await ok(device.base + '/v1/device/grants/revoke', device.token, { claim_id: candidate.claim });
    const canceled = await ok(device.base + '/v1/device/grants', device.token);
    assert.equal(canceled.invitation, null);
    assert.equal(canceled.invitation_sequence, 1);
    assert.deepEqual(canceled.grants, [originalBefore]);
    await denied(device.base + '/v1/device/grants/approve', device.token, approval(candidate.claim), 409, 'stale_claim');
    await denied(device.base + '/v1/pair', null, pairBody, 401, 'invalid_or_consumed_ticket');
    await denied(device.base + '/v1/device/invitations', device.token, invitation, 409, 'stale_invitation');
    // An old targeted retry and late approval cannot cancel a different invitation.
    const next = { invitation_id: hex(16), invitation_sequence: 2, ticket: hex(16) };
    await ok(device.base + '/v1/device/invitations', device.token, next);
    const newer = await ok(device.base + '/v1/pair', null, { ticket: next.ticket, controller_token: hex(32), name: 'Synthetic later candidate', capabilities: ['task_lease_v1'] });
    const beforeRetry = await ok(device.base + '/v1/device/grants', device.token);
    await ok(device.base + '/v1/device/grants/revoke', device.token, { claim_id: candidate.claim });
    await denied(device.base + '/v1/device/grants/approve', device.token, approval(candidate.claim), 409, 'stale_claim');
    assert.deepEqual(await ok(device.base + '/v1/device/grants', device.token), beforeRetry);
    await ok(device.base + '/v1/device/grants/approve', device.token, approval(newer.claim_id));
    const complete = await ok(device.base + '/v1/device/grants', device.token);
    assert.deepEqual(complete.grants.find(grant => grant.claim_id === original.claim), originalBefore);
    assert.ok(complete.grants.some(grant => grant.claim_id === newer.claim_id));
    assert.equal(complete.grants.some(grant => grant.claim_id === candidate.claim), false);
    await ok(device.base + '/v1/tasks/release', original.token, leaseBody(lease));
  });
  await check('remote connection management uses full grants, leases and exact command shapes', async () => {
    const device = await enroll(), controller = await single(device);
    const singlePhone = await phone(device, false);
    await ok(device.base + '/v1/command', controller.token, { ...command(1), action: 'connection_invite' });
    assert.equal(singlePhone.messages.at(-1).action, 'connection_invite');
    await denied(device.base + '/v1/command', controller.token, { ...command(2), action: 'connection_status' }, 409, 'multi_api_required');
    await ok(device.base + '/v1/device/multi/enable', device.token, migration(controller.claim));
    const fakePhone = await phone(device);
    // Even the one migrated grant must acquire a task for connection management.
    await denied(device.base + '/v1/command', controller.token, { ...command(2), action: 'connection_invite' }, 409, 'lease_required');
    const lease = await ok(device.base + '/v1/tasks/acquire', controller.token, { task_id: hex(16) });
    const invitationId = hex(16), candidate = hex(16);
    let sequence = 2;
    for (const fields of [
      { action: 'connection_status' }, { action: 'connection_invite' },
      { action: 'connection_approve', invitation_id: invitationId, claim_id: candidate, verification_code: '012345' },
      { action: 'connection_cancel', invitation_id: invitationId }, { action: 'connection_revoke', claim_id: candidate },
    ]) {
      const body = { ...command(sequence++, lease), ...fields };
      await ok(device.base + '/v1/command', controller.token, body);
      const envelope = fakePhone.messages.at(-1);
      assert.equal(envelope.claim_id, controller.claim);
      const { lease_id, lease_epoch, ...raw } = body;
      assert.deepEqual(envelope.command, raw);
      await denied(device.base + '/v1/command', controller.token, body, 409, 'sequence_already_admitted');
    }
    for (const changed of [
      { verification_code: 123456 }, { verification_code: '12345' }, { verification_code: '1234567' },
      { invitation_id: 'not-an-id' }, { claim_id: 'A'.repeat(32) }, { owner_token: hex(32) },
    ]) await denied(device.base + '/v1/command', controller.token,
      { ...command(sequence, lease), action: 'connection_approve', invitation_id: invitationId, claim_id: candidate, verification_code: '012345', ...changed },
      400, 'invalid_command');
    const before = fakePhone.messages.length;
    const unauthenticated = await request(device.base + '/v1/command', hex(32), { ...command(sequence, lease), action: 'connection_invite' });
    assert.equal(unauthenticated.status, 401);
    const deviceOnly = await request(device.base + '/v1/device/grants/approve', controller.token, approval(candidate));
    assert.equal(deviceOnly.status, 401);
    assert.equal(fakePhone.messages.length, before, 'unapproved credentials never reach Android');
    await ok(device.base + '/v1/tasks/release', controller.token, leaseBody(lease));
    await denied(device.base + '/v1/command', controller.token, { ...command(sequence, lease), action: 'connection_status' }, 410, 'lease_expired');
  });
  await check('CLI and MCP expose remote connection management without implicit screen actions', async () => {
    const device = await enroll(), controller = await single(device);
    await ok(device.base + '/v1/device/multi/enable', device.token, migration(controller.claim));
    const fakePhone = await phone(device), flow = await cliLoopback(device, controller, { profileName: 'remote-connections.json' });
    const rpc = (method, params) => ok(device.base + '/mcp', controller.token, { jsonrpc: '2.0', id: 1, method, ...(params ? { params } : {}) });
    try {
      const listed = (await rpc('tools/list')).result.tools;
      for (const action of ['connection_status', 'connection_invite', 'connection_approve', 'connection_cancel', 'connection_revoke']) {
        const spec = listed.find(tool => tool.name === 'phone_' + action);
        assert.ok(spec, action);
        for (const field of ['sequence', 'lease_id', 'lease_epoch']) assert.ok(spec.inputSchema.required.includes(field));
      }
      assert.equal(listed.find(tool => tool.name === 'phone_ping').annotations.readOnlyHint, true);
      assert.equal(listed.find(tool => tool.name === 'phone_battery_status').annotations.readOnlyHint, true);
      assert.equal((await flow.invoke('task-acquire')).code, 0);
      let saved = await flow.saved();
      assert.equal(saved.task_needs_observation, true);
      const invitation = hex(16), claim = hex(16);
      for (const [name, args, input] of [
        ['connection-status', [], null], ['connection-invite', [], null], ['battery-status', [], null],
        ['connection-approve', ['--invitation-id', invitation, '--claim-id', claim, '--code-stdin'], '012345\n'],
        ['connection-cancel', ['--invitation-id', invitation], null],
        ['connection-revoke', ['--claim-id', claim], null],
      ]) {
        const before = fakePhone.messages.filter(m => m.type === 'command').length;
        const result = await flow.invoke(name, args, input); assert.equal(result.code, 0, JSON.stringify(result.value));
        const commands = fakePhone.messages.filter(m => m.type === 'command');
        assert.equal(commands.length, before + 1);
        assert.equal(commands.at(-1).command.action, name.replaceAll('-', '_'));
      }
      saved = await flow.saved();
      assert.equal(saved.task_needs_observation, true);
      assert.equal(JSON.stringify(saved).includes('012345'), false);
      const lease = saved.task_lease;
      const status = await ok(device.base + '/v1/status', controller.token);
      const ping = await rpc('tools/call', { name: 'phone_ping', arguments: { sequence: status.command_sequence + 1, lease_id: lease.lease_id, lease_epoch: lease.lease_epoch } });
      assert.equal(ping.result.isError, false);
      assert.equal(fakePhone.messages.at(-1).command.action, 'ping');
      const current = await ok(device.base + '/v1/status', controller.token);
      const management = await rpc('tools/call', { name: 'phone_connection_status', arguments: { sequence: current.command_sequence + 1, lease_id: lease.lease_id, lease_epoch: lease.lease_epoch } });
      assert.equal(management.result.isError, false);
      assert.equal(fakePhone.messages.at(-1).command.action, 'connection_status');
      assert.equal((await flow.invoke('task-release')).code, 0);
    } finally { await flow.close(); }
  });
  {
    const trace = { ok: false, scope: 'independent production CLI processes and local production workerd with synthetic phone; no real credential store, cloud runtime, Android or personal-AI acceptance', cases: [] };
    try {
      for (const otherOwner of [false, true]) await check('credential restore requires a fresh task ' + (otherOwner ? 'after another claim and a lost acquire reply' : 'instead of adopting the same claim old lease'), async () => {
        const result = { other_claim_holds_old_lease: otherOwner, ok: false }; trace.cases.push(result);
        const x = await enroll(), controller = await single(x);
        await ok(x.base + '/v1/device/multi/enable', x.token, migration(controller.claim));
        const phoneFixture = await phone(x);
        let holder = controller;
        if (otherOwner) {
          // The migrated grant must first demonstrate task-lease support before
          // the production relay permits adding another independent grant.
          const migratedTask = await ok(x.base + '/v1/tasks/acquire', controller.token, { task_id: hex(16) });
          await ok(x.base + '/v1/tasks/release', controller.token, leaseBody(migratedTask));
          const invitation = { invitation_id: hex(16), invitation_sequence: 1, ticket: hex(16) };
          await ok(x.base + '/v1/device/invitations', x.token, invitation);
          holder = { token: hex(32), name: 'Synthetic prior holder' };
          holder.claim = (await ok(x.base + '/v1/pair', null, { ticket: invitation.ticket, controller_token: holder.token, name: holder.name, capabilities: ['task_lease_v1'] })).claim_id;
          await ok(x.base + '/v1/device/grants/approve', x.token, approval(holder.claim));
        }
        let original, restored;
        try {
          original = await cliLoopback(x, holder, { profileName: 'restore-original-' + otherOwner + '.json' });
          const acquired = await original.invoke('task-acquire'); assert.equal(acquired.code, 0);
          const oldLease = (await original.saved()).task_lease;
          restored = await cliLoopback(x, controller, { profileName: 'restore-new-' + otherOwner + '.json', createProfile: false });
          await assert.rejects(readFile(restored.profile), { code: 'ENOENT' });
          const messagesBeforeRestore = phoneFixture.messages.length;
          const restoredResult = await restored.invoke('restore', ['--credential-stdin'], JSON.stringify({ website: restored.url, username: controller.claim, password: controller.token }));
          assert.equal(restoredResult.code, 0, JSON.stringify(restoredResult.value));
          assert.equal(phoneFixture.messages.length, messagesBeforeRestore, 'restore must not send a phone command or fence');
          assert.ok(restored.requests.length > 0);
          assert.ok(restored.requests.every(r => r.method === 'GET' && ['/v1/pair/status', '/v1/status'].includes(r.endpoint)), 'restore may only verify the existing grant');
          assert.deepEqual(new Set(restored.requests.map(r => r.endpoint)), new Set(['/v1/pair/status', '/v1/status']), 'verify both the approved identity and the multi-AI state');
          const saved = await restored.saved();
          assert.equal(saved.url, restored.url); assert.equal(saved.controller_token, controller.token);
          assert.equal(saved.credential_claim_id, controller.claim); assert.equal(saved.state, 'approved'); assert.equal(saved.mode, 'multi');
          assert.equal(saved.restore_fresh_task_required, true);
          assert.equal((await stat(restored.profile)).mode & 0o777, 0o600);
          for (const key of ['task_lease', 'snapshot', 'observation']) assert.equal(Object.hasOwn(saved, key), false);
          for (const commandName of ['status', 'pair-status']) {
            const status = await restored.invoke(commandName); assert.equal(status.code, 0);
            const after = await restored.saved(); assert.equal(after.restore_fresh_task_required, true); assert.equal(Object.hasOwn(after, 'task_lease'), false);
          }
          const beforeBlocked = restored.requests.length;
          for (const commandName of ['ping', 'task-release']) {
            const blocked = await restored.invoke(commandName); assert.equal(blocked.code, 1);
          }
          assert.ok(restored.requests.slice(beforeBlocked).every(r => r.method === 'GET'), 'restore barrier must not command, acquire or release implicitly');
          assert.equal(phoneFixture.messages.length, messagesBeforeRestore);
          const busy = await restored.invoke('task-acquire'); assert.equal(busy.code, 1);
          const firstAcquire = restored.requests.filter(r => r.endpoint === '/v1/tasks/acquire');
          assert.equal(firstAcquire.length, 1); assert.equal(firstAcquire[0].status, 429);
          const acquiring = await restored.saved();
          assert.equal(acquiring.restore_fresh_task_required, true); assert.equal(acquiring.task_lease.state, 'acquiring');
          const newIntent = acquiring.task_lease.task_id;
          assert.notEqual(newIntent, oldLease.task_id); assert.equal(firstAcquire[0].body.task_id, newIntent);
          const stillHeld = await ok(x.base + '/v1/status', holder.token);
          assert.equal(stillHeld.task.lease_id, oldLease.lease_id); assert.equal(stillHeld.task.task_id, oldLease.task_id);
          assert.equal((await original.invoke('task-release')).code, 0);
          assert.equal((await restored.invoke('status')).code, 0);
          assert.equal((await restored.saved()).restore_fresh_task_required, true);
          assert.equal((await restored.saved()).task_lease.task_id, newIntent);
          if (otherOwner) {
            restored.setDrop('acquire');
            const lost = await restored.invoke('task-acquire'); assert.equal(lost.code, 1);
            const remoteReady = await ok(x.base + '/v1/status', controller.token);
            assert.equal(remoteReady.task.state, 'ready'); assert.equal(remoteReady.task.task_id, newIntent);
            assert.equal((await restored.invoke('status')).code, 0);
            const uncertain = await restored.saved(); assert.equal(uncertain.restore_fresh_task_required, true); assert.equal(uncertain.task_lease.state, 'acquiring');
            const beforePing = restored.requests.length; assert.equal((await restored.invoke('ping')).code, 1);
            assert.ok(restored.requests.slice(beforePing).every(r => r.method === 'GET'));
          }
          const ready = await restored.invoke('task-acquire'); assert.equal(ready.code, 0, JSON.stringify(ready.value));
          const active = await restored.saved();
          assert.notEqual(active.restore_fresh_task_required, true); assert.equal(active.task_lease.state, 'ready');
          assert.equal(active.task_lease.task_id, newIntent); assert.ok(active.task_lease.lease_epoch > oldLease.lease_epoch);
          const acquireRequests = restored.requests.filter(r => r.endpoint === '/v1/tasks/acquire');
          assert.equal(acquireRequests.length, otherOwner ? 3 : 2);
          assert.ok(acquireRequests.every(r => r.body.task_id === newIntent));
          assert.equal((await restored.invoke('ping')).code, 0);
          assert.equal((await restored.invoke('task-release')).code, 0);
          const final = await ok(x.base + '/v1/status', controller.token); assert.equal(final.task.state, 'idle'); assert.equal(final.pending, 0);
          assert.equal((await restored.saved()).controller_token, controller.token);
          assert.equal(phoneFixture.messages.filter(m => m.type === 'command').length, 1);
          assert.equal(restored.requests.filter(r => r.endpoint === '/v1/command').length, 1);
          assert.equal(restored.requests.filter(r => r.endpoint === '/v1/tasks/release').length, 1);
          assert.ok(restored.requests.every(r => ['/v1/pair/status', '/v1/status', '/v1/tasks/acquire', '/v1/tasks/release', '/v1/command'].includes(r.endpoint)), 'no pairing, approval, revocation or other API route');
          Object.assign(result, { ok: true, restore_get_only: true, fresh_profile_mode: '0600', old_lease_not_adopted_or_released: true, old_task_id_not_reused: true, busy_acquire_status: 429, lost_acquire_reply_checked: otherOwner, ready_status_did_not_clear_barrier: true, same_acquire_intent_preserved: true, acquire_posts: acquireRequests.length, phone_commands: 1, final_task_state: final.task.state, final_pending: final.pending });
        } catch (error) { result.failure = error.message; throw error; }
        finally { if (restored) await restored.close(); if (original) await original.close(); }
      }, 'credential-restore');
      trace.ok = true;
    } finally {
      if (option('--restore-output')) await writeFile(option('--restore-output'), JSON.stringify(trace, null, 2) + '\n', { mode: 0o600 });
    }
  }
  await check('old single wire and sequence remain unchanged', async () => {
    d = await enroll(); a = await single(d); p = await phone(d, false);
    await ok(d.base + '/v1/command', a.token, command(7));
    assert.equal(p.messages.at(-1).action, 'ping'); assert.equal(p.messages.at(-1).type, undefined);
    assert.equal((await ok(d.base + '/v1/status', a.token)).command_sequence, 7);
  });
  await check('old single owner stop retains empty-body behavior', async () => {
    const legacy=await enroll();const controller=await single(legacy);await phone(legacy,false);
    assert.equal((await ok(legacy.base+'/v1/device/revoke',legacy.token,undefined,'POST')).ok,true);
    await denied(legacy.base+'/v1/status',controller.token,undefined,410,'expired_or_revoked');
  });
  await check('delayed migration and claim-scoped stop cannot change a later single pairing', async () => {
    const x=await enroll(), original=await single(x), delayed=migration(original.claim);
    await ok(x.base+'/v1/device/revoke',x.token,undefined,'POST');
    const later=await single(x), currentPhone=await phone(x,false);
    await ok(x.base+'/v1/command',later.token,command(1));
    const before=await ok(x.base+'/v1/device/pairing',x.token);
    await denied(x.base+'/v1/device/multi/enable',x.token,{capabilities:caps},400,'expected_claim_required');
    await denied(x.base+'/v1/device/multi/enable',x.token,delayed,409,'stale_claim');
    const stopped=await ok(x.base+'/v1/device/grants/revoke',x.token,{claim_id:original.claim});
    assert.equal(stopped.ok,true);assert.equal(stopped.device_stop_confirmed,false);
    assert.deepEqual(await ok(x.base+'/v1/device/pairing',x.token),before);
    const status=await ok(x.base+'/v1/status',later.token);assert.equal(status.mode,undefined);assert.equal(status.deviceConnected,true);assert.equal(status.command_sequence,1);
    await ok(x.base+'/v1/command',later.token,command(2));
    assert.equal(currentPhone.messages.at(-1).action,'ping');assert.equal(currentPhone.messages.at(-1).type,undefined);
    assert.equal((await ok(x.base+'/v1/status',later.token)).command_sequence,2);
  });
  await check('claim-scoped owner stop revokes the matching single and exact retry is harmless', async () => {
    const x=await enroll(), controller=await single(x);await phone(x,false);
    await denied(x.base+'/v1/device/grants/revoke',controller.token,{claim_id:controller.claim},401,'unauthorized');
    await denied(x.base+'/v1/device/grants/revoke',x.token,{},400,'invalid_request');
    assert.equal((await ok(x.base+'/v1/device/grants/revoke',x.token,{claim_id:controller.claim})).ok,true);
    const stopped=await ok(x.base+'/v1/device/pairing',x.token);
    await denied(x.base+'/v1/status',controller.token,undefined,410,'expired_or_revoked');
    await denied(x.base+'/v1/device/multi/enable',x.token,migration(controller.claim),409,'stale_claim');
    await ok(x.base+'/v1/device/grants/revoke',x.token,{claim_id:controller.claim});
    assert.deepEqual(await ok(x.base+'/v1/device/pairing',x.token),stopped);
  });
  await check('event reads retain claim isolation and do not acquire or steal another AI lease',async()=>{
    const x=await enroll(),first=await single(x);await ok(x.base+'/v1/device/multi/enable',x.token,migration(first.claim));const device=await phone(x);
    const task=await ok(x.base+'/v1/tasks/acquire',first.token,{task_id:hex(16)});
    const ticket=hex(16);await ok(x.base+'/v1/device/invitations',x.token,{invitation_id:hex(16),invitation_sequence:1,ticket});
    const other={token:hex(32)};other.claim=(await ok(x.base+'/v1/pair',null,{ticket,controller_token:other.token,name:'Event consumer',capabilities:['task_lease_v1']})).claim_id;
    await ok(x.base+'/v1/device/grants/approve',x.token,approval(other.claim));
    const read={id:crypto.randomUUID(),action:'event_read',sequence:1,epoch:'',after:0,limit:16};
    await ok(x.base+'/v1/command',other.token,read);assert.equal(device.messages.at(-1).claim_id,other.claim);assert.equal(device.messages.at(-1).lease_id,null);
    assert.equal((await ok(x.base+'/v1/status',first.token)).task.lease_id,task.lease_id);
    await denied(x.base+'/v1/command',other.token,{...read,id:crypto.randomUUID(),sequence:2,lease_id:task.lease_id,lease_epoch:task.lease_epoch},400,'invalid_command');
    await denied(x.base+'/v1/command',other.token,{...read,id:crypto.randomUUID(),sequence:2,claim_id:first.claim},400,'invalid_command');
    await ok(x.base+'/v1/device/grants/revoke',x.token,{claim_id:other.claim});
    await denied(x.base+'/v1/command',other.token,{...read,id:crypto.randomUUID(),sequence:2},401,'unauthorized');device.ws.close();
  });
  await check('explicit migration preserves token claim sequence and blocks second invitation before handshake', async () => {
    const s = await ok(d.base + '/v1/device/multi/enable', d.token, migration(a.claim));
    assert.equal(s.grants.length, 1); assert.equal(s.grants[0].claim_id, a.claim); assert.equal(s.grants[0].command_sequence, 7); assert.equal(s.grants[0].lease_required, false);
    assert.equal((await ok(d.base + '/v1/pair/status', a.token)).state, 'approved');
    assert.deepEqual(await ok(d.base + '/v1/device/multi/enable', d.token, migration(a.claim)), s);
    assert.deepEqual(await ok(d.base + '/v1/device/multi/enable', d.token, { capabilities: caps }), s);
    await denied(d.base + '/v1/device/invitations', d.token, { invitation_id: hex(16), invitation_sequence: 1, ticket: hex(16) }, 409, 'client_upgrade_required');
    await denied(d.base + '/v1/device/pairing', d.token, undefined, 409, 'multi_api_required', 'POST');
    await denied(d.base + '/v1/device/approve', d.token, { claim_id: a.claim, allow_tap: false }, 409, 'multi_api_required');
    p = await phone(d);
    await ok(d.base + '/v1/command', a.token, command(8));
    const last = p.messages.at(-1); assert.equal(last.claim_id, a.claim); assert.equal(last.lease_id, null); assert.equal(last.command.sequence, 8);
  });
  await check('acquire ACK creates lease and permanently closes unleased path', async () => {
    const body = { task_id: hex(16) };
    tA = await ok(d.base + '/v1/tasks/acquire', a.token, body);
    assert.equal(tA.renewal_sequence, 0);
    const retry = await ok(d.base + '/v1/tasks/acquire', a.token, body);
    assert.equal(retry.lease_id, tA.lease_id); assert.equal(retry.expires_at_ms, tA.expires_at_ms);
    await denied(d.base + '/v1/command', a.token, command(9), 409, 'lease_required');
    await ok(d.base + '/v1/command', a.token, command(9, tA));
    const s = await ok(d.base + '/v1/status', a.token); assert.equal(s.lease_ready, true); assert.equal(s.task.renewal_sequence, 0);
  });
  await check('independent invitation opt-in approval leaves A task and credentials intact', async () => {
    invite = { invitation_id: hex(16), invitation_sequence: 1, ticket: hex(16) };
    const r = await ok(d.base + '/v1/device/invitations', d.token, invite);
    assert.deepEqual(await ok(d.base + '/v1/device/invitations', d.token, invite), r);
    b = { token: hex(32), name: 'Synthetic B' }; const body = { ticket: invite.ticket, controller_token: b.token, name: b.name };
    await denied(d.base + '/v1/pair', null, body, 409, 'client_upgrade_required');
    const pair = await ok(d.base + '/v1/pair', null, { ...body, capabilities: ['task_lease_v1'] }); b.claim = pair.claim_id;
    assert.deepEqual(await ok(d.base + '/v1/pair', null, { ...body, capabilities: ['task_lease_v1'] }), pair);
    await denied(d.base + '/v1/device/grants/approve', d.token, approval(hex(16)), 409, 'stale_claim');
    assert.equal((await ok(d.base + '/v1/status', a.token)).task.lease_id, tA.lease_id);
    await ok(d.base + '/v1/device/grants/approve', d.token, approval(b.claim));
    const old = await ok(d.base + '/v1/status', a.token); assert.equal(old.command_sequence, 9); assert.equal(old.task.lease_id, tA.lease_id);
    const bstatus = await ok(d.base + '/v1/status', b.token); assert.equal(bstatus.command_sequence, 0); assert.equal(bstatus.task.state, 'busy');
    for (const [key, value] of Object.entries(bstatus.task)) if (key !== 'state') assert.equal(value, null);
  });
  await check('same-phone feedback remains private to each independent grant', async () => {
    const report = { report_id: crypto.randomUUID(), title: 'Synthetic isolation', description: 'Test only.', phase: 'observe', screen_state: 'ready' };
    const created = await request(d.base + '/v1/reports', a.token, report); assert.equal(created.status, 201, JSON.stringify(created));
    assert.equal((await ok(d.base + '/v1/reports/' + report.report_id, a.token)).report.description, report.description);
    await denied(d.base + '/v1/reports/' + report.report_id, b.token, undefined, 404, 'report_not_found');
    assert.equal((await ok(d.base + '/v1/device/reports/' + report.report_id, d.token)).report.description, report.description);
  });
  await check('other AI and forged lease cannot consume sequence or operate', async () => {
    await denied(d.base + '/v1/tasks/acquire', b.token, { task_id: hex(16) }, 429, 'device_busy');
    const bad = await denied(d.base + '/v1/command', b.token, command(1, tA), 409, 'lease_mismatch'); assert.equal(bad.admitted, false);
    assert.equal((await ok(d.base + '/v1/status', b.token)).command_sequence, 0);
  });
  await check('renewal watermark prevents old retry extending later lease', async () => {
    const one = { ...leaseBody(tA), renewal_id: hex(16), renewal_sequence: 1 };
    const r1 = await ok(d.base + '/v1/tasks/renew', a.token, one);
    assert.equal((await ok(d.base + '/v1/tasks/renew', a.token, one)).expires_at_ms, r1.expires_at_ms);
    const r2 = await ok(d.base + '/v1/tasks/renew', a.token, { ...one, renewal_id: hex(16), renewal_sequence: 2 });
    assert.equal(r2.renewal_sequence, 2);
    await denied(d.base + '/v1/tasks/renew', a.token, one, 409, 'stale_renewal');
    const s = await ok(d.base + '/v1/status', a.token); assert.equal(s.task.renewal_sequence, 2); assert.equal(s.task.expires_at_ms, r2.expires_at_ms); tA = r2;
  });
  await check('negative fence ACK stays fenced and exact retry resolves without new intent', async () => {
    p.ack = 'negative'; const body = { ...leaseBody(tA), renewal_id: hex(16), renewal_sequence: 3 };
    await denied(d.base + '/v1/tasks/renew', a.token, body, 503, 'task_fence_pending');
    const first = p.messages.at(-1); assert.equal(first.type, 'task_fence');
    const s = await ok(d.base + '/v1/status', a.token); assert.equal(s.task.state, 'fencing'); assert.equal(s.task.lease_id, null);
    await denied(d.base + '/v1/command', a.token, command(10, tA), 503, 'task_fence_pending');
    p.ack = 'success'; tA = await ok(d.base + '/v1/tasks/renew', a.token, body);
    assert.equal(p.messages.at(-1).operation_id, first.operation_id);
    assert.equal(tA.renewal_sequence, 3);
  });
  await check('lost fence ACK never yields ready; retry uses original deadline and operation', async () => {
    p.ack = 'ignore'; const body = { ...leaseBody(tA), renewal_id: hex(16), renewal_sequence: 4 };
    await denied(d.base + '/v1/tasks/renew', a.token, body, 503, 'task_fence_pending');
    const fence = p.messages.at(-1), status = await ok(d.base + '/v1/status', a.token);
    assert.equal(status.task.state, 'fencing'); assert.equal(status.task.lease_id, null);
    p.ack = 'success'; tA = await ok(d.base + '/v1/tasks/renew', a.token, body);
    assert.equal(tA.expires_at_ms, status.task.expires_at_ms); assert.equal(p.messages.at(-1).operation_id, fence.operation_id);
    assert.equal(p.messages.at(-1).renewal_sequence, 4);
  });
  await check('MCP exposes task tools and per-controller sequence', async () => {
    const rpc = async (token, method, params) => ok(d.base + '/mcp', token, { jsonrpc: '2.0', id: 1, method, ...(params ? { params } : {}) });
    assert.match((await rpc(a.token, 'initialize')).result.instructions, /phone_task_acquire/);
    const tools = (await rpc(a.token, 'tools/list')).result.tools;
    assert.equal(tools.filter(t => t.name.startsWith('phone_task_')).length, 3);
    assert.ok(tools.find(t => t.name === 'phone_observe').inputSchema.properties.lease_id);
    const status = await rpc(b.token, 'tools/call', { name: 'phone_status', arguments: {} });
    assert.equal(JSON.parse(status.result.content[0].text).next_sequence, 1);
    const released = await rpc(a.token, 'tools/call', { name: 'phone_task_release', arguments: leaseBody(tA) });
    assert.equal(JSON.parse(released.result.content[0].text).state, 'released');
    await ok(d.base + '/v1/tasks/release', a.token, leaseBody(tA));
    await denied(d.base + '/v1/tasks/acquire', a.token, { task_id: tA.task_id }, 409, 'task_closed');
  });
  await check('handoff uses higher fence and independent sequence; stale A cannot act', async () => {
    tB = await ok(d.base + '/v1/tasks/acquire', b.token, { task_id: hex(16) }); assert.ok(tB.lease_epoch > tA.lease_epoch);
    await ok(d.base + '/v1/command', b.token, command(1, tB));
    await denied(d.base + '/v1/command', a.token, command(100, tA), 409, 'lease_mismatch');
    assert.equal((await ok(d.base + '/v1/status', a.token)).command_sequence, 9);
    await ok(d.base + '/v1/device/grants/revoke', d.token, { claim_id: a.claim });
    await denied(d.base + '/v1/status', a.token, undefined, 401, 'unauthorized');
    const before=await ok(d.base+'/v1/device/grants',d.token);
    await denied(d.base+'/v1/device/multi/enable',d.token,migration(a.claim),409,'stale_claim');
    assert.deepEqual(await ok(d.base+'/v1/device/multi/enable',d.token,{capabilities:caps}),before);
    await ok(d.base + '/v1/command', b.token, command(2, tB));
    assert.equal((await ok(d.base + '/v1/status', b.token)).task.lease_id, tB.lease_id);
  });
  await check('invitation cancellation watermark rejects replay beyond bounded tombstones', async () => {
    for (let sequence = 2; sequence <= 36; sequence++) {
      const i = { invitation_id: hex(16), invitation_sequence: sequence, ticket: hex(16) };
      await ok(d.base + '/v1/device/invitations', d.token, i);
      await ok(d.base + '/v1/device/invitations/cancel', d.token, { invitation_id: i.invitation_id, invitation_sequence: sequence });
    }
    await denied(d.base + '/v1/device/invitations', d.token, invite, 409, 'stale_invitation');
    assert.equal((await ok(d.base + '/v1/device/grants', d.token)).invitation_sequence, 36);
  });
  await check('connection replacement invalidates old lease without revoking pairing', async () => {
    const previous = p; p = await phone(d);
    const s = await ok(d.base + '/v1/status', b.token); assert.equal(s.task.state, 'idle'); assert.equal(s.command_sequence, 2); assert.equal(s.deviceConnected, true);
    await denied(d.base + '/v1/command', b.token, command(3, tB), 410, 'lease_expired');
    const next = await ok(d.base + '/v1/tasks/acquire', b.token, { task_id: hex(16) }); assert.ok(next.lease_epoch > tB.lease_epoch); tB = next;
    await ok(d.base + '/v1/command', b.token, command(3, tB));
    assert.equal(previous.messages.filter(m => m.type === 'command').at(-1).command.sequence, 2);
  });
  await check('in-flight response lost on connection replacement stays unknown and is never replayed', async () => {
    p.commands='hold'; let reached; const sent=new Promise(resolve=>{reached=resolve;});p.onCommand=reached;
    const payload=command(4,tB), pending=request(d.base+'/v1/command',b.token,payload);await sent;
    p=await phone(d);const result=await pending;assert.equal(result.status,503);assert.equal(result.data.admitted,undefined);
    assert.match(result.data.error,/outcome_unknown/);
    const s=await ok(d.base+'/v1/status',b.token);assert.equal(s.command_sequence,4);assert.equal(s.task.state,'idle');
    tB=await ok(d.base+'/v1/tasks/acquire',b.token,{task_id:hex(16)});await ok(d.base+'/v1/command',b.token,command(5,tB));
    assert.equal(p.messages.some(m=>m.command?.id===payload.id),false);
  });
  await check('cold workerd restart persists grants sequence and invitation watermark but invalidates old task', async () => {
    await mf.dispose(); await start();
    const s = await ok(d.base + '/v1/status', b.token); assert.equal(s.command_sequence, 5); assert.equal(s.task.state, 'idle'); assert.equal(s.deviceConnected, false);
    assert.equal((await ok(d.base + '/v1/device/grants', d.token)).invitation_sequence, 36);
    await denied(d.base + '/v1/device/invitations', d.token, invite, 409, 'stale_invitation');
    p = await phone(d); await denied(d.base + '/v1/command', b.token, command(4, tB), 410, 'lease_expired');
    const next = await ok(d.base + '/v1/tasks/acquire', b.token, { task_id: hex(16) }); assert.ok(next.lease_epoch > tB.lease_epoch); tB = next;
  });
  await check('revoke task holder confirms stop and all-stop retains invitation watermark', async () => {
    const r = await ok(d.base + '/v1/device/grants/revoke', d.token, { claim_id: b.claim }); assert.equal(r.device_stop_confirmed, true);
    await denied(d.base + '/v1/status', b.token, undefined, 401, 'unauthorized');
    await stopAll(d);
    const s = await ok(d.base + '/v1/device/grants', d.token); assert.equal(s.grants.length, 0); assert.equal(s.invitation_sequence, 36);
    await denied(d.base + '/v1/device/invitations', d.token, invite, 409, 'stale_invitation');
  });
  await check('all-stop retries cannot revoke newly approved grants or bypass generation body', async () => {
    const generation=(await ok(d.base+'/v1/device/grants',d.token)).generation;
    await denied(d.base+'/v1/device/revoke',d.token,undefined,415,'json_required','POST');
    await denied(d.base+'/v1/device/revoke',d.token,{},400,'invalid_request');
    await denied(d.base+'/v1/device/revoke',d.token,{expected_generation:generation+1},409,'generation_mismatch');
    const i={invitation_id:hex(16),invitation_sequence:37,ticket:hex(16)},fresh={token:hex(32),name:'After stop'};
    await ok(d.base+'/v1/device/invitations',d.token,i);
    fresh.claim=(await ok(d.base+'/v1/pair',null,{ticket:i.ticket,controller_token:fresh.token,name:fresh.name,capabilities:['task_lease_v1']})).claim_id;
    await ok(d.base+'/v1/device/grants/approve',d.token,approval(fresh.claim));p=await phone(d);
    const task=await ok(d.base+'/v1/tasks/acquire',fresh.token,{task_id:hex(16)});
    const before=await ok(d.base+'/v1/device/grants',d.token);
    const retry=await ok(d.base+'/v1/device/revoke',d.token,{expected_generation:generation-1});
    assert.equal(retry.generation,generation);assert.deepEqual(await ok(d.base+'/v1/device/grants',d.token),before);
    await ok(d.base+'/v1/command',fresh.token,command(1,task));
  });
  await check('terminal unenrollment cannot re-enable multi mode or restore old grants', async () => {
    await ok(d.base+'/v1/device/unenroll',d.token,undefined,'POST');
    await denied(d.base+'/v1/device/multi/enable',d.token,{capabilities:caps},410,'device_unenrolled');
    await denied(d.base+'/v1/status',b.token,undefined,410,'device_unenrolled');
    await ok(d.base+'/v1/device/unenroll',d.token,undefined,'POST');
    await denied('/v1/enroll',null,{device_id:d.id,device_token:d.token},410,'device_unenrolled');
  });
  await check('idle waiting and pending single states reject migration without losing the original invitation', async () => {
    const x = await enroll();
    const idle=await ok(x.base+'/v1/device/pairing',x.token);
    await denied(x.base+'/v1/device/multi/enable',x.token,migration(hex(16)),409,'stale_claim');
    assert.deepEqual(await ok(x.base+'/v1/device/pairing',x.token),idle);
    const i = await ok(x.base + '/v1/device/pairing', x.token, undefined, 'POST');
    const waiting=await ok(x.base+'/v1/device/pairing',x.token);
    await denied(x.base+'/v1/device/multi/enable',x.token,migration(hex(16)),409,'stale_claim');
    assert.deepEqual(await ok(x.base+'/v1/device/pairing',x.token),waiting);
    const body = { ticket: i.ticket, controller_token: hex(32), name: 'Pending legacy' };
    const c = await ok(x.base + '/v1/pair', null, body);
    const pending=await ok(x.base+'/v1/device/pairing',x.token);
    await denied(x.base+'/v1/device/multi/enable',x.token,migration(c.claim_id),409,'stale_claim');
    assert.deepEqual(await ok(x.base+'/v1/device/pairing',x.token),pending);
    assert.equal((await ok(x.base + '/v1/pair', null, body)).claim_id, c.claim_id);
    await ok(x.base + '/v1/device/approve', x.token, approval(c.claim_id));await phone(x,false);
    await ok(x.base+'/v1/command',body.controller_token,command(1));
    const status = await ok(x.base + '/v1/status', body.controller_token); assert.equal(status.mode, undefined); assert.equal(status.command_sequence, 1);
  });
  await check('four-grant limit and simultaneous task acquisition remain bounded', async () => {
    const x=await enroll(), first=await single(x), clients=[first];
    await ok(x.base+'/v1/device/multi/enable',x.token,migration(first.claim)); await phone(x);
    const ready=async c=>{const t=await ok(x.base+'/v1/tasks/acquire',c.token,{task_id:hex(16)});await ok(x.base+'/v1/tasks/release',c.token,leaseBody(t));};
    await ready(first);
    for(let n=1;n<4;n++) {
      const i={invitation_id:hex(16),invitation_sequence:n,ticket:hex(16)}, c={token:hex(32),name:'Synthetic '+n};
      await ok(x.base+'/v1/device/invitations',x.token,i);
      c.claim=(await ok(x.base+'/v1/pair',null,{ticket:i.ticket,controller_token:c.token,name:c.name,capabilities:['task_lease_v1']})).claim_id;
      await ok(x.base+'/v1/device/grants/approve',x.token,approval(c.claim)); clients.push(c); await ready(c);
    }
    await denied(x.base+'/v1/device/invitations',x.token,{invitation_id:hex(16),invitation_sequence:4,ticket:hex(16)},409,'grant_limit_reached');
    const race=await Promise.all(clients.map(c=>request(x.base+'/v1/tasks/acquire',c.token,{task_id:hex(16)})));
    assert.equal(race.filter(r=>r.status===200).length,1);assert.equal(race.filter(r=>r.data.error==='device_busy').length,3);
    const winner=race.findIndex(r=>r.status===200);await ok(x.base+'/v1/tasks/release',clients[winner].token,leaseBody(race[winner].data));
    await stopAll(x);
  });
  await check('real CLI processes recover lost acquire and renewal over production relay without re-pairing', async () => {
    const x=await enroll(), c=await single(x);await ok(x.base+'/v1/device/multi/enable',x.token,migration(c.claim));const phoneFixture=await phone(x);
    const fixture=await cliLoopback(x,c,{profileName:'cli-private.json',drop:'acquire'}), {invoke}=fixture;
    try {
      assert.equal((await invoke('task-acquire')).code,1);const ready=await invoke('task-acquire');assert.equal(ready.code,0,JSON.stringify(ready.value));
      assert.equal(fixture.requests.filter(r=>r.endpoint==='/v1/tasks/acquire').length,1);
      assert.equal((await invoke('ping')).code,0);fixture.setDrop('renew');assert.equal((await invoke('task-renew')).code,1);assert.equal((await invoke('task-renew')).code,0);
      const renewalBodies=fixture.requests.filter(r=>r.endpoint==='/v1/tasks/renew').map(r=>r.body);
      assert.deepEqual(renewalBodies[0],renewalBodies[1]);assert.equal(renewalBodies[0].renewal_sequence,1);
      assert.equal((await invoke('ping')).code,0);assert.equal((await invoke('task-release')).value.state,'released');
      const saved=await fixture.saved();assert.equal(saved.controller_token,c.token);assert.equal(saved.command_sequence,2);assert.equal(saved.state,'approved');
      assert.equal(phoneFixture.messages.filter(m=>m.type==='command').length,2);
    }finally{await fixture.close();}
  });
  await check('real CLI processes keep status-reconciled leases live with clocks 180 seconds ahead and behind', async () => {
    const trace={ok:false,scope:'local production CLI/workerd with synthetic phone; no system clock change or personal AI',cases:[]}, failures=[];
    const privateTask=task=>Object.entries(task).every(([key,value])=>key==='state'||value===null);
    try {
      for (const offset of [-180,180]) {
        const result={clock_offset_seconds:offset,ok:false};trace.cases.push(result);
        let fixture;
        try {
          const x=await enroll(), c=await single(x);
          await ok(x.base+'/v1/device/multi/enable',x.token,migration(c.claim));const phoneFixture=await phone(x);
          const idle=await ok(x.base+'/v1/status',c.token);
          fixture=await cliLoopback(x,c,{profileName:'cli-clock-'+offset+'.json',clockOffsetSeconds:offset});
          const acquired=await fixture.invoke('task-acquire'), before=await fixture.saved(), beforeProbe=await fixture.probe();
          const invitation={invitation_id:hex(16),invitation_sequence:1,ticket:hex(16)},other={token:hex(32),name:'Synthetic observer'};
          await ok(x.base+'/v1/device/invitations',x.token,invitation);
          other.claim=(await ok(x.base+'/v1/pair',null,{ticket:invitation.ticket,controller_token:other.token,name:other.name,capabilities:['task_lease_v1']})).claim_id;
          await ok(x.base+'/v1/device/grants/approve',x.token,approval(other.claim));
          const busy=await ok(x.base+'/v1/status',other.token);
          const status=await fixture.invoke('status'), after=await fixture.saved(), afterProbe=await fixture.probe();
          const ping=await fixture.invoke('ping'), released=await fixture.invoke('task-release'), saved=await fixture.saved();
          const final=await ok(x.base+'/v1/status',c.token);
          Object.assign(result,{acquire_exit:acquired.code,status_exit:status.code,
            before_status:beforeProbe.value,after_status:afterProbe.value,
            status_relative_ttl_ms:status.value.task?.expires_in_ms??null,
            ping_exit:ping.code,ping_error:ping.value.error??null,release_exit:released.code,
            release_state:released.value.state,final_task_state:final.task.state,
            idle_metadata_private:privateTask(idle.task),other_ai_busy_metadata_private:privateTask(busy.task),
            final_idle_metadata_private:privateTask(final.task),
            local_deadline_not_extended:after.task_lease.local_expires_at<=before.task_lease.local_expires_at,
            task_identity_preserved:['task_id','lease_id','lease_epoch'].every(key=>after.task_lease[key]===before.task_lease[key]),
            acquire_posts:fixture.requests.filter(r=>r.endpoint==='/v1/tasks/acquire').length,
            renewal_posts:fixture.requests.filter(r=>r.endpoint==='/v1/tasks/renew').length,
            phone_commands:phoneFixture.messages.filter(m=>m.type==='command').length,
            final_command_sequence:saved.command_sequence,local_task_state:saved.task_lease.state});
          assert.equal(acquired.code,0);assert.equal(status.code,0);assert.equal(beforeProbe.code,0);assert.equal(afterProbe.code,0);
          assert.equal(beforeProbe.value.live,true);assert.equal(afterProbe.value.live,true,'status must not expire a fresh lease under clock skew');
          assert.ok(afterProbe.value.remaining_ms>0&&afterProbe.value.remaining_ms<=120000);
          assert.equal(ping.code,0,JSON.stringify(ping.value));assert.equal(released.code,0);assert.equal(released.value.state,'released');
          assert.equal(result.local_deadline_not_extended,true);assert.equal(result.task_identity_preserved,true);
          assert.ok(Number.isSafeInteger(status.value.task.expires_in_ms));assert.ok(status.value.task.expires_in_ms>0&&status.value.task.expires_in_ms<=120000);
          assert.equal(idle.task.state,'idle');assert.equal(busy.task.state,'busy');assert.equal(final.task.state,'idle');
          for(const task of [idle.task,busy.task,final.task]){assert.equal(task.expires_in_ms,null);assert.equal(privateTask(task),true);}
          assert.equal(result.acquire_posts,1);assert.equal(result.renewal_posts,0);assert.equal(result.phone_commands,1);
          assert.equal(saved.controller_token,c.token);assert.equal(saved.state,'approved');assert.equal(saved.command_sequence,1);assert.equal(saved.task_lease.state,'closed');
          result.ok=true;
        } catch(error) { result.failure=error.message;failures.push({clock_offset_seconds:offset,error:error.message}); }
        finally { if(fixture) await fixture.close(); }
      }
      trace.ok=failures.length===0;
    } finally {
      if(option('--clock-skew-output')) await writeFile(option('--clock-skew-output'),JSON.stringify(trace,null,2)+'\n',{mode:0o600});
    }
    assert.deepEqual(failures,[],'clock-skew cases failed; inspect --clock-skew-output for both offsets');
  });
  // Optional real-clock acceptance: keep ordinary core checks fast. No real
  // device/config, explicit renewal, shortened TTL or background keeper is used.
  if (process.argv.includes('--active-lease-window')) await check('real CLI processes automatically renew one active task across two TTL windows', async () => {
    const started = performance.now(), cliPath = join(root, 'cli/airtap.py');
    const digest = bytes => createHash('sha256').update(bytes).digest('hex');
    const trace = { ok: false, scope: 'local production CLI/relay, synthetic phone ACK; not Android or personal-AI acceptance',
      cli_sha256: digest(await readFile(cliPath)), events: [] };
    const record = values => {
      const event = { elapsed_ms: Math.round(performance.now() - started), ...values };
      trace.events.push(event); console.log(JSON.stringify({ active_lease: event }));
    };
    const x = await enroll(), c = await single(x);
    await ok(x.base + '/v1/device/multi/enable', x.token, migration(c.claim));
    const phoneFixture = await phone(x), profile = join(temp, 'cli-active-lease.json');
    let original;
    const sameIdentity = lease => !!original && ['task_id', 'lease_id', 'lease_epoch'].every(key => lease?.[key] === original[key]);
    const savedState = async () => {
      const saved = JSON.parse(await readFile(profile, 'utf8')), lease = saved.task_lease ?? {};
      return { task_state: lease.state ?? null, same_identity: sameIdentity(lease),
        remaining_ms: Number.isFinite(lease.local_expires_at) ? Math.round(lease.local_expires_at * 1000 - Date.now()) : null,
        renewal_sequence: lease.renewal_sequence ?? null, pending_renewal: !!lease.pending_renewal,
        command_sequence: saved.command_sequence, needs_observation: saved.task_needs_observation === true };
    };
    const requests = [];
    const edge = createServer(async (req, res) => {
      const endpoint = req.url.slice(x.base.length);
      try {
        const chunks = []; for await (const bytes of req) chunks.push(bytes);
        const raw = Buffer.concat(chunks), body = raw.length ? JSON.parse(raw.toString()) : {};
        const upstream = await mf.dispatchFetch('http://localhost' + req.url, { method: req.method,
          headers: { ...req.headers, 'cf-connecting-ip': '127.0.0.1' }, ...(raw.length ? { body: raw } : {}) });
        const bytes = Buffer.from(await upstream.arrayBuffer()), reply = JSON.parse(bytes.toString());
        const event = { phase: 'http', endpoint, status: upstream.status,
          ...(body.action ? { action: body.action } : {}),
          ...(Number.isSafeInteger(body.renewal_sequence) ? { requested_renewal_sequence: body.renewal_sequence } : {}),
          ...(Number.isSafeInteger(reply.renewal_sequence) ? { confirmed_renewal_sequence: reply.renewal_sequence } : {}),
          ...(Number.isSafeInteger(reply.expires_in_ms) ? { expires_in_ms: reply.expires_in_ms } : {}),
          ...(Number.isSafeInteger(reply.expires_at_ms) ? { expires_at_ms: reply.expires_at_ms } : {}),
          ...(typeof reply.error === 'string' && /^[a-z_]+$/.test(reply.error) ? { error: reply.error } : {}) };
        requests.push(event); record(event);
        res.writeHead(upstream.status, { 'content-type': 'application/json', 'content-length': bytes.length }); res.end(bytes);
      } catch { record({ phase: 'fixture_error', endpoint }); res.writeHead(502); res.end(); }
    });
    const invoke = async command => {
      record({ phase: 'before_cli', command, ...await savedState() });
      const result = await new Promise((resolve, reject) => {
        const child = spawn(process.env.PYTHON || 'python3', [cliPath, '--allow-local-http', '--config', profile, command],
          { stdio: ['ignore', 'pipe', 'pipe'] });
        let out = '', err = ''; const timer = setTimeout(() => child.kill('SIGKILL'), 60000);
        child.stdout.on('data', bytes => out += bytes); child.stderr.on('data', bytes => err += bytes);
        child.on('error', error => { clearTimeout(timer); reject(error); });
        child.on('close', code => {
          clearTimeout(timer);
          try { assert.equal(err, ''); assert.equal(out.includes(c.token), false); resolve({ code, value: JSON.parse(out) }); }
          catch (error) { reject(error); }
        });
      });
      record({ phase: 'after_cli', command, exit: result.code, ...await savedState(),
        ...(result.value.error ? { error: result.value.error } : {}) });
      assert.equal(result.code, 0, result.value.error); return result.value;
    };
    try {
      await new Promise((resolve, reject) => { edge.once('error', reject); edge.listen(0, '127.0.0.1', resolve); });
      await writeFile(profile, JSON.stringify({ url: 'http://127.0.0.1:' + edge.address().port + x.base,
        controller_token: c.token, state: 'approved', persistent: true, all_apps: true, allow_control: true,
        session_expires_at: null, command_sequence: 0 }), { mode: 0o600 });
      original = await invoke('task-acquire');
      const activeStart = performance.now();
      for (let index = 0; index <= 10; index++) {
        await new Promise(resolve => setTimeout(resolve, Math.max(0, activeStart + index * 20000 - performance.now())));
        await invoke('ping');
        const state = await savedState();
        assert.equal(state.same_identity, true); assert.equal(state.task_state, 'ready');
        assert.equal(state.pending_renewal, false); assert.equal(state.command_sequence, index + 1);
      }
      await new Promise(resolve => setTimeout(resolve, Math.max(0, activeStart + 205000 - performance.now())));
      assert.equal((await invoke('task-release')).state, 'released');
      const status = await invoke('status');
      assert.equal(status.task.state, 'idle'); assert.equal(status.deviceConnected, true); assert.equal(status.pending, 0);
      const acquires = requests.filter(e => e.endpoint === '/v1/tasks/acquire');
      const renewals = requests.filter(e => e.endpoint === '/v1/tasks/renew');
      assert.equal(acquires.length, 1); assert.ok(renewals.length >= 2);
      let deadline = acquires[0].expires_at_ms;
      for (const [index, renewal] of renewals.entries()) {
        assert.equal(renewal.status, 200); assert.equal(renewal.requested_renewal_sequence, index + 1);
        assert.equal(renewal.confirmed_renewal_sequence, index + 1); assert.ok(renewal.expires_at_ms > deadline);
        deadline = renewal.expires_at_ms;
      }
      assert.equal(requests.filter(e => e.endpoint === '/v1/command').length, 11);
      assert.equal(requests.filter(e => e.endpoint === '/v1/tasks/release').length, 1);
      assert.equal(phoneFixture.messages.filter(e => e.type === 'command').length, 11);
      assert.equal(phoneFixture.messages.filter(e => e.type === 'task_fence' && e.holder && e.renewal_sequence > 0).length, renewals.length);
      const saved = JSON.parse(await readFile(profile, 'utf8'));
      assert.equal(saved.controller_token, c.token); assert.equal(saved.state, 'approved'); assert.equal(saved.task_lease.state, 'closed');
      assert.equal(digest(await readFile(cliPath)), trace.cli_sha256);
      Object.assign(trace, { ok: true, automatic_renewals: renewals.length, explicit_renewals: 0, commands: 11, task_acquires: 1 });
    } finally {
      if (edge.listening) await new Promise(resolve => edge.close(resolve));
      if (option('--lease-output')) await writeFile(option('--lease-output'), JSON.stringify(trace, null, 2) + '\n', { mode: 0o600 });
    }
  });
  await check('control pause cannot block cancellation release or independent/full revoke', async () => {
    const x=await enroll(), c=await single(x);await ok(x.base+'/v1/device/multi/enable',x.token,migration(c.claim));await phone(x);
    const t=await ok(x.base+'/v1/tasks/acquire',c.token,{task_id:hex(16)});
    const i={invitation_id:hex(16),invitation_sequence:1,ticket:hex(16)};await ok(x.base+'/v1/device/invitations',x.token,i);
    const stopGeneration=(await ok(x.base+'/v1/device/grants',x.token)).generation;
    bindings.CONTROL_OPEN='false';await mf.dispose();await start();
    await denied(x.base+'/v1/status',c.token,undefined,503,'control_closed');
    await ok(x.base+'/v1/tasks/release',c.token,leaseBody(t));
    await ok(x.base+'/v1/device/invitations/cancel',x.token,{invitation_id:i.invitation_id,invitation_sequence:1});
    await ok(x.base+'/v1/device/grants/revoke',x.token,{claim_id:c.claim});
    await ok(x.base+'/v1/device/revoke',x.token,{expected_generation:stopGeneration});
  });
  if (option('--output')) await writeFile(option('--output'), JSON.stringify({ ok: true, scope: 'local production relay synthetic roundtrip, not Android or personal-AI acceptance', checks }, null, 2) + '\n');
} finally { for (const ws of sockets) { try { ws.close(1000, 'test complete'); } catch {} } await mf.dispose(); await rm(temp, { recursive: true, force: true }); }
