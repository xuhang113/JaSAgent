# 018-sidecar-egress 状态

- 日期：2026-11-16（Asia/Shanghai）
- 开评结论：今天不开评。

## 上一件完成情况

上一件 `018-sidecar-egress`（让离开集群的 HTTP 走 sidecar）已整块完成。`017-nacos-agent-discovery` 此前也已整块完成。

- `review.md`、`spec.md` 已在 `08be62c33ec4c0571e99943215cb2602e810745c` 落地（2026-11-15 21:04 Asia/Shanghai），记录 Hang 的「先开工018」确认。
- 实现已在 `089f0ebe924ac0968fc7b8a1700404e1435e8393`（2026-11-15 21:44 Asia/Shanghai）和 `e8ae185317dc348b9f44fb48262022c380189994`（2026-11-15 22:09 Asia/Shanghai）落地，并有 `changelog/2026-11-15-sidecar-egress.md`。
- ATDD 已在 `aedb7910843a1b42f8b119ed83f3519e1daa8458` 落地（2026-11-15 22:16 Asia/Shanghai）：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-009.txt`。T-001 到 T-009 全部为「通过」，对应规格第 1 到第 9 条。
- 部署已在 `b366158396d36cc110add19fbcefa73c6482d3ca` 落地（2026-11-15 22:36 Asia/Shanghai）：`deploy.md`、`deploy/k8s` 与探测脚本。官方 `probe-egress` 于 22:35:31 为 `PROBE PASSED`；I-001 至 I-016 于 22:35:42 全部通过。规格第 10 到第 15 条均通过。

本次开评核对时，master 仍为 `b366158396d36cc110add19fbcefa73c6482d3ca`，没有更新的实现、测试或部署提交。`requirements/` 下没有 019 目录。

## 今天没有开评的原因

018 已整块完成，但不另开新的 SDD 需求评审。`019-gateway-public-ingress` 已在群里讨论并全员通过。Hang 此前决定先做 018；018 完成后，领队已于 2026-11-15 22:41（Asia/Shanghai）在群里向 Hang 汇报，并请他确认是否开工 019。Hang 尚未确认。确认前不写 `spec.md`，不改 Git 上或 Hang 的 Mac 上的代码，不部署。一轮只推进一块，因此今天不再另开一块，也不再让调研另交候选。

下一棒：等 Hang 确认是否开工 `019-gateway-public-ingress`。

## 今天有没有开评

沠有开评。原因见上：018 已完成，019 已全员通过，仍在等 Hang 确认。
