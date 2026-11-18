# 需求 021 sidecar 出站不再占用 Tomcat 平台工作线程

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-18（Asia/Shanghai）领队、实现、测试、部署、代码review 均已通过。Hang 于约 18:18 确认「开工021」。020-c-end-tenant-im 已整块完成。对照确认时的 master [`2f0dc84`](https://github.com/xuhang113/JaSAgent/commit/2f0dc84eebdfbcf8e5eda21636981f35128b200c)。

## 这次要改什么

018 已经让离开集群的 HTTP 经 sidecar 的 `/v1/forward` 流式转发，白名单、票据、每 Pod sidecar 和回环约定已经落地。现在的问题是：sidecar 仍用 Spring MVC + Tomcat，`EgressController.forward` 同步处理，票据校验/核销和上游转发都走阻塞的 `HttpClient.send`，再在同一条请求线程里按块抄写响应。一路长流式会占住一条 Tomcat 平台工作线程，并发高时会堵住 `/health` 和新的转发。

这次改成这几件事。

1. `/v1/forward` 在等待 agentpass、上游或抄写流式响应期间，不得占用 Tomcat 平台工作线程。
2. 不改 `/v1/forward` 的 HTTP 约定，也不再整包缓冲。018 的出站范围、流式、白名单、票据、回环和探测约定保持不变。
3. 实现只改 `jasagent-sidecar` 以及对应测试和脚本；不改 `jasagent-app` / `jasagent-llm`，不改运行时执行模型。实现和测试不改 `deploy/k8s`。
4. 实现开 PR，代码审查通过后再交给测试。ATDD 全部通过后，若需要环境变量或启动参数，由部署开 PR 调整清单，但不把生产线程上限永久改低。

界面仍只放在 `jasagent-web`。网关仍按单副本。验收以部署的云电脑为准。运行时全程异步不在本轮。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-sidecar | 提供 `/v1/forward`；Spring MVC + Tomcat；票据和上游用阻塞 `HttpClient.send`；同一请求线程按块抄写流式响应 | 等待 agentpass、上游或抄写流式响应期间不得占用 Tomcat 平台工作线程。优先在现有 Spring MVC 上用虚拟线程或 Servlet 异步，不整模块改成 WebFlux。若用虚拟线程，抄写时不得把载体线程钉死。HTTP 约定、流式、白名单和票据规则保持不变 |
| jasagent-app / jasagent-llm | 离开集群的 HTTP 经回环调用 `/v1/forward`；调用方仍用阻塞 `send` | 本轮不改 |
| scripts | 已有 018 出站测试脚本 | 补本轮可执行测试脚本，须能按下面的验收独立判定平台线程和流式首块 |
| deploy/k8s | 每个 agent Pod 带一个 sidecar 容器；官方探测已按 018 要求证明经 `/v1/forward` 出站 | 实现和测试阶段不动。ATDD 全部通过后，仅在需要环境变量或启动参数时由部署开 PR 调整；不得把生产线程上限永久改低 |
| jasagent-web / jasagent-gateway | 控制台与网关入口 | 本轮不改 |

## 改动地方

**jasagent-sidecar**

`/v1/forward` 在等待 agentpass、上游或把上游响应按块抄回调用方期间，不得占用 Tomcat 平台工作线程（指操作系统上的平台线程，不是虚拟线程，也不是请求槽位）。实现优先在现有 Spring MVC 上用虚拟线程或 Servlet 异步，不要把本模块整份改成 WebFlux。若选用虚拟线程，抄写响应时不得把载体线程钉死。

`/v1/forward` 的 HTTP 约定不变：方法、路径、目的地址头与票据头、流式请求体和响应体、不跟随重定向，都保持 018 已锁定的行为。不得再把整个请求体或响应体读进内存后再转发。大模型白名单和票据核销规则保持现有约定。本轮改到的类、常量、方法都要有注释。票据、口令和密钥不写进仓库、日志、异常或响应。

**jasagent-app / jasagent-llm**

本轮不改。agent 侧调用仍走现有 `/v1/forward`。运行时全程异步放到后续轮次。

**scripts**

为本轮验收提供可执行测试脚本。脚本须能把 sidecar 的 Tomcat 平台工作线程（操作系统平台线程）故意压低，再发起超过该上限的并发长流式 `/v1/forward`，并判定：这些流持续出块，首块在上游结束前到达，期间 `/health` 和新的转发在超时内可用。不得只调 `server.tomcat.threads.max` 就算完成（启用虚拟线程时该值往往是请求槽位，压低后可能反而限制并发，测不出「不占平台线程」）。实现提交可以按需要修正与本轮验收对齐的脚本，但不得修改 `deploy/k8s` 下的清单。

**deploy/k8s**

实现和测试提交不得修改这个目录。把线程数调低只用于验收，不得把生产清单里的线程上限永久改低。等本轮 ATDD 全部通过后，若需要环境变量或启动参数，由部署开 Pull Request 调整清单，审查后再合并、再上集群。官方探测仍须 `PROBE PASSED`，现有 egress 与 gVisor 检查范围不得缩小。`deploy.md` 要有环境地址、命令和集成测试结果。验收以部署的云电脑为准，不再依赖 Hang 的 Mac。

**jasagent-web / jasagent-gateway**

本轮不改。网关仍按单副本。公网入口维持 019。

## 数据库

不改表结构。不新增库。

## 中间件

不引进新的中间件。Nacos、MySQL、Redis 的访问仍不经 sidecar。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。不加消息队列。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、票据和密钥不写进仓库、日志、异常或响应。实现按本 spec 开 Pull Request，由 JaSAgent 代码review 对照本规格审查后再交给测试。实现和测试阶段不改 `deploy/k8s`；等本轮 ATDD 全部通过后，若需要调整清单，由部署开 PR。变更记录不能代替本轮 ATDD。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. `/v1/forward` 在等待 agentpass、上游或抄写流式响应期间，不占用 Tomcat 平台工作线程（操作系统上的平台线程，不是虚拟线程或请求槽位）。
2. 把 sidecar 的 Tomcat 平台工作线程故意压低后，仍能同时扛住超过该上限的长流式 `/v1/forward`。这些流要持续出块，首块须在上游结束前到达，证明没有整包缓冲。
3. 在第 2 条的并发长流式进行期间，`/health` 和新的 `/v1/forward` 仍要在超时内可用。
4. 第 2 条的压低不得只调 `server.tomcat.threads.max`。启用虚拟线程时，该值往往是请求槽位而不是操作系统平台线程，只调它可能反而限制并发，测不出「等待和抄写时不占平台线程」。
5. `/v1/forward` 的 HTTP 约定不变，且不再整包缓冲。
6. 018 规格第 1 到 9 条的行为约定仍然成立，本轮复跑须通过：离开集群的 HTTP 仍由 agent 经 `/v1/forward` 发出；Nacos（含 gRPC 9848）、MySQL、Redis、网关和本集群服务不经 sidecar；向 Nacos 注册的仍是非回环地址；`/v1/forward` 对请求体和响应体做流式转发；大模型白名单和票据核销规则仍然有效；本机启动脚本拉起 sidecar，两台 agent 可共用一个回环 sidecar。本轮不要求再改 `jasagent-app` / `jasagent-llm`。
7. 实现改动只覆盖 `jasagent-sidecar` 以及对应测试和脚本，不改 `jasagent-app` / `jasagent-llm`，不改运行时执行模型。
8. 实现和测试提交不修改 `deploy/k8s` 下的清单文件。
9. 本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、票据和密钥不出现在仓库、说明或日志里。

第 1 到第 9 条在 ATDD 阶段验收。下面几条在部署阶段验收。

10. 官方 `scripts/k8s/probe-egress.sh` 整体为 `PROBE PASSED`；现有 egress 与 gVisor 检查范围没有被缩小。018 的每 Pod sidecar 与出站约定仍然有效。网关仍是单副本。015 / 016 / 017 / 018 / 020 在云电脑上已通过的集成检查仍然成立。
11. 生产 `deploy/k8s` 的线程上限没有被永久改低。若本轮需要环境变量或启动参数，已由部署开 PR 调整，而不是直接推 master。
12. `requirements/021-sidecar-egress-threads/deploy.md` 写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

## 还没定

没有还没定的口径。实现选用虚拟线程还是 Servlet 异步，由实现在「现有 Spring MVC、不整模块改成 WebFlux、不钉死载体线程」的约束内决定。运行时全程异步、agent 侧调用改成不占线程，不在本轮，仍留在已确认的总计划后续轮次。
