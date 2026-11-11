# Airtap

把闲置安卓手机，交给你正在用的个人 AI。

Airtap 让你的个人 AI 查看手机屏幕、点击、输入和切换 App。你在熟悉的 AI 对话里交代任务，AI 在手机上执行，再检查结果。Muse、Instinct 已有不同版本的实际任务测试，具体范围见下文。

手机可以留在家中，保持供电和联网。日常使用不需要插 USB、常开电脑或自建服务器，也不用另外购买模型 API；操作仍会消耗你原有 AI 的套餐额度。

**[下载 Android App](https://airtap.memtrix.dev/install)** · [安装与接入](https://airtap.memtrix.dev/install#start) · [使用帮助](https://airtap.memtrix.dev/help) · [自行部署](docs/reproduce-v040.md) · [反馈问题](https://github.com/hao-cyber/airtap/issues)

Android 14+ · 公开测试版 · 需要能调用工具的个人 AI

## 已经做过的任务

[观看 90 秒真机使用演示](https://airtap.memtrix.dev/#demo)：Muse 对话 → 手机执行 → 返回结果。

这段旧版演示中，Muse 在小红书读取两条徒步路线，整理到新便签里，保存后重新打开核对，再回到对话汇报。设备是魅族 Note 16 / Android 15，录制版本为 beta.9（code39），任务由 Muse 通过自己的云端工具执行。[查看这轮结果](docs/results/beta9-code39-muse-20261029.json)

查资料、编辑文字、跨 App 整理内容，都由你原有的 AI 规划；Airtap 提供操作手机的能力。它不要求每个 App 单独提供 API，也不需要你先搭好固定工作流。具体任务仍取决于 AI、App 和手机系统。

## 开始使用

[观看 70 秒安装与接入指南](https://airtap.memtrix.dev/install#install-demo)。真机演示官网下载和系统设置，邀请与核码配合步骤说明。视频使用 beta.9／beta.10 的旧版界面；示例手机已接入，并非首次安装全程实录。

1. 在 Android 14 或更新的手机上安装 Airtap。
2. 打开 App，按引导完成操作权限、通知和后台运行设置。
3. 点「邀请我的 AI」，把八位邀请码告诉自己的 AI。第一次用 Airtap 时，点「复制给我的 AI」一并带上接入地址。
4. AI 返回另一组六位核对码；与手机上显示的一致，就确认连接。

发给 AI 的是 **App 生成的邀请**，不是这个 GitHub 链接。普通用户不需要安装命令行工具或配置 MCP。

连接后，可以试试：“打开浏览器查一下明天的天气，整理到便签里，保存后重新打开确认。”手机需要已有相应 App；首次使用某个 App 时，可能还需要登录或完成它的设置。

## 能做什么

- **看屏幕**：获取截图、界面文字、当前 App 和窗口，分段读取长文本。
- **操作界面**：点击、长按、滑动、按住拖动、多指手势，以及返回、回桌面和切换 App。
- **编辑文字**：输入、选择、复制、剪切、粘贴，移动光标和触发搜索等键盘动作。
- **处理文件**：上传、下载和续传工作文件，把指定图片保存到手机相册，或交给其他 App 打开。

AI 可以组合这些动作完成跨 App 任务，不限于计算器等演示应用。具体能否完成，取决于 App 页面、账号权限和手机系统。[完整能力与测试记录](docs/atomic-capabilities.md)

## 当前支持情况

当前官网推荐 **0.4.0-beta.38（code68）**。AI 可以将工作目录里的 JPEG、PNG、WebP 保存到手机相册，再到目标 App 选图；系统弹窗没有可读控件时，也可尝试看图点击。系统明确保护的页面和按钮仍可能无法操作。[更新说明](release/android/68.json) · [相册任务实测](docs/results/beta38-code68-personal-ai-20261111.json) · [发布核验](docs/results/beta38-code68-publication-20261111.json)

| 个人 AI | 已有测试 |
| --- | --- |
| Muse | code68：两台原连接远程升级、图片上传和相册发布；其中一台在信息 App 选中图片并查看附件预览，随后取消空白草稿，没有发送消息。自然出现的照片权限弹窗也完成了看图点击“拒绝”。 |
| Instinct | code66／CLI.17：由已有 AI 协助接入，使用自己的连接完成计算器任务，并从 Vault 恢复配置；code64 另有两台手机的短信 App、浏览器和再次使用记录。 |

两台魅族 Note 16 / Android 15 曾完成[两个 AI 互斥交接与 90 秒息屏后的再次使用](docs/results/beta34-code64-two-ai-20261110.json)。[code65 实测](docs/results/beta35-code65-muse-20261110.json)覆盖持续触控、任务保存、文件往返与短时息屏恢复；[code66 实测](docs/results/beta36-code66-personal-ai-20261111.json)覆盖电池状态和已有 AI 代办新增连接。各项证据保留原版本与条件。

目前的几个限制：

- 魅族选图器曾漏掉一张 320×240 的小图；同一图片可由文件管理打开。正常尺寸图片已完成选图与预览，具体过滤阈值仍未确定。
- 通知事件与 SIM PIN 输入默认关闭；实际事件投递、实卡 PIN 输入和媒体采集仍待真机验收。录音需麦克风权限，录屏每次需要系统确认。
- 更多机型、纯移动网络完整任务、多日无人值守和独立新用户安装仍待验证。普通息屏唤醒不等于重启后无需验证，手机开启 SIM PIN 时仍须处理系统验证。目前没有 iOS 客户端。

其他 AI 尚未确认兼容。它至少需要能执行网络工具调用；用截图定位和操作时，还需要实际读取图片的能力。普通聊天入口不一定具备这些能力。[AI 接入说明](docs/agent.md)

## 连接与控制

链路是 **你的 AI ⇄ 中继 ⇄ 手机**。手机主动连接中继，因此 AI 和手机不用在同一个 Wi-Fi 下。默认公共中继运行在 Cloudflare Workers 与 Durable Objects，也可以[自行部署](docs/reproduce-v040.md)。公共中继由项目维护，有运行成本。

有效配对会保留，正常断网后会尝试自动重连；建立连接长时间没有响应时也会自动重试。恢复后让 AI 先查看手机状态，再继续任务，避免重复操作。你可以在手机上随时停止或撤销控制。系统要求密码或生物识别时，Airtap 不能绕过；系统强制停止 App 或关闭权限后，可能需要你重新开启。

code51 已完成一次[短时断网恢复](docs/results/beta21-code51-network-recovery-20261102.json)和[供电状态下 60 分钟静置观察](docs/results/beta21-code51-idle-20261103.json)，不代表所有网络或多日后台均已通过。旧版曾因手机原有 VPN 分流影响连接，保留[历史排查记录](docs/results/beta9-code39-network-route-20261029.json)。

连接使用 HTTPS，**目前不是端到端加密，中继能够接触经过它的画面与操作内容**。默认不保存截图和操作正文，会保存配对及必要连接信息。请只把邀请交给自己的 AI。[权限与隐私说明](SECURITY.md)

## 开发

代码分为 [Android 客户端](apps/android/)、[中继服务](relay/service/) 和 [Python CLI](cli/airtap.py)。接入提供 HTTPS、CLI 和远程 MCP，AI 可选择自己能用的方式。

- [构建与自托管](docs/reproduce-v040.md)
- [运行检查与每夜构建](docs/checks.md)
- [AI 接入与命令说明](docs/agent.md)
- [发布与更新](release/README.md)

## 参与与反馈

想关注后续进展，可以 Star 本仓库；使用反馈、机型实测和代码改进都欢迎。报告问题时，请写清手机型号、Android 和 Airtap 版本、使用的 AI、想完成的任务，以及实际卡在哪一步。改代码前请阅读 [产品与体验约定](docs/project-conventions.md)，并运行与改动相应的检查。

[GitHub Issues](https://github.com/hao-cyber/airtap/issues) 是公开的，请勿上传邀请、连接配置、账号信息或私人截图。需要私下排查时，可以让已连接的 AI 提交脱敏故障报告，在手机「设置 → 问题反馈」查看处理状态；不需要注册 GitHub。

采用 [Apache-2.0](LICENSE) 许可证，依赖见[第三方声明](apps/android/app/src/main/assets/THIRD_PARTY_NOTICES.txt)。
