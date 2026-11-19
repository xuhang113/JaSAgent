# 需求 022 运行时全程异步

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-18（Asia/Shanghai）领队、调研、实现、测试、部署、代码review 均已通过，补充已并进口径。Hang 于 2026-11-19 约 08:06（Asia/Shanghai）确认「022 开工」。021-sidecar-egress-threads 已整块完成。对照确认时的产品 master [`11f497c`](https://github.com/xuhang113/JaSAgent/commit/11f497c887a9eb16620f274bcc48f15a01f0099b)；领队随后写入的 021 `status.md` 在 [`6d904c9`](https://github.com/xuhang113/JaSAgent/commit/6d904c91cdf8b06cbdb40356092208b7f2f9aeaf)，未改产品代码。

## 这次要改什么

021 已经让 sidecar 的 `/v1/forward` 在等待或抄写期间不再占用 Tomcat 平台工作线程，018 的出站约定仍然成立。现在的问题在 agent 侧：出站仍用阻塞的 `HttpClient.send`，大模型等待虽包在 `VirtualIo` 里却仍在同一条虚拟任务上同步 join，工作流仍走 `invokeSync`，尤其是 `resume` 会在 agent 的请求线程上 join 完剩余执行，一次长恢复会占住一条 Tomcat 平台工作线程。并发高时会堵住 `/health` 和新的提交、发消息。

这次改成这几件事。

1. 等待 sidecar、模型流或工具，以及恢复执行期间，agent（`jasagent-app` 进程）不得占用操作系统平台工作线程。
2. 不改 `/v1/forward` 的 HTTP 约定。018 出站范围和 021 sidecar 验收保持不变。
3. 实现只改运行时相关模块（`jasagent-llm`、`jasagent-app`，以及接线所需的 `jasagent-workflow` / `jasagent-controller`；若调度器等运行时模块在等待时仍占用 agent 的操作系统平台线程，也留在本轮），以及对应测试和脚本。不改 sidecar、网关和 `jasagent-web`。实现和测试不改 `deploy/k8s`。
4. 实现开 PR，代码审查通过后再交给测试。ATDD 全部通过后，若需要环境变量或启动参数，由部署开 PR 调整清单，但不把生产线程上限永久改低。
5. 异步移交时，会话、租户和权限上下文不得丢失，也不得串到别人的请求上；020 的租户隔离仍须成立。

界面仍只放在 `jasagent-web`。网关仍按单副本。验收以部署的云电脑为准。每会话关注点和技能自动摘要、agentpass 不在本轮。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-llm | agent 经回环调用 sidecar `/v1/forward`；`SidecarForward.exchange` 仍用阻塞 `HttpClient.send`，调用方在同一线程读流；`SidecarLlmTransport` / `LangChain4jLlmClient` / `GrokLlmClient` 包在 `VirtualIo.supply` 里，实际等待仍同步 join | 等待 sidecar 或模型流期间不得占用 agent 的操作系统平台工作线程。优先用现有 `VirtualIo`、虚拟线程或 Servlet 异步，不整模块改成 WebFlux。读流时不得钉死载体；出站也要避开 021 里 JDK `ofInputStream().read` 在 `synchronized` 里钉住载体那种读法。`/v1/forward` 的 HTTP 约定不变 |
| jasagent-app | `LocalRuntime` 已有虚拟线程工人池；`submit` / `appendMessage` 在正常路径上很快返回；`resume` 和 `interrupt` 会在请求线程上 join；`application.properties` 尚未打开 Tomcat 虚拟线程开关 | 等待工具或继续执行、尤其是恢复路径，不得占用 agent 的 Tomcat 平台工作线程。会话、租户和权限上下文在异步移交时不得丢失或串线 |
| jasagent-workflow / jasagent-controller | `LangGraphWorkflowEngine.execute` 已返回 `CompletionStage`，随后仍走 `invokeSync`；`ExecutionController.events` 已先起虚拟线程再返回 `SseEmitter` | 仅在接线需要，或这些模块在等待时仍占用 agent 操作系统平台线程时改动。不改对外 HTTP 约定里与本轮无关的部分 |
| 调度器及其他运行时模块 | 若在等待时仍占用 agent 的操作系统平台线程 | 留在本轮一并改掉 |
| jasagent-sidecar / jasagent-gateway / jasagent-web | sidecar 已按 021 不占平台线程；网关与控制台已按 019 / 020 落地 | 本轮不改 |
| scripts | 已有 018 / 021 出站和 sidecar 线程验收脚本 | 补本轮可执行测试脚本，须能按下面的验收独立判定 agent 平台线程、恢复路径、思考或首块，以及不同租户并发时的上下文隔离 |
| deploy/k8s | 每个 agent Pod 带一个 sidecar；官方探测已按 018 / 021 要求证明出站与 sidecar 线程验收 | 实现和测试阶段不动。ATDD 全部通过后，仅在需要环境变量或启动参数时由部署开 PR 调整；不得把生产线程上限永久改低 |

## 改动地方

**jasagent-llm**

`SidecarForward` 以及经它出站的大模型传输，在等待 sidecar 或读取流式响应期间，不得占用 agent 的操作系统平台工作线程。实现优先用现有 `VirtualIo`、虚拟线程或非阻塞抄写，不要把本模块整份改成 WebFlux。读流时不得钉死载体线程，也不得再采用 JDK `ofInputStream().read` 放在 `synchronized` 里那种会钉住载体的读法。

`/v1/forward` 的 HTTP 约定不变：方法、路径、目的地址头与票据头、流式请求体和响应体、不跟随重定向，都保持 018 / 021 已锁定的行为。不得再把整个请求体或响应体读进内存后再转发。本轮改到的类、常量、方法都要有注释。票据、口令和密钥不写进仓库、日志、异常或响应。

**jasagent-app**

运行时在等待 sidecar、模型流或工具，以及 `resume` 恢复执行期间，不得在 agent 的请求线程（Tomcat 平台工作线程）上同步 join 完整段剩余执行。`interrupt` 若同样会在请求线程上长时间占用平台线程，也一并改掉。实现可继续用现有虚拟线程工人池、`VirtualIo` 或 Servlet 异步，但必须保证：请求线程在交出等待之后就能回到池里，`/health` 和新的提交、发消息在超时内仍然可用。

异步移交时，会话、租户和权限上下文必须随任务一起带走，不得丢失，也不得串到别人的请求上。020 已锁定的租户隔离继续有效：网关仍须拒绝非管理员的集群接口和他人会话，本轮不得打开新的串租户路径。

本轮改到的类、常量、方法都要有注释。

**jasagent-workflow / jasagent-controller**

只在接线需要，或这些模块在等待时仍占用 agent 操作系统平台线程时改动。例如工作流若仍在平台线程上 `invokeSync` 等到结束，应改到不占平台线程的等待方式。`ExecutionController.events` 已有的异步返回可以保留。不把这些模块整份改成 WebFlux。

**调度器及其他运行时模块**

若核对这些模块后，发现它们在等待时仍占用 agent 的操作系统平台工作线程，本轮一并改掉。不要把范围扩到 agentpass 或技能自动摘要。

**jasagent-sidecar / jasagent-gateway / jasagent-web**

本轮不改。sidecar 的 021 验收继续成立。网关仍按单副本。公网入口维持 019。界面仍只放在 `jasagent-web`。

**scripts**

为本轮验收提供可执行测试脚本。脚本须能把 **agent**（`jasagent-app` 进程）的 Tomcat 平台工作线程（操作系统平台线程）故意压低，再发起超过该上限的并发长 ReAct / 流式调用，并且必须覆盖恢复路径，同时用不同租户并发执行。并判定：这些调用的思考或首块在上游结束前到达；期间 `/health` 和新的提交、发消息在超时内可用；会话、租户和权限上下文没有丢失，也没有串到别人的请求上。不得只调 `server.tomcat.threads.max` 就算完成（启用虚拟线程时该值往往是请求槽位，压低后可能反而限制并发，测不出「不占平台线程」）。实现提交可以按需要修正与本轮验收对齐的脚本，但不得修改 `deploy/k8s` 下的清单。

**deploy/k8s**

实现和测试提交不得修改这个目录。把线程数调低只用于验收，不得把生产清单里的线程上限永久改低。等本轮 ATDD 全部通过后，若需要环境变量或启动参数，由部署开 Pull Request 调整清单，审查后再合并、再上集群。官方探测仍须 `PROBE PASSED`，现有 egress、sidecar 线程与 gVisor 检查范围不得缩小。`deploy.md` 要有环境地址、命令和集成测试结果。验收以部署的云电脑为准，不再依赖 Hang 的 Mac。

## 数据库

不改表结构。不新增库。用户和会话仍使用现有 MySQL，租户隔离约定与 020 保持一致。

## 中间件

不引进新的中间件。Nacos、MySQL、Redis 的访问仍不经 sidecar。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。不加消息队列。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、票据和密钥不写进仓库、日志、异常或响应。实现按本 spec 开 Pull Request，由 JaSAgent 代码review 对照本规格审查后再交给测试。实现和测试阶段不改 `deploy/k8s`；等本轮 ATDD 全部通过后，若需要调整清单，由部署开 PR。变更记录不能代替本轮 ATDD。不直接推产品代码到 master。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. 等待 sidecar、模型流或工具，以及恢复执行期间，agent（`jasagent-app` 进程）不占用操作系统平台工作线程（不是虚拟线程或请求槽位）。
2. 把 agent 的 Tomcat 平台工作线程故意压低后，仍能同时扛住超过该上限的长 ReAct / 流式调用，并且必须覆盖恢复路径。这些调用的思考或首块须在上游结束前到达。
3. 在第 2 条的并发长调用进行期间，`/health` 和新的提交、发消息仍要在超时内可用。
4. 第 2 条的压低不得只调 `server.tomcat.threads.max`。启用虚拟线程时，该值往往是请求槽位而不是操作系统平台线程，只调它可能反而限制并发，测不出「等待和恢复时不占平台线程」。
5. `/v1/forward` 的 HTTP 约定不变。018 的出站范围、流式、白名单、票据和回环约定仍然成立。021 sidecar「等待或抄写期间不占用 Tomcat 平台工作线程」的验收继续成立。
6. 异步移交时，会话、租户和权限上下文不得丢失，也不得串到别人的请求上。020 的租户隔离仍须成立。本条验收必须用不同租户并发覆盖。
7. 实现改动只覆盖运行时相关模块（`jasagent-llm`、`jasagent-app`，以及接线所需的 `jasagent-workflow` / `jasagent-controller`；若调度器等运行时模块在等待时仍占用 agent 的操作系统平台线程，也留在本轮）以及对应测试和脚本。不改 sidecar、网关和 `jasagent-web`。
8. 实现和测试提交不修改 `deploy/k8s` 下的清单文件。
9. 本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、票据和密钥不出现在仓库、说明或日志里。实现优先用现有 `VirtualIo`、虚拟线程或 Servlet 异步，不整模块改成 WebFlux；读流时不钉死载体，agent 侧出站避开 021 那种钉住载体的读法。

第 1 到第 9 条在 ATDD 阶段验收。下面几条在部署阶段验收。

10. 官方 `scripts/k8s/probe-egress.sh` 整体为 `PROBE PASSED`；现有 egress、sidecar 线程与 gVisor 检查范围没有被缩小。018 / 021 在云电脑上已通过的集成检查仍然成立。网关仍是单副本。015 / 016 / 017 / 018 / 020 / 021 在云电脑上已通过的集成检查仍然成立。
11. 生产 `deploy/k8s` 的线程上限没有被永久改低。若本轮需要环境变量或启动参数，已由部署开 PR 调整，而不是直接推 master。
12. `requirements/022-runtime-full-async/deploy.md` 写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

## 还没定

没有还没定的口径。实现选用现有 `VirtualIo`、虚拟线程还是 Servlet 异步，由实现在「不整模块改成 WebFlux、读流不钉死载体、出站避开 021 那种钉住载体的读法」的约束内决定。每会话关注点和技能自动摘要、agentpass、MySQL 数据目录、网关高可用，以及 019 尚未正式关闭的项，不在本轮。
