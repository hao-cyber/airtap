// The recommendation, retained APK assets and retired-download policy deploy together.
const protection = {
  'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer', 'X-Frame-Options':'DENY',
  'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
};
const retiredPage = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>安装包已停止提供 · Airtap</title><link rel="stylesheet" href="/style.css"><main class="document"><a href="/">← Airtap</a><h1>请使用当前版本</h1><p>这个旧安装包已停止提供，请前往安装页下载当前版本。</p><p><a href="/install">前往安装页</a></p></main></html>';
const videoPath = path => /^\/media\/[a-z0-9-]+\.mp4$/.test(path);
function fixedBody(body, size) {
  // Workers derives Content-Length from the stream type, not a manually set
  // header. Node's test runtime has no FixedLengthStream.
  if (typeof FixedLengthStream === 'undefined') return body;
  const stream = new FixedLengthStream(size);
  body.pipeTo(stream.writable).catch(() => {}); // Failure also errors the readable side.
  return stream.readable;
}
async function videoResponse(request, response, env, path) {
  if (response.status !== 200) return response;
  const headers = new Headers(response.headers);
  let size = Number(headers.get('Content-Length'));
  if (!size) {
    // ASSETS may omit length even for GET. Only these two independently reviewed
    // manifests, deployed atomically with their bytes, can provide it.
    for (const kind of ['usage', 'install']) {
      try {
        const metadata = await env.ASSETS.fetch(new Request(new URL('/media/' + kind + '-video.json', request.url)));
        const media = await metadata.json();
        if (metadata.ok && media.schema === 'airtap-reviewed-' + kind + '-video-v1'
            && '/media/' + media.filename === path && validVideoSize(media.bytes)) {
          size = media.bytes;
          break;
        }
      } catch { /* An omitted guide must not prevent the other film playing. */ }
    }
    if (!size) {
      await response.body?.cancel();
      return new Response('Video temporarily unavailable', {status:502});
    }
  }
  if (!validVideoSize(size) || !response.body) {
    await response.body?.cancel();
    return new Response('Video temporarily unavailable', {status:502});
  }
  headers.set('Accept-Ranges', 'bytes');
  headers.set('Content-Length', String(size));
  if (request.method === 'HEAD') {
    // The HTTP runtime suppresses this body for HEAD while retaining its known
    // length; a null body would cause Workers to remove Content-Length.
    return new Response(fixedBody(response.body, size), {headers});
  }
  const value = request.headers.get('Range');
  const validator = request.headers.get('If-Range');
  const matches = !validator || (validator === headers.get('ETag') && !validator.startsWith('W/'))
    || validator === headers.get('Last-Modified');
  const match = matches && value && /^bytes=(\d*)-(\d*)$/.exec(value);
  // Multiple ranges and unknown range units may be ignored with a full 200.
  if (!match || (!match[1] && !match[2])) return new Response(fixedBody(response.body, size), {headers});
  const start = match[1] ? Number(match[1]) : Math.max(0, size - Number(match[2]));
  const end = match[1] && match[2] ? Math.min(size - 1, Number(match[2])) : size - 1;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= size || end < start) {
    await response.body?.cancel();
    headers.set('Content-Range', `bytes */${size}`);
    headers.set('Content-Length', '0');
    return new Response(null, {status:416, headers});
  }
  headers.set('Content-Range', `bytes ${start}-${end}/${size}`);
  headers.set('Content-Length', String(end - start + 1));
  const reader = response.body.getReader();
  let offset = 0;
  const body = new ReadableStream({
    async pull(controller) {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) { controller.error(new Error('Video asset ended early')); return; }
        const begin = offset; offset += chunk.value.length;
        if (offset <= start) continue;
        controller.enqueue(chunk.value.subarray(Math.max(0, start - begin), Math.min(chunk.value.length, end + 1 - begin)));
        if (offset > end) { controller.close(); await reader.cancel(); }
        return;
      }
    },
    cancel(reason) { return reader.cancel(reason); },
  });
  return new Response(fixedBody(body, end - start + 1), {status:206, headers});
}
const validVideoSize = value => Number.isSafeInteger(value) && value >= 1 && value <= 20 * 1024 * 1024;
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!['GET', 'HEAD'].includes(request.method)) return new Response('Method not allowed', {status:405, headers:{Allow:'GET, HEAD'}});
    const asset = async path => {
      const headers = new Headers();
      // Preserve native video seeking without forwarding cookies or credentials.
      if (videoPath(path) && request.method === 'GET') {
        for (const name of ['Range', 'If-Range']) {
          if (request.headers.has(name)) headers.set(name, request.headers.get(name));
        }
      }
      // A known-length GET body lets the HTTP runtime retain length for HEAD.
      return env.ASSETS.fetch(new Request(new URL(path, url), {
        method: videoPath(path) && request.method === 'HEAD' ? 'GET' : request.method, headers,
      }));
    };
    const apkMatch = /^\/downloads\/([a-f0-9]{64})\.apk$/.exec(url.pathname);
    if (apkMatch) {
      let retired;
      try {
        const policy = await env.ASSETS.fetch(new Request(new URL('/updates/retired-android.json', url)));
        if (!policy.ok) throw new Error('Missing retirement policy');
        retired = await policy.json();
        if (retired.schema !== 'airtap-retired-android-artifacts-v1' || !Array.isArray(retired.sha256) || retired.sha256.some(x => typeof x !== 'string' || !/^[a-f0-9]{64}$/.test(x))) throw new Error('Invalid retirement policy');
      } catch {
        return new Response(request.method === 'HEAD' ? null : 'Download temporarily unavailable', {status:503, headers:{...protection,'Cache-Control':'no-store'}});
      }
      if (retired.sha256.includes(apkMatch[1])) {
        return new Response(request.method === 'HEAD' ? null : retiredPage, {status:410, headers:{...protection,'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store, no-transform','Link':'</install>; rel="alternate"'}});
      }
    }
    if (url.pathname === '/download/android') {
      const current = await env.ASSETS.fetch(new Request(new URL('/updates/android.json', url)));
      if (!current.ok) return new Response('Download temporarily unavailable', {status:503, headers:{'Cache-Control':'no-store'}});
      const release = await current.json();
      if (!/^[a-f0-9]{64}$/.test(release.apk_sha256)) return new Response('Invalid release', {status:503});
      return new Response(null, {status:302, headers:{Location:'/downloads/' + release.apk_sha256 + '.apk', 'Cache-Control':'no-store'}});
    }
    const pages = {'/':'/index.html','/install':'/index.html','/help':'/index.html','/privacy':'/privacy.html','/release-notes':'/release-notes.html'};
    const path = pages[url.pathname.replace(/\/$/, '') || '/'] || url.pathname;
    let response = await asset(path);
    // Some ASSETS implementations ignore Range. Slice their stream only when
    // needed; never buffer the whole video or apply this behavior to APKs.
    if (videoPath(path)) response = await videoResponse(request, response, env, path);
    const headers = new Headers(response.headers);
    for (const [key, value] of Object.entries(protection)) headers.set(key, value);
    // Keep public pages fresh and prevent the CDN from injecting analytics scripts.
    headers.set('Cache-Control', /^\/downloads\/[a-f0-9]{64}\.apk$/.test(path) && response.ok ? 'public, max-age=31536000, immutable' : 'public, no-cache, no-transform');
    if (path === '/updates/android.json' || path === '/updates/retired-android.json') headers.set('Cache-Control', 'no-store');
    if (path.endsWith('.apk') && response.ok) {
      headers.set('Content-Type','application/vnd.android.package-archive');
      headers.set('Content-Disposition','attachment; filename="Airtap.apk"');
    }
    return new Response(response.body,{status:response.status,headers});
  }
};
