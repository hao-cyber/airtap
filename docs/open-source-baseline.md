# 公开源码基线

本仓库于 2026-10-27 从已审核的 `airtap-source-0.4.0-beta.8-code38.zip` 建立干净历史，并作为后续产品开发主线。原包 SHA256：

```text
c9fd36c5e232064513c636ac1e601468d26fc295948ae6e47d2ee64741bb5492
```

基线对应 Android `0.4.0-beta.8`（code38）、CLI `0.4.0-cli.8` 与协议 v4，采用 Apache 2.0。原 ZIP 有 263 个成员，262 条内部文件摘要已逐项核对。旧私有仓库的 Git 历史、原始截图、聊天、凭据及部署资料不导入；已审阅的历史技术摘要保留原版本、失败和验收边界，不代表当前所有设备与个人 AI 都已通过。具体范围见 [README](../README.md)。

## 导入快照与当前工作树

根目录 `SOURCE-MANIFEST.json`、`SHA256SUMS`、`SOURCE-PACKAGE.md` 与 `SOURCE-OMISSIONS.md` 保留原包内容，用于说明导入来源及原始快照。`SHA256SUMS` 仅在原 ZIP 的未修改解压副本中校验；本仓库已包含下列迁移文档调整，此后还会持续开发，不能把原清单当作当前工作树的完整校验或验收结果。当前改动以 Git 提交及对应检查记录追踪。

初始迁移调整以下五个文档与一份打包允许清单，生产代码、测试、许可证及原始快照校验清单不变：

- 新增 `AGENTS.md`：公开开发约定与后续唯一开发主线。
- 调整 `README.md`：合并重复的源码分发提示，链接本基线说明。
- 调整 `docs/project-conventions.md`：移除旧设备预览 Release 链接，说明其未随本次手机源码公开。
- 调整 `docs/checks.md`：将旧私有仓库的账单与 CI 状态明确标为迁移前历史。
- 新增 `docs/open-source-baseline.md`：记录导入来源及以上差异。
- 调整 `tools/source-package-files.json`：显式纳入本说明，维持未列入文档链接和文件的拒绝策略，不放宽隐私扫描。公开 `AGENTS.md` 属于 Git 仓库的开发约定；现有源码 ZIP 导出器仍一律排除同名协作配置，因此本次不将其加入 ZIP 允许清单。

新仓不导入旧 Actions 运行记录，不恢复已停用的 `Reproducible checks`。本地检查与实际设备验收继续分别记录；开源迁移不重建公共中继，不改变手机配对，也不扩张已验证的产品范围。
