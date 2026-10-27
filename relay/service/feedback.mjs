// Private feedback storage. No phone commands, remote fetches, model calls or logging.
// All keys share the existing AdmissionRegistry store under an independent prefix.
import { bearer, readBody, hash, equal, HEX32, HEX64 } from '../worker/worker.mjs';

export const REPORT_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const REPORT_STATUSES = ['new', 'investigating', 'needs_info', 'resolved', 'closed'];
const SCREEN_STATES = ['ready', 'screen_off', 'dismissible_keyguard', 'authentication_required', 'unavailable', 'unknown'];
const RETENTION = 30 * 86400, MAX_REPORTS = 2000, MAX_SCOPE_REPORTS = 100;
const P = 'feedback:', recordKey = id => P + 'report:' + id;
const expiryKey = row => P + 'expiry:' + String(row.expires_at).padStart(12, '0') + ':' + row.report_id;
const fingerprintKey = row => P + 'fingerprint:' + row.scope + ':' + row.fingerprint;
const scopeIndex = scope => P + 'index:' + scope + ':';
const ownerIndex = id => P + 'owner:' + id + ':';
const globalIndex = P + 'list:';
const fail = (status, error) => ({ status, body: { error } });
const success = (body, status = 200) => ({ status, body });
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const fields = (body, allowed, required = []) => object(body) && Object.keys(body).every(key => allowed.includes(key)) && required.every(key => Object.hasOwn(body, key));
const text = (value, max, multiline = false) => typeof value === 'string' && value.trim().length > 0 && [...value].length <= max &&
  !(multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\p{Cs}]/u : /[\p{Cc}\p{Cs}]/u).test(value);
const randomToken = () => [...crypto.getRandomValues(new Uint8Array(32))].map(value => value.toString(16).padStart(2, '0')).join('');
export const feedbackEnabled = env => typeof env.FEEDBACK_ADMIN_SHA256 === 'string' && env.FEEDBACK_ADMIN_SHA256.length === 64 && HEX64.test(env.FEEDBACK_ADMIN_SHA256);
export const isFeedbackPath = path => /^\/(?:v1\/(?:reports(?:\/[^/]+)?|admin\/reports(?:\/[^/]+)?)|v1\/device\/reports(?:\/[^/]+)?)$/.test(path);
export const feedbackListPath = path => /^\/(?:d\/[a-f0-9]{32}\/)?v1\/(?:device\/)?reports$/.test(path);
export async function adminAuthenticated(request, env) {
  const token = bearer(request);
  return feedbackEnabled(env) && token !== null && token.length === 64 && equal(await hash(token), env.FEEDBACK_ADMIN_SHA256);
}

export function validateReport(body) {
  const sizes = { title: 160, description: 8000, expected: 4000, actual: 4000, steps: 4000, phase: 64, error_code: 80, app_version: 64, agent: 160 };
  if (!fields(body, ['report_id', ...Object.keys(sizes), 'screen_state'], ['report_id', 'title', 'description']) || typeof body.report_id !== 'string' || body.report_id.length !== 36 || !REPORT_UUID.test(body.report_id)) return false;
  for (const [key, max] of Object.entries(sizes)) if (Object.hasOwn(body, key) && !text(body[key], max, ['description', 'expected', 'actual', 'steps'].includes(key))) return false;
  return !Object.hasOwn(body, 'screen_state') || SCREEN_STATES.includes(body.screen_state);
}
function pagination(body, admin = false) {
  const allowed = admin ? ['cursor', 'limit', 'status', 'device_id'] : ['cursor', 'limit'];
  if (!fields(body, allowed) || (Object.hasOwn(body, 'limit') && (!Number.isInteger(body.limit) || body.limit < 1 || body.limit > 50)) ||
      (Object.hasOwn(body, 'cursor') && (typeof body.cursor !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(body.cursor))) ||
      (Object.hasOwn(body, 'status') && !REPORT_STATUSES.includes(body.status)) ||
      (Object.hasOwn(body, 'device_id') && (typeof body.device_id !== 'string' || !HEX32.test(body.device_id)))) return null;
  return { limit: body.limit ?? 20, cursor: body.cursor, status: body.status, device_id: body.device_id };
}
export async function feedbackRequest(request, role) {
  const url = new URL(request.url), origin = request.headers.get('Origin');
  if (origin && origin !== url.origin) return fail(403, 'invalid_origin');
  const path = url.pathname, method = request.method;
  const base = role === 'admin' ? '/v1/admin/reports' : role === 'owner' ? '/v1/device/reports' : '/v1/reports';
  let operation, id, body = {};
  if (path === base && method === 'POST' && ['controller', 'anonymous'].includes(role)) operation = 'create';
  else if (path === base && method === 'GET' && ['controller', 'owner'].includes(role)) operation = 'list';
  else if (path === base + '/query' && method === 'POST' && role === 'admin') operation = 'list';
  else if (path.startsWith(base + '/') && REPORT_UUID.test(path.slice(base.length + 1))) {
    id = path.slice(base.length + 1);
    if (method === 'GET') operation = 'get';
    else if (method === 'DELETE') operation = 'delete';
    else if (method === 'PATCH' && ['controller', 'admin'].includes(role)) operation = 'update';
  }
  if (!operation) return fail(404, 'not_found');
  if (url.search && !(operation === 'list' && method === 'GET')) return fail(400, 'invalid_pagination');
  const hasBody = ['POST', 'PATCH'].includes(method);
  if (hasBody && !/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('Content-Type') ?? '')) return fail(415, 'json_required');
  const parsed = await readBody(request, hasBody ? 24 * 1024 : 0);
  if (parsed.error) return { status: parsed.error.status, body: await parsed.error.json() };
  if (hasBody) {
    try { body = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(parsed.bytes)); }
    catch { return fail(400, 'invalid_json'); }
  }
  if (operation === 'create' && !validateReport(body)) return fail(400, 'invalid_report');
  if (operation === 'update') {
    if (role === 'controller' && (!fields(body, ['supplement'], ['supplement']) || !text(body.supplement, 4000, true))) return fail(400, 'invalid_report_update');
    if (role === 'admin' && (!fields(body, ['status', 'maintenance_reply']) || Object.keys(body).length === 0 ||
        (Object.hasOwn(body, 'status') && !REPORT_STATUSES.includes(body.status)) ||
        (Object.hasOwn(body, 'maintenance_reply') && !text(body.maintenance_reply, 4000, true)))) return fail(400, 'invalid_report_update');
  }
  if (operation === 'list') {
    if (method === 'GET') {
      const keys = [...url.searchParams.keys()];
      if (keys.some(key => !['limit', 'cursor'].includes(key)) || new Set(keys).size !== keys.length) return fail(400, 'invalid_pagination');
      body = Object.fromEntries(url.searchParams);
      if (Object.hasOwn(body, 'limit')) {
        if (!/^[1-9][0-9]?$/.test(body.limit)) return fail(400, 'invalid_pagination');
        body.limit = Number(body.limit);
      }
    }
    body = pagination(body, role === 'admin');
    if (!body) return fail(400, 'invalid_pagination');
  }
  return { operation, id, body };
}

function summary(row) {
  return Object.fromEntries(['report_id', 'title', 'status', 'created_at', 'updated_at', 'expires_at', 'duplicate_of'].map(key => [key, row[key]]));
}
function receipt(row) { const { title, ...rest } = summary(row); return rest; }
function details(row, admin) {
  const { scope, receipt_hash, fingerprint, content_hash, device_id, claim_id, ...report } = row;
  return { ...report, ...(admin ? { device_id: device_id ?? null, claim_id: claim_id ?? null } : {}) };
}
function allowed(actor, row) {
  if (actor.role === 'admin') return true;
  if (actor.role === 'owner') return !!actor.device_id && actor.device_id === row.device_id;
  if (actor.role === 'controller') return actor.scope === row.scope;
  return actor.role === 'anonymous' && row.scope?.startsWith('anonymous:') && equal(actor.token_hash, row.receipt_hash);
}

// Injectable clock is local to this storage component for deterministic expiry tests;
// production always uses real wall time. There is no HTTP test/admin clock override.
export class FeedbackStore {
  constructor(storage, clock = () => Math.floor(Date.now() / 1000), cursorKey) { this.storage = storage; this.clock = clock; this.cursorKey = cursorKey; }
  async run(actor, command) {
    return this.storage.transaction(async storage => {
      const ledger = new FeedbackLedger(storage, this.clock(), this.cursorKey);
      await ledger.cleanup();
      const result = await ledger.run(actor, command);
      await ledger.schedule();
      return result;
    });
  }
  async alarm() {
    return this.storage.transaction(async storage => {
      const ledger = new FeedbackLedger(storage, this.clock(), this.cursorKey); await ledger.cleanup(); await ledger.schedule();
    });
  }
}
class FeedbackLedger {
  constructor(storage, time, cursorKey) { this.storage = storage; this.time = time; this.cursorKey = cursorKey; }
  async counts() { return await this.storage.get(P + 'counts') ?? { total: 0, scopes: {} }; }
  async expire(row) {
    const keys = [recordKey(row.report_id), expiryKey(row), globalIndex + row.report_id, scopeIndex(row.scope) + row.report_id];
    if (row.device_id) keys.push(ownerIndex(row.device_id) + row.report_id);
    if (row.fingerprint && (await this.storage.get(fingerprintKey(row)))?.report_id === row.report_id) keys.push(fingerprintKey(row));
    await this.storage.delete(keys);
    const counts = await this.counts(); counts.total = Math.max(0, counts.total - 1);
    if ((counts.scopes[row.scope] ?? 0) <= 1) delete counts.scopes[row.scope]; else counts.scopes[row.scope]--;
    await this.storage.put(P + 'counts', counts);
  }
  async cleanup() {
    const expiry = await this.storage.list({ prefix: P + 'expiry:', limit: 100 });
    for (const [key, row] of expiry) {
      if (row.expires_at > this.time) break;
      const record = await this.storage.get(recordKey(row.report_id));
      if (record) await this.expire(record); else await this.storage.delete(key);
    }
    const rates = await this.storage.get(P + 'rates');
    if (rates && rates.hour !== Math.floor(this.time / 3600)) await this.storage.delete(P + 'rates');
  }
  async schedule() {
    const expiry = await this.storage.list({ prefix: P + 'expiry:', limit: 1 });
    const rates = await this.storage.get(P + 'rates');
    const deadlines = [...expiry.values()].map(row => row.expires_at);
    if (rates) deadlines.push((rates.hour + 1) * 3600);
    if (deadlines.length) await this.storage.setAlarm(Math.max(this.time + 1, Math.min(...deadlines)) * 1000);
    else await this.storage.deleteAlarm();
  }
  async rate(actor) {
    const hour = Math.floor(this.time / 3600);
    const previous = await this.storage.get(P + 'rates');
    const rates = previous?.hour === hour ? previous : { hour, keys: {} };
    const key = actor.role === 'anonymous' ? 'anonymous:' + actor.ip_hash : actor.role === 'admin' ? 'admin' : actor.scope;
    const limit = actor.role === 'anonymous' ? 5 : actor.role === 'admin' ? 300 : 20;
    if ((rates.keys[key] ?? 0) >= limit || (actor.role === 'anonymous' && (rates.keys.anonymous_total ?? 0) >= 100)) return false;
    rates.keys[key] = (rates.keys[key] ?? 0) + 1;
    if (actor.role === 'anonymous') rates.keys.anonymous_total = (rates.keys.anonymous_total ?? 0) + 1;
    await this.storage.put(P + 'rates', rates); return true;
  }
  async index(row) {
    const value = { ...summary(row), scope: row.scope, device_id: row.device_id ?? null };
    const values = { [globalIndex + row.report_id]: value, [scopeIndex(row.scope) + row.report_id]: value };
    if (row.device_id) values[ownerIndex(row.device_id) + row.report_id] = value;
    await this.storage.put(values);
  }
  async load(id) {
    const row = await this.storage.get(recordKey(id));
    if (row && row.expires_at <= this.time) { await this.expire(row); return null; }
    return row;
  }
  async run(actor, { operation, id, body }) {
    if (operation === 'list') return this.list(actor, body);
    if (operation === 'create') return this.create(actor, body);
    const row = await this.load(id);
    if (!row || !allowed(actor, row)) return fail(404, 'report_not_found');
    if (operation === 'delete') {
      if (!row.deleted) {
        const tombstone = { report_id: id, scope: row.scope, expires_at: row.expires_at, deleted: true,
          ...(row.device_id ? { device_id: row.device_id } : {}), ...(row.receipt_hash ? { receipt_hash: row.receipt_hash } : {}) };
        await this.storage.put(recordKey(id), tombstone);
        const keys = [globalIndex + id, scopeIndex(row.scope) + id];
        if (row.device_id) keys.push(ownerIndex(row.device_id) + id);
        if ((await this.storage.get(fingerprintKey(row)))?.report_id === id) keys.push(fingerprintKey(row));
        await this.storage.delete(keys);
      }
      return success({ ok: true, report_id: id, deleted: true });
    }
    if (row.deleted) return fail(410, 'report_deleted');
    if (operation === 'get') return success({ report: details(row, actor.role === 'admin') });
    if (operation === 'update') {
      if (actor.role === 'controller' && row.supplements.length >= 5) return fail(429, 'report_supplement_limit');
      if (!await this.rate(actor)) return fail(429, 'feedback_rate_limited');
      if (actor.role === 'controller') row.supplements.push({ text: body.supplement, created_at: this.time });
      else Object.assign(row, body);
      row.updated_at = this.time;
      await this.storage.put(recordKey(id), row); await this.index(row);
      return success(receipt(row));
    }
    return fail(404, 'not_found');
  }
  async create(actor, body) {
    const existing = await this.load(body.report_id);
    // Normalize field order, preserving exact text. No report contents enter logs or error replies.
    const normalized = Object.fromEntries(Object.keys(body).sort().map(key => [key, body[key]]));
    const contentHash = await hash(JSON.stringify(normalized));
    if (existing) {
      if (!allowed(actor, existing)) return fail(409, 'report_id_conflict');
      if (existing.deleted) return fail(410, 'report_deleted');
      if (!equal(existing.content_hash, contentHash)) return fail(409, 'report_id_conflict');
      return success({ ...receipt(existing), replayed: true });
    }
    let counts = await this.counts();
    const scope = actor.role === 'anonymous' ? 'anonymous:' + body.report_id : actor.scope;
    if (counts.total >= MAX_REPORTS || (counts.scopes[scope] ?? 0) >= MAX_SCOPE_REPORTS) return fail(429, 'feedback_capacity_reached');
    if (!await this.rate(actor)) return fail(429, 'feedback_rate_limited');
    const { report_id, ...content } = normalized;
    const fingerprint = await hash(JSON.stringify(content));
    const previous = await this.storage.get(fingerprintKey({ scope, fingerprint }));
    const duplicate = previous ? await this.load(previous.report_id) : null;
    let accessToken;
    if (actor.role === 'anonymous') accessToken = actor.access_token ?? randomToken();
    const row = { ...normalized, scope, status: 'new', created_at: this.time, updated_at: this.time, expires_at: this.time + RETENTION,
      duplicate_of: duplicate && !duplicate.deleted && duplicate.scope === scope ? duplicate.report_id : null,
      supplements: [], fingerprint, content_hash: contentHash,
      ...(actor.device_id ? { device_id: actor.device_id, claim_id: actor.claim_id } : {}),
      ...(accessToken ? { receipt_hash: await hash(accessToken) } : {}) };
    await this.storage.put({ [recordKey(report_id)]: row, [expiryKey(row)]: { report_id, expires_at: row.expires_at },
      [fingerprintKey(row)]: { report_id, expires_at: row.expires_at } });
    await this.index(row);
    counts = await this.counts();
    counts.total++; counts.scopes[scope] = (counts.scopes[scope] ?? 0) + 1; await this.storage.put(P + 'counts', counts);
    return success({ ...receipt(row), ...(accessToken ? { access_token: accessToken } : {}) }, 201);
  }
  async cursorMac(actor, body, id) {
    const context = JSON.stringify([actor.role, actor.scope ?? actor.device_id ?? '', body.status ?? '', body.device_id ?? '', id]);
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(this.cursorKey), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    return [...new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(context)))].map(value => value.toString(16).padStart(2, '0')).join('');
  }
  async list(actor, body) {
    let after;
    if (body.cursor) {
      let parts;
      try { parts = atob(body.cursor.replace(/-/g, '+').replace(/_/g, '/')).split('.'); } catch { return fail(400, 'invalid_pagination'); }
      if (parts.length !== 2 || !REPORT_UUID.test(parts[0]) || !equal(parts[1], await this.cursorMac(actor, body, parts[0]))) return fail(400, 'invalid_pagination');
      after = parts[0];
    }
    const prefix = actor.role === 'admin' ? globalIndex : actor.role === 'owner' ? ownerIndex(actor.device_id) : scopeIndex(actor.scope);
    const rows = await this.storage.list({ prefix, ...(after ? { startAfter: prefix + after } : {}), limit: 101 });
    const reports = []; let last, scanned = 0;
    for (const [key, row] of rows) {
      if (scanned >= 100 || reports.length >= body.limit) break;
      scanned++; last = row.report_id;
      if (row.expires_at <= this.time) { await this.load(row.report_id); continue; }
      if (body.status && body.status !== row.status || body.device_id && body.device_id !== row.device_id) continue;
      reports.push(summary(row));
    }
    return success({ reports, next_cursor: rows.size > scanned && last ? btoa(last + '.' + await this.cursorMac(actor, body, last)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') : null });
  }
}
