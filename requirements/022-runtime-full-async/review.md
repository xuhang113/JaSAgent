# 需求 022 运行时全程异步评审记录

- 日期：2026-11-19（Asia/Shanghai）
- 块：运行时（`jasagent-llm`、`jasagent-app`，以及接线所需的 `jasagent-workflow` / `jasagent-controller`；若调度器等运行时模块在等待时仍占用 agent 的操作系统平台线程，也留在本轮。对应测试和脚本；`deploy/k8s` 仅在 ATDD 之后如需调整再由部署走 PR）
- 结论：已确认。领队、调研、实现、测试、部署、代码review 均写「通过」，补充已并进口径。Hang 于 2026-11-19 约 08:06（Asia/Shanghai）确认「022 开工」。本文件和 `spec.md` 在确认之后写成。021-sidecar-egress-threads 已整块完成：实现 [PR #9](https://github.com/xuhang113/JaSAgent/pull/9)、部署 [PR #10](https://github.com/xuhang113/JaSAgent/pull/10) 已按顺序合入，产品代码基准为 [`11f497c`](https://github.com/xuhang113/JaSAgent/commit/11f497c887a9eb16620f274bcc48f15a01f0099b)。之后领队写入 021 的 `status.md`，当时 master 为 [`6d904c9`](https://github.com/xuhang113/JaSAgent/commit/6d904c91cdf8b06cbdb40356092208b7f2f9aeaf)，未改产品代码。

## 候选

调研在群里交出的候选 **022-runtime-full-async**。对照当时的产品 `master`（[`11f497c`](https://github.com/xuhang113/JaSAgent/commit/11f497c887a9eb16620f274bcc48f15a01f0099b)）：021 已经让 sidecar 的 `/v1/forward` 在等待或抄写期间不再占用 Tomcat 平台工作线程，018 的出站范围、流式、白名单、票据和回环约定仍然成立。缺口在 agent 侧。`SidecarForward.exchange` 仍用阻塞的 `HttpClient.send`，调用方在同一条线程上读 `InputStream`。`SidecarLlmTransport`、`LangChain4jLlmClient`、`GrokLlmClient` 虽包在 `VirtualIo.supply` 里，实际等待仍是同步的（例如 `streamingModel.chat` 后再 `done.join()`，或 `Stages.join(client.chat(...))`）。`LangGraphWorkflowEngine.execute` 已通过 `VirtualIo.supply` 返回 `CompletionStage`，随后仍走 `compiled.graph().invokeSync(...)`。`LocalRuntime` 已有每任务一条虚拟线程的工人池，`submit` / `appendMessage` 在正常路径上很快返回 `ACCEPTED` / 202；但 `POST /api/executions/{id}/resume` 在请求线程上对 `control.resume(...).toCompletableFuture().join()`，一次长恢复会占住 agent 的一条 Tomcat 平台工作线程，`interrupt` 也会在请求线程上 join。`decidePass` 已用工人池继续跑，情况较好。`ExecutionController.events` 已先起虚拟线程再立刻返回 `SseEmitter`。`jasagent-app` 的 `application.properties` 尚未打开 Tomcat 虚拟线程开关。C 端总计划第 3 项是全程异步，按锁定顺序本轮就是这块运行时。

建议改动：本轮只改运行时相关模块（`jasagent-llm`、`jasagent-app`，以及接线所需的 `jasagent-workflow` / `jasagent-controller`）。等待 sidecar、模型流或工具，以及恢复执行期间，agent（`jasagent-app` 进程）不得占用操作系统平台工作线程（不是虚拟线程或请求槽位）。优先用现有 `VirtualIo`、虚拟线程或 Servlet 异步，不要整模块改成 WebFlux；读流时不得钉死载体线程。不改 sidecar、网关和 `jasagent-web`，也不改 `/v1/forward` 的 HTTP 约定。018 出站范围和 021 sidecar 验收保持不变，官方探测仍须 `PROBE PASSED`。验收要把 agent 的 Tomcat 平台工作线程故意压低（不能只调 `server.tomcat.threads.max`），再并发跑超过该上限的长 ReAct / 流式调用，并且必须覆盖恢复路径；思考或首块须在上游结束前到达，期间 `/health` 和新的提交、发消息仍要在超时内可用。压低只用于验收，不把生产 `deploy/k8s` 的线程上限永久改低。实现开 PR → 代码审查 → 测试 → 部署开 PR，不直接推 master。

候选交出后，领队核对缺口属实并写「通过」，锁定上述范围，并补充：若调度器等运行时模块在等待时仍占用 agent 的操作系统平台线程，也留在本轮。代码review 写「通过」，并补充：异步移交时，会话、租户和权限上下文不得丢失，也不得串到别人的请求上。部署写「通过」，并补充：官方探测仍须 `PROBE PASSED`，018 / 021 检查继续成立；若 ATDD 之后需要环境变量或启动参数，再由部署开 PR；不要把生产线程上限永久改低。实现写「通过」，并补充：agent 侧出站也要避开 021 里 JDK `ofInputStream().read` 在 `synchronized` 里钉住载体那种读法；020 的租户隔离仍须成立。测试写「通过」，并补充：上下文移交这一条的验收要用不同租户并发覆盖。领队将上述补充并进口径后再次请实现、测试、部署、代码review 表态，各方均再写「通过」。领队于 2026-11-18 22:18（Asia/Shanghai）在群里请 Hang 确认开工。2026-11-19 07:18 每日开评时尚未确认，当天未开新评。Hang 于 2026-11-19 约 08:06 确认「022 开工」。

## 表态

- 领队：通过。锁定本轮只改运行时相关模块（`jasagent-llm`、`jasagent-app`，以及接线所需的 `jasagent-workflow` / `jasagent-controller`；若调度器等运行时模块在等待时仍占用 agent 的操作系统平台线程，也留在本轮），不改 sidecar、网关和 `jasagent-web`。等待 sidecar、模型流或工具，以及恢复执行期间，不得占用 agent 的操作系统平台工作线程。优先用现有 `VirtualIo`、虚拟线程或 Servlet 异步，不整模块改成 WebFlux。读流时不得钉死载体。并将代码review、部署、实现、测试提出的补充并进口径：异步移交时会话、租户和权限上下文不得丢失或串线；020 租户隔离仍须成立，验收用不同租户并发覆盖；agent 侧出站避开 021 那种钉住载体的读法；官方探测仍须 `PROBE PASSED`，018 / 021 检查继续成立；压低线程只用于验收，不永久改低生产清单上限。确认前不写 SDD、不改代码、不部署。
- 调研：交出候选 022-runtime-full-async，并同意上述锁定口径和补充。确认前不写文件、不改代码。
- 实现：通过。范围可以落地。agent 侧出站也要避开 021 里 JDK `ofInputStream().read` 在 `synchronized` 里钉住载体那种读法；020 的租户隔离仍须成立。确认前不改代码。
- 测试：通过。验收必须能独立判定：压低的是 agent 的操作系统平台工作线程，不是虚拟线程或请求槽位；同时扛住超过该上限的长 ReAct / 流式调用，并且必须覆盖恢复路径；思考或首块须在上游结束前到达；期间 `/health` 和新的提交、发消息在超时内可用。验收不能只调 `server.tomcat.threads.max`。异步移交时的上下文不得丢失或串线，这一条要用不同租户并发覆盖。018 / 021 约定仍须成立，官方探测留给部署。确认前不写 tdd、不改代码。
- 部署：通过。官方探测仍须 `PROBE PASSED`，018 出站和 021 sidecar 验收继续成立。调低线程只用于验收，不把生产 `deploy/k8s` 的线程上限永久改低；若 ATDD 之后需要环境变量或启动参数，再开 PR 调整清单。确认前不写文件、不改代码、不部署。
- 代码review：通过。对照产品 master [`11f497c`](https://github.com/xuhang113/JaSAgent/commit/11f497c887a9eb16620f274bcc48f15a01f0099b) 缺口属实。补充：异步移交时，会话、租户和权限上下文不得丢失，也不得串到别人的请求上。同意验收才压低平台线程、按操作系统平台工作线程计算、必须覆盖恢复路径、思考或首块须在上游结束前到达，以及优先用现有 `VirtualIo`、虚拟线程或 Servlet 异步、不整模块改成 WebFlux、读流时不钉死载体。后续实现 PR 将核对：等待和恢复期间不得占用 agent 的操作系统平台工作线程，不得改 `/v1/forward` 约定，018 / 021 和 020 租户隔离必须保持。
- Hang：于 2026-11-19 约 08:06（Asia/Shanghai）确认「022 开工」，作为对上述已锁定口径的确认。

## 确认后的口径

1. 本轮编号为 **022-runtime-full-async**。实现只改运行时相关模块：`jasagent-llm`、`jasagent-app`，以及接线所需的 `jasagent-workflow` / `jasagent-controller`。若调度器或其他运行时模块在等待时仍占用 agent 的操作系统平台线程，也留在本轮。以及对应测试和脚本。不改 sidecar、网关和 `jasagent-web`。实现和测试阶段不改 `deploy/k8s`。
2. 等待 sidecar、模型流或工具，以及恢复执行期间，agent（`jasagent-app` 进程）不得占用操作系统平台工作线程（不是虚拟线程或请求槽位）。
3. 实现优先用现有 `VirtualIo`、虚拟线程或 Servlet 异步，不要整模块改成 WebFlux。读流时不得钉死载体线程。agent 侧出站也要避开 021 里 JDK `ofInputStream().read` 在 `synchronized` 里钉住载体那种读法。
4. `/v1/forward` 的 HTTP 约定、018 出站范围和 021 sidecar 验收保持不变。官方探测仍须 `PROBE PASSED`。
5. 验收必须能独立判定：把 agent 的 Tomcat 平台工作线程故意压低后，仍须同时扛住超过该上限的长 ReAct / 流式调用，并且必须覆盖恢复路径；思考或首块须在上游结束前到达，期间 `/health` 和新的提交、发消息仍要在超时内可用。验收不能只调 `server.tomcat.threads.max`（启用虚拟线程时该值往往是请求槽位）。把线程数调低只用于验收，不把生产 `deploy/k8s` 的线程上限永久改低；若 ATDD 之后需要环境变量或启动参数，再由部署开 PR。
6. 异步移交时，会话、租户和权限上下文不得丢失，也不得串到别人的请求上。020 的租户隔离仍须成立，验收要用不同租户并发覆盖这一点。
7. 从 020 起的流程继续有效：实现按本 spec 开 Pull Request，由 JaSAgent 代码review 对照本规格审查后再交给测试。测试全部通过后，部署如需调整 `deploy/k8s` 或 `deploy.md` 再开 PR，审查后再合并、再上集群。调研把本目录的 `review.md` 和 `spec.md` 先交到仓库，不挡开工。不直接推产品代码到 master。
8. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。口令、令牌、票据和密钥不进 Git、日志、异常或响应。变更记录不能代替本轮 ATDD。验收以部署的云电脑为准。
9. 界面仍只放在 `jasagent-web`。网关仍按单副本。公网入口维持 019。每会话关注点和技能自动摘要、agentpass、MySQL 数据目录、网关高可用，以及 019 尚未正式关闭的项，都不在本轮。
