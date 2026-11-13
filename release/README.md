# 固定入口与内部版本管理

用户只需记住 https://airtap.memtrix.dev 。`/install` 是独立安装与接入页，用于二维码与分享；`/download/android` 总是临时跳转到推荐 APK，`/help` 按类别和关键词提供使用帮助。首页保留用途、真机演示与实际验证范围，不要求用户先读完整技术记录。主流程不要求用户挑选版本、协议、部署环境或下载某个 GitHub 附件。APK 下载文件名固定为 `Airtap.apk`。

推荐版本是发布团队的责任。推荐安装包升级时将官网、`/updates/android.json` 和安装包一次部署；仅更新网站也使用同一冻结构建流程，无需虚增 Android 版本；APK 存在 `/downloads/<sha256>.apk`，字节不可覆盖。网站和推荐信息不长期缓存，APK 可永久缓存。正常升级时保留未退役的旧 APK；经审核需停止公开分发的包写入 `retired-apks.json`，旧地址返回 410 与当前安装页链接，不把原地址替换成新 APK 字节。私有历史原件与冻结构建保留。App、CLI 与协议各自管理内部版本，不要求用户理解或配对其编号。

APK 下载支持 HEAD 文件长度和单段 Range 续传。构建从实际保留的 APK 字节生成 `/updates/android-downloads.json`，供静态资源未返回长度时使用；该清单与文件一起部署，不保存用户或设备信息。公网验收逐个核对 APK 的完整内容、HEAD 长度与 206 分段内容。支持续传不代表手机浏览器已获存储权限或所有网络路径都正常；[实际发布与公网检查](../docs/results/apk-download-range-publication-20261108.json)保留验证范围。

公开静态页设置 `Cache-Control: public, no-cache, no-transform`，要求 CDN 保持原文并防止自动注入访问统计脚本，再以实际 HTTP 内容核对；此设置仅影响下载站，不修改中继或整个域名区域的分析配置。更新 JSON 仍为 `no-store`。参见 [Cloudflare 自动注入说明](https://developers.cloudflare.com/web-analytics/get-started/)。Cloudflare Managed robots 仍可能在 `/robots.txt` 前附加托管规则，这一项须按[网站验收清单](site-acceptance.md#部署后)单独审阅确切前缀，不以此放宽其他文件的校验。

code63 将首次及新增 AI 的邀请改为八位数字。数字只定位短期邀请，仍需手机核对另一组六位数字并批准。协议、CLI、Android 原生界面与异常路径的检查范围见[本版记录](../docs/results/beta33-code63-short-invitations-20261110.json)；历史手机实测保持各自版本。

## 每夜开发包与推荐安装包

新公开仓库的 [Android 每夜构建](../.github/workflows/android-nightly.yml) 在北京时间每日 03:17（UTC 19:17）调度，也可手动触发。它使用标准 Ubuntu 24.04 runner、Java 17，执行 debug 构建与 lint；只保存开发 APK、摘要和构建元数据，每次最多 20 MiB、保留 3 天，不配置 Actions 缓存。调度不保证准点，公开仓库长期无活动可能自动停用；费用与完整运行说明见[代码检查说明](../docs/checks.md#android-每夜构建)。

每夜包使用临时 debug 签名，未预配置公共中继，供开发者配置服务后验证。它与推荐包使用相同包名，但签名不同，不能覆盖正式版；不同日期的每夜包也不保证可覆盖升级。**不得建议用户通过卸载正式版或清除数据来试用每夜包。** 普通用户仍从官网固定入口安装推荐版本。

每夜构建不读取正式签名、不生成正式发布记录、不部署或修改推荐版本。需要可持续升级的公开测试渠道时，须另行设计包名、稳定签名、服务配置及升级验收；不能把每夜构建成功直接写成新版本已发布或已通过手机验收。

## 当前发布身份

code78 / beta.48 已作为 [GitHub 测试版](https://github.com/hao-cyber/airtap/releases/tag/v0.4.0-beta.48)公开；公共中继已更新至 revision41 / CLI.24。它补上 AI 打开正常通知使用权设置页的入口，不自动授予权限。Instinct 已经原连接完成一台真机覆盖升级，通知任务结果仍待核验；[构建、发布与实际范围](../docs/results/beta48-code78-notification-settings-20261113.json)分别记录。官网推荐暂未切换。

当前官网推荐 Android `0.4.0-beta.45`（code75），[版本资料](android/75.json)固定制品身份。新增 `ping.readiness`，只读查询通知、媒体采集和 SIM PIN 准备状态，不自动开启权限。[两位 AI 真机回归](../docs/results/beta45-code75-personal-ai-20261112.json)完成原连接远程升级及状态回读；Instinct 另完成计算器无声录屏与私有文件回传。两台最终回桌面、静音、释放任务，无 USB 或机主操作手机；录屏系统确认由已获当次授权的代理操作。保留短时掉线、下载重试、早期失败及录屏播放时长差异。[发布核验](../docs/results/beta45-code75-publication-20261112.json)记录实际部署。

code73 已在 GitHub 发布候选，Muse 与 Instinct 分别经两台原连接完成远程升级及 96 KiB 完整上传。小红走 VPN 的一次同样本对照由 code72 超时改善为 28.17 秒完成；小黑走 Wi-Fi 的独立样本为 14.93 秒，不能把两台设备当作同条件测速。小黑完成实际选图附件预览，小红完成文件分享预览，但系统文件选择器仍未选中，见[实际记录](../docs/results/beta43-code73-personal-ai-files-20261112.json)。

[真实 DocumentsUI 模拟器验证](../docs/results/beta44-code74-documentsui-native-20261112.json)使用生产点击选中文件，再由独立接收 App 核验完整 SHA；它与上述实体手机的视觉附件核对分别记录。

此前 code66 增加已有 AI 代办连接管理、电池状态及过期任务恢复，见[发布核验](../docs/results/beta36-code66-publication-20261111.json)与[实际任务记录](../docs/results/beta36-code66-personal-ai-20261111.json)。

此前 code65 的通知事件、持续触控、文件续传、媒体采集和任务记录保持原范围，见[实现与检查](../docs/results/beta35-code65-capabilities-20261110.json)及[Muse 两机记录](../docs/results/beta35-code65-muse-20261110.json)。

此前 code64 延长新邀请至一小时，过期可重新生成，AI 在手机批准后自行核实。[构建与配对检查](../docs/results/beta34-code64-pairing-journey-20261110.json)与[两台手机实测](../docs/results/beta34-code64-two-ai-20261110.json)保持原版本范围。

当前为公开测试渠道，使用长期 RSA 3072 发布密钥，Android `debuggable=false`，最低 Android 14。包名继续使用 `io.airtap.preview`，以保留已安装用户的数据；包名不作为用户界面名称。正式发布签名不代表产品已通过全部机型或普通用户验证。

此前 Android `0.4.0-beta.33`（code63），[版本元数据](android/63.json)固定制品身份。[发布核验](../docs/results/beta33-code63-publication-20261110.json)记录中继 revision26、CLI.14 和官网的实际更新。Muse 经两台原连接完成[远程覆盖升级、计算器核对与收尾](../docs/results/beta33-code63-muse-two-devices-20261110.json)，没有 USB 或人工操作手机。新短码的实体手机配对、当前 Instinct 任务和双 AI 交接尚未完成。

此前 code62 的[发布核验](../docs/results/beta32-code62-publication-20261109.json)确认官网与 GitHub 提供同一签名制品。Muse 经原连接完成[正常覆盖升级与收尾](../docs/results/beta32-code62-upgrade-20261109.json)，保留安装中断和参数错误后的自行恢复记录；该轮没有人工操作手机或 USB 代做任务。随后在第二台手机完成[原连接升级与 70 秒息屏恢复](../docs/results/beta32-code62-second-device-20261109.json)：约 4.4 秒唤醒，计算器结果和桌面均实际查看，保留失败恢复记录。这仍不替代重启、Instinct 或双 AI 交接验收。

此前 Muse 在 code61 使用原连接，在一台魅族 Note 16 上完成两次息屏唤醒、桌面视觉核对、短信界面读取和收尾。机主在测试前完成了重启后的 SIM PIN 验证，该保护开关仍开启，因此后续重启无需人工尚未通过。Google Play 搜索因未登录未完成；本版 Instinct、跨云端环境配置保留和多日后台仍未验证。

此前 [code52 发布记录](../docs/results/beta22-code52-publication-20261104.json)与 [Muse 重启记录](../docs/results/beta22-code52-recovery-20261104.json)继续保留，不能作为 code61 的独立重启验收。

历史 code51 的[制品准备](../docs/results/beta21-code51-release-preparation-20261103.json)、[发布](../docs/results/beta21-code51-publication-20261103.json)、[Muse](../docs/results/beta21-code51-muse-cli12-wake-20261103.json)、[Instinct](../docs/results/beta21-code51-instinct-cli12-wake-20261103.json)及[双 AI 交接](../docs/results/beta21-code51-two-ai-handoff-20261103.json)记录保持原范围；原 code39 [版本资料](android/39.json)与验收也保留，不重写历史。

从极早的协议预览版升级时，安装保留应用数据不等于旧临时授权可以继续使用。若 App 显示“开始新版连接”，按前台流程为该手机建立新邀请并核码；不要复制另一部手机的配对，也不要卸载或清除其他 App 账号。原发布证书的签名轮换仅覆盖匹配的已安装包，具体设备须核对实际签名及升级结果。

发布证书 SHA256：`a2aa0c1b6fdaf1baa37aa20817387524a1318b5dab82bf7c185663141daba60f`。

本次使用 Android v3 签名轮换，从原项目开发证书继承已安装数据权限；未开启旧证书回滚权限。兼容路径只适用于原项目证书安装的预览包，不包括他人自行构建的包。私钥、密码、升级签名链的维护副本及手机备份均存于源码外的私有目录，不随源码发布。签名链本身也包含在已签名 APK 的证明中。

长期维护需要独立加密备份发布密钥，并记录恢复责任人。当前已在本机私有目录创建发布密钥；2026-10-26 发布者明确选择暂时只保留本机，独立备份稍后安排。本轮不再等待备份位置，也不把本机保管写成已完成灾难恢复。不要重新生成密钥替代遗失密钥。

## 生成新推荐版本

1. 在 Android 构建配置递增 `versionCode`，设置面向维护者的 `versionName`。不要改包名或签名。协议改变时另外完成协议及个人 AI 回归。
2. 从私有保管位置向进程提供 `JAVA_HOME`、`ANDROID_HOME` 和四个 `AIRTAP_RELEASE_*` 签名环境变量；设置 `AIRTAP_RELEASE_LINEAGE` 指向原签名链。不要把环境值写进命令参数、仓库或日志。
3. 构建并验证：

```sh
python3 release/build_android.py --relay https://YOUR-EXISTING-RELAY --output /private/new-release/Airtap.apk
```

4. 为 APK 创建新的 `release/android/<versionCode>.json`，填写真实字节数、摘要、证书、版本、更新说明，沿用 `25.json` 的公共字段。不得放凭据、设备身份或原始屏幕。用实际 `apksigner` 与 `aapt2` 的结果填写，不能仅凭 Gradle 源码推断。
5. 将上一轮冻结部署作为 `--previous`，保留未退役旧制品与已有退役记录，拒绝 versionCode 倒退或签名改变。相同 versionCode 只接受逐字相同的 APK 与原生身份，可用于下文的网站更新：

```sh
node --test release/worker.test.mjs
python3 -m unittest discover -s release -p 'test_*.py'
python3 release/build_site.py --apk /private/new-release/Airtap.apk --metadata release/android/26.json --output /private/site-26 --previous /private/site-25 --tools "$ANDROID_HOME/build-tools/36.1.0"
python3 release/verify_site.py --build /private/site-26
cp -R /private/site-26 /private/site-26-deploy
wrangler deploy --config /private/site-26-deploy/wrangler.json
python3 release/verify_site.py --build /private/site-26 --base-url https://airtap.memtrix.dev
```

首次上线不传 `--previous`。示例中的编号由发布者管理，不能直接用示例值作为验收证据。部署前核对 `build-manifest.json` 并保留冻结目录，之后不修改其文件。Wrangler 会在配置旁写入运行状态，预览和部署均使用新建工作副本；校验始终以原冻结目录为准，不把副本内的 `.wrangler` 当作发布制品。

6. 上述线上验收脚本以普通 Python HTTP 客户端读取固定下载入口、所有页面与静态资源、更新 JSON、推荐及保留的旧 APK，逐字比对冻结构建并检查缓存和下载响应头，防止 CDN 注入或推荐仍旧；对每个退役 APK 地址分别检查 GET 与 HEAD 返回 410、禁止缓存并引导安装当前版本。若 `/robots.txt` 包含已单独审阅的托管前缀，增加 `--managed-robots-prefix /private/reviewed-robots-prefix.txt`，只允许该前缀与冻结原文的精确拼接，并在报告中记录转换摘要；前缀或其他内容变化仍拒绝。它不会部署或访问私人接口；输出只证明网站 HTTP 内容，不代替手机安装、覆盖升级或个人 AI 验收。另在浏览器检查手机和桌面宽度、帮助直达与下载，再完成对应手机覆盖升级。App 检查更新只访问固定域名，不发送身份或凭据，且不会自动安装。安装仍由 Android 系统确认。
7. 保存提交、部署 ID、证书、APK 摘要和测试结果，发布带这些证据的 release。旧验收仅覆盖其原制品，新制品需要对应变更回归。 创建 GitHub Release 时，`--target` 使用已推送提交的完整 40 位 SHA；缩写可能被 API 拒绝。提交结果未知时先核对标签和 Release 是否已存在，再决定重试，不覆盖已经发布的制品。

## 仅更新官网与帮助

修正新手指引、帮助、样式或下载响应，不需要重新构建或签署 APK，也不需要提高 `versionCode`。使用当前已发布 APK、原 metadata 和上一份冻结部署，输出到一个新的目录：

```sh
python3 release/build_site.py --apk /private/current/Airtap.apk --metadata release/android/CURRENT_CODE.json --output /private/site-help-refresh --previous /private/current-site --tools "$ANDROID_HOME/build-tools/36.1.0"
python3 release/verify_site.py --build /private/site-help-refresh
```

`CURRENT_CODE` 是维护者替换的示例。构建清单会标记 `deployment_kind: site_refresh`；Android 升级是 `android_update`，首次构建是 `initial`。同版本仍会执行 APK 原生签名及清单校验，比较包名、版本名与编号、字节摘要与长度、签名、SDK 和调试标志。不同字节不能借“只更新网站”占用原 versionCode；之前推荐的 APK 丢失或改变也会拒绝。网站发布记录使用自己的提交和部署 ID，不把新部署日期写成用户收到新 APK。

视频须先完成内容和隐私审阅。使用片身份记录在 `usage-video.json`，安装指南单独记录在 `install-video.json`；MP4 保留在源码树以外。构建时分别显式传入 `--media-file /private/reviewed-usage.mp4` 和 `--install-media-file /private/reviewed-install.mp4`，只接受各自清单指定的字节数和 SHA256，并核对公开海报。成片进入冻结站的 `media/`，与页面一起逐字校验。未提供某项参数时不生成对应播放器，也不引用缺失的视频；后续刷新若需要保留两条视频，必须再次提供两个已审文件。构建不会自动从上次部署继承媒体，也不会扫描私有制作目录。单个网站视频上限为20 MiB，源码文件的4 MiB上限不变。

媒体清单的 `version_code` 表示内容关联的参考发行版；安装教程中，它是教程参考的发行版，不表示整片素材都录于该版本。可选的 `recorded_version_codes` 记录真实素材设备上的版本，必须为非空、递增且不重复的正整数列表，不接受布尔值。例如教程参考 code39、混合使用 code39 与 code40 的录屏时，填写 `version_code: 39` 和 `recorded_version_codes: [39, 40]`，并在制作记录中逐段对应来源。未提供该可选字段的旧清单继续可用，但缺省不证明全部素材来自参考版本；版本列表也不证明这些版本都通过任务验收。官网推荐 APK 仅由 Android 发布资料生成的 `updates/android.json` 决定，媒体版本字段不参与推荐切换。

两条视频均采用原生播放器，用户点击后播放，默认不预载、不自动播放。使用片展示特定版本的一次任务；安装指南结合已接入手机的真实操作片段与明确标注的步骤说明，未实录首次安装、开启权限、发邀请或核码。这些材料不替代独立用户验收或长期后台验证。发布前分别核对视频/海报摘要、所有条件披露、手机阅读与播放/拖动行为；公网验收对每条视频检查完整字节、MIME、HEAD 长度及206分段字节。

网站保持一个普通用户下载入口，不要求用户选择开发包和候选包。开发分支的能力描述、历史验收和正式推荐版本应分别标明；推荐切换时统一修改首页动态版本、更新说明、README 与使用指南，不能只改下载按钮。

部署前可对照 [网站发布验收清单](site-acceptance.md)。所有检查结果保存在冻结目录以外，避免将测试记录、截图或本地路径一起部署。正式部署后再次运行上面的 `verify_site.py --base-url`，不要把本地预览当作线上已经更新。

## 停止旧安装包的公开分发

`retired-apks.json` 是经审核的不可变 APK 摘要清单。构建将它与上一份冻结部署的退役记录合并，生成 `/updates/retired-android.json`；未来网站刷新仍保留旧地址的 410 响应。推荐 APK 不得出现在退役清单中。构建验证旧文件摘要后，从新部署资产中排除退役二进制，不删除上一份私有冻结目录。

使用当前 APK 与 metadata 按“仅更新官网与帮助”的流程构建，审阅新资产目录只包含仍受支持的 APK。Worker 在访问 APK 静态资产之前检查退役记录；已退役路径（包括带查询参数或 Range 的请求）返回不缓存的 410 页面，链接 `/install`，不重定向为不同字节。其他未知摘要仍返回 404。缺失或损坏的退役记录会暂时拒绝 APK 下载，不能回退到公开旧文件。

历史 APK 曾使用一年 `immutable` 缓存。当前 `run_worker_first: true` 且未启用额外响应缓存时，Worker 在读取 APK 前返回 410；无需先清缓存才能生效。部署后分别验证原 URL 的 GET、HEAD 与实际下载入口；若另外启用了 CDN 响应缓存，再按配置评估并定向清理。服务端退役不能收回浏览器已缓存、个人 AI 已下载或手机已安装的副本，也不能替代那些客户端自己的覆盖升级。记录实际路由、缓存配置与是否执行定向清理；不要宣称本地通过就已完成公网退役。

冻结源码、私有 APK、原测试证据和原部署记录均保留。退役仅改变公开下载，不关闭中继、不撤销连接、不删除手机数据。需要回退网站改动时，应从前一版本内容重新构建并保留退役清单；直接回滚到退役前的 Worker 会重新公开旧包，不能作为普通恢复步骤。

## 故障与回退

网站／推荐选择错误可使用已冻结内容恢复；已有 APK 退役时必须保留退役策略，不能直接重启公开旧包。已升级的 Android 不支持用更低 versionCode 普通覆盖安装；客户端回退应将已知良好代码构建为更高 versionCode，并用同一长期密钥发布。不要要求用户卸载清数据解决发布错误。

当前中继仍使用已验收部署，App 默认配置已包含地址，普通用户不需要看到或填写。另行迁移中继域名时须保持服务身份与已有设备记录并验证配对；不能只替换字符串后假定迁移完成。
