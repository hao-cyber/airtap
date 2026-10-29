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
test('native video ranges preserve headers without forwarding credentials',async()=>{
  const r=await worker.fetch(new Request('https://airtap.test/media/usage-code39.mp4',{
    headers:{Range:'bytes=2-4','If-Range':'"video-hash"',Authorization:'private',Cookie:'private'}
  }),{ASSETS:{fetch:async req=>{
    assert.equal(req.headers.get('Range'),'bytes=2-4');
    assert.equal(req.headers.get('If-Range'),'"video-hash"');
    assert.equal(req.headers.get('Authorization'),null);assert.equal(req.headers.get('Cookie'),null);
    return new Response('234',{status:206,headers:{'Content-Type':'video/mp4','Content-Length':'3','Content-Range':'bytes 2-4/10'}});
  }}});
  assert.equal(r.status,206);assert.equal(r.headers.get('Content-Range'),'bytes 2-4/10');
  assert.equal(await r.text(),'234');
});
test('video fallback serves streamed ranges and HEAD when ASSETS ignores Range',async()=>{
  const videoEnv={ASSETS:{fetch:async req=>{
    assert.equal(req.method,'GET');
    return new Response('0123456789',{headers:{'Content-Type':'video/mp4','Content-Length':'10','ETag':'"video-hash"'}});
  }}};
  for(const [method,range,ifRange,status,body,contentRange] of [
    ['HEAD',null,null,200,'',null],['GET','bytes=2-4',null,206,'234','bytes 2-4/10'],
    ['GET','bytes=7-',null,206,'789','bytes 7-9/10'],['GET','bytes=-3',null,206,'789','bytes 7-9/10'],
    ['GET','bytes=10-',null,416,'','bytes */10'],['GET','bytes=-0',null,416,'','bytes */10'],
    ['GET','bytes=2-4','"old-hash"',200,'0123456789',null],
    ['GET','bytes=2-4','"video-hash"',206,'234','bytes 2-4/10'],
  ]) {
    const headers={};if(range)headers.Range=range;if(ifRange)headers['If-Range']=ifRange;
    const r=await worker.fetch(new Request('https://airtap.test/media/usage-code39.mp4',{method,headers}),videoEnv);
    assert.equal(r.status,status);assert.equal(r.headers.get('Content-Range'),contentRange);
    assert.equal(r.headers.get('Content-Length'),String(method==='HEAD'?10:body.length));
    // The actual HTTP server strips the known-length response body for HEAD.
    assert.equal(await r.text(),method==='HEAD'?'0123456789':body);
  }
});
test('missing ASSETS length uses only a bounded matching public media identity',async()=>{
  const media={schema:'airtap-reviewed-usage-video-v1',filename:'usage-code39.mp4',bytes:10};
  for(const [change,expected] of [[{},200],[{filename:'other.mp4'},502],[{schema:'wrong'},502],[{bytes:21*1024*1024},502]]) {
    const env={ASSETS:{fetch:async req=>new URL(req.url).pathname==='/media/usage-video.json'
      ? new Response(JSON.stringify({...media,...change}))
      : new Response('0123456789',{headers:{'Content-Type':'video/mp4'}})}};
    const r=await worker.fetch(new Request('https://airtap.test/media/usage-code39.mp4'),env);
    assert.equal(r.status,expected);
    if(expected===200){assert.equal(r.headers.get('Content-Length'),'10');assert.equal(await r.text(),'0123456789');}
  }
});
test('two films use their own bounded manifest for HEAD and streamed Range',async()=>{
  const films={usage:'0123456789',install:'abcdefghijklmnop'};
  for(const kind of ['usage','install']) for(const method of ['GET','HEAD']) {
    const lookedUp=[];
    const env={ASSETS:{fetch:async req=>{
      const path=new URL(req.url).pathname;
      for(const key of ['usage','install']) if(path===`/media/${key}-video.json`) {
        lookedUp.push(key);
        return new Response(JSON.stringify({schema:`airtap-reviewed-${key}-video-v1`,filename:`${key}-code39.mp4`,bytes:films[key].length}));
      }
      return new Response(films[kind],{headers:{'Content-Type':'video/mp4'}});
    }}};
    const r=await worker.fetch(new Request(`https://airtap.test/media/${kind}-code39.mp4`,{method,headers:{Range:'bytes=2-4'}}),env);
    assert.equal(r.status,method==='HEAD'?200:206);
    assert.equal(r.headers.get('Content-Length'),String(method==='HEAD'?films[kind].length:3));
    assert.equal(r.headers.get('Content-Range'),method==='HEAD'?null:`bytes 2-4/${films[kind].length}`);
    assert.equal(await r.text(),method==='HEAD'?films[kind]:films[kind].slice(2,5));
    assert.deepEqual(lookedUp,kind==='usage'?['usage']:['usage','install']);
  }
});
test('installation film survives omitted usage media but rejects wrong or unbounded guide identity',async()=>{
  for(const [change,status] of [[{},206],[{schema:'airtap-reviewed-usage-video-v1'},502],[{filename:'usage-code39.mp4'},502],[{bytes:21*1024*1024},502]]) {
    const env={ASSETS:{fetch:async req=>{
      const path=new URL(req.url).pathname;
      if(path==='/media/usage-video.json')return new Response('not included',{status:404});
      if(path==='/media/install-video.json')return new Response(JSON.stringify({schema:'airtap-reviewed-install-video-v1',filename:'install-code39.mp4',bytes:10,...change}));
      return new Response('0123456789',{headers:{'Content-Type':'video/mp4'}});
    }}};
    const r=await worker.fetch(new Request('https://airtap.test/media/install-code39.mp4',{headers:{Range:'bytes=2-4'}}),env);
    assert.equal(r.status,status);
    if(status===206)assert.equal(await r.text(),'234');
  }
});
