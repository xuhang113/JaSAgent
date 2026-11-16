# 018-sidecar-egress 状态

## 2026-11-17（Asia/Shanghai）

- 日期：2026-11-17（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `018-sidecar-egress`（让离开集群的 HTTP 走 sidecar）已整块完成。`017-nacos-agent-discovery` 此前也已整块完成。

- `review.md`、`spec.md` 已在 `08be62c33ec4c0571e99943215cb2602e810745c` 落地（2026-11-15 21:04 Asia/Shanghai），记录 Hang 的「先开工018」确认。
- 实现已在 `089f0ebe924ac0968fc7b8a1700404e1435e8393`（2026-11-15 21:44 Asia/Shanghai）和 `e8ae185317dc348b9f44fb48262022c380189994`（2026-11-15 22:09 Asia/Shanghai）落地，并有 `changelog/2026-11-15-sidecar-egress.md`。
- ATDD 已在 `aedb7910843a1b42f8b119ed83f3519e1daa8458` 落地（2026-11-15 22:16 Asia/Shanghai）：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-009.txt`。T-001 到 T-009 全部为「通过」，对应规格第 1 到第 9 条。
- 部署已在 `b366158396d36cc110add19fbcefa73c6482d3ca` 落地（2026-11-15 22:36 Asia/Shanghai）：`deploy.md`、`deploy/k8s` 与探测脚本。官方 `probe-egress` 于 22:35:31 为 `PROBE PASSED`；I-001 至 I-016 于 22:35:42 全部通过。规格第 10 到第 15 条均通过。

本次开评核对时，master 仍为 `aa853b73532ef02ac9e8049b9013fc74eef22226`（2026-11-16 07:21 Asia/Shanghai 的 status.md 更正）。没有更新的实现、测试或部署提交。`requirements/` 下没有 019 目录。

### 今天没有开评的原因

018 已整块完成，但不另开新的 SDD 需求评审。`019-gateway-public-ingress` 已在群里讨论并全员通过。

2026-11-16 11:28（Asia/Shanghai），Hang 在群里提出 019 仍按生产环境做：集群内用 Ingress-nginx 反向代理，挂到免费域名，免费资源可自行创建。领队随即组织复评，调研、实现、测试、部署均于 11:30 前后表决「通过」。11:51 领队锁定调整后的口径，并请 Hang 确认是否开工 019，同时选定主机名方案：购买域名接入 Cloudflare，或全免费使用 Tailscale Funnel 的 `*.ts.net`。

锁定口径：稳定主机名 → Cloudflare Tunnel（或全免费时的 Tailscale Funnel）→ Ingress-nginx → 只转到 jasagent-gateway，不暴露 web、Nacos、MySQL、Redis 和 agent。这台云机没有公网 IP，隧道不能省；Ingress-nginx 不用 hostNetwork，并纳入 gVisor；SSE 要能穿过隧道和 Ingress；官方探测仍须 `PROBE PASSED`，egress/gVisor 不缩小。网关侧：`JASAGENT_GATEWAY_WEB_ORIGIN` 仍是集群内 web 上游；只信任 Ingress/隧道这一跳的 `X-Forwarded-*`；HTTPS 下 cookie 带 Secure，不写死跳到 `127.0.0.1` 或 HTTP。不用 trycloudflare，凭证不进仓库。

截至本次开评核对（2026-11-17 07:18 Asia/Shanghai），JaSAgent 群在 11:51 请 Hang 确认之后没有新回复；领队 1:1 最后一条仍为 2026-11-16 07:22:24 的开评结论。Hang 尚未确认开工，也尚未选定主机名方案。确认前不写 `spec.md`，不改 Git 上或 Hang 的 Mac 上的代码，不部署。一轮只推进一块，因此今天不再另开一块，也不再让调研另交候选。

下一棒：等 Hang 确认是否开工 `019-gateway-public-ingress`，并选定主机名方案（付费域名接入 Cloudflare，或全免费使用 `*.ts.net`）。

### 今天有没有开评

没有开评。原因见上：018 已完成，019 已全员通过，仍在等 Hang 确认开工并选定主机名方案。

## 2026-11-16（Asia/Shanghai）

- 日期：2026-11-16（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `018-sidecar-egress`（让离开集群的 HTTP 走 sidecar）已整块完成。`017-nacos-agent-discovery` 此前也已整块完成。

- `review.md`、`spec.md` 已在 `08be62c33ec4c0571e99943215cb2602e810745c` 落地（2026-11-15 21:04 Asia/Shanghai），记录 Hang 的「先开工018」确认。
- 实现已在 `089f0ebe924ac0968fc7b8a1700404e1435e8393`（2026-11-15 21:44 Asia/Shanghai）和 `e8ae185317dc348b9f44fb48262022c380189994`（2026-11-15 22:09 Asia/Shanghai）落地，并有 `changelog/2026-11-15-sidecar-egress.md`。
- ATDD 已在 `aedb7910843a1b42f8b119ed83f3519e1daa8458` 落地（2026-11-15 22:16 Asia/Shanghai）：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-009.txt`。T-001 到 T-009 全部为「通过」，对应规格第 1 到第 9 条。
- 部署已在 `b366158396d36cc110add19fbcefa73c6482d3ca` 落地（2026-11-15 22:36 Asia/Shanghai）：`deploy.md`、`deploy/k8s` 与探测脚本。官方 `probe-egress` 于 22:35:31 为 `PROBE PASSED`；I-001 至 I-016 于 22:35:42 全部通过。规格第 10 到第 15 条均通过。

本次开评核对时，master 仍为 `b366158396d36cc110add19fbcefa73c6482d3ca`，没有更新的实现、测试或部署提交。`requirements/` 下没有 019 目录。

### 今天没有开评的原因

018 已整块完成，但不另开新的 SDD 需求评审。`019-gateway-public-ingress` 已在群里讨论并全员通过。Hang 此前决定先做 018；018 完成后，领队已于 2026-11-15 22:41（Asia/Shanghai）在群里向 Hang 汇报，并请他确认是否开工 019。Hang 尚未确认。确认前不写 `spec.md`，不改 Git 上或 Hang 的 Mac 上的代码，不部署。一轮只推进一块，因此今天不再另开一块，也不再让调研另交候选。

下一棒：等 Hang 确认是否开工 `019-gateway-public-ingress`。

### 今天有没有开评

没有开评。原因见上：018 已完成，019 已全员通过，仍在等 Hang 确认。
