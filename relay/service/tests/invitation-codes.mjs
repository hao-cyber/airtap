#!/usr/bin/env node
// Real service/DO/CLI protocol, synthetic identities and a simulated phone.
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
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-invite-codes-')));
await mkdir(join(temp, 'assets'));
const hex = n => randomBytes(n).toString('hex');
// The only time/storage overrides are on private test DO stubs, never public routes.
const fixture = `import entry,{AdmissionRegistry,ServiceDeviceRelay} from './service.mjs';
export default entry;
export class TestRegistry extends AdmissionRegistry {
 async fetch(request) { await this.ctx.blockConcurrencyWhile(async()=>{});
  if(new URL(request.url).pathname==='/expire') {
   for(const [key,row] of await this.ctx.storage.list({prefix:'invite:d:'})) await this.ctx.storage.put(key,{...row,expires:1});
   await this.ctx.storage.put('invite:rates',{minute:1,total:1,ips:{synthetic:1}});
   await this.alarm();
  }
  const rows=await this.ctx.storage.list({prefix:'invite:'});
  return Response.json({keys:[...rows.keys()],alarm:await this.ctx.storage.getAlarm()});
 }
}
export class TestDevice extends ServiceDeviceRelay {
 async fetch(request) {
  if(new URL(request.url).pathname==='/fixture-expire') {await this.ctx.blockConcurrencyWhile(async()=>{
   await this.persist({...this.state,pairingExpires:1,invitation:this.state.invitation?{...this.state.invitation,expiresAt:1}:null});
  });return Response.json({ok:true});} return super.fetch(request);
 }
}`;
const modules = [{ type:'ESModule',path:join(root,'relay/service/invitation-code-fixture.mjs'),contents:fixture },
 ...await Promise.all(['service/service.mjs','worker/worker.mjs','service/feedback.mjs'].map(async f=>({type:'ESModule',path:join(root,'relay',f),contents:await readFile(join(root,'relay',f),'utf8')})))];
const options = {host:'127.0.0.1',port:0,cf:false,workers:[{name:'invite-codes',modulesRoot:join(root,'relay'),modules,compatibilityDate:'2026-10-01',
 durableObjects:{ADMISSION:{className:'TestRegistry',useSQLite:true},DEVICES:{className:'TestDevice',useSQLite:true}},
 ratelimits:{INGRESS:{namespace_id:'2413',simple:{limit:10000,period:60}}},
 assets:{directory:join(temp,'assets'),binding:'ASSETS',run_worker_first:true,routerConfig:{has_user_worker:true}},
 bindings:{SERVICE_ID:hex(16),SERVICE_LABEL:'Synthetic short invitation',IP_HASH_SALT:hex(32),REGISTRATION_OPEN:'true',CONTROL_OPEN:'true',
 ARTIFACT_VERSION:'candidate',CLI_SHA256:'a'.repeat(64),AGENT_SHA256:'b'.repeat(64),MAX_DEVICES:'20',DAILY_REGISTRATIONS:'20',IP_DAILY_REGISTRATIONS:'20'}}]};
const mf = new Miniflare(convertV4MiniflareOptions ? {...convertV4MiniflareOptions(options),telemetry:{enabled:false}}:options);
const checks=[],sockets=[]; let serial=1,bridge;
async function call(path,token,body,method,ip) {
 const r=await mf.dispatchFetch('http://localhost'+path,{method:method??(body===undefined?'GET':'POST'),headers:{'cf-connecting-ip':ip??('192.0.2.'+(serial++%250+1)),...(token?{Authorization:'Bearer '+token}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
 return {status:r.status,data:await r.json(),headers:r.headers};
}
async function ok(...args) {const r=await call(...args);assert.equal(r.status,200,JSON.stringify(r.data));return r.data;}
async function check(name,fn){await fn();checks.push({name,ok:true});console.log(JSON.stringify(checks.at(-1)));}
async function enroll(){const d={id:hex(16),token:hex(32)};d.base='/d/'+d.id;await ok('/v1/enroll',null,{device_id:d.id,device_token:d.token});return d;}
async function invitation(d){const i=await ok(d.base+'/v1/device/pairing',d.token,undefined,'POST');const c=await ok(d.base+'/v1/device/invitation-code',d.token,{ticket:i.ticket});return {...i,...c};}
const resolve=code=>ok('/v1/invitations/resolve',null,{code});
const approval=claim_id=>({claim_id,allow_tap:false,allow_control:true,allowed_packages:[],persistent:true,all_apps:true});
async function phone(d) {
 const r=await mf.dispatchFetch('http://localhost'+d.base+'/v1/device',{headers:{'cf-connecting-ip':'127.0.0.1',Authorization:'Bearer '+d.token,Upgrade:'websocket'}});
 assert.equal(r.status,101);const ws=r.webSocket;ws.accept();sockets.push(ws);
 await new Promise(resolve=>{ws.addEventListener('message',e=>{const b=JSON.parse(e.data);
 if(b.type==='hello_ack')resolve();else if(b.type==='task_fence')ws.send(JSON.stringify({type:'task_fence_ack',operation_id:b.operation_id,lease_epoch:b.lease_epoch,ok:true}));
 });ws.send(JSON.stringify({type:'hello',protocol_version:4,capabilities:['multi_ai_v1','task_lease_v1']}));});
}
try {
 await mf.ready;const d=await enroll(),other=await enroll();let i;
 await check('owner-only mint, strict input, stable code and original expiry',async()=>{
  i=await invitation(d);assert.match(i.code,/^[0-9]{8}$/);
  const again=await ok(d.base+'/v1/device/invitation-code',d.token,{ticket:i.ticket});
  assert.equal(again.code,i.code);assert.equal(again.pairing_expires_at,i.pairing_expires_at);
  assert.equal((await call(d.base+'/v1/device/invitation-code',other.token,{ticket:i.ticket})).status,401);
  assert.equal((await call(d.base+'/v1/device/invitation-code',d.token,{ticket:hex(16)})).status,409);
  assert.equal((await call('/v1/invitations/resolve',null,{code:Number(i.code)})).status,400);
  assert.equal((await call('/v1/invitations/resolve?code='+i.code)).status,404);
 });
 await check('resolution is isolated, read-only, uncacheable and conveys no control authority',async()=>{
  const r=await call('/v1/invitations/resolve',null,{code:i.code});assert.equal(r.status,200);
  assert.match(r.headers.get('cache-control'),/no-store/);
  assert.deepEqual(Object.keys(r.data).sort(),['protocol_version','device_base_path','ticket','pairing_expires_at','cli_sha256','agent_sha256','task_lease_required'].sort());
  assert.equal(r.data.device_base_path,d.base);assert.equal(r.data.ticket,i.ticket);assert.equal(r.data.task_lease_required,false);
  assert.equal((await ok(d.base+'/v1/device/pairing',d.token)).state,'waiting');
  assert.equal((await call(d.base+'/v1/status',i.ticket)).status,401);
  const j=await invitation(other);assert.notEqual(j.code,i.code);assert.equal((await resolve(j.code)).device_base_path,other.base);
 });
 await check('actual CLI retry after lost pairing reply retains identity and approval',async()=>{
  let drop=true;const paths=[];
  bridge=createServer(async(req,res)=>{try{const chunks=[];for await(const b of req)chunks.push(b);const data=Buffer.concat(chunks);
   const response=await mf.dispatchFetch('http://localhost'+req.url,{method:req.method,headers:{...req.headers,'cf-connecting-ip':'198.51.100.20'},...(data.length?{body:data}: {})});
   paths.push(req.url);const bytes=Buffer.from(await response.arrayBuffer());
   if(drop&&req.url.endsWith('/v1/pair')){drop=false;req.socket.destroy();return;}
   res.writeHead(response.status,Object.fromEntries(response.headers));res.end(bytes);
  }catch{res.writeHead(500);res.end('{}');}});
  await new Promise(resolve=>bridge.listen(0,'127.0.0.1',resolve));const service='http://127.0.0.1:'+bridge.address().port,config=join(temp,'ai.json');
  async function cli(){return await new Promise((resolve,reject)=>{const p=spawn(process.env.PYTHON??'python3',[join(root,'cli/airtap.py'),'--allow-local-http','--config',config,'pair-code','--service',service,'--name','Test AI','--code-stdin']);let out='',err='';p.stdout.on('data',x=>out+=x);p.stderr.on('data',x=>err+=x);p.on('error',reject);p.on('close',code=>resolve({code,data:JSON.parse(out),err}));p.stdin.end(i.code.slice(0,4)+' '+i.code.slice(4)+'\n');});}
  assert.equal((await cli()).code,1);const saved=JSON.parse(await readFile(config));
  const retry=await cli();assert.equal(retry.code,0,JSON.stringify(retry));assert.equal(retry.data.state,'pending');
  assert.equal(JSON.parse(await readFile(config)).controller_token,saved.controller_token);
  assert.equal(paths.filter(x=>x.endsWith('/v1/pair')).length,1);assert.equal(paths.filter(x=>x==='/v1/invitations/resolve').length,1);
  const pending=await ok(d.base+'/v1/device/pairing',d.token);assert.equal(pending.candidate.claim_id,retry.data.claim_id);assert.match(retry.data.verification_code,/^[0-9]{6}$/);
  assert.equal((await call(d.base+'/v1/status',saved.controller_token)).status,403);
  await ok(d.base+'/v1/device/approve',d.token,approval(retry.data.claim_id));
  assert.equal((await call('/v1/invitations/resolve',null,{code:i.code})).status,404);
  assert.equal((await cli()).data.state,'approved');assert.equal(paths.filter(x=>x==='/v1/invitations/resolve').length,1);
  d.controller=saved.controller_token;d.claim=retry.data.claim_id;
  await new Promise(resolve=>bridge.close(resolve));bridge=null;
 });
 await check('second AI code, cancellation, approval and unchanged original connection',async()=>{
  await ok(d.base+'/v1/device/multi/enable',d.token,{capabilities:['multi_ai_v1','task_lease_v1'],expected_claim_id:d.claim});await phone(d);
  const task=await ok(d.base+'/v1/tasks/acquire',d.controller,{task_id:hex(16)});
  const seed={invitation_id:hex(16),invitation_sequence:1,ticket:hex(16)};await ok(d.base+'/v1/device/invitations',d.token,seed);
  const first=await ok(d.base+'/v1/device/invitation-code',d.token,{ticket:seed.ticket});assert.equal((await resolve(first.code)).task_lease_required,true);
  await ok(d.base+'/v1/device/invitations/cancel',d.token,{invitation_id:seed.invitation_id,invitation_sequence:1});
  assert.equal((await call('/v1/invitations/resolve',null,{code:first.code})).status,404);
  const next={invitation_id:hex(16),invitation_sequence:2,ticket:hex(16)};await ok(d.base+'/v1/device/invitations',d.token,next);
  const second=await ok(d.base+'/v1/device/invitation-code',d.token,{ticket:next.ticket});assert.notEqual(first.code,second.code);
  const resolved=await resolve(second.code),token=hex(32);const claim=await ok(d.base+'/v1/pair',null,{ticket:resolved.ticket,controller_token:token,name:'Second AI',capabilities:['task_lease_v1']});
  await ok(d.base+'/v1/device/grants/approve',d.token,approval(claim.claim_id));
  const old=await ok(d.base+'/v1/status',d.controller);assert.equal(old.claim_id,d.claim);assert.equal(old.task.lease_id,task.lease_id);
  assert.equal((await ok(d.base+'/v1/status',token)).task.state,'busy');
  assert.equal((await call('/v1/invitations/resolve',null,{code:second.code})).status,404);
 });
 await check('expired invitation and retired device cannot resolve',async()=>{
  const j=await invitation(other);const ns=await mf.getDurableObjectNamespace('DEVICES');await ns.get(ns.idFromName(other.id)).fetch('http://test/fixture-expire');
  assert.equal((await call('/v1/invitations/resolve',null,{code:j.code})).status,404);
  const last=await invitation(other);await ok(other.base+'/v1/device/unenroll',other.token,undefined,'POST');
  assert.equal((await call('/v1/invitations/resolve',null,{code:last.code})).status,404);
 });
 await check('guess attempts are bounded per IP and service-wide; cleanup retains no expired ticket',async()=>{
  for(let n=0;n<10;n++)assert.equal((await call('/v1/invitations/resolve',null,{code:'00000000'},'POST','203.0.113.3')).status,404);
  assert.equal((await call('/v1/invitations/resolve',null,{code:'00000000'},'POST','203.0.113.3')).status,429);
  let bounded=false;for(let n=0;n<600;n++){const r=await call('/v1/invitations/resolve',null,{code:'00000000'},'POST','2001:db8::'+(n+1).toString(16));if(r.status===429){bounded=true;break;}assert.equal(r.status,404);}
  assert.equal(bounded,true);
  const ns=await mf.getDurableObjectNamespace('ADMISSION'),stub=ns.get(ns.idFromName('service'));
  const before=await(await stub.fetch('http://test/inspect')).json();assert.ok(before.keys.some(x=>x.startsWith('invite:d:')));assert.ok(before.alarm);
  const cleaned=await(await stub.fetch('http://test/expire')).json();assert.deepEqual(cleaned.keys,[]);
 });
 const report={ok:true,checks,scope:'Synthetic actual Worker/DO/CLI; not Android UI, physical device or personal AI acceptance'};
 if(option('--output'))await writeFile(option('--output'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({ok:true,checks:checks.length}));
} finally {if(bridge)await new Promise(resolve=>bridge.close(resolve));for(const s of sockets)try{s.close();}catch{}await mf.dispose();await rm(temp,{recursive:true,force:true});}
