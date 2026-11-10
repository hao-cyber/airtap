// Focused persistence/hibernation tests for the production class. Synthetic
// storage/socket adapters make crash boundaries deterministic; no test HTTP API.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { timingSafeEqual } from 'node:crypto';
const source = (await readFile(new URL('../../worker/worker.mjs', import.meta.url), 'utf8')).replace("import { DurableObject } from 'cloudflare:workers';", 'class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }');
crypto.subtle.timingSafeEqual ??= timingSafeEqual;
const { MultiPairingRelay, hash, PAIRING_TTL_SECONDS } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
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
async function instance(s, ws, fail = () => false, env = {}) {
  let saved = structuredClone(s), alarm;
  const ctx = { storage: { async get() { return structuredClone(saved); }, async put(k,v) { if (fail()) throw new Error('synthetic storage failure'); saved = structuredClone(v); },
    async setAlarm(n) { alarm = n; }, async deleteAlarm() { alarm = null; } },
    getWebSockets: () => ws ? [ws] : [], blockConcurrencyWhile(fn) { this.ready = Promise.resolve().then(fn); return this.ready; } };
  const r = new Relay(ctx, env); await ctx.ready;
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
test('expired release retries have one durable deadline and retire an unconfirmed socket', async t => {
  let time=Date.now(); t.mock.method(Date,'now',()=>time);
  const s=state(),ws=socket(s),{r,saved,alarm}=await instance(s,ws);
  r.state.task.expiresAtMs=time-1;await r.expire();
  const releasing=structuredClone(saved().task),deadline=releasing.releaseDeadlineMs;
  assert.equal(deadline,time+30000);assert.equal(alarm(),time+5000);
  const first=ws.sent.at(-1);
  for(let index=0;index<5;index++) {
    time+=5000;
    await r.acknowledgeFence(ws,{operation_id:first.operation_id,lease_epoch:first.lease_epoch,ok:false,error:'command_in_progress'});
    await r.expire();
    assert.equal(saved().task.releaseDeadlineMs,deadline);
    assert.deepEqual(ws.sent.at(-1),first);
    assert.equal(r.multiStatus(grant()).task.expires_in_ms,0);
    assert.equal(alarm(),Math.min(time+5000,deadline));
    await error(await r.taskRequest('acquire',{task_id:h('0')},claim),'device_busy');
  }
  time=deadline;await r.expire();
  assert.equal(ws.deserializeAttachment().retired,true);assert.equal(r.connected(),false);
  assert.equal(saved().task,null);assert.equal(saved().closedTasks.at(-1).leaseId,lease);
  assert.equal(saved().grants[claim].sequence,41);assert.equal(saved().invitationSequence,75);
  assert.equal(alarm(),null);
  await r.acknowledgeFence(ws,{operation_id:first.operation_id,lease_epoch:first.lease_epoch,ok:true});
  assert.equal(saved().task,null);
  await error(await r.taskRequest('acquire',{task_id:h('0')},claim),'device_disconnected');
});
test('an existing releasing record gets one recovery deadline and cannot renew it through polls', async t => {
  let time=Date.now(); t.mock.method(Date,'now',()=>time);
  const s=state(),ws=socket(s),{r,saved,alarm}=await instance(s,ws);
  r.state.task={...r.state.task,state:'releasing',fenceEpoch:5,fenceTtlMs:0};
  r.state.taskEpoch=5;await r.persist(r.state);
  await r.expire();const deadline=saved().task.releaseDeadlineMs;
  assert.equal(deadline,time+30000);assert.equal(alarm(),time+5000);
  time+=29000;await r.expire();assert.equal(saved().task.releaseDeadlineMs,deadline);
  time+=1000;await r.expire();assert.equal(saved().task,null);assert.equal(r.connected(),false);
});
test('release recovery persistence failure retires old authority before any fresh handoff', async t => {
  let time=Date.now(); t.mock.method(Date,'now',()=>time);
  const s=state(),ws=socket(s);let fail=false;const {r,saved}=await instance(s,ws,()=>fail);
  r.state.task.expiresAtMs=time-1;await r.expire();time=saved().task.releaseDeadlineMs;
  fail=true;await assert.rejects(r.expire(),/storage failure/);
  assert.equal(r.connected(),false);assert.equal(ws.deserializeAttachment().retired,true);
  assert.equal(saved().task.state,'releasing');assert.equal(saved().grants[claim].sequence,41);
  fail=false;await r.expire();assert.equal(saved().task,null);
});
test('recovery never closes a still-valid ready task or skips the new phone ACK', async t => {
  let time=Date.now(); t.mock.method(Date,'now',()=>time);
  const s=state(),ws=socket(s),{r,saved}=await instance(s,ws);
  time+=60000;await r.expire();assert.equal(saved().task.state,'ready');assert.equal(r.connected(),true);
  await error(await r.taskRequest('acquire',{task_id:h('0')},claim),'device_busy');
  r.state.task.expiresAtMs=time-1;await r.expire();time=saved().task.releaseDeadlineMs;await r.expire();
  const replacement=socket(s);replacement.serializeAttachment({...replacement.deserializeAttachment(),connectionId:h('9'),task:null});
  r.device=replacement;
  const pending=r.taskRequest('acquire',{task_id:h('0')},claim);const result=await pending;
  const fence=replacement.sent.at(-1);assert.ok(fence.lease_epoch>5);
  await r.acknowledgeFence(replacement,{operation_id:fence.operation_id,lease_epoch:fence.lease_epoch,ok:false,error:'command_in_progress'});
  await error(await result.pendingResponse,'task_fence_pending');assert.equal(saved().task.state,'fencing');
  await r.acknowledgeFence(ws,{operation_id:fence.operation_id,lease_epoch:fence.lease_epoch,ok:true});
  assert.equal(saved().task.state,'fencing');
  const retry=await r.taskRequest('acquire',{task_id:h('0')},claim);
  await r.acknowledgeFence(replacement,{operation_id:fence.operation_id,lease_epoch:fence.lease_epoch,ok:true});
  assert.equal((await (await retry.pendingResponse).json()).state,'ready');assert.equal(saved().task.state,'ready');
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


test('single invitation and pending comparison remain valid past five minutes, then expire at one hour', async t => {
  let millis=Date.now();t.mock.method(Date,'now',()=>millis);const start=Math.floor(millis/1000);
  assert.equal(PAIRING_TTL_SECONDS,3600);
  const device='7'.repeat(64),controller='8'.repeat(64),s={...singleState('idle'),candidate:null,ticketHash:null,persistent:false,deviceHash:await hash(device)};
  const {r}=await instance(s,null,()=>false,{EXPIRES_AT:String(start+7200)});
  const request=new Request('https://synthetic.invalid/v1/device/pairing',{method:'POST'});
  const created=await (await r.handle('/v1/device/pairing',request,{},device,s.deviceHash)).json();
  assert.equal(created.pairing_expires_at,start+3600);
  millis=(start+301)*1000;await r.expire();assert.equal(r.state.phase,'waiting');
  const pair=await (await r.handle('/v1/pair',request,{ticket:created.ticket,controller_token:controller,name:'Synthetic AI'},null,null)).json();
  assert.equal(pair.state,'pending');assert.equal(pair.pairing_expires_at,created.pairing_expires_at);
  millis=(start+3599)*1000;await r.expire();assert.equal(r.state.phase,'pending');
  millis=(start+3600)*1000;await r.expire();assert.equal(r.state.phase,'revoked');
  const refused=await r.handle('/v1/device/approve',request,{claim_id:pair.claim_id,allow_tap:false,persistent:true,all_apps:true,allow_control:true,allowed_packages:[]},device,s.deviceHash);
  assert.notEqual(refused.status,200);
});
test('multi invitation retries retain a fixed one-hour expiry and expired candidate never creates a grant', async t => {
  let millis=Date.now();t.mock.method(Date,'now',()=>millis);const start=Math.floor(millis/1000);
  const s={...state(),task:null,grants:{}};
  const {r}=await instance(s,null,()=>false,{CLI_SHA256:'a'.repeat(64),AGENT_SHA256:'b'.repeat(64)});
  const body={invitation_id:h('4'),invitation_sequence:76,ticket:h('5')};
  const first=await (await r.invitation(body)).json();assert.equal(first.pairing_expires_at,start+3600);
  millis=(start+301)*1000;await r.expire();
  const retry=await (await r.invitation(body)).json();assert.equal(retry.pairing_expires_at,first.pairing_expires_at);
  const candidate=await (await r.pairMulti({ticket:body.ticket,controller_token:'9'.repeat(64),name:'Synthetic AI',capabilities:['task_lease_v1']})).json();
  assert.equal(candidate.state,'pending');assert.equal(candidate.pairing_expires_at,first.pairing_expires_at);
  millis=(start+3599)*1000;await r.expire();assert.ok(r.state.invitation?.candidate);
  millis=(start+3600)*1000;await r.expire();assert.equal(r.state.invitation,null);
  const denied=await r.approveMulti({claim_id:candidate.claim_id,allow_tap:false,persistent:true,all_apps:true,allow_control:true,allowed_packages:[]});
  assert.equal(denied.status,409);assert.equal(Object.keys(r.state.grants).length,0);
});

function delegatedState() {
  const s = { ...state(), task: null };
  s.invitation = { id: h('4'), sequence: 75, expiresAt: Math.floor(clock()/1000)+3600,
    origin: 'multi_ai_v1', ticketHash: '8'.repeat(64),
    candidate: { id: h('5'), hash: '9'.repeat(64), name: 'Synthetic child', code: '012345', capabilities: ['task_lease_v1'] } };
  return s;
}
const delegatedApproval = () => ({ claim_id: h('5'), delegated_by: claim, allow_tap: false,
  persistent: true, all_apps: true, allow_control: true, allowed_packages: [] });

test('delegation requires the current inviter full-phone grant without mutating refused state', async () => {
  for (const changed of [{persistent:false}, {allApps:false}, {allowControl:false}, {allowedPackages:['test.app']}]) {
    const s = delegatedState(); Object.assign(s.grants[claim].scope, changed);
    const {r, saved} = await instance(s, null), before = saved();
    const reply = await r.approveMulti(delegatedApproval());
    assert.equal(reply.status, 403); assert.deepEqual(await reply.json(), {error:'permission_denied',admitted:false,protocol_version:4});
    assert.deepEqual(saved(), before); assert.equal(Object.keys(r.state.grants).length, 1);
  }
});
test('completed delegated grant and exact recovery survive inviter removal and cold restore', async () => {
  const {r, saved} = await instance(delegatedState(), null);
  const before = structuredClone(r.state.grants[claim]);
  assert.equal((await r.approveMulti(delegatedApproval())).status, 200);
  assert.equal(saved().grants[h('5')].delegated_by, claim);
  assert.deepEqual(saved().grants[claim], before);
  await r.revokeGrant(claim);
  const completed = saved(), restored = await instance(completed, null);
  assert.equal((await restored.r.approveMulti(delegatedApproval())).status, 200);
  assert.deepEqual(restored.saved(), completed);
  await restored.r.revokeGrant(h('5'));
  const denied = await restored.r.approveMulti(delegatedApproval());
  assert.equal(denied.status, 403); await error(denied,'delegator_revoked');
  assert.deepEqual(restored.saved().grants, {});
});
test('failed delegated approval persistence cannot gain authority after inviter revocation', async () => {
  let failing = false;
  const {r, saved} = await instance(delegatedState(), null, () => failing);
  const before = saved(); failing = true;
  await assert.rejects(r.approveMulti(delegatedApproval()), /storage failure/);
  assert.deepEqual(saved(), before); assert.equal(r.state.grants[h('5')], undefined);
  failing = false; await r.revokeGrant(claim);
  const response = await r.approveMulti(delegatedApproval());
  assert.equal(response.status, 403); await error(response,'delegator_revoked');
  assert.equal(saved().grants[h('5')], undefined);
});
