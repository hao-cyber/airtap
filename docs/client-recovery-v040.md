# 0.4 配对恢复修复与验证范围

本次修复让首次 `pair-start` 在请求未送达或响应丢失后，保留并恢复同一个配对身份。它修改 CLI、AI 接入说明和自托管命令，不修改 Android 执行端、服务协议或手机授权。

## 中断后如何继续

保留原私有配置，使用同一个服务地址、名称和配置路径重试 `pair-start`，从标准输入再次提供原邀请票据。CLI 先查询原身份的配对状态：

- 仍待批准：返回原核对数字；不要创建第二个身份。
- 已批准：保存批准状态，继续 `wait-ready`。
- 只有明确的 HTTP 401 才使用原身份重发配对申请；中继对相同身份和名称的申请保持幂等。
- 状态查询结果不确定、已撤销、身份不匹配或邀请失效时，不自动创建新身份，也不重放手机动作。

旧版待批准配置若未保存名称，不能使用这条自动恢复路径；原配置保留，可用 `pair-status` 查询。完整接入步骤以 [AI 接入说明](agent.md)为准。MCP 工具本身不完成首次配对；agent 仍需先执行邀请中的 HTTPS／Python 步骤，获准后才能使用支持自定义 Bearer 认证的 MCP 客户端。

## 原始手机证据与本次修复分开记录

原始冻结源码 ZIP 的 SHA256 为 `f87dd3a8d9a79dd662c30bc6a64231d478ea4fee74c3079efa0bf0ac381e136e`。其 [code 24 物理验收记录](results/companion-v040-physical-acceptance.json)及 `source_hashes` 保持原样，不能把旧 CLI 的手机验收改写成新 CLI 已经实测。

本次只读核对确认：原 ZIP 中全部 42 个 Android 生产源码、构建配置和 Wrapper 文件与修复后的工作区逐字一致，当前生产源码目录没有新增或缺失文件。原包之外的历史 `src/test` 文件不计入这 42 个 APK 构建输入。继续沿用 Android `0.4.0-companion-preview / code 24` APK：800953 字节，SHA256 `c9ec221d144a38bf219270f3615ed591e8eb572e15528312c94245c8e2c81152`。这次字节核对不是重新构建或新的手机验收。

原手机验收由维护者通过 CLI／MCP 完成。该轮便签的已验结果是输入后返回列表可见，没有验证保存后重新打开。修复后另行进行了 Muse／Instinct 原样邀请、跨 App 任务、便签保存重开、恢复和撤销，分别记录在[个人 AI 验收](personal-ai-acceptance-v040.md)；不回写原轮次或使用旧版本结果代替。独立普通用户完整复刻仍未验证。

## 可复跑的本地结果

在源码根目录准备 Python 3.10+、Node.js 22+，并在 `relay/worker` 执行 `npm ci` 后运行：

```sh
node relay/service/tests/pair-recovery.mjs
node relay/service/tests/companion-roundtrip.mjs
```

配对恢复测试默认把结果输出到终端；需要保存时可加 `--output ./pair-recovery-result.json`。构建与部署命令见[自托管指南](reproduce-v040.md)。

2026-10-25 的实际结果：

| 测试 | 结果 | 范围 |
| --- | --- | --- |
| [首次配对恢复](../relay/service/results/pair-recovery-v040.json) | 7/7 | 真实 workerd、CLI 子进程与本机故障注入；覆盖请求未送达、响应丢失、批准后恢复、身份不匹配、不确定状态、撤销及旧配置。无手机、云部署或个人 AI。 |
| [协议／CLI／MCP 回归](../relay/service/results/companion-roundtrip-v040.json) | 8/8 | 真实 workerd、CLI、MCP 和合成手机；测试临时提高入口限速以检查超过 200 次命令，不改变生产配置。 |

早期修复候选的 8/8 额外包含一次旧 CLI 故障复现；当前可分发 runner 默认运行 7 个修复后用例，不包含私有旧 CLI 副本。

本次结果绑定以下文件；后续源码包应使用自身清单校验分发字节，而不是沿用原 ZIP 的摘要：

| 文件 | SHA256 |
| --- | --- |
| `cli/airtap.py` | `c9a968076e83d90f10350b1ffc86fe5675facb11bf33939402c162bfa66cc53c` |
| `docs/agent.md` | `749abdc6b4cf3cc8eba965b2eeb6578890c553de3fdf76dea28d457891d06f0d` |
| `relay/service/tests/pair-recovery.mjs` | `c5a296e3c9073df7841aed74a8a95319b36f2d4bdef8bc804f8c4869dc6aec7b` |
| `relay/service/results/pair-recovery-v040.json` | `f3d53227a5227cc12a6ffaa0ca71d4910813d9d5f6d2c021cf187b71eb70b90f` |
| `relay/service/results/companion-roundtrip-v040.json` | `78af91e36b16d89fbce4349e94d3754682bed5a807e32a1243c31bfd2e7e40cd` |

## 线上分发核验

2026-10-25 已在保持原服务身份、密钥、设备登记和运营配置的前提下更新分发文件。随后从维护者 Mac 通过公开 HTTPS 入口下载 CLI 和接入说明，确认下载字节、响应中的 SHA256 与上表一致；分发版本为 `service4-042d0f7f-c9a96807-749abdc6`，下载前后的服务元数据一致。四次请求均在首次尝试得到 HTTP 200。

详见[线上下载核验记录](results/client-recovery-v040-delivery.json)。该检查没有申请配对或操作手机，也不能证明其他地区的网络体验、实际个人 AI 接入或任务执行成功；这些仍需各自的实测证据。
