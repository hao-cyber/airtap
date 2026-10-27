# 可审计的源码分发

源码导出使用 `tools/source-package-files.json`，与生产源码、测试和构建依赖一起维护。以下操作只读明确列入的工作树文件，不读取提交历史，也不发布仓库或文件。

## 维护允许清单

```sh
python3 tools/source_manifest.py --check
python3 tools/source_manifest.py --write-candidate /private/next-source-allowlist.json
```

检查按已声明的当前生产和测试目录枚举实际文件，并核对 `tools/check.py` 的主机／协议脚本。它覆盖 Android 主代码与资源、Gradle 构建输入、CLI、中继、网站与发布工具、测试夹具及停用的 CI 参考文件。新增类、原生资源或测试漏列时失败，不能以“打包成功”掩盖依赖缺失。这是明确范围的文件闭包检查，不声称能自动理解任意新工具的全部依赖；新增目录或外部资源位置需要调整 `tools/source_manifest.py` 的策略并审阅。

`--write-candidate` 仅输出新提案，不覆盖生效清单。它将声明范围内新增 UTF-8 源文件加入提案，从 Gradle/CLI 读取版本，并保留原有已审阅文档与二进制条目。它不会自动批准新的二进制、删掉旧条目或沿所有文档链接抓取历史证据。审阅差异、更新版本对应的文档和结果条目后，再把提案保存为 `tools/source-package-files.json`。新二进制必须单独审阅、记录固定 SHA256 及依据；默认只保留构建所需的已审查 Gradle wrapper。

当前 beta.8/code38 与 CLI.8 的闭包须包含分页／长文本生产类及 observationpages 夹具、workspace 分享 Provider 与独立接收 App 夹具、SAF 暂存回收及文件并发／epoch 检查，以及证书策略和公开证书夹具、通知／首次接入恢复、邀请摘要绑定与实际 v4 协议测试。它们位于已声明的生产和测试目录中，不能只复制 APK 使用的主类而漏掉复核依赖。新增[分页元数据结果](results/paged-metadata-host-20261027.json)、[code36 正常首用记录](results/beta7-code36-clean-install-20261027.json)等文档证据仍须单独审阅并加入允许清单；生成器不会自动批准结果文件或沿链接补齐私人材料。

导出的源码包不带原始手机截图、聊天、维护回执、APK、签名材料、部署状态、依赖缓存或 `.git`。历史技术结果可保留经过检查的摘要，必须保留当时的版本、失败和人工帮助范围；不把摘要说成包内附有所有原始证据。生产文件、测试代码、资源和 AI 接入说明不能被导出器改写以通过扫描。

## 预检、冻结与验证

```sh
python3 -m unittest discover -s tools/tests -p 'test_*.py'
python3 tools/package_source.py --output /private/source-preflight
python3 tools/package_source.py --freeze --output /private/source-freeze
```

预检也会在内存生成两次确定性 ZIP、检查成员和权限、实际安全解压并核对摘要，但不保存发行 ZIP。`--freeze` 另外输出 ZIP、manifest、逐文件校验清单和验证报告；若已有同名冻结制品内容不同则拒绝覆盖。输出目录不要放进源码、推荐下载站或 Git 跟踪范围。

运行导出时保持工作树稳定。各生产文件和已审查二进制会在收集后重新读取核对，检测混合修改；构建和验收绑定到具体提交仍是发布流程的责任。工具为每次导出生成 `SOURCE-MANIFEST.json`、`SOURCE-PACKAGE.md`、`SOURCE-OMISSIONS.md` 和 `SHA256SUMS`；这些文件随对应制品保存，不放在持续变化的源码树中充当校验清单。

当前规则精确保留 `https://airtap.memtrix.dev` 及列出的无凭据公开页面，不放行其他部署子域、Worker/Tunnel 主机，也不放行带查询凭据、用户信息、端口或伪装后缀的地址。私钥、明显 Bearer 凭据、维护者路径以及 JSON 中已知的配对／凭据字段仍然拒绝；文档和历史摘要的指定地址／路径可产生有记录的脱敏副本。启发式扫描不能保证识别所有私人信息，新增内容仍需人工审阅。

解压 ZIP 后，进入源码根目录：

```sh
shasum -a 256 -c SHA256SUMS
python3 tools/source_manifest.py --check
python3 tools/check.py --suite core --install-deps
```

Linux 可用 `sha256sum -c SHA256SUMS`。检查环境和可选 Android 编译见[代码检查说明](checks.md)。这些命令不需要私有导出适配器、发布密钥、Cloudflare 登录或真实手机；首次构建仍需要下载固定的公开依赖。源码包不会提供维护者签名私钥，源码 ZIP 的确定性不代表重建 APK 与维护者签名包逐字一致。

使用源码者不需要运行 `package_source.py`，正常构建与自托管不依赖维护者的验收原件。最终网站、APK、源码包和各份验收记录应按实际提交／制品摘要对应。当前推荐 code38／CLI.8 增加证书验证、邀请校验信息和连接准备恢复；[模拟器升级与恢复](results/beta8-code38-recovery-20261027.json)、[Muse WPS → 便签回归](results/beta8-code38-muse-20261027.json)分别记录实际范围。code36 正常首用、code37 Muse 补验继续绑定其实际版本。源文件、夹具或文档变化后，须审阅允许清单并重新冻结／解压检查。

导出检查、主机／协议测试、维护者模拟器首用、实体手机及个人 AI 是不同证据。code37 Muse 已有限通过原配对、自动唤醒和长文本／便签任务，[补验](results/beta7-code37-muse-followup-20261027.json)又核实 WPS 小文本显示、小红书搜索与部分详情、抖音结果列表及新便签保存重开；抖音详情未完成，不能据此宣布全部任务或 code38 通过。演示视频未获用户认可，暂缓交付，也不放入本轮源码包作为已验收成果。通过源码检查不等于这些验收全部通过。
