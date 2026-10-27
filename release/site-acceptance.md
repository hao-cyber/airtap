# 网站发布验收

此清单验证普通用户拿到的安装入口与说明。Android、个人 AI 和设备任务必须有各自对应的验收记录；页面可打开不代表手机任务已通过。

## 发布前

- 确认本轮推荐 APK 的版本、SHA256、字节数、长期证书和非调试状态来自实际 APK。不要使用开发候选元数据推断实际包。
- 官网、更新 JSON、APK 与退役清单使用同一份冻结构建；`--previous` 指向上一份冻结部署。不要覆盖冻结目录或删除私有历史 APK；明确退役的包从新公开资产中排除，旧 URL 应返回 410 与升级提示。
- 更新 README 和手机使用指南中的“推荐版”，保留旧验收原来的版本与范围。开发中、已实现、已验证和已推荐不能相互替代。
- 保持单一下载按钮。安装包为 `Airtap.apk`；新用户不用挑选 APK、协议、服务或 AI 接入技术。
- 检查首次路径：目标手机打开安装页 → 安装 → 系统操作权限与通知 → 完整邀请发到私人 AI 对话 → 六位码核对 → AI 完成首个任务并返回结果。
- 明确工具型 AI 的要求，不能把“任意普通聊天窗口”写成兼容。网页链接不能替代 App 为设备生成的邀请。
- 明确正常安装和使用无需 USB、常开电脑、自建服务器或同一局域网；纯移动网络、多机型、长期后台等未完成验证的范围保持可见。
- 邀请过期、原连接离线、用户解除连接、系统身份验证分别给出下一步；不要统一指向卸载、清数据或重新配对。
- 不放连接凭据、设备身份、个人聊天、原始日志或尚未通过内容验收的视频。
- 首屏说明产品给谁用、能做什么、安装条件和下一步。普通用户以下载和接入为主；源码、自托管、贡献和公开反馈有明确入口，不与下载争夺主次。
- 宣传中的实际任务链接到对应设备、版本和结果；示例与实测分开，历史录像标原版本。未经过真实用户试用，不宣称转化率、完成时间或任务成功率已经提高。
- 分享标题、描述、canonical、Open Graph 和 Twitter 卡片与页面一致。分享图来自公开品牌素材，不含用户内容；图片进入冻结清单，线上可读取。不同平台的分享卡展示仍取决于平台缓存与抓取策略。
- GitHub 公开反馈与 App 私有反馈分清；不为统计访问加入未经决定的追踪器、Cookie 弹窗或表单。

```sh
node --test release/worker.test.mjs
python3 -m unittest discover -s release -p 'test_*.py'
python3 release/verify_site.py --build /private/frozen-site
```

## 本地浏览器

使用已有 Worker 的本地预览，核对 320、390、768、1440px 宽度及键盘操作。Wrangler 会写入 `.wrangler` 运行状态，因此复制到新的工作目录，保留冻结原件：

```sh
cp -R /private/frozen-site /private/site-preview
wrangler dev --local --config /private/site-preview/wrangler.json
```

1. 首页下载入口、二维码、Android 最低版本清楚，网页没有横向溢出。
2. `/help` 直接到四步接入；`/help#operation-help` 展开系统授权步骤；`/help#pair-help` 和 `/help#offline-help` 能分别直达相应答案。
3. 首次任务可直接复制给 AI，用户知道应看到什么结果、之后在哪里继续提任务。
4. `/help#remote-help` 提醒首次完成后台准备、息屏与断网后用原连接实测，而不是一律要求用户重新配对。
5. `/privacy`、`/release-notes`、版本详情、下载和更新 JSON 均能访问。普通首页没有开发版本选择器。
6. iPhone 暂不提供安装包，不能暗示此 APK 可以装到 iOS。检查新手流程与系统权限实际按钮是否一致。
7. GitHub、源码、测试记录和问题反馈链接指向公开仓库；首页、安装页与 README 互相能到达。二维码解码结果仍是固定安装地址。
8. 分享图在 1200×630 下字形、留白和裁切正确；元数据中的图片 URL、尺寸、替代文字与实物一致。浏览器无脚本错误，Android/iOS/桌面的下载提示分别检查。

## 部署后

部署同样使用新的工作副本，使用冻结原件进行实际公网验收，结果写到目录外：

```sh
cp -R /private/frozen-site /private/site-deploy
wrangler deploy --config /private/site-deploy/wrangler.json
python3 release/verify_site.py --build /private/frozen-site --base-url https://airtap.memtrix.dev > /private/site-http-verification.json
```

脚本检查推荐跳转、全部页面与静态资源、更新 JSON、APK 字节和响应头，包含未退役的旧 APK；对退役地址检查 GET／HEAD 的 410、升级链接及禁止缓存。旧 APK 曾使用长期不可变缓存，需记录 Worker 优先路由及额外响应缓存配置，并现场重查；仅在相应缓存策略需要时执行定向 CDN 清理；不能据此声称已经清除他人的本地下载或浏览器缓存。若失败，保留实际错误，定位部署、缓存或网络问题后再恢复，不应仅因某个浏览器能打开就宣布通过。

另用实际浏览器打开 `/install` 和帮助直达链接。新 APK 还需要正常覆盖升级与对应变更回归；沿用同 APK 的网站修正不要求重复安装。记录 Git 提交、冻结构建摘要、Cloudflare 部署 ID、公网检查以及手机/AI 验收的明确边界。无法招募独立用户时保留“独立普通用户全程试用未验证”，不虚构替代记录。
