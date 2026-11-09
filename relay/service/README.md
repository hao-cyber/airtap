
### Durable Object 代码更新

部署工具显式传入 `--durable-objects-code-update-mode immediate`，避免新 Worker 调用仍运行旧版的后台对象时缺少新增 RPC 方法。2026-11-10 的短码首发曾在默认延迟更新窗口内遇到此错误；原持久记录未删除，窗口结束后公网检查通过。Cloudflare 默认可延迟更新至 300 秒，详见[官方代码更新说明](https://developers.cloudflare.com/durable-objects/deployments/durable-objects-code-updates/)。

立即更新会重置活动对象，可能中断请求和 WebSocket；这不撤销持久配对，也不保证无中断。安排在已授权维护窗口，更新后核对实际公网入口和原连接恢复；结果未知的手机操作不得重放。Wrangler 必须支持上述参数，不支持时先更新部署工具，不静默删去参数。冻结状态记录实际使用的更新模式。部署成功和源码摘要一致仍不能代替公网功能核验。
