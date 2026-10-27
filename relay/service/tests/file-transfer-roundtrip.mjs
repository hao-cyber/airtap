#!/usr/bin/env node
// Production modules, real workerd, real Python CLI subprocess, synthetic phone.
// A loopback-only edge adapter adds CF-Connecting-IP as Cloudflare does in service.
// No test/admin endpoints, storage edits, physical phone or personal AI are used.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdtemp, mkdir, rm, realpath, symlink, stat } from 'node:fs/promises';
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
const { WebSocket } = createRequire(miniflareURL)('ws');
const root = fileURLToPath(new URL('../../../', import.meta.url));
const source = await readFile(join(root, 'relay/service/service.mjs'), 'utf8');
const core = await readFile(join(root, 'relay/worker/worker.mjs'), 'utf8');
const cliPath = join(root, 'cli/airtap.py'), cli = await readFile(cliPath);
const guide = await readFile(join(root, 'docs/agent.md'));
const digest = value => createHash('sha256').update(value).digest('hex');
const hex = bytes => randomBytes(bytes).toString('hex');
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-files-')));
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
    CLI_SHA256: digest(cli), AGENT_SHA256: digest(guide), ARTIFACT_VERSION: 'local-cli-v3' },
}] };
const mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options);
const backend = await mf.ready;
const edge = createServer(async (request, response) => {
  try {
    const chunks = []; let size = 0;
    for await (const chunk of request) { size += chunk.length; if (size > 8192) throw new Error('test input limit'); chunks.push(chunk); }
    const headers = { ...request.headers, 'cf-connecting-ip': '127.0.0.1', connection: 'close' }; delete headers.host; delete headers['transfer-encoding'];
    const upstream = await fetch(new URL(request.url, backend), { method: request.method, headers,
      ...(chunks.length ? { body: Buffer.concat(chunks) } : {}), signal: AbortSignal.timeout(20000) });
    const bytes = Buffer.from(await upstream.arrayBuffer()), outgoing = Object.fromEntries(upstream.headers);
    delete outgoing['content-encoding']; delete outgoing['transfer-encoding']; outgoing['content-length'] = String(bytes.length);
    response.writeHead(upstream.status, outgoing); response.end(bytes);
  } catch { response.writeHead(502); response.end(); }
});
await new Promise(resolve => edge.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + edge.address().port;
const d = { id: hex(16), token: hex(32) }, base = origin + '/d/' + d.id;
const config = join(temp, 'private', 'profile.json');
const checks = []; let phone; const seenCommands = []; let pingState = {}; let gestureError, commandError = null;
async function http(path, { token, body, method } = {}) {
  const r = await fetch(origin + path, { method: method ?? (body === undefined ? 'GET' : 'POST'), headers: {
    ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json; charset=utf-8' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(22000) });
  return new Response(await r.arrayBuffer(), { status: r.status, headers: r.headers });
}
async function python(args, stdin = '', expectedExit = 0) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.env.PYTHON || 'python3', [cliPath, '--allow-local-http', '--config', config, ...args], { stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '', stderr = ''; const timer = setTimeout(() => child.kill('SIGKILL'), 30000);
    child.stdout.on('data', bytes => { stdout += bytes; }); child.stderr.on('data', bytes => { stderr += bytes; });
    child.on('error', reject); child.on('close', code => {
      clearTimeout(timer);
      try { assert.equal(code, expectedExit, 'CLI exit mismatch: ' + stdout); assert.equal(stderr, ''); assert.equal(stdout.includes(d.token), false); if (controller) assert.equal(stdout.includes(controller), false); resolve(JSON.parse(stdout)); }
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
let controller, lastSequence = 0, candidate;
async function api(command, sequence, extra = {}) {
  return http('/d/' + d.id + '/v1/command', { token: controller, body: { id: crypto.randomUUID(), action: command, sequence, ...extra } });
}
async function connectPhone() {
  const target = new URL('/d/' + d.id + '/v1/device', backend); target.protocol = 'ws:';
  const ws = new WebSocket(target, { headers: { Authorization: 'Bearer ' + d.token, 'CF-Connecting-IP': '127.0.0.1' } });
  ws.on('message', bytes => {
    const cmd = JSON.parse(bytes); seenCommands.push(cmd);
    assert.ok(cmd.sequence > lastSequence); lastSequence = cmd.sequence;
    if (cmd.action === 'gesture' && gestureError) {
      ws.send(JSON.stringify({ id: cmd.id, ok: false, error: { code: gestureError } })); return;
    }
    if (commandError) { ws.send(JSON.stringify({ id: cmd.id, ok: false, error: commandError })); return; }
    if (cmd.action.startsWith('file_')) {
      try { ws.send(JSON.stringify({id:cmd.id,ok:true,result:fileResponse(cmd)})); }
      catch (error) { ws.send(JSON.stringify({id:cmd.id,ok:false,error:{code:error.message}})); }
      return;
    }
    let result;
    if (cmd.action === 'observe') result = { package: 'com.example.app', observation_id: hex(16), expires_in_ms: 30000,
      coordinate_space: 'normalized_image', width: 720, height: 1600, display_id: 0, window_id: 3, rotation: 0, capture_scope: 'display', window_bounds: [0,40,1080,2200],
      mime_type: 'image/jpeg', image_base64: Buffer.from([255,216,255,217]).toString('base64') };
    else if (cmd.action === 'read_tree') result = { package: cmd.package ?? 'com.example.app', snapshot_id: hex(16), expires_in_ms: 30000, nodes: [{ node_id: '1', text: 'test', clickable: true }] };
    else if (cmd.action === 'ping') result = { authorized: true, persistent: true, all_apps: true, ...pingState };
    else if (cmd.action === 'apps') result = { apps: [{ name: 'test', package: 'com.example.app' }] };
    else if (cmd.action === 'audio_state') result = { fixed: false, streams: [{ stream: 'music', available: true, volume: { min: 0, max: 15, current: 5, muted: false }, control: 'available_with_platform_policy' }] };
    else if (['set_volume', 'adjust_volume'].includes(cmd.action)) result = { accepted: true, requires_observation: false, verification: 'native_readback', target_observed: true, after: { min: 0, max: 15, current: cmd.level ?? 6, muted: cmd.direction === 'mute' } };
    else if (cmd.action === 'windows') result = { windows: [{ window_id: 3, display_id: 0, package: 'com.android.packageinstaller', active: true, root_available: false }],
      content_included: false, window_selection_supported: false, selection: { source: 'active_window', error: 'window_root_unavailable' } };
    else result = { accepted: true, requires_observation: true };
    ws.send(JSON.stringify({ id: cmd.id, ok: true, result }));
  });
  await new Promise((resolve, reject) => { ws.once('open', () => { ws.send('hello'); resolve(); }); ws.once('error', reject); });
  return ws;
}
async function mcp(method, params, token = controller, extraHeaders = {}) {
  return fetch(base + '/mcp', { method: 'POST', headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-11-25', ...extraHeaders }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
}
// This mock validates client/protocol behavior, not Android filesystem guarantees.
const files = new Map(), transfers = new Map();
const safRoot='saf_'+hex(16), readOnlyRoot='saf_'+hex(16);
let corruptChunk = false, dropChunkOnce = false, publicationPause = false, publicationUnknown = false, publicationNameConflict = false, externalCreates = 0;
const metadata = (root, path, data) => ({ root, path, name: path.split('/').at(-1), kind:'file', size:data.length, modified_ms:1, version:digest(data), sha256:digest(data) });
function fileResponse(cmd) {
  const key = cmd.root + '/' + cmd.path;
  if (cmd.action === 'file_roots') return {roots:[{root:'workspace',name:'Workspace',kind:'workspace',readable:true,writable:true,publication_atomic:true,operations:['list','stat','read','upload']},{root:safRoot,name:'Selected writable directory',kind:'saf',readable:true,writable:true,publication_atomic:false,operations:['list','stat','read','upload']},{root:readOnlyRoot,name:'Selected read-only directory',kind:'saf',readable:true,writable:false,publication_atomic:false,operations:['list','stat','read']}],limits:{max_file_bytes:8388608,max_chunk_bytes:4096,max_list_entries:64,max_uploads:4,upload_ttl_ms:86400000}};
  if (cmd.action === 'file_list') return {root:cmd.root,path:cmd.path,entries:[...files].filter(([k])=>k.startsWith(cmd.root+'/')).map(([k,v])=>metadata(cmd.root,k.slice(cmd.root.length+1),v)),next_offset:null,truncated:false};
  if(cmd.action==='file_share'){if(!files.has(key))throw Error('file_not_found');if(digest(files.get(key))!==cmd.version)throw Error('file_changed');return {accepted:true,share_id:hex(16),root:cmd.root,path:cmd.path,mime_type:cmd.mime_type,target_package:cmd.target_package||null,expires_in_ms:300000,read_only:true,chooser_requested:true,delivery_verified:false,requires_observation:true};}
  if (cmd.action === 'file_stat') { if(!files.has(key)) throw Error('file_not_found'); return metadata(cmd.root,cmd.path,files.get(key)); }
  if (cmd.action === 'file_read') {
    const data=files.get(key);if(!data) throw Error('file_not_found'); if(digest(data)!==cmd.version) throw Error('file_version_changed');
    const chunk=data.subarray(cmd.offset,cmd.offset+cmd.length);
    return {root:cmd.root,path:cmd.path,offset:cmd.offset,next_offset:cmd.offset+chunk.length,data_base64:chunk.toString('base64'),sha256:corruptChunk?'0'.repeat(64):digest(chunk),eof:cmd.offset+chunk.length===data.length,size:data.length,version:digest(data)};
  }
  let transfer=transfers.get(cmd.transfer_id);
  if (cmd.action==='file_upload_begin') {
    if(!['workspace',safRoot].includes(cmd.root))throw Error(cmd.root===readOnlyRoot?'file_scope_read_only':'file_root_unavailable');
    const spec={transfer_id:cmd.transfer_id,root:cmd.root,path:cmd.path,size:cmd.size,sha256:cmd.sha256};
    if(transfer) { if(Object.entries(spec).some(([k,v])=>transfer[k]!==v)) throw Error('file_transfer_conflict'); }
    else { if(files.has(key))throw Error('file_exists'); transfer={...spec,state:'receiving',publication_atomic:cmd.root==='workspace',offset:0,expires_at:Date.now()+86400000,data:Buffer.alloc(0)};transfers.set(cmd.transfer_id,transfer); }
  } else if(!transfer) throw Error('file_transfer_not_found');
  if(cmd.action==='file_upload_chunk') {
    const raw=Buffer.from(cmd.data_base64,'base64');if(digest(raw)!==cmd.sha256)throw Error('file_hash_mismatch');
    if(transfer.state!=='receiving')throw Error('file_transfer_not_receiving');
    if(cmd.offset>transfer.offset)throw Error('file_offset_conflict');
    if(cmd.offset<transfer.offset) { if(!transfer.data.subarray(cmd.offset,cmd.offset+raw.length).equals(raw))throw Error('file_offset_conflict'); }
    else {transfer.data=Buffer.concat([transfer.data,raw]);transfer.offset=transfer.data.length;}
    if(dropChunkOnce){dropChunkOnce=false;throw Error('connection_changed_outcome_unknown');}
  }
  if(cmd.action==='file_upload_finish'&&transfer.state!=='finished') {
    if(transfer.offset!==transfer.size)throw Error('file_incomplete');
    if(digest(transfer.data)!==transfer.sha256)throw Error('file_hash_mismatch');
    const target=transfer.root+'/'+transfer.path;
    if(transfer.state==='commit_unknown')return {...transfer,data:undefined,next_step:'Inspect the selected directory; do not recreate.'};
    if(transfer.state==='receiving') {
      if(files.has(target))throw Error('file_exists');
      if(!transfer.publication_atomic) {
        externalCreates++;
        if(publicationUnknown){publicationUnknown=false;transfer.state='commit_unknown';return {...transfer,data:undefined,next_step:'Inspect the selected directory; do not recreate.'};}
        transfer.state='publishing';files.set(target,Buffer.alloc(0));
        if(publicationNameConflict){publicationNameConflict=false;transfer.cleanup_required=true;throw Error('file_name_conflict');}
        if(publicationPause){publicationPause=false;throw Error('connection_changed_outcome_unknown');}
      }
    }
    files.set(target,transfer.data);transfer.state='finished';transfer.file=metadata(transfer.root,transfer.path,transfer.data);
  }
  if(cmd.action==='file_upload_abort'&&['receiving','publishing'].includes(transfer.state)) {if(transfer.state==='publishing')files.delete(transfer.root+'/'+transfer.path);delete transfer.cleanup_required;transfer.state='aborted';}
  const {data,...result}=transfer;return result;
}
try {
  await check('v4 discovery, no duration/command cap, enrollment and integrity-checked CLI delivery', async () => {
    const discovery = await (await http('/v1/service')).json(); assert.equal(discovery.protocol_version, 4); assert.equal(discovery.command_budget, null); assert.equal(discovery.session_ttl_seconds, null);
    assert.equal((await http('/v1/enroll', { body: { device_id: d.id, device_token: d.token } })).status, 200);
    for (const [path, expected] of [['/cli/airtap.py', cli], ['/agent.md', guide]]) {
      const r = await http('/d/' + d.id + path); assert.equal(r.status, 200); assert.equal(digest(Buffer.from(await r.arrayBuffer())), digest(expected)); assert.equal(r.headers.get('X-Airtap-Artifact-SHA256'), digest(expected));
    }
  });
  await check('CLI pair, pending denial, explicit full-phone persistent approval', async () => {
    const invitation = await (await http('/d/' + d.id + '/v1/device/pairing', { token: d.token, method: 'POST' })).json();
    const started = await python(['pair-start', '--url', base, '--name', 'Companion test', '--ticket-stdin'], invitation.ticket + '\n');
    controller = JSON.parse(await readFile(config)).controller_token;
    assert.equal((await api('ping', 1)).status, 403);
    candidate = (await (await http('/d/' + d.id + '/v1/device/pairing', { token: d.token })).json()).candidate;
    assert.equal(started.verification_code, candidate.verification_code);
    const legacy = await http('/d/' + d.id + '/v1/device/approve', { token: d.token, body: { claim_id: candidate.claim_id, allow_tap: false, allow_control: true, allowed_packages: [] } }); assert.equal(legacy.status, 400);
    const approved = await http('/d/' + d.id + '/v1/device/approve', { token: d.token, body: { claim_id: candidate.claim_id, allow_tap: false, allow_control: true, allowed_packages: [], persistent: true, all_apps: true } });
    assert.equal(approved.status, 200); assert.equal((await approved.json()).session_expires_at, null);
    const status = await python(['pair-status']); assert.equal(status.persistent, true); assert.equal(status.all_apps, true);
  });
  phone = await connectPhone();
  await python(['wait-ready', '--wait-seconds', '5']);
  await check('workspace discovery and strict MCP schemas', async()=>{
    const roots=(await python(['file-roots'])).result;assert.equal(roots.roots[0].root,'workspace');
    const tools=(await (await mcp('tools/list',{})).json()).result.tools;
    for(const action of ['share','roots','list','stat','read','upload_begin','upload_status','upload_chunk','upload_finish','upload_abort']) {
      const tool=tools.find(t=>t.name==='phone_file_'+action);assert.ok(tool);assert.equal(tool.inputSchema.additionalProperties,false);
      assert.equal(tool.annotations.readOnlyHint,['roots','list','stat','read','upload_status'].includes(action));
    }
  });
  const bytes=Buffer.concat([randomBytes(9200),Buffer.from('中文 🫶 fixture')]);
  const input=join(temp,'input.bin');await writeFile(input,bytes);
  let transfer;
  await check('real CLI upload/download verify multi-chunk binary with no base64 stdout',async()=>{
    const uploaded=await python(['file-upload','--root','workspace','--path','sample.bin','--input',input]);
    transfer=uploaded.result.transfer_id;assert.equal(uploaded.result.state,'finished');assert.equal(uploaded.result.sha256,digest(bytes));
    assert.equal(JSON.stringify(uploaded).includes(bytes.subarray(0,100).toString('base64')),false);
    assert.equal(seenCommands.filter(x=>x.action==='file_upload_chunk').length,3);
    const output=join(temp,'download','copy.bin');const downloaded=await python(['file-download','--root','workspace','--path','sample.bin','--output',output]);
    assert.equal(downloaded.result.sha256,digest(bytes));assert.deepEqual(await readFile(output),bytes);assert.equal((await stat(output)).mode&0o777,0o600);
    const before=seenCommands.length;
    const again=await python(['file-upload','--root','workspace','--path','sample.bin','--input',input]);
    assert.equal(again.result.state,'finished');assert.deepEqual(seenCommands.slice(before).map(x=>x.action),['file_upload_status','file_stat']);
    const listed=(await python(['file-list','--root','workspace'])).result;assert.equal(listed.entries.length,1);
  });
  await check('workspace sharing reaches CLI and MCP as unverified read-only handoff',async()=>{
    const info=(await python(['file-stat','--root','workspace','--path','sample.bin'])).result;
    const share=await python(['file-share','--root','workspace','--path','sample.bin','--version',info.version,'--mime-type','application/octet-stream','--target-package','com.example.receiver']);
    assert.equal(share.result.chooser_requested,true);assert.equal(share.result.delivery_verified,false);assert.equal(share.result.read_only,true);assert.equal(share.result.expires_in_ms,300000);assert.equal(JSON.stringify(share).includes('content://'),false);
    const m=(await(await mcp('tools/call',{name:'phone_file_share',arguments:{sequence:lastSequence+1,root:'workspace',path:'sample.bin',version:info.version,mime_type:'text/plain',target_package:''}})).json()).result;
    assert.equal(m.isError,false);await python(['status']);
    for(const bad of [{root:safRoot},{path:'../private'},{target_package:'io.airtap.preview'},{target_package:'com.bad/intent'},{mime_type:'*/*'},{mode:'rw'},{version:'bad'}]){
      const before=seenCommands.length;assert.equal((await api('file_share',lastSequence+1,{root:'workspace',path:'sample.bin',version:info.version,mime_type:'text/plain',target_package:'',...bad})).status,400);assert.equal(seenCommands.length,before);
    }
    const stale=await python(['file-share','--root','workspace','--path','sample.bin','--version','0'.repeat(64),'--mime-type','text/plain'],'',1);assert.equal(stale.error,'file_changed');
  });
  await check('read chunk and MCP binary resource retain authenticated metadata without base64 text',async()=>{
    const info=(await python(['file-stat','--root','workspace','--path','sample.bin'])).result;
    const output=join(temp,'part.bin');const part=await python(['file-read','--root','workspace','--path','sample.bin','--offset','4096','--length','100','--version',info.version,'--output',output]);
    assert.deepEqual(await readFile(output),bytes.subarray(4096,4196));assert.equal('data_base64' in part.result,false);
    const result=(await (await mcp('tools/call',{name:'phone_file_read',arguments:{sequence:lastSequence+1,root:'workspace',path:'sample.bin',offset:0,length:20,version:info.version}})).json()).result;
    assert.equal(result.isError,false);const blob=result.content.find(x=>x.type==='resource');assert.deepEqual(Buffer.from(blob.resource.blob,'base64'),bytes.subarray(0,20));
    const text=result.content.find(x=>x.type==='text').text;assert.equal(text.includes('data_base64'),false);assert.equal(text.includes(bytes.subarray(0,20).toString('base64')),false);
    await python(['status']);
  });
  await check('strict wire rejects traversal, foreign roots, extra fields, binary abuse and bounds before dispatch',async()=>{
    const read={root:'workspace',path:'sample.bin',offset:0,length:1,version:digest(bytes)};
    const begin={root:'workspace',path:'next.bin',transfer_id:hex(16),size:1,sha256:digest(Buffer.from('a'))};
    const chunk={transfer_id:transfer,offset:0,data_base64:'YQ==',sha256:digest(Buffer.from('a'))};
    const bad=[
      ['file_roots',{path:''}],['file_stat',{root:'workspace',path:'../private'}],['file_stat',{root:'workspace',path:'/etc/passwd'}],
      ['file_stat',{root:'workspace',path:'a//b'}],['file_stat',{root:'workspace',path:'a\\b'}],['file_stat',{root:'workspace',path:'content://x'}],
      ['file_stat',{root:'private',path:'x'}],['file_stat',{root:'workspace',path:'x/'+'.'.repeat(2)}],['file_list',{root:'workspace',path:'',offset:-1,limit:64}],
      ['file_list',{root:'workspace',path:'',offset:4097,limit:1}],['file_read',{...read,length:4097}],['file_read',{...read,offset:true}],['file_read',{...read,version:'bad'}],['file_read',{...read,url:'https://example.test/file'}],
      ['file_upload_begin',{...begin,root:'saf_bad'}],['file_upload_begin',{...begin,size:8388609}],['file_upload_begin',{...begin,overwrite:true}],
      ['file_upload_chunk',{...chunk,data_base64:'YQ'}],['file_upload_chunk',{...chunk,data_base64:'YR=='}],['file_upload_chunk',{...chunk,data_base64:'YQ==\n'}],
      ['file_upload_chunk',{...chunk,data_base64:Buffer.alloc(4097).toString('base64')}],['file_upload_chunk',{...chunk,offset:8388608}],
      ['file_upload_chunk',{...chunk,sha256:'A'.repeat(64)}],['file_upload_status',{transfer_id:'x'}],['file_upload_finish',{transfer_id:transfer,path:'other'}]
    ];
    const before=seenCommands.length;
    for(const [action,fields]of bad)assert.equal((await api(action,lastSequence+1,fields)).status,400,action);
    assert.equal(seenCommands.length,before);
    const m=(await (await mcp('tools/call',{name:'phone_file_upload_chunk',arguments:{sequence:lastSequence+1,...chunk,data_base64:'YR=='}})).json()).result;
    assert.equal(m.isError,true);assert.equal(seenCommands.length,before);
  });
  await check('CLI rejects local symlinks/directories and remote traversal, never overwrites outputs',async()=>{
    const link=join(temp,'linked.bin');await symlink(input,link);const before=seenCommands.length;
    await python(['file-upload','--root','workspace','--path','link.bin','--input',link],'',1);
    await python(['file-upload','--root','workspace','--path','dir.bin','--input',temp],'',1);
    const bad=await python(['file-stat','--root','workspace','--path','../private'],'',1);assert.equal(bad.error,'invalid_file_path');assert.equal(seenCommands.length,before);
    const output=join(temp,'existing.bin');await writeFile(output,'KEEP');
    const exists=await python(['file-download','--root','workspace','--path','sample.bin','--output',output],'',1);assert.equal(exists.error,'file_already_exists');assert.equal(await readFile(output,'utf8'),'KEEP');
  });
  await check('corrupted chunk hash causes no local publication or content leak',async()=>{
    corruptChunk=true;const output=join(temp,'corrupt.bin');
    const failed=await python(['file-download','--root','workspace','--path','sample.bin','--output',output],'',1);assert.equal(failed.error,'invalid_file_chunk');assert.equal(JSON.stringify(failed).includes('data_base64'),false);
    await assert.rejects(stat(output));corruptChunk=false;
  });
  await check('uncertain chunk persists transfer identity, explicit resume checks status and does not duplicate bytes',async()=>{
    dropChunkOnce=true;const before=seenCommands.length;
    const failed=await python(['file-upload','--root','workspace','--path','resume.bin','--input',input],'',1);assert.equal(failed.error,'connection_changed_outcome_unknown');
    const saved=JSON.parse(await readFile(config));assert.equal(saved.last_file_upload.transfer_id,failed.transfer_id);
    const stopped=seenCommands.length;assert.deepEqual(seenCommands.slice(before).map(x=>x.action),['file_upload_begin','file_upload_chunk']);
    const resumed=await python(['file-upload','--root','workspace','--path','resume.bin','--input',input]);
    assert.equal(seenCommands[stopped].action,'file_upload_status');assert.equal(seenCommands[stopped+1].offset,4096);assert.equal(resumed.result.state,'finished');assert.deepEqual(files.get('workspace/resume.bin'),bytes);
  });
  await check('empty file and same-transfer abort/finish idempotence',async()=>{
    const empty=join(temp,'empty.bin');await writeFile(empty,Buffer.alloc(0));
    const done=(await python(['file-upload','--root','workspace','--path','empty.bin','--input',empty])).result;assert.equal(done.state,'finished');
    assert.equal((await python(['file-upload-finish','--transfer-id',done.transfer_id])).result.state,'finished');
    assert.equal((await python(['file-upload-abort','--transfer-id',done.transfer_id])).result.state,'finished');
    const output=join(temp,'empty-copy.bin');await python(['file-download','--root','workspace','--path','empty.bin','--output',output]);assert.equal((await readFile(output)).length,0);
    const id=hex(16);assert.equal((await api('file_upload_begin',lastSequence+1,{root:'workspace',path:'abort.bin',transfer_id:id,size:1,sha256:digest(Buffer.from('a'))})).status,200);
    await python(['status']);assert.equal((await python(['file-upload-abort','--transfer-id',id])).result.state,'aborted');assert.equal((await python(['file-upload-abort','--transfer-id',id])).result.state,'aborted');
  });
  await check('saved receipt does not falsely prove a subsequently changed file still matches',async()=>{
    const changed=join(temp,'changed.bin');await writeFile(changed,'original');
    const args=['file-upload','--root','workspace','--path','receipt.bin','--input',changed];
    await python(args);files.set('workspace/receipt.bin',Buffer.from('edited'));
    const result=await python(args,'',1);assert.equal(result.error,'finished_file_changed');assert.equal(files.get('workspace/receipt.bin').toString(),'edited');
  });
  await check('SAF flags are per-root and provider read-only denial is preserved',async()=>{
    const roots=(await python(['file-roots'])).result.roots;
    assert.equal(roots.find(r=>r.root===safRoot).publication_atomic,false);assert.equal(roots.find(r=>r.root===readOnlyRoot).writable,false);
    const denied=await python(['file-upload','--root',readOnlyRoot,'--path','denied.bin','--input',input],'',1);assert.equal(denied.error,'file_scope_read_only');
    assert.equal(files.has(readOnlyRoot+'/denied.bin'),false);
    const done=await python(['file-upload','--root',safRoot,'--path','external.bin','--input',input]);assert.equal(done.result.publication_atomic,false);assert.equal(done.result.verification,'current_stat_sha256');
  });
  await check('SAF publishing resumes its recorded document with one creation and fresh verification',async()=>{
    publicationPause=true;const before=externalCreates;
    const args=['file-upload','--root',safRoot,'--path','publishing.bin','--input',input];
    const failed=await python(args,'',1);assert.equal(failed.error,'connection_changed_outcome_unknown');assert.equal(externalCreates,before+1);
    const stopped=seenCommands.length;const result=await python(args);assert.equal(result.result.state,'finished');assert.equal(externalCreates,before+1);
    assert.deepEqual(seenCommands.slice(stopped).map(x=>x.action),['file_upload_status','file_upload_finish','file_stat']);assert.deepEqual(files.get(safRoot+'/publishing.bin'),bytes);
  });
  await check('SAF name-conflict cleanup is explicit and never deletes a finished neighbour',async()=>{
    publicationNameConflict=true;const args=['file-upload','--root',safRoot,'--path','conflict.bin','--input',input];
    const failed=await python(args,'',1);assert.equal(failed.error,'file_name_conflict');
    const stopped=seenCommands.length;const retry=await python(args,'',1);assert.equal(retry.error,'file_cleanup_required');assert.deepEqual(seenCommands.slice(stopped).map(x=>x.action),['file_upload_status']);
    const aborted=await python(['file-upload-abort','--transfer-id',failed.transfer_id]);assert.equal(aborted.result.state,'aborted');assert.equal(files.has(safRoot+'/conflict.bin'),false);assert.deepEqual(files.get(safRoot+'/external.bin'),bytes);
  });
  await check('SAF unknown creation and expired receipt never trigger blind recreation or deletion',async()=>{
    publicationUnknown=true;const before=externalCreates;
    const args=['file-upload','--root',safRoot,'--path','unknown.bin','--input',input];
    const failed=await python(args,'',1);assert.equal(failed.error,'file_commit_unknown');assert.equal(externalCreates,before+1);assert.match(failed.instruction,/Inspect/);
    const stopped=seenCommands.length;const retry=await python(args,'',1);assert.equal(retry.error,'file_commit_unknown');assert.deepEqual(seenCommands.slice(stopped).map(x=>x.action),['file_upload_status']);
    transfers.delete(failed.transfer_id);const expiredAt=seenCommands.length;
    const expired=await python(args,'',1);assert.equal(expired.error,'file_external_transfer_not_found');assert.deepEqual(seenCommands.slice(expiredAt).map(x=>x.action),['file_upload_status']);assert.equal(externalCreates,before+1);
  });
  await check('fresh sequence remains mandatory and revoke fences file HTTP/MCP access',async()=>{
    assert.equal((await api('file_roots',lastSequence)).status,409);
    await python(['revoke']);assert.equal((await api('file_roots',lastSequence+1)).status,410);
    assert.equal((await mcp('tools/call',{name:'phone_file_roots',arguments:{sequence:lastSequence+1}})).status,410);
  });
} finally {
  phone?.terminate(); edge.closeAllConnections(); await new Promise(resolve => edge.close(resolve)); await mf.dispose(); await rm(temp, { recursive: true, force: true });
  const output = resolve(option('--output') ?? join(root, 'relay/service/results/file-transfer-roundtrip-v040.json')); await mkdir(join(output, '..'), { recursive: true });
  await writeFile(output, JSON.stringify({ checkedAt: new Date().toISOString(), scope: 'Real workerd, production relay + real Python CLI + remote MCP. Synthetic file executor, not Android SAF or phone acceptance.', serviceSourceSha256: digest(source), coreSourceSha256: digest(core), cliSha256: digest(cli), passed: checks.filter(c => c.ok).length, total: checks.length, checks }, null, 2) + '\n');
}
