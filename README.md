# Airtap

把闲置安卓手机，交给你正在用的个人 AI。

Airtap 让你的个人 AI 查看手机屏幕、点击、输入和切换 App。你在熟悉的 AI 对话里交代任务，AI 在手机上执行，再检查结果。Muse、Instinct 已有不同版本的实际任务测试，具体范围见下文。

手机可以留在家中，保持供电和联网。日常使用不需要插 USB、常开电脑或自建服务器，也不用另外购买模型 API；操作仍会消耗你原有 AI 的套餐额度。

**[下载 Android App](https://airtap.memtrix.dev/install)** · [安装与接入](https://airtap.memtrix.dev/install#start) · [使用帮助](https://airtap.memtrix.dev/help) · [自行部署](docs/reproduce-v040.md) · [反馈问题](https://github.com/hao-cyber/airtap/issues)

Android 14+ · 公开测试版 · Muse、Instinct 已有实测

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

第一次发给 AI 的是 **App 生成的接入短句**，里面有邀请码和地址。安装、接入和日常使用都无需 USB 或电脑；普通用户不用运行命令或配置 MCP。

使用其他 AI 时，[让它先检查当前入口的工具能力](https://airtap.memtrix.dev/install#ai-check)。网页不会自动替 AI 验证通过；普通聊天模式也不会因为收到邀请就获得操作能力。

连接后，交给它一件完整的事。例如：

> 打开手机浏览器查三个值得去的西湖景点，整理到便签。保存后重新打开，确认景点和来源都在，再告诉我保存在哪里。不要覆盖原内容。

让 AI 使用手机已有的同类 App，完成后核对实际结果。配对成功只是接上手机；这件事做完才算体验过 Airtap。下次回到原来的 AI 对话继续用。

## 能做什么

- **看屏幕**：获取截图、界面文字、当前 App 和窗口，分段读取长文本。
- **操作界面**：点击、长按、滑动、按住拖动、多指手势，以及返回、回桌面和切换 App。
- **编辑文字**：输入、选择、复制、剪切、粘贴，移动光标和触发搜索等键盘动作。
- **处理文件**：上传、下载和续传工作文件，把指定图片保存到手机相册，或交给其他 App 打开。

AI 可以组合这些动作完成跨 App 任务，不限于计算器等演示应用。具体能否完成，取决于 App 页面、账号权限和手机系统。[完整能力与测试记录](docs/atomic-capabilities.md)

## 当前支持情况

官网推荐 **0.4.0-beta.48（code78）**，目前提供 Android 客户端。Muse 和 Instinct 已分别保留原连接远程升级；两台魅族 Note 16 / Android 15 有跨 App、双 AI 交接、短时息屏恢复和系统选图实测。一台还完成了无声录屏与文件回传。各记录注明实际版本和条件，不能由一次成功推断所有设备都可用。

- 系统保护的画面、按钮和身份验证仍有边界。普通弹窗应先由 AI 看清并尝试正常操作。
- Instinct 已完成实体拍照、短录音回传、账单笔记保存重开，以及一次真实通知触发的手机任务；轮询进程中断后恢复，未重复执行同一事件。[查看实际范围与失败记录](docs/results/beta48-code78-notification-settings-20261113.json)。
- 这不保证所有 App 通知都能捕获。实卡 SIM PIN、多日无人值守和纯移动网络完整任务仍有未验项；通知与 SIM PIN 功能默认关闭。
- 更多机型、其他 AI 和独立新用户首装尚未验证；目前没有 iOS 客户端。

[完整能力与验证范围](docs/atomic-capabilities.md) · [code75 两位 AI 实测](docs/results/beta45-code75-personal-ai-20261112.json) · [更新说明](release/android/78.json)

<details>
<summary>查看具体任务与恢复记录</summary>

| 记录 | 实际范围 |
| --- | --- |
| [code74 系统选图](docs/results/beta44-code74-personal-ai-picker-20261112.json) | Muse 将图片选入 Gmail；Instinct 在信息 App 查看附件预览，均未发送。 |
| [code64 两位 AI 交接](docs/results/beta34-code64-two-ai-20261110.json) | 两机互斥交接，90 秒息屏后再次使用；准备阶段有维护者操作。 |
| [code65 持续操作与文件](docs/results/beta35-code65-muse-20261110.json) | 持续触控、任务保存、文件往返与短时息屏恢复。 |
| [code66 连接管理](docs/results/beta36-code66-personal-ai-20261111.json) | 电池状态和已有 AI 代办新增连接。 |
| [code51 短时断网](docs/results/beta21-code51-network-recovery-20261102.json) / [60 分钟观察](docs/results/beta21-code51-idle-20261103.json) | 原连接恢复与供电状态下的限定观察，不代表多日后台通过。 |

部分图库会隐藏小图，可检查过滤项或使用文件／分享入口，见[实际排查](docs/results/beta39-code69-files-20261111.json)。无声录屏每次需要系统确认，成品时长差异见当前版实测。上述旧版本记录保留其原范围。

</details>

## 连接与控制

链路是 **你的 AI ⇄ 中继 ⇄ 手机**。手机主动连接中继，因此 AI 和手机不用在同一个 Wi-Fi 下。默认公共中继运行在 Cloudflare Workers 与 Durable Objects，也可以[自行部署](docs/reproduce-v040.md)。公共中继由项目维护，有运行成本。

有效配对会保留，正常断网后会尝试自动重连；建立连接长时间没有响应时也会自动重试。恢复后让 AI 先查看手机状态，再继续任务，避免重复操作。你可以在手机上随时停止或撤销控制。系统要求密码或生物识别时，Airtap 不能绕过；系统强制停止 App 或关闭权限后，可能需要你重新开启。

连接使用 HTTPS，**目前不是端到端加密，中继能够接触经过它的画面与操作内容**。默认不保存截图和操作正文，会保存配对及必要连接信息。请只把邀请交给自己的 AI。[权限与隐私说明](SECURITY.md)

## 开发

代码分为 [Android 客户端](apps/android/)、[中继服务](relay/service/) 和 [Python CLI](cli/airtap.py)。接入提供 HTTPS、CLI 和远程 MCP，AI 可选择自己能用的方式。

- [构建与自托管](docs/reproduce-v040.md)
- [运行检查与每夜构建](docs/checks.md)
- [体验评审与用户测试方法](docs/design/product-experience.md)
- [AI 接入与命令说明](docs/agent.md)
- [发布与更新](release/README.md)

## 参与与反馈

想关注后续进展，可以 Star 本仓库；使用反馈、机型实测和代码改进都欢迎。报告问题时，请写清手机型号、Android 和 Airtap 版本、使用的 AI、想完成的任务，以及实际卡在哪一步。改代码前请阅读 [产品与体验约定](docs/project-conventions.md)，并运行与改动相应的检查。

[GitHub Issues](https://github.com/hao-cyber/airtap/issues) 是公开的，请勿上传邀请、连接配置、账号信息或私人截图。需要私下排查时，可以让已连接的 AI 提交脱敏故障报告，在手机「设置 → 问题反馈」查看处理状态；不需要注册 GitHub。

采用 [Apache-2.0](LICENSE) 许可证，依赖见[第三方声明](apps/android/app/src/main/assets/THIRD_PARTY_NOTICES.txt)。
