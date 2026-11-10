import { DurableObject } from 'cloudflare:workers';

// One expiring preview phone, not an open registration or multi-tenant service.
const HEX64 = /^[a-f0-9]{64}$/;
const HEX32 = /^[a-f0-9]{32}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ACTIONS = new Set(['ping', 'read_tree', 'screenshot', 'tap_test']);
const AUDIO_STREAMS = new Set(['music', 'alarm', 'ring', 'notification', 'system', 'voice_call']);
const CONTROL_ACTIONS = new Set(['sim_pin_submit', 'sim_pin_prepare', 'media_capture', 'media_stop', 'touch_begin', 'touch_move', 'touch_end', 'set_volume', 'adjust_volume', 'launch', 'open_url', 'click', 'long_click', 'set_text', 'scroll', 'node_action', 'tap', 'long_press', 'swipe', 'drag', 'gesture', 'back', 'home', 'recents', 'system_action', 'focus', 'set_selection', 'select_all', 'copy', 'cut', 'paste', 'insert_text', 'delete_text', 'editor_action']);
const FILE_READ_ACTIONS = new Set(['file_roots', 'file_list', 'file_stat', 'file_read', 'file_upload_status']);
const FILE_WRITE_ACTIONS = new Set(['file_mkdir', 'file_rename', 'file_delete', 'file_share', 'file_upload_begin', 'file_upload_chunk', 'file_upload_finish', 'file_upload_abort']);
const EVENT_ACTIONS = new Set(['event_status', 'event_read']);
const FILE_LIMIT = 64 * 1024 * 1024;
const FILE_CHUNK_LIMIT = 32768;
const SYSTEM_ACTIONS = new Set(['notifications', 'quick_settings', 'dismiss_notification_shade', 'all_apps', 'dpad_up', 'dpad_down', 'dpad_left', 'dpad_right', 'dpad_center', 'power_dialog', 'lock_screen', 'split_screen']);
const NODE_OPERATIONS = new Set(['accessibility_focus', 'clear_accessibility_focus', 'next_granularity', 'previous_granularity', 'custom', 'select', 'clear_selection', 'clear_focus', 'context_click', 'expand', 'collapse', 'dismiss', 'show_on_screen', 'scroll_up', 'scroll_down', 'scroll_left', 'scroll_right', 'page_up', 'page_down', 'page_left', 'page_right', 'scroll_to_position', 'set_progress', 'drag_start', 'drag_drop', 'drag_cancel']);
const PACKAGE = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/;
const IMAGE_LIMIT = 512 * 1024;
const TEXT_LIMIT = 64 * 1024;
const COMMAND_LIMIT = 64 * 1024;
const H = { 'Cache-Control': 'no-store, no-cache, no-transform', 'X-Content-Type-Options': 'nosniff', 'X-Airtap-Protocol-Version': '2' };
const json = (status, data) => new Response(JSON.stringify(data), { status, headers: { ...H, 'Content-Type': 'application/json' } });
const now = () => Math.floor(Date.now() / 1000);
export const PAIRING_TTL_SECONDS = 60 * 60;
const randomHex = bytes => [...crypto.getRandomValues(new Uint8Array(bytes))].map(x => x.toString(16).padStart(2, '0')).join('');
const encode = value => new TextEncoder().encode(value);
const hash = async value => [...new Uint8Array(await crypto.subtle.digest('SHA-256', encode(value)))].map(x => x.toString(16).padStart(2, '0')).join('');
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && crypto.subtle.timingSafeEqual(encode(a), encode(b));
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
const validPackage = value => typeof value === 'string' && value.length <= 200 && (value === 'android' || PACKAGE.test(value));
const authorizationSurface = value => value === 'io.airtap.preview' || value.startsWith('io.airtap.preview.');
const scope = state => ({ allow_control: state.allowControl === true, allowed_packages: state.allowedPackages ?? [],
  ...(state.persistent === true ? { persistent: true, all_apps: true, command_sequence: state.sequence ?? 0, capabilities: ['companion_v1'] } : {}) });
function approvalScope(body) {
  if (exact(body, ['claim_id', 'allow_tap', 'allow_control', 'allowed_packages', 'persistent', 'all_apps']) &&
      body.persistent === true && body.all_apps === true && body.allow_control === true &&
      Array.isArray(body.allowed_packages) && body.allowed_packages.length === 0)
    return { allowControl: true, allowedPackages: [], persistent: true, allApps: true, sequence: 0 };
  if (exact(body, ['claim_id', 'allow_tap'])) return { allowControl: false, allowedPackages: [] };
  if (!exact(body, ['claim_id', 'allow_tap', 'allow_control', 'allowed_packages']) || typeof body.allow_control !== 'boolean' ||
      !Array.isArray(body.allowed_packages) || body.allowed_packages.length > 8 ||
      body.allowed_packages.some(name => !validPackage(name) || authorizationSurface(name)) || new Set(body.allowed_packages).size !== body.allowed_packages.length) return null;
  return { allowControl: body.allow_control, allowedPackages: [...body.allowed_packages].sort() };
}
// Paths are phone-side identifiers within an explicit root, never URLs or host paths.
function validFilePath(value, empty = false) {
  return typeof value === 'string' && (empty && value === '' || value.length > 0 && encode(value).length <= 1024 &&
    !/[\\:\p{Cc}\p{Cs}]/u.test(value) && value.split('/').length <= 32 && value.split('/').every(part => part && part !== '.' && part !== '..' && [...part].length <= 255));
}
const validFileRoot = value => typeof value === 'string' && /^(?:workspace|saf_[a-f0-9]{32})$/.test(value);
const fileInteger = value => Number.isSafeInteger(value) && value >= 0 && value <= FILE_LIMIT;
function fileCommandShape(cmd) {
  const base = ['id', 'action'];
  if (cmd.action === 'file_mkdir') return exact(cmd,[...base,'root','path']) && cmd.root === 'workspace' && validFilePath(cmd.path);
  if (cmd.action === 'file_rename') return exact(cmd,[...base,'root','path','destination','version']) && cmd.root === 'workspace' && validFilePath(cmd.path) && validFilePath(cmd.destination) && typeof cmd.version === 'string' && HEX64.test(cmd.version);
  if (cmd.action === 'file_delete') return exact(cmd,[...base,'root','path','version']) && cmd.root === 'workspace' && validFilePath(cmd.path) && typeof cmd.version === 'string' && HEX64.test(cmd.version);
  if (cmd.action === 'file_roots') return exact(cmd, base);
  if (cmd.action === 'file_share') return exact(cmd, [...base, 'root', 'path', 'version', 'mime_type', 'target_package']) && cmd.root === 'workspace' && validFilePath(cmd.path) && typeof cmd.version === 'string' && HEX64.test(cmd.version) && typeof cmd.mime_type === 'string' && cmd.mime_type.length <= 127 && /^[a-zA-Z0-9!#$&^_.+-]+\/[a-zA-Z0-9!#$&^_.+-]+$/.test(cmd.mime_type) && (cmd.target_package === '' || validPackage(cmd.target_package) && !authorizationSurface(cmd.target_package));
  if (['file_upload_status', 'file_upload_finish', 'file_upload_abort'].includes(cmd.action))
    return exact(cmd, [...base, 'transfer_id']) && typeof cmd.transfer_id === 'string' && HEX32.test(cmd.transfer_id);
  if (cmd.action === 'file_upload_chunk') {
    if (!exact(cmd, [...base, 'transfer_id', 'offset', 'data_base64', 'sha256']) || typeof cmd.transfer_id !== 'string' ||
        !HEX32.test(cmd.transfer_id) || !fileInteger(cmd.offset) || typeof cmd.sha256 !== 'string' || !HEX64.test(cmd.sha256) ||
        typeof cmd.data_base64 !== 'string' || !cmd.data_base64.length || cmd.data_base64.length > 43692) return false;
    try {
      const bytes = atob(cmd.data_base64);
      return bytes.length > 0 && bytes.length <= FILE_CHUNK_LIMIT && cmd.offset + bytes.length <= FILE_LIMIT && btoa(bytes) === cmd.data_base64;
    } catch { return false; }
  }
  if (!validFileRoot(cmd.root)) return false;
  if (cmd.action === 'file_list') return exact(cmd, [...base, 'root', 'path', 'offset', 'limit']) && validFilePath(cmd.path, true) &&
    Number.isInteger(cmd.offset) && cmd.offset >= 0 && cmd.offset <= 4096 && Number.isInteger(cmd.limit) && cmd.limit >= 1 && cmd.limit <= 64;
  if (cmd.action === 'file_stat') return exact(cmd, [...base, 'root', 'path']) && validFilePath(cmd.path, true);
  if (cmd.action === 'file_read') return exact(cmd, [...base, 'root', 'path', 'offset', 'length', 'version']) && validFilePath(cmd.path) &&
    fileInteger(cmd.offset) && Number.isInteger(cmd.length) && cmd.length >= 1 && cmd.length <= FILE_CHUNK_LIMIT &&
    typeof cmd.version === 'string' && HEX64.test(cmd.version);
  if (cmd.action === 'file_upload_begin') return exact(cmd, [...base, 'root', 'path', 'transfer_id', 'size', 'sha256']) && validFilePath(cmd.path) &&
    typeof cmd.transfer_id === 'string' && HEX32.test(cmd.transfer_id) && fileInteger(cmd.size) && typeof cmd.sha256 === 'string' && HEX64.test(cmd.sha256);
  return false;
}
function validOpenUrl(value) {
  // Browser navigation on the authorized phone, never a relay-side request.
  // Preserve the original URL. Shared cases: ../service/tests/fixtures/open-url-cases.json.
  if (typeof value !== 'string' || value.length < 1 || value.length > 2048 ||
      /[^\x21-\x7e]|[\\"<>^`{|}]/.test(value) || /%(?![0-9a-fA-F]{2})/.test(value) ||
      /%(?:[01][0-9a-fA-F]|7[fF])/.test(value)) return false;
  const url = /^(https?):\/\/([^/?#]+)(.*)$/i.exec(value);
  if (!url || /[\[\]]/.test(url[3])) return false;
  const authority = /^(?:\[([0-9a-fA-F:.]+)\]|([A-Za-z0-9.-]+))(?::([0-9]{1,5}))?$/.exec(url[2]);
  if (!authority || (authority[3] !== undefined && (+authority[3] < 1 || +authority[3] > 65535))) return false;
  if (authority[1]) {
    try { new URL(value); } catch { return false; } // Validate the IPv6 literal without resolving it.
  } else {
    const host = authority[2], labels = host.replace(/\.$/, '').split('.');
    if (host.length > 253 || !labels.every(label => /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/.test(label))) return false;
    // Refuse ambiguous abbreviated/octal/hex IP spellings that browsers reinterpret.
    if (/^(?:[0-9]+|0x[0-9a-f]+)$/i.test(labels.at(-1)) &&
        (host.endsWith('.') || labels.length !== 4 || !labels.every(label => /^(?:0|[1-9][0-9]{0,2})$/.test(label) && +label <= 255))) return false;
  }
  return true;
}
function commandShape(cmd, companion = false) {
  if (companion) {
    if (!Number.isSafeInteger(cmd?.sequence) || cmd.sequence < 1) return false;
    const { sequence, ...rest } = cmd; cmd = rest;
  }
  if (!cmd || typeof cmd.id !== 'string' || !UUID.test(cmd.id)) return false;
  if (companion && cmd.action === 'event_status') return exact(cmd, ['id','action']);
  if (companion && cmd.action === 'event_read') return exact(cmd, ['id','action','epoch','after','limit']) &&
    typeof cmd.epoch === 'string' && (cmd.epoch === '' || HEX32.test(cmd.epoch)) && Number.isSafeInteger(cmd.after) && cmd.after >= 0 && Number.isInteger(cmd.limit) && cmd.limit >= 1 && cmd.limit <= 16;
  if (FILE_READ_ACTIONS.has(cmd.action) || FILE_WRITE_ACTIONS.has(cmd.action)) return companion && fileCommandShape(cmd);
  if (ACTIONS.has(cmd.action) && exact(cmd, ['id', 'action'])) return true;
  if (companion && ['apps', 'windows', 'foreground', 'observe', 'back', 'home', 'recents', 'system_actions', 'audio_state'].includes(cmd.action)) return exact(cmd, ['id', 'action']);
  if (companion && cmd.action === 'system_action') return exact(cmd, ['id', 'action', 'name']) && SYSTEM_ACTIONS.has(cmd.name);
  if (companion && cmd.action === 'set_volume') return exact(cmd, ['id', 'action', 'stream', 'level']) && AUDIO_STREAMS.has(cmd.stream) && Number.isInteger(cmd.level) && cmd.level >= 0 && cmd.level <= 2147483647;
  if (companion && cmd.action === 'adjust_volume') return exact(cmd, ['id', 'action', 'stream', 'direction']) && AUDIO_STREAMS.has(cmd.stream) && ['raise', 'lower', 'mute', 'unmute'].includes(cmd.direction);
  if (companion && cmd.action==='sim_pin_prepare') return exact(cmd,['id','action']);
  if (companion && cmd.action==='sim_pin_submit') return exact(cmd,['id','action','challenge','pin'])&&typeof cmd.challenge==='string'&&HEX32.test(cmd.challenge)&&typeof cmd.pin==='string'&&/^[0-9]{4,8}$/.test(cmd.pin);
  if (companion && ['media_status','media_stop'].includes(cmd.action)) return exact(cmd,['id','action','capture_id'])&&typeof cmd.capture_id==='string'&&HEX32.test(cmd.capture_id);
  if (companion && cmd.action === 'media_capture') return exact(cmd,['id','action','kind','seconds'])&&['photo','video','audio','screen'].includes(cmd.kind)&&Number.isInteger(cmd.seconds)&&cmd.seconds>=1&&cmd.seconds<=60;
  if (companion && cmd.action === 'touch_end') return exact(cmd,['id','action','touch_id']) && typeof cmd.touch_id === 'string' && HEX32.test(cmd.touch_id);
  if (companion && ['touch_begin','touch_move'].includes(cmd.action)) {
    const fields=['id','action','package','observation_id','x','y'];
    const point=n=>typeof n==='number'&&Number.isFinite(n)&&n>=0&&n<1;
    return validPackage(cmd.package)&&typeof cmd.observation_id==='string'&&HEX32.test(cmd.observation_id)&&point(cmd.x)&&point(cmd.y)&&
      (cmd.action==='touch_begin'?exact(cmd,fields):exact(cmd,[...fields,'touch_id','duration_ms'])&&typeof cmd.touch_id==='string'&&HEX32.test(cmd.touch_id)&&Number.isInteger(cmd.duration_ms)&&cmd.duration_ms>=1&&cmd.duration_ms<=5000);
  }
  if (!validPackage(cmd.package)) return false;
  if (companion && cmd.action === 'gesture') {
    if (!exact(cmd, ['id', 'action', 'package', 'observation_id', 'tracks']) || !HEX32.test(cmd.observation_id ?? '') ||
        !Array.isArray(cmd.tracks) || cmd.tracks.length < 1 || cmd.tracks.length > 10) return false;
    const point = n => typeof n === 'number' && Number.isFinite(n) && n >= 0 && n < 1;
    for (const track of cmd.tracks) {
      if (!exact(track, ['start_ms', 'x', 'y', 'path']) || !Number.isInteger(track.start_ms) || track.start_ms < 0 || track.start_ms >= 10000 ||
          !point(track.x) || !point(track.y) || !Array.isArray(track.path) || track.path.length < 1 || track.path.length > 32) return false;
      let end = track.start_ms;
      for (const step of track.path) {
        if (!exact(step, ['x', 'y', 'duration_ms']) || !point(step.x) || !point(step.y) ||
            !Number.isInteger(step.duration_ms) || step.duration_ms < 1 || step.duration_ms > 5000) return false;
        end += step.duration_ms;
      }
      if (end > 10000) return false;
    }
    return true;
  }
  if (companion && ['tap', 'long_press', 'swipe', 'drag'].includes(cmd.action)) {
    const fields = ['id', 'action', 'package', 'observation_id', 'x', 'y'];
    const point = value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value < 1;
    if (!HEX32.test(cmd.observation_id ?? '') || !point(cmd.x) || !point(cmd.y)) return false;
    if (cmd.action === 'drag') {
      if (!exact(cmd, [...fields, 'hold_ms', 'path']) || !Number.isInteger(cmd.hold_ms) || cmd.hold_ms < 100 || cmd.hold_ms > 3000 ||
          !Array.isArray(cmd.path) || cmd.path.length < 1 || cmd.path.length > 16) return false;
      let duration = cmd.hold_ms;
      for (const step of cmd.path) {
        if (!exact(step, ['x', 'y', 'duration_ms']) || !point(step.x) || !point(step.y) ||
            !Number.isInteger(step.duration_ms) || step.duration_ms < 1 || step.duration_ms > 5000) return false;
        duration += step.duration_ms;
      }
      return duration <= 10000;
    }
    if (cmd.action !== 'swipe') return exact(cmd, fields);
    return exact(cmd, [...fields, 'end_x', 'end_y', 'duration_ms']) && point(cmd.end_x) && point(cmd.end_y) &&
      Number.isInteger(cmd.duration_ms) && cmd.duration_ms >= 100 && cmd.duration_ms <= 5000;
  }
  if (['read_tree', 'screenshot', 'launch'].includes(cmd.action)) return exact(cmd, ['id', 'action', 'package']);
  if (cmd.action === 'open_url') return exact(cmd, ['id', 'action', 'package', 'url']) &&
    validOpenUrl(cmd.url);
  if (companion && cmd.action === 'read_tree_page') return exact(cmd, ['id', 'action', 'package', 'snapshot_id', 'offset', 'limit']) &&
    typeof cmd.snapshot_id === 'string' && HEX32.test(cmd.snapshot_id) && Number.isInteger(cmd.offset) && cmd.offset >= 0 && cmd.offset <= 2048 &&
    Number.isInteger(cmd.limit) && cmd.limit >= 1 && cmd.limit <= 128;
  if (typeof cmd.snapshot_id !== 'string' || !HEX32.test(cmd.snapshot_id) || typeof cmd.node_id !== 'string' || !/^[1-9][0-9]{0,3}$/.test(cmd.node_id) || Number(cmd.node_id) > (companion ? 2048 : 128)) return false;
  const keys = ['id', 'action', 'package', 'snapshot_id', 'node_id'];
  if (companion && cmd.action === 'read_node_text') return exact(cmd, [...keys, 'field', 'offset', 'length']) &&
    ['text', 'description', 'hint_text'].includes(cmd.field) && Number.isInteger(cmd.offset) && cmd.offset >= 0 && cmd.offset <= 2147483647 &&
    Number.isInteger(cmd.length) && cmd.length >= 2 && cmd.length <= 4096;
  if (companion && ['focus', 'select_all', 'copy', 'cut', 'paste'].includes(cmd.action)) return exact(cmd, keys);
  if (companion && cmd.action === 'set_selection') return exact(cmd, [...keys, 'start', 'end']) &&
    [cmd.start, cmd.end].every(value => Number.isInteger(value) && value >= 0 && value <= 2147483647);
  if (companion && cmd.action === 'delete_text') return exact(cmd, [...keys, 'before', 'after']) &&
    [cmd.before, cmd.after].every(value => Number.isInteger(value) && value >= 0 && value <= 2000) && cmd.before + cmd.after > 0;
  if (companion && cmd.action === 'editor_action') return exact(cmd, [...keys, 'editor_action']) &&
    ['default', 'done', 'go', 'next', 'previous', 'search', 'send'].includes(cmd.editor_action);
  if (companion && cmd.action === 'node_action') {
    if (!NODE_OPERATIONS.has(cmd.operation)) return false;
    const fields = [...keys, 'operation'];
    if (cmd.operation === 'custom') return exact(cmd, [...fields, 'action_id']) && Number.isInteger(cmd.action_id) && cmd.action_id >= 0x02000000 && cmd.action_id <= 2147483647;
    if (['next_granularity', 'previous_granularity'].includes(cmd.operation)) return exact(cmd, [...fields, 'granularity', 'extend_selection']) &&
      ['character', 'word', 'line', 'paragraph', 'page'].includes(cmd.granularity) && typeof cmd.extend_selection === 'boolean';
    if (cmd.operation === 'scroll_to_position') return exact(cmd, [...fields, 'row', 'column']) &&
      [cmd.row, cmd.column].every(value => Number.isInteger(value) && value >= 0 && value <= 2147483647);
    if (cmd.operation === 'set_progress') return exact(cmd, [...fields, 'value']) && typeof cmd.value === 'number' &&
      Number.isFinite(cmd.value) && Number.isFinite(Math.fround(cmd.value));
    return exact(cmd, fields);
  }
  if (cmd.action === 'click' || (companion && cmd.action === 'long_click')) return exact(cmd, keys);
  if (cmd.action === 'scroll') return exact(cmd, [...keys, 'direction']) && ['forward', 'backward'].includes(cmd.direction);
  if (cmd.action === 'set_text' || (companion && cmd.action === 'insert_text')) return exact(cmd, [...keys, 'text']) && typeof cmd.text === 'string' && [...cmd.text].length <= 2000 && !/\p{Cs}/u.test(cmd.text);
  return false;
}
async function publicAsset(request, env) {
  const url = new URL(request.url);
  const descriptor = {
    '/cli/airtap.py': { digest: env.CLI_SHA256, type: 'text/x-python; charset=utf-8', filename: 'airtap.py' },
    '/agent.md': { digest: env.AGENT_SHA256, type: 'text/markdown; charset=utf-8', filename: 'agent.md' },
  }[url.pathname];
  if (!descriptor) return null;
  if (request.method !== 'GET' || request.url.includes('?') || url.hash) return json(404, { error: 'not_found' });
  if (!env.ASSETS || !HEX64.test(descriptor.digest ?? '') || !/^[a-zA-Z0-9._-]{1,64}$/.test(env.ARTIFACT_VERSION ?? '')) return json(503, { error: 'artifacts_not_configured' });
  // Do not forward caller Authorization/Cookie headers to the static binding.
  let response;
  try { response = await env.ASSETS.fetch(new Request('https://assets.invalid' + url.pathname)); }
  catch { return json(503, { error: 'artifact_unavailable' }); }
  if (response.status !== 200) return json(503, { error: 'artifact_unavailable' });
  return new Response(response.body, { headers: { ...H, 'Content-Type': descriptor.type,
    'Content-Disposition': 'attachment; filename="' + descriptor.filename + '"',
    'X-Airtap-Artifact-SHA256': descriptor.digest, 'X-Airtap-Version': env.ARTIFACT_VERSION } });
}

function route(request) {
  const url = new URL(request.url);
  if (request.url.includes('?') || url.hash) return null;
  if (request.method === 'GET' && ['/v1/device', '/v1/device/pairing', '/v1/pair/status', '/v1/status', '/v1/device/grants'].includes(url.pathname)) return url.pathname;
  if (request.method === 'POST' && ['/v1/setup', '/v1/device/pairing', '/v1/pair', '/v1/device/approve', '/v1/command', '/v1/revoke', '/v1/device/revoke', '/v1/device/multi/enable', '/v1/device/invitations', '/v1/device/invitations/cancel', '/v1/device/grants/approve', '/v1/device/grants/revoke', '/v1/tasks/acquire', '/v1/tasks/renew', '/v1/tasks/release'].includes(url.pathname)) return url.pathname;
  return null;
}
function configured(env) {
  return HEX64.test(env.SETUP_TOKEN ?? '') && /^\d{10}$/.test(env.EXPIRES_AT ?? '') && Number(env.EXPIRES_AT) <= now() + 86400;
}
function bearer(request) {
  const value = request.headers.get('Authorization') ?? '';
  return /^Bearer [a-f0-9]{64}$/.test(value) ? value.slice(7) : null;
}
function attachment(socket) { try { return socket.deserializeAttachment(); } catch { return null; } }
function live(socket, generation) {
  const a = attachment(socket);
  return socket.readyState === 1 && a?.version === 1 && a.retired === false && a.generation === generation;
}
function retire(socket, reason, code = 1008) {
  // workerd can revive a closed socket as OPEN (#7595); mark it before close.
  try { socket.serializeAttachment({ ...attachment(socket), version: 1, retired: true }); }
  finally { try { socket.close(code, reason); } catch {} }
}
async function readBody(request, limit) {
  const fail = () => ({ error: json(limit === 0 ? 400 : 413, { error: limit === 0 ? 'body_not_permitted' : 'body_too_large' }) });
  const length = request.headers.get('Content-Length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > limit)) {
    // Explicitly release a rejected upload; assets routing can reuse its HTTP
    // connection and an unread body must not occupy that connection indefinitely.
    void request.body?.cancel().catch(() => {});
    return fail();
  }
  const reader = request.body?.getReader();
  if (!reader) return { bytes: new Uint8Array() };
  let size = 0, timer;
  const chunks = [], deadline = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('body_timeout')), 5000); });
  try {
    for (;;) {
      const { done, value } = await Promise.race([reader.read(), deadline]);
      if (done) break;
      size += value.byteLength;
      if (size > limit) { void reader.cancel().catch(() => {}); return fail(); }
      chunks.push(value);
    }
  } catch (error) {
    void reader.cancel().catch(() => {});
    return { error: json(error?.message === 'body_timeout' ? 408 : 400, { error: 'body_unavailable' }) };
  } finally { clearTimeout(timer); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return { bytes };
}
function initial() { return { version: 1, generation: 0, phase: 'idle', deviceHash: null, ticketHash: null, pairingExpires: null, candidate: null, sessionExpires: null, allowTap: false, allowControl: false, allowedPackages: [], seen: [] }; }

export default { async fetch(request, env) {
  const artifact = await publicAsset(request, env);
  if (artifact) return artifact;
  const path = route(request);
  if (!path) return json(404, { error: 'not_found' });
  if (!configured(env)) return json(503, { error: 'not_configured' });
  if (now() >= Number(env.EXPIRES_AT)) return json(410, { error: 'expired' });
  // Reject invalid enrollment credentials before opening the one fixed object.
  if (path === '/v1/setup' && !equal(bearer(request), env.SETUP_TOKEN)) return json(401, { error: 'unauthorized' });
  if (path !== '/v1/setup' && path !== '/v1/pair' && !bearer(request)) return json(401, { error: 'unauthorized' });
  try { return await env.RELAY.get(env.RELAY.idFromName('single-phone-preview'), { locationHint: 'apac' }).fetch(request); }
  catch { return json(503, { error: 'relay_unavailable', detail: 'Do not replay an uncertain action.' }); }
} };

export class PairingRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.state = initial(); this.pending = null; this.device = null;
    ctx.blockConcurrencyWhile(async () => {
      this.state = await ctx.storage.get('state') ?? initial();
      const usable = this.active();
      for (const socket of ctx.getWebSockets('device')) {
        if (usable && !this.device && live(socket, this.state.generation)) this.device = socket;
        else retire(socket, 'inactive_session');
      }
      await this.restoreTransport();
    });
  }
  async restoreTransport() {}
  protocolVersion() { return 2; }
  replaceAuthenticatedDeviceConnection() { return false; }
  headers() { return { ...H, 'X-Airtap-Protocol-Version': String(this.protocolVersion()) }; }
  json(status, data) { return new Response(JSON.stringify(this.protocolVersion() >= 3 ? { ...data, protocol_version: this.protocolVersion() } : data), { status, headers: { ...this.headers(), 'Content-Type': 'application/json' } }); }
  validConfiguration() { return configured(this.env); }
  globalDeadline() { return Number(this.env.EXPIRES_AT); }
  expiryValue() { return this.globalDeadline(); }
  async beforeCommand(cmd) { return null; }
  globalExpired() { return now() >= this.globalDeadline(); }
  active() { return !this.globalExpired() && this.state.phase === 'approved' && (this.state.persistent === true || now() < this.state.sessionExpires); }
  connected() { return this.active() && !!this.device && live(this.device, this.state.generation); }
  async persist(next) { await this.ctx.storage.put('state', next); this.state = next; }
  async schedule() {
    const s = this.state;
    const deadline = s.phase === 'approved' ? s.sessionExpires : ['waiting', 'pending'].includes(s.phase) ? s.pairingExpires : this.globalDeadline();
    await this.ctx.storage.setAlarm(Math.min(deadline, this.globalDeadline()) * 1000);
  }
  async expire() {
    const s = this.state;
    if (this.globalExpired() || (s.phase === 'approved' && !s.persistent && now() >= s.sessionExpires) || (['waiting', 'pending'].includes(s.phase) && now() >= s.pairingExpires)) {
      this.disconnect('expired', 410);
      if (s.phase !== 'revoked') await this.persist({ ...s, phase: 'revoked', ticketHash: null, allowTap: false, allowControl: false, allowedPackages: [], generation: s.generation + 1 });
    }
  }
  candidateState() {
    const s = this.state;
    return { protocol_version: this.protocolVersion(), state: s.phase, claim_id: s.candidate.id, verification_code: s.candidate.code, pairing_expires_at: s.pairingExpires,
      ...(s.phase === 'approved' ? { session_expires_at: s.sessionExpires, allow_tap: s.allowTap, ...scope(s) } : {}) };
  }
  deviceState() {
    const s = this.state;
    return { protocol_version: this.protocolVersion(), state: s.phase,
      ...(['pending', 'approved'].includes(s.phase) && s.candidate ? { candidate: { claim_id: s.candidate.id, name: s.candidate.name, verification_code: s.candidate.code } } : {}),
      ...(s.pairingExpires ? { pairing_expires_at: s.pairingExpires } : {}),
      ...(s.phase === 'approved' ? { session_expires_at: s.sessionExpires, allow_tap: s.allowTap, ...scope(s) } : {}) };
  }
  controllerAuth(tokenHash, pendingAllowed = false) {
    if (!equal(tokenHash, this.state.candidate?.hash)) return this.json(401, { error: 'unauthorized' });
    if (this.globalExpired() || this.state.phase === 'revoked') return this.json(410, { error: 'expired_or_revoked' });
    if (pendingAllowed && this.state.phase === 'pending') return null;
    if (!this.active()) return this.json(403, { error: 'phone_approval_required' });
    return null;
  }
  emptyPostBody(path) { return ['/v1/device/pairing', '/v1/revoke', '/v1/device/revoke'].includes(path); }
  async fetch(request) {
    const path = route(request);
    if (!path) return this.json(404, { error: 'not_found' });
    if (!this.validConfiguration()) return this.json(503, { error: 'not_configured' });
    const token = bearer(request);
    const tokenHash = token ? await hash(token) : null;
    let body;
    if (request.method === 'POST') {
      const empty = this.emptyPostBody(path);
      if (!empty && !/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('Content-Type') ?? '')) return this.json(415, { error: 'json_required' });
      const parsed = await readBody(request, empty ? 0 : path === '/v1/command' ? COMMAND_LIMIT : path === '/v1/device/approve' || path.startsWith('/v1/tasks/') || path.startsWith('/v1/device/') ? 4096 : 1024);
      if (parsed.error) return this.json(parsed.error.status, await parsed.error.json());
      if (!empty) {
        try { body = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(parsed.bytes)); }
        catch { return this.json(400, { error: 'invalid_json' }); }
      }
    }
    // Storage changes, authorization checks, and command admission are serialized.
    // Wrap a pending command's Promise so the lock is released before its reply.
    const result = await this.ctx.blockConcurrencyWhile(async () => {
      await this.expire();
      if (this.globalExpired()) return this.json(410, { error: 'expired' });
      return this.handle(path, request, body, token, tokenHash);
    });
    return result?.pendingResponse ? result.pendingResponse : result;
  }
  async handle(path, request, body, token, tokenHash) {
    let s = this.state;
    if (path === '/v1/setup') {
      if (!equal(token, this.env.SETUP_TOKEN)) return this.json(401, { error: 'unauthorized' });
      if (!exact(body, ['device_token']) || typeof body.device_token !== 'string' || !HEX64.test(body.device_token)) return this.json(400, { error: 'invalid_setup' });
      // Distinct bearer roles are required even if the caller reuses a secret.
      if (equal(body.device_token, this.env.SETUP_TOKEN)) return this.json(400, { error: 'distinct_device_token_required' });
      const deviceHash = await hash(body.device_token);
      if (s.deviceHash && !equal(s.deviceHash, deviceHash)) return this.json(409, { error: 'phone_already_enrolled' });
      if (!s.deviceHash) await this.persist({ ...s, deviceHash });
      await this.schedule();
      return this.json(200, { ok: true, expires_at: this.expiryValue() });
    }
    if (path === '/v1/pair') {
      if (!exact(body, ['ticket', 'controller_token', 'name']) || typeof body.ticket !== 'string' || !HEX32.test(body.ticket) || typeof body.controller_token !== 'string' || !HEX64.test(body.controller_token) ||
          typeof body.name !== 'string' || [...body.name].length < 1 || [...body.name].length > 48 || /\p{C}/u.test(body.name) || !body.name.trim()) return this.json(400, { error: 'invalid_pairing_request' });
      const controllerHash = await hash(body.controller_token), ticketHash = await hash(body.ticket);
      if (equal(controllerHash, s.deviceHash) || equal(body.controller_token, this.env.SETUP_TOKEN)) return this.json(400, { error: 'distinct_controller_token_required' });
      if (!s.ticketHash || !equal(ticketHash, s.ticketHash)) return this.json(401, { error: 'invalid_or_consumed_ticket' });
      if (!['waiting', 'pending'].includes(s.phase) || now() >= s.pairingExpires) return this.json(410, { error: 'pairing_expired' });
      if (s.candidate) {
        if (equal(controllerHash, s.candidate.hash) && body.name === s.candidate.name) return this.json(200, this.candidateState());
        return this.json(409, { error: 'ticket_already_claimed' });
      }
      // Rejection sampling avoids bias in the six digit human comparison code.
      let sample;
      do { sample = crypto.getRandomValues(new Uint32Array(1))[0]; } while (sample >= 4294000000);
      const candidate = { hash: controllerHash, id: randomHex(16), code: String(sample % 1000000).padStart(6, '0'), name: body.name };
      await this.persist({ ...s, phase: 'pending', candidate });
      await this.schedule();
      return this.json(200, this.candidateState());
    }
    if (path.startsWith('/v1/device')) {
      if (!equal(tokenHash, s.deviceHash)) return this.json(401, { error: 'unauthorized' });
      if (path === '/v1/device/pairing' && request.method === 'GET') return this.json(200, this.deviceState());
      if (path === '/v1/device/pairing') {
        const ticket = randomHex(16), ticketHash = await hash(ticket);
        // A fresh explicit invitation invalidates the previous controller and
        // starts a new replay domain. Reconnect/hibernation never clears seen.
        const next = { ...s, generation: s.generation + 1, phase: 'waiting', ticketHash, pairingExpires: Math.min(now() + PAIRING_TTL_SECONDS, this.globalDeadline()), candidate: null, sessionExpires: null, allowTap: false, allowControl: false, allowedPackages: [], persistent: false, allApps: false, sequence: 0, seen: [] };
        this.state = next; this.disconnect('new_pairing', 410);
        await this.persist(next);
        await this.schedule();
        return this.json(200, { ticket, pairing_expires_at: next.pairingExpires, expires_at: this.expiryValue() });
      }
      if (path === '/v1/device/approve') {
        const requestedScope = approvalScope(body);
        if (!requestedScope || (requestedScope.persistent && this.protocolVersion() !== 4) || typeof body.claim_id !== 'string' || !HEX32.test(body.claim_id) || typeof body.allow_tap !== 'boolean') return this.json(400, { error: 'invalid_approval' });
        if (!s.candidate || body.claim_id !== s.candidate.id) return this.json(409, { error: 'stale_claim' });
        if (s.phase === 'approved' && body.allow_tap === s.allowTap && (requestedScope.persistent === true) === (s.persistent === true) && requestedScope.allowControl === (s.allowControl === true) && JSON.stringify(requestedScope.allowedPackages) === JSON.stringify(s.allowedPackages ?? [])) return this.json(200, { ok: true, session_expires_at: s.sessionExpires, allow_tap: s.allowTap, ...scope(s) });
        if (s.phase !== 'pending' || now() >= s.pairingExpires) return this.json(409, { error: 'claim_not_pending' });
        const next = { ...s, ...requestedScope, phase: 'approved', ticketHash: null, sessionExpires: requestedScope.persistent ? null : Math.min(now() + 900, this.globalDeadline()), allowTap: body.allow_tap };
        await this.persist(next); await this.schedule();
        return this.json(200, { ok: true, session_expires_at: next.sessionExpires, allow_tap: next.allowTap, ...scope(next) });
      }
      if (path === '/v1/device/revoke') return this.revoke();
      if (path === '/v1/device') {
        if (!this.active()) return this.json(403, { error: 'phone_approval_required' });
        return this.accept(request);
      }
    }
    const denied = this.controllerAuth(tokenHash, path === '/v1/pair/status');
    if (denied) return denied;
    if (path === '/v1/pair/status') return this.json(200, this.candidateState());
    if (path === '/v1/status') {
      const connected = this.connected(), connection = connected ? attachment(this.device) : null;
      return this.json(200, { protocol_version: this.protocolVersion(), deviceConnected: connected, pending: this.pending ? 1 : 0,
        session_expires_at: s.sessionExpires, allow_tap: s.allowTap, ...scope(s),
        connection_id: connection?.connectionId ?? null, connected_at: connection?.connectedAt ?? null,
        last_seen_at: connection?.lastSeenAt ?? null, command_budget_remaining: s.persistent ? null : Math.max(0, 200 - s.seen.length) });
    }
    if (path === '/v1/revoke') return this.revoke();
    if (path === '/v1/command') return this.command(body);
    return this.json(404, { error: 'not_found' });
  }
  async revoke() {
    const next = { ...this.state, phase: 'revoked', ticketHash: null, allowTap: false, allowControl: false, allowedPackages: [], generation: this.state.generation + 1 };
    // Stop live work first. Failure to persist is reported as failure, never success.
    this.state = next; this.disconnect('revoked', 410);
    await this.persist(next); await this.schedule();
    return this.json(200, { ok: true });
  }
  async accept(request) {
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return this.json(426, { error: 'websocket_required' });
    // handle() reaches here only after the device bearer and active grant checks,
    // under the request serialization lock. A controller cannot replace a phone.
    const replace = this.replaceAuthenticatedDeviceConnection();
    if (this.connected() && !replace) return this.json(409, { error: 'device_already_connected' });
    // retire() persists the old socket's retired marker before closing it. Keep
    // the grant generation, seen IDs and all budgets: reconnect never replays or
    // extends work. A command already sent to the old phone has unknown outcome.
    if (this.device || this.pending) this.disconnect(replace ? 'connection_replaced_outcome_unknown' : 'connection_replaced', 503, 1012);
    await this.schedule();
    if (!this.active()) return this.json(410, { error: 'expired' });
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, ['device']);
    server.serializeAttachment({ version: 1, generation: this.state.generation, retired: false, helloReceived: false,
      connectionId: randomHex(16), connectedAt: now(), lastSeenAt: null });
    this.device = server;
    return new Response(null, { status: 101, webSocket: client, headers: this.headers() });
  }
  async command(cmd) {
    const s = this.state;
    if (!commandShape(cmd, s.persistent === true)) return this.json(400, { error: 'invalid_command' });
    if (cmd.package && (authorizationSurface(cmd.package) || !(s.allApps === true || (s.allowedPackages ?? []).includes(cmd.package)))) return this.json(403, { error: 'package_permission_required' });
    if ((CONTROL_ACTIONS.has(cmd.action) || FILE_WRITE_ACTIONS.has(cmd.action)) && s.allowControl !== true) return this.json(403, { error: 'control_permission_required' });
    if (cmd.action === 'tap_test' && !s.allowTap) return this.json(403, { error: 'tap_permission_required' });
    if (s.persistent && cmd.sequence <= (s.sequence ?? 0)) return this.json(409, { error: 'sequence_already_admitted', admitted: false });
    if (s.seen.includes(cmd.id)) return this.json(409, { error: 'request_already_admitted', detail: 'Inspect state; do not replay.' });
    if (this.pending) return this.json(429, { error: 'busy', admitted: false });
    if (!this.connected()) return this.json(503, { error: 'device_disconnected', admitted: false });
    if (!s.persistent && s.seen.length >= 200) return this.json(429, { error: 'preview_command_budget_exhausted' });
    const admittedDevice = this.device;
    const denied = await this.beforeCommand(cmd);
    if (denied) return denied;
    if (!this.active() || this.state.generation !== s.generation) return this.json(410, { error: 'expired_or_revoked' });
    if (!this.connected() || admittedDevice !== this.device || this.pending) return this.json(503, { error: 'connection_changed', admitted: false });
    await this.persist({ ...s, ...(s.persistent ? { sequence: cmd.sequence } : {}), seen: [...s.seen, cmd.id].slice(s.persistent ? -200 : 0) });
    if (!this.active()) { this.disconnect('expired', 410); return this.json(410, { error: 'expired_request_consumed' }); }
    if (!this.connected() || admittedDevice !== this.device) return this.json(503, { error: 'connection_changed_request_consumed' });
    const pendingResponse = new Promise(resolve => {
      const entry = { id: cmd.id, action: cmd.action, generation: s.generation, resolve, timer: null }; this.pending = entry;
      entry.timer = setTimeout(() => {
        if (this.pending !== entry) return;
        if (!this.active()) { this.disconnect('expired_outcome_unknown', 410); return; }
        this.finish(this.json(504, { error: 'device_timeout', detail: 'Outcome unknown; inspect before another action.' }));
        this.disconnect('timeout', 503, 1011);
      }, Math.min(15000, this.state.persistent ? Infinity : this.state.sessionExpires * 1000 - Date.now(), this.globalDeadline() * 1000 - Date.now()));
      try { admittedDevice.send(JSON.stringify(cmd)); } catch { this.disconnect('device_disconnected_outcome_unknown', 503, 1011); }
    });
    return { pendingResponse };
  }
  finish(response) { const entry = this.pending; this.pending = null; if (entry) { clearTimeout(entry.timer); entry.resolve(response); } }
  disconnect(reason, status = 503, code = 1008) { const socket = this.device; this.device = null; this.finish(this.json(status, { error: reason })); if (socket) retire(socket, reason, code); }
  webSocketMessage(socket, data) {
    if (socket !== this.device) return;
    if (!this.active() || !live(socket, this.state.generation)) { this.disconnect('expired_or_revoked', 410); return; }
    socket.serializeAttachment({ ...attachment(socket), lastSeenAt: now() });
    // Send once from the phone to avoid workerd #7566 on a silent socket.
    if (data === 'hello') {
      const a = attachment(socket);
      if (a?.helloReceived !== false) { this.disconnect('duplicate_hello', 502); return; }
      socket.serializeAttachment({ ...a, helloReceived: true }); return;
    }
    const entry = this.pending;
    if (!entry || entry.generation !== this.state.generation) { this.disconnect('unsolicited_response', 502); return; }
    if (typeof data === 'string') {
      if (encode(data).byteLength > (entry.action === 'observe' ? IMAGE_LIMIT : TEXT_LIMIT)) { this.disconnect('response_too_large', 502); return; }
      let body; try { body = JSON.parse(data); } catch { this.disconnect('invalid_json_response', 502); return; }
      if (!body || Array.isArray(body) || typeof body !== 'object' || body.id !== entry.id || typeof body.ok !== 'boolean' || (entry.action === 'screenshot' && body.ok)) { this.disconnect('invalid_response', 502); return; }
      this.finish(this.json(200, body)); return;
    }
    if (entry.action !== 'screenshot' || !(data instanceof ArrayBuffer) || data.byteLength < 40 || data.byteLength > 36 + IMAGE_LIMIT) { this.disconnect('invalid_image', 502); return; }
    const bytes = new Uint8Array(data), id = new TextDecoder().decode(bytes.slice(0, 36));
    if (id !== entry.id || bytes[36] !== 255 || bytes[37] !== 216 || bytes.at(-2) !== 255 || bytes.at(-1) !== 217) { this.disconnect('invalid_image', 502); return; }
    this.finish(new Response(data.slice(36), { headers: { ...this.headers(), 'Content-Type': 'image/jpeg' } }));
  }
  webSocketClose(socket, code, reason) {
    if (socket === this.device) { this.device = null; this.finish(this.json(503, { error: 'device_disconnected_outcome_unknown' })); }
    retire(socket, reason, code === 1005 ? 1000 : code);
  }
  webSocketError(socket) { if (socket === this.device) this.disconnect('device_error', 503, 1011); else retire(socket, 'device_error', 1011); }
  async alarm() { await this.ctx.blockConcurrencyWhile(async () => { await this.expire(); if (!this.globalExpired()) await this.schedule(); }); }
}

// Shared primitives for the separately deployed, multi-device service entry.
export { publicAsset, route, bearer, readBody, hash, equal, exact, HEX32, HEX64, now };

// Opt-in v4 extension. The original single-grant state and execution path remain
// untouched until the enrolled device explicitly migrates them.
const MULTI_CAPS = ['multi_ai_v1', 'task_lease_v1'];
const leaseInteger = n => Number.isSafeInteger(n) && n > 0;
const sequenceInteger = n => Number.isSafeInteger(n) && n >= 0;
const h32 = n => typeof n === 'string' && HEX32.test(n);
const sameCaps = (a, b) => Array.isArray(a) && JSON.stringify(a) === JSON.stringify(b);
const TASK_TTL = 120000;
const FENCE_WAIT = 15000;
export class MultiPairingRelay extends PairingRelay {
  emptyPostBody(path) { return path === '/v1/device/revoke' && this.multi() ? false : super.emptyPostBody(path); }
  multi() { return this.state.mode === 'multi' && this.state.schema_version === 2; }
  transportReady() { return !!this.device && live(this.device, this.state.generation) && attachment(this.device)?.multiHello === true; }
  active() { return this.multi() ? !this.globalExpired() && Object.keys(this.state.grants).length > 0 : super.active(); }
  connected() { return this.multi() ? this.active() && this.transportReady() : super.connected(); }
  grantByHash(tokenHash) {
    if (!this.multi()) return equal(tokenHash, this.state.candidate?.hash) ? this.state.candidate : null;
    return Object.values(this.state.grants).find(g => equal(tokenHash, g.hash)) ?? null;
  }
  controllerClaim(tokenHash) { return this.grantByHash(tokenHash)?.id ?? null; }
  controllerSequence(tokenHash) { return this.multi() ? this.grantByHash(tokenHash)?.sequence ?? 0 : this.state.sequence ?? 0; }
  controllerAuth(tokenHash, pendingAllowed = false) {
    if (!this.multi()) return super.controllerAuth(tokenHash, pendingAllowed);
    if (this.globalExpired()) return this.json(410, { error: 'expired_or_revoked' });
    if (this.grantByHash(tokenHash)) return null;
    if (equal(tokenHash, this.state.invitation?.candidate?.hash))
      return pendingAllowed ? null : this.json(403, { error: 'phone_approval_required' });
    return this.json(401, { error: 'unauthorized' });
  }
  taskMatchesConnection(task = this.state.task) {
    return !!task && this.transportReady() && task.connectionId === attachment(this.device)?.connectionId;
  }
  async restoreTransport() {
    if (!this.multi()) return;
    const t = this.state.task, a = attachment(this.device);
    const confirmed = t && t.state === 'ready' && t.expiresAtMs > Date.now() && this.taskMatchesConnection(t) &&
      !a?.pendingCommand && a?.task?.leaseId === t.leaseId && a.task.epoch === t.epoch &&
      a.task.expiresAtMs === t.expiresAtMs && a.task.operationId === t.operationId && a.task.renewalSequence === t.renewalSequence;
    // Hibernation is safe only with matching durable state AND socket evidence.
    // Unknown in-flight work retires this transport rather than guessing ready.
    if (a?.pendingCommand || t && !confirmed) {
      this.disconnect('task_recovery_required', 503, 1012);
      await this.persist(this.closedState(this.state, t));
    }
  }
  closedState(state, task) {
    if (!task) return { ...state, task: null };
    const item = { claimId: task.claimId, taskId: task.taskId, leaseId: task.leaseId, epoch: task.epoch };
    return { ...state, task: null, closedTasks: [...state.closedTasks.filter(t => t.leaseId !== task.leaseId), item].slice(-32) };
  }
  async scheduleMulti() {
    const deadlines = [this.state.invitation?.expiresAt * 1000, this.state.task?.state !== 'releasing' ? this.state.task?.expiresAtMs : null].filter(n => Number.isFinite(n) && n > Date.now());
    if (deadlines.length) await this.ctx.storage.setAlarm(Math.min(...deadlines));
    else await this.ctx.storage.deleteAlarm();
  }
  async expire() {
    if (!this.multi()) return super.expire();
    let s = this.state;
    if (s.invitation && s.invitation.expiresAt <= now()) {
      await this.persist({ ...s, invitation: null }); s = this.state;
    }
    if (s.task && !this.taskMatchesConnection()) {
      this.resolveFence(this.json(503, { error: 'device_disconnected' }));
      await this.persist(this.closedState(s, s.task)); s = this.state;
    }
    if (s.task && s.task.state !== 'releasing' && s.task.expiresAtMs <= Date.now()) await this.beginRelease(s.task);
    if (this.state.task?.state === 'releasing') this.sendFence();
    await this.scheduleMulti();
  }
  multiState() {
    const s = this.state, i = s.invitation;
    return { mode: 'multi', generation: s.generation, max_grants: 4, invitation_sequence: s.invitationSequence,
      grants: Object.values(s.grants).map(g => ({ claim_id: g.id, name: g.name, state: 'approved',
        ...scope(g.scope), command_sequence: g.sequence, lease_required: g.lease_required, lease_ready: g.lease_ready })),
      invitation: i ? { invitation_id: i.id, invitation_sequence: i.sequence, state: i.candidate ? 'pending' : 'waiting', pairing_expires_at: i.expiresAt,
        candidate: i.candidate ? { claim_id: i.candidate.id, name: i.candidate.name, verification_code: i.candidate.code } : null } : null };
  }
  pairState(identity) {
    const g = this.state.grants[identity.id], i = this.state.invitation;
    return { state: g ? 'approved' : 'pending', claim_id: identity.id, verification_code: identity.code,
      pairing_expires_at: g ? identity.pairingExpires : i.expiresAt,
      ...(g ? { session_expires_at: null, allow_tap: g.scope.allowTap, ...scope(g.scope), command_sequence: g.sequence,
        lease_required: g.lease_required, lease_ready: g.lease_ready } : {}) };
  }
  multiStatus(g) {
    const connected = this.connected(), a = connected ? attachment(this.device) : null, t = this.state.task;
    const task = { state: t ? t.claimId === g.id ? t.state === 'ready' ? 'ready' : 'fencing' : 'busy' : 'idle',
      task_id: null, lease_id: null, lease_epoch: null, expires_at_ms: null, expires_in_ms: null, renewal_sequence: null };
    if (t?.claimId === g.id) Object.assign(task, { task_id: t.taskId, lease_epoch: t.epoch, expires_at_ms: t.expiresAtMs,
      expires_in_ms: Math.max(0, Math.min(TASK_TTL, t.expiresAtMs - Date.now())),
      renewal_sequence: t.renewalSequence, ...(t.state === 'ready' ? { lease_id: t.leaseId } : {}) });
    return { mode: 'multi', claim_id: g.id, deviceConnected: connected, pending: this.pending?.claimId === g.id ? 1 : 0,
      session_expires_at: null, allow_tap: g.scope.allowTap, ...scope(g.scope), command_sequence: g.sequence,
      connection_id: a?.connectionId ?? null, connected_at: a?.connectedAt ?? null, last_seen_at: a?.lastSeenAt ?? null,
      command_budget_remaining: null, lease_required: g.lease_required, lease_ready: g.lease_ready, task };
  }
  async enableMulti(body) {
    if (this.globalExpired()) return this.json(410, { error: 'device_unenrolled' });
    const legacy = exact(body, ['capabilities']), fenced = exact(body, ['capabilities', 'expected_claim_id']) && h32(body.expected_claim_id);
    if ((!legacy && !fenced) || !sameCaps(body.capabilities, MULTI_CAPS)) return this.json(400, { error: 'invalid_request' });
    if (this.multi()) {
      // Legacy retries may read the already-migrated state, never create it.
      if (fenced && !this.state.grants[body.expected_claim_id]) return this.json(409, { error: 'stale_claim' });
      return this.json(200, this.multiState());
    }
    if (!fenced) return this.json(400, { error: 'expected_claim_required' });
    const s = this.state;
    if (this.protocolVersion() !== 4 || s.version !== 1 || s.schema_version !== undefined || s.mode !== undefined ||
        !['idle', 'waiting', 'pending', 'approved', 'revoked'].includes(s.phase) || !Array.isArray(s.seen))
      return this.json(409, { error: 'unsupported_state_schema' });
    // An old request must not migrate a later pairing on the same enrollment.
    if (s.phase !== 'approved' || s.candidate?.id !== body.expected_claim_id) return this.json(409, { error: 'stale_claim' });
    if (s.persistent !== true || !h32(s.candidate?.id) || !HEX64.test(s.candidate?.hash ?? '') ||
        !sequenceInteger(s.sequence ?? 0)) return this.json(409, { error: 'unsupported_state_schema' });
    const c = s.candidate;
    const grants = { [c.id]: { ...c, state: 'approved', pairingExpires: s.pairingExpires, sequence: s.sequence ?? 0, seen: [...s.seen],
      scope: { persistent: true, allApps: s.allApps === true, allowControl: s.allowControl === true,
        allowedPackages: [...(s.allowedPackages ?? [])], allowTap: s.allowTap === true }, lease_required: false, lease_ready: false } };
    const next = { ...s, schema_version: 2, mode: 'multi', grants, invitation: null, invitationSequence: 0,
      task: null, taskEpoch: 0, closedTasks: [], phase: 'multi', candidate: null, ticketHash: null, persistent: false, seen: [] };
    // Persist first; a failed migration cannot invalidate the old pairing.
    await this.persist(next); this.disconnect('multi_protocol_enabled', 503, 1012); await this.scheduleMulti();
    return this.json(200, this.multiState());
  }
  async pairMulti(body) {
    const caps = exact(body, ['ticket', 'controller_token', 'name', 'capabilities']) && sameCaps(body.capabilities, ['task_lease_v1']);
    const legacy = exact(body, ['ticket', 'controller_token', 'name']);
    if ((!caps && !legacy) || !h32(body?.ticket) || typeof body.controller_token !== 'string' || !HEX64.test(body.controller_token) ||
        typeof body.name !== 'string' || [...body.name].length < 1 || [...body.name].length > 80 || /[\p{Cc}\p{Cf}\p{Cs}]/u.test(body.name) || !body.name.trim())
      return this.json(400, { error: 'invalid_pairing_request' });
    const i = this.state.invitation;
    if (!i || !equal(await hash(body.ticket), i.ticketHash)) return this.json(401, { error: 'invalid_or_consumed_ticket' });
    if (legacy && i.origin !== 'legacy_v4') return this.json(409, { error: 'client_upgrade_required' });
    const controllerHash = await hash(body.controller_token);
    if (equal(controllerHash, this.state.deviceHash) || this.grantByHash(controllerHash)) return this.json(400, { error: 'distinct_controller_token_required' });
    if (i.candidate) {
      if (equal(controllerHash, i.candidate.hash) && body.name === i.candidate.name && sameCaps(body.capabilities ?? [], i.candidate.capabilities))
        return this.json(200, this.pairState(i.candidate));
      return this.json(409, { error: 'ticket_already_claimed' });
    }
    let sample; do { sample = crypto.getRandomValues(new Uint32Array(1))[0]; } while (sample >= 4294000000);
    const candidate = { id: randomHex(16), hash: controllerHash, name: body.name, code: String(sample % 1000000).padStart(6, '0'), capabilities: body.capabilities ?? [] };
    await this.persist({ ...this.state, invitation: { ...i, candidate } });
    return this.json(200, this.pairState(candidate));
  }
  async invitation(body, cancel = false) {
    if (!exact(body, cancel ? ['invitation_id', 'invitation_sequence'] : ['invitation_id', 'invitation_sequence', 'ticket']) ||
        !h32(body.invitation_id) || !leaseInteger(body.invitation_sequence) || !cancel && !h32(body.ticket)) return this.json(400, { error: 'invalid_request' });
    const s = this.state, i = s.invitation;
    if (cancel) {
      if (i && (i.id !== body.invitation_id || i.sequence !== body.invitation_sequence)) return this.json(409, { error: 'stale_invitation' });
      if (!i && body.invitation_sequence > s.invitationSequence) return this.json(409, { error: 'stale_invitation' });
      if (i) { await this.persist({ ...s, invitation: null }); await this.scheduleMulti(); }
      return this.json(200, { ok: true });
    }
    const ticketHash = await hash(body.ticket);
    if (i && i.id === body.invitation_id && i.sequence === body.invitation_sequence && equal(i.ticketHash, ticketHash)) return this.invitationResult(i);
    // Durable watermark, retained across expiry/cancel/revoke; a 32-ID cache
    // alone would allow a delayed request to reopen after eviction.
    if (body.invitation_sequence <= s.invitationSequence) return this.json(409, { error: 'stale_invitation' });
    if (i) return this.json(409, { error: 'invitation_pending' });
    if (Object.keys(s.grants).length >= 4) return this.json(409, { error: 'grant_limit_reached' });
    if (Object.values(s.grants).some(g => !g.lease_ready)) return this.json(409, { error: 'client_upgrade_required' });
    if (Object.keys(s.grants).length && !this.transportReady()) return this.json(409, { error: 'device_upgrade_required' });
    if (![this.env.CLI_SHA256, this.env.AGENT_SHA256].every(x => typeof x === 'string' && HEX64.test(x))) return this.json(503, { error: 'artifact_metadata_unavailable' });
    const next = { id: body.invitation_id, sequence: body.invitation_sequence, origin: 'multi_ai_v1', ticketHash, expiresAt: now() + PAIRING_TTL_SECONDS, candidate: null };
    await this.persist({ ...s, invitation: next, invitationSequence: next.sequence }); await this.scheduleMulti();
    return this.invitationResult(next);
  }
  invitationResult(i) { return this.json(200, { invitation_id: i.id, invitation_sequence: i.sequence, pairing_expires_at: i.expiresAt,
    expires_at: null, cli_sha256: this.env.CLI_SHA256, agent_sha256: this.env.AGENT_SHA256 }); }
  async approveMulti(body) {
    const desired = approvalScope(body);
    if (!desired?.persistent || body.allow_tap !== false || !h32(body.claim_id)) return this.json(400, { error: 'invalid_approval' });
    const s = this.state, existing = s.grants[body.claim_id];
    if (existing && (existing.scope.allowTap !== false || existing.scope.allApps !== true || existing.scope.allowControl !== true || existing.scope.allowedPackages.length !== 0)) return this.json(409, { error: 'approval_scope_conflict' });
    if (existing) return this.json(200, { ok: true, claim_id: existing.id, session_expires_at: null, allow_tap: existing.scope.allowTap, ...scope(existing.scope), command_sequence: existing.sequence });
    const i = s.invitation;
    if (!i?.candidate || i.candidate.id !== body.claim_id) return this.json(409, { error: 'stale_claim' });
    const c = i.candidate, legacy = i.origin === 'legacy_v4' && Object.keys(s.grants).length === 0 && !c.capabilities.includes('task_lease_v1');
    const g = { ...c, state: 'approved', pairingExpires: i.expiresAt, sequence: 0, seen: [], scope: { ...desired, allowTap: false }, lease_required: !legacy, lease_ready: false };
    await this.persist({ ...s, grants: { ...s.grants, [g.id]: g }, invitation: null }); await this.scheduleMulti();
    return this.json(200, { ok: true, claim_id: g.id, session_expires_at: null, allow_tap: false, ...scope(g.scope) });
  }
  async handle(path, request, body, token, tokenHash) {
    if (path === '/v1/device/multi/enable') {
      if (!equal(tokenHash, this.state.deviceHash)) return this.json(401, { error: 'unauthorized' });
      return this.enableMulti(body);
    }
    if (path === '/v1/device/grants/revoke') {
      if (!equal(tokenHash, this.state.deviceHash)) return this.json(401, { error: 'unauthorized' });
      if (!exact(body, ['claim_id']) || !h32(body.claim_id)) return this.json(400, { error: 'invalid_request' });
      return this.multi() ? this.revokeGrant(body.claim_id) : this.revokeSingleGrant(body.claim_id);
    }
    if (!this.multi()) return super.handle(path, request, body, token, tokenHash);
    if (path === '/v1/pair') return this.pairMulti(body);
    if (path.startsWith('/v1/device')) {
      if (!equal(tokenHash, this.state.deviceHash)) return this.json(401, { error: 'unauthorized' });
      if (path === '/v1/device/grants' || path === '/v1/device/pairing' && request.method === 'GET') return this.json(200, this.multiState());
      if (path === '/v1/device/pairing' || path === '/v1/device/approve') return this.json(409, { error: 'multi_api_required' });
      if (path === '/v1/device/invitations') return this.invitation(body);
      if (path === '/v1/device/invitations/cancel') return this.invitation(body, true);
      if (path === '/v1/device/grants/approve') return this.approveMulti(body);
      if (path === '/v1/device/revoke') return this.revoke(body);
      if (path === '/v1/device') {
        if (!this.active()) return this.json(403, { error: 'phone_approval_required' });
        return this.accept(request);
      }
    }
    const denied = this.controllerAuth(tokenHash, path === '/v1/pair/status'); if (denied) return denied;
    const g = this.grantByHash(tokenHash);
    if (path === '/v1/pair/status') return this.json(200, this.pairState(g ?? this.state.invitation.candidate));
    if (path === '/v1/status') return this.json(200, this.multiStatus(g));
    if (path === '/v1/revoke') return this.revokeGrant(g.id);
    if (path.startsWith('/v1/tasks/')) return this.taskRequest(path.slice('/v1/tasks/'.length), body, g.id);
    if (path === '/v1/command') return this.command(body, g.id);
    return this.json(404, { error: 'not_found' });
  }
  async revoke(body) {
    if (!this.multi()) return super.revoke();
    if (!exact(body, ['expected_generation']) || !sequenceInteger(body.expected_generation)) return this.json(400, { error: 'invalid_request' });
    const generation = this.state.generation;
    if (body.expected_generation > generation) return this.json(409, { error: 'generation_mismatch' });
    // A delayed retry confirms its old stop without touching later grants.
    if (body.expected_generation < generation) return this.json(200, { ok: true, generation });
    if (!sequenceInteger(generation + 1)) return this.json(503, { error: 'storage_unavailable' });
    const next = { ...this.state, grants: {}, invitation: null, task: null, generation: generation + 1 };
    this.disconnect('revoked', 410);
    // Do not advance the in-memory receipt watermark before the write commits.
    // Otherwise a failed write followed by a retry could report durable success.
    await this.persist(next); await this.scheduleMulti(); return this.json(200, { ok: true, generation: next.generation });
  }
  async revokeGrant(claimId) {
    const s = this.state, grants = { ...s.grants }; delete grants[claimId];
    // Invalidate authorization before any storage/network wait. Persist failure
    // is failure, but the revoked controller is never admitted in this process.
    this.state = { ...s, grants }; await this.persist(this.state);
    if (this.pending?.claimId === claimId) this.finish(this.json(410, { error: 'revoked_outcome_unknown' }));
    if (this.state.task?.claimId === claimId) {
      await this.beginRelease(this.state.task);
      if (this.state.task && this.transportReady()) {
        const result = this.awaitFence();
        return { pendingResponse: result.pendingResponse.then(async r => this.json(200, { ok: true, device_stop_confirmed: r.ok })) };
      }
    }
    if (!Object.keys(grants).length) this.disconnect('no_approved_grants', 410);
    return this.json(200, { ok: true, device_stop_confirmed: false });
  }
  async revokeSingleGrant(claimId) {
    const s = this.state;
    if (s.version !== 1 || s.schema_version !== undefined || s.mode !== undefined ||
        !['idle', 'waiting', 'pending', 'approved', 'revoked'].includes(s.phase)) return this.json(409, { error: 'unsupported_state_schema' });
    // A delayed stop is scoped to its original claim, including during migration.
    if (!['pending', 'approved'].includes(s.phase) || s.candidate?.id !== claimId)
      return this.json(200, { ok: true, device_stop_confirmed: false });
    if (!sequenceInteger(s.generation + 1)) return this.json(503, { error: 'storage_unavailable' });
    const next = { ...s, phase: 'revoked', ticketHash: null, allowTap: false, allowControl: false,
      allowedPackages: [], generation: s.generation + 1 };
    this.disconnect('revoked', 410);
    // Unlike a speculative in-memory revocation, only a committed state can
    // make a later retry return an idempotent success.
    await this.persist(next); await this.schedule();
    return this.json(200, { ok: true, device_stop_confirmed: false });
  }
  async accept(request) {
    if (!this.multi()) return super.accept(request);
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') return this.json(426, { error: 'websocket_required' });
    this.disconnect('connection_replaced_outcome_unknown', 503, 1012);
    if (this.state.task) await this.persist(this.closedState(this.state, this.state.task));
    return super.accept(request);
  }
  taskReady(t) { return this.json(200, { ok: true, state: 'ready', task_id: t.taskId, lease_id: t.leaseId, lease_epoch: t.epoch,
    expires_at_ms: t.expiresAtMs, expires_in_ms: Math.max(0, t.expiresAtMs - Date.now()), renewal_sequence: t.renewalSequence }); }
  taskError(error, status = 409) { return this.json(status, { error, ...(error === 'device_busy' || error === 'task_fence_pending' ? { retry_after_ms: 1000 } : {}) }); }
  async taskRequest(action, body, claimId) {
    const fields = action === 'acquire' ? ['task_id'] : action === 'renew' ? ['task_id', 'lease_id', 'lease_epoch', 'renewal_id', 'renewal_sequence'] : ['task_id', 'lease_id', 'lease_epoch'];
    if (!['acquire', 'renew', 'release'].includes(action) || !exact(body, fields) || !h32(body.task_id) || action !== 'acquire' && (!h32(body.lease_id) || !leaseInteger(body.lease_epoch)) ||
        action === 'renew' && (!h32(body.renewal_id) || !leaseInteger(body.renewal_sequence))) return this.taskError('invalid_request', 400);
    if (!this.state.grants[claimId]) return this.taskError('unauthorized', 401);
    const t = this.state.task;
    if (action === 'release' && this.state.closedTasks.some(c => c.claimId === claimId && c.taskId === body.task_id && c.leaseId === body.lease_id && c.epoch === body.lease_epoch))
      return this.json(200, { ok: true, state: 'released' });
    if (action === 'acquire') {
      if (this.state.closedTasks.some(c => c.claimId === claimId && c.taskId === body.task_id)) return this.taskError('task_closed');
      if (t) {
        if (t.claimId !== claimId || t.taskId !== body.task_id) return this.taskError('device_busy', 429);
        if (t.state === 'releasing') return this.taskError('task_closed');
        if (t.state === 'ready') return this.taskReady(t);
        return this.awaitFence();
      }
      if (!this.connected()) return this.taskError('device_disconnected', 503);
      if (this.pending) return this.taskError('device_busy', 429);
      if (!leaseInteger(this.state.taskEpoch + 1)) return this.taskError('storage_unavailable', 503);
      const task = { claimId, taskId: body.task_id, leaseId: randomHex(16), epoch: this.state.taskEpoch + 1, state: 'fencing',
        expiresAtMs: Date.now() + TASK_TTL, operationId: randomHex(16), renewalId: null, renewalSequence: 0,
        connectionId: attachment(this.device).connectionId, fenceEpoch: this.state.taskEpoch + 1, fenceTtlMs: TASK_TTL };
      await this.persist({ ...this.state, task, taskEpoch: task.epoch }); await this.scheduleMulti();
      return this.awaitFence();
    }
    if (!t) return this.taskError('lease_expired', 410);
    if (t.claimId !== claimId || t.taskId !== body.task_id || t.leaseId !== body.lease_id || t.epoch !== body.lease_epoch) return this.taskError('lease_mismatch');
    if (action === 'release') { await this.beginRelease(t); return this.state.task ? this.awaitFence() : this.json(200, { ok: true, state: 'released' }); }
    if (t.state === 'releasing' || t.expiresAtMs <= Date.now()) return this.taskError('lease_expired', 410);
    if (body.renewal_sequence < t.renewalSequence || body.renewal_sequence === t.renewalSequence && body.renewal_id !== t.renewalId) return this.taskError('stale_renewal');
    if (body.renewal_sequence === t.renewalSequence) return t.state === 'ready' ? this.taskReady(t) : this.awaitFence();
    if (t.state !== 'ready') return this.taskError('task_fence_pending', 503);
    if (this.pending) return this.taskError('device_busy', 429);
    const next = { ...t, state: 'fencing', operationId: randomHex(16), renewalId: body.renewal_id, renewalSequence: body.renewal_sequence,
      expiresAtMs: Date.now() + TASK_TTL, fenceTtlMs: TASK_TTL };
    await this.persist({ ...this.state, task: next }); await this.scheduleMulti(); return this.awaitFence();
  }
  async beginRelease(t) {
    if (t.state === 'releasing') return;
    if (!this.taskMatchesConnection(t)) {
      this.resolveFence(this.taskError('task_closed'));
      if (this.pending) this.finish(this.json(409, { error: 'task_closed_outcome_unknown' }));
      await this.persist(this.closedState(this.state, t)); return;
    }
    const epoch = this.state.taskEpoch + 1;
    if (!leaseInteger(epoch)) { this.disconnect('epoch_exhausted', 503); await this.persist(this.closedState(this.state, t)); return; }
    const next = { ...t, state: 'releasing', operationId: randomHex(16), fenceEpoch: epoch, fenceTtlMs: 0 };
    // Keep the in-flight marker until the durable fencing state exists. If
    // storage fails, retire the uncertain transport; never restore an idle
    // ready lease while the old native action may still be running.
    try { await this.persist({ ...this.state, task: next, taskEpoch: epoch }); }
    catch (error) { this.disconnect('task_release_storage_failure_outcome_unknown', 503, 1011); throw error; }
    this.resolveFence(this.taskError('task_closed'));
    if (this.pending) this.finish(this.json(409, { error: 'task_closed_outcome_unknown' }));
    await this.scheduleMulti(); this.sendFence();
  }
  sendFence() {
    const t = this.state.task;
    if (!t || !this.taskMatchesConnection(t)) return false;
    const holder = t.state === 'releasing' ? null : { claim_id: t.claimId, task_id: t.taskId, lease_id: t.leaseId };
    try { this.device.send(JSON.stringify({ type: 'task_fence', operation_id: t.operationId, lease_epoch: t.fenceEpoch, holder, ttl_ms: t.fenceTtlMs, renewal_sequence: holder ? t.renewalSequence : 0 })); return true; }
    catch { this.disconnect('device_disconnected', 503); return false; }
  }
  awaitFence() {
    const t = this.state.task;
    if (!t || !this.taskMatchesConnection(t)) return { pendingResponse: Promise.resolve(this.taskError('device_disconnected', 503)) };
    const pendingResponse = new Promise(resolve => {
      const waiter = { operationId: t.operationId, resolve, timer: null };
      this.fenceWaiters ??= [];
      waiter.timer = setTimeout(() => { this.fenceWaiters = this.fenceWaiters.filter(w => w !== waiter); resolve(this.taskError('task_fence_pending', 503)); }, FENCE_WAIT);
      this.fenceWaiters.push(waiter);
    });
    this.sendFence(); return { pendingResponse };
  }
  resolveFence(response) {
    const waiters = this.fenceWaiters ?? []; this.fenceWaiters = [];
    for (const w of waiters) { clearTimeout(w.timer); w.resolve(response.clone()); }
  }
  async acknowledgeFence(socket, body) {
    const t = this.state.task;
    if (!t || socket !== this.device || body.operation_id !== t.operationId || body.lease_epoch !== t.fenceEpoch) return; // late ACK has no authority
    if (!body.ok) { this.resolveFence(this.taskError('task_fence_pending', 503)); return; }
    if (t.state === 'ready') return; // duplicate ACK cannot extend or clear context
    if (t.state !== 'releasing' && t.expiresAtMs <= Date.now()) { await this.beginRelease(t); return; }
    const a = attachment(socket);
    if (t.state === 'releasing') {
      socket.serializeAttachment({ ...a, task: null, pendingCommand: false });
      await this.persist(this.closedState(this.state, t)); await this.scheduleMulti();
      this.resolveFence(this.json(200, { ok: true, state: 'released' }));
      if (!Object.keys(this.state.grants).length) this.disconnect('no_approved_grants', 410);
      return;
    }
    const g = this.state.grants[t.claimId]; if (!g) { await this.beginRelease(t); return; }
    // Both records must be present before ready is reported. Crash between them
    // fails the restore comparison and retires the uncertain transport.
    socket.serializeAttachment({ ...a, task: { leaseId: t.leaseId, epoch: t.epoch, expiresAtMs: t.expiresAtMs, operationId: t.operationId, renewalSequence: t.renewalSequence }, pendingCommand: false });
    const next = { ...t, state: 'ready' };
    await this.persist({ ...this.state, task: next, grants: { ...this.state.grants, [g.id]: { ...g, lease_required: true, lease_ready: true } } });
    if (next.expiresAtMs <= Date.now()) { await this.beginRelease(next); return; }
    this.resolveFence(this.taskReady(next));
  }
  leaseDenied(cmd, claimId) {
    const g = this.state.grants[claimId], t = this.state.task;
    if (!g) return this.json(401, { error: 'unauthorized', admitted: false });
    const fields = 'lease_id' in cmd || 'lease_epoch' in cmd;
    if (EVENT_ACTIONS.has(cmd.action)) return fields ? this.json(400, { error: 'invalid_command', admitted: false }) : null;
    if (!fields && !g.lease_required && Object.keys(this.state.grants).length === 1 && !t) return null;
    let error;
    if (!h32(cmd.lease_id) || !leaseInteger(cmd.lease_epoch)) error = 'lease_required';
    else if (!t || t.expiresAtMs <= Date.now()) error = 'lease_expired';
    else if (t.claimId !== claimId || t.leaseId !== cmd.lease_id || t.epoch !== cmd.lease_epoch) error = 'lease_mismatch';
    else if (t.state !== 'ready') error = 'task_fence_pending';
    else if (t.expiresAtMs - Date.now() < 20000) error = 'lease_renew_required';
    return error ? this.json(error === 'lease_expired' ? 410 : error === 'task_fence_pending' ? 503 : 409, { error, admitted: false }) : null;
  }
  async command(cmd, claimId) {
    if (!this.multi()) return super.command(cmd);
    if (!cmd || typeof cmd !== 'object' || Array.isArray(cmd)) return this.json(400, { error: 'invalid_command', admitted: false });
    const { lease_id, lease_epoch, ...raw } = cmd, g = this.state.grants[claimId];
    const deniedLease = this.leaseDenied(cmd, claimId); if (deniedLease) return deniedLease;
    if (!commandShape(raw, true)) return this.json(400, { error: 'invalid_command', admitted: false });
    if (raw.package && (authorizationSurface(raw.package) || !(g.scope.allApps || g.scope.allowedPackages.includes(raw.package)))) return this.json(403, { error: 'package_permission_required', admitted: false });
    if ((CONTROL_ACTIONS.has(raw.action) || FILE_WRITE_ACTIONS.has(raw.action)) && !g.scope.allowControl) return this.json(403, { error: 'control_permission_required', admitted: false });
    if (raw.action === 'tap_test' && !g.scope.allowTap) return this.json(403, { error: 'tap_permission_required', admitted: false });
    if (raw.sequence <= g.sequence) return this.json(409, { error: 'sequence_already_admitted', admitted: false });
    if (g.seen.includes(raw.id)) return this.json(409, { error: 'request_already_admitted', admitted: false });
    if (this.pending) return this.json(429, { error: 'device_busy', admitted: false, retry_after_ms: 1000 });
    if (!this.connected()) return this.json(503, { error: 'device_disconnected', admitted: false });
    const socket = this.device, generation = this.state.generation;
    const quota = await this.beforeCommand(raw); if (quota) return quota;
    const invalid = this.leaseDenied(cmd, claimId); if (invalid) return invalid;
    if (!this.connected() || this.device !== socket || generation !== this.state.generation) return this.json(503, { error: 'device_disconnected', admitted: false });
    const fresh = this.state.grants[claimId];
    // Attachment records an in-flight command before persisting admission. An
    // interruption at either step cannot restore an apparently idle ready lease.
    socket.serializeAttachment({ ...attachment(socket), pendingCommand: true });
    await this.persist({ ...this.state, grants: { ...this.state.grants, [claimId]: { ...fresh, sequence: raw.sequence, seen: [...fresh.seen, raw.id].slice(-200) } } });
    const pendingResponse = new Promise(resolve => {
      const entry = { id: raw.id, action: raw.action, generation, claimId, leaseId: lease_id ?? null, epoch: lease_epoch ?? null, resolve, timer: null };
      this.pending = entry;
      entry.timer = setTimeout(() => { if (this.pending === entry) { this.finish(this.json(504, { error: 'device_timeout', detail: 'Outcome unknown; inspect before another action.' })); this.disconnect('timeout', 503, 1011); } }, 15000);
      try { socket.send(JSON.stringify({ type: 'command', claim_id: claimId, lease_id: lease_id ?? null, lease_epoch: lease_epoch ?? null, command: raw })); }
      catch { this.disconnect('device_disconnected_outcome_unknown', 503, 1011); }
    });
    return { pendingResponse };
  }
  finish(response) {
    if (this.multi() && this.device && this.pending) this.device.serializeAttachment({ ...attachment(this.device), pendingCommand: false });
    super.finish(response);
  }
  disconnect(reason, status = 503, code = 1008) {
    if (this.multi()) this.resolveFence(this.taskError('device_disconnected', 503));
    super.disconnect(reason, status, code);
  }
  async webSocketMessage(socket, data) {
    if (!this.multi()) return super.webSocketMessage(socket, data);
    return this.ctx.blockConcurrencyWhile(async () => {
      if (socket !== this.device || !live(socket, this.state.generation)) return;
      let body;
      if (typeof data === 'string' && encode(data).byteLength <= 4096) { try { body = JSON.parse(data); } catch {} }
      const a = attachment(socket);
      if (!a?.multiHello) {
        if (!exact(body, ['type', 'protocol_version', 'capabilities']) || body.type !== 'hello' || body.protocol_version !== 4 || !sameCaps(body.capabilities, MULTI_CAPS)) { this.disconnect('device_upgrade_required', 409); return; }
        socket.serializeAttachment({ ...a, helloReceived: true, multiHello: true, lastSeenAt: now(), task: null, pendingCommand: false });
        socket.send(JSON.stringify({ type: 'hello_ack', protocol_version: 4, mode: 'multi', connection_id: a.connectionId })); return;
      }
      if (body?.type === 'task_fence_ack') {
        const valid = body.ok === true ? exact(body, ['type', 'operation_id', 'lease_epoch', 'ok']) : body.ok === false && exact(body, ['type', 'operation_id', 'lease_epoch', 'ok', 'error']) && ['command_in_progress', 'grant_unavailable', 'stale_fence', 'storage_failure'].includes(body.error);
        if (!valid || !h32(body.operation_id) || !leaseInteger(body.lease_epoch)) { this.disconnect('invalid_fence_ack', 502); return; }
        await this.acknowledgeFence(socket, body); return;
      }
      const p = this.pending, t = this.state.task;
      if (!p || !this.state.grants[p.claimId] || p.leaseId && (!t || t.state !== 'ready' || t.leaseId !== p.leaseId || t.epoch !== p.epoch || t.expiresAtMs <= Date.now())) {
        this.disconnect('stale_task_response', 502); return;
      }
      return super.webSocketMessage(socket, data);
    });
  }
  async webSocketClose(socket, code, reason) {
    if (!this.multi()) return super.webSocketClose(socket, code, reason);
    return this.ctx.blockConcurrencyWhile(async () => { super.webSocketClose(socket, code, reason); await this.expire(); });
  }
}
