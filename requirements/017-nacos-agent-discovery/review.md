# 需求 017 从 Nacos 发现并可扩缩容的 agent 评审记录

- 日期：2026-11-14（Asia/Shanghai）
- 块：运行时 / 网关发现（`jasagent-app` / `jasagent-gateway` / 必要脚本；`deploy/k8s` 在 ATDD 之后由部署补）
- 结论：已确认。领队、实现、测试、部署均写「通过」，Hang 确认「开工」。确认记录如下。本文件和 `spec.md` 在确认之后写成。016-cloud-k8s-up-stack 已整块完成后再写本轮规格。

## 候选

调研在群里交出的候选 **017-nacos-agent-discovery**：016 完成后推进运行时这一块，本轮不改 sidecar。对照当时的 `master`，默认 agent 不是动态发现出来的。`AgentInstance.primary()`、`secondary()`、`defaults()` 以及 `parse("")` 在配置空白时回退到 `127.0.0.1:18731` 和 `127.0.0.1:18732`（编号 `agent-18731` / `agent-18732`）。网关配置 `jasagent.gateway.agents` 是静态目录，粘性路由再叠加进程内存活探测；集群里 `deploy/k8s/agent.yaml` 把 StatefulSet 固定为 2 副本，并用两个 Service 把 `jasagent-agent-0` / `jasagent-agent-1` 分别钉在 18731 / 18732；网关环境变量 `JASAGENT_GATEWAY_AGENTS` 和官方 `scripts/k8s/probe-egress.sh` 也写死这两台。仓库和 POM 里没有 Nacos 或其它注册中心。

建议改动：agent 运行时向 Nacos 注册网关能访问的地址，退出时注销；网关只从 Nacos 发现实例，Nacos 空或不可用时不允许回退到静态目录或本机约定地址。继续用 MySQL 按 `(tenant_id, user_id)` 做粘性；绑定的实例从发现结果里消失时改派到其它健康实例；若已经没有可用实例，请求失败且不删除粘性行。运行时按负载扩缩容，HPA 最小 2、最大 10。实现改 `jasagent-app`、`jasagent-gateway` 和让本机启动也走 Nacos 所必需的脚本，不改 sidecar 产品代码，实现和测试阶段不改 `deploy/k8s`。等 ATDD 全部通过后，由部署在云电脑上补 Nacos、扩缩容、网络策略和自定义指标，并把 `probe-egress` 改成按发现到的实例验收，不缩小现有 egress 和 gVisor 检查范围。验收和部署都以部署的云电脑为准。网关仍按单副本。

候选交出后，领队最初把 HPA 主指标锁成 CPU。实现和测试指出：agent 常常阻塞在模型调用上，CPU 不一定上升，只按 CPU 可能扩不起来。领队随后改锁：主指标用进行中的会话或在途请求，CPU 只作补充。

## 表态

- 领队：通过。先开评并锁定：agent 注册到 Nacos、退出注销；网关只从 Nacos 发现，空或不可用时不允许静态兜底；注册地址必须是网关能访问的地址，不能是 `127.0.0.1`；实现改 `jasagent-app`、`jasagent-gateway` 和必要脚本，不改 sidecar，实现阶段不改 `deploy/k8s`；验收和部署都在部署的云电脑上做。HPA 最初锁成 CPU 70%，在实现和测试指出模型调用可能不抬升 CPU 后改口：最小 2、最大 10，主指标用进行中的会话或在途请求，CPU 只作补充。全员通过后向 Hang 确认。
- 实现：通过。同意从 Nacos 发现、不允许静态兜底、注册非回环可达地址，以及实现范围只改 `jasagent-app`、`jasagent-gateway` 和必要脚本。不赞成只按 CPU 做 HPA 主指标，因为 agent 常阻塞在模型调用上，CPU 可能不上升。领队改口后再次通过。确认前不改代码。
- 测试：通过。同意上述发现与粘性口径，以及 ATDD 在仓库内验证注册、注销、无静态兜底和改派行为。同样指出 CPU 不宜单独作为扩缩容主指标。领队改口后再次通过。
- 部署：通过。口径按领队最终锁定的来：HPA 最小 2、最大 10，主指标用进行中的会话或在途请求，CPU 只作补充；Nacos 空或不可用时不允许静态兜底，注册地址必须是网关能访问的地址，不能是 `127.0.0.1`。ATDD 通过后在云电脑上补 Nacos、扩缩容、网络策略和自定义指标，并把 `probe-egress` 改成按发现到的实例验收，不缩小范围。确认前且 016 收完前不改 `deploy/k8s`。016 随后已完成。
- Hang：确认「开工」。确认对象是领队在群里向 Hang 锁定的口径，包括 agent 注册到 Nacos 并在退出时注销，网关只从 Nacos 发现且空或不可用时不允许静态兜底，注册地址不能是 `127.0.0.1`，HPA 最小 2、最大 10，主指标用进行中的会话或在途请求、CPU 只作补充，实现改 `jasagent-app`、`jasagent-gateway` 和必要脚本，不改 sidecar，实现阶段不改 `deploy/k8s`，验收和部署都在这台云电脑上做。

## 确认后的口径

1. agent 启动时向 Nacos 注册网关能访问的地址，退出时注销。注册地址不能是容器或本机的 `127.0.0.1`，也不能是其它回环地址。
2. 网关只从 Nacos 发现 agent 实例。Nacos 为空或不可用时，不允许回退到静态目录、`primary()` / `secondary()` / `defaults()`，或 `127.0.0.1:18731` / `127.0.0.1:18732`。需要转发到 agent 的请求此时失败。
3. 继续使用 MySQL 表 `jasagent_gateway_affinity`，按 `(tenant_id, user_id)` 粘性。绑定的实例从发现结果里消失时，改派到其它健康的已发现实例。若已经没有可用实例，请求失败，且不删除粘性行。
4. agent 运行时按负载扩缩容。HPA 最小 2、最大 10；主指标用进行中的会话或在途请求，CPU 只作补充。实现要让 agent 暴露这一主指标，供后续 HPA 使用。
5. 实现改 `jasagent-app`、`jasagent-gateway`，以及让本机启动也走 Nacos 所必需的脚本。本机启动同样不得把回环地址注册进 Nacos。不改 sidecar 产品代码。实现和测试阶段不改 `deploy/k8s`。
6. 网关仍按单副本验收。登录会话和存活探测仍在网关进程内。
7. 等本轮 ATDD 全部通过后，由部署在云电脑上补 Nacos、扩缩容、网络策略和自定义指标，并把 `probe-egress` 改成按发现到的实例验收；不得缩小现有 egress 和 gVisor 检查范围。官方探测整体必须 `PROBE PASSED`。`deploy.md` 要有环境地址、命令和集成测试结果。验收以部署的云电脑为准，不再依赖 Hang 的 Mac。
8. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。口令、令牌、Nacos 认证信息仍只从仓库外可配置路径或 Secret 读取。变更记录不能代替本轮 ATDD。
