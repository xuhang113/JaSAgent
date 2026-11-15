# 需求 018 让离开集群的 HTTP 走 sidecar 评审记录

- 日期：2026-11-15（Asia/Shanghai）
- 块：sidecar（`jasagent-sidecar` / 必要的 `jasagent-app`、`jasagent-llm` 和脚本；`deploy/k8s` 在 ATDD 之后由部署补）
- 结论：已确认。领队、实现、测试、部署均写「通过」，Hang 确认「先开工018」。确认记录如下。本文件和 `spec.md` 在确认之后写成。017-nacos-agent-discovery 已整块完成后再写本轮规格。019-gateway-public-ingress 已在群里讨论并全员通过，但 Hang 决定先做 018，019 不在本轮范围。

## 候选

调研在群里交出的候选 **018-sidecar-egress**。对照当时的 `master`（017 部署提交 `2a94d06`）：模块 `jasagent-sidecar` 已存在，并提供 `/v1/forward`；官方 `probe-egress` 也会打到 sidecar 的 Service。但 `jasagent-app` / `jasagent-llm` 并不调用 `/v1/forward`：它们向 agentpass 取票后，由进程自己向外拨号（LangChain4j / JDK HttpClient）。本机 `scripts/start.sh` 不启动 sidecar，`agent.yaml` 也没有 sidecar 地址。集群里 sidecar 是独立的共享 Deployment，`replicas: 1`，和 agent 不在同一 Pod（两者都监听 8080）。转发会把整个请求体读进内存（`readAllBytes`、`BodyHandlers.ofByteArray()`），HTTP/1.1，不跟随重定向，因此大模型流式响应过不去。探测打的是 sidecar 自身，证明不了是 agent 进程经 sidecar 出站。集群 NetworkPolicy 本应拦住 agent 直连外网，但 sidecar 不是标准正向代理，`entrypoint.sh` 虽可设置 Java HTTP/HTTPS 代理，`agent.yaml` 并未配置 `JASAGENT_HTTPS_PROXY_HOST`。

建议改动：离开集群的 HTTP（大模型、外部工具、MCP 等）必须经 sidecar 的 `/v1/forward`，不能绕开。Nacos 注册与发现（含 gRPC 9848）、MySQL、Redis、网关和本集群服务不走 sidecar，避免和 017 的注册死锁。集群里每个 agent Pod 带一个 sidecar 容器，经本机回环访问，随 agent 扩缩；本机两台 agent 可以共用一个回环 sidecar，`start.sh` 要启动 sidecar 并走同一条路径。转发必须流式，不能整包缓冲。保留现有的大模型白名单和票据核销规则。实现改 `jasagent-sidecar` 以及把出站挂到 sidecar 所必需的 `jasagent-app`、`jasagent-llm` 和脚本；实现和测试不改 `deploy/k8s`。ATDD 通过后，部署在云电脑上改清单、网络策略和探测：必须证明是 agent 进程自己经 `/v1/forward` 出站，且不能直连外网。NetworkPolicy 只到 Pod，同一网络命名空间要用进程或用户级出站规则（或等价手段）；按 agent Pod IP 丢弃出站的兜底必须改掉，否则 sidecar 合法出站会被拦住。现有 egress、gVisor 检查不缩小，官方探测仍须 `PROBE PASSED`。验收和部署都以部署的云电脑为准。

候选交出后，测试先未写「通过」，要求先锁定出站范围：Nacos 和其它 HTTP 是否也必须走 sidecar。实现和部署提出：只有离开集群的 HTTP 走 sidecar；Nacos（含 gRPC 9848）、MySQL、Redis 和本集群服务不走，这样 017 的注册不会死锁。领队按此锁定，并锁定集群内每 Pod 一个 sidecar、本机可共用一个回环 sidecar，以及部署提出的 NetworkPolicy / 同一网络命名空间规则和 Pod IP 兜底必须改掉。测试随后写「通过」。

## 表态

- 领队：通过。锁定：离开集群的 HTTP 必须经 sidecar 的 `/v1/forward`，不能绕开；Nacos、MySQL、Redis、网关和本集群服务不走 sidecar。集群里每个 agent Pod 带一个 sidecar 容器，经本机回环访问，随 agent 扩缩；本机两台 agent 可以共用一个回环 sidecar。转发必须流式，不能整包缓冲。实现改 `jasagent-sidecar` 以及必要的 `jasagent-app`、`jasagent-llm` 和脚本，实现和测试不改 `deploy/k8s`。ATDD 通过后，部署在云电脑上改清单、网络策略和探测，要证明是 agent 自己的出站且不能直连；同一网络命名空间要用进程或用户级出站规则，按 agent Pod IP 丢弃出站的兜底必须改掉。现有 egress、gVisor 不缩小，官方探测仍须 `PROBE PASSED`。全员通过后向 Hang 确认。
- 实现：通过。同意上述出站范围、每 Pod sidecar、本机共用回环 sidecar、流式转发，以及实现范围。确认前不改代码。
- 测试：先要求锁清出站范围后再表态。领队锁定「离开集群的 HTTP 走 sidecar，Nacos（含 gRPC 9848）、MySQL、Redis 和本集群服务不走」之后，写「通过」。同意验收必须证明是 agent 进程经 `/v1/forward` 出站且不能直连，官方探测仍须 `PROBE PASSED`。确认前不写 tdd、不改代码。
- 部署：通过。强调 NetworkPolicy 只作用到 Pod，同一网络命名空间要有进程或用户级出站规则（或等价手段）；云电脑上按 agent Pod IP 丢弃出站的兜底，在 sidecar 与 agent 同 Pod 后必须改掉，否则会拦住 sidecar 的合法出站。ATDD 通过后在云电脑上改清单、网络策略和探测，不缩小现有 egress、gVisor 检查，官方探测仍须 `PROBE PASSED`。确认前不改清单、不部署。
- Hang：确认「先开工018」。确认对象是领队在群里向 Hang 锁定的上述口径。当时 019-gateway-public-ingress 也已全员通过、待选定隧道，Hang 决定先做 018，019 挂起，不在本轮写入规格。

## 确认后的口径

1. 离开集群的 HTTP（大模型、外部工具、MCP 等）必须经 sidecar 的 `/v1/forward` 出站，不能由 agent 进程直接向外拨号绕开 sidecar。
2. Nacos 注册与发现（包括 gRPC 9848）、MySQL、Redis、网关以及本集群内的其它服务，不走 sidecar。017 的注册与发现约定保持有效：agent 向 Nacos 注册的仍须是网关能访问的非回环地址；回环只用于同一 Pod 内访问 sidecar。
3. 集群里每个 agent Pod 带一个 sidecar 容器，agent 经本机回环访问 sidecar，sidecar 随 agent 扩缩，不再使用独立的共享 sidecar Deployment。
4. 本机 `scripts/start.sh` 要启动 sidecar，并走与集群相同的 `/v1/forward` 路径。本机两台 agent 可以共用一个回环 sidecar。
5. sidecar 转发必须流式，不能把整个请求体或响应体读进内存后再转发。
6. 保留现有的大模型白名单和票据核销规则。
7. 实现改 `jasagent-sidecar`，以及把出站挂到 sidecar 所必需的 `jasagent-app`、`jasagent-llm` 和脚本。实现和测试阶段不改 `deploy/k8s`。
8. 等本轮 ATDD 全部通过后，由部署在云电脑上改清单、网络策略和探测。探测必须证明是 agent 进程经 `/v1/forward` 出站，且 agent 不能直连外网。同一网络命名空间要用进程或用户级出站规则，或等价手段；按 agent Pod IP 丢弃出站的兜底必须改掉，不得误伤 sidecar 的合法出站。现有 egress、gVisor 检查范围不得缩小，官方探测整体必须 `PROBE PASSED`。`deploy.md` 要有环境地址、命令和集成测试结果。验收以部署的云电脑为准，不再依赖 Hang 的 Mac。
9. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。口令、令牌、票据和密钥仍只从仓库外可配置路径或 Secret 读取。变更记录不能代替本轮 ATDD。
10. 界面仍只放在 `jasagent-web`。网关仍按单副本。本轮不处理 019 的公网入口。
