# 026-gateway-session-media 状态

## 2026-11-28（Asia/Shanghai）

- 日期：2026-11-28（Asia/Shanghai）
- 开评结论：今天开评。本日评审按 Hang 已指定的 027「去掉隧道」（Funnel / Tailscale 清理与公网入口相关工作）进行。请调研先在群里交一个候选，不写文件，并写明该候选落在哪一块（网关 / sidecar / 运行时 / agentpass / K8s）。

### 上一件完成情况

上一件 `026-gateway-session-media`（网关会话内图片、语音和视频的上传、取回与 IM 壳展示或播放）已整块完成，测试通过。今日开评独立核对了仓库中的 `review.md`、`spec.md`、`tdd.md`、`results/`、`deploy.md` 和 changelog，同时核对 GitHub、领队父对话与 JaSAgent 群检索索引。

- Hang 于 2026-11-26 13:20:42（Asia/Shanghai）在群里确认「026开工」（seq 751），并于 13:20:53 指定「去掉隧道放到027」（seq 752）。Funnel / Tailscale 的清理不在本轮。
- `review.md`、`spec.md` 已写入并合入 master [`8a1dff58a2dbed9ce034a5213b4a42ad806be398`](https://github.com/xuhang113/JaSAgent/commit/8a1dff58a2dbed9ce034a5213b4a42ad806be398)（2026-11-26 13:30:46 Asia/Shanghai）。领队于 13:47 核对称与已锁定口径一致，宣布 SDD 通过。
- 产品实现基线为 [`35ac67bb1e8e710e5abd86e6a75ca048b4384154`](https://github.com/xuhang113/JaSAgent/commit/35ac67bb1e8e710e5abd86e6a75ca048b4384154)，并有 [`changelog/2026-11-26-gateway-session-media.md`](https://github.com/xuhang113/JaSAgent/blob/master/changelog/2026-11-26-gateway-session-media.md)。代码review 于 2026-11-26 15:16:17（Asia/Shanghai）对当时头提交 `35ac67bb` 提交了 COMMENTED 审查（[review 6069930204](https://github.com/xuhang113/JaSAgent/pull/19#pullrequestreview-6069930204)），结论为通过，并写明不合并。
- ATDD 补在 [`6d3afbe2c61da97607bbddc03fb9b98fc411e772`](https://github.com/xuhang113/JaSAgent/commit/6d3afbe2c61da97607bbddc03fb9b98fc411e772)（2026-11-27 15:57 Asia/Shanghai）：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-008.txt`。今日复核 T-001 到 T-008 全部为「通过」，对应规格第 1 到第 8 条。该提交只增加 ATDD 文件，未改产品代码或 `deploy/k8s`。领队已于 16:07 独立核对并宣布通过。
- 产品与 ATDD 随 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19) 由领队用 GitHub 合并提交合入，合并提交为 [`b98c66f535fcdd9f15388c6b37caaf50aa8fde7a`](https://github.com/xuhang113/JaSAgent/commit/b98c66f535fcdd9f15388c6b37caaf50aa8fde7a)（2026-11-27 18:53:29 Asia/Shanghai），父母提交为 [`2897e2b6`](https://github.com/xuhang113/JaSAgent/commit/2897e2b6cd1c6e28d828132ca2ef0980879781c4) 与 [`6d3afbe`](https://github.com/xuhang113/JaSAgent/commit/6d3afbe2c61da97607bbddc03fb9b98fc411e772)，merged_by xuhang113，没有 squash，也没有直接推产品代码到 master。
- 部署已在 [PR #20](https://github.com/xuhang113/JaSAgent/pull/20) 落地：头提交 [`0c53eaef5a7de8cb62040b30cba09e2a1cb7861d`](https://github.com/xuhang113/JaSAgent/commit/0c53eaef5a7de8cb62040b30cba09e2a1cb7861d)（2026-11-27 18:42:18 Asia/Shanghai），相对 #19 头 `6d3afbe` 只改 `deploy/k8s` 和 `deploy.md`。代码review 对头提交 `0c53eaef` 提交了 COMMENTED 审查（[review 6087033487](https://github.com/xuhang113/JaSAgent/pull/20#pullrequestreview-6087033487)），结论为通过，并写明不合并。领队独立核对后，按先 #19、再 #20 合入。合并提交为 [`42670d7f2e60cfa8910505dd5dcc9adafcc395b8`](https://github.com/xuhang113/JaSAgent/commit/42670d7f2e60cfa8910505dd5dcc9adafcc395b8)（2026-11-27 18:56:27 Asia/Shanghai），父母提交为 [`b98c66f`](https://github.com/xuhang113/JaSAgent/commit/b98c66f535fcdd9f15388c6b37caaf50aa8fde7a) 与 [`0c53eae`](https://github.com/xuhang113/JaSAgent/commit/0c53eaef5a7de8cb62040b30cba09e2a1cb7861d)，merged_by xuhang113。
- `deploy.md` 记录云电脑实测：规格第 9 到第 12 条均已通过。官方 `probe-egress` 于 2026-11-27 18:40:43 为 `PROBE PASSED`（含 018–025 既有项，未缩小范围）。第 9 条：删/重建网关 Pod 后，两个新就绪副本都能取回同一份附件字节，用户库仍只有 `jasagent`，没有另建库，也没有把两个副本挂到同一块普通 RWO 盘。第 11 条：Ingress 仍只到 `jasagent-gateway:18721`，没有单独的附件 Service。第 12 条：未恢复 Funnel，未把公网入口或历史主机名写成可用。
- 部署于 2026-11-27 18:59:24（Asia/Shanghai）回报：按合入后 SHA `42670d7` 在云电脑再跑官方探测，结果为 `PROBE PASSED`；清单与该 SHA 一致，网关仍是 2 个就绪副本，Ingress 仍只到 `jasagent-gateway:18721`，Funnel 未恢复，公网入口不能写成可用，没有再改仓库，也未开 027。领队随后独立复核 GitHub：master 仍为 `42670d7`，`requirements/` 仍只有 001–026，没有 027 目录。该合入后探测本身不是领队重跑的；合入前走查仍以 `deploy.md` 的 18:40:43 为准。
- 非阻塞残余仍记为事实，不挡 026 关闭：云电脑内核缺少 `xt_statistic`，第一次随 `up-stack.sh` 的官方探测曾因过期 DNAT 失败，部署在宿主机纠正 DNAT 后重跑通过，没有改官方脚本或缩小范围；NodePort 30080 当时可访问 `/health`，但不是公网入口。024 整块和 019 仍维持 2026-11-24 按 Hang 放弃集群外走查的正式关闭；公网入口不能写成可用。这不等于 Funnel 已经测过。

本次开评核对时（2026-11-28 约 07:20 Asia/Shanghai），master 仍为 [`42670d7f2e60cfa8910505dd5dcc9adafcc395b8`](https://github.com/xuhang113/JaSAgent/commit/42670d7f2e60cfa8910505dd5dcc9adafcc395b8)。其后没有新的 master 提交，没有 `requirements/027` 目录或 027 分支，也没有新的产品或部署 Pull Request。唯一未关闭的 PR 仍是无关的 [#3](https://github.com/xuhang113/JaSAgent/pull/3)。最近一次合入的产品或部署 PR 是 2026-11-27 的 #19 与 #20。

JaSAgent 群检索索引 `topped_through_seq` 为 780，与索引内最大序号一致；索引文件在 2026-11-28 06:51（Asia/Shanghai）随 box 刷新后未见继续写入。索引里最新帖停在 2026-11-27 18:18:22 部署回应将另开清单 PR（seq 780）。其后的合入宣布、代码review 结论和合入后探测，已在领队父对话和 GitHub 上核对，不把索引滞后当成群里没有后续消息。领队 1:1 检索索引最新仍是 2026-11-27 07:26:47 的每日开评（seq 48）；Hang 对 026 的开工确认是在群里作出的，1:1 在那条开评之后未见新的用户回复。Hang 在群里最后一条可见用户消息是 2026-11-27 15:48:25「确认进度继续推进」（seq 772）。

### 今天开评的原因

026 已整块完成且测试通过。按顺序一轮只推进一块：网关 → sidecar → 运行时 → agentpass → K8s。026 是网关这一棒，默认下一棒是 sidecar（`jasagent-sidecar`）。但 Hang 已于 2026-11-26 指定「去掉隧道放到027」，因此今天开评按该指定进行，不自动改成 sidecar 新特性。请调研先在群里交一个候选（不写文件），并写明它落在哪一块。等领队、实现、测试、部署、代码review 都写「通过」后，再向 Hang 确认。确认前不写 `spec.md`，不改 Git 上或 Hang 的 Mac 上的代码，不部署。

前期项目不稳定，本次可以引入重大改动、重构或重要特性，不要因为实现难度或成本缩小范围。一轮只推进一块。界面只用 `jasagent-web`。019、024 虽已正式关闭，公网 Funnel 路径并未实测恢复，公网入口不能写成可用。

Hang 于 2026-11-24 20:54:55 在群里（seq 706）要求：测试通过后由领队负责把代码合到 master。领队已接受为常设合并规则：审查和测试都通过后，由领队用 GitHub merge commit 合入，不 squash，不直接推 master，不再另等 Hang 点头，也不再默认交给部署合并。这不影响本轮确认前不写 spec、不改代码、不部署。Hang 2026-11-24 对 024 第 9 条和 019 集群外走查的那句「需要我确认的部分直接通过，不要等我确认」，不适用于新一轮开工确认。

### 今天有没有开评

有开评。原因见上：026 已完成且测试通过，今天按 Hang 已指定的 027「去掉隧道」开评。

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
