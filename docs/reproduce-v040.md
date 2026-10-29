# Airtap 0.4 构建与自托管

当前生产入口为 `relay/service/service.mjs`、`cli/airtap.py` 和 `apps/android`；统一协议 v4。实验目录和旧版验收记录不是当前接入流程。

本页面向自行构建或部署的人。开发电脑仅用于构建和部署，部署完成后无需常驻：中继运行在 Cloudflare Workers 与 Durable Objects，手机主动连接中继。AI 选择 CLI 时使用自身工具环境中的 Python，不要求机主另外配置电脑、服务器或 Tailscale。直接使用公共服务请按[手机使用指南](user-guide-v040.md)安装。

需要 Python 3.10+、Node.js 22+、JDK 17+、Android SDK 36、构建工具及可用的 Gradle 依赖网络。使用项目 Gradle wrapper；准备 Cloudflare 账户并通过 Wrangler 的正常流程登录。用户使用公共服务无需这些工具或账户。下面各段命令从解压后的源码根目录依次执行。

```sh
cd relay/worker
npm ci
cd ../..
./relay/worker/node_modules/.bin/wrangler login
python3 relay/service/deploy.py --wrangler "$(pwd)/relay/worker/node_modules/.bin/wrangler" --state-dir /absolute/private/deployment
```

部署状态必须在源码目录外，目录仅自己可读。使用返回的 HTTPS 服务根地址构建：

```sh
(
  cd apps/android
  ./gradlew :app:assembleDebug :app:lintDebug -PpublicRelayOrigin=https://YOUR-SERVICE
)
```

`apps/android/app/build/outputs/apk/debug/app-debug.apk` 是开发签名预览包；自己构建的签名通常不同，不能覆盖其他提供者安装包。正式签名通过源码外的四个 `AIRTAP_RELEASE_*` 环境变量提供，详见 `apps/android/app/build.gradle`。不要发布签名私钥。

未知部署结果必须用 `--resume <state.json>` 重试相同冻结状态。更新源码用 `--update <state.json>`，保留服务身份与设备；改运营设置用 `--configure`。默认无每日命令数、请求数或响应字节数配额；可以配置基础设施容量与限速。

普通用户路径见 `docs/user-guide-v040.md`；AI 获取服务提供的 `agent.md` 和 CLI。接入者校验下载内容摘要，完成邀请／核码后保存私有配置。任务结束不自动撤销连接。

接入说明、手机能力与公开下载必须对应同一发布状态。本版 Android APK 为 `0.4.0-beta.9`（code39），配套 CLI.8；增加30秒连接握手期限，超时后保留有效配对并按原退避策略重试，不自动重放操作。实际制品与来源见[构建校验](results/beta9-code39-build-20261029.json)，恢复、停止与撤销范围见[模拟器检查](results/beta9-code39-recovery-20261029.json)。[Muse 本版任务](results/beta9-code39-muse-20261029.json)已在魅族 Note 16 完成小红书读取、便签保存重开和返回 Muse，记录第二轮自行恢复、首轮失败及临时充电常亮条件；不据此宣称自主唤醒或所有网络故障已解决。[code38 交付记录](results/beta8-delivery-20261027.json)和更早记录保留原版本范围。个人 AI 按设备实际返回的能力选择恢复方式，自行使用 CLI、HTTPS 或 MCP，不让普通用户决定技术实现。

使用自定义域名时，还要从实际 AI 的网络检查 `/v1/service`、`/agent.md`、`/cli/airtap.py` 和安装页的下载资源。Cloudflare 的浏览器检查可能拦截普通 HTTP 工具；HTTP 403 且正文为 `error code: 1010` 时，先按[官方排查说明](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1010/)核对 Browser Integrity Check。当前公共服务通过一条 Configuration Rule，仅对中继、原预览下载站和固定官网 `airtap.memtrix.dev` 三个完整主机名设置 `bic: false`；配对、服务端鉴权和 HTTPS 继续保留。自托管提供者需要在自己的域名配置中完成对应检查，不能以关闭整个域名区域的安全保护代替精确配置。Wrangler 的 Workers 部署权限不等于 Configuration Rules 编辑权限。

配置后重新执行曾经失败的原请求，并验证下载文件摘要；自定义客户端标识请求成功，不能代替默认 HTTP 工具的复验。公共服务的这项配置由提供者负责，普通用户无需进入 Cloudflare 控制台。

验证当前协议和 CLI：

```sh
node relay/service/tests/companion-roundtrip.mjs
node relay/service/tests/pair-recovery.mjs
```

第一组测试使用真正的 workerd 和 CLI、合成手机；第二组通过本机网络故障注入检查首次配对恢复，包括请求未送达、响应丢失、已批准、已撤销和身份不匹配。配对测试默认将结果输出到终端，也可用 `--output /absolute/private/pair-recovery.json` 保存报告。两者都不代替 Android 真机验证。真机需要覆盖安装、系统权限、手机确认、节点和截图动作、断线恢复、锁屏恢复及停止控制。必须记录测试工具、人工辅助与实际客户端。

远程使用验收应把息屏、无需身份验证的锁屏和需要身份验证的锁屏分开：状态查询不唤醒；已授权 GUI 请求尝试平台允许的自动恢复；恢复后必须取得新观察，旧截图和节点不能继续执行动作；需要认证与唤醒失败应分别给出具体诊断；停止或撤销后不自动恢复。还要在实际设备上确认任务结果、后台与网络恢复以及需要机主介入的次数。仅在亮屏状态跑通任务，不能计作无人值守通过。

HTTP 401/404 不足以判断邀请已过期。维护者应结合请求的完整设备路径、结构化错误和现有配对状态区分身份不匹配、撤销、过期与未知设备；诊断记录不得含邀请、凭据或未脱敏屏幕内容。

系统身份验证导致设备命令被拒绝时，必须验证该次操作未执行、未入队等待验证后重放；验证完成后由 AI 获取新截图或界面树并重新决定后续动作。操作后的观察失败和未知响应须分开处理，不能据此断言之前的动作未执行。beta.4 已在魅族 Note 16 完成约 75 分钟 USB 供电静置后的实际 Muse 自动亮屏、计算器和便签保存重开任务，见[有界后验记录](results/beta4-muse-idle-20261026.json)。本轮有一次额外人工任务确认，不代表拔线、整夜或多日验收；后台设置入口核对本身也不等于恢复验收。
