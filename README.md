> 本仓库从已审核的源码包建立干净历史，并作为后续开发主线。历史资料保留其原验收范围；原包校验清单仅验证导入快照，不用于校验持续变化的工作树。详见[公开源码基线](docs/open-source-baseline.md)与[原源码包范围说明](SOURCE-PACKAGE.md)。

# Airtap

**让你的个人 AI，使用你已有的设备。**

从“把闲置手机，变成你个人 AI 的手机”开始。面向不懂技术的普通用户：安装 App，完成手机权限，把邀请交给自己的个人 AI，核对一次数字即可连接。无需电脑常驻、自建服务器、模型 API key 或逐个选择 App。用户决定目标，AI 负责理解任务，Airtap 尽量提供完成任务所需的设备能力。

远程无人值守是核心场景：设备可以留在家中或办公室，不要求用户随身携带。产品不预先限定少数 App、场景或工作流；能力缺口应优先补齐，当前尚未实现或验证的范围如实记录。其他设备是逐类适配与验证的方向，不代表当前手机安装包已经支持。

当前默认链路为：**个人 AI ⇄ Cloudflare 公共中继 ⇄ 手机**。日常使用不依赖 USB、北京或东京服务器、常开电脑或 Tailscale。手机主动连接中继；个人 AI 使用它已有的工具环境。

当前手机公开推荐版为 **Android 0.4.0-beta.8（code38），配套 CLI.8，统一协议 v4**。本版包含证书验证、连接恢复和完整观察／文件操作等改进；继续提供私有故障反馈、按需亮屏、系统允许的锁屏恢复和持续配对。iOS 是同一产品的后续平台，尚未实现和真机验证。beta 系列沿用协议 v4；覆盖更新保留已批准的连接，卸载重装则需要重新准备权限和接入。

[打开手机安装页](https://airtap.memtrix.dev/install)

## 使用方式

1. 在 Android 14 或更新的手机安装预配置 APK，打开后点“开始使用”。
2. 按 App 引导开启手机操作权限和连接通知；系统要求本人处理的安装或权限步骤，在手机完成。
3. 点“邀请我的 AI”，把完整邀请复制或分享给自己的私人 AI 对话。
4. 核对六位数字，点“数字一致，连接我的 AI”。以后正常使用不必重新配对。

正常断网自动重连。新版按实际屏幕状态尝试自动亮屏、退出无需身份验证或系统已允许退出的锁屏；确需系统身份验证时给出明确原因和恢复步骤。因系统身份验证而拒绝的本次设备操作未执行；验证后由 AI 重新观察，再决定后续操作，不会自动补做失败命令。手机或连接通知可以随时停止控制，停止后必须重新接入。无需另外购买模型 API，但原 AI 套餐和服务仍有成本。

beta.3 的魅族 Note 16 维护记录：仅开启厂商后台许可仍可能被暂停；补充 Android 电池“无限制”后，code 26 在 USB 供电下连续息屏 631.9 秒未被冻结。随后从 code 26 覆盖升级到 beta.3 / code 27，未重开 App 即在息屏后台恢复原配对；维护层短断网 6 秒后 10.65 秒内重新连接，当时的 App 更新检查显示已是推荐版本。这些是 [beta.3 的维护证据](docs/results/beta3-remote-recovery-20261026.json)，不描述 beta.4 升级或 60 分钟 Muse 后验，也不代表所有机型、拔线或长期无人值守已通过。

## 新版能力

- 一次批准完整手机操作；取消默认 App 数量、15 分钟会话、200 命令及每日命令数量限制。服务仍有容量和请求速率保护。
- 实际 App 目录、启动应用、网页导航、前台识别、读屏、节点点击／长按／输入／滚动。
- 基于最新截图的坐标点击、长按和滑动，以及返回、主页、最近任务。动作后重新观察结果；beta.4 的滑动不能替代“按住后持续拖动”。
- 配对和命令序号在手机及服务端持久保存，网络或进程恢复不重复执行未知结果的动作。
- HTTPS、Python CLI 和标准远程 MCP 共用协议。目标是不绑定任何个人 AI 品牌；客户端需要能调用工具；可通过界面树观察和确认的任务无需看图，截图定位与坐标操作则需要实际看图能力。MCP 客户端还需支持自定义 Bearer 认证。
- AI 自行选择可用接入工具，普通用户无需选择技术路线。初次配对采用命令或请求，不影响随后通过截图、界面树和输入动作操作手机 GUI。

自 beta.3 起支持将机主提供并授权使用的内容输入应用密码框，以及打开合法 HTTP/HTTPS 网页（包括局域网地址）。密码在界面树中继续遮蔽；应用输入与设备系统身份验证分别处理。这些能力需要包含对应实现的中继与客户端，旧 APK 不据此获得同等能力。

手机系统决定哪些状态需要身份验证、哪些画面受保护；Airtap 应准确区分状态并优先通过平台正常能力恢复任务。AI 不能自行操作 Airtap 的授权界面。Airtap 不提供业务语义审批，个人 AI 应根据用户已经明确授权的任务执行，不重复索要同一确认。

当前推荐下载为 **Android 0.4.0-beta.8 / code38，配套 CLI.8**。已整合连续拖动、多指手势、文本编辑、语义控件、音量、窗口和范围内的双向文件传输，并加入界面树分页、长文本续读、工作文件跨 App 分享及旧连接 SAF 暂存回收。文件仅能访问当前配对的工作目录或机主另行选择的文件夹；分享通过 Android 临时只读权限交给选定接收方，不能据此读取任意手机文件。实现、证据和待补范围见[手机操作能力清单](docs/atomic-capabilities.md)。

code38 新增证书验证加固、邀请内文件校验信息、通知恢复和首次接入恢复修复。网站推荐、APK 和中继制品对应关系见[本轮交付记录](docs/results/beta8-delivery-20261027.json)。已通过 26 步本地整合检查、签名构建、[模拟器覆盖升级与恢复](docs/results/beta8-code38-recovery-20261027.json)，以及 [Muse 原配对 WPS → 便签真机回归](docs/results/beta8-code38-muse-20261027.json)。原始超时、受保护页面恢复与图片附件缺失情况均已记录。历史验收继续绑定原版本；本轮不代表全部 App、个人 AI 或长时间无人值守均已复验。

## beta.4 私有故障反馈

beta.4 已提供个人 AI 直接通过 HTTPS 提交、查询、补充和删除故障报告，以及手机上的报告管理。个人 AI 按用户已有授权选择必要信息并脱敏，Airtap 负责私有接收、权限隔离和在线报告的 30 天到期清理；不会自动上传手机画面或公开到 GitHub。普通用户无需新增插件、GitHub 账号或回到手机重复确认已授权的反馈。

beta.4 已发布到固定下载地址并在公共服务启用反馈，验证内容与范围见 [beta.4 验收计划](docs/acceptance-beta4-plan.md)和对应交付记录。旧 beta.3 安装包不自动获得新版报告管理。使用时以目标服务 `/v1/service` 是否声明 `feedback_v1` 及实际接口结果为准；未启用反馈不影响设备操作。详见[反馈合同与分工](docs/feedback-design.md)和[私有维护操作](docs/feedback-operations.md)。

[手机使用指南](docs/user-guide-v040.md) · [AI 接入说明](docs/agent.md) · [构建与自托管](docs/reproduce-v040.md) · [配对恢复修复与验证范围](docs/client-recovery-v040.md) · [个人 AI 验收](docs/personal-ai-acceptance-v040.md)

## 验证与交付

当前与历史版本的技术证据按层次记录：

| 范围 | 已通过的证据 | 尚未覆盖 |
| --- | --- | --- |
| 界面树分页与长文本 | [主机、实际 CLI/MCP 协议及 Android 模拟器](docs/results/paged-observation-20261027.json)：303 个可见节点、12,000 UTF-16 单位完整回读；[分页元数据补丁](docs/results/paged-metadata-host-20261027.json)有 17 项主机检查及 [6 项原生元数据检查](docs/results/paged-metadata-native-20261027.json) | 实体手机界面树分页尚未触发；Muse 已完成下述分段长文本回读 |
| 文件分享与旧 SAF 暂存回收 | [64 项主机检查](docs/results/file-sharing-cleanup-host-20261027.json)、[17 项实际协议检查](docs/results/file-sharing-cleanup-protocol-20261027.json)、[独立接收 App 与本地回收的模拟器验收](docs/results/file-sharing-cleanup-emulator-20261027.json)；[code37 Muse 补验](docs/results/beta7-code37-muse-followup-20261027.json)确认 WPS 显示 52 字节文本内容 | WPS 未做导出字节往返；魅族便签未导入分享文件，其他接收方和实体 SAF 回收未覆盖 |
| 正常安装、权限与接入 | [code36 首用记录](docs/results/beta7-code36-clean-install-20261027.json)：维护者在全新模拟器走正常安装器、系统权限和核码，经公共中继用实际 CLI 完成 Clock → Files → Clock 并核对结果；[code37 覆盖升级与撤销](docs/results/beta7-code37-upgrade-revoke-20261027.json)保留原配对，原 CLI 再完成 Clock 任务，本地撤销后旧请求均 HTTP 410 | APK 由 ADB 放入下载目录；不代表浏览器下载、独立新用户或 Muse 验收 |

Muse 已有 [code34 的限定原子能力回归](docs/results/beta6-code34-muse-20261027.json)和 [code35 的浏览器到便签任务](docs/results/beta6-code35-muse-demo-20261027.json)。[code37 静音真机回归](docs/results/beta7-code37-muse-20261027.json)已实际通过原配对、息屏后自动唤醒、806 字符长文四段回读及另一份测试便签保存重开。[首次协议获准后的 code37 补验](docs/results/beta7-code37-muse-followup-20261027.json)已确认 WPS 展示 52 字节文本、小红书公开搜索及前八条可见路线详情、抖音两屏六条结果列表，以及 191 字便签保存重开。抖音详情未完成：权限提示未因点击消失，Muse 返回后继续浏览；首轮协议阻碍、魅族便签空白导入和后续失败记录均保留。该轮不能证明 code38 新改动、完整抖音内容或所有 App 均已通过。**演示视频暂缓交付：现有剪辑未获用户认可，不属于本轮已验收成果。**

beta.4 / code 28 的[正式包正常首用](docs/results/beta4-clean-install-20261026.json)、[反馈协议 15 项](docs/results/beta4-feedback-protocol-20261026.json)、[反馈 UI 39 项](docs/results/beta4-feedback-ui-20261026.json)和[公共 HTTPS 13 项](docs/results/beta4-feedback-live-20261026.json)已有记录。首用由维护者在干净 Android 16 模拟器完成，联系人首启权限弹窗有一次人工处理；不是独立普通用户验收。小红保持 USB 供电，实际 Muse 在约 75 分钟静置后通过原配对自动亮屏，完成计算器 48×23=1104 和测试便签保存后重开核对；有一次额外人工任务确认，未由维护工具代操作。结果、失败恢复与证据来源见[真机后验](docs/results/beta4-muse-idle-20261026.json)和[本轮交付验证](docs/results/beta4-delivery-20261026.json)。

beta.3 的构建、协议、屏幕恢复、应用密码框和网址导航等变更回归见[新版能力验证](docs/results/beta3-remote-recovery-20261026.json)。不同版本和设备的验收分别记录。

此前 beta.2 发布阶段增加固定官网、长期发布签名、更新检查与逐步权限引导。机主实际试装暴露了“进入无障碍后不知道下一步”的引导缺口；beta.2 针对这一问题增加按钮前的操作步骤、返回检测和具体帮助入口。模拟器回归与维护者测试不能替代独立普通用户全程通过，当前仍需继续完成这一验收。详见[本轮发布与引导验证](docs/results/release-experience-20261026.json)。 随后机主确认小红覆盖更新后看到了新版分步引导，并到达“邀请我的 AI”；这是机主反馈，尚不等于当时该轮完整 AI 任务验收，见[当时真机反馈](docs/results/release-owner-feedback-20261026.json)。

code 24 原始维护者验收在魅族 Note 16（小红）、Android 15 完成：浏览器下载和安装、持续配对、计算器任务、便签输入后返回列表可见、坐标操作、锁屏及断网恢复、进程退出恢复、停止后重启不恢复。该原始轮次未验证便签保存后重新打开。真机 MCP 图片和防重放验证通过，原冻结源码的构建与 lint 通过。详见[原始验收记录](docs/results/companion-v040-physical-acceptance.json)；该记录及其源码摘要保留原样。

此后补充了 CLI 首次配对中断恢复和接入说明修复，Android 生产源码与原冻结包的 42 个构建输入逐字一致，沿用原 code 24 APK。修复后[配对恢复 7 项](relay/service/results/pair-recovery-v040.json)和[协议／CLI／MCP 8 组](relay/service/results/companion-roundtrip-v040.json)本地集成测试通过；测试使用真实 workerd、CLI 和合成手机，不代表修复后的 Muse／Instinct 整套实测。版本与证据对应关系见[修复说明](docs/client-recovery-v040.md)。

公共中继与下载站的 Cloudflare 403／1010 拦截已通过仅匹配这两个主机的配置修复。原先失败的默认 Python HTTP 请求首次复验即可取得正确的公开资源和 APK，摘要与分发文件一致。对照组保留了一次超时及单次重试成功记录；这不代表所有网络都已验证。详见[下载入口复验](docs/results/cloudflare-v040-bootstrap-verification.json)。

强行停止在该机型会关闭无障碍权限，需要从系统重新开启；普通进程退出无需重新操作。测试中存在网络超时，已保留原始失败，并补充仅在请求发送前重试连接的处理。

在历史 code 24 手机基线验收中，Muse 与 Instinct 均在同一台 Android 15 魅族 Note 16 上通过原样邀请接入、时钟与便签跨 App 任务、保存后离开再重开核对、真实断网后继续读取，以及撤销后旧凭据拒绝的技术验收。手机准备和核码由维护者协助，业务任务由各自 AI 执行。原件、失败、补验及证据边界见[个人 AI 验收](docs/personal-ai-acceptance-v040.md)；这与独立普通用户从零安装是不同的验收范围，也不代表 beta.4 或其他 AI 已复验。旧版记录保留为历史证据，仅描述 code 24。

豆包已检查实际工作入口，但页面要求先登录，尚未进行工具调用和手机任务，见[入口检查](docs/results/doubao-v040-entry-check.json)。任意 AI、其他机型、移动网络完整任务、多日后台、独立普通用户完整复刻及 iOS 仍未验证。

源码采用 [Apache-2.0](LICENSE)，依赖见[第三方声明](apps/android/app/src/main/assets/THIRD_PARTY_NOTICES.txt)。当前推荐安装包为长期发布签名的公开测试版，发布与更新流程见 [发布管理](release/README.md)；此前维护者验收绑定其原预览包；签名私钥、连接凭据和设备原始截图不进入源码交付。
