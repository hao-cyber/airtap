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

## 当前覆盖

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

新增测试文件后应检查本入口是否需要加入；新增 case 若属于现有 suite 则自动随该 suite 执行。不要把固定历史测试总数当作未来版本证明。

本轮增量的可查证记录：

- [分页与长文本](results/paged-observation-20261027.json)、[分页元数据一致性主机检查](results/paged-metadata-host-20261027.json)。原生模拟器记录使用实际 Android 节点，但测试授权由夹具提供；`core` 不重复运行该模拟器流程。
- [文件分享与回收主机检查](results/file-sharing-cleanup-host-20261027.json)、[真实协议检查](results/file-sharing-cleanup-protocol-20261027.json)和[独立接收 App 模拟器检查](results/file-sharing-cleanup-emulator-20261027.json)。接收夹具源码及人工复核步骤见[shares 测试说明](../apps/android/tests/shares/README.md)；core 中的合成手机不代替 Android ContentProvider、正常分享界面或本地回收操作。
- [code36 正常首用](results/beta7-code36-clean-install-20261027.json)另外验证正常安装器、权限、核码和公共 CLI 的 Clock → Files → Clock 任务；它是维护者模拟器验收，包含人工网络恢复，且 APK 预先由 ADB 放入下载目录。code37 分页补丁、code38 新改动、实体手机与新版 Muse 任务不能沿用该记录宣称全部通过。

当前推荐为 beta.8/code38 与 CLI.8。证书专项证据见 [CLI 与事件复核](results/tls-incident-20261027.json)、[Android 主机及模拟器检查](results/android-tls-20261027.json)；code38 的[模拟器升级与恢复](results/beta8-code38-recovery-20261027.json)和 [Muse WPS → 便签短回归](results/beta8-code38-muse-20261027.json)分别记录实际范围，不能由源码、主机或专项探测代替。code37 的 [Muse 补验](results/beta7-code37-muse-followup-20261027.json)确认 WPS 小文本显示、小红书搜索与部分详情、抖音结果列表及便签保存重开，抖音详情仍未完成；结果保持绑定 code37。

[证书安全维护补充记录](results/security-maintenance-20261027.json)记录服务器 Tunnel 升级、故障报告维护工具的 TLS 修复和两个旧预览入口的兼容资产更新；旧 APK 与已下载 CLI 不会因此自动升级。

视频暂缓交付，测试结果以源码和上述独立记录为准，不以旧演示剪辑证明新版已验收。

[10 月 29 日 Muse 小红书 → 便签记录](results/beta8-code38-muse-20261029.json)补充当前版本的跨 App 结果、短时自主重连和维护者续接提示，也保留稍后等候 60 秒仍离线的结果。CLI 的有界状态探测已有实现；接入指南补齐等待、重新观察与未知动作不重放的流程，Python service 检查覆盖相应故障。实现与测试不等于个人 AI 已独立完成恢复。

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
