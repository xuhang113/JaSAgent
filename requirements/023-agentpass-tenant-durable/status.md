# 023-agentpass-tenant-durable 状态

## 2026-11-21（Asia/Shanghai）

- 日期：2026-11-21（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `023-agentpass-tenant-durable`（通行证按租户持久化到现有 MySQL）已整块完成，测试通过。

- `review.md`、`spec.md` 已在 [PR #13](https://github.com/xuhang113/JaSAgent/pull/13) 中落地，记录 Hang 于 2026-11-20 约 08:21（Asia/Shanghai）在群里确认「通行证需要持久化权限判断 开始做吧」。领队、调研、实现、测试、部署、代码review 均已写「通过」，补充已并进口径。
- 实现产品提交为 [`ef848d6`](https://github.com/xuhang113/JaSAgent/commit/ef848d6a7258b9c09f88552010483939148abccf)，并有 [`changelog/2026-11-20-agentpass-tenant-durable.md`](https://github.com/xuhang113/JaSAgent/blob/master/changelog/2026-11-20-agentpass-tenant-durable.md)。ATDD 结果补在 [`d23afbf`](https://github.com/xuhang113/JaSAgent/commit/d23afbf97c8649e0738cefd0cacc9e020e7426bb)。两者随 [PR #13](https://github.com/xuhang113/JaSAgent/pull/13) 合入，合并提交为 [`b4ad3ee`](https://github.com/xuhang113/JaSAgent/commit/b4ad3eeec138315cdebd8eb03de2e225e9888458)（19:48:31 Asia/Shanghai）。
- ATDD：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-009.txt`。T-001 到 T-009 全部为「通过」，对应规格第 1 到第 9 条。
- 部署已在 [PR #14](https://github.com/xuhang113/JaSAgent/pull/14) 落地并合入，合并后 master 为 [`e9db29e`](https://github.com/xuhang113/JaSAgent/commit/e9db29eac729e7ac3189b8777dc4f8923622b5a0)（19:49:05 Asia/Shanghai）：`deploy.md` 记录云电脑实测。官方 `probe-egress` 于 19:13:44 为 `PROBE PASSED`（未改探测脚本，也未跳过 `ticket-issued` 或 `external-with-ticket`）；I-001 至 I-016 于 19:15:49 全部通过（`016 IT PASSED`）。规格第 10 到第 12 条均已通过。签名密钥只从仓库外文件写入 Secret `jasagent-agentpass`，原文不在 Git。网关仍是单副本；agentpass 仍是 ClusterIP，没有暴露到公网。公网 Funnel `/health` 本轮未复查，不挡第 10 到 12 条。两份 PR 都由 xuhang113 按审查意见先合 #13、再合 #14，没有直接推 master。
- 领队已于 2026-11-20 20:19:06（Asia/Shanghai）在 JaSAgent 群里宣布 023 整块完成（seq 546–547），并请调研交 K8s 候选。

本次开评核对时（2026-11-21 约 07:20 Asia/Shanghai），master 仍为 [`e9db29eac729e7ac3189b8777dc4f8923622b5a0`](https://github.com/xuhang113/JaSAgent/commit/e9db29eac729e7ac3189b8777dc4f8923622b5a0)。之后没有新的 master 提交，没有 024 目录或 024 分支，也没有新的 Pull Request。唯一未关闭的 PR 仍是无关的 [#3](https://github.com/xuhang113/JaSAgent/pull/3)。

### 今天没有开评的原因

023 已整块完成且测试通过。按顺序下一棒是 K8s（`deploy/k8s`）。候选 `024-k8s-durable-ha` 已于 2026-11-20 20:21:54 由调研在群里交出；领队、调研、实现、测试、部署、代码review 均已写「通过」，补充已并进已锁定口径（代码review 20:23:02，领队锁定口径 20:23:07，部署 20:23:17，实现 21:17:01，测试 21:18:07）。领队已于 2026-11-20 21:19:06（Asia/Shanghai）在群里请 Hang 确认是否开工（seq 558）。

截至本次开评核对：群内最新帖仍是 21:19:06 的确认请求（检索索引 `topped_through_seq` 为 558，索引最近一次写入为 2026-11-20 23:40 Asia/Shanghai）；领队 1:1 在 2026-11-20 07:23 的每日开评之后未见 Hang 确认 024；仓库里也还没有 024 的 `spec.md` 或代码。一轮只推进一块，因此今天不开新评，也不再让调研另交候选。下一棒仍是等 Hang 确认开工 024，而不是开 025。

确认前不写 `spec.md`，不改 Git 上或 Hang 的 Mac 上的代码，不部署。

已锁定口径摘要：本轮只改 K8s 这一块。MySQL 数据目录改成持久卷，必须先把现有 `jasagent` 库迁过去再切卷，不能直接用空卷滚动掉当前 MySQL Pod；迁完之后，删/重建该 Pod 不得丢 020 会话和 023 证与流水。不新建库。整机或数据盘被清空不在本轮承诺里。网关至少 2 个就绪副本；若存活探测或 EventSource 仍在进程内、会挡住多副本，允许改 `jasagent-gateway`，由实现先开 PR，不动 `deploy/k8s`。不改 sidecar 和 agentpass 的产品约定。跨副本仍须满足 020 的 Cookie 与租户隔离。`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 仍禁止 `0.0.0.0/0`。官方探测须改掉「必须单副本」的断言，改为至少 2 个就绪副本，web 上游仍是集群内地址；官方探测仍须 `PROBE PASSED`，018–023 的其余检查范围不得缩小。恢复 Funnel 公网路径（`*.ts.net` → Ingress-nginx → 只到 `jasagent-gateway:18721`），正式确认集群外或手机可登录、控制台 EventSource 经该 HTTPS 入口可用，并补上 019 尚未正式关闭的 `/admin/cluster` 和未登录拦截。若要重新登录，部署在私信里找 Hang，不在群里执行 `tailscale up`；不暴露 web、Nacos、MySQL、Redis、agent 或 agentpass。把已验证的无 systemd 进程拉起方式收进官方 `prepare-cluster.sh`。不做每会话技能摘要、日程推送、多媒体、外部插件、CodeReview，也不做 Nacos 集群化或 Redis 持久化。界面仍只用 `jasagent-web`。有网关代码就先由实现开 PR（不动 `deploy/k8s`），再代码审查、测试，最后部署开 PR；纯清单则由部署开 PR，不要直接推 master。

### 今天有没有开评

没有开评。原因见上：023 已完成，024 已全员通过，仍待 Hang 确认开工。
