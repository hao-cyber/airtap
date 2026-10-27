#!/usr/bin/env python3
"""Build/verify a static, public-by-link APK delivery directory. Never deploys."""
import argparse
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import zipfile

MAX_APK = 25 * 1024 * 1024
HEX = re.compile(r'[0-9a-f]{64}')
SCHEMA = 'airtap-download-release-v1'
SOURCE_SCHEMA = 'airtap-download-release-v2'
MAX_SOURCE_ZIP = 25 * 1024 * 1024
MAX_SOURCE_FILES = 4096
FIELDS = {'schema', 'application_id', 'version_name', 'version_code', 'apk_bytes',
          'apk_sha256', 'signer_sha256', 'min_sdk', 'target_sdk', 'distribution', 'debuggable'}
MANIFEST_SCOPE = 'Static download assets only. No device credential, control route or Worker script. URL possession is not authentication.'


class Refused(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode()


def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise Refused('Duplicate JSON key')
        result[key] = value
    return result


def read_regular(path, limit):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= limit:
            raise Refused('Input must be a bounded, nonempty regular file')
        data = stream.read(limit + 1)
        if len(data) != info.st_size:
            raise Refused('Input changed or exceeded its bound')
        return data


def metadata(data):
    value = json.loads(data.decode('utf-8'), object_pairs_hook=pairs)
    if not isinstance(value, dict) or set(value) != (FIELDS | {'source'} if value.get('schema') == SOURCE_SCHEMA else FIELDS):
        raise Refused('Metadata fields do not match the explicit public schema')
    if value['schema'] not in (SCHEMA, SOURCE_SCHEMA) or value['distribution'] != 'invited-development-preview' or value['debuggable'] is not True:
        raise Refused('Only explicitly labeled development previews are supported')
    if not isinstance(value['application_id'], str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+', value['application_id']) or len(value['application_id']) > 200:
        raise Refused('Invalid application ID')
    if not isinstance(value['version_name'], str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', value['version_name']):
        raise Refused('Invalid version name')
    for key in ('apk_sha256', 'signer_sha256'):
        if not isinstance(value[key], str) or not HEX.fullmatch(value[key]):
            raise Refused('Invalid digest')
    for key in ('version_code', 'apk_bytes', 'min_sdk', 'target_sdk'):
        if type(value[key]) is not int:
            raise Refused('Numeric release fields must be integers')
    if not 1 <= value['version_code'] < 2**31 or not 1 <= value['apk_bytes'] <= MAX_APK or not 34 <= value['min_sdk'] <= value['target_sdk'] <= 100:
        raise Refused('Release bounds exceeded')
    if value['schema'] == SOURCE_SCHEMA:
        source = value['source']
        if not isinstance(source, dict) or set(source) != {'bytes', 'sha256', 'archive_root'}:
            raise Refused('Unexpected public source metadata')
        if type(source['bytes']) is not int or not 1 <= source['bytes'] <= MAX_SOURCE_ZIP or not isinstance(source['sha256'], str) or not HEX.fullmatch(source['sha256']):
            raise Refused('Invalid source bound or digest')
        if source['archive_root'] != 'airtap-source-' + value['version_name']:
            raise Refused('Source root must match the fixed release version')
    return value


def source_archive(data, release):
    """Check this project's already-reviewed source archive; never execute it."""
    import package_source as source_package
    source = release['source']
    if len(data) != source['bytes'] or digest(data) != source['sha256']:
        raise Refused('Source bytes differ from fixed release metadata')
    root = source['archive_root']
    try:
        paths = {}
        seen = set()
        history_notices = set()
        total = 0
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if not 1 <= len(entries) <= MAX_SOURCE_FILES:
                raise Refused('Source file count exceeded')
            for entry in entries:
                name = source_package.safe_path(entry.filename)
                if not name.startswith(root + '/') or name.casefold() in seen:
                    raise Refused('Source root or unique path mismatch')
                seen.add(name.casefold())
                relative = name[len(root) + 1:]
                # Applies the source distributor's private-path and extension bans.
                validation_path = relative
                if relative.startswith('experiments/') and relative.endswith('.json') and source_package.omittable(relative):
                    # The source generator includes explicit omission notices at
                    # some historical JSON paths, never the original records.
                    # Other private components/suffixes remain forbidden.
                    history_notices.add(relative)
                    validation_path = 'omitted-history/' + relative[len('experiments/'):]
                source_package.validate_entry({'path': validation_path, 'kind': 'source'})
                mode = entry.external_attr >> 16
                expected_mode = 0o755 if relative == 'apps/android/gradlew' else 0o644
                if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != expected_mode or entry.is_dir() or entry.flag_bits & 1 or entry.compress_type != zipfile.ZIP_STORED or entry.date_time != source_package.FIXED_TIME:
                    raise Refused('Unexpected source archive metadata')
                total += entry.file_size
                if entry.file_size > source_package.MAX_FILE_BYTES or total > source_package.MAX_TOTAL_BYTES:
                    raise Refused('Expanded source size exceeded')
                paths[relative] = archive.read(entry)
        if source_package.zip_bytes(paths, root) != data:
            raise Refused('Source ZIP is not the canonical distributor encoding')
        if not {'SOURCE-MANIFEST.json', 'SHA256SUMS', 'apps/android/app/build.gradle', 'cli/airtap.py'} <= paths.keys():
            raise Refused('Required source entries missing')
        manifest = json.loads(paths['SOURCE-MANIFEST.json'], object_pairs_hook=pairs)
        manifest_fields = {'schema', 'version', 'archive_root', 'scope', 'source_files_are_byte_identical',
                           'historical_hash_notice', 'manifest_coverage', 'entries', 'omitted_originals', 'verification'}
        if not isinstance(manifest, dict) or set(manifest) != manifest_fields or manifest.get('schema') != 'airtap-source-manifest-v1' or manifest.get('archive_root') != root or manifest.get('version') != release['version_name'] or manifest.get('source_files_are_byte_identical') is not True:
            raise Refused('Unexpected source manifest identity')
        rows = manifest.get('entries')
        if not isinstance(rows, list) or len(rows) != len(paths) - 2:
            raise Refused('Source manifest inventory mismatch')
        covered = set()
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get('path'), str):
                raise Refused('Invalid source manifest entry')
            name = row['path']
            if name in covered or name in {'SOURCE-MANIFEST.json', 'SHA256SUMS'} or name not in paths:
                raise Refused('Source manifest duplicate or unknown path')
            covered.add(name)
            if row.get('sha256') != digest(paths[name]) or type(row.get('bytes')) is not int or row['bytes'] != len(paths[name]) or row.get('mode') != ('0755' if name == 'apps/android/gradlew' else '0644'):
                raise Refused('Source manifest byte or mode mismatch')
            kind = row.get('kind')
            if kind not in {'source', 'document', 'result', 'binary', 'omission', 'generated_document'}:
                raise Refused('Unexpected source manifest kind')
            row_fields = {'path', 'kind', 'original_sha256', 'original_bytes', 'status', 'transformations', 'mode', 'sha256', 'bytes'}
            if set(row) != row_fields | ({'binary_review'} if kind == 'binary' else set()):
                raise Refused('Unexpected source manifest entry fields')
            if kind == 'source' and (row.get('original_sha256') != row['sha256'] or row.get('original_bytes') != row['bytes']):
                raise Refused('Source bytes were transformed')
            if kind == 'binary' and (not isinstance(row.get('binary_review'), str) or not row['binary_review'] or
                    not (name == 'apps/android/gradle/wrapper/gradle-wrapper.jar' or name.startswith('docs/results/') and Path(name).suffix.lower() in {'.jpg', '.png'})):
                raise Refused('Source binary lacks review attribution or an expected media path')
            if name in history_notices:
                notice = json.loads(paths[name], object_pairs_hook=pairs)
                expected_notice = {'schema': 'airtap-source-omission-v1', 'status': 'original_record_not_included',
                    'path': name, 'reason': source_package.omission_reason(name),
                    'original_sha256': row.get('original_sha256'),
                    'notice': 'This is an omission notice, not the original result. No original response, pairing data or raw log is included. Historical conclusions remain in the accompanying Markdown reports.'}
                if kind != 'omission' or notice != expected_notice or not isinstance(notice.get('original_sha256'), str) or not HEX.fullmatch(notice['original_sha256']):
                    raise Refused('Historical path must contain only the exact omission notice')
            scan_kind = 'result' if kind in {'result', 'omission'} else 'document' if kind in {'document', 'generated_document'} else kind
            if scan_kind == 'result':
                json.loads(paths[name], object_pairs_hook=pairs)
            if source_package.scan(name, paths[name], scan_kind):
                raise Refused('Source privacy scan refused content')
        if covered != paths.keys() - {'SOURCE-MANIFEST.json', 'SHA256SUMS'}:
            raise Refused('Source manifest does not cover every payload file')
        if source_package.scan('SOURCE-MANIFEST.json', paths['SOURCE-MANIFEST.json'], 'result'):
            raise Refused('Source manifest privacy scan refused content')
        checksums = {}
        for line in paths['SHA256SUMS'].decode('utf-8').splitlines(keepends=True):
            match = re.fullmatch(r'([0-9a-f]{64})  ([^\r\n]+)\n', line)
            if not match or match[2] in checksums:
                raise Refused('Source checksum entry is malformed or duplicate')
            checksums[match[2]] = match[1]
        if set(checksums) != paths.keys() - {'SHA256SUMS'} or any(checksums[name] != digest(paths[name]) for name in checksums):
            raise Refused('Source checksums do not exactly cover archive bytes')
        gradle = paths['apps/android/app/build.gradle'].decode('utf-8')
        if re.findall(r'\bversionName\s+["\']([^"\']+)', gradle) != [release['version_name']] or re.findall(r'\bversionCode\s+(\d+)', gradle) != [str(release['version_code'])]:
            raise Refused('Source Android version differs from APK metadata')
        cli_versions = re.findall(r'^VERSION\s*=\s*["\']([^"\']+)["\']\s*$', paths['cli/airtap.py'].decode('utf-8'), re.M)
        if cli_versions != [release['version_name']]:
            raise Refused('Source CLI version differs from release metadata')
        source_package.verify_links(paths)
    except (ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError) as failure:
        raise Refused('Source archive failed bounded path, manifest, checksum, privacy or link verification') from failure
    return {'files': len(paths), 'extracted_bytes': total}


def native_identity(apk, aapt2, apksigner):
    def run(args):
        r = subprocess.run(args, capture_output=True, text=True, timeout=45)
        if r.returncode or len(r.stdout) > 1024 * 1024:
            raise Refused('APK inspection failed; tool diagnostics were not published')
        return r.stdout
    signature = run([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)])
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
    if len(signers) != 1:
        raise Refused('Expected exactly one verified APK signer')
    manifest = run([str(aapt2), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'])
    def one(pattern):
        found = re.findall(pattern, manifest, re.M)
        if len(found) != 1:
            raise Refused('APK manifest field missing or ambiguous')
        return found[0]
    return {'application_id': one(r'^    A: package="([^"]+)"'),
            'version_name': one(r':versionName\(0x[0-9a-f]+\)="([^"]+)"'),
            'version_code': int(one(r':versionCode\(0x[0-9a-f]+\)=(\d+)')),
            'min_sdk': int(one(r':minSdkVersion\(0x[0-9a-f]+\)=(\d+)')),
            'target_sdk': int(one(r':targetSdkVersion\(0x[0-9a-f]+\)=(\d+)')),
            'debuggable': one(r':debuggable\(0x[0-9a-f]+\)=(true|false)') == 'true',
            'signer_sha256': signers[0]}


def companion_page(release, apk_url):
    e = lambda key: html.escape(str(release[key]))
    return f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow">
<title>Airtap · 给你的 AI 一部手机</title><style>
*{{box-sizing:border-box}}body{{margin:0;background:#f6f5ef;color:#182923;font:17px/1.7 system-ui,sans-serif}}main{{max-width:760px;margin:auto;padding:32px 24px 64px}}header{{font-weight:750;letter-spacing:.04em}}h1{{font-size:clamp(38px,8vw,62px);line-height:1.15;letter-spacing:-.045em;margin:48px 0 24px}}.intro{{font-size:20px;max-width:600px;color:#44564e}}a{{color:#175c42}}.download{{display:block;background:#175c42;color:white;text-align:center;border-radius:16px;padding:17px 22px;margin:28px 0 12px;font-weight:650;text-decoration:none}}.meta{{font-size:14px;color:#627068}}section{{border-top:1px solid #d9dfd7;margin-top:36px;padding-top:20px}}h2{{font-size:24px}}ol{{padding-left:24px}}li{{padding:8px 0}}details{{background:white;border-radius:14px;padding:16px 20px;margin:14px 0}}summary{{cursor:pointer;font-weight:600}}code{{font-size:12px;overflow-wrap:anywhere}}.pill{{display:inline-block;border:1px solid #ced8ce;border-radius:20px;padding:4px 12px;font-size:13px;margin-top:12px}}
</style></head><body><main><header>AIRTAP <span class="pill">Android 技术预览</span></header>
<h1>给你的 AI<br>一部真正的手机。</h1><p class="intro">把闲置手机交给你已有的个人 AI。安装、授权、发出邀请，核对一次数字，以后直接使用。</p>
<a class="download" href="{apk_url}" download>下载 Android 安装包</a>
<p class="meta">Android 14 及以上 · {release['apk_bytes']/1048576:.1f} MB · {e('version_name')} / {e('version_code')}</p>
<p>无需电脑常驻、自建服务器或另买模型 API。原 AI 套餐额度及连接服务仍有成本。</p>
<section><h2>四步就绪</h2><ol>
<li><strong>安装并打开 Airtap。</strong>在手机浏览器下载后打开 APK，按系统提示允许这个可信来源安装。打开 App，点“开始使用”；旧版升级点“开始新版连接”。</li>
<li><strong>按提示开启手机权限。</strong>在无障碍列表找到“Airtap 手机操作”并开启，再允许连接通知。若提示受限设置，核对来源后从 Airtap 应用信息按系统流程允许。</li>
<li><strong>把邀请交给自己的 AI。</strong>点“邀请我的 AI”，复制或分享完整邀请到自己的私人 AI 对话。AI 会处理连接，你无需运行命令。</li>
<li><strong>核对六位数字。</strong>与 AI 返回的数字一致时，点“数字一致，连接我的 AI”。之后无需每次配对，不必逐个选择 App 或频繁续期。</li></ol></section>
<section><h2>手机随时听你的</h2><p>AI 可以查看屏幕、跨 App 点击、输入和滑动。锁屏时暂停，解锁后继续；正常断网自动重连。你可以随时从 Airtap 或连接通知停止，停止后不会自动恢复控制。</p></section>
<details><summary>我的 AI 能接入吗？</summary><p>需要能调用工具并查看图片的个人 AI。提供 HTTP、CLI 和远程 MCP 接入，具体产品和模式仍需逐一实测。只有文字聊天的窗口不能仅凭邀请控制手机。技术差异交给 AI 按邀请处理。</p></details>
<details><summary>连接不稳定或无法安装？</summary><p>先检查网络和 Airtap 权限。在 Airtap“设置 → 后台运行设置”允许本 App 后台运行。邀请五分钟有效，过期重新生成。安装提示签名不一致时联系提供者，不要直接卸载清数据。操作超时后先查看结果，避免重复执行。</p></details>
<details><summary>当前版本与验证范围</summary><p>这是开发签名技术预览。本轮真机样本为 Android 15 魅族 Note 16；不宣称所有手机、AI、移动网络或长期无人看护场景已验证。Android 14 是最低安装要求。iPhone 版本尚未提供。维护者验收不等同于独立普通用户试用。</p><p>手机系统仍管理锁屏、受保护画面和权限；Airtap 的授权页不能由远端 AI 操作。连接服务能接触转发的屏幕内容，目前没有应用层端到端加密。</p></details>
<details><summary>核对安装包</summary><p>包名：<code>{e('application_id')}</code></p><p>APK SHA256：<code>{e('apk_sha256')}</code></p><p>签名证书 SHA256：<code>{e('signer_sha256')}</code></p><p>只从可信提供者取得本页。摘要用于一致性核对，不能代替对来源的信任。<a href="release.json">版本资料</a> · <a href="SHA256SUMS">文件校验</a></p></details>
</main></body></html>'''.encode()


def page(release, apk_url, source_url=None):
    if release['version_name'].startswith('0.4.'):
        return companion_page(release, apk_url)
    e = lambda key: html.escape(str(release[key]))
    android = {34: '14', 35: '15', 36: '16'}.get(release['min_sdk'], 'API ' + str(release['min_sdk']))
    screen_guide = ''
    if (release['version_name'], release['version_code']) in {
            ('0.3.3-service-preview', 17), ('0.3.4-service-preview', 18),
            ('0.3.5-service-preview', 19)}:
        screen_guide = '''<p><strong>本版只在有效授权期间保持亮屏，屏幕可以变暗。</strong>手动息屏或锁屏会结束本地授权；解锁后需要新邀请、新核码。停止、撤销、到期或进程死亡后释放亮屏资源，不远程唤醒或解锁，也不让手机永久常亮。</p>'''
    expanded_apps = (release['version_name'], release['version_code']) in {
        ('0.3.4-service-preview', 18), ('0.3.5-service-preview', 19)}
    scope_guide = ('本版可搜索手机上可启动的 App，每次最多选择 8 个；计算器只是建议的首次验收用例。'
                   if expanded_apps else '没有支持的计算器就联系提供者，不扩大为任意包名。')
    usage_boundary = ('本版由你选择 App 范围，支持读屏、启动、节点点击、输入和滑动；是否能完成具体任务仍取决于 App 与 Android 界面。发送消息、账号变更、支付等具体提交须先由你明确确认，AI 不得自行决定。'
                      if expanded_apps else '本预览先用于有人看护的计算器任务，浏览器仍是实验能力；不要执行支付、账号变更或发送消息。')
    source_section = ''
    if source_url is not None:
        source = release['source']
        source_section = f'''<h2>源码与自托管</h2>
<p><a class="download" href="{source_url}" download>下载源码 ZIP（构建／自托管）</a></p>
<p>源码版本 <code>{e('version_name')}</code>，{source['bytes']:,} 字节；SHA256：<code>{source['sha256']}</code>。</p>
<p>这是经过清单校验的源码分发包，不含签名私钥、部署凭据或依赖缓存。普通手机用户无需下载源码。构建者解压后先校验 <code>SHA256SUMS</code>，再阅读 <code>docs/reproduce.md</code>；构建需要 JDK／Android SDK，首次依赖下载需要网络。自己构建的签名通常与本页安装包不同。</p>
'''
    markup = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><title>安装 Airtap · 受邀技术预览</title>
<style>body{{font:17px/1.65 system-ui,sans-serif;max-width:720px;margin:36px auto;padding:0 22px;color:#17252a;background:#fafbf8}}h1{{font-size:32px;line-height:1.2}}a{{color:#095d54}}.download{{display:inline-block;padding:12px 22px;background:#095d54;color:white;border-radius:8px;text-decoration:none}}code{{overflow-wrap:anywhere;font-size:14px}}dt{{font-weight:650;margin-top:14px}}dd{{margin:0}}.note{{background:#eaf2ed;padding:14px 18px;border-radius:8px}}li{{margin:9px 0}}</style></head>
<body><main><h1>把闲置安卓手机，变成你现有个人 AI 的手机。</h1>
<p>这是 Airtap 的受邀开发技术预览。先在手机安装并授权，再将 App 生成的短期邀请交给自己的个人 AI。</p>
<p><a class="download" href="{apk_url}" download>下载 Android 安装包</a></p>
<p>需要 Android {android} 或更新版本（API {e('min_sdk')} 起）；安装包 {release['apk_bytes']:,} 字节。版本 <code>{e('version_name')}</code>，versionCode {e('version_code')}。</p>
<p>上述版本是最低安装要求；目前真机样本为两台魅族 Note 16、Android 15。其他机型、Android 14 真机和移动网络尚未完成验证。</p>
<div class="note">仅从可信提供者取得本页地址。此包使用开发签名，不是应用商店正式版；兼容机型、网络和个人 AI 仍有限。下载链接不授予手机控制权，也不是访问控制：知道地址的人可以下载。服务费用和容量以交付说明为准，不承诺全部免费。</div>
<h2>1. 安装并登记</h2>
<p>在手机浏览器下载，完成后从下载记录打开 APK，选择系统“软件包安装程序”。按系统提示仅允许本次可信来源安装，核对下方包信息后继续；不要关闭系统检查。同包名升级须同签名且版本号不降低，遇不兼容不要卸载或清数据。</p>
<p>首次启动浏览器时，由你决定是否接受它的条款和权限，也可使用自己已信任的浏览器。安装完成后，用正常返回／关闭操作收起“下载完成”或推荐应用浮层，不点击推广安装。</p>
<p>打开 Airtap，核对显示的 HTTPS 服务域名与提供者说明一致，再点击“连接并登记此手机”。预配置服务包不需要 USB、常开电脑、模型 API key 或 Cloudflare 账户。地址为空就联系提供者；登记超时或未知时保留当前身份重试，不清数据。已有登记的升级用户直接检查权限。</p>
<h2>2. 开启手机权限</h2><ol>
<li>点击“打开无障碍设置”，找到本次安装的 Airtap 服务，阅读系统说明后开启。若有“受限设置”提示，先核对应用来源，再按系统应用信息页的正常流程处理。</li>
<li>点击“允许会话通知 / 打开通知设置”。允许本 App 通知；已有“Airtap 授权中的会话”渠道时也保持开启。首次使用该渠道可能尚未创建。</li>
<li>查看“后台运行准备指引”，仅检查 Airtap 的后台权限。魅族已观察到的路径为“应用信息 → 耗电和后台 → 手动管理 → 允许后台运行”；其他品牌入口可能不同，不更改其他 App 或全局电池策略。</li>
<li>返回 Airtap，确认“无障碍服务：已连接”和“会话通知：已允许”。创建邀请按钮不可用时，先检查登记和这两项。批准前保持手机亮屏、解锁。</li>
</ol>
<h2>3. 把原样邀请交给自己的 AI</h2>
<p>先确认 AI 实际能运行 Python 3.10+ 的 POSIX 工具、访问 HTTPS、保存私有文件并通过工具查看图片。Muse／Instinct 是已有实际测试的对象；仅能文字聊天的窗口不能因为收到链接就控制手机，尚未验证豆包。</p>
<p>准备完成后点击“创建新配对邀请”。使用“复制接入 prompt”或“分享接入 prompt…”把完整原文发给自己的私人 AI 会话；分享会打开选择器，由你选接收方。AI 在另一台设备时，使用自己已有的私人传递渠道。保留完整设备路径及每个 URL，不手工改写；不要公开邀请或把手机长期凭据交给 AI。</p>
<p>邀请最多五分钟。AI 应按原文下载并校验 CLI，使用 <code>pair-start</code> 申请后立即告诉你六位码；等待期间沿用同一份配置，不反复申请。票据过期或两端码不一致时，停止使用该票据，在手机创建新邀请。</p>
<h2>4. 核码并批准本次范围</h2>
<p>在手机核对 AI 返回的六位码。点击“选择本次允许的 App”，第一轮建议只选计算器并确认范围，再勾选“允许在所选 App 内启动、点击、输入和滚动”，然后批准。默认只读不能启动或点击；修改范围会重置操作勾选。{scope_guide}</p>
<p>若未自动进入，点击“进入会话页并连接”。让 AI 等待就绪、核对实际计算器范围和操作权限；“已批准”不等于已经连通。</p>
<h2>5. 完成第一轮计算</h2>
<p>把这条任务发给 AI：“请清空计算器旧式，每一步读新树，计算 8×9，实际打开本轮截图确认当前结果为 72；完成后主动撤销，并确认原授权后续请求被拒绝。结果未知时不要重放操作。”</p>
<p>首次打开计算器可能出现魅族系统提示“Airtap 正在尝试打开计算器”。由你核对目标后点击“打开”，再告诉 AI 查询状态、读取新树继续。AI 若收到 <code>launch_unconfirmed</code>，不要自动重复启动；Airtap 不操作这个系统确认窗口。</p>
<h2>6. 停止、到期与下次使用</h2>
<p>每次批准最多 <strong>15 分钟、200 个命令 ID</strong>。登记长期保留不代表永久控制；重连不会续权。到期后必须重新生成邀请、核码并批准。</p>
{screen_guide}
<p>异常时先在 Airtap 或常驻通知点击停止，随后让 AI 撤销并检查拒绝结果。网络超时表示远端结果未知，不能当作撤销成功；本机停止是另一项确认。App 进程结束或重新打开不会恢复旧控制权。</p>
<h2>遇到问题</h2><dl>
<dt>下载页还是旧版本</dt><dd>正常刷新页面，核对版本和摘要后再下载；不凭旧标签页判断当前版本。</dd>
<dt>AI 无法下载或连接</dt><dd>保留 403、超时或校验不符的错误，核对完整邀请和服务来源；不关闭 TLS 校验。仅在有效授权内尝试网络恢复，写操作结果未知时不自动重试。</dd>
<dt>手机显示已停止或授权到期</dt><dd>旧邀请和旧批准不能恢复控制。正常打开 Airtap，保留登记，创建新邀请并重新核码。</dd>
</dl>
<p>{usage_boundary}包名范围不能识别所有敏感行为，中继能接触经过它的读屏与操作内容，尚无应用层端到端加密。</p>
<p>本页已包含默认手机使用步骤，无需读取私有源码。普通安装、首次权限和手机到 AI 的实际传递仍需完成；维护者代操作不能算独立用户复刻。其他机型、长期后台和任意 App／AI 不在已验承诺内。</p>
<h2>核对安装包</h2><dl><dt>包名</dt><dd><code>{e('application_id')}</code></dd>
<dt>APK SHA256</dt><dd><code>{e('apk_sha256')}</code></dd><dt>签名证书 SHA256</dt><dd><code>{e('signer_sha256')}</code></dd></dl>
<p>同一网页的摘要只用于一致性核对，不是独立发布者认证。请同时核对可信交付消息里的摘要和签名。<a href="release.json">版本资料</a> · <a href="SHA256SUMS">文件校验清单</a></p>
{source_section}</main></body></html>
'''
    if (release['version_name'], release['version_code']) == ('0.3.5-service-preview', 19):
        companion_labels = {
            '打开 Airtap，核对显示的 HTTPS 服务域名': '打开 Airtap，点“完成手机准备”或右上角“设置”，核对显示的 HTTPS 服务域名',
            '点击“允许会话通知 / 打开通知设置”': '点击“会话通知设置”',
            '查看“后台运行准备指引”': '查看“后台运行指引”',
            '准备完成后点击“创建新配对邀请”。使用“复制接入 prompt”或“分享接入 prompt…”': '准备完成后点“返回”回到首页，再点“邀请我的 AI”。使用“复制完整邀请”或“分享邀请…”',
            '点击“选择本次允许的 App”': '点击“选择 App（最多 8 个）”',
            '再勾选“允许在所选 App 内启动、点击、输入和滚动”，然后批准。': '再打开“允许操作所选 App”开关，然后点“批准接入”。',
            '修改范围会重置操作勾选': '修改范围会关闭操作开关',
            '点击“进入会话页并连接”': '点击“打开会话”',
        }
        for old, new in companion_labels.items():
            markup = markup.replace(old, new)
    return markup.encode()


def site_files(release, apk_bytes, source_bytes=None):
    if len(apk_bytes) != release['apk_bytes'] or digest(apk_bytes) != release['apk_sha256']:
        raise Refused('APK bytes differ from fixed release metadata')
    apk_path = 'downloads/' + release['apk_sha256'] + '.apk'
    has_source = release['schema'] == SOURCE_SCHEMA
    if has_source != (source_bytes is not None):
        raise Refused('Source metadata and ZIP input must be provided together')
    source_path = 'downloads/' + release['source']['sha256'] + '.zip' if has_source else None
    if has_source:
        source_archive(source_bytes, release)
    files = {apk_path: apk_bytes, 'release.json': encoded(release), 'index.html': page(release, apk_path, source_path)}
    if has_source:
        files[source_path] = source_bytes
    files['_headers'] = ('''/*
  X-Content-Type-Options: nosniff
  X-Frame-Options: DENY
  Referrer-Policy: no-referrer
  X-Robots-Tag: noindex, nofollow
  Cache-Control: no-store
  Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
/downloads/''' + release['apk_sha256'] + '''.apk
  Content-Type: application/vnd.android.package-archive
  Content-Disposition: attachment; filename="airtap-''' + release['version_name'] + '''.apk"
''').encode()
    if has_source:
        files['_headers'] += ('/' + source_path + '\n  Content-Type: application/zip\n  Content-Disposition: attachment; filename="' + release['source']['archive_root'] + '.zip"\n').encode()
    files['SHA256SUMS'] = ''.join(digest(data) + '  ' + path + '\n' for path, data in sorted(files.items())).encode()
    return files


def worker_config(name):
    if not isinstance(name, str) or not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', name):
        raise Refused('Invalid explicit download Worker name')
    return {'name': name, 'compatibility_date': '2026-10-01', 'workers_dev': True,
            'preview_urls': False, 'observability': {'enabled': False},
            'assets': {'directory': './site', 'not_found_handling': 'none'}}


def build(apk, release_path, output, worker_name, aapt2, apksigner, source_zip=None):
    release = metadata(read_regular(release_path, 16384))
    data = read_regular(apk, MAX_APK)
    if source_zip is not None and Path(source_zip).suffix.lower() != '.zip':
        raise Refused('Source input must be a ZIP file')
    source_data = read_regular(source_zip, MAX_SOURCE_ZIP) if source_zip is not None else None
    files = site_files(release, data, source_data)
    config = worker_config(worker_name)
    # Inspect an immutable private copy so the verified bytes are the copied bytes.
    with tempfile.TemporaryDirectory(prefix='airtap-download-verify-') as temp:
        snapshot = Path(temp) / 'input.apk'
        snapshot.write_bytes(data)
        snapshot.chmod(0o600)
        actual = native_identity(snapshot, aapt2, apksigner)
    if any(release[k] != v for k, v in actual.items()):
        raise Refused('Actual APK identity differs from release metadata')
    output = Path(output)
    if output.exists() or output.is_symlink() or not output.parent.is_dir():
        raise Refused('Output must be a new directory with an existing parent')
    output.mkdir(mode=0o700)
    for path, content in files.items():
        target = output / 'site' / path
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(content)
        target.chmod(0o644)
    (output / 'wrangler.json').write_bytes(encoded(config))
    manifest = {'schema': 'airtap-download-build-v2' if source_data is not None else 'airtap-download-build-v1', 'release_sha256': digest(files['release.json']),
                'native_apk_verified': actual, 'files': {p: {'bytes': len(d), 'sha256': digest(d)} for p, d in sorted(files.items())},
                'wrangler_sha256': digest(encoded(config)), 'deployed': False,
                'scope': MANIFEST_SCOPE}
    (output / 'build-manifest.json').write_bytes(encoded(manifest))
    return verify(output)


def verify(output):
    output = Path(output)
    if output.is_symlink() or not output.is_dir():
        raise Refused('Invalid output directory')
    release = metadata(read_regular(output / 'site/release.json', 16384))
    apk_path = 'downloads/' + release['apk_sha256'] + '.apk'
    source_data = read_regular(output / 'site/downloads' / (release['source']['sha256'] + '.zip'), MAX_SOURCE_ZIP) if release['schema'] == SOURCE_SCHEMA else None
    expected = site_files(release, read_regular(output / 'site' / apk_path, MAX_APK), source_data)
    manifest = json.loads(read_regular(output / 'build-manifest.json', 32768), object_pairs_hook=pairs)
    config = json.loads(read_regular(output / 'wrangler.json', 8192), object_pairs_hook=pairs)
    if not isinstance(manifest, dict) or set(manifest) != {'schema', 'release_sha256', 'native_apk_verified', 'files', 'wrangler_sha256', 'deployed', 'scope'} or manifest.get('scope') != MANIFEST_SCOPE or not isinstance(config, dict):
        raise Refused('Unexpected manifest or configuration shape')
    if config != worker_config(config.get('name')) or digest(encoded(config)) != manifest.get('wrangler_sha256'):
        raise Refused('Unexpected deployment configuration')
    allowed_files = {'site/' + p for p in expected} | {'wrangler.json', 'build-manifest.json'}
    allowed_dirs = {'site', 'site/downloads'}
    found_files = set()
    for path in output.rglob('*'):
        relative = path.relative_to(output).as_posix()
        info = path.lstat()
        if stat.S_ISDIR(info.st_mode) and relative in allowed_dirs:
            continue
        if not stat.S_ISREG(info.st_mode) or relative not in allowed_files or stat.S_IMODE(info.st_mode) & 0o111:
            raise Refused('Unexpected path, file type or executable mode in output')
        found_files.add(relative)
    if found_files != allowed_files:
        raise Refused('Output inventory mismatch')
    expected_manifest_files = {p: {'bytes': len(d), 'sha256': digest(d)} for p, d in sorted(expected.items())}
    expected_schema = 'airtap-download-build-v2' if source_data is not None else 'airtap-download-build-v1'
    if manifest.get('schema') != expected_schema or manifest.get('deployed') is not False or manifest.get('files') != expected_manifest_files or manifest.get('release_sha256') != digest(expected['release.json']):
        raise Refused('Build manifest mismatch')
    identity = {key: release[key] for key in ('application_id', 'version_name', 'version_code', 'min_sdk', 'target_sdk', 'debuggable', 'signer_sha256')}
    if manifest.get('native_apk_verified') != identity:
        raise Refused('APK identity record mismatch')
    for path, data in expected.items():
        if read_regular(output / 'site' / path, max(MAX_APK, len(data))) != data:
            raise Refused('Generated content differs from deterministic template')
    result = {'status': 'verified_local_static_directory_not_deployed', 'files': len(allowed_files),
            'apk_sha256': release['apk_sha256'], 'version_name': release['version_name'],
            'manifest_sha256': digest(read_regular(output / 'build-manifest.json', 32768)),
            'verification_limit': 'Local inventory/bytes/template consistency; not independent publisher authentication or an online download test.'}
    if source_data is not None:
        result.update(source_sha256=release['source']['sha256'], source_bytes=release['source']['bytes'], source_archive_root=release['source']['archive_root'])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    create = sub.add_parser('build')
    for option in ('apk', 'metadata', 'output', 'aapt2', 'apksigner'):
        create.add_argument('--' + option, required=True, type=Path)
    create.add_argument('--worker-name', required=True)
    create.add_argument('--source-zip', type=Path, help='Reviewed final source ZIP; requires release-v2 source metadata')
    check = sub.add_parser('verify')
    check.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = build(args.apk, args.metadata, args.output, args.worker_name, args.aapt2, args.apksigner, args.source_zip) if args.command == 'build' else verify(args.output)
        print(json.dumps(result, ensure_ascii=False))
    except (Refused, OSError, ValueError, subprocess.SubprocessError):
        parser.exit(1, 'Refused: input, APK verification or artifact checks failed; no deployment was attempted.\n')


if __name__ == '__main__':
    main()
