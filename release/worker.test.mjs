import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';
const hash='a'.repeat(64);
const retiredHash='b'.repeat(64);
const assets={'/updates/android.json':JSON.stringify({apk_sha256:hash}),'/updates/retired-android.json':JSON.stringify({schema:'airtap-retired-android-artifacts-v1',sha256:[retiredHash]}),'/index.html':'home','/privacy.html':'privacy','/release-notes.html':'notes',[`/downloads/${hash}.apk`]:'APK'};
const env={ASSETS:{fetch:async request=>{const p=new URL(request.url).pathname;return new Response(request.method==='HEAD'?null:assets[p]||'missing',{status:p in assets?200:404});}}};
const get=(path,method='GET')=>worker.fetch(new Request('https://airtap.test'+path,{method}),env);
test('recommendation redirects temporarily to an immutable APK with no stale cache',async()=>{const r=await get('/download/android');assert.equal(r.status,302);assert.equal(r.headers.get('Location'),`/downloads/${hash}.apk`);assert.equal(r.headers.get('Cache-Control'),'no-store');});
test('home install help are the same stable entry and documents resolve',async()=>{for(const p of ['/','/install','/install/','/help'])assert.equal(await(await get(p)).text(),'home');assert.equal(await(await get('/privacy')).text(),'privacy');assert.equal(await(await get('/release-notes')).text(),'notes');});
test('immutable APK has installer MIME, friendly filename, cache and HEAD behavior',async()=>{const r=await get(`/downloads/${hash}.apk`);assert.equal(r.headers.get('Content-Type'),'application/vnd.android.package-archive');assert.match(r.headers.get('Cache-Control'),/immutable/);assert.equal(r.headers.get('Content-Disposition'),'attachment; filename="Airtap.apk"');assert.equal(await(await get(`/downloads/${hash}.apk`,'HEAD')).text(),'');});
test('updates cannot be cached and private or unknown paths do not fall back to homepage',async()=>{assert.equal((await get('/updates/android.json')).headers.get('Cache-Control'),'no-store');assert.equal((await get('/secrets.json')).status,404);assert.equal((await get('/download/android','POST')).status,405);});
test('bad recommendation fails closed',async()=>{const r=await worker.fetch(new Request('https://airtap.test/download/android'),{ASSETS:{fetch:async()=>new Response('{"apk_sha256":"../secret"}')}});assert.equal(r.status,503);});
test('public pages revalidate and forbid CDN script injection',async()=>{for(const p of ['/','/help','/privacy','/release-notes']){const r=await get(p);assert.equal(r.headers.get('Cache-Control'),'public, no-cache, no-transform');assert.match(r.headers.get('Content-Security-Policy'),/script-src 'self'/);}});
test('retired APK is an explicit noncached 410 page for GET and HEAD, including query and Range requests',async()=>{
  for(const method of ['GET','HEAD']) {
    let oldAssetRead=false;
    const guarded={ASSETS:{fetch:async request=>{
      if(new URL(request.url).pathname===`/downloads/${retiredHash}.apk`){oldAssetRead=true;return new Response('OLD APK');}
      return env.ASSETS.fetch(request);
    }}};
    const r=await worker.fetch(new Request(`https://airtap.test/downloads/${retiredHash}.apk?download=1`,{method,headers:{Range:'bytes=0-100'}}),guarded);
    assert.equal(r.status,410);assert.equal(oldAssetRead,false);
    assert.equal(r.headers.get('Cache-Control'),'no-store, no-transform');
    assert.equal(r.headers.get('Content-Type'),'text/html; charset=utf-8');
    assert.equal(r.headers.get('Content-Disposition'),null);assert.equal(r.headers.get('Location'),null);
    assert.equal(r.headers.get('Link'),'</install>; rel="alternate"');
    assert.equal(r.headers.get('X-Content-Type-Options'),'nosniff');
    const body=await r.text();if(method==='HEAD')assert.equal(body,'');else assert.match(body,/href="\/install"/);
  }
});
test('unknown APK remains 404 and retirement registry cannot be cached',async()=>{
  assert.equal((await get(`/downloads/${'c'.repeat(64)}.apk`)).status,404);
  assert.equal((await get('/updates/retired-android.json')).headers.get('Cache-Control'),'no-store');
});
test('missing or malformed retirement policy fails closed before reading any APK',async()=>{
  for(const response of [()=>new Response('missing',{status:404}),()=>new Response('not json'),()=>new Response('{"schema":"wrong","sha256":[]}'),()=>new Response('{"schema":"airtap-retired-android-artifacts-v1","sha256":["bad"]}')]) {
    let apkRead=false;
    const r=await worker.fetch(new Request(`https://airtap.test/downloads/${hash}.apk`),{ASSETS:{fetch:async req=>{
      if(new URL(req.url).pathname==='/updates/retired-android.json')return response();
      apkRead=true;return new Response('APK');
    }}});
    assert.equal(r.status,503);assert.equal(apkRead,false);assert.equal(r.headers.get('Cache-Control'),'no-store');
  }
});
