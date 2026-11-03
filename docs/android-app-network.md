# 可选：安卓 App 网络分流

Airtap 不要求安装代理客户端。只有手机上的其他 App 需要不同网络出口时，才配置这一项。下面以 **FlClash 0.9.2 的 Android 界面**为例：国内 App 直连，需要代理的 App 进入 VPN，再由规则选择出口。其他版本和手机的菜单可能不同，完成后须在自己的设备上验证。

## 先分清两层设置

| 设置 | 决定什么 | 不能证明什么 |
| --- | --- | --- |
| Android 按 App 排除 VPN | 选中的 App 不进入 FlClash 的 VPN | 不能只凭连接列表里没看到它，就判断已直连 |
| 域名／GEOIP 规则 | 进入 VPN 的请求，按目标域名或 IP 选择出口 | 中国 IP 不等于国内 App；国外 App 也会访问国内 CDN |
| `PROCESS-NAME` 包名规则 | 对识别出的 Android 包名优先选择指定代理组 | 进入代理组不等于最终经过代理节点，组也可能选择 `DIRECT` |

如果只是希望国外服务正常使用，先保留可用的域名／IP 规则。只有某个 App 需要固定走代理时，再加包名规则。不要用一条 `GEOIP,CN,DIRECT` 代替按 App 分流。[Android VPN 说明](https://developer.android.com/develop/connectivity/vpn) · [Mihomo 路由规则](https://wiki.metacubex.one/config/rules/)

## 配置步骤

### 1. 保留可用连接

从 [FlClash 官方发布页](https://github.com/chen08209/FlClash/releases)获取客户端，使用你已有服务提供的配置。订阅链接可能含凭据，不要公开、贴进反馈或交给第三方转换网站。

Android 同一用户通常只能有一个活动 VPN。启动 FlClash 会替换 Tailscale 等已有 VPN；如果原远程控制依赖它，先让个人 AI 通过 **Airtap 原生连接独立完成一次任务**，再安排切换。保留 Airtap 配对，切换后验证同一连接恢复，不通过重新配对掩盖网络问题。旧链路的退役按设备实际用途验收后完成。

### 2. 国内 App 直连

在 FlClash **工具 → 访问控制**启用访问控制，选择**黑名单模式**，勾选需要直连的国内 App，保存。此模式的说明应是“选中应用将会被排除在 VPN 之外”，不要选反。[官方实现](https://github.com/chen08209/FlClash/blob/v0.9.2/lib/views/access.dart)

已经批准并验证 Airtap 直连的设备，将 **Airtap** 也保留在排除列表中。切换后仍须核对原 AI 连接能否完成任务。需要代理的国外 App 不勾选排除；FlClash 运行模式保持**规则**。这时只能说这些 App 进入了 VPN，还不能说它们的所有请求都走代理。

### 3. 必要时，为国外 App 加包名规则

下面使用当前订阅的本地扩展，不修改订阅正文。先确认该订阅已经使用**标准**扩展；如果正在使用脚本或自定义规则，先理解现有配置，不直接切换覆盖它。

1. **工具 → 常规 → 连接 → 查找进程**：打开，对应 `find-process-mode: always`。0.9.2 的开关只有开启／关闭，默认关闭。[官方实现](https://github.com/chen08209/FlClash/blob/v0.9.2/lib/views/config/general.dart)
2. 在**配置**页打开当前订阅的菜单，进入**扩展 → 标准**。点下方 **MATCH-TARGET**，选择订阅中实际存在的代理组，例如 `Proxies`。先核对该组及其嵌套组当前选中的真实节点；`DIRECT` 或无法确认出口时，不能记为代理已生效。
3. 检查是否已有使用 `MATCH-TARGET` 的附加规则：修改这个目标会同时影响它们。若会改变原用途，先调整方案。
4. 在同页**附加规则**点“＋”：**代理类型**选 `PROCESS-NAME`，**内容**填手机上实际安装 App 的准确包名，**分流策略**选 `MATCH-TARGET`，保存，再点**立即应用**。每个需要此策略的 App 一条。

普通“附加规则”表单不能直接选择任意订阅代理组；这里的 `MATCH-TARGET` 会被替换成上一步选中的组，然后放在原订阅规则前面。如果选择“跟随配置”，会使用订阅最后一条 `MATCH` 的目标，它不一定是代理组。[扩展页面](https://github.com/chen08209/FlClash/blob/v0.9.2/lib/views/profiles/extend/standard.dart) · [规则合成逻辑](https://github.com/chen08209/FlClash/blob/v0.9.2/lib/common/task.dart)

可在当前订阅菜单的**更多 → 运行时配置**中，仅核对相关规则是否在前，以及组名是否正确。例如 `PROCESS-NAME,com.example.foreign,Proxies` 中的包名是占位示例，不能原样使用。不要导出整份运行时配置，它可能包含节点凭据。

### 4. 常开与重启恢复

| 位置／设置 | 本方案取值与含义 |
| --- | --- |
| Android 设置 → VPN → FlClash → 始终开启的 VPN | 开启，让系统维护 VPN 服务；仍须实测正常重启后的恢复 |
| 同页“阻止未使用 VPN 的连接” | **关闭**。开启会阻断被排除 App 的直连，和本方案冲突 |
| FlClash 工具 → 进阶配置 → 网络 → 允许应用绕过 VPN | **关闭**。这和明确的 App 排除列表是两回事，不让已进入 VPN 的 App 主动选择绕过 |
| FlClash 工具 → 常规 → 自动运行 | 可开启；它表示打开 FlClash 时启动代理，不能单独作为开机自启证明 |
| 手机的后台／电池管理 | 按厂商正常设置允许 FlClash 和 Airtap 后台运行，不关闭全局安全保护 |

“始终开启”不是“永不掉线”。系统强制停止、权限撤销、首次解锁要求、厂商后台限制及代理节点故障仍可能影响恢复。[Android 始终开启与阻断规则](https://developer.android.com/develop/connectivity/vpn#always-on) · [FlClash 自动运行说明](https://github.com/chen08209/FlClash/blob/v0.9.2/arb/intl_zh_CN.arb)

## 怎样确认配置真的有效

完成配置后，先做一次当前网络检查，再安排一次正常重启，避免反复切换网络：

1. 打开一个被排除的国内 App，完成一个公开内容查询；核对排除列表仍正确。需要严格证明系统分流时，还应检查 Android 的 VPN App／UID 路由，不能只看 FlClash 中没有记录。
2. 在目标国外 App 发起一个新的、无敏感内容的请求。到 FlClash 的**连接统计 → 连接／最近请求**查看对应记录：进程包名、匹配规则和内容，以及代理链最终使用的实际节点。包名规则应显示 `ProcessName` 和准确包名；底层字段为 `metadata.process`、`rule`、`rulePayload`、`chains`。核对出站不是 `DIRECT`，并确认页面结果或传输确实成功。旧长连接可能仍沿用此前出口，不必为验证关闭所有 App 的连接。[连接数据模型](https://github.com/chen08209/FlClash/blob/v0.9.2/lib/models/common.dart)
3. 让个人 AI 用原 Airtap 连接完成一个短任务，确认网络设置没有破坏手机控制。
4. 正常重启手机。若系统要求首次解锁，按正常流程完成并记录这次介入；随后先观察 FlClash 是否自行恢复，再检查 App 排除与规则是否保留。分别重复国内 App、国外 App、Airtap 的实际任务。若手动打开了 FlClash 才恢复，要如实记录，不能写成自动恢复通过。

保留客户端版本、手机系统、测试 App、实际出口、失败和恢复方式即可，不保存订阅、账号、验证码或完整连接日志。完成几个 App 的检查不代表所有 App、所有网络或多日无人值守都已通过。

## Google Play 与能力边界

如果手机原本就有 Google Play、Google Play 服务和 Google 服务框架，先正常检查是否启用、版本更新、账号登录及 **Play 商店 → 设置 → 关于 → Play Protect 认证**，再测试下载。系统里存在这些组件，不等于设备已认证或服务已可用；不要因此安装第三方 GMS 套件、替换系统包或清除已有账号数据。没有官方组件或未认证的设备，按厂商及 Google 的支持路径处理。[Google 设备认证说明](https://support.google.com/googleplay/answer/7165974?hl=zh-Hans)

按包名分流也有边界：FlClash 0.9.2 按连接所属 UID 查包名，共享 UID 时只取第一个包名；隔离进程、系统下载器或 Google 服务代发的请求可能不归原 App。DNS、代理节点连接及局域网流量有各自路径；把 DNS 的 IPv6 选项关闭不等于所有 IPv6 流量都被代理。UDP 节点能力也会影响规则匹配。保留已工作的 DNS／IPv6 设置，再按具体失败排查，不承诺“某 App 的每个字节永远走代理”。[Android 包名识别实现](https://github.com/chen08209/FlClash/blob/v0.9.2/android/service/src/main/java/com/follow/clash/service/VpnService.kt#L46)

网络可达也不保证账号注册、地区资格、节点信誉或某项服务一定可用；这些结果需要在对应 App 正常验证，不能用“VPN 已连接”代替。
