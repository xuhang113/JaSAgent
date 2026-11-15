# 需求 018 让离开集群的 HTTP 走 sidecar

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-15（Asia/Shanghai）领队、实现、测试、部署和 Hang 均已通过。017-nacos-agent-discovery 已整块完成。Hang 确认先开工 018；019-gateway-public-ingress 不在本轮范围。

## 这次要改什么

017 已经把 agent 注册到 Nacos、网关从 Nacos 发现，以及 HPA 扩缩容收完。界面仍只放在 `jasagent-web`。现在的问题是：sidecar 已经存在，官方探测也会打到 `/v1/forward`，但 agent 进程并不经 sidecar 出站。`jasagent-app` / `jasagent-llm` 向 agentpass 取票后，由进程自己向外拨号。本机 `scripts/start.sh` 不启动 sidecar。集群里 sidecar 是独立的共享 Deployment，`replicas: 1`，不随 agent 扩缩。转发会把整个请求体读进内存，大模型流式响应过不去。探测打的是 sidecar 自身，证明不了是 agent 进程经 sidecar 出站。

这次改成这几件事。

1. 离开集群的 HTTP（大模型、外部工具、MCP 等）必须经 sidecar 的 `/v1/forward`，不能绕开。
2. Nacos（含 gRPC 9848）、MySQL、Redis、网关和本集群服务不走 sidecar。017 向 Nacos 注册非回环地址的约定不变；回环只用于同一 Pod 内访问 sidecar。
3. 集群里每个 agent Pod 带一个 sidecar 容器，经本机回环访问，随 agent 扩缩。本机启动也走同一条路径，两台 agent 可以共用一个回环 sidecar。
4. 转发必须流式，不能整包缓冲。保留现有的大模型白名单和票据核销规则。
5. 实现改 `jasagent-sidecar` 以及必要的 `jasagent-app`、`jasagent-llm` 和脚本，实现和测试阶段不改 `deploy/k8s`。等 ATDD 全部通过后，由部署在云电脑上改清单、网络策略和探测，并写 `deploy.md`。探测必须证明是 agent 进程出站，且不能直连外网。

网关仍按单副本。界面仍只放在 `jasagent-web`。验收和部署都以部署的云电脑为准，不再依赖 Hang 的 Mac。本轮不做公网入口。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-sidecar | 独立进程提供 `/v1/forward`；整包缓冲请求和响应；大模型白名单和票据核销已有；集群里是共享 Deployment，一份副本 | 改为流式转发；继续提供 `/v1/forward`，保留白名单和票据核销；能在与 agent 同 Pod 时经回环被访问，也能在本机被两台 agent 共用 |
| jasagent-app | 向 agentpass 取票后，由本进程直接向外拨号，不调用 sidecar 的 `/v1/forward` | 离开集群的 HTTP 改为经本机回环调用 sidecar 的 `/v1/forward`；Nacos、MySQL、Redis、网关和本集群服务仍直连，不经 sidecar；向 Nacos 注册的仍须是非回环地址 |
| jasagent-llm | 用 LangChain4j / JDK HttpClient 直接访问大模型，不走 sidecar | 离开集群的大模型 HTTP 改为经 sidecar 的 `/v1/forward` 发出，并保持流式 |
| scripts | 本机 `start.sh` 不启动 sidecar | 本机启动要拉起 sidecar，两台 agent 共用一个回环 sidecar，出站路径与集群一致 |
| deploy/k8s | sidecar 是独立 Deployment；探测打 sidecar Service 本身；云电脑上有按 agent Pod IP 丢弃出站的兜底 | 实现和测试阶段不动。ATDD 全部通过后，由部署改为每个 agent Pod 带一个 sidecar 容器，补网络策略和进程或用户级出站规则，改掉按 Pod IP 丢弃的兜底，并把探测改成证明 agent 进程经 `/v1/forward` 出站且不能直连 |
| jasagent-web / jasagent-gateway | 控制台与网关入口 | 本轮不改 |

## 改动地方

**jasagent-sidecar**

`/v1/forward` 必须按流式转发请求体和响应体，不得再把整个报文体读进内存后再发给上游或客户端。大模型白名单和票据核销规则保持现有约定：白名单内的主机可以免票，其它主机仍须先核销票据再转发。本轮改到的类、常量、方法都要有注释。票据、口令和密钥不写进仓库、日志、异常或响应。

**jasagent-app**

离开集群的 HTTP（大模型、外部工具、MCP 等）必须经 sidecar 的 `/v1/forward` 发出，不能再由本进程直接向外网拨号。访问地址是本机回环上的 sidecar，不能把回环地址注册进 Nacos。Nacos 注册与发现（包括 gRPC 9848）、MySQL、Redis、网关以及本集群内的其它服务，仍直连，不经 sidecar。017 已锁定的注册约定不变：注册地址必须是网关能访问的非回环地址。本轮改到的类、常量、方法都要有注释。

**jasagent-llm**

大模型调用要改到经 sidecar 的 `/v1/forward` 发出，并且响应必须保持流式，不能在 sidecar 或调用链上整包缓冲。白名单和票据规则与 sidecar 侧保持一致。本轮改到的类、常量、方法都要有注释。

**scripts**

本机日常入口必须启动 sidecar。`scripts/start.sh` 以及为完成本轮本机启动所必需的相关脚本，要拉起一个回环上的 sidecar，本机两台 agent 可以共用它，出站走 `/v1/forward`。相关脚本和新增的函数、常量、用法说明都要有注释。实现提交可以按需要修正与出站对齐的脚本，但不得修改 `deploy/k8s` 下的清单。

**deploy/k8s**

实现和测试提交不得修改这个目录。等本轮 ATDD 全部通过后，由部署在云电脑上把 sidecar 从独立的共享 Deployment 改成每个 agent Pod 里的一个 sidecar 容器，经本机回环被 agent 访问，并随 agent 扩缩。

网络策略仍只作用到 Pod。同一网络命名空间内，要用进程或用户级出站规则，或等价手段，保证是 agent 进程不能直连外网，而 sidecar 可以代表它出站。云电脑上现行按 agent Pod IP 丢弃出站的兜底必须改掉，否则会拦住同 Pod 里 sidecar 的合法出站。

官方 `scripts/k8s/probe-egress.sh` 以及本轮所需的补充探测，必须证明是 agent 进程经 `/v1/forward` 出站，且 agent 不能直连外网。不得只探测 sidecar Service 自身就算通过。现有的 egress 和 gVisor 检查范围不得缩小，整体必须 `PROBE PASSED`。然后在部署的云电脑上部署，`deploy.md` 写明环境地址、命令和集成测试结果。验收环境以这台云电脑为准，不再依赖 Hang 的 Mac。

**jasagent-web / jasagent-gateway**

本轮不改。网关仍按单副本。不处理公网入口。

## 数据库

不改表结构。不新增库。口令、令牌仍只从仓库外文件或 Secret 读取，JDBC 地址仍不能带 `password=`。

## 中间件

不引进新的中间件。Nacos、MySQL、Redis 的访问不经 sidecar。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。不加消息队列。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、票据和密钥不写进仓库、日志、异常或响应。开发规范里的 K8s 文件按这个时机补：实现和测试阶段不改 `deploy/k8s`；等本轮 ATDD 全部通过后，由部署补清单、网络策略、出站规则和探测，部署并交出带环境地址、命令和集成测试结果的 `deploy.md`。变更记录不能代替本轮 ATDD。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. 离开集群的 HTTP（大模型、外部工具、MCP 等）由 agent 进程经 sidecar 的 `/v1/forward` 发出，而不是由 agent 进程直接向外网拨号。
2. Nacos 注册与发现（包括 gRPC 9848）、MySQL、Redis、网关以及本集群内的其它服务，不经 sidecar。
3. agent 向 Nacos 注册的地址仍是网关能访问的非回环地址，不是 `127.0.0.1`、`localhost` 或其它回环地址。回环只用于同一进程组或同一 Pod 内访问 sidecar。
4. sidecar 对 `/v1/forward` 的请求体和响应体做流式转发，不再整包缓冲。
5. 现有的大模型白名单和票据核销规则仍然有效。
6. 本机启动脚本拉起 sidecar；本机两台 agent 可以共用一个回环 sidecar，出站路径与集群约定一致。
7. 实现改动覆盖 `jasagent-sidecar`，以及把出站挂到 sidecar 所必需的 `jasagent-app`、`jasagent-llm` 和脚本。
8. 实现和测试提交不修改 `deploy/k8s` 下的清单文件。
9. 本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、票据和密钥不出现在仓库、说明或日志里。
10. ATDD 全部通过后，部署在云电脑集群里把 sidecar 放进每个 agent Pod，agent 经本机回环访问，sidecar 随 agent 扩缩，不再使用独立的一份共享 Deployment。
11. 探测证明是 agent 进程经 `/v1/forward` 出站，且 agent 进程不能直连外网；不得只打 sidecar Service 自身就算通过。
12. 同一网络命名空间已采用进程或用户级出站规则，或等价手段；原先按 agent Pod IP 丢弃出站的兜底已经改掉，sidecar 的合法出站不被误伤。
13. 本轮所需的网络策略已补上。官方探测里现有的 egress 与 gVisor 检查范围没有被缩小。整体结果为 `PROBE PASSED`。
14. 015 / 016 / 017 的 I-001 到 I-016 类检查在云电脑上仍然通过。网关仍是单副本。Nacos 发现和注册仍然有效。
15. `requirements/018-sidecar-egress/deploy.md` 写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

第 10 到第 15 条在部署阶段验收。第 1 到第 9 条在 ATDD 阶段验收。

## 还没定

没有还没定。出站范围、每 Pod sidecar、本机共用回环 sidecar、流式转发，以及 NetworkPolicy / Pod IP 兜底的处理，均已锁定。集群清单、网络策略、出站规则和探测脚本在本轮 ATDD 全部通过后由部署补全，实现和测试阶段不动。云电脑继续用 k3s + gVisor。019 的公网入口本轮不做。
