# 017-nacos-agent-discovery 状态

- 日期：2026-11-15（Asia/Shanghai）
- 开评结论：今天不开评。

## 上一件完成情况

当前块仍是 `017-nacos-agent-discovery`（从 Nacos 发现并可扩缩容的 agent），尚未整块完成。

- `review.md`、`spec.md` 已在 `8587b821d12e616bbfcd3575ba7e472a9dbb1d12` 落地（2026-11-14 23:35 Asia/Shanghai），记录 Hang 的「开工」确认。
- 实现已在 `26bc4a331b47902679fc0d68bebbb093269d2e98` 落地（2026-11-15 00:19 Asia/Shanghai）：`jasagent-app`、`jasagent-gateway`、scripts（`start.sh`、`stop.sh`、`common.sh`、`test-017-nacos-agent-discovery.sh`）、根 POM 的 Nacos 客户端版本，以及 `changelog/2026-11-14-nacos-agent-discovery.md`。未改 `deploy/k8s`、sidecar 产品代码或官方 `scripts/k8s/probe-egress.sh`。
- ATDD 已在 `35c78cafdf6bb1a0ee73869388cfa27a95cc5c03` 落地（2026-11-15 07:19 Asia/Shanghai）：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-012.txt`。T-001 到 T-012 全部为「通过」，对应规格第 1 到第 12 条。该提交未改 `deploy/k8s`、sidecar 产品代码或 `probe-egress.sh`。
- 部署尚未落地。目录中没有 `deploy.md`。规格第 13 到第 18 条（云电脑补 Nacos、HPA 最小 2 最大 10、网络策略、按发现实例验收的 `probe-egress` 且整体 `PROBE PASSED`、I-001 到 I-016 仍通过、`deploy.md` 写明环境地址、命令和集成测试结果）尚未验收。

master 当时 HEAD 为 `35c78cafdf6bb1a0ee73869388cfa27a95cc5c03`。

## 堵住的原因

上一件未完成：ATDD 已通过，但部署阶段（规格第 13 到第 18 条 / `deploy.md`）还没提交到 Git。按流程，上一件未完成时不开新的 SDD 需求评审。

下一棒：部署在云电脑上补 Nacos、扩缩容、网络策略、自定义指标和探测脚本，部署并交出 `deploy.md`。验收环境以部署的云电脑为准，不改 Hang 的 Mac。

## 今天有没有开评

没有开评。原因见上：017 尚未整块完成，不能开始下一块。
