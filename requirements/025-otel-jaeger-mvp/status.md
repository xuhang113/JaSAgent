# 025-otel-jaeger-mvp 状态

## 2026-11-25（Asia/Shanghai）

- 日期：2026-11-25（Asia/Shanghai）
- 开评结论：今天开评。本日评审块是网关（`jasagent-gateway`）。请调研先在群里交一个候选，不写文件。

### 上一件完成情况

上一件 `025-otel-jaeger-mvp`（OpenTelemetry 到 Jaeger 的链路 MVP）已整块完成，测试通过。今日开评独立核对仓库中的 `review.md`、`spec.md`、`tdd.md`、`results/`、`deploy.md` 和 changelog，以及 JaSAgent 群与领队 1:1。

- Hang 于 2026-11-24 约 09:23（Asia/Shanghai）确认「开工」。`review.md`、`spec.md` 已写入并合入 master [`1ac39ca`](https://github.com/xuhang113/JaSAgent/commit/1ac39ca07ee7b152453012626d9d094c4a8be9e2)（2026-11-24 09:25:34 Asia/Shanghai）。领队、调研、实现、测试、部署、代码review 均已写「通过」，补充已并进口径。
- 实现产品提交为 [`ba44725`](https://github.com/xuhang113/JaSAgent/commit/ba44725c05c61f6dcf6b7a8d0452595ed2fdc7c6)，并有 [`changelog/2026-11-24-otel-jaeger-mvp.md`](https://github.com/xuhang113/JaSAgent/blob/master/changelog/2026-11-24-otel-jaeger-mvp.md)。ATDD 结果补在 [`475bc7b`](https://github.com/xuhang113/JaSAgent/commit/475bc7b21e9e8420f4d40c6f0b4607f0c45364b0)。两者随 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 合入，合并提交为 [`7b97aac`](https://github.com/xuhang113/JaSAgent/commit/7b97aac94803813a59f12df33dd5d506ebcfa981)（2026-11-24 19:01:19 Asia/Shanghai）。
- ATDD：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-008.txt`。今日复核 T-001 到 T-008 全部为「通过」，对应规格第 1 到第 8 条。
- 部署已在 [PR #18](https://github.com/xuhang113/JaSAgent/pull/18) 落地并合入，合并提交为 [`078ad78`](https://github.com/xuhang113/JaSAgent/commit/078ad7899db4ab7644c9001104fbd1b33570ee00)（2026-11-24 20:47:12 Asia/Shanghai），父母提交为 [`7b97aac`](https://github.com/xuhang113/JaSAgent/commit/7b97aac94803813a59f12df33dd5d506ebcfa981) 与 [`450e735`](https://github.com/xuhang113/JaSAgent/commit/450e73555e7f6197eb55082f164a53fa10f98206)，没有 squash，也没有直接推 master。`deploy.md` 记录云电脑实测：官方 `probe-egress` 于 20:27:19 为 `PROBE PASSED`（含 018–024 既有项，以及新增的 `jaeger-clusterip-not-nodeport` 和 `jaeger-not-on-ingress`；`gateway-multi-replica-web-origin` 为 replicas=2 ready=2；`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 为 Ingress Pod IP，不是 `0.0.0.0/0`）；规格第 9 到第 13 条均已通过。第 9 条：admin 经两个网关副本打开 `/admin/jaeger` 能看到根 span `jasagent.execution`（导出 traceID 与内存 traceId 不同，验收未用内存编号去对）。第 11 条：Ingress 仍只到 `jasagent-gateway:18721`，Jaeger 只走 ClusterIP，不挂 NodePort。第 12 条按口径不是失败条件（重启丢链路、NetworkPolicy 因缺少 `CONFIG_IP_SET` 不当作通过条件）。两份 PR 都由 xuhang113 按安排先合 #17、再合 #18。代码review 对 #18 head `450e735` 的 COMMENT 审查结论为通过。
- 非阻塞残余仍记为事实，不挡 025 关闭：宿主机 Ingress NodePort 30080 因缺少 `xt_statistic` 不通，第 9 条改走网关 Pod IP，仍是经网关反代；Jaeger 服务名是 OpenTelemetry SDK 缺省的 `unknown_service:java`，规格看的是根 span 名称。
- 024 整块和 019 已于 2026-11-24 20:57（Asia/Shanghai）正式关闭。依据是 Hang 在群里（seq 707，20:55:24）说「需要我确认的部分直接通过，不要等我确认」。领队在群里（seq 716–717）宣布关闭：这是明确放弃集群外走查，不是 Funnel 路径已经恢复或测过。公网入口不能写成可用；当时云电脑上 `tailscaled` 不在，宿主机 NodePort 30080 仍不通。昨晚宣布后尚未把该结论写入 Git，本提交一并补记，详见 [`requirements/024-k8s-durable-ha/status.md`](https://github.com/xuhang113/JaSAgent/blob/master/requirements/024-k8s-durable-ha/status.md) 与 [`requirements/019-gateway-public-ingress/status.md`](https://github.com/xuhang113/JaSAgent/blob/master/requirements/019-gateway-public-ingress/status.md)。

本次开评核对时（2026-11-25 约 07:20 Asia/Shanghai），master 仍为 [`078ad7899db4ab7644c9001104fbd1b33570ee00`](https://github.com/xuhang113/JaSAgent/commit/078ad7899db4ab7644c9001104fbd1b33570ee00)。之后没有新的 master 提交，没有 `requirements/026` 目录或 026 分支，也没有新的产品或部署 Pull Request。唯一未关闭的 PR 仍是无关的 [#3](https://github.com/xuhang113/JaSAgent/pull/3)。JaSAgent 群检索索引 `topped_through_seq` 为 717，索引最近一次写入为 2026-11-25 06:51 Asia/Shanghai；最新帖仍是 2026-11-24 20:57:33 领队宣布 024 与 019 正式关闭（seq 717）。之后没有新的群消息，也没有已在讨论的 026 候选。领队 1:1 最新仍是 2026-11-24 07:20:48 的每日开评（seq 45），其后未见 Hang 回复。

### 今天开评的原因

025 已整块完成且测试通过。024 整块和 019 已正式关闭，不再挡住下一轮。按顺序一轮只推进一块：网关 → sidecar → 运行时 → agentpass → K8s。024 是 K8s 这一棒，025 是 Hang 点名的链路观测 MVP，现已完成。因此今天开评，点名本日评审块为网关（`jasagent-gateway`）。请调研先在群里交一个候选（不写文件）。等领队、实现、测试、部署、代码review 都写「通过」后，再向 Hang 确认。确认前不写 `spec.md`，不改 Git 上或 Hang 的 Mac 上的代码，不部署。

前期项目不稳定，本次可以引入重大改动、重构或重要特性，不要因为实现难度或成本缩小范围。一轮只推进一块。界面只用 `jasagent-web`。019 虽已正式关闭，公网 Funnel 路径并未实测恢复，公网入口不能写成可用；是否把公网入口或其它网关改动作为本轮候选，由调研论证，不自动并进本轮。

Hang 于 2026-11-24 20:54:55 在群里（seq 706）要求：测试通过后由领队负责把代码合到 master。领队已接受为常设合并规则：审查和测试都通过后，由领队用 GitHub merge commit 合入，不 squash，不直接推 master，不再另等 Hang 点头，也不再默认交给部署合并。这不影响本轮确认前不写 spec、不改代码、不部署。

### 今天有没有开评

有开评。原因见上：025 已完成且测试通过，024 与 019 已正式关闭，按顺序开评网关。
