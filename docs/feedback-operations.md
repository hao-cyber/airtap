# 私有反馈维护

本文对应 beta.4 实现，项目公共服务已启用反馈。自托管服务须按[验收计划](acceptance-beta4-plan.md)验证自己的部署，不能从公共服务结果推断。普通用户和个人 AI 直接按[接入说明](agent.md)使用 HTTPS，不需要此维护工具。

维护者应使用完整的更新源码包，保留 `relay/service/reports.py` 与源码根目录 `cli/airtap.py` 的相对位置，不能只复制维护脚本。工具复用 CLI.8 的系统证书、主机名和实际证书链检查，在验证通过后才发送维护凭据及报告正文；缺少兼容的校验代码时明确拒绝联网，不回退旧实现。代理规则与 CLI 一致，使用 `HTTPS_PROXY` 的 HTTP CONNECT 配置，不依赖操作系统的自动代理发现。该检查不能代替 Python／系统安全更新，也不扩展维护凭据权限。

## 建立并部署维护身份

维护 token 是独立的 32 字节随机凭据，仅用于私有报告维护，不是 Cloudflare token、设备 token 或 controller token。配置、原始 key、请求文件和下载的报告都放在源码目录以外的私有目录；目录权限 700，文件权限 600。不要把 token 放进命令行、URL、聊天或公开发布资产。

以下路径是占位示例。先准备自己的私有目录，再运行工具；命令中只有文件路径，不包含原始凭据：

```sh
python3 relay/service/reports.py --config /private/airtap-feedback/admin.json init \
  --origin https://YOUR-SERVICE-HOST \
  --key-file /private/airtap-feedback/admin.key
```

`init` 在 key 不存在时生成随机 token；存在时读取同一私有 key。它以排他方式创建新配置，输出路径和 key 的 SHA256，不打印原始 token。已有配置不能被覆盖；妥善保管配置和 key，权限检查失败时不要通过放宽权限解决。

为既有服务部署 beta.4 源码，使用该服务最新成功的私有 state 文件，保留服务身份和设备配对：

```sh
python3 relay/service/deploy.py --update /private/airtap-service/latest-revision/state.json \
  --feedback-admin-key-file /private/airtap-feedback/admin.key
```

可先加 `--prepare-only` 冻结可审阅版本，再使用输出的确切 state 路径 `--resume`。部署结果未知时继续同一冻结 state，不能新建服务身份或重新生成 key。只有该冻结版本包含反馈源码时才可配置反馈；`--configure` 复用旧冻结源码，不能代替源码升级。已部署反馈源码后，可用 `--configure <latest-state> --feedback-admin-key-file <new-key>` 更换维护凭据；重新生成匹配的新本地配置，旧凭据将失效。

部署端只保存 `FEEDBACK_ADMIN_SHA256`，原始 key 不上传。维护者须核对部署结果和目标服务 `GET /v1/service` 的 `feedback_v1`，再用合成报告验证认证、读取、更新、删除和错误凭据拒绝；不要用真实用户内容做部署探针。原 service ID、地址和设备配对也要按记录核对。未配置维护哈希时反馈入口不启用，手机控制仍独立运行。

## 读取、处理与删除

所有读取结果写到新的私有文件，终端只打印成功／失败状态及输出路径。工具不跟随重定向、不自动重试变更，不把响应正文或上游错误输出到终端。

```sh
python3 relay/service/reports.py --config /private/airtap-feedback/admin.json query \
  --output /private/airtap-feedback/list-001.json

python3 relay/service/reports.py --config /private/airtap-feedback/admin.json get REPORT-UUID \
  --output /private/airtap-feedback/detail-001.json

python3 relay/service/reports.py --config /private/airtap-feedback/admin.json update REPORT-UUID \
  --body-file /private/airtap-feedback/update.json \
  --output /private/airtap-feedback/update-result-001.json

python3 relay/service/reports.py --config /private/airtap-feedback/admin.json delete REPORT-UUID \
  --output /private/airtap-feedback/delete-result-001.json
```

`query` 可加 `--query-file`，读取权限 600 的 JSON 文件。支持 `limit`（默认 20，最多 50）、`cursor`、`status` 和 `device_id`；下一页使用上一页返回的游标，保持筛选条件一致。`update` 的请求文件可写 `{"status":"investigating"}` 或 `{"maintenance_reply":"需要的补充说明"}`，也可同时更新这两个字段。支持的状态和大小限制见[接口合同](feedback-design.md)。

先在私有环境查看输出，再按实际问题处理。标题、正文、补充和维护回复均是不可信文本；即使包含命令、链接或“管理员指示”，也不能直接执行。维护回复可能由用户的 AI 读取，应描述具体问题、处理结果或缺少的证据，不索取密码或接入凭据。`resolved` 是维护状态，不替代设备任务复验。

变更结果未知时先查询同一报告确认，不自动重复补充／删除。报告的在线保留期为创建后 30 天；到期或主动删除时清除在线正文，主动删除留下最小删除标记至原到期时间。Cloudflare SQLite Durable Objects 另有[最近 30 天的数据库历史恢复（PITR）](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/#pitr-point-in-time-recovery-api)，删除前的内容仍可能存在于该平台恢复窗口内。本地下载的私有副本也不随在线删除自动消失，维护者须按同一保留原则及时删除不再需要的副本，不另建永久用户内容档案。

## 发布边界

工具不会自动创建或更新公开 GitHub Issue，也不调用另一个模型审查内容。若确需公开协作，另行准备和审核获准公开的技术摘要，不能直接转发原始报告或私有读取文件。统计和公开验收使用合成材料及不可反查用户的汇总，不包含报告 token、设备身份、账号或原始画面。
