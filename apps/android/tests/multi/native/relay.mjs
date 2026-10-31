#!/usr/bin/env node
// Private native-test transport: unchanged production modules in real workerd.
// No fake phone, automatic approval, test backend endpoint, or device operation.
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdir, chmod, realpath } from 'node:fs/promises';
import { createServer } from 'node:https';
import { request as httpRequest } from 'node:http';
import { createInterface } from 'node:readline';
import { join, resolve, relative, isAbsolute } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const emit = value => process.stdout.write(JSON.stringify(value) + '\n');
const hex = bytes => randomBytes(bytes).toString('hex');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const args = new Map();
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i], value = process.argv[i + 1];
  if (!['--root', '--miniflare', '--key', '--cert', '--port', '--state-dir', '--state'].includes(key)
      || value === undefined || args.has(key)) {
    emit({ event: 'error', error: 'invalid_arguments' }); process.exit(1);
  }
  args.set(key, value);
}
for (const key of ['--key', '--cert', '--state-dir']) if (!args.has(key)) {
  emit({ event: 'error', error: 'missing_argument', argument: key }); process.exit(1);
}

let mf, backend, server, input, shuttingDown = false, restarting = false;
const sockets = new Set(), tunnels = new Set();
let restartChain = Promise.resolve();

function closeWebSockets() {
  const count = tunnels.size;
  for (const pair of [...tunnels]) { pair.client.destroy(); pair.upstream.destroy(); }
  tunnels.clear();
  return count;
}

async function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true; input?.close(); closeWebSockets();
  for (const socket of sockets) socket.destroy();
  if (server?.listening) await new Promise(resolveClose => server.close(resolveClose));
  await restartChain.catch(() => {});
  if (mf) { await mf.dispose(); mf = null; }
}

async function main() {
  const root = await realpath(resolve(args.get('--root') ?? fileURLToPath(new URL('../../../../../', import.meta.url))));
  const stateDir = resolve(args.get('--state-dir'));
  await mkdir(stateDir, { recursive: true, mode: 0o700 }); await chmod(stateDir, 0o700);
  const actualStateDir = await realpath(stateDir), inside = relative(root, actualStateDir);
  if (inside === '' || (!inside.startsWith('..' + '/') && inside !== '..' && !isAbsolute(inside))) throw new Error('private_state_required');
  const responses = join(actualStateDir, 'responses');
  await mkdir(responses, { recursive: true, mode: 0o700 }); await chmod(responses, 0o700);
  const port = Number(args.get('--port') ?? 8443);
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('invalid_port');
  const requireWorker = createRequire(join(root, 'relay/worker/package.json'));
  const modulePath = args.get('--miniflare') ? resolve(args.get('--miniflare')) : requireWorker.resolve('miniflare');
  const { Miniflare, convertV4MiniflareOptions, Log, LogLevel } = await import(pathToFileURL(modulePath).href);
  const cli = await readFile(join(root, 'cli/airtap.py')), agent = await readFile(join(root, 'docs/agent.md'));
  await mkdir(join(actualStateDir, 'assets/cli'), { recursive: true, mode: 0o700 });
  await writeFile(join(actualStateDir, 'assets/cli/airtap.py'), cli, { mode: 0o600 });
  await writeFile(join(actualStateDir, 'assets/agent.md'), agent, { mode: 0o600 });
  const bindingsFile = join(actualStateDir, 'bindings.private.json');
  let identity;
  try { identity = JSON.parse(await readFile(bindingsFile, 'utf8')); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    identity = { SERVICE_ID: hex(16), IP_HASH_SALT: hex(32) };
    await writeFile(bindingsFile, JSON.stringify(identity) + '\n', { mode: 0o600, flag: 'wx' });
  }
  if (!/^[a-f0-9]{32}$/.test(identity.SERVICE_ID) || !/^[a-f0-9]{64}$/.test(identity.IP_HASH_SALT)) throw new Error('invalid_private_state');
  const sourceNames = ['service/service.mjs', 'worker/worker.mjs', 'service/feedback.mjs'];
  const modules = await Promise.all(sourceNames.map(async name => ({ type: 'ESModule', path: join(root, 'relay', name), contents: await readFile(join(root, 'relay', name), 'utf8') })));
  const options = {
    host: '127.0.0.1', port: 0, cf: false, log: new Log(LogLevel.NONE), logRequests: false,
    durableObjectsPersist: join(actualStateDir, 'do'), resourcePersistencePath: join(actualStateDir, 'state'),
    workers: [{ name: 'airtap-native-multi-private', modulesRoot: join(root, 'relay'), modules,
      compatibilityDate: '2026-10-01',
      durableObjects: { ADMISSION: { className: 'AdmissionRegistry', useSQLite: true }, DEVICES: { className: 'ServiceDeviceRelay', useSQLite: true } },
      ratelimits: { INGRESS: { namespace_id: '6401', simple: { limit: 10000, period: 60 } } },
      assets: { directory: join(actualStateDir, 'assets'), binding: 'ASSETS', run_worker_first: true, routerConfig: { has_user_worker: true } },
      bindings: { ...identity, SERVICE_LABEL: 'Private Android multi AI validation', REGISTRATION_OPEN: 'true', CONTROL_OPEN: 'true',
        CLI_SHA256: digest(cli), AGENT_SHA256: digest(agent), ARTIFACT_VERSION: 'native-private',
        MAX_DEVICES: '20', DAILY_REGISTRATIONS: '20', IP_DAILY_REGISTRATIONS: '20' },
    }],
  };
  async function start() {
    mf = new Miniflare(convertV4MiniflareOptions ? { ...convertV4MiniflareOptions(options), telemetry: { enabled: false } } : options);
    backend = new URL(String(await mf.ready));
  }
  await start();
  const forwardHeaders = request => ({ ...request.headers, host: backend.host, 'cf-connecting-ip': '127.0.0.1' });
  const upstreamUrl = path => {
    if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || /[\r\n\\]/.test(path)) throw new Error('invalid_path');
    const url = new URL(path, backend);
    if (url.origin !== backend.origin) throw new Error('invalid_path');
    return url;
  };
  server = createServer({ key: await readFile(resolve(args.get('--key'))), cert: await readFile(resolve(args.get('--cert'))) }, (request, response) => {
    if (restarting || shuttingDown) { response.writeHead(503); response.end(); return; }
    let upstream;
    try {
      upstream = httpRequest(upstreamUrl(request.url), { method: request.method, headers: forwardHeaders(request) }, incoming => {
        response.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(response);
        incoming.on('error', () => response.destroy());
      });
      upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end(); });
      request.on('aborted', () => upstream.destroy()); request.on('error', () => upstream.destroy());
      response.on('close', () => { if (!response.writableEnded) upstream.destroy(); });
      request.pipe(upstream);
    } catch { upstream?.destroy(); response.writeHead(400); response.end(); }
  });
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); socket.on('error', () => {}); });
  server.on('tlsClientError', () => {});
  server.on('upgrade', (request, client, head) => {
    if (restarting || shuttingDown) { client.destroy(); return; }
    let upstream;
    try {
      upstream = httpRequest(upstreamUrl(request.url), { method: request.method, headers: forwardHeaders(request) });
      upstream.once('upgrade', (response, socket, upstreamHead) => {
        if (restarting || shuttingDown || client.destroyed) { socket.destroy(); client.destroy(); return; }
        const pair = { client, upstream: socket }; tunnels.add(pair);
        const cleanup = () => { tunnels.delete(pair); client.destroy(); socket.destroy(); };
        client.on('close', cleanup); socket.on('close', cleanup); client.on('error', cleanup); socket.on('error', cleanup);
        client.write('HTTP/1.1 ' + response.statusCode + ' ' + response.statusMessage + '\r\n'
          + Object.entries(response.headers).flatMap(([name, value]) => (Array.isArray(value) ? value : [value]).map(item => name + ': ' + item + '\r\n')).join('') + '\r\n');
        if (head.length) socket.write(head); if (upstreamHead.length) client.write(upstreamHead);
        client.pipe(socket); socket.pipe(client);
      });
      upstream.once('response', response => {
        // A real production rejection (401/403/etc.) remains an HTTP rejection of the upgrade.
        client.write('HTTP/1.1 ' + response.statusCode + ' ' + response.statusMessage + '\r\nConnection: close\r\n\r\n');
        response.pipe(client); response.on('end', () => client.end()); response.on('error', () => client.destroy());
      });
      upstream.on('error', () => client.destroy()); client.on('error', () => upstream.destroy());
      upstream.end();
    } catch { upstream?.destroy(); client.destroy(); }
  });
  await new Promise((resolveListen, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolveListen); });
  const actualPort = server.address().port;
  const ready = { event: 'ready', protocol_version: 4, origin: 'https://localhost', host_origin: 'https://localhost:' + actualPort,
    port: actualPort, admin: 'stdin-json', state_dir: actualStateDir,
    source_sha256: Object.fromEntries(modules.map(module => [relative(root, module.path), digest(module.contents)])) };
  if (args.has('--state')) {
    const stateFile = resolve(args.get('--state')); await mkdir(resolve(stateFile, '..'), { recursive: true, mode: 0o700 });
    await writeFile(stateFile, JSON.stringify(ready, null, 2) + '\n', { mode: 0o600 }); await chmod(stateFile, 0o600);
  }
  emit(ready);

  async function command(value) {
    const id = value?.id;
    if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(id)) { emit({ id: null, ok: false, error: 'invalid_request_id' }); return; }
    try {
      if (value.op === 'request') {
        if (restarting || shuttingDown || !mf) throw new Error('relay_unavailable');
        if (!['GET', 'POST'].includes(value.method) || typeof value.path !== 'string'
            || !/^\/(?:v1\/(?:service|enroll)|d\/[a-f0-9]{32}\/(?:v1\/[A-Za-z0-9_/-]+|mcp|agent\.md|cli\/airtap\.py))$/.test(value.path)
            || value.token !== undefined && (typeof value.token !== 'string' || !/^[a-f0-9]{64}$/.test(value.token))
            || value.method === 'GET' && value.body !== undefined) throw new Error('invalid_request');
        const hasBody = value.body !== undefined;
        const response = await mf.dispatchFetch('http://localhost' + value.path, {
          method: value.method, headers: { 'cf-connecting-ip': '127.0.0.1',
            ...(value.token ? { Authorization: 'Bearer ' + value.token } : {}),
            ...(hasBody ? { 'Content-Type': 'application/json' } : {}) },
          ...(hasBody ? { body: JSON.stringify(value.body) } : {}),
        });
        const bytes = Buffer.from(await response.arrayBuffer());
        const result = { status: response.status, headers: Object.fromEntries(response.headers) };
        try { result.body = JSON.parse(bytes.toString('utf8')); }
        catch { result.body_base64 = bytes.toString('base64'); }
        const file = join(responses, id + '-' + hex(8) + '.json');
        await writeFile(file, JSON.stringify(result) + '\n', { mode: 0o600, flag: 'wx' });
        emit({ id, ok: true, status: response.status, response_file: file });
      } else if (value.op === 'close_ws') {
        emit({ id, ok: true, closed: closeWebSockets() });
      } else if (value.op === 'restart') {
        restartChain = restartChain.catch(() => {}).then(async () => {
          restarting = true;
          try { closeWebSockets(); await mf?.dispose(); mf = null; await start(); }
          finally { restarting = false; }
        });
        await restartChain; emit({ id, ok: true, restarted: true });
      } else if (value.op === 'shutdown') {
        await shutdown(); emit({ id, ok: true, stopped: true }); process.exit(0);
      } else emit({ id, ok: false, error: 'unsupported_operation' });
    } catch { emit({ id, ok: false, error: 'operation_failed' }); }
  }
  input = createInterface({ input: process.stdin, crlfDelay: Infinity });
  input.on('line', line => {
    if (line.length > 1024 * 1024) { emit({ id: null, ok: false, error: 'request_too_large' }); return; }
    try { void command(JSON.parse(line)); }
    catch { emit({ id: null, ok: false, error: 'invalid_json' }); }
  });
  input.on('close', () => { if (!shuttingDown) void shutdown().then(() => process.exit(0)); });
}

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { void shutdown().finally(() => process.exit(0)); });
try { await main(); }
catch { emit({ event: 'error', error: 'startup_failed' }); await shutdown().catch(() => {}); process.exitCode = 1; }
