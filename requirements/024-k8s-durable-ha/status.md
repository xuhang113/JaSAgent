# 024-k8s-durable-ha 状态

## 2026-11-25（Asia/Shanghai）

- 日期：2026-11-25（Asia/Shanghai）
- 开评结论：024 已正式关闭。今天的新评在 025 完成后展开，见 [`requirements/025-otel-jaeger-mvp/status.md`](https://github.com/xuhang113/JaSAgent/blob/master/requirements/025-otel-jaeger-mvp/status.md)。本日评审块是网关（`jasagent-gateway`），不在本目录重开 024。

### 完成与关闭情况

`024-k8s-durable-ha`（K8s 持久化与网关多副本）已正式关闭。实现、ATDD（T-001 至 T-006 通过）和第 7、8、10、11、12 条的集群实测此前已合入；官方探测于 2026-11-22 13:28:05 为 `PROBE PASSED`，I-001 至 I-016 于 13:28:47 全部通过。

规格第 9 条（Funnel 公网路径，以及 Hang 本人在集群外或手机走一遍登录、控制台 EventSource，并补上 019 的 `/admin/cluster` 和未登录拦截）并未实测完成。2026-11-24 20:55:24（Asia/Shanghai），Hang 在 JaSAgent 群里（seq 707）说「需要我确认的部分直接通过，不要等我确认」。领队于 20:57（seq 716–717）据此宣布 024 整块正式关闭：依据是明确放弃集群外走查，不是 Funnel 路径已经恢复或测过。公网入口不能写成可用；当时云电脑上 `tailscaled` 不在，宿主机 Ingress NodePort 30080 因缺少 `xt_statistic` 仍不通。这些事实仍在，只是不再挡住关闭和下一轮开评。

昨晚宣布后尚未把结论写入 Git。本次开评（2026-11-25 约 07:20 Asia/Shanghai）核实时 master 仍为 [`078ad78`](https://github.com/xuhang113/JaSAgent/commit/078ad7899db4ab7644c9001104fbd1b33570ee00)（合入 025 部署 [PR #18](https://github.com/xuhang113/JaSAgent/pull/18)），本提交补记关闭结论。025 已整块完成且测试通过，今天开评网关，详情见 025 的 `status.md`。

### 今天有没有开评

本目录不再开 024 的后续评审。今天开评网关，记录见 025 的 `status.md`。

## 2026-11-24（Asia/Shanghai）

- 日期：2026-11-24（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `024-k8s-durable-ha`（K8s 持久化与网关多副本）尚未整块完成。今日开评独立核对仓库中的 `review.md`、`spec.md`、`tdd.md`、`results/`、`deploy.md` 和 changelog，以及 JaSAgent 群与相关 1:1。

- Hang 于 2026-11-22 约 10:08（Asia/Shanghai）在群里确认「开始搞024」（seq 562）。`review.md`、`spec.md` 已写入并合入 master [`390c4c8`](https://github.com/xuhang113/JaSAgent/commit/390c4c82861726b2d58500c4f38b00647bcddf06)（2026-11-22 10:11:28 Asia/Shanghai）。领队、调研、实现、测试、部署、代码review 均已写「通过」，补充已并进口径。
- 实现产品提交为 [`c42fdda8`](https://github.com/xuhang113/JaSAgent/commit/c42fdda8d4f69accea112480443d0d569fbb377d)，并有 [`changelog/2026-11-22-gateway-ha-liveness.md`](https://github.com/xuhang113/JaSAgent/blob/master/changelog/2026-11-22-gateway-ha-liveness.md)。ATDD 结果补在 [`33a1c4bb`](https://github.com/xuhang113/JaSAgent/commit/33a1c4bbca339d201c70bb7443954b16eee10b3b)。两者随 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 合入，合并提交为 [`4043ebc`](https://github.com/xuhang113/JaSAgent/commit/4043ebccdb86b29540f254cd07a79cec5e036a33)（2026-11-22 13:58:40 Asia/Shanghai）。
- ATDD：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-006.txt`。今日复核 T-001 到 T-006 全部为「通过」，对应规格第 1 到第 6 条。
- 部署已在 [PR #16](https://github.com/xuhang113/JaSAgent/pull/16) 落地并合入，合并提交为 [`5d7c4fe`](https://github.com/xuhang113/JaSAgent/commit/5d7c4fe60595a32ce4c2e8fc50132c381297526c)（2026-11-22 13:59:20 Asia/Shanghai）。`deploy.md` 记录云电脑实测：第 7 条先把现有 `jasagent` 库迁到持久卷再切换，删/重建 MySQL Pod 后标记行仍在，没有另建库；官方 `probe-egress` 于 13:28:05 为 `PROBE PASSED`（含 `ticket-issued`、`external-with-ticket`，以及 `gateway-multi-replica-web-origin` 的 replicas=2 ready=2，`WEB_ORIGIN` 仍是集群内地址；018–023 范围未缩小；`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 不是 `0.0.0.0/0`）；I-001 至 I-016 于 13:28:47 全部通过（`016 IT PASSED`）；第 10 条无 systemd 的官方 `prepare-cluster.sh` 已收进并实测拉起；第 11、12 条走 PR，且 `deploy.md` 已写环境地址和结果。两份 PR 都由 xuhang113 按安排先合 #15、再合 #16，没有直接推 master。
- 堵住的原因是规格第 9 条仍未复查。2026-11-21 云电脑更新后 `/var/lib/tailscale` 不在，本轮没有执行 `tailscale up` 或 `tailscale login`。历史主机名是 `https://jasagent-cloud.tail01f87a.ts.net`，公网 `/health` 未复查。集群外或手机登录、控制台 EventSource，以及 019 尚未正式关闭的 `/admin/cluster` 和未登录拦截，不能只在集群内代替。部署已于 2026-11-22 13:32（Asia/Shanghai）在与 Hang 的 1:1 请其方便时继续办理 Funnel 登录，之后未见回复。因此 024 不能算整块完成，019 也不能正式关闭。

本次开评核对时（2026-11-24 约 07:20 Asia/Shanghai），master 仍为 [`07ffdaaf020a217fc8faba69ffc3b553e6da503b`](https://github.com/xuhang113/JaSAgent/commit/07ffdaaf020a217fc8faba69ffc3b553e6da503b)（2026-11-23 07:23:24 Asia/Shanghai 的 docs-only status.md，父提交 [`5d7c4fe60595`](https://github.com/xuhang113/JaSAgent/commit/5d7c4fe60595a32ce4c2e8fc50132c381297526c)）。之后没有新的 master 提交。唯一未关闭的 PR 仍是无关的 [#3](https://github.com/xuhang113/JaSAgent/pull/3)。已合入的 020–024 残留分支仍保持 2026-11-22 14:32 删除后的状态。JaSAgent 群检索索引 `topped_through_seq` 为 612（indexer 在 box 刷新后于 06:53 更新），最新帖仍是 2026-11-23 07:25:41（Asia/Shanghai）的每日开评（seq 612）；之后没有新的群消息，也没有 Hang 确认已完成集群外复查。Hang 在群里的最近一条仍是 2026-11-22 14:49:57 的「第九条是什么」（seq 607），当时已答复。领队 1:1 最新仍是昨日 07:25:44 的每日开评（seq 44），没有 Hang 回复。部署 1:1 最新仍是 2026-11-22 13:32 的 Funnel 登录请求（seq 45），没有后续回复。

### 今天没有开评的原因

024 尚未整块完成，规格第 9 条 Funnel 与集群外登录仍未复查。一轮只推进一块，因此今天不开新评，也不让调研交 025 候选。下一棒仍是等 Hang 在与「JaSAgent 部署」的私信里恢复 Funnel，并在集群外（或手机）走完登录、控制台 EventSource，以及 019 留下的 `/admin/cluster` 和未登录拦截。登录不要在群里做。

### 今天有没有开评

没有开评。原因见上：024 未整块完成，堵住点是第 9 条。

## 2026-11-23（Asia/Shanghai）

- 日期：2026-11-23（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `024-k8s-durable-ha`（K8s 持久化与网关多副本）尚未整块完成。今日开评独立核对仓库中的 `review.md`、`spec.md`、`tdd.md`、`results/`、`deploy.md` 和 changelog，以及 JaSAgent 群与相关 1:1。

- Hang 于 2026-11-22 约 10:08（Asia/Shanghai）在群里确认「开始搞024」（seq 562）。`review.md`、`spec.md` 已写入并合入 master [`390c4c8`](https://github.com/xuhang113/JaSAgent/commit/390c4c82861726b2d58500c4f38b00647bcddf06)（2026-11-22 10:11:28 Asia/Shanghai）。领队、调研、实现、测试、部署、代码review 均已写「通过」，补充已并进口径。
- 实现产品提交为 [`c42fdda8`](https://github.com/xuhang113/JaSAgent/commit/c42fdda8d4f69accea112480443d0d569fbb377d)，并有 [`changelog/2026-11-22-gateway-ha-liveness.md`](https://github.com/xuhang113/JaSAgent/blob/master/changelog/2026-11-22-gateway-ha-liveness.md)。ATDD 结果补在 [`33a1c4bb`](https://github.com/xuhang113/JaSAgent/commit/33a1c4bbca339d201c70bb7443954b16eee10b3b)。两者随 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 合入，合并提交为 [`4043ebc`](https://github.com/xuhang113/JaSAgent/commit/4043ebccdb86b29540f254cd07a79cec5e036a33)（2026-11-22 13:58:40 Asia/Shanghai）。
- ATDD：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-006.txt`。今日复核 T-001 到 T-006 全部为「通过」，对应规格第 1 到第 6 条。
- 部署已在 [PR #16](https://github.com/xuhang113/JaSAgent/pull/16) 落地并合入，合并提交为 [`5d7c4fe`](https://github.com/xuhang113/JaSAgent/commit/5d7c4fe60595a32ce4c2e8fc50132c381297526c)（2026-11-22 13:59:20 Asia/Shanghai）。`deploy.md` 记录云电脑实测：第 7 条先把现有 `jasagent` 库迁到持久卷再切换，删/重建 MySQL Pod 后标记行仍在，没有另建库；官方 `probe-egress` 于 13:28:05 为 `PROBE PASSED`（含 `ticket-issued`、`external-with-ticket`，以及 `gateway-multi-replica-web-origin` 的 replicas=2 ready=2，`WEB_ORIGIN` 仍是集群内地址；018–023 范围未缩小；`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 不是 `0.0.0.0/0`）；I-001 至 I-016 于 13:28:47 全部通过（`016 IT PASSED`）；第 10 条无 systemd 的官方 `prepare-cluster.sh` 已收进并实测拉起；第 11、12 条走 PR，且 `deploy.md` 已写环境地址和结果。两份 PR 都由 xuhang113 按安排先合 #15、再合 #16，没有直接推 master。
- 堵住的原因是规格第 9 条仍未复查。2026-11-21 云电脑更新后 `/var/lib/tailscale` 不在，本轮没有执行 `tailscale up` 或 `tailscale login`。历史主机名是 `https://jasagent-cloud.tail01f87a.ts.net`，公网 `/health` 未复查。集群外或手机登录、控制台 EventSource，以及 019 尚未正式关闭的 `/admin/cluster` 和未登录拦截，不能只在集群内代替。部署已于 2026-11-22 13:32（Asia/Shanghai）在与 Hang 的 1:1 请其方便时继续办理 Funnel 登录，之后未见回复。因此 024 不能算整块完成，019 也不能正式关闭。

本次开评核对时（2026-11-23 约 07:25 Asia/Shanghai），master 仍为 [`5d7c4fe60595a32ce4c2e8fc50132c381297526c`](https://github.com/xuhang113/JaSAgent/commit/5d7c4fe60595a32ce4c2e8fc50132c381297526c)。之后没有新的 master 提交。唯一未关闭的 PR 仍是无关的 [#3](https://github.com/xuhang113/JaSAgent/pull/3)。已合入的 020–024 残留分支已于 2026-11-22 14:32 删除。JaSAgent 群检索索引 `topped_through_seq` 为 611，最新帖仍是 2026-11-22 14:50:35（Asia/Shanghai）领队对 Hang「第九条是什么」（seq 607）的说明；之后没有新的群消息，也没有 Hang 确认已完成集群外复查。领队 1:1 在昨日 07:19 的每日开评之后，Hang 的开工确认是在群里做出的；集群外复查确认仍未出现。部署 1:1 最新仍是 13:32 的 Funnel 登录请求，没有后续回复。

### 今天没有开评的原因

024 尚未整块完成，规格第 9 条 Funnel 与集群外登录仍未复查。一轮只推进一块，因此今天不开新评，也不让调研交 025 候选。下一棒仍是等 Hang 在与「JaSAgent 部署」的私信里恢复 Funnel，并在集群外（或手机）走完登录、控制台 EventSource，以及 019 留下的 `/admin/cluster` 和未登录拦截。登录不要在群里做。

### 今天有没有开评

没有开评。原因见上：024 未整块完成，堵住点是第 9 条。
