#!/usr/bin/env node
// Production modules, real workerd, real Python CLI subprocess, synthetic phone.
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
const { WebSocket } = createRequire(miniflareURL)('ws');
const root = fileURLToPath(new URL('../../../', import.meta.url));
const source = await readFile(join(root, 'relay/service/service.mjs'), 'utf8');
const core = await readFile(join(root, 'relay/worker/worker.mjs'), 'utf8');
const cliPath = join(root, 'cli/airtap.py'), cli = await readFile(cliPath);
const guide = await readFile(join(root, 'docs/agent.md'));
const digest = value => createHash('sha256').update(value).digest('hex');
const hex = bytes => randomBytes(bytes).toString('hex');
const temp = await realpath(await mkdtemp(join(tmpdir(), 'airtap-cli-v3-')));
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
const checks = []; let phone; const seenCommands = []; let pingState = {}; let gestureError, commandError = null, observedPackage = 'com.example.app';
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
let pagedMode = false, pagedSnapshot = null; const longText = ('文字🚀e\u0301').repeat(2000);
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
    let result;
    if (cmd.action === 'observe') result = { package: observedPackage, observation_id: hex(16), expires_in_ms: 30000,
      coordinate_space: 'normalized_image', width: 720, height: 1600, display_id: 0, window_id: 3, rotation: 0, capture_scope: 'display', window_bounds: [0,40,1080,2200],
      mime_type: 'image/jpeg', image_base64: Buffer.from([255,216,255,217]).toString('base64') };
    else if (pagedMode && ['read_tree', 'read_tree_page'].includes(cmd.action)) {
      if (cmd.action === 'read_tree') pagedSnapshot = hex(16);
      const offset = cmd.offset ?? 0, end = Math.min(300, offset + (cmd.limit ?? 128));
      result = { package: 'com.example.app', snapshot_id: pagedSnapshot, expires_in_ms: 30000, offset, returned_nodes: end-offset, total_nodes: 300, total_inspected_nodes: 300, traversal_complete: true, next_offset: end < 300 ? end : null, truncated: end < 300, nodes: Array.from({length:end-offset}, (_,i)=>({node_id:String(offset+i+1), text:'fixture'})) };
    }
    else if (cmd.action === 'read_node_text') { let end = Math.min(longText.length, cmd.offset + cmd.length); if (end < longText.length && /[\uD800-\uDBFF]/.test(longText[end-1])) end--;
      result = { package: cmd.package, snapshot_id: cmd.snapshot_id, node_id: cmd.node_id, field: cmd.field, text: longText.slice(cmd.offset,end), offset_utf16:cmd.offset, length_utf16:end-cmd.offset, offset_unit:'utf16', total_length_utf16:longText.length, next_offset_utf16:end < longText.length ? end : null, eof:end===longText.length };
    }
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
    assert.equal(invitation.cli_sha256, digest(cli)); assert.equal(invitation.agent_sha256, digest(guide));
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
  await check('real CLI ready, native tree action and automatic post-action image observation', async () => {
    phone = await connectPhone(); assert.equal((await python(['wait-ready', '--wait-seconds', '5'])).ready, true);
    const tree = (await python(['read-tree'])).result;
    const click = await python(['click', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '1']);
    assert.equal(click.result.accepted, true); assert.ok(click.observation.output); assert.equal(click.observation.coordinate_space, 'normalized_image');
    const tap = await python(['tap', '--package', click.observation.package, '--observation-id', click.observation.observation_id, '--x', '0.5', '--y', '0.5']); assert.equal(tap.result.accepted, true);
    const stale = await python(['tap', '--package', click.observation.package, '--observation-id', click.observation.observation_id, '--x', '0.5', '--y', '0.5'], '', 1); assert.equal(stale.error, 'fresh_observation_required');
  });
  await check('framework android package works across CLI observation, node targets and MCP', async () => {
    observedPackage = 'android';
    const observed = (await python(['observe'])).result; assert.equal(observed.package, 'android');
    const tap = await python(['tap', '--package', 'android', '--observation-id', observed.observation_id, '--x', '0.5', '--y', '0.5', '--no-observe']);
    assert.equal(tap.result.accepted, true); assert.equal(seenCommands.at(-1).package, 'android');
    const tree = (await python(['read-tree', '--package', 'android'])).result;
    const click = await python(['click', '--package', 'android', '--snapshot-id', tree.snapshot_id, '--node-id', '1', '--no-observe']);
    assert.equal(click.result.accepted, true); assert.equal(seenCommands.at(-1).package, 'android');
    const mcpResult = await (await mcp('tools/call', { name: 'phone_read_tree', arguments: { sequence: lastSequence + 1, package: 'android' } })).json();
    assert.equal(mcpResult.result.isError, false); assert.equal(seenCommands.at(-1).package, 'android');
    observedPackage = 'com.example.app'; await python(['status']);
  });
  await check('framework exception rejects other single labels and retains owner surface protection', async () => {
    const before = seenCommands.length;
    for (const pkg of ['Android', 'androidx', 'system', 'android ', ' android', 'android\n', 'android..app', 'android/ResolverActivity']) {
      assert.equal((await python(['read-tree', '--package', pkg], '', 1)).error, 'invalid_or_protected_package');
      assert.equal((await api('read_tree', lastSequence + 1, { package: pkg })).status, 400);
      const result = await (await mcp('tools/call', { name: 'phone_read_tree', arguments: { sequence: lastSequence + 1, package: pkg } })).json();
      assert.equal(result.result.isError, true);
    }
    for (const pkg of ['io.airtap.preview', 'io.airtap.preview.future']) {
      assert.equal((await python(['read-tree', '--package', pkg], '', 1)).error, 'invalid_or_protected_package');
      assert.equal((await api('read_tree', lastSequence + 1, { package: pkg })).status, 403);
      const result = await (await mcp('tools/call', { name: 'phone_read_tree', arguments: { sequence: lastSequence + 1, package: pkg } })).json();
      assert.equal(result.result.isError, true);
    }
    assert.equal(seenCommands.length, before);
  });
  await check('paged tree and long Unicode text traverse real CLI/workerd without renewing snapshot TTL', async () => {
    pagedMode = true;
    const tree = (await python(['read-tree'])).result;
    const first = JSON.parse(await readFile(config)).snapshot;
    const nodes = [...tree.nodes]; let offset = tree.next_offset;
    while (offset !== null) {
      const page = (await python(['read-tree-page', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--offset', String(offset)])).result;
      assert.equal(page.snapshot_id, tree.snapshot_id); nodes.push(...page.nodes); offset = page.next_offset;
      assert.ok(JSON.parse(await readFile(config)).snapshot.expires_at <= first.expires_at);
    }
    assert.equal(new Set(nodes.map(n => n.node_id)).size, 300); assert.equal(nodes.at(-1).node_id, '300');
    let text = '', cursor = 0;
    while (cursor !== null) {
      const chunk = (await python(['read-node-text', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '300', '--offset', String(cursor), '--length', '4096'])).result;
      text += chunk.text; cursor = chunk.next_offset_utf16;
    }
    assert.equal(text, longText);
    assert.equal((await python(['click', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '300', '--no-observe'])).result.accepted, true);
    assert.equal((await python(['read-node-text', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '300'], '', 1)).error, 'fresh_snapshot_required');
    pagedMode = false;
  });
  await check('page and long-text strict invalid shapes never dispatch; MCP schemas and readonly annotation match', async () => {
    const target = { package:'com.example.app', snapshot_id:hex(16) }, before = seenCommands.length;
    const cases = [
      ['read_tree_page', {...target,offset:-1,limit:128}], ['read_tree_page', {...target,offset:0,limit:129}],
      ['read_tree_page', {...target,offset:0.5,limit:1}], ['read_tree_page', {...target,offset:true,limit:1}],
      ['read_tree_page', {...target,offset:2049,limit:1}], ['read_tree_page', {...target,offset:0,limit:1,extra:true}],
      ['read_node_text', {...target,node_id:'300',field:'text',offset:0,length:1}], ['read_node_text', {...target,node_id:'2049',field:'text',offset:0,length:4096}],
      ['read_node_text', {...target,node_id:'300',field:'password',offset:0,length:4096}], ['read_node_text', {...target,node_id:'300',field:'text',offset:2147483648,length:4096}],
      ['read_node_text', {...target,node_id:'300',field:'text',offset:0,length:4097}], ['read_node_text', {...target,node_id:'300',field:'text',offset:0,length:true}],
    ];
    for (const [action,args] of cases) assert.equal((await api(action,lastSequence+1,args)).status,400);
    assert.equal((await api('read_tree_page',lastSequence+1,{...target,package:'io.airtap.preview',offset:0,limit:1})).status,403);
    assert.equal(seenCommands.length,before);
    const listed = await (await mcp('tools/list',{})).json();
    for (const name of ['phone_read_tree_page','phone_read_node_text']) {
      const spec=listed.result.tools.find(t=>t.name===name);assert.equal(spec.annotations.readOnlyHint,true);assert.equal(spec.inputSchema.additionalProperties,false);
    }
    pagedMode = true; const tree=(await python(['read-tree'])).result;
    const page=await (await mcp('tools/call',{name:'phone_read_tree_page',arguments:{...target,snapshot_id:tree.snapshot_id,offset:128,limit:128,sequence:lastSequence+1}})).json();
    assert.equal(page.result.isError,false);assert.equal(seenCommands.at(-1).action,'read_tree_page');
    const text=await (await mcp('tools/call',{name:'phone_read_node_text',arguments:{...target,snapshot_id:tree.snapshot_id,node_id:'300',field:'description',offset:0,length:4096,sequence:lastSequence+1}})).json();
    assert.equal(text.result.isError,false);assert.equal(seenCommands.at(-1).field,'description');
    pagedMode=false;await python(['status']);
  });
  await check('atomic input real CLI preserves Unicode, selection offsets and editor arguments', async () => {
    const examples = [
      ['focus', [], {}], ['set-selection', ['--start', '1', '--end', '3'], { start: 1, end: 3 }],
      ['select-all', [], {}], ['copy', [], {}], ['cut', [], {}], ['paste', [], {}],
      ['insert-text', ['--text-stdin'], { text: '中文 🫶 שלום\nnext' }],
      ['delete-text', ['--before', '2', '--after', '1'], { before: 2, after: 1 }],
      ['editor-action', ['--editor-action', 'search'], { editor_action: 'search' }],
    ];
    for (const [action, args, expected] of examples) {
      const tree = (await python(['read-tree'])).result;
      const result = await python([action, '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '1', ...args, '--no-observe'], expected.text ?? '');
      assert.equal(result.result.accepted, true); assert.equal(seenCommands.at(-1).action, action.replaceAll('-', '_'));
      for (const [key, value] of Object.entries(expected)) assert.equal(seenCommands.at(-1)[key], value);
      const stale = await python([action, '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '1', ...args, '--no-observe'], expected.text ?? '', 1);
      assert.equal(stale.error, 'fresh_snapshot_required');
    }
  });
  await check('invalid atomic requests and owner-surface targets never reach the phone', async () => {
    const target = { package: 'com.example.app', snapshot_id: hex(16), node_id: '1' };
    const cases = [
      ['set_selection', { ...target, start: -1, end: 0 }], ['set_selection', { ...target, start: true, end: 0 }],
      ['set_selection', { ...target, start: 0, end: 2147483648 }],
      ['delete_text', { ...target, before: 0, after: 0 }], ['delete_text', { ...target, before: 2001, after: 0 }],
      ['editor_action', { ...target, editor_action: 'shell' }], ['insert_text', { ...target, text: String.fromCharCode(0xd800) }],
      ['system_action', { name: 'arbitrary_key' }], ['system_action', { name: 'dpad_center', package: 'io.airtap.preview' }],
    ];
    const before = seenCommands.length;
    for (const [action, args] of cases) assert.equal((await api(action, lastSequence + 1, args)).status, 400);
    assert.equal((await api('paste', lastSequence + 1, { ...target, package: 'io.airtap.preview' })).status, 403);
    assert.equal(seenCommands.length, before);
  });
  await check('system action CLI observes normally but never auto-wakes a requested lock', async () => {
    await python(['system-actions']); assert.equal(seenCommands.at(-1).action, 'system_actions');
    await python(['system-action', '--name', 'notifications']); assert.equal(seenCommands.at(-1).action, 'observe');
    const before = seenCommands.length;
    const locked = await python(['system-action', '--name', 'lock_screen']);
    assert.equal(locked.result.accepted, true); assert.equal(locked.observation, undefined);
    assert.equal(seenCommands.length, before + 1); assert.equal(seenCommands.at(-1).name, 'lock_screen');
    await python(['system-action', '--name', 'dpad_center', '--no-observe']); assert.equal(seenCommands.at(-1).name, 'dpad_center');
  });
  await check('MCP exposes atomic schemas and passes Unicode to the same guarded relay', async () => {
    const list = (await (await mcp('tools/list', {})).json()).result.tools;
    for (const name of ['phone_insert_text', 'phone_set_selection', 'phone_editor_action', 'phone_system_action', 'phone_system_actions']) assert.ok(list.some(x => x.name === name));
    assert.equal(list.find(x => x.name === 'phone_system_actions').annotations.readOnlyHint, true);
    const text = 'MCP 中文 🫶';
    const result = await (await mcp('tools/call', { name: 'phone_insert_text', arguments: { sequence: lastSequence + 1, package: 'com.example.app', snapshot_id: hex(16), node_id: '1', text } })).json();
    assert.equal(result.result.isError, false); assert.equal(seenCommands.at(-1).text, text);
    await python(['status']);
  });
  await check('volume discovery and explicit streams traverse CLI without screenshot side effects', async () => {
    const discovery = await (await http('/v1/service')).json(); assert.ok(discovery.capabilities.includes('volume_v1'));
    assert.equal((await python(['audio-state'])).result.streams[0].volume.max, 15);
    for (const stream of ['music', 'alarm', 'ring', 'notification', 'system', 'voice_call']) {
      const start = seenCommands.length; const result = await python(['set-volume', '--stream', stream, '--level', '4']);
      assert.equal(result.result.verification, 'native_readback'); assert.equal(result.observation, undefined);
      assert.equal(seenCommands.length, start + 1); assert.equal(seenCommands.at(-1).stream, stream); assert.equal(seenCommands.at(-1).level, 4);
    }
    for (const direction of ['raise', 'lower', 'mute', 'unmute']) {
      await python(['adjust-volume', '--stream', 'music', '--direction', direction]);
      assert.equal(seenCommands.at(-1).direction, direction);
    }
  });
  await check('volume shape rejects ambiguous stream, flags, nonintegers and hidden extra arguments before phone dispatch', async () => {
    const start = seenCommands.length;
    const invalid = [['set_volume', { stream: 'active', level: 2 }], ['set_volume', { stream: 'music', level: true }], ['set_volume', { stream: 'music', level: -1 }], ['set_volume', { stream: 'music', level: 1.5 }], ['set_volume', { stream: 'music', level: 2147483648 }], ['set_volume', { stream: 'music', level: 3, flags: 1 }], ['adjust_volume', { stream: 'music', direction: 'toggle' }], ['adjust_volume', { stream: 'music', direction: 'raise', package: 'com.example.app' }], ['audio_state', { stream: 'music' }]];
    for (const [action, args] of invalid) assert.equal((await api(action, lastSequence + 1, args)).status, 400);
    assert.equal((await python(['set-volume', '--stream', 'music', '--level', '-1'], '', 1)).error, 'invalid_volume_level');
    assert.equal(seenCommands.length, start);
  });
  await check('volume device policy rejection and uncertain outcome remain visible without replay', async () => {
    for (const code of ['volume_fixed', 'audio_permission_or_policy_denied', 'audio_action_outcome_unknown']) {
      commandError = { code }; const start = seenCommands.length;
      const result = await python(['set-volume', '--stream', 'music', '--level', '4'], '', 1);
      assert.equal(result.error, code); assert.equal(result.retry_action, false); assert.equal(seenCommands.length, start + 1);
    }
    commandError = null;
  });
  await check('MCP volume schemas distinguish readonly discovery and guarded mutation', async () => {
    const list = (await (await mcp('tools/list', {})).json()).result.tools;
    assert.equal(list.find(tool => tool.name === 'phone_audio_state').annotations.readOnlyHint, true);
    assert.equal(list.find(tool => tool.name === 'phone_set_volume').annotations.readOnlyHint, false);
    assert.deepEqual(list.find(tool => tool.name === 'phone_adjust_volume').inputSchema.properties.direction.enum, ['raise', 'lower', 'mute', 'unmute']);
    const result = await (await mcp('tools/call', { name: 'phone_set_volume', arguments: { sequence: lastSequence + 1, stream: 'alarm', level: 3 } })).json();
    assert.equal(result.result.isError, false); assert.equal(seenCommands.at(-1).stream, 'alarm');
    const start = seenCommands.length;
    const invalid = await (await mcp('tools/call', { name: 'phone_set_volume', arguments: { sequence: lastSequence + 1, stream: 'music', level: 3, flags: 1 } })).json();
    assert.equal(invalid.error.code, -32602); assert.equal(seenCommands.length, start);
    await python(['status']);
  });
  await check('held drag traverses CLI, relay and MCP with timed edge dwell and a fresh post-action observation', async () => {
    const service = await (await http('/v1/service')).json(); assert.ok(service.capabilities.includes('drag_v1'));
    const observed = (await python(['observe'])).result;
    const start = seenCommands.length;
    const drag = await python(['drag', '--package', observed.package, '--observation-id', observed.observation_id,
      '--x', '0.2', '--y', '0.3', '--hold-ms', '700', '--to', '0.98', '0.5', '750', '--to', '0.98', '0.5', '1000', '--to', '0.6', '0.5', '650']);
    assert.equal(drag.result.accepted, true); assert.ok(drag.observation.output);
    assert.equal(seenCommands.length, start + 2); // Exactly one drag then one observe, never long_press + swipe.
    const dispatched = seenCommands[start]; assert.equal(dispatched.action, 'drag'); assert.equal(dispatched.hold_ms, 700);
    assert.deepEqual(dispatched.path, [{ x: 0.98, y: 0.5, duration_ms: 750 }, { x: 0.98, y: 0.5, duration_ms: 1000 }, { x: 0.6, y: 0.5, duration_ms: 650 }]);
    const stale = await python(['drag', '--package', observed.package, '--observation-id', observed.observation_id,
      '--x', '0.2', '--y', '0.3', '--to', '0.6', '0.5', '700'], '', 1); assert.equal(stale.error, 'fresh_observation_required');
    const list = await (await mcp('tools/list', {})).json(); const schema = list.result.tools.find(tool => tool.name === 'phone_drag').inputSchema;
    assert.equal(schema.properties.path.maxItems, 16); assert.equal(schema.properties.path.items.additionalProperties, false);
    const args = { sequence: lastSequence + 1, package: drag.observation.package, observation_id: drag.observation.observation_id,
      x: 0.2, y: 0.3, hold_ms: 700, path: dispatched.path };
    const result = await (await mcp('tools/call', { name: 'phone_drag', arguments: args })).json();
    assert.equal(result.result.isError, false); assert.equal(seenCommands.at(-1).action, 'drag');
    assert.deepEqual(seenCommands.at(-1).path, args.path);
    assert.equal((await api('drag', lastSequence, args)).status, 409);
    await python(['status']); // MCP advanced the shared sequence; resync the CLI identity.
  });
  await check('malformed drag timing/path/coordinates are rejected without dispatch or observation consumption', async () => {
    const observed = (await python(['observe'])).result;
    const base = { package: observed.package, observation_id: observed.observation_id, x: 0.2, y: 0.3, hold_ms: 700,
      path: [{ x: 0.6, y: 0.5, duration_ms: 700 }] };
    const invalid = [
      { hold_ms: 99 }, { hold_ms: 3001 }, { hold_ms: 700.5 }, { hold_ms: true }, { path: [] }, { path: null },
      { path: Array.from({ length: 17 }, () => base.path[0]) }, { path: [{ x: 1, y: 0.5, duration_ms: 700 }] },
      { path: [{ x: 0.5, y: '0.5', duration_ms: 700 }] }, { path: [{ x: 0.5, y: 0.5, duration_ms: 700, extra: 1 }] },
      { path: [{ x: 0.5, y: 0.5, duration_ms: 0 }] }, { path: [{ x: 0.5, y: 0.5, duration_ms: 5001 }] },
      { path: [{ x: 0.5, y: 0.5, duration_ms: 1.5 }] }, { path: [{ x: 0.5, y: 0.5, duration_ms: true }] },
      { path: [{ x: 0.5, y: 0.5, duration_ms: 5000 }, { x: 0.6, y: 0.5, duration_ms: 4301 }] },
      { extra: 'unsupported' }, { x: -0.1 }, { observation_id: 'not-a-recent-image' },
    ];
    const before = seenCommands.length;
    for (const change of invalid) assert.equal((await api('drag', lastSequence + 1, { ...base, ...change })).status, 400);
    assert.equal((await api('drag', lastSequence + 1, { ...base, package: 'io.airtap.preview' })).status, 403);
    for (const flags of [['--hold-ms', '99', '--to', '0.6', '0.5', '700'], ['--to', '0.6', '0.5', '1.5'],
        ['--to', 'NaN', '0.5', '700'], ['--to', '0.6', '0.5', '5000', '--to', '0.6', '0.5', '4301']]) {
      const result = await python(['drag', '--package', observed.package, '--observation-id', observed.observation_id, '--x', '0.2', '--y', '0.3', ...flags], '', 1);
      assert.equal(result.error, 'invalid_drag');
    }
    assert.equal(seenCommands.length, before);
    // A rejected local shape must leave the fresh observation usable.
    const result = await python(['drag', '--package', observed.package, '--observation-id', observed.observation_id,
      '--x', '0.2', '--y', '0.3', '--to', '0.6', '0.5', '700', '--no-observe']); assert.equal(result.result.accepted, true);
    assert.equal(seenCommands.at(-1).hold_ms, 700);
  });
  await check('multi-touch tracks traverse CLI/HTTP/MCP unchanged; partial outcomes are never replayed', async () => {
    const service = await (await http('/v1/service')).json(); assert.ok(service.capabilities.includes('gesture_v1'));
    const tracks = [
      { start_ms: 0, x: 0.2, y: 0.2, path: [{ x: 0.8, y: 0.8, duration_ms: 500 }] },
      { start_ms: 150, x: 0.4, y: 0.4, path: [{ x: 0.6, y: 0.4, duration_ms: 100 }] },
      { start_ms: 200, x: 0.7, y: 0.2, path: [{ x: 0.7, y: 0.6, duration_ms: 250 }] },
    ];
    const observed = (await python(['observe'])).result, before = seenCommands.length;
    const result = await python(['gesture', '--package', observed.package, '--observation-id', observed.observation_id, '--gesture-stdin'], JSON.stringify(tracks));
    assert.equal(result.result.accepted, true); assert.ok(result.observation.output); assert.equal(seenCommands.length, before + 2);
    assert.equal(seenCommands[before].action, 'gesture'); assert.deepEqual(seenCommands[before].tracks, tracks);
    const list = await (await mcp('tools/list', {})).json(); const tool = list.result.tools.find(x => x.name === 'phone_gesture');
    assert.equal(tool.inputSchema.properties.tracks.maxItems, 10); assert.equal(tool.inputSchema.properties.tracks.items.additionalProperties, false);
    const args = { sequence: lastSequence + 1, package: result.observation.package, observation_id: result.observation.observation_id, tracks };
    const call = await (await mcp('tools/call', { name: 'phone_gesture', arguments: args })).json();
    assert.equal(call.result.isError, false); assert.deepEqual(seenCommands.at(-1).tracks, tracks);
    await python(['status']);
    const fresh = (await python(['observe'])).result, count = seenCommands.length;
    gestureError = 'gesture_cancelled_outcome_unknown';
    const failure = await python(['gesture', '--package', fresh.package, '--observation-id', fresh.observation_id, '--gesture-stdin'], JSON.stringify(tracks), 1);
    gestureError = undefined;
    assert.equal(failure.outcome, 'unknown'); assert.equal(failure.retry_action, false); assert.equal(seenCommands.length, count + 1);
    const replay = await python(['gesture', '--package', fresh.package, '--observation-id', fresh.observation_id, '--gesture-stdin'], JSON.stringify(tracks), 1);
    assert.equal(replay.error, 'fresh_observation_required'); assert.equal(seenCommands.length, count + 1);
  });
  await check('gesture contract rejects malformed tracks before admission and accepts staggered double-tap', async () => {
    const observed = (await python(['observe'])).result;
    const track = { start_ms: 0, x: 0.5, y: 0.5, path: [{ x: 0.5, y: 0.5, duration_ms: 60 }] };
    const invalid = [[], null, Array.from({ length: 11 }, () => track), [{ ...track, start_ms: true }], [{ ...track, start_ms: -1 }],
      [{ ...track, start_ms: 0.5 }], [{ ...track, start_ms: 9999 }], [{ ...track, x: 1 }], [{ ...track, y: '0.5' }],
      [{ ...track, path: [] }], [{ ...track, path: Array.from({ length: 33 }, () => track.path[0]) }],
      [{ ...track, path: [{ ...track.path[0], duration_ms: 0 }] }], [{ ...track, path: [{ ...track.path[0], duration_ms: 5001 }] }],
      [{ ...track, path: [{ ...track.path[0], duration_ms: true }] }], [{ ...track, path: [{ ...track.path[0], pressure: 1 }] }],
      [{ ...track, extra: 'unsupported' }],
    ];
    const before = seenCommands.length;
    for (const tracks of invalid) {
      assert.equal((await api('gesture', lastSequence + 1, { package: observed.package, observation_id: observed.observation_id, tracks })).status, 400);
      const result = await python(['gesture', '--package', observed.package, '--observation-id', observed.observation_id, '--gesture-stdin'], JSON.stringify(tracks), 1);
      assert.equal(result.error, 'invalid_gesture');
    }
    for (const raw of ['{bad', '['.repeat(1200) + ']'.repeat(1200)]) {
      const result = await python(['gesture', '--package', observed.package, '--observation-id', observed.observation_id, '--gesture-stdin'], raw, 1);
      assert.equal(result.error, 'invalid_gesture');
    }
    const oversized = await python(['gesture', '--package', observed.package, '--observation-id', observed.observation_id, '--gesture-stdin'], ' '.repeat(8193), 1);
    assert.equal(oversized.error, 'request_too_large');
    assert.equal(seenCommands.length, before);
    const tracks = [track, { ...track, start_ms: 160 }];
    const result = await python(['gesture', '--package', observed.package, '--observation-id', observed.observation_id, '--gesture-stdin', '--no-observe'], JSON.stringify(tracks));
    assert.equal(result.result.accepted, true); assert.deepEqual(seenCommands.at(-1).tracks, tracks);
  });
  await check('semantic node operations pass through real CLI with exact parameter shapes', async () => {
    const noArgs = ['accessibility_focus', 'clear_accessibility_focus', 'select', 'clear_selection', 'clear_focus', 'context_click', 'expand', 'collapse', 'dismiss', 'show_on_screen', 'scroll_up', 'scroll_down', 'scroll_left', 'scroll_right', 'page_up', 'page_down', 'page_left', 'page_right', 'drag_start', 'drag_drop', 'drag_cancel'];
    const examples = [...noArgs.map(operation => [operation, [], {}]), ['scroll_to_position', ['--row', '3', '--column', '2'], { row: 3, column: 2 }], ['set_progress', ['--value', '0.625'], { value: 0.625 }], ['custom', ['--action-id', '2130837795'], { action_id: 2130837795 }], ['next_granularity', ['--granularity', 'word', '--extend-selection', 'true'], { granularity: 'word', extend_selection: true }], ['previous_granularity', ['--granularity', 'character', '--extend-selection', 'false'], { granularity: 'character', extend_selection: false }]];
    for (const [operation, args, expected] of examples) {
      const tree = (await python(['read-tree'])).result;
      const result = await python(['node-action', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '1', '--operation', operation, ...args, '--no-observe']);
      assert.equal(result.result.accepted, true); assert.equal(seenCommands.at(-1).action, 'node_action'); assert.equal(seenCommands.at(-1).operation, operation);
      for (const [key, value] of Object.entries(expected)) assert.equal(seenCommands.at(-1)[key], value);
      const stale = await python(['node-action', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '1', '--operation', operation, ...args, '--no-observe'], '', 1);
      assert.equal(stale.error, 'fresh_snapshot_required');
    }
  });
  await check('semantic request shapes and protected targets are rejected before dispatch', async () => {
    const target = { package: 'com.example.app', snapshot_id: hex(16), node_id: '1' };
    const before = seenCommands.length;
    const invalid = [
      { operation: 'arbitrary_action' }, { operation: 'expand', value: 1 },
      { operation: 'scroll_to_position', row: 1 }, { operation: 'scroll_to_position', row: -1, column: 0 },
      { operation: 'scroll_to_position', row: true, column: 0 }, { operation: 'scroll_to_position', row: 1.5, column: 0 },
      { operation: 'scroll_to_position', row: 2147483648, column: 0 },
      { operation: 'set_progress' }, { operation: 'set_progress', value: '3' }, { operation: 'set_progress', value: 1e39 },
      { operation: 'set_progress', value: 3, row: 1 }, { operation: 'drag_start', column: 0 },
      { operation: 'custom', action_id: 1 }, { operation: 'custom', action_id: 16908342 }, { operation: 'custom', action_id: true },
      { operation: 'custom', action_id: 2147483648 }, { operation: 'custom', action_id: 2130837795, bundle: {} },
      { operation: 'custom', action_id: 2130837795, label: 'trusted instruction' },
      { operation: 'next_granularity', granularity: 'word' }, { operation: 'next_granularity', granularity: 3, extend_selection: true },
      { operation: 'previous_granularity', granularity: 'word', extend_selection: 'false' },
      { operation: 'next_granularity', granularity: 'sentence', extend_selection: false },
      { operation: 'clear_accessibility_focus', action_id: 2130837795 },
    ];
    for (const args of invalid) assert.equal((await api('node_action', lastSequence + 1, { ...target, ...args })).status, 400);
    assert.equal((await api('node_action', lastSequence + 1, { ...target, package: 'io.airtap.preview', operation: 'expand' })).status, 403);
    assert.equal(seenCommands.length, before);
    const tree = (await python(['read-tree'])).result;
    for (const [args, error] of [
      [['--operation', 'set_progress', '--value', 'nan'], 'invalid_node_progress'],
      [['--operation', 'scroll_to_position', '--row', '0'], 'invalid_node_position'],
      [['--operation', 'collapse', '--column', '1'], 'unexpected_node_argument'],
      [['--operation', 'custom', '--action-id', '1'], 'invalid_custom_action'],
      [['--operation', 'custom', '--action-id', '2130837795', '--value', '1'], 'invalid_custom_action'],
      [['--operation', 'next_granularity', '--granularity', 'word'], 'invalid_node_granularity'],
      [['--operation', 'previous_granularity', '--granularity', 'word', '--extend-selection', 'true', '--column', '1'], 'invalid_node_granularity'],
      [['--operation', 'accessibility_focus', '--action-id', '2130837795'], 'unexpected_node_argument'],
    ]) {
      const result = await python(['node-action', '--package', tree.package, '--snapshot-id', tree.snapshot_id, '--node-id', '1', ...args, '--no-observe'], '', 1);
      assert.equal(result.error, error);
    }
    assert.equal(seenCommands.length, before + 1);
  });
  await check('MCP discovers semantic controls and shares strict HTTP validation', async () => {
    const tools = (await (await mcp('tools/list', {})).json()).result.tools;
    const spec = tools.find(tool => tool.name === 'phone_node_action');
    assert.ok(spec); assert.equal(spec.annotations.readOnlyHint, false);
    assert.ok(spec.inputSchema.properties.operation.enum.includes('set_progress'));
    for (const operation of ['accessibility_focus', 'clear_accessibility_focus', 'custom', 'next_granularity', 'previous_granularity']) assert.ok(spec.inputSchema.properties.operation.enum.includes(operation));
    assert.equal(spec.inputSchema.properties.extend_selection.type, 'boolean');
    assert.equal(spec.inputSchema.properties.action_id.minimum, 33554432);
    const target = { package: 'com.example.app', snapshot_id: hex(16), node_id: '1' };
    const valid = await (await mcp('tools/call', { name: 'phone_node_action', arguments: { sequence: lastSequence + 1, ...target, operation: 'set_progress', value: 4.25 } })).json();
    assert.equal(valid.result.isError, false); assert.equal(seenCommands.at(-1).value, 4.25);
    for (const args of [{ operation: 'custom', action_id: 2130837795 }, { operation: 'next_granularity', granularity: 'line', extend_selection: false }, { operation: 'accessibility_focus' }]) {
      const result = await (await mcp('tools/call', { name: 'phone_node_action', arguments: { sequence: lastSequence + 1, ...target, ...args } })).json();
      assert.equal(result.result.isError, false);
      for (const [key, value] of Object.entries(args)) assert.equal(seenCommands.at(-1)[key], value);
    }
    const before = seenCommands.length;
    const bad = await (await mcp('tools/call', { name: 'phone_node_action', arguments: { sequence: lastSequence + 1, ...target, operation: 'dismiss', value: 4.25 } })).json();
    assert.equal(bad.result.isError, true); assert.equal(seenCommands.length, before);
    await python(['status']);
  });
  await check('CLI readiness distinguishes authentication, automatic preparation and legacy devices without polling or re-pairing', async () => {
    const cases = [
      { ping: {}, ready: true, screen: 'unknown', screenReady: null, prepare: false, auth: null },
      { ping: { device_unlocked: false }, ready: false, screen: 'unknown', screenReady: false, prepare: false, auth: null },
      { ping: { device_unlocked: true }, ready: true, screen: 'unknown', screenReady: true, prepare: false, auth: null },
      { ping: { screen_state: 'ready', auto_wake: true }, ready: true, screen: 'ready', screenReady: true, prepare: false, auth: false },
      { ping: { screen_state: 'screen_off', auto_wake: true }, ready: true, screen: 'screen_off', screenReady: false, prepare: true, auth: false },
      { ping: { screen_state: 'dismissible_keyguard', auto_wake: true }, ready: true, screen: 'dismissible_keyguard', screenReady: false, prepare: true, auth: false },
      { ping: { screen_state: 'authentication_required', auto_wake: true }, ready: false, screen: 'authentication_required', screenReady: false, prepare: false, auth: true },
      { ping: { screen_state: 'unavailable', auto_wake: true }, ready: false, screen: 'unavailable', screenReady: false, prepare: false, auth: null },
      { ping: { screen_state: 'screen_off', auto_wake: false }, ready: false, screen: 'screen_off', screenReady: false, prepare: false, auth: false },
    ];
    for (const sample of cases) {
      pingState = sample.ping; const before = seenCommands.length;
      const result = await python(['wait-ready', '--wait-seconds', '5']);
      assert.equal(result.ready, sample.ready); assert.equal(result.connection_ready, true);
      assert.equal(result.screen_state, sample.screen); assert.equal(result.screen_ready, sample.screenReady);
      assert.equal(result.will_prepare_screen, sample.prepare); assert.equal(result.needs_system_authentication, sample.auth);
      assert.equal(result.attempts, 1); assert.equal(result.status.persistent, true);
      assert.equal(seenCommands.length, before + 1); assert.equal(seenCommands.at(-1).action, 'ping');
      assert.equal(JSON.parse(await readFile(config)).controller_token, controller);
    }
    for (const invalid of [{ screen_state: controller }, { screen_state: 'ready', auto_wake: 'yes' }]) {
      pingState = invalid; const before = seenCommands.length;
      const result = await python(['wait-ready', '--wait-seconds', '5'], '', 1);
      assert.equal(result.error, 'invalid_screen_state'); assert.equal(seenCommands.length, before + 1);
    }
    pingState = {};
  });
  await check('window metadata traverses CLI and read-only MCP without implicit window selection', async () => {
    const before = seenCommands.length;
    const value = (await python(['windows'])).result;
    assert.equal(value.windows[0].package, 'com.android.packageinstaller');
    assert.equal(value.windows[0].display_id, 0); assert.equal(value.content_included, false);
    assert.equal(value.window_selection_supported, false); assert.equal(seenCommands.length, before + 1);
    const listed = await (await mcp('tools/list', {})).json(); const schema = listed.result.tools.find(t => t.name === 'phone_windows');
    assert.equal(schema.annotations.readOnlyHint, true); assert.deepEqual(Object.keys(schema.inputSchema.properties), ['sequence']);
    const result = await (await mcp('tools/call', { name: 'phone_windows', arguments: { sequence: lastSequence + 1 } })).json();
    assert.equal(result.result.isError, false); assert.ok(JSON.stringify(result).includes('window_root_unavailable'));
    const dispatched = seenCommands.length;
    assert.equal((await api('windows', lastSequence + 1, { window_id: 3 })).status, 400);
    assert.equal(seenCommands.length, dispatched);
    await python(['status']); // A different controller client advanced the persisted sequence.
    const image = (await python(['observe'])).result;
    assert.equal(image.display_id, 0); assert.equal(image.rotation, 0); assert.equal(image.capture_scope, 'display');
    assert.deepEqual(image.window_bounds, [0,40,1080,2200]);
  });
  await check('unavailable window guidance is retained without navigation or action replay', async () => {
    const before = seenCommands.length;
    const guidance = 'Run windows, then a fresh observe/read_tree after the installer settles. No automatic Back is performed.';
    commandError = { code: 'window_root_unavailable', next_step: guidance };
    const failed = await python(['read-tree'], '', 1);
    assert.equal(failed.error, 'window_root_unavailable'); assert.equal(failed.instruction, guidance); assert.equal(failed.retry_action, false);
    assert.equal(seenCommands.length, before + 1); assert.equal(seenCommands.at(-1).action, 'read_tree');
    commandError = { code: 'window_root_unavailable', next_step: 'invalid\nguidance' };
    assert.equal((await python(['read-tree'], '', 1)).instruction, undefined);
    commandError = null;
  });
  await check('HTTP(S) navigation accepts device-local pages and rejects malformed URLs before dispatch', async () => {
    const cases = JSON.parse(await readFile(join(root, 'relay/service/tests/fixtures/open-url-cases.json'), 'utf8'));
    for (const sample of cases) {
      const before = seenCommands.length;
      if (sample.valid) {
        const result = await python(['open-url', '--package', 'com.example.app', '--url', sample.url, '--no-observe']);
        assert.equal(result.result.accepted, true, sample.name);
        assert.equal(seenCommands.length, before + 1, sample.name);
        assert.equal(seenCommands.at(-1).url, sample.url, 'URL must reach the phone unchanged');
      } else {
        const result = await python(['open-url', '--package', 'com.example.app', '--url', sample.url, '--no-observe'], '', 1);
        assert.equal(result.error, 'invalid_open_url', sample.name);
        assert.equal((await api('open_url', lastSequence + 1, { package: 'com.example.app', url: sample.url })).status, 400, sample.name);
        assert.equal(seenCommands.length, before, sample.name);
      }
    }
    const url = 'http://192.168.1.1:8080/#network';
    const result = await (await mcp('tools/call', { name: 'phone_open_url', arguments: { sequence: lastSequence + 1, package: 'com.example.app', url } })).json();
    assert.equal(result.result.isError, false); assert.equal(seenCommands.at(-1).url, url);
  });
  await check('more than 200 commands remain usable; original sequence replay stays rejected', async () => {
    let seq = lastSequence;
    for (let i = 0; i < 210; i++) { const r = await api('ping', ++seq); assert.equal(r.status, 200); }
    const before = seenCommands.length;
    assert.equal((await api('ping', 1)).status, 409); assert.equal(seenCommands.length, before);
    const status = await python(['status']); assert.equal(status.command_budget_remaining, null); assert.equal(status.command_sequence, seq);
  });
  await check('malformed/unauthorized calls never reach the phone', async () => {
    const before = seenCommands.length, seq = lastSequence + 1;
    assert.equal((await api('launch', seq, { package: 'io.airtap.preview' })).status, 403);
    assert.equal((await api('tap', seq, { package: 'com.example.app', observation_id: hex(16), x: 1, y: 0.5 })).status, 400);
    assert.equal((await mcp('tools/list', {}, hex(32))).status, 401);
    assert.equal((await mcp('tools/list', {}, controller, { Origin: 'https://evil.invalid' })).status, 403);
    assert.equal(seenCommands.length, before);
  });
  await check('remote MCP discovery, image content, sequence fencing and unknown-tool errors', async () => {
    const init = await (await mcp('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'test', version: '1' } })).json(); assert.equal(init.result.protocolVersion, '2025-11-25');
    const list = await (await mcp('tools/list', {})).json(); assert.ok(list.result.tools.some(x => x.name === 'phone_swipe'));
    const r = await (await mcp('tools/call', { name: 'phone_observe', arguments: { sequence: lastSequence + 1 } })).json(); assert.equal(r.result.content[0].type, 'image'); assert.equal(r.result.isError, false);
    const replay = await (await mcp('tools/call', { name: 'phone_observe', arguments: { sequence: lastSequence } })).json(); assert.equal(replay.result.isError, true);
    const bad = await (await mcp('tools/call', { name: 'phone_shell', arguments: {} })).json(); assert.equal(bad.error.code, -32602);
    assert.equal((await fetch(base + '/mcp', { headers: { Authorization: 'Bearer ' + controller } })).status, 405);
  });
  await check('transport takeover preserves scope and monotonic replay state', async () => {
    const prior = lastSequence; const old = phone; phone = await connectPhone(); old.terminate();
    assert.equal((await api('ping', prior)).status, 409); assert.equal((await api('ping', prior + 1)).status, 200);
    const status = await python(['status']); assert.equal(status.persistent, true); assert.equal(status.deviceConnected, true);
  });
  await check('CLI revoke invalidates both HTTP and MCP credentials', async () => {
    assert.equal((await python(['revoke'])).state, 'revoked');
    assert.equal((await api('ping', lastSequence + 1)).status, 410);
    assert.equal((await mcp('tools/list', {})).status, 410);
  });
} finally {
  phone?.terminate(); edge.closeAllConnections(); await new Promise(resolve => edge.close(resolve)); await mf.dispose(); await rm(temp, { recursive: true, force: true });
  const output = resolve(option('--output') ?? join(root, 'relay/service/results/companion-roundtrip-v040.json')); await mkdir(join(output, '..'), { recursive: true });
  await writeFile(output, JSON.stringify({ checkedAt: new Date().toISOString(), scope: 'Real workerd, production relay + real Python CLI + remote MCP. Synthetic phone. Test ingress limit raised to allow >200-command regression in one minute.', serviceSourceSha256: digest(source), coreSourceSha256: digest(core), cliSha256: digest(cli), passed: checks.filter(c => c.ok).length, total: checks.length, checks }, null, 2) + '\n');
}
