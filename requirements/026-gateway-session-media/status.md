# 026-gateway-session-media 状态

## 2026-11-27（Asia/Shanghai）

- 日期：2026-11-27（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `026-gateway-session-media`（网关会话内图片、语音和视频的上传、取回与 IM 壳展示或播放）尚未整块完成。今日开评独立核对了仓库中的 `review.md`、`spec.md`，并查找 `tdd.md`、`results/`、`deploy.md` 和 changelog，同时核对 GitHub 上的 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19)、JaSAgent 群与领队 1:1。

- Hang 于 2026-11-26 13:20:42（Asia/Shanghai）在群里确认「026开工」（seq 751），并于 13:20:53 指定「去掉隧道放到027」（seq 752）。领队随即确认：026 先做；去掉隧道（Funnel / Tailscale）的代码和文档清理放到 027，不并进本轮。
- `review.md`、`spec.md` 已写入并合入 master [`8a1dff58a2dbed9ce034a5213b4a42ad806be398`](https://github.com/xuhang113/JaSAgent/commit/8a1dff58a2dbed9ce034a5213b4a42ad806be398)（2026-11-26 13:30:46 Asia/Shanghai，父提交 [`52d9380e`](https://github.com/xuhang113/JaSAgent/commit/52d9380e6b530a343a80445d3821600c1e670753)）。该提交只新增上述两份文档（+140/−0），没有产品代码或 `deploy/k8s`。领队于 13:47 核对称与已锁定口径一致，宣布 SDD 通过，并请实现按 spec 开 PR（seq 759–760）。
- 实现于 2026-11-26 15:06:44（Asia/Shanghai）打开 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19)（标题「feat(026): 网关会话附件留在网关和已有 MySQL」，头分支 `cursor/gateway-session-media-b324`，头提交 [`35ac67bb1e8e710e5abd86e6a75ca048b4384154`](https://github.com/xuhang113/JaSAgent/commit/35ac67bb1e8e710e5abd86e6a75ca048b4384154)，base 为当时的 master `8a1dff58`）。28 个改动文件只在 `jasagent-gateway`、`jasagent-web`、`jasagent-persist`、[`changelog/2026-11-26-gateway-session-media.md`](https://github.com/xuhang113/JaSAgent/blob/35ac67bb1e8e710e5abd86e6a75ca048b4384154/changelog/2026-11-26-gateway-session-media.md) 和 `scripts/test-026-gateway-session-media.sh`，未改 `deploy/k8s`、sidecar、agentpass、otel 和官方集群脚本。产品代码尚未合入 master。实现在 PR 正文中声明本地 `bash scripts/test-026-gateway-session-media.sh` 通过、前端 `npm test` 65 项通过；这不能代替正式 ATDD 回写。
- 代码review 于 2026-11-26 15:16:17（Asia/Shanghai）对头提交 `35ac67bb` 提交了 COMMENTED 审查（[review 6069930204](https://github.com/xuhang113/JaSAgent/pull/19#pullrequestreview-6069930204)），结论为通过，并写明不合并。领队于 15:20:02 在群里核对称后宣布代码审查通过，并请测试写 tdd、跑 ATDD（seq 768–769）。部署须等 ATDD 全部通过后再开清单 PR；在此之前不合入 #19。
- 今日复核：master 上 `requirements/026-gateway-session-media/` 仍只有 `review.md` 和 `spec.md`，没有 `status.md`（本文件为首次写入）、`tdd.md`、`tests/`、`logs/`、`results/` 或 `deploy.md`。PR #19 头提交同样没有这些 ATDD 或部署回写文件；changelog 只存在于该 PR 分支，尚未进入 master。

本次开评核对时（2026-11-27 约 07:20 Asia/Shanghai），master 仍为 [`8a1dff58a2dbed9ce034a5213b4a42ad806be398`](https://github.com/xuhang113/JaSAgent/commit/8a1dff58a2dbed9ce034a5213b4a42ad806be398)。其后没有新的 master 提交。PR #19 仍为 open、非 draft、未合并，可合并状态 clean，头提交仍是 `35ac67bb`，没有前移，更新时间仍为 2026-11-26 15:16:18（Asia/Shanghai），没有 issue 评论，也没有 check runs。没有新的 026 ATDD 分支或部署 PR。唯一另外未关闭的 PR 仍是无关的 [#3](https://github.com/xuhang113/JaSAgent/pull/3)。

JaSAgent 群检索索引 `topped_through_seq` 为 769，与当前最大序号一致。最新帖仍是 2026-11-26 15:20:02 领队请测试开始 ATDD（seq 768–769）。索引在 2026-11-26 23:56:36（Asia/Shanghai）仍有写入（其他对话），其后没有新的群消息，也没有测试回写 ATDD 结果，也没有部署开 PR 的报告。领队 1:1 最新仍是 2026-11-26 07:23:24 的每日开评（seq 47）；Hang 对 026 的开工确认是在群里作出的，1:1 在那条开评之后未见新的用户回复。

上一件已整块完成的是 `025-otel-jaeger-mvp`：ATDD T-001 至 T-008 通过，部署第 9 到第 13 条通过，官方探测于 2026-11-24 20:27:19 为 `PROBE PASSED`，已合入 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 与 [PR #18](https://github.com/xuhang113/JaSAgent/pull/18)。024 整块和 019 仍维持 2026-11-24 按 Hang 放弃集群外走查的正式关闭；公网入口不能写成可用。这不等于 Funnel 已经测过，也不把去掉隧道并进 026。

### 今天没有开评的原因

026 已确认并开工，但尚未整块完成：实现 PR 未合入，正式 ATDD（`tdd.md` / `results/`）尚未提交，部署尚未开始。堵住点是测试还没有按已确认 spec 写 tdd、跑 ATDD 并把结果写回仓库。一轮只推进一块，因此今天不开 027（去掉隧道），也不再让调研另交候选。下一棒仍是先完成 026 的 ATDD、合入和部署验收，而不是开新评。

已锁定口径不变：本轮做会话内图片、语音和视频的上传、取回与 IM 壳展示或播放；模型看图听音放到之后的运行时轮次。附件由网关自己处理，不能粘到 agent。登录以 MySQL 会话为准；绑定租户、会话和所有者，不能只凭附件 id 读写，也不能经 `/admin/cluster` 或 `/admin/jaeger` 旁路。优先落到已有 MySQL，不新建库；若用共享存储，两个就绪副本都要能读写，不能只挂一块普通 RWO 盘，也不能用进程内存或 emptyDir。类型用允许名单（视频不要用宽泛的 `video/*`，排除 SVG/HTML/XML），超限拒绝且元数据和内容都不落库。必须走网关、用现有登录 Cookie 实际上传并取回同一份字节，再在 IM 壳展示或播放；跨副本要从不是接收上传的那一个就绪副本取回。未登录上传和下载须 401 且 `errorCode` 为 `AUTH`；租户隔离要有并发覆盖，非所有者按 020 约定拒绝，不能靠猜测附件 ID 读到内容。下载和播放仍带登录 Cookie，不加公开直链。ATDD 不依赖集群附件卷或 Funnel。实现已开 PR，仍不动 `deploy/k8s`，待 ATDD 全部通过后再由部署开清单 PR。现在仍不合入 #19，也不直接推产品代码到 master。Funnel 不并进本轮，去掉隧道定为 027；公网入口不能写成可用；019、025 约定和 018–025 验收不缩小。界面只用 `jasagent-web`。

请测试尽快按已确认 spec 写 tdd、跑 ATDD，并把结果写回仓库。部署等 ATDD 全部通过后再开清单 PR。

### 今天有没有开评

没有开评。原因见上：026 已开工但尚未整块完成，正式 ATDD 未提交，PR #19 未合入，部署未开始。
