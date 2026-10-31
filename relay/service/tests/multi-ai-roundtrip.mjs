#!/usr/bin/env node
// Real local workerd, production modules, synthetic identities and fake phone.
// No cloud service, personal AI, private config or physical device is contacted.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdtemp, mkdir, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
const option = k => process.argv.includes(k) ? process.argv[process.argv.indexOf(k) + 1] : undefined;
const require = createRequire(new URL('../../worker/package.json', import.meta.url));
const { Miniflare, convertV4MiniflareOptions } = await import(pathToFileURL(option('--miniflare') ?? require.resolve('miniflare')).href);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-multi-')));
await mkdir(join(temp, 'assets'));
const hex = n => randomBytes(n).toString('hex');
const bindings = { SERVICE_ID: hex(16), SERVICE_LABEL: 'Synthetic multi AI test', IP_HASH_SALT: hex(32), REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true',
  FEEDBACK_ADMIN_SHA256: 'c'.repeat(64), CLI_SHA256: 'a'.repeat(64), AGENT_SHA256: 'b'.repeat(64), ARTIFACT_VERSION: 'candidate', MAX_DEVICES: '20', DAILY_REGISTRATIONS: '20', IP_DAILY_REGISTRATIONS: '20' };
const options = { host: '127.0.0.1', port: 0, cf: false, durableObjectsPersist: join(temp, 'do'), resourcePersistencePath: join(temp, 'state'), workers: [{ name: 'multi-ai-test', modulesRoot: join(root, 'relay'),
  modules: await Promise.all(['service/service.mjs', 'worker/worker.mjs', 'service/feedback.mjs'].map(async f => ({ type: 'ESModule', path: join(root, 'relay', f), contents: await readFile(join(root, 'relay', f), 'utf8') }))),
  compatibilityDate: '2026-10-01', durableObjects: { ADMISSION: { className: 'AdmissionRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
  ratelimits: { INGRESS: { namespace_id: '3401', simple: { limit: 10000, period: 60 } } },
  assets: { directory: join(temp, 'assets'), binding: 'ASSETS', run_worker_first: true, routerConfig: { has_user_worker: true } }, bindings }] };
let mf;
async function start() { mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options); await mf.ready; }
await start();
const checks = [], sockets = [];
async function request(path, token, body, method) {
  const response = await mf.dispatchFetch('http://localhost' + path, { method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers: { 'cf-connecting-ip': '127.0.0.1', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  return { status: response.status, data: await response.json() };
}
async function ok(path, token, body, method) { const r = await request(path, token, body, method); assert.equal(r.status, 200, JSON.stringify(r)); return r.data; }
async function denied(path, token, body, status, error, method) { const r = await request(path, token, body, method); assert.equal(r.status, status, JSON.stringify(r)); assert.equal(r.data.error, error); return r.data; }
async function check(name, fn) { const started = performance.now(); await fn(); const r = { name, ok: true, elapsed_ms: Math.round(performance.now() - started) }; checks.push(r); console.log(JSON.stringify(r)); }
async function enroll() { const d = { id: hex(16), token: hex(32) }; d.base = '/d/' + d.id; await ok('/v1/enroll', null, { device_id: d.id, device_token: d.token }); return d; }
const caps = ['multi_ai_v1', 'task_lease_v1'];
const approval = id => ({ claim_id: id, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true });
async function stopAll(d) { const state=await ok(d.base+'/v1/device/grants',d.token);return ok(d.base+'/v1/device/revoke',d.token,{expected_generation:state.generation}); }
async function single(d) {
  const invite = await ok(d.base + '/v1/device/pairing', d.token, undefined, 'POST');
  const a = { token: hex(32), name: 'Synthetic A' };
  a.claim = (await ok(d.base + '/v1/pair', null, { ticket: invite.ticket, controller_token: a.token, name: a.name })).claim_id;
  await ok(d.base + '/v1/device/approve', d.token, approval(a.claim)); return a;
}
async function phone(d, multi = true) {
  const r = await mf.dispatchFetch('http://localhost' + d.base + '/v1/device', { headers: { 'cf-connecting-ip': '127.0.0.1', Authorization: 'Bearer ' + d.token, Upgrade: 'websocket' } });
  assert.equal(r.status, 101); const ws = r.webSocket; ws.accept(); sockets.push(ws);
  const p = { ws, messages: [], ack: 'success', commands: 'success' };
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
let d, a, b, p, tA, tB, invite;
try {
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
  await check('explicit migration preserves token claim sequence and blocks second invitation before handshake', async () => {
    const s = await ok(d.base + '/v1/device/multi/enable', d.token, { capabilities: caps });
    assert.equal(s.grants.length, 1); assert.equal(s.grants[0].claim_id, a.claim); assert.equal(s.grants[0].command_sequence, 7); assert.equal(s.grants[0].lease_required, false);
    assert.equal((await ok(d.base + '/v1/pair/status', a.token)).state, 'approved');
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
  await check('legacy pending invitation migrates once and still accepts exact original retry', async () => {
    const x = await enroll(), i = await ok(x.base + '/v1/device/pairing', x.token, undefined, 'POST');
    const body = { ticket: i.ticket, controller_token: hex(32), name: 'Pending legacy' };
    const c = await ok(x.base + '/v1/pair', null, body);
    const s = await ok(x.base + '/v1/device/multi/enable', x.token, { capabilities: caps }); assert.equal(s.invitation.pairing_expires_at, i.pairing_expires_at);
    assert.equal((await ok(x.base + '/v1/pair', null, body)).claim_id, c.claim_id);
    await ok(x.base + '/v1/device/grants/approve', x.token, approval(c.claim_id));
    const status = await ok(x.base + '/v1/status', body.controller_token); assert.equal(status.lease_required, false); assert.equal(status.command_sequence, 0);
  });
  await check('four-grant limit and simultaneous task acquisition remain bounded', async () => {
    const x=await enroll(), first=await single(x), clients=[first];
    await ok(x.base+'/v1/device/multi/enable',x.token,{capabilities:caps}); await phone(x);
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
    const x=await enroll(), c=await single(x);await ok(x.base+'/v1/device/multi/enable',x.token,{capabilities:caps});const phoneFixture=await phone(x);
    let drop='acquire', acquiredPosts=0;const renewalBodies=[];
    const edge=createServer(async(req,res)=>{try{
      const chunks=[];for await(const bytes of req) chunks.push(bytes);const raw=Buffer.concat(chunks);
      if(req.url.endsWith('/tasks/acquire'))acquiredPosts++;
      if(req.url.endsWith('/tasks/renew'))renewalBodies.push(JSON.parse(raw.toString()));
      const upstream=await mf.dispatchFetch('http://localhost'+req.url,{method:req.method,headers:{...req.headers,'cf-connecting-ip':'127.0.0.1'},...(raw.length?{body:raw}:{})});
      const bytes=Buffer.from(await upstream.arrayBuffer());
      if(drop&&req.url.endsWith('/tasks/'+drop)){drop=null;req.socket.destroy();return;}
      res.writeHead(upstream.status,{'content-type':'application/json','content-length':bytes.length});res.end(bytes);
    }catch{res.writeHead(502);res.end();}});
    await new Promise(resolve=>edge.listen(0,'127.0.0.1',resolve));const profile=join(temp,'cli-private.json');
    await writeFile(profile,JSON.stringify({url:'http://127.0.0.1:'+edge.address().port+x.base,controller_token:c.token,state:'approved',persistent:true,all_apps:true,allow_control:true,session_expires_at:null,command_sequence:0}),{mode:0o600});
    const invoke=command=>new Promise((resolve,reject)=>{const child=spawn(process.env.PYTHON||'python3',[join(root,'cli/airtap.py'),'--allow-local-http','--config',profile,command],{stdio:['ignore','pipe','pipe']});let out='',err='';const timer=setTimeout(()=>child.kill('SIGKILL'),25000);child.stdout.on('data',b=>out+=b);child.stderr.on('data',b=>err+=b);child.on('error',reject);child.on('close',code=>{clearTimeout(timer);try{assert.equal(err,'');assert.equal(out.includes(c.token),false);resolve({code,value:JSON.parse(out)});}catch(e){reject(e);}});});
    try {
      assert.equal((await invoke('task-acquire')).code,1);const ready=await invoke('task-acquire');assert.equal(ready.code,0,JSON.stringify(ready.value));assert.equal(acquiredPosts,1);
      assert.equal((await invoke('ping')).code,0);drop='renew';assert.equal((await invoke('task-renew')).code,1);assert.equal((await invoke('task-renew')).code,0);
      assert.deepEqual(renewalBodies[0],renewalBodies[1]);assert.equal(renewalBodies[0].renewal_sequence,1);
      assert.equal((await invoke('ping')).code,0);assert.equal((await invoke('task-release')).value.state,'released');
      const saved=JSON.parse(await readFile(profile,'utf8'));assert.equal(saved.controller_token,c.token);assert.equal(saved.command_sequence,2);assert.equal(saved.state,'approved');
      assert.equal(phoneFixture.messages.filter(m=>m.type==='command').length,2);
    }finally{await new Promise(resolve=>edge.close(resolve));}
  });
  await check('control pause cannot block cancellation release or independent/full revoke', async () => {
    const x=await enroll(), c=await single(x);await ok(x.base+'/v1/device/multi/enable',x.token,{capabilities:caps});await phone(x);
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
