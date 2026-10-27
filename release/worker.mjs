// The recommendation, retained APK assets and retired-download policy deploy together.
const protection = {
  'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer', 'X-Frame-Options':'DENY',
  'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
};
const retiredPage = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>安装包已停止提供 · Airtap</title><link rel="stylesheet" href="/style.css"><main class="document"><a href="/">← Airtap</a><h1>请使用当前版本</h1><p>这个旧安装包已停止提供，请前往安装页下载当前版本。</p><p><a href="/install">前往安装页</a></p></main></html>';
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!['GET', 'HEAD'].includes(request.method)) return new Response('Method not allowed', {status:405, headers:{Allow:'GET, HEAD'}});
    const asset = async path => env.ASSETS.fetch(new Request(new URL(path, url), {method:request.method}));
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
    const response = await asset(path);
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
