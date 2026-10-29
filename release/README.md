# 固定入口与内部版本管理

用户只需记住 https://airtap.memtrix.dev 。`/install` 用于二维码与分享，`/download/android` 总是临时跳转到推荐 APK，`/help` 提供使用帮助。主流程不要求用户挑选版本、协议、部署环境或下载某个 GitHub 附件。APK 下载文件名固定为 `Airtap.apk`。

推荐版本是发布团队的责任。推荐安装包升级时将官网、`/updates/android.json` 和安装包一次部署；仅更新网站也使用同一冻结构建流程，无需虚增 Android 版本；APK 存在 `/downloads/<sha256>.apk`，字节不可覆盖。网站和推荐信息不长期缓存，APK 可永久缓存。正常升级时保留未退役的旧 APK；经审核需停止公开分发的包写入 `retired-apks.json`，旧地址返回 410 与当前安装页链接，不把原地址替换成新 APK 字节。私有历史原件与冻结构建保留。App、CLI 与协议各自管理内部版本，不要求用户理解或配对其编号。

公开静态页设置 `Cache-Control: public, no-cache, no-transform`，要求 CDN 保持原文并防止自动注入访问统计脚本，再以实际 HTTP 内容核对；此设置仅影响下载站，不修改中继或整个域名区域的分析配置。更新 JSON 仍为 `no-store`。参见 [Cloudflare 自动注入说明](https://developers.cloudflare.com/web-analytics/get-started/)。Cloudflare Managed robots 仍可能在 `/robots.txt` 前附加托管规则，这一项须按[网站验收清单](site-acceptance.md#部署后)单独审阅确切前缀，不以此放宽其他文件的校验。

## 每夜开发包与推荐安装包

新公开仓库的 [Android 每夜构建](../.github/workflows/android-nightly.yml) 在北京时间每日 03:17（UTC 19:17）调度，也可手动触发。它使用标准 Ubuntu 24.04 runner、Java 17，执行 debug 构建与 lint；只保存开发 APK、摘要和构建元数据，每次最多 20 MiB、保留 3 天，不配置 Actions 缓存。调度不保证准点，公开仓库长期无活动可能自动停用；费用与完整运行说明见[代码检查说明](../docs/checks.md#android-每夜构建)。

每夜包使用临时 debug 签名，未预配置公共中继，供开发者配置服务后验证。它与推荐包使用相同包名，但签名不同，不能覆盖正式版；不同日期的每夜包也不保证可覆盖升级。**不得建议用户通过卸载正式版或清除数据来试用每夜包。** 普通用户仍从官网固定入口安装推荐版本。

每夜构建不读取正式签名、不生成正式发布记录、不部署或修改推荐版本。需要可持续升级的公开测试渠道时，须另行设计包名、稳定签名、服务配置及升级验收；不能把每夜构建成功直接写成新版本已发布或已通过手机验收。

## 当前发布身份

当前为公开测试渠道，使用长期 RSA 3072 发布密钥，Android `debuggable=false`，最低 Android 14。包名继续使用 `io.airtap.preview`，以保留已安装用户的数据；包名不作为用户界面名称。正式发布签名不代表产品已通过全部机型或普通用户验证。

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
7. 保存提交、部署 ID、证书、APK 摘要和测试结果，发布带这些证据的 release。旧验收仅覆盖其原制品，新制品需要对应变更回归。

## 仅更新官网与帮助

修正新手指引、帮助、样式或下载响应，不需要重新构建或签署 APK，也不需要提高 `versionCode`。使用当前已发布 APK、原 metadata 和上一份冻结部署，输出到一个新的目录：

```sh
python3 release/build_site.py --apk /private/current/Airtap.apk --metadata release/android/CURRENT_CODE.json --output /private/site-help-refresh --previous /private/current-site --tools "$ANDROID_HOME/build-tools/36.1.0"
python3 release/verify_site.py --build /private/site-help-refresh
```

`CURRENT_CODE` 是维护者替换的示例。构建清单会标记 `deployment_kind: site_refresh`；Android 升级是 `android_update`，首次构建是 `initial`。同版本仍会执行 APK 原生签名及清单校验，比较包名、版本名与编号、字节摘要与长度、签名、SDK 和调试标志。不同字节不能借“只更新网站”占用原 versionCode；之前推荐的 APK 丢失或改变也会拒绝。网站发布记录使用自己的提交和部署 ID，不把新部署日期写成用户收到新 APK。

本轮网站不包含演示视频；视频需单独通过内容验收后再决定发布。网站保持一个普通用户下载入口，不要求用户选择开发包和候选包。开发分支的能力描述、历史验收和正式推荐版本应分别标明；推荐切换时统一修改首页动态版本、更新说明、README 与使用指南，不能只改下载按钮。

部署前可对照 [网站发布验收清单](site-acceptance.md)。所有检查结果保存在冻结目录以外，避免将测试记录、截图或本地路径一起部署。正式部署后再次运行上面的 `verify_site.py --base-url`，不要把本地预览当作线上已经更新。

## 停止旧安装包的公开分发

`retired-apks.json` 是经审核的不可变 APK 摘要清单。构建将它与上一份冻结部署的退役记录合并，生成 `/updates/retired-android.json`；未来网站刷新仍保留旧地址的 410 响应。推荐 APK 不得出现在退役清单中。构建验证旧文件摘要后，从新部署资产中排除退役二进制，不删除上一份私有冻结目录。

使用当前 APK 与 metadata 按“仅更新官网与帮助”的流程构建，审阅新资产目录只包含仍受支持的 APK。Worker 在访问 APK 静态资产之前检查退役记录；已退役路径（包括带查询参数或 Range 的请求）返回不缓存的 410 页面，链接 `/install`，不重定向为不同字节。其他未知摘要仍返回 404。缺失或损坏的退役记录会暂时拒绝 APK 下载，不能回退到公开旧文件。

历史 APK 曾使用一年 `immutable` 缓存。当前 `run_worker_first: true` 且未启用额外响应缓存时，Worker 在读取 APK 前返回 410；无需先清缓存才能生效。部署后分别验证原 URL 的 GET、HEAD 与实际下载入口；若另外启用了 CDN 响应缓存，再按配置评估并定向清理。服务端退役不能收回浏览器已缓存、个人 AI 已下载或手机已安装的副本，也不能替代那些客户端自己的覆盖升级。记录实际路由、缓存配置与是否执行定向清理；不要宣称本地通过就已完成公网退役。

冻结源码、私有 APK、原测试证据和原部署记录均保留。退役仅改变公开下载，不关闭中继、不撤销连接、不删除手机数据。需要回退网站改动时，应从前一版本内容重新构建并保留退役清单；直接回滚到退役前的 Worker 会重新公开旧包，不能作为普通恢复步骤。

## 故障与回退

网站／推荐选择错误可使用已冻结内容恢复；已有 APK 退役时必须保留退役策略，不能直接重启公开旧包。已升级的 Android 不支持用更低 versionCode 普通覆盖安装；客户端回退应将已知良好代码构建为更高 versionCode，并用同一长期密钥发布。不要要求用户卸载清数据解决发布错误。

当前中继仍使用已验收部署，App 默认配置已包含地址，普通用户不需要看到或填写。另行迁移中继域名时须保持服务身份与已有设备记录并验证配对；不能只替换字符串后假定迁移完成。
