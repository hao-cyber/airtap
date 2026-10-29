#!/usr/bin/env python3
"""Verify a signed release and build one atomic website + recommendation deployment.

Creates a new directory. Does not upload, mutate recommendations or hold signing keys.
"""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
RETIRED_APKS = ROOT / 'retired-apks.json'
MEDIA_MANIFEST = ROOT / 'usage-video.json'
INSTALL_MEDIA_MANIFEST = ROOT / 'install-video.json'
MAX_MEDIA_BYTES = 20 * 1024 * 1024
SITE_FILES = {'index.html', 'app.js', 'style.css', 'mark.svg', 'install-qr.svg',
              'robots.txt', 'sitemap.xml', 'share.png', 'share.svg'}
HOST = 'airtap.memtrix.dev'
FIELDS = {'schema','application_id','version_name','version_code','apk_sha256','apk_bytes',
          'signer_sha256','min_sdk','target_sdk','debuggable','channel','published_at','download_path','notes'}
APK_IDENTITY = {'application_id','version_name','version_code','apk_sha256','apk_bytes',
                'signer_sha256','min_sdk','target_sdk','debuggable'}

def digest(data):
    return hashlib.sha256(data).hexdigest()

def retirement_metadata(value):
    if not isinstance(value, dict) or set(value) != {'schema', 'sha256'} or value['schema'] != 'airtap-retired-android-artifacts-v1':
        raise ValueError('Unexpected retired APK fields')
    hashes = value['sha256']
    if not isinstance(hashes, list) or any(not isinstance(x, str) or not re.fullmatch('[a-f0-9]{64}', x) for x in hashes):
        raise ValueError('Invalid retired APK digest')
    if hashes != sorted(set(hashes)):
        raise ValueError('Retired APK digests must be sorted and unique')
    return value

def retired_apks(previous=None):
    retired = set(retirement_metadata(json.loads(RETIRED_APKS.read_text()))['sha256'])
    if previous:
        prior = previous / 'site/updates/retired-android.json'
        if prior.exists():
            retired.update(retirement_metadata(json.loads(prior.read_text()))['sha256'])
    return {'schema': 'airtap-retired-android-artifacts-v1', 'sha256': sorted(retired)}

def release_metadata(value):
    if set(value) != FIELDS or value['schema'] != 'airtap-android-update-v1':
        raise ValueError('Unexpected public release fields')
    if value['application_id'] != 'io.airtap.preview' or value['debuggable'] is not False or value['channel'] not in ('beta','stable'):
        raise ValueError('Expected a non-debuggable signed Airtap release')
    for key in ('apk_sha256','signer_sha256'):
        if not re.fullmatch('[a-f0-9]{64}', value[key]): raise ValueError('Invalid digest')
    for key in ('version_code','min_sdk','target_sdk','apk_bytes'):
        if type(value[key]) is not int or value[key] < 1: raise ValueError('Invalid integer')
    if not 34 <= value['min_sdk'] <= value['target_sdk'] <= 100 or value['apk_bytes'] > 25*1024*1024:
        raise ValueError('Release outside supported bounds')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}',value['version_name']): raise ValueError('Invalid version')
    if value['download_path'] != '/download/android': raise ValueError('Download path must be fixed')
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}',value['published_at']): raise ValueError('Invalid publication date')
    if not isinstance(value['notes'],list) or not 1 <= len(value['notes']) <= 10 or any(not isinstance(n,str) or not 1 <= len(n) <= 500 for n in value['notes']):
        raise ValueError('Invalid release notes')
    return value

def verify_apk(path, release, tools):
    data=path.read_bytes()
    if len(data)!=release['apk_bytes'] or digest(data)!=release['apk_sha256']: raise ValueError('APK bytes do not match')
    def run(*args):return subprocess.check_output([str(tools/args[0]),*args[1:]],text=True,stderr=subprocess.PIPE,timeout=60)
    signature=run('apksigner','verify','--verbose','--print-certs',str(path))
    signers=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$',signature,re.M)
    if signers != [release['signer_sha256']]:raise ValueError('Release signer differs')
    manifest=run('aapt2','dump','xmltree',str(path),'--file','AndroidManifest.xml')
    expected={r'package="([^"]+)"':release['application_id'],r':versionName\(0x[0-9a-f]+\)="([^"]+)"':release['version_name'],r':versionCode\(0x[0-9a-f]+\)=(\d+)':str(release['version_code']),r':minSdkVersion\(0x[0-9a-f]+\)=(\d+)':str(release['min_sdk']),r':targetSdkVersion\(0x[0-9a-f]+\)=(\d+)':str(release['target_sdk'])}
    for pattern,value in expected.items():
        if re.findall(pattern,manifest)!=[value]:raise ValueError('Native APK identity mismatch')
    if re.search(r':debuggable\(0x[0-9a-f]+\)=true',manifest):raise ValueError('Debug APK refused')
    return data

def document(title, body):
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+title+' · Airtap</title><link rel="stylesheet" href="/style.css"><main class="document"><a href="/">← Airtap</a><h1>'+title+'</h1>'+body+'</main></html>').encode()

def reviewed_media(media_file, kind='usage'):
    """Only explicit, hash-pinned public footage enters a frozen website."""
    if kind not in ('usage', 'install'):
        raise ValueError('Unknown public media kind')
    manifest = MEDIA_MANIFEST if kind == 'usage' else INSTALL_MEDIA_MANIFEST
    m = json.loads(manifest.read_text())
    fields = {'schema', 'filename', 'sha256', 'bytes', 'duration_seconds',
              'version_code', 'title', 'description', 'poster'}
    if set(m) != fields or m['schema'] != 'airtap-reviewed-' + kind + '-video-v1':
        raise ValueError('Unexpected public media manifest')
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,100}\.mp4', m['filename']):
        raise ValueError('Invalid public video filename')
    if (not re.fullmatch('[a-f0-9]{64}', m['sha256']) or type(m['bytes']) is not int
            or not 1 <= m['bytes'] <= MAX_MEDIA_BYTES or type(m['duration_seconds']) is not int
            or not 1 <= m['duration_seconds'] <= 600 or type(m['version_code']) is not int
            or m['version_code'] < 1):
        raise ValueError('Invalid public video identity')
    if any(not isinstance(m[k], str) or not 1 <= len(m[k]) <= limit
           for k, limit in [('title', 120), ('description', 1000)]):
        raise ValueError('Invalid public video text')
    p = m['poster']
    if (not isinstance(p, dict) or set(p) != {'filename', 'sha256', 'bytes'}
            or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,100}\.jpg', p['filename'])
            or not re.fullmatch('[a-f0-9]{64}', p['sha256'])
            or type(p['bytes']) is not int or not 1 <= p['bytes'] <= 4 * 1024 * 1024):
        raise ValueError('Invalid public poster identity')
    if media_file is None:
        return {}, '', p['filename']
    def read_exact(path, identity):
        if path.is_symlink() or not path.is_file() or path.stat().st_size != identity['bytes']:
            raise ValueError('Reviewed media file missing, symlinked or wrong size')
        with path.open('rb') as stream:
            data = stream.read(identity['bytes'] + 1)
        if len(data) != identity['bytes'] or digest(data) != identity['sha256']:
            raise ValueError('Reviewed media bytes differ')
        return data
    video = read_exact(media_file, m)
    poster = read_exact(ROOT / 'site' / p['filename'], p)
    src = '/media/' + m['filename']
    player_id = 'demo' if kind == 'usage' else 'install-demo'
    block = ('<figure class="usage-demo" id="' + player_id + '"><div class="demo-player">'
             '<video controls playsinline preload="none" width="1080" height="1920" '
             'poster="/' + p['filename'] + '" aria-labelledby="' + player_id + '-title" aria-describedby="' + player_id + '-description">'
             '<source src="' + src + '" type="video/mp4">'
             '<p>浏览器无法播放此视频，可<a href="' + src + '">打开视频文件</a>。</p></video></div>'
             '<figcaption><h3 id="' + player_id + '-title">' + html.escape(m['title']) + '</h3>'
             '<p id="' + player_id + '-description">' + html.escape(m['description']) + '</p>'
             '<a class="text-link" href="' + src + '">单独打开视频 <span aria-hidden="true">↗</span></a>'
             '</figcaption></figure>')
    return {'media/' + m['filename']: video, p['filename']: poster,
            'media/' + kind + '-video.json': (json.dumps(m, ensure_ascii=False, indent=2) + '\n').encode()}, block, p['filename']

def build(apk, metadata, output, tools, previous=None, media_file=None, install_media_file=None):
    r=release_metadata(json.loads(metadata.read_text()))
    data=verify_apk(apk,r,tools)
    if output.exists():raise ValueError('Use a new output directory; never overwrite a frozen build')
    retired=retired_apks(previous)
    if r['apk_sha256'] in retired['sha256']:
        raise ValueError('Recommended APK has been retired; publish a supported release')
    old=None
    deployment_kind='initial'
    if previous:
        old=release_metadata(json.loads((previous/'site/updates/android.json').read_text()))
        if r['application_id']!=old['application_id'] or r['signer_sha256']!=old['signer_sha256']:
            raise ValueError('Unexpected application or signer transition')
        if r['version_code']<old['version_code']:raise ValueError('Android versionCode cannot decrease')
        if r['version_code']==old['version_code']:
            if any(r[key]!=old[key] for key in APK_IDENTITY):
                raise ValueError('Same versionCode must retain the identical APK and native identity')
            deployment_kind='site_refresh'
        else:
            deployment_kind='android_update'
        prior_apk=previous/'site/downloads'/(old['apk_sha256']+'.apk')
        if not prior_apk.is_file() or prior_apk.stat().st_size!=old['apk_bytes'] or digest(prior_apk.read_bytes())!=old['apk_sha256']:
            raise ValueError('Previous recommended APK is missing or changed')
    media_files, demo, poster_name = reviewed_media(media_file)
    install_files, install_demo, install_poster = reviewed_media(install_media_file, 'install')
    if media_files.keys() & install_files.keys():
        raise ValueError('Public media identities collide')
    # A stray recording or symlink in the source directory is never a public asset.
    entries = list((ROOT / 'site').iterdir())
    if (any(p.is_symlink() or not p.is_file() or p.name not in SITE_FILES | {poster_name, install_poster} for p in entries)
            or not SITE_FILES <= {p.name for p in entries}):
        raise ValueError('Unexpected, missing or symlinked site asset')
    files={p.name:p.read_bytes() for p in entries if p.name in SITE_FILES}
    files.update(media_files)
    files.update(install_files)
    substitutions={'APK_SIZE':f"{r['apk_bytes']/1048576:.1f}",'VERSION':r['version_name'],'DATE':r['published_at'],'SHA256':r['apk_sha256'],'SIGNER':r['signer_sha256']}
    page=files['index.html'].decode().replace('{{USAGE_DEMO}}', demo).replace('{{INSTALL_DEMO}}', install_demo)
    for key,value in substitutions.items():page=page.replace('{{'+key+'}}',html.escape(value))
    if '{{' in page:raise ValueError('Unfilled page variable')
    files['index.html']=page.encode()
    files['updates/android.json']=(json.dumps(r,ensure_ascii=False,indent=2)+'\n').encode()
    files['updates/retired-android.json']=(json.dumps(retired,indent=2)+'\n').encode()
    files['downloads/'+r['apk_sha256']+'.apk']=data
    if previous:
        for p in (previous/'site/downloads').glob('*.apk'):
            d=p.read_bytes()
            if p.name!=digest(d)+'.apk':raise ValueError('Prior immutable APK was changed')
            if digest(d) not in retired['sha256']:
                files['downloads/'+p.name]=d
    files['release-notes.html']=document('更新说明','<p>'+r['published_at']+' · '+html.escape(r['version_name'])+' · 公开测试</p><ul>'+''.join('<li>'+html.escape(x)+'</li>' for x in r['notes'])+'</ul><p>各项能力的验证范围以对应发布记录为准。此前机型和个人 AI 的验收仅覆盖其原版本；新增能力需要单独验证，不代表所有机型或 AI 均已支持。</p><p><a href="/download/android">下载推荐安装包</a></p>')
    files['privacy.html']=document('数据与控制','<p>手机和你的个人 AI 通过 Airtap 公共连接服务通信。屏幕、界面文字和操作指令在任务执行期间经过服务转发；默认不持久化截图或操作内容，目前没有应用层端到端加密。网络托管提供者也参与传输。</p><p>配对凭据与授权状态用于维持连接。不要公开分享邀请或 AI 的连接配置。你的个人 AI 如何保存任务内容，还取决于你使用的 AI 产品。</p><p>更新检查只请求公开的版本资料，不发送手机身份、配对凭据或屏幕内容。网站没有添加分析脚本或广告跟踪。</p><p>可随时在 App 或通知中停止并解除连接。旧 AI 不会自动恢复权限；下一次连接需要新的邀请和机主确认。手机系统自身的权限、锁屏和应用隔离仍然生效。</p><p>你可以授权自己的个人 AI 提交技术故障反馈。由 AI 选择必要信息并去除私人内容，Airtap 不自行收集截图、聊天或操作记录。报告私有保存，维护人员可以查看并回复，不会自动公开到 GitHub。AI 负责脱敏，服务不保证识别或移除报告中所有私人信息。</p><p>报告在 Airtap 在线存储中自提交起保留 30 天，到期或你主动删除后清除在线正文。已配对 AI 可查询、补充和删除本连接提交的报告；你可在 App 的“设置 → 问题反馈”查看及删除本设备的报告，解除 AI 连接后仍可管理。主动删除后，在线存储仅保留无正文占位到原到期时间，防止迟到重试恢复报告。未配对反馈通过私有回执管理，请让 AI 保存回执。</p><p>Cloudflare SQLite Durable Objects 另提供<a href="https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/#pitr-point-in-time-recovery-api">最近 30 天的数据库历史恢复</a>，删除前的内容仍可能存在于该平台恢复窗口内。</p><p>反馈与手机操作相互独立。请勿在报告中放入邀请、密码或连接凭据；有用的脱敏描述即可，不需要 GitHub 账号。本页描述当前公开测试实现。</p>')
    output.mkdir(parents=True)
    for name,content in files.items():
        p=output/'site'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(content)
    shutil.copyfile(ROOT/'worker.mjs',output/'worker.mjs')
    config={'name':'airtap-install','main':'worker.mjs','compatibility_date':'2026-10-01','workers_dev':False,'preview_urls':False,'observability':{'enabled':False},'routes':[{'pattern':HOST,'custom_domain':True}],'assets':{'directory':'./site','binding':'ASSETS','run_worker_first':True}}
    (output/'wrangler.json').write_text(json.dumps(config,indent=2)+'\n')
    inventory={str(p.relative_to(output)):digest(p.read_bytes()) for p in output.rglob('*') if p.is_file()}
    (output/'build-manifest.json').write_text(json.dumps({'schema':'airtap-release-site-build-v1','release':r,'files':inventory,'previous_version_code':old['version_code'] if old else None,'deployment_kind':deployment_kind},ensure_ascii=False,indent=2)+'\n')
    return {'output':str(output),'apk_sha256':r['apk_sha256'],'files':len(inventory),'deployment_kind':deployment_kind,'deployed':False}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('apk','metadata','output','tools'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--previous',type=Path)
    p.add_argument('--media-file', type=Path, help='Explicit reviewed MP4 matching usage-video.json; omit to build without the player')
    p.add_argument('--install-media-file', type=Path, help='Explicit reviewed MP4 matching install-video.json; omit to build without the installation guide player')
    print(json.dumps(build(**vars(p.parse_args())),ensure_ascii=False))
