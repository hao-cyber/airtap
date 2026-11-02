// Focused persistence/hibernation tests for the production class. Synthetic
// storage/socket adapters make crash boundaries deterministic; no test HTTP API.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { timingSafeEqual } from 'node:crypto';
const source = (await readFile(new URL('../../worker/worker.mjs', import.meta.url), 'utf8')).replace("import { DurableObject } from 'cloudflare:workers';", 'class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }');
crypto.subtle.timingSafeEqual ??= timingSafeEqual;
const { MultiPairingRelay } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const h = c => c.repeat(32), claim = h('a'), lease = h('b'), taskId = h('c'), connection = h('d'), operation = h('e');
const clock = () => Date.now();
const grant = () => ({ id: claim, hash: '1'.repeat(64), name: 'Synthetic', sequence: 41, seen: ['saved-command'], lease_required: true, lease_ready: true,
  scope: { persistent: true, allApps: true, allowControl: true, allowTap: false, allowedPackages: [] } });
function state() { return { version: 1, schema_version: 2, mode: 'multi', generation: 3, phase: 'multi', deviceHash: '2'.repeat(64),
  grants: { [claim]: grant() }, invitation: null, invitationSequence: 75, taskEpoch: 4, closedTasks: [],
  task: { claimId: claim, taskId, leaseId: lease, epoch: 4, state: 'ready', expiresAtMs: clock() + 100000,
    operationId: operation, renewalId: h('f'), renewalSequence: 7, connectionId: connection, fenceEpoch: 4, fenceTtlMs: 120000 } }; }
function socket(s) {
  let a = { version: 1, generation: s.generation, retired: false, helloReceived: true, multiHello: true, connectionId: connection, connectedAt: Math.floor(clock()/1000),
    pendingCommand: false, task: { leaseId: lease, epoch: 4, expiresAtMs: s.task.expiresAtMs, operationId: operation, renewalSequence: 7 } };
  return { readyState: 1, sent: [], deserializeAttachment: () => structuredClone(a), serializeAttachment: v => { a = structuredClone(v); },
    send(v) { this.sent.push(JSON.parse(v)); }, close() { this.readyState = 3; } };
}
class Relay extends MultiPairingRelay { protocolVersion() { return 4; } globalExpired() { return this.state.serviceRetired === true; } }
async function instance(s, ws, fail = () => false) {
  let saved = structuredClone(s), alarm;
  const ctx = { storage: { async get() { return structuredClone(saved); }, async put(k,v) { if (fail()) throw new Error('synthetic storage failure'); saved = structuredClone(v); },
    async setAlarm(n) { alarm = n; }, async deleteAlarm() { alarm = null; } },
    getWebSockets: () => ws ? [ws] : [], blockConcurrencyWhile(fn) { this.ready = Promise.resolve().then(fn); return this.ready; } };
  const r = new Relay(ctx, {}); await ctx.ready;
  return { r, saved: () => saved, alarm: () => alarm };
}
const body = () => ({ task_id: taskId, lease_id: lease, lease_epoch: 4 });
const migration = (expected = claim) => ({ capabilities: ['multi_ai_v1','task_lease_v1'], expected_claim_id: expected });
const singleState = (phase = 'approved') => ({ version: 1, generation: 3, phase, deviceHash: '2'.repeat(64),
  candidate: { id: claim, hash: '1'.repeat(64), name: 'Synthetic A', code: '123456' },
  persistent: true, allApps: true, allowControl: true, allowTap: false, allowedPackages: [],
  pairingExpires: Math.floor(clock()/1000)+300, ticketHash: '3'.repeat(64), sequence: 41, seen: ['saved-command'] });
async function error(response, code) { assert.equal((await response.json()).error, code); }

test('hibernation restores only exact ready lease/sequence on the same socket', async () => {
  const s = state(), ws = socket(s), {r, saved} = await instance(s, ws);
  assert.equal(r.connected(), true); assert.equal(r.state.task.renewalSequence, 7);
  assert.equal(saved().grants[claim].sequence, 41); assert.equal(saved().invitationSequence, 75);
  await error(await r.taskRequest('renew', {...body(), renewal_id:h('f'), renewal_sequence:6}, claim), 'stale_renewal');
});
test('status exposes bounded relative TTL only to the holder and never renews it', async t => {
  let now = Date.now(); t.mock.method(Date, 'now', () => now);
  const s = state(), ws = socket(s), {r, saved} = await instance(s, ws);
  const deadline = r.state.task.expiresAtMs, persisted = saved();
  assert.equal(r.multiStatus(grant()).task.expires_in_ms, 100000);
  now += 750;
  assert.equal(r.multiStatus(grant()).task.expires_in_ms, 99250);
  assert.equal(r.state.task.expiresAtMs, deadline);
  assert.deepEqual(saved(), persisted);
  const other = r.multiStatus({...grant(), id: h('9')}).task;
  assert.equal(other.state, 'busy');
  for (const key of ['task_id', 'lease_id', 'lease_epoch', 'expires_at_ms', 'expires_in_ms', 'renewal_sequence']) assert.equal(other[key], null);
  r.state.task.state = 'fencing';
  const fencing = r.multiStatus(grant()).task;
  assert.equal(fencing.state, 'fencing'); assert.equal(fencing.lease_id, null); assert.equal(fencing.expires_in_ms, 99250);
  now = deadline + 1; assert.equal(r.multiStatus(grant()).task.expires_in_ms, 0);
  now = deadline - 180000; assert.equal(r.multiStatus(grant()).task.expires_in_ms, 120000);
  r.state.task = null;
  assert.equal(r.multiStatus(grant()).task.state, 'idle');
  assert.equal(r.multiStatus(grant()).task.expires_in_ms, null);
});
for (const mutation of ['pending', 'missing', 'deadline', 'operation', 'renewal', 'fencing', 'expired']) test('uncertain restore retires transport: ' + mutation, async () => {
  const s = state(), ws = socket(s), a = ws.deserializeAttachment();
  if (mutation === 'pending') a.pendingCommand = true;
  if (mutation === 'missing') a.task = null;
  if (mutation === 'deadline') a.task.expiresAtMs++;
  if (mutation === 'operation') a.task.operationId = h('0');
  if (mutation === 'renewal') a.task.renewalSequence--;
  if (mutation === 'fencing') s.task.state = 'fencing';
  if (mutation === 'expired') s.task.expiresAtMs = clock()-1;
  ws.serializeAttachment(a);
  const {r, saved} = await instance(s, ws);
  assert.equal(r.connected(), false); assert.equal(saved().task, null); assert.equal(saved().closedTasks[0].leaseId, lease);
  assert.equal(saved().grants[claim].sequence, 41); assert.equal(ws.deserializeAttachment().retired, true);
});
test('legacy in-flight command also cannot restore an apparently idle channel', async () => {
  const s=state(), ws=socket(s); s.task=null; s.grants[claim].lease_required=false;
  ws.serializeAttachment({...ws.deserializeAttachment(), task:null, pendingCommand:true});
  const {r}=await instance(s,ws); assert.equal(r.connected(),false);
});
test('expiry waits for null-holder ACK; failed and late ACKs cannot permit handoff', async () => {
  const s = state(), ws = socket(s), {r,saved} = await instance(s, ws);
  r.state.task.expiresAtMs = clock()-1;
  await r.expire();
  assert.equal(saved().task.state, 'releasing'); assert.equal(saved().taskEpoch, 5);
  const f = ws.sent.at(-1); assert.equal(f.holder, null); assert.equal(f.renewal_sequence, 0); assert.equal(f.ttl_ms, 0);
  await error(await r.taskRequest('acquire', {task_id:h('0')}, claim), 'device_busy');
  await r.acknowledgeFence(ws,{operation_id:operation,lease_epoch:4,ok:true}); assert.equal(r.state.task.state,'releasing');
  await r.acknowledgeFence(ws,{operation_id:f.operation_id,lease_epoch:5,ok:false,error:'command_in_progress'}); assert.equal(r.state.task.state,'releasing');
  await r.acknowledgeFence(ws,{operation_id:f.operation_id,lease_epoch:5,ok:true}); assert.equal(saved().task,null);
  assert.equal(saved().grants[claim].sequence,41);
});
test('ACK persistence failure is never reported ready and fails closed on restart', async () => {
  const s=state(), ws=socket(s); let fail=false; const {r,saved}=await instance(s,ws,()=>fail);
  r.state.task.state='fencing'; r.state.task.operationId=h('0'); await r.persist(r.state); fail=true;
  await assert.rejects(r.acknowledgeFence(ws,{operation_id:h('0'),lease_epoch:4,ok:true}),/storage failure/);
  assert.equal(r.state.task.state,'fencing');
  const restarted=await instance(saved(),ws); assert.equal(restarted.r.connected(),false); assert.equal(restarted.saved().task,null);
});
test('repeated fence dispatch preserves duration and renewal watermark', async () => {
  const s=state(), ws=socket(s), {r}=await instance(s,ws);
  r.state.task.state='fencing'; r.sendFence(); r.sendFence();
  assert.deepEqual(ws.sent.at(-1),ws.sent.at(-2)); assert.equal(ws.sent.at(-1).renewal_sequence,7);
});
test('unknown schema cannot become a controllable migration', async () => {
  const s={...state(),schema_version:3}, ws=socket(s); const {r}=await instance(s,ws);
  await error(await r.enableMulti(migration()),'unsupported_state_schema'); assert.equal(r.connected(),false);
});

test('terminal retirement never exposes grants through re-enable', async () => {
  const s={...state(),serviceRetired:true}, ws=socket(s); const {r}=await instance(s,ws);
  await error(await r.enableMulti({capabilities:['multi_ai_v1','task_lease_v1']}),'device_unenrolled'); assert.equal(r.connected(),false);
});


test('release persistence failure retires uncertain in-flight transport', async () => {
  const s=state(), ws=socket(s); let fail=false; const {r,saved}=await instance(s,ws,()=>fail);
  ws.serializeAttachment({...ws.deserializeAttachment(),pendingCommand:true});
  let response;
  r.pending={id:h('0'),claimId:claim,leaseId:lease,epoch:4,resolve:v=>{response=v;},timer:null};
  fail=true;
  await assert.rejects(r.beginRelease(r.state.task),/storage failure/);
  assert.equal(r.connected(),false);
  assert.equal(ws.deserializeAttachment().retired,true);
  assert.equal(ws.deserializeAttachment().pendingCommand,true);
  await error(response,'task_release_storage_failure_outcome_unknown');
  assert.equal(saved().task.state,'ready'); // failed write did not invent durable fencing
  assert.equal(ws.sent.length,0); // no unrecorded fence/action dispatch
  const restarted=await instance(saved(),ws);
  assert.equal(restarted.r.connected(),false);
  assert.equal(restarted.saved().task,null);
});


test('ACK write crossing expiry never returns ready and requires clear fence', async t => {
  let time=Date.now(); t.mock.method(Date,'now',()=>time);
  const s=state(), ws=socket(s), {r}=await instance(s,ws);
  r.state.task.state='fencing';
  const persist=r.persist.bind(r);
  r.persist=async next=>{ if(next.task?.state==='ready')time=next.task.expiresAtMs; await persist(next); };
  let resolved;
  r.fenceWaiters=[{resolve:v=>{resolved=v;},timer:null}];
  await r.acknowledgeFence(ws,{operation_id:operation,lease_epoch:4,ok:true});
  assert.equal(r.state.task.state,'releasing');
  await error(resolved,'task_closed');
  const clear=ws.sent.at(-1); assert.equal(clear.holder,null); assert.equal(clear.lease_epoch,5);
  await error(await r.taskRequest('acquire',{task_id:h('0')},claim),'device_busy');
  await r.acknowledgeFence(ws,{operation_id:clear.operation_id,lease_epoch:5,ok:true});
  assert.equal(r.state.task,null);
});


test('generation-fenced all-stop leaves later grants intact on retry', async () => {
  const s=state(),ws=socket(s),{r,saved}=await instance(s,ws);
  await error(await r.revoke(), 'invalid_request');
  await error(await r.revoke({}), 'invalid_request');
  await error(await r.revoke({expected_generation:4}), 'generation_mismatch');
  assert.equal(r.connected(),true);
  assert.deepEqual(await (await r.revoke({expected_generation:3})).json(),{protocol_version:4,ok:true,generation:4});
  assert.equal(r.connected(),false);assert.deepEqual(saved().grants,{});assert.equal(saved().task,null);
  const later=h('0');await r.persist({...r.state,grants:{[later]:{...grant(),id:later}}});
  const before=structuredClone(saved());
  assert.equal((await (await r.revoke({expected_generation:3})).json()).generation,4);
  assert.deepEqual(saved(),before);
  const restarted=await instance(saved());
  assert.equal((await (await restarted.r.revoke({expected_generation:3})).json()).generation,4);
  assert.deepEqual(restarted.saved(),before);
});
test('all-stop failed write never advances durable receipt watermark', async () => {
  const s=state(),ws=socket(s);let fail=false;const {r,saved}=await instance(s,ws,()=>fail);
  fail=true;await assert.rejects(r.revoke({expected_generation:3}),/storage failure/);
  assert.equal(r.connected(),false);assert.equal(r.state.generation,3);assert.equal(saved().generation,3);
  fail=false;assert.equal((await (await r.revoke({expected_generation:3})).json()).generation,4);
  assert.equal(saved().generation,4);assert.deepEqual(saved().grants,{});
});

test('migration requires the same approved claim and preserves rejected single states', async () => {
  for (const phase of ['idle','waiting','pending','revoked','approved']) {
    const s=singleState(phase),{r,saved}=await instance(s);
    await error(await r.enableMulti({capabilities:migration().capabilities}),'expected_claim_required');
    await error(await r.enableMulti(migration(h('0'))),'stale_claim');
    if (phase!=='approved') await error(await r.enableMulti(migration()),'stale_claim');
    assert.deepEqual(saved(),s);assert.deepEqual(r.state,s);
  }
});
test('successful migration is durable before disconnect and exact retry is read-only', async () => {
  const s=singleState(),{r,saved}=await instance(s);let disconnects=0;
  r.disconnect=()=>{disconnects++;assert.equal(saved().mode,'multi');};
  const first=await (await r.enableMulti(migration())).json();
  assert.equal(first.mode,'multi');assert.equal(first.grants[0].claim_id,claim);assert.equal(first.grants[0].command_sequence,41);
  assert.deepEqual(saved().grants[claim].seen,['saved-command']);const before=structuredClone(saved());
  assert.deepEqual(await (await r.enableMulti(migration())).json(),first);
  assert.deepEqual(await (await r.enableMulti({capabilities:migration().capabilities})).json(),first);
  await error(await r.enableMulti(migration(h('0'))),'stale_claim');
  assert.deepEqual(saved(),before);assert.equal(disconnects,1);
});
test('migration persistence failure preserves single and never disconnects its transport', async () => {
  const s=singleState(),{r,saved}=await instance(s,null,()=>true);let disconnected=false;
  r.disconnect=()=>{disconnected=true;};
  await assert.rejects(r.enableMulti(migration()),/storage failure/);
  assert.deepEqual(saved(),s);assert.deepEqual(r.state,s);assert.equal(disconnected,false);
});
test('claim-scoped stop no-ops on a replacement single and never revokes it on restart', async () => {
  const s=singleState();s.candidate.id=h('0');const {r,saved}=await instance(s);
  assert.equal((await (await r.revokeSingleGrant(claim)).json()).ok,true);assert.deepEqual(saved(),s);
  const restarted=await instance(saved());
  assert.equal((await (await restarted.r.revokeSingleGrant(claim)).json()).ok,true);assert.deepEqual(restarted.saved(),s);
});
test('claim-scoped single stop is durable and cannot report success after a failed write', async () => {
  const s=singleState();let fail=true;const {r,saved}=await instance(s,null,()=>fail);let disconnected=0;
  r.disconnect=()=>{disconnected++;};
  await assert.rejects(r.revokeSingleGrant(claim),/storage failure/);
  assert.equal(saved().phase,'approved');assert.equal(r.state.phase,'approved');assert.equal(disconnected,1);
  fail=false;assert.equal((await (await r.revokeSingleGrant(claim)).json()).ok,true);
  assert.equal(saved().phase,'revoked');assert.equal(saved().generation,s.generation+1);
  const before=structuredClone(saved());assert.equal((await (await r.revokeSingleGrant(claim)).json()).ok,true);
  assert.deepEqual(saved(),before);assert.equal(disconnected,2);
  await error(await r.enableMulti(migration()),'stale_claim');assert.deepEqual(saved(),before);
});
