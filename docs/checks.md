# 可复现代码检查

`tools/check.py` 是本地验证入口。它运行主机上的真实生产 helper、受控 Android 平台替身、本机 workerd/SQLite、CLI 和 MCP 测试；可另行调用 Android SDK 编译和 lint。新公开仓库另有下述 Android 每夜构建；历史上因账单限制无法启动的 `Reproducible checks` 继续停用。**检查通过不是实体手机、OEM、Muse/Instinct 或普通用户首用验收，也不证明发布 APK 已验签。** 这些证据仍单独记录。

## 本地运行

准备 Python 3.12+、Node.js 22+（包含 npm）、JDK 17+；`JAVA_HOME` 指向实际 JDK。停用的云端模板记录 Node 22、Temurin 17、Python 3.12，当前不执行。主机 Java 测试以 `--release 17` 编译。无需模型 key、Cloudflare 登录、SSH、手机、USB 或正式签名。

```sh
python3 tools/check.py --suite core --install-deps
```

`npm ci` 使用仓库既有的 `relay/worker/package-lock.json`，禁用生命周期脚本及 audit/fund，不改依赖锁。平台 workerd 二进制随锁定的可选依赖安装。首次需要访问 npm 官方包源；随后可不带 `--install-deps` 复用当前工作树的依赖。npm 用户/全局配置使用独立空文件，测试子进程不继承签名、Cloudflare、GitHub 或 SSH 凭据变量。

可单独运行：

```sh
python3 tools/check.py --suite host
python3 tools/check.py --suite protocol --install-deps
python3 tools/check.py --suite android
```

`android` 还需要 Android SDK，设置 `ANDROID_HOME`，并按 SDK 许可安装 `platforms;android-36` 和 `build-tools;35.0.0`。它使用项目校验过分发 SHA 的 Gradle wrapper，执行 `:app:compileDebugJavaWithJavac :app:lintDebug`；没有 release 打包、签名或上传步骤。`--suite all --install-deps` 依次运行全部检查。项目内已有 SDK 路径配置也可供本地 Gradle 使用。

输出默认写到忽略提交的 `dist/checks/`，可用 `--output-dir` 指定独立目录。`summary.json` 记录运行时版本、每项退出码、耗时和证据范围；测试遇到失败或超时立即返回非零，不用跳过失败伪造成功。测试报告是合成数据；不要把真实手机内容或凭据替换进公共测试。

## 实机验收中的观察工具

真实个人 AI 任务或演示录制进行中，不运行普通 `adb shell uiautomator dump`，包括带 `--compressed` 的调用，也不使用隐式 `getUiAutomation()`／`connect(0)` 的旁路观察工具。它们默认可抑制其他无障碍服务，导致 Airtap 服务解绑、连接中断及随后重绑；`--compressed` 只影响节点输出，不能避免这一干扰。见 [Android UiAutomation 标志说明](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)和 [AOSP dump 实现](https://android.googlesource.com/platform/frameworks/testing/+/refs/heads/main/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)。

- 维护者观察画面使用 `screencap`／`screenrecord`，状态使用低频 `dumpsys`／`logcat`；这些工具不经过上述无障碍抑制路径，但仍有运行开销，须记录采集方式和时段。
- 需要节点树时，由受测个人 AI 通过 Airtap 的 `read_tree`／`observe` 获取，不由维护者旁路工具代替真实 AI 观察。
- 独立测试夹具确需 UiAutomation 时，必须显式使用 `getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)`，并记录驱动、标志以及点击、权限、网络或进程操作等干预。该标志不代表所有观察开销为零；正在进行的真实 AI 验收中不临时安装或改造测试驱动。
- 已受观察工具干扰的片段保留失败、时间和人工续接记录，单独标明干扰，再录制新的干净片段。不能把旧片段称为无干扰最终演示，也不能仅据这一机制断言所有断线或卡住都由观察工具引起。

维护候选 code46 增加显式只读诊断入口：`adb shell dumpsys activity io.airtap.preview/.MainActivity --airtap-diagnostics`。它只输出连接模式、迁移阶段、授权数量、服务状态和设备地址摘要，不输出原始地址、凭据、核码、AI 名称或界面内容，也不执行连接、恢复或授权操作。存储读取不确定时返回未知，不修复记录。该入口依赖已授权的 USB 调试和现有 Activity，仅用于维护；日常使用不需要。地址摘要仍可关联同一设备，诊断记录保持私有，对外分享须审核。状态字段和本地按钮阶段不替代个人 AI 的真实任务回执。

维护候选 code47 在 `activity` 中增加 `button_touch_events`、`button_touch_rejected` 和 `button_touch_last_flags`：分别记录当前 Activity 的普通按钮收到的触摸事件数、被原有安全判断拒绝的次数及最近事件的数值标志。计数只在内存中，Activity 重建后归零；不记录坐标、目标名称或文字，不改变遮挡触摸保护。拒绝数包含原有遮挡检查和基类安全检查；没有新增事件只能说明未到达这些按钮的过滤回调，不能单凭它确定输入在哪一层丢失。计数的是触摸事件，不是点击次数，也不代表业务动作已执行。它不覆盖对话框或多 AI 页自己的按钮；无诊断参数时保持原系统 dump 行为。

维护候选 code48 在主界面、多 AI 管理页和问题反馈页的普通按钮上补充页面内提示：按下事件因遮挡被拒绝时，明确告知“本次点击未执行”，请先收起音量面板或悬浮窗后重试。触摸提示与原连接、请求或删除状态分开保存，下一次合法点击回调开始时才清除旧提示并执行原动作一次，避免在按下与抬手之间改变按钮布局；问题反馈页会滚到提示处。滑动或取消不会清除提示，也不补执行动作。提示不改变原安全判断，不自动重放点击、不刷新连接，也不新增声音或悬浮层；对话框确认逻辑保持原样。host 检查覆盖原拒绝条件、基类检查短路、仅按下时提示、原业务状态保留及正常动作回调，不代替真机触摸与布局验证。一轮 Flyme 维护实测中，普通收起后仍残留系统音量覆盖窗口，正常重启后遮挡标志清除、点击恢复；重启仅是该故障的维护恢复记录，不是普通用户的接入步骤，也不保证能解决所有遮挡问题。

同轮 Android 模拟器的独立无网络夹具提取上述生产按钮与提示方法，使用真实 View 分派检查遮挡拒绝、正常触摸、移动、取消和提示滚动。初版在反馈页出现按下后按钮位移，19 个案例中 3 个失败；改为点击回调入口清提示后，原 19 个案例、349 项断言通过。它验证这些方法的 View 层行为，不代表完整 APK、Flyme 实际覆盖窗口或个人 AI 验收。

## 当前覆盖

CLI.10 的本地检查范围与未验证项见[动作后观察检查记录](results/cli10-post-action-20261102.json)。

- CLI.10 的动作后观察选择：默认截图，显式 `--after-action tree` 在动作接受后返回当前前台的新树，`none` 与旧 `--no-observe` 保留单动作路径。检查新旧快照、实际换包、TTL、分页、租约续期/失效、参数冲突和锁屏/音量例外；后观察或本地保存失败保留已接受的动作结果，不重放。Python 检查使用实际参数解析、配置持久化和命令分派，替换传输与时间；本地 workerd 集成检查实际 CLI 和合成手机的请求数及顺序。它们不代表真机性能或个人 AI 验收，树模式仍需独立的动作与观察两次设备请求。

- 分页树与长文本的稳定快照、UTF-16 边界和敏感字段遮蔽，以及 range、动作标签/集合、collection 等已导出元数据变化导致旧页失效的 Java helper 检查。
- 输入编辑、触摸计划/执行、语义控件、窗口、音量、文件状态机、授权恢复/调度、反馈 UI、邀请反馈和连接关闭处理的 Java helper 检查；文件检查包含旧 SAF 暂存回收、配对/epoch 变化及接收方读取与其他文件操作竞争的反例。
- 连接生命周期夹具执行生产 `connect`、握手期限、退避、网络替换及停止方法：握手一直不返回时，30 秒后取消该连接并按原退避重试；成功、停止、撤销和网络切换会清理期限，迟到回调不能影响新连接。夹具使用虚拟时钟和传输替身，不证明真实 DNS/TLS 或手机后台恢复表现。
- 当前源码允许清单与生产／测试依赖闭包检查，以及源码导出隐私规则、字节一致性、确定性 ZIP 与不可覆盖的正反测试。
- Android 与 Python 的证书策略检查保留系统信任及主机名验证，并覆盖受影响公钥、不可检查时拒绝联网、代理路径在向目标服务发送凭据前拒绝等情况；不会修改系统信任库。
- 首次接入恢复与邀请摘要检查覆盖原身份／票据保留、旧保存状态缺摘要时拒绝导出、未知结果不重发；通知恢复检查区分暂停、恢复和机主主动停止。
- Python 文件校验、URL、维护报告/部署冻结逻辑和静态下载站检查；部署逻辑仅使用临时目录和合成对象，不调用部署服务。
- 真实本地 workerd 的核心协议/CLI/MCP、文件传输、SQLite 用量迁移/并发、配对恢复和私有反馈协议；手机端是受控连接替身。
- Android SDK 源码编译与 lint；不启动模拟器、不安装 APK、不替代真实手势/文件提供者/断线恢复记录。

实际 v4 邀请摘要检查还可单独运行 `node relay/service/tests/invitation-artifacts.mjs`（先准备上述 Node 依赖）。它核对邀请摘要与实际文件字节，并确认缺失或非法摘要配置不会先签发票据；使用合成身份，不操作手机。

持续任务的自动续租可另外运行下列检查，约需 4 分钟，不加入默认 `core` 的等待时间：

```sh
mkdir -p dist/checks
node relay/service/tests/multi-ai-roundtrip.mjs --active-lease-window --lease-output dist/checks/active-lease.json
```

它使用本地 workerd、真实 CLI 独立进程和合成手机响应：只申请一次租约，每 20 秒发一次 `ping`，持续 205 秒后释放；不显式续租、不缩短期限，要求至少两次自动续租及最终空闲状态。输出仅保留请求类别、结果、期限、序列和源码摘要，不保存身份或凭据。这个检查验证正常网络与时钟下的跨进程续租，不替代 Android 真机或个人 AI 实测，也不能追溯此前缺少记录的异常。

同一中继套件的默认短测还覆盖客户端时钟快／慢 180 秒的真实 CLI 子进程：申请租约后查询状态，核对本地剩余期限，再执行 `ping` 和释放。CLI.12 用服务端相对期限扣除请求耗时，避免跨主机绝对时钟相减；状态查询不延长已知租约，其他 AI 的忙碌状态不暴露期限。

[CLI.12 修复与交付记录](results/cli12-lease-recovery-delivery-20261102.json)保留旧版时钟偏差复现、续租回执丢失后的恢复回归、最终源码检查和公网结果。允许恢复的只是服务端已确认的同一续租意图，下一条新命令前须重试其原标识；不复活已释放任务，也不重放未知结果的手机操作。

Android 执行端另有[code51 原生租约与交接检查](results/beta21-native-leases-20261102.json)：在隔离模拟器中运行未改动的生产 Java，验证同 epoch 续租、空闲过期、旧租约拒绝、手势与文件调用结束前的交接限制。它使用显式协议续租与合成机主设置，不代替个人 AI、实体手机或正常首次接入验收。

新增测试文件后应检查本入口是否需要加入；新增 case 若属于现有 suite 则自动随该 suite 执行。不要把固定历史测试总数当作未来版本证明。

本轮增量的可查证记录：

- [分页与长文本](results/paged-observation-20261027.json)、[分页元数据一致性主机检查](results/paged-metadata-host-20261027.json)。原生模拟器记录使用实际 Android 节点，但测试授权由夹具提供；`core` 不重复运行该模拟器流程。
- [文件分享与回收主机检查](results/file-sharing-cleanup-host-20261027.json)、[真实协议检查](results/file-sharing-cleanup-protocol-20261027.json)和[独立接收 App 模拟器检查](results/file-sharing-cleanup-emulator-20261027.json)。接收夹具源码及人工复核步骤见[shares 测试说明](../apps/android/tests/shares/README.md)；core 中的合成手机不代替 Android ContentProvider、正常分享界面或本地回收操作。
- [code36 正常首用](results/beta7-code36-clean-install-20261027.json)另外验证正常安装器、权限、核码和公共 CLI 的 Clock → Files → Clock 任务；它是维护者模拟器验收，包含人工网络恢复，且 APK 预先由 ADB 放入下载目录。code37 分页补丁、code38 新改动、实体手机与新版 Muse 任务不能沿用该记录宣称全部通过。

以下为 beta.9/code39 与 CLI.8 的历史验收记录：[构建与制品校验](results/beta9-code39-build-20261029.json)及[模拟器恢复、停止与撤销](results/beta9-code39-recovery-20261029.json)分别记录范围。该模拟器驱动已显式使用上述非抑制标志；[Muse 真机任务](results/beta9-code39-muse-20261029.json)另行记录第二轮完成与自行恢复、首轮失败及维护者临时充电常亮等条件，不能互相代替。证书专项证据见 [CLI 与事件复核](results/tls-incident-20261027.json)、[Android 主机及模拟器检查](results/android-tls-20261027.json)；code38 的[模拟器升级与恢复](results/beta8-code38-recovery-20261027.json)和 [Muse WPS → 便签短回归](results/beta8-code38-muse-20261027.json)分别记录实际范围，不能由源码、主机或专项探测代替。code37 的 [Muse 补验](results/beta7-code37-muse-followup-20261027.json)确认 WPS 小文本显示、小红书搜索与部分详情、抖音结果列表及便签保存重开，抖音详情仍未完成；结果保持绑定 code37。

[证书安全维护补充记录](results/security-maintenance-20261027.json)记录服务器 Tunnel 升级、故障报告维护工具的 TLS 修复和两个旧预览入口的兼容资产更新；旧 APK 与已下载 CLI 不会因此自动升级。

视频暂缓交付，测试结果以源码和上述独立记录为准，不以旧演示剪辑证明新版已验收。

[10 月 29 日 Muse 小红书 → 便签记录](results/beta8-code38-muse-20261029.json)补充 code38 的跨 App 结果、短时自主重连和维护者续接提示，也保留稍后等候 60 秒仍离线的结果。CLI 的有界状态探测已有实现；接入指南补齐等待、重新观察与未知动作不重放的流程，Python service 检查覆盖相应故障。实现与测试不等于个人 AI 已独立完成恢复。

[本轮维护与旧包下架记录](results/maintenance-cleanup-20261027.json)记录实际 CI 停用范围、6 个旧公开 APK 退役及线上检查；历史副本和兼容服务保留。

## Android 每夜构建

2026-10-27 用户授权在新公开仓库配置 [`Android nightly`](../.github/workflows/android-nightly.yml)。工作流使用标准 `ubuntu-24.04` runner、Temurin Java 17 和项目 Gradle wrapper，安装所需 Android SDK 后执行 `:app:assembleDebug :app:lintDebug`，生成开发验证 APK 并检查 lint；不替代上面的完整 `core` 检查、手机验收或个人 AI 验收。

- 每天北京时间 **03:17** 调度，对应 UTC 前一天 19:17（`17 19 * * *`），以默认分支代码构建；维护者也可在 GitHub Actions 页面手动触发。
- GitHub 调度可能延迟，高负载时可能丢弃排队任务；公开仓库连续 60 天没有活动时，计划任务可能被自动停用。不能把配置时间当作准时完成的承诺。[调度说明](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)
- 产物仅包含 debug APK、SHA256 摘要与构建元数据；每次上传前检查合计不超过 **20 MiB**，保留 **3 天**，不上传 Gradle 缓存或整个构建目录，不配置 Actions 缓存。
- 标准 GitHub 托管 runner 在公开仓库的计算用量免费；Actions 产物存储仍需关注账号与 GitHub Packages 共享的额度。本配置预期不新增收费，但不保证整个账号账单为零，其他仓库、手动重复运行和额度变化也会影响用量；不自动购买额度或升级套餐。[GitHub 费用说明](https://docs.github.com/en/billing/concepts/product-billing/github-actions)

构建产物在对应运行的 **Artifacts** 中下载，可能要求登录 GitHub。它使用临时 debug 签名，包名与正式版相同，**不能覆盖升级现有正式安装，跨夜开发包也不保证可覆盖升级**。不要通过卸载正式版或清除数据来安装测试包，以免丢失配对和用户数据。该包未预配置公共中继，开发者须自行配置服务；普通用户继续从[官网安装入口](https://airtap.memtrix.dev/install)安装推荐版本。

工作流不持有正式签名私钥、不发布 GitHub Release、不部署中继或网站，也不会切换推荐安装包。云端成功与否以具体运行记录为准；工作流文件存在、本地构建成功都不能代替云端实跑结果。

## 按账单故障停用工作流，本地检查继续

GitHub Actions 功能保持开启。曾有明确 payments／spending-limit 拒绝证据的 `Reproducible checks` 继续停用，仅保留不执行的参考模板 [`tools/ci/github-actions-check.yml.disabled`](../tools/ci/github-actions-check.yml.disabled)。上面的 Android 每夜构建是另行授权的独立工作流，不受这一模板的停用影响。

当前继续使用上面的本地命令完成检查，记录结果及实际验收范围。普通测试失败不能当作账单故障停用；其他工作流须逐项根据实际失败注释判断，不由品牌、runner 名称或 failure 状态推定。恢复这套受账单限制的工作流须先解决运行条件并经机主明确授权，不因支付状态恢复而自动重开，也不擅自购买额度或升级套餐。

本地通过与云端状态分开记录。工作流停用的行为见 [GitHub 官方说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)。这套检查不负责 Airtap 中继、手机客户端或网站的部署。
