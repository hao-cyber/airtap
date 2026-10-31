# Android 多 AI 候选检查

此目录覆盖候选协议，不代表已发布版本或设备验收。主机入口：

```sh
JAVA_HOME=/path/to/jdk python3 tools/check.py --suite core --install-deps
```

- `test_multi_ai_control.py` 执行完整 `TrustedGrant`、`TaskLease`、`MultiAiProtocol`，并原样提取 `PreviewService` 的 fence、命令权限、固定 claim 与远端交集逻辑。Android UI、网络、文件 drain 是可控替身；JSON 传输用对象映射，不测试 JSON 字节解析。
- `test_task_lease.py` 用单调时钟覆盖租约、续租水位、重连、持久化失败与过期。
- `test_multi_file_scopes.py` 原样提取文件权限修改和执行计数，覆盖共享 SAF URI、各 grant epoch、响应超时与底层操作完成。实际 provider 与 Handler 使用替身。
- `test_approval_recovery.py`、`test_owner_revocations.py`、`test_enrollment_record_limits.py` 分别覆盖持久同意／单邀请／停止、固定 generation 的全停重试、加密记录容量与失败保留。
- `test_drag.py` 运行实际触控执行器，并以 Android pointer contract 替身确认 ACK 所需的 quiescent 不能由取消请求或错误回执代替。

原 single 分支仍执行既有回归；本地 workerd 的协议测试独立验证真实 HTTP/WS。它们不能代替 Android 系统授权、实际 Accessibility 手势、SAF provider、后台生命周期、多 AI 真实任务及新用户体验。

当前故障边界：若系统未确认 held pointer 的释放，服务保留非 quiescent 状态并拒绝新的任务 ACK，不能把它报告为自动恢复成功。原有 AI token、claim 与命令序号在显式开启多 AI 后保留；成功 task fence 之后永久要求任务租约。旧任务、命令、观察和分享不会随 socket 重连恢复。
