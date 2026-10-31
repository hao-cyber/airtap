# 多 AI 授权协议草案

状态：候选协议，等待实现与验收。本文不表示当前公开 APK、中继或任何手机已经支持多 AI。

目标是一台手机保留多份独立的个人 AI 授权，执行时按完整任务互斥。新增 AI 不更换已有 AI 的凭据，不解除已有配对。默认整机授权继续适用，Airtap 不在两个 AI 之间提供 Android 账号或屏幕内容隔离。

## 1. 版本、类型和边界

- HTTPS 基址仍是现有 `/d/{device_id}`，设备身份、服务身份、签名身份及 enrollment 不变。
- 外层 `protocol_version` 保持 `4`。发现接口新增 `multi_ai_v1`、`task_lease_v1` 能力；旧响应字段及原单授权路径保留。不能只靠服务器声明能力推定手机支持。
- 每设备最多 **4 份已批准授权**；唯一待处理邀请占一个名额，故 `approved_count + (invitation != null ? 1 : 0) <= 4`。取消或过期的邀请释放名额。这个上限限制状态体积，不限制整机可操作 App。
- `H32` 是 32 位小写十六进制字符串，`H64` 是 64 位小写十六进制字符串，`U4` 是 UUID v4。标识、邀请及凭据均由安全随机源生成，不能使用时间戳或名字生成。
- `I` 是 JSON 正整数，且不超过 `9007199254740991`；`I0` 可为零。布尔值不能当整数，非有限数、额外请求字段及错误类型均拒绝。名字为 1–80 个 Unicode 码点，无控制/格式/代理项字符。
- 未注明的 JSON POST 请求上限为 4096 字节；命令沿用现有 8192 字节上限（含租约字段）；响应上限沿用原动作定义。仅接受 `application/json`。所有接口禁止 query 和 fragment，返回 `Cache-Control: no-store`。
- 以下示例中的 `<H32>` 等是类型占位符，不是真实凭据。权限与身份只来自 Bearer 及本机持久化批准记录；JSON 中的名字不是身份依据。

## 2. 状态与兼容模式

服务内增加 `multi` 状态，旧 v4 单授权状态在手机显式启用前维持原有行为。启用后状态包括：

```text
schema_version = 2
mode = "multi"
device identity / generation / serviceRetired             # 原值保留
grants: claim_id -> { hash, name, state:"approved", sequence, seen,
                     lease_required, lease_ready, scope }
invitationSequence: I0                                  # durable high-watermark; never reset
invitation: null | { id, sequence:I, origin:"legacy_v4"|"multi_ai_v1", ticketHash, expiresAt,
                     candidate:null|{hash,id,name,code,capabilities} }
task: null | { claimId, taskId, leaseId, epoch, state:"fencing"|"ready"|"releasing",
               expiresAtMs, operationId, renewalId:null|H32, renewalSequence:I0,
               connectionId, fenceEpoch, fenceTtlMs }
taskEpoch: I0
closedTasks: 最近最多 32 个 {claimId,taskId,leaseId,epoch}    # 幂等释放/避免误重开
```

`seen` 保持每 grant 最近 200 个命令 ID；sequence 持续递增，不因切任务、断线、迁移、再次批准同一 claim 而清零。token 仅保留摘要。设备的停止世代与任务 epoch 分开：添加 AI、邀请过期、撤销非执行中的 AI不提升全局 generation。

单授权兼容规则：

1. 旧手机不调用启用接口，旧 CLI、WebSocket `hello` 和单授权请求照旧。
2. 新手机启用后，把旧批准记录迁为唯一 grant，`lease_required=false`、`lease_ready=false`；旧 token、claim、scope、sequence、seen 原样保留。
3. 这个唯一 grant 在没有有效任务租约时可以继续原命令格式；一旦它成功完成首次租约握手，就原子地设置 `lease_required=true, lease_ready=true`，以后所有手机命令都必须带该任务租约。status、反馈和撤销不要求租约。
4. **创建第二份邀请要求每个已有 grant 的 `lease_ready=true`**，且当前手机已经完成本协议 WebSocket hello。单纯上报客户端版本/能力不满足要求。否则返回 `409 client_upgrade_required` 或 `409 device_upgrade_required`，不改现有授权。
5. 新邀请仅接受声明 `task_lease_v1` 的申请者。新批准 grant 的 `lease_required=true`，首次任务握手前 `lease_ready=false`。仅迁移前已经存在的 legacy 邀请可以继续接受原三字段申请，并在没有其他 grant 时产生兼容的唯一 grant（required/ready 都为 false）。因此不支持租约的 CLI 不能成为第二个可操作控制器，也不能走旧请求绕过任务锁。
6. 新模式下旧 `POST /v1/device/pairing` 和旧 `POST /v1/device/approve` 返回 `409 multi_api_required`，不得换代、清旧 grant 或断连接；旧控制端 `/v1/pair/status`、`/v1/status`、`/v1/revoke` 保持可解析响应，作用于该控制端自己的 grant。全停仍用设备接口。
7. 已启用多授权的状态不能自动降回旧 schema 或复原旧备份。旧服务或旧 APK 不理解新状态时明确拒绝；不能为了回退恢复已撤销授权或旧 sequence。

## 3. 设备管理接口

下表全部使用现有设备 Bearer。成功状态码为 200，未列出的字段不得由客户端提交。

| 方法与路径 | 请求 JSON | 行为 |
| --- | --- | --- |
| `POST /v1/device/multi/enable` | `{"capabilities":["multi_ai_v1","task_lease_v1"]}` | 幂等迁移为新模式；不授予任何新控制权限。返回下述设备状态。 |
| `GET /v1/device/grants` | 无正文 | 读取新模式状态，包含全部已批准 grant 和唯一邀请；不会从远端状态自动生成本机同意。 |
| `POST /v1/device/invitations` | `{"invitation_id":"<H32>","invitation_sequence":1,"ticket":"<H32>"}` | 手机先持久化两个新随机值及大于服务端水位的 invitation_sequence，再请求创建；服务仅保存 ticketHash，期限为首次接受时刻起 300 秒。同 id/hash 的重试返回原期限，不延长；不同内容不覆盖待处理邀请。 |
| `POST /v1/device/invitations/cancel` | `{"invitation_id":"<H32>","invitation_sequence":1}` | 仅取消相同 id/sequence 邀请。无邀请且 sequence 不高于水位时幂等成功；存在不同邀请时 `409 stale_invitation`。不影响已批准 grant 或活动任务。 |
| `POST /v1/device/grants/approve` | `{"claim_id":"<H32>","allow_tap":false,"allow_control":true,"allowed_packages":[],"persistent":true,"all_apps":true}` | 仅批准当前、未过期 candidate；已有同 claim、同 scope 幂等成功，保留 sequence。必须配合手机本地真实核码批准及 approval fence。 |
| `POST /v1/device/grants/revoke` | `{"claim_id":"<H32>"}` | 单独撤销该 grant；不存在时幂等成功。立即拒绝其新命令，执行中的任务进入停止/交接 fence。保留其他 grant。 |
| `POST /v1/device/revoke` | `{"expected_generation":I0}` | multi 全停：仅匹配当前 generation 时原子撤销全部 grant、邀请、租约并提升 generation、关闭旧 WS；较低世代幂等返回当前水位且不碰后来授权，较高世代 `409 generation_mismatch`。返回 `{"ok":true,"generation":I}`。原设备 enrollment 保留，不自动恢复。旧 single 无正文行为保持，multi 无正文不能绕过门禁。 |
| `POST /v1/device/unenroll` | 无正文 | 终止当前 enrollment，撤销所有 grant/邀请/租约并释放登记；registry 持久保留 retired/closing 身份，原 device_id/token 不能重新登记。重试只清理同一终止身份，不能作用于其他新 enrollment；不使用可重复的新 generation 代替此永久退役 fence。 |

`enable` 与 `GET grants` 的响应固定为：

```json
{
  "protocol_version": 4,
  "mode": "multi",
  "generation": 0,
  "max_grants": 4,
  "invitation_sequence": 0,
  "grants": [
    {"claim_id":"<H32>","name":"Example AI","state":"approved",
     "command_sequence":0,"lease_required":false,"lease_ready":false,
     "persistent":true,"all_apps":true,"allow_control":true,"allowed_packages":[]}
  ],
  "invitation": null
}
```

`invitation` 非空时为 `{"invitation_id":H32,"invitation_sequence":I,"state":"waiting"|"pending","pairing_expires_at":I,"candidate":null|{"claim_id":H32,"name":string,"verification_code":六位数字字符串}}`。不返回 ticket 或控制 token。邀请创建响应为 `{"protocol_version":4,"invitation_id":H32,"invitation_sequence":I,"pairing_expires_at":I,"expires_at":null,"cli_sha256":H64,"agent_sha256":H64}`；元数据缺失须在创建前拒绝。手机从自己持久化的 ticket 组成邀请，未知 HTTP 结果只重试同一 id/ticket/sequence；不得因丢响应自增 sequence。只有开始新的明确邀请时才读服务水位并递增。

创建时已经有不同邀请返回 `409 invitation_pending`；满额返回 `409 grant_limit_reached`；除当前仍有效且 id/hash/sequence 完全相同的幂等重试外，`invitation_sequence <= invitationSequence` 一律返回 `409 stale_invitation`，包括过期、取消、批准和全停后。水位持久化且不清零；有限 ID 缓存不能替代这道门禁。新邀请使用新 ID，不复用旧 ID。

批准响应保持现有 scope 字段：`protocol_version,ok:true,claim_id,session_expires_at:null,allow_tap:false,allow_control:true,allowed_packages:[],persistent:true,all_apps:true`。批准不隐含连接成功或任务成功。取消和撤销响应为 `{"protocol_version":4,"ok":true}`。

单撤销不等待外部 AI 同意。若被撤销者持有任务，服务先持久化撤销并发 fence，安全接管前任何其他动作不准入；设备离线时撤销仍有效，但不能声称手机已确认停止。该接口响应追加 `device_stop_confirmed:boolean`，用于区分服务端撤销和设备确认；不把网络失联伪装成完成。

## 4. 控制器申请和状态

新模式 `/v1/pair` 请求：

```json
{"ticket":"<H32>","controller_token":"<H64>","name":"Example AI","capabilities":["task_lease_v1"]}
```

ticket 只允许申请一份候选，不能控制手机。同 ticket、同 token、同 name、同 capabilities 幂等；不同内容返回 `409 ticket_already_claimed`。新模式下没有 capabilities 的旧申请返回 `409 client_upgrade_required`。旧单授权模式仍接受原三字段请求。

申请与 `/v1/pair/status` 沿用 `state,claim_id,verification_code,pairing_expires_at`；批准后返回原 scope 字段及 `lease_required`、`lease_ready`。新模式 `GET /v1/status` 使用控制器 Bearer，仅返回自身授权，保留 `deviceConnected,pending,session_expires_at,allow_tap,persistent,all_apps,allow_control,allowed_packages,command_sequence,connection_id,connected_at,last_seen_at,command_budget_remaining`，并新增：

```text
claim_id: H32
mode: "multi"
lease_required: boolean
lease_ready: boolean
task: {state:"idle"|"busy"|"fencing"|"ready", task_id:H32|null,
       lease_id:H32|null, lease_epoch:I0|null, expires_at_ms:I|null,
       renewal_sequence:I0|null}
```

其他 AI 持有任务时只返回 `state:"busy"`，其余 task 字段为 null，不泄露该 AI 的姓名、任务名或标识。ready/fencing 返回本人的续租水位（从 0 开始）；恢复客户端必须读取服务端水位，不能猜 0。本人任务还在 fencing 时不返回可控制的 lease_id，HTTP 状态不能冒充设备确认。

`POST /v1/revoke` 无正文，仅撤销调用者自己；已被删除的 token 返回 `401 unauthorized`，不得重配或重新申请来“恢复”。撤销响应丢失时，通过原 token status 被拒绝确认其不再可控，不自动重放手机任务。设备机主接口才有全停权。

## 5. 任务租约 HTTP 接口

控制器 Bearer 解析为不可变 claim 上下文，客户端不提交 claim_id。租约时长固定 120000 ms；服务从发送 fence 的时刻计算到期，不从 ACK 收到时延后。续租使用新 operation id，同一重试不延长原到期。命令准入时至少剩 20000 ms，否则要求续租。

| 路径（POST） | 精确请求 | 成功与幂等 |
| --- | --- | --- |
| `/v1/tasks/acquire` | `{"task_id":H32}` | 空闲且设备握手就绪时建立 fencing 记录。设备 ACK 后返回 ready；同 claim/task_id 重试返回同 lease，不延长。其他任务返回 `429 device_busy`。最近已关闭 task_id 返回 `409 task_closed`。 |
| `/v1/tasks/renew` | `{"task_id":H32,"lease_id":H32,"lease_epoch":I,"renewal_id":H32,"renewal_sequence":I}` | 仅本人当前有效租约；持久化新 renewal intent，设备确认新期限后成功。更高 renewal_sequence 才接受为新 intent；等于水位且 renewal_id 相同仅返回/继续同 intent，低于水位或同水位不同 ID 返回 `409 stale_renewal`，不能延长。水位与 intent 原子持久化。续租不改变 holder/lease_epoch，不清当前快照。 |
| `/v1/tasks/release` | `{"task_id":H32,"lease_id":H32,"lease_epoch":I}` | 停止接收本任务新命令，发送清空 holder 的新 epoch fence；设备确认后进入 idle。同已关闭 lease 重试返回 released；不能释放别人的租约。 |

acquire/renew 成功响应：

```json
{"protocol_version":4,"ok":true,"state":"ready","task_id":"<H32>","lease_id":"<H32>","lease_epoch":1,"expires_at_ms":1800000000000,"expires_in_ms":119900,"renewal_sequence":0}
```

其中时间值仅为合成结构示例，实际以服务计时为准。release 成功响应 `{"protocol_version":4,"ok":true,"state":"released"}`。HTTP 等待 fence 最多 15000 ms；超时返回 `503 task_fence_pending`，保留同一 intent，不产生可控制租约，也不重放动作。CLI 可查询状态/重试完全相同的控制请求；只能在取得 ready 后继续。

租约不是后台任务队列，不保存未来点击，不按旧截图自动恢复。到期、断线、连接替换或全停使旧租约失去执行资格；旧 holder 的迟到动作拒绝。任务重新取得资格后必须重新观察。服务不能确认设备已结束旧操作时保持 fencing/offline，不能仅凭 TTL 让新 AI 与旧动作并行。

DO hibernation 不能把每次思考间隙都变成断任务：只有持久化的 ready 租约与同一仍存活 WS 的序列化 attachment（已确认的 lease_id、epoch、到期时间、operation_id、renewal_sequence）完全匹配且未过期时，才允许恢复该租约。状态/attachment 任一缺失、未完成命令或 fence 结果未知时，不能猜 ready，需新 fence 确认；不重放原动作。ACK 后须先完成状态和 attachment 记录，再返回 ready；中途崩溃默认未就绪。

## 6. 设备 WebSocket 与最后一道 fence

继续使用设备 Bearer 连接 `/v1/device`。新模式手机先读取 `/v1/device/grants`，与本地已批准记录取交集；远端存在而本地没有同意的 grant 永不执行。服务返回的缺失/撤销项不能由手机自动重建。

新模式只接受首条结构化 hello：

```json
{"type":"hello","protocol_version":4,"capabilities":["multi_ai_v1","task_lease_v1"]}
```

服务响应 `{"type":"hello_ack","protocol_version":4,"mode":"multi","connection_id":H32}`；这个 ACK 后连接才有租约握手资格。单授权旧模式保留原字符串 `hello`；新模式收到旧 hello/未知 schema 关闭连接，不猜测支持。无已批准 grant 时不得开启控制通道；首次批准后再连接。

服务发送 fence：

```json
{"type":"task_fence","operation_id":"<H32>","lease_epoch":1,
 "holder":{"claim_id":"<H32>","task_id":"<H32>","lease_id":"<H32>"},"ttl_ms":120000,"renewal_sequence":0}
```

释放、到期、当前 grant 撤销时 holder 为 null 且 ttl_ms 为 0；移交/释放使用更大的 epoch。续租的 epoch 和 holder 不变，但 operation_id 不同。手机只接受当前 WS 世代，拒绝小于已见 epoch 的请求；同 operation id + 相同字段幂等返回原结果，不重新延长本地期限；同 id 不同字段拒绝。首次 acquire 的 renewal_sequence 为 0，续租沿用 HTTP 持久水位，清空 holder 为 0；手机同 epoch 只接收相同 holder 且严格递增的水位，完全相同 intent 重试不延长。有限 operation_id 缓存不能替代水位。

手机处理新 epoch 的顺序：使旧命令授权失效 → 清掉属于旧任务的快照/节点与分享读 fence → 取消可取消动作，并确认旧动作/文件 IO 已结束或被末端 fence 阻止 → 验证新 holder 有本地有效授权 → 持久化必要停止/授权 fence，安装新易失任务上下文 → ACK。新期限从首次收到该 intent 时用本机 monotonic clock 计算，持久化等待不能额外延长期限；持久化完成后再次核验期限，不能用手机墙钟比较服务的绝对时间。同 epoch 的续租只允许完全相同 holder 且本机旧租约仍有效；仅确认新期限，不清快照、不撤当前命令，不能用续租复活已过期任务。

ACK 精确结构为 `{"type":"task_fence_ack","operation_id":H32,"lease_epoch":I,"ok":true}`，或 `{"type":"task_fence_ack","operation_id":H32,"lease_epoch":I,"ok":false,"error":"command_in_progress"|"grant_unavailable"|"stale_fence"|"storage_failure"}`。只有匹配当前 WS、operation、epoch 的成功 ACK 才使 relay 的 task 变 ready；同时令该 grant `lease_ready=true, lease_required=true`。失败和未知 ACK 不可被当作成功。

如果旧动作尚未安全结束，手机先撤执行资格但返回 `command_in_progress`；服务保留禁止新动作的 fencing 状态，同 intent 可重试，手机完成后才 ACK。不能发送“成功 ACK”然后在后台继续旧 UI 操作。断线时手机即时作废本地租约，任务上下文不跨进程恢复；重连后新的 lease_epoch 才可执行；重连前的同 intent（包括 null holder）不能在新连接 ACK 为当前 fence。

手机发起 multi 全停前读取并持久保存该 intent 的 `expected_generation`；网络重试始终使用原值，不能重新读取新世代替换旧 intent。成功回执的 `generation` 必须高于该 intent 的预期值；这只确认旧停止已处理，不表示后来新授权也停止。服务写入失败不得提前返回较高水位或成功。

## 7. 命令与独立序列

新 CLI 的 `/v1/command` 请求是现有严格动作字段加 `lease_id:H32, lease_epoch:I`。例如：

```json
{"id":"00000000-0000-4000-8000-000000000001","action":"foreground","sequence":1,"lease_id":"<H32>","lease_epoch":1}
```

服务验证本人 grant、ready lease、期限、单 pending、sequence、重复 ID、quota 后，先持久化该 grant 的 sequence/seen 再发设备。忙碌、错误租约及额度拒绝的 `admitted:false` 请求不更新服务 sequence；客户端自己的已用序列可以跳号，不能倒退。

设备收到的 transport envelope：

```json
{"type":"command","claim_id":"<H32>","lease_id":"<H32>","lease_epoch":1,
 "command":{"id":"00000000-0000-4000-8000-000000000001","action":"foreground","sequence":1}}
```

服务从 Bearer 注入 claim_id，绝不转发客户端指定的授权身份。设备验证本机 grant、lease、当前 WS、期限和 sequence，持久化该 grant 的 sequence 后，把去掉 sequence 的原动作 JSON 交原严格执行器；每次真正触控、节点 action、启动 App、文件写入及持续分享读取前再检查同一上下文。响应保留现有 JSON/binary image 格式，由单 pending 的 claim/lease/epoch/命令 ID 校验归属；不能把 A 的响应发给 B。

所有手机命令（包括读屏、文件和 ping）属于任务。单授权兼容 grant 的原格式只在无任务且 `lease_required=false` 时允许，并固定映射为唯一 grant；仍发送带明确 claim_id 的 command envelope，但 lease_id/lease_epoch 为 null，只有旧 single 模式继续 raw command；不能在任何另一个任务期间偷跑。成功首次租约握手后没有退回无租约控制的隐式路径。

MCP 新增 `phone_task_acquire`、`phone_task_renew`、`phone_task_release`，参数与 HTTP 相同；原动作工具新增相同租约字段，返回当前 grant 的 next_sequence。任务 begin 到最终核验覆盖多个工具/CLI 进程；不能每个 RPC 自动 release 后宣称任务互斥。CLI 私有配置保存自己的 task/lease，状态丢失先查 status，不推测 token 或复用他人的文件。

## 8. 错误和不确定结果

错误均为 JSON，至少含 `protocol_version:4,error:string`。手机命令在未准入时附 `admitted:false`；准入后丢响应沿用 outcome unknown 语义，不能附伪造的 false。`retry_after_ms` 仅用于 busy/fencing，范围 100–15000。

| HTTP | error | 处理 |
| --- | --- | --- |
| 400 | `invalid_request` / `invalid_command` | 修改请求，不重发原动作。 |
| 401 | `unauthorized` | 凭据不再有效；不重新配对绕过。 |
| 403 | `phone_approval_required` | 候选未批准，本次手机动作未执行。 |
| 409 | `multi_api_required` / `client_upgrade_required` / `device_upgrade_required` | 完成对应工具升级，不替换已有配对。 |
| 409 | `grant_limit_reached` / `invitation_pending` / `stale_invitation` / `stale_claim` / `ticket_already_claimed` | 查对应状态，不破坏其他授权。 |
| 409 | `lease_required` / `lease_mismatch` / `lease_renew_required` / `task_closed` / `stale_renewal` | 取得本人有效 ready lease 后重新观察；旧动作不得盲重放。 |
| 409 | `generation_mismatch` | 全停预期世代高于服务当前水位；核对本地持久意图与设备身份，不自动改成新世代重试。 |
| 409 | `sequence_already_admitted` / `request_already_admitted` | 沿用原查询结果/重新观察语义。 |
| 410 | `pairing_expired` / `expired_or_revoked` / `lease_expired` / `device_unenrolled` | 当前邀请/授权/租约已失效，不能自动复活。 |
| 429 | `device_busy` / 现有 quota 错误 | 未准入；按 retry_after_ms 等待、查状态，不保存陈旧点击排队。 |
| 503 | `device_disconnected` / `task_fence_pending` / `storage_unavailable` / `artifact_metadata_unavailable` | 未取得安全执行条件；保留有效授权和同 intent，失败不转成功。 |
| 504 | `device_timeout` | 命令可能执行；保持未知结果，不自动重放。 |

取消/全停/单撤销接口不受控制暂停或额度耗尽阻止。其他请求的 quota 继续按整台设备聚合，四个 AI 不获得四倍额度；鉴权失败不能消耗别人的额度。

## 9. 迁移、文件和恢复不变量

- 服务端迁移一次原子保存：只有 approved 旧 candidate 变有效 grant；pending/waiting 变邀请；revoked/expired 保持不可控；sequence/seen/原 token hash 不变。迁移失败保留旧状态且报错，不写半个 schema。

| 旧服务状态 | 迁移结果 |
| --- | --- |
| `approved`、persistent、未退役 | 一条兼容 grant，claim/hash/name/scope/sequence/seen 原样；无邀请，无任务。 |
| 未过期 `waiting` | 一个 `origin:legacy_v4` 邀请，首次迁移生成并保存 invitation_id；原 ticketHash/期限不变，不生成 grant。 |
| 未过期 `pending` | 同上，原 candidate 的 hash/id/name/code 原样；capabilities 为空，不视为已经支持 lease。 |
| `idle`、`revoked` 或已过期邀请 | 空 grant/邀请/任务；不因旧 candidate 仍留在状态中而复活。 |
| serviceRetired 或结构不完整/未知 schema | 拒绝启用，不创建可控身份；返回明确错误。 |

迁移 legacy 邀请时，手机从 GET 状态获取新 invitation_id；本地既有 ticket 不改变、不记录到日志。该例外只服务于迁移前已存在的邀请，所有新建邀请必须是 `multi_ai_v1`。
- 手机加密授权采用可辨识 schema；可信记录大小读写均限制为 16 KiB（enrollment 仍用原限制）。最多四份 grant 和单 pending，每个 approval fence 独立；全局 stopped 标记优先于任何残余密文。同意 B 的写入失败不得删除 A。停止与晚到审批回调的竞争保持 fail closed。
- 手机本地批准只由正常 UI 核码建立；relay grant 列表不能替代该同意。不同设备 identity 的旧记录不能迁入当前 enrollment。
- workspace、SAF 授权、分享票据、故障报告按 claim 绑定；设备 owner 能管理所有，控制器仅能访问自己的。文件清理使用有效 grant 集合，不把另一个已批准 AI 当旧连接。已有复制给外部 App 的字节无法追回，保持原披露。
- 单撤销移除对应 grant 的未来权限；通知栏全停作废所有本机 grant、任务和 pending 回调。服务端与手机任一处 stopped/revoked 都不能由重连自动解除。
- 正常断线可以自动恢复原授权连接；旧任务的动作及快照不能自动重放。新任务握手完成后再观察决定下一步。

## 10. 本候选必须验证

合成双控制器验证：原 single 迁移、原凭据/序列不变、第二邀请能力门禁、4 份上限、邀请重试/丢响应/取消/过期、A 授权不被 B 审批故障影响、单撤销与全停、独立 sequence/反馈/文件、任务竞争/续租/幂等释放、手机 ACK 缺失/错误/迟到、旧 epoch/WS 响应、到期时未结束动作、设备/DO 重启、未知命令结果、暂停/额度下仍可撤销。

旧 v2 preview 和 v4 single 测试保留。新手机/中继/CLI 编译与合成检查通过后，才进行保留原配对的候选升级及 Muse、Instinct 分别从真实环境执行与交接；不得用 USB 代做成两个 AI 成功，也不得为了验收撤销正在使用的旧配对。公开状态与发布制品需另行核验。
