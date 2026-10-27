#!/usr/bin/env node
// Real SQLite-backed workerd with a loopback-only fixture subclass. No test RPC
// is exported by the production worker and no deployed relay/device is used.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
const option = key => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : undefined;
const requireWorker = createRequire(new URL('../../worker/package.json', import.meta.url));
const { Miniflare, convertV4MiniflareOptions } = await import(pathToFileURL(option('--miniflare') ?? requireWorker.resolve('miniflare')).href);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const temp = await mkdtemp(join(tmpdir(), 'airtap-usage-'));
const id = '1'.padStart(32, '0'), hash = 'a'.repeat(64);
const entry = `import { AdmissionRegistry, ServiceDeviceRelay } from './service/service.mjs';
export { ServiceDeviceRelay };
export class FixtureRegistry extends AdmissionRegistry {
  async seed(b) { return this.ctx.blockConcurrencyWhile(async () => {
    const day=Math.floor(Date.now()/86400000), data={version:1,devices:{},daily:{day,registrations:0,ips:{}}};
    for(let i=1;i<=(b.count??1);i++) data.devices[i.toString(16).padStart(32,'0')]={hash:'a'.repeat(64),state:'active',createdAt:Math.floor(Date.now()/1000),usage:{day:day-(b.oldDay?1:0),requests:b.requests??0,commands:b.commands??0,bytes:b.bytes??0}};
    await this.save(data); return {ok:true};
  }); }
  async save(next){this.directoryWrites=(this.directoryWrites??0)+1;return super.save(next);}
  async saveUsage(id, usage){
    if(await this.ctx.storage.get('test:fail')) { await this.ctx.storage.delete('test:fail'); throw new Error('synthetic usage write failure'); }
    this.usageWrites=(this.usageWrites??0)+1; return super.saveUsage(id,usage);
  }
  async inspect(id){return this.ctx.blockConcurrencyWhile(async()=>({directoryWrites:this.directoryWrites??0,usageWrites:this.usageWrites??0,stored:await this.ctx.storage.get('usage:'+id),legacy:this.data.devices[id],directoryBytes:new TextEncoder().encode(JSON.stringify(this.data)).length}));}
  async rewrite(){return this.ctx.blockConcurrencyWhile(async()=>{const next=structuredClone(this.data);next.daily.registrations++;await this.save(next);return {ok:true};});}
  async oldUsage(id){return this.ctx.blockConcurrencyWhile(async()=>{const key='usage:'+id,s=await this.ctx.storage.get(key);await this.ctx.storage.put(key,{...s,day:Math.floor(Date.now()/86400000)-1});return {ok:true};});}
  async failNext(){await this.ctx.storage.put('test:fail',true);return {ok:true};}
  async heal(){await this.ctx.storage.delete('test:fail');return {ok:true};}
}
export default {async fetch(request,env){
 const b=await request.json(), r=env.ADMISSION.get(env.ADMISSION.idFromName(b.case));
 try { let v;
  switch(b.op){case 'seed':v=await r.seed(b);break;case 'inspect':v=await r.inspect(b.id);break;case 'rewrite':v=await r.rewrite();break;case 'old':v=await r.oldUsage(b.id);break;case 'fail':v=await r.failNext();break;case 'heal':v=await r.heal();break;case 'retire':v=await r.retire(b.id,b.hash);break;case 'charge':v=await r.charge(b.id,b.hash,b.kind,b.bytes);break;default:return new Response(null,{status:404});}
  return Response.json(v);
 }catch{return Response.json({error:'fixture_storage_failure'},{status:503});}
}};`;
const paths = ['relay/service/service.mjs', 'relay/worker/worker.mjs', 'relay/service/feedback.mjs'];
const modules = await Promise.all(paths.map(async p => ({ type: 'ESModule', path: join(root, p), contents: await readFile(join(root, p), 'utf8') })));
let controlOpen = 'true', mf, origin;
async function start() {
  const options = { host: '127.0.0.1', port: 0, cf: false, durableObjectsPersist: join(temp, 'do'), resourcePersistencePath: join(temp, 'state'), workers: [{ name: 'usage-fixture', modulesRoot: join(root, 'relay'),
    modules: [{ type: 'ESModule', path: join(root, 'relay/usage-fixture.mjs'), contents: entry }, ...modules], compatibilityDate: '2026-10-01',
    durableObjects: { ADMISSION: { className: 'FixtureRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
    bindings: { SERVICE_ID: '1'.repeat(32), SERVICE_LABEL: 'Local usage test', IP_HASH_SALT: '2'.repeat(64), REGISTRATION_OPEN: 'true', CONTROL_OPEN: controlOpen,
      MAX_DEVICES: '1000', DEVICE_DAILY_REQUESTS: '5', DEVICE_DAILY_COMMANDS: '4', DEVICE_DAILY_RESPONSE_BYTES: '1000' } }] };
  mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options);
  origin = String(await mf.ready);
}
async function call(c, op, extra = {}) {
  const r = await fetch(origin, { method: 'POST', body: JSON.stringify({ case: c, op, id, hash, ...extra }), signal: AbortSignal.timeout(10000) });
  return r.json();
}
const checks = [];
async function check(name, run) { const start = performance.now(); await run(); const value = { name, passed: true, ms: Math.round(performance.now()-start) }; checks.push(value); console.log(JSON.stringify(value)); }
let measurement;
try {
  await start();
  await check('legacy daily counters migrate without resetting any allowance', async () => {
    await call('migration','seed',{requests:4,commands:3,bytes:800});
    assert.deepEqual(await call('migration','charge',{kind:'request',bytes:100}),{ok:true});
    assert.equal((await call('migration','charge',{kind:'request',bytes:1})).error,'device_request_daily_limit');
    assert.deepEqual(await call('migration','charge',{kind:'command',bytes:100}),{ok:true});
    assert.equal((await call('migration','charge',{kind:'command',bytes:0})).error,'device_command_daily_limit');
    const s=await call('migration','inspect');assert.equal(s.stored.requests,5);assert.equal(s.stored.commands,4);assert.equal(s.stored.bytes,1000);assert.equal(s.directoryWrites,1);assert.equal(s.usageWrites,2);
  });
  await check('directory rewrites cannot roll back newer keyed counters', async () => {
    await call('migration','rewrite');
    assert.equal((await call('migration','charge',{kind:'request',bytes:0})).error,'device_request_daily_limit');
    assert.equal((await call('migration','inspect')).stored.bytes,1000);
  });
  await check('concurrent requests enforce exact persistent per-device budget', async () => {
    await call('concurrent','seed');
    const results=await Promise.all(Array.from({length:20},()=>call('concurrent','charge',{kind:'request',bytes:1})));
    assert.equal(results.filter(x=>x.ok).length,5);assert.equal(results.filter(x=>x.error==='device_request_daily_limit').length,15);
    const s=await call('concurrent','inspect');assert.equal(s.stored.requests,5);assert.equal(s.stored.bytes,5);assert.equal(s.directoryWrites,1);
  });
  await check('response budget, invalid accounting, unknown IDs and wrong hashes cannot mutate usage', async () => {
    await call('denial','seed',{bytes:999});
    assert.equal((await call('denial','charge',{kind:'request',bytes:2})).error,'device_response_daily_limit');
    for(const extra of [{kind:'invalid',bytes:0},{kind:'request',bytes:-1},{kind:'request',bytes:0.5}])assert.equal((await call('denial','charge',extra)).error,'invalid_budget_kind');
    assert.equal((await call('denial','charge',{kind:'request',bytes:0,hash:'b'.repeat(64)})).error,'device_unenrolled');
    assert.equal((await call('denial','charge',{kind:'request',bytes:0,id:'f'.repeat(32)})).error,'device_unenrolled');
    assert.equal((await call('denial','inspect')).stored,undefined);
  });
  await check('failed usage persistence does not confirm or consume a successful charge', async () => {
    await call('failure','seed');await call('failure','fail');
    assert.equal((await call('failure','charge',{kind:'request',bytes:7})).error,'fixture_storage_failure');
    await mf.dispose();await start();await call('failure','heal');
    const after=await call('failure','inspect');assert.equal(after.legacy.state,'active');assert.equal(after.stored,undefined);
    assert.deepEqual(await call('failure','charge',{kind:'request',bytes:7}),{ok:true});assert.equal((await call('failure','inspect')).stored.requests,1);
  });
  await check('cold workerd restart preserves migrated counters and auth checks', async () => {
    await mf.dispose();await start();
    assert.equal((await call('migration','charge',{kind:'request',bytes:0})).error,'device_request_daily_limit');
    assert.equal((await call('concurrent','inspect')).stored.requests,5);
    assert.equal((await call('migration','charge',{kind:'request',bytes:0,hash:'b'.repeat(64)})).error,'device_unenrolled');
  });
  await check('day rollover works for both legacy and keyed values', async () => {
    await call('rollover','seed',{oldDay:true,requests:100,commands:100,bytes:5000});
    assert.deepEqual(await call('rollover','charge',{kind:'request',bytes:8}),{ok:true});
    await call('migration','old');assert.deepEqual(await call('migration','charge',{kind:'command',bytes:9}),{ok:true});
    const s=await call('migration','inspect');assert.equal(s.stored.requests,0);assert.equal(s.stored.commands,1);assert.equal(s.stored.bytes,9);
  });
  await check('retirement removes keyed usage atomically and continues rejecting commands', async () => {
    assert.deepEqual(await call('rollover','retire'),{ok:true});
    const s=await call('rollover','inspect');assert.equal(s.stored,undefined);assert.equal(s.legacy.state,'retired');
    assert.equal((await call('rollover','charge',{kind:'request',bytes:0})).error,'device_unenrolled');
  });
  await check('1000 registered rows do not cause a directory rewrite for each active request', async () => {
    await call('scaling','seed',{count:1000});const before=await call('scaling','inspect');
    const times=[];for(let n=1;n<=100;n++){const start=performance.now();assert.deepEqual(await call('scaling','charge',{id:n.toString(16).padStart(32,'0'),kind:'request',bytes:1}),{ok:true});times.push(performance.now()-start);}
    const after=await call('scaling','inspect');assert.equal(after.directoryWrites,before.directoryWrites);assert.equal(after.usageWrites,100);
    times.sort((a,b)=>a-b);measurement={registered:1000,sequentialCharges:100,directoryBytes:before.directoryBytes,directoryWritesDuringCharges:0,keyedUsageWrites:100,localMedianMs:times[50],localP95Ms:times[95],cloudConcurrencyOrBillingTest:false};
  });
  await check('control closure still takes precedence over persisted usage', async () => {
    controlOpen='false';await mf.dispose();await start();
    assert.equal((await call('migration','charge',{kind:'request',bytes:0})).error,'control_closed');
    assert.equal((await call('migration','inspect')).stored.commands,1);
  });
  const report={checks,measurement,limitations:['Loopback-only workerd with synthetic directory records. No Cloudflare billing, wide-area latency, phone or personal-AI acceptance is claimed.','Storage fault injection fails immediately before the real put. It does not reproduce every disk/edge failure.']};
  if(option('--output'))await writeFile(option('--output'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({passed:checks.length,measurement}));
}finally { if(mf)await mf.dispose();await rm(temp,{recursive:true,force:true}); }
