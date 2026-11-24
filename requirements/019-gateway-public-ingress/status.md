# 019-gateway-public-ingress 状态

## 2026-11-25（Asia/Shanghai）

- 日期：2026-11-25（Asia/Shanghai）
- 开评结论：019 已正式关闭。今天的新评在 025 完成后展开，见 [`requirements/025-otel-jaeger-mvp/status.md`](https://github.com/xuhang113/JaSAgent/blob/master/requirements/025-otel-jaeger-mvp/status.md)。本日评审块是网关（`jasagent-gateway`），不在本目录重开 019。

### 完成与关闭情况

`019-gateway-public-ingress`（给网关增加公网入口）已正式关闭。实现和 ATDD 第 1 到第 8 条，以及部署里不依赖公网主机名的第 10、13 条此前已经通过。规格第 9、11、12、14 条里依赖 Funnel 公网入口的部分并未实测完成：没有稳定的 `*.ts.net` HTTPS 入口写回，集群外登录、带 Secure 的会话 Cookie、经该入口的控制台 EventSource，以及公网 `/health` 都还没有复查。

2026-11-24 20:55:24（Asia/Shanghai），Hang 在 JaSAgent 群里（seq 707）说「需要我确认的部分直接通过，不要等我确认」。领队于 20:57（seq 716–717）据此宣布 019 正式关闭：依据是明确放弃集群外走查，不是 Funnel 路径已经恢复或测过。公网入口不能写成可用；当时云电脑上 `tailscaled` 不在，宿主机 Ingress NodePort 30080 因缺少 `xt_statistic` 仍不通。这些事实仍在，只是不再挡住关闭和下一轮开评。

昨晚宣布后尚未把结论写入 Git。本次开评（2026-11-25 约 07:20 Asia/Shanghai）核实时 master 仍为 [`078ad78`](https://github.com/xuhang113/JaSAgent/commit/078ad7899db4ab7644c9001104fbd1b33570ee00)，本提交补记关闭结论。025 已整块完成且测试通过，今天开评网关，详情见 025 的 `status.md`。是否把公网入口作为新的网关候选，由调研论证，不自动把已关闭的 019 未测项并进本轮。

### 今天有没有开评

本目录不再开 019 的后续评审。今天开评网关，记录见 025 的 `status.md`。

## 2026-11-18（Asia/Shanghai）

- 日期：2026-11-18（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `019-gateway-public-ingress`（给网关增加公网入口）尚未整块完成。`018-sidecar-egress` 此前已整块完成。

- `review.md`、`spec.md` 已在 `c4ab8c68f4dd83b791b8b908aa22f0b590b81055` 落地（2026-11-17 16:46 Asia/Shanghai），记录 Hang 于 16:43 确认「019开始弄」，主机名锁定为 Tailscale Funnel 的 `*.ts.net`。
- 实现已在 `beb47b25358aee4a9fdab415760a677eec0ab743` 落地（2026-11-17 18:27 Asia/Shanghai），并有 `changelog/2026-11-17-gateway-public-ingress.md`。
- ATDD 已在 `bd520d62c369fa32c9428d89d82ddfeb6276f2ba` 落地（2026-11-17 18:37 Asia/Shanghai）：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-008.txt`。T-001 到 T-008 全部为「通过」，对应规格第 1 到第 8 条。
- 部署已在 `4a7b4e2bcb551a2aa3a2b81dc81be828c813056a` 落地（2026-11-17 19:04:49 Asia/Shanghai）：`deploy.md`、`deploy/k8s` 与探测脚本，并有 `changelog/2026-11-17-gateway-public-ingress-deploy.md`。官方 `probe-egress` 于 18:58:25 为 `PROBE PASSED`；I-001 至 I-016 于 19:02:47 全部通过。规格第 10、13 条已通过。第 9、11、12、14 条里依赖公网主机名的部分尚未完成。

本次开评核对时，master 仍为 `4a7b4e2bcb551a2aa3a2b81dc81be828c813056a`。没有更新的实现、测试或部署提交。

### 今天没有开评的原因

019 的实现和 ATDD（T-001 至 T-008）已通过，云电脑上的 Ingress-nginx（不使用 hostNetwork，已纳入 gVisor）、官方探测和 I-001 至 I-016 也已完成。但公网 Funnel 尚未建立：部署所用云电脑没有 Tailscale 登录态，稳定的 `*.ts.net` 主机名还没创建，规格第 9、11、12、14 条里依赖公网入口的部分被挡住。集群外（含手机）还不能经 `https://<*.ts.net>` 打开控制台并完成登录验收。

2026-11-17 19:06（Asia/Shanghai），部署已在与 Hang 的 1:1 里请他先回复再启动登录；领队于 19:07 在群里确认同一堵住点，并请登录只在部署 1:1 里办理，不要在群里走设备码。截至本次开评核对（2026-11-18 07:14 Asia/Shanghai），JaSAgent 群仍止于 19:07:36 的说明，部署 1:1 仍止于 19:06:06 的请示，未见 Hang 回复，也没有补上公网主机名的新提交。

上一件未完成，因此今天不开评，也不再让调研另交候选。下一棒仍是补完 019，而不是开下一块。

下一棒：等 Hang 在与「JaSAgent 部署」的私聊里回复并完成 Tailscale 账号授权。登录后由部署执行 `bash scripts/k8s/configure-funnel.sh`，把稳定 `*.ts.net` 主机名补进 `deploy.md`，再用集群外（含手机）验收登录、`/admin/cluster`、未登录拦截和 EventSource。凭证不进仓库，也不在群里办理登录。

### 今天有没有开评

没有开评。原因见上：019 尚未整块完成，堵住点是 Funnel 公网主机名仍待 Hang 在部署 1:1 里登录。
