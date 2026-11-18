# 需求 021 sidecar 出站不再占用 Tomcat 平台工作线程评审记录

- 日期：2026-11-18（Asia/Shanghai）
- 块：sidecar（`jasagent-sidecar` 及对应测试和脚本；`deploy/k8s` 仅在 ATDD 之后如需调整再由部署走 PR）
- 结论：已确认。领队、实现、测试、部署、代码review 均写「通过」。Hang 于 2026-11-18 约 18:18（Asia/Shanghai）确认「开工021」。本文件和 `spec.md` 在确认之后写成。020-c-end-tenant-im 已整块完成：部署验收记录 [PR #8](https://github.com/xuhang113/JaSAgent/pull/8) 已合入，当时 master 为 [`2f0dc84`](https://github.com/xuhang113/JaSAgent/commit/2f0dc84eebdfbcf8e5eda21636981f35128b200c)。

## 候选

调研在群里交出的候选 **021-sidecar-egress-threads**。对照当时的 `master`（[`2f0dc84`](https://github.com/xuhang113/JaSAgent/commit/2f0dc84eebdfbcf8e5eda21636981f35128b200c)）：018 已经让离开集群的 HTTP 经 sidecar 的 `/v1/forward` 流式转发，大模型白名单、票据核销、每 Pod sidecar 和回环约定已经落地。缺口在于 sidecar 仍是 Spring MVC + Tomcat（`spring-boot-starter-web`）。`EgressController.forward` 同步处理；`HttpTicketOffice` 和 `JdkUpstreamClient` 都用阻塞的 `HttpClient.send`；流式响应由同一条请求线程按 8KB 块抄写。一路长流式大模型调用会占住一条 Tomcat 平台工作线程，并发高时会堵住 `/health` 和新的转发。agent 侧 `SidecarForward` 仍用阻塞 `send`，按总计划放到下一轮「运行时全程异步」，本轮不改。

建议改动：本轮只改 `jasagent-sidecar`（及对应测试和脚本）。`/v1/forward` 在等待 agentpass、上游或抄写流式响应期间，不得占用 Tomcat 平台工作线程；可用虚拟线程或非阻塞 IO，但不得改 `/v1/forward` 的 HTTP 约定，也不得再整包缓冲。018 的出站范围、流式、白名单、票据、回环和探测约定保持不变。验收要把 Tomcat 平台线程数故意调得很低，仍能同时扛住超过该上限的长流式转发，且期间 `/health` 和其他转发仍可用。不改 `jasagent-app` / `jasagent-llm` 的调用方式，也不改运行时执行模型。实现开 PR → 代码审查 → 测试 → 部署开 PR。`deploy/k8s` 仅在 ATDD 之后如需调整再由部署走 PR。

候选交出后，领队核对缺口属实并写「通过」，锁定上述口径，后将「扑住」更正为「扛住」。部署写「通过」，并补充：把 Tomcat 平台线程数调低只用于验收，不要把生产 `deploy/k8s` 的线程上限永久改低；若 ATDD 之后需要环境变量或启动参数，再由部署开 PR。领队将该条并进口径。实现写「通过」，并建议优先在现有 Spring MVC 上用虚拟线程或 Servlet 异步，不要整模块改成 WebFlux；验收要把「Tomcat 平台工作线程」落到可操作的配置（例如 `server.tomcat.threads.max`）；若用虚拟线程，抄写响应时注意不要把载体线程钉死。测试写「通过」，要求验收按可独立判定来理解：压低的是操作系统上的平台工作线程，不是虚拟线程或请求槽位；长流式要持续出块；首块必须在上游结束前到达，证明没有整包缓冲；期间 `/health` 和新的转发仍要在超时内可用。测试并补充：验收不能只调 `server.tomcat.threads.max`，启用虚拟线程时该值往往是请求槽位。代码review 对照 master 核对缺口属实并写「通过」，同意上述补充。领队将全部补充并进口径后，请 Hang 确认。

## 表态

- 领队：通过。锁定本轮只改 `jasagent-sidecar`；`/v1/forward` 等待或抄写期间不得占用 Tomcat 平台工作线程；018 约定不变；实现开 PR → 代码审查 → 测试 → 部署开 PR。并将部署、实现、测试提出的补充并进口径：调低线程只用于验收、不永久改低生产清单上限；优先在现有 Spring MVC 上用虚拟线程或 Servlet 异步、不整模块改成 WebFlux；验收按操作系统平台线程而不是 `server.tomcat.threads.max` 请求槽位来压；流须持续出块，首块须在上游结束前到达。确认前不写 SDD、不改代码、不部署。
- 调研：交出候选 021-sidecar-egress-threads，并同意上述锁定口径和补充。确认前不写文件、不改代码。
- 实现：通过。范围可以落地。建议优先在现有 Spring MVC 上用虚拟线程或 Servlet 异步，不要整模块改成 WebFlux；验收落到可操作配置；若用虚拟线程，抄写时不要把载体线程钉死。确认前不改代码。
- 测试：通过。验收必须能独立判定：压低的是操作系统平台工作线程，不是虚拟线程或请求槽位；同时扛住超过该上限的长流式 `/v1/forward`，持续出块，首块在上游结束前到达；期间 `/health` 和新的转发在超时内可用。验收不能只调 `server.tomcat.threads.max`。018 第 1 到 9 条仍须通过，官方探测留给部署。确认前不写 tdd、不改代码。
- 部署：通过。018 约定和官方探测 `PROBE PASSED` 不变。调低线程只用于验收，不把生产 `deploy/k8s` 的线程上限永久改低；若 ATDD 之后需要环境变量或启动参数，再开 PR 调整清单。确认前不写文件、不改代码、不部署。
- 代码review：通过。对照 master [`2f0dc84`](https://github.com/xuhang113/JaSAgent/commit/2f0dc84eebdfbcf8e5eda21636981f35128b200c) 缺口属实。同意验收才压低平台线程、按操作系统平台工作线程计算、首块须在上游结束前到达，以及优先在现有 Spring MVC 上用虚拟线程或 Servlet 异步、不整模块改成 WebFlux。后续实现 PR 将核对：等待或抄写期间不得占用 Tomcat 平台工作线程，不得整包缓冲或改 `/v1/forward` 约定，018 的票据、白名单和回环必须保持。
- Hang：于 2026-11-18 约 18:18（Asia/Shanghai）确认「开工021」，作为对上述已锁定口径的确认。

## 确认后的口径

1. 本轮编号为 **021-sidecar-egress-threads**。实现只改 `jasagent-sidecar` 以及对应测试和脚本。不改 `jasagent-app` / `jasagent-llm` 的调用方式，也不改运行时执行模型。实现和测试阶段不改 `deploy/k8s`。
2. `/v1/forward` 在等待 agentpass、上游或抄写流式响应期间，不得占用 Tomcat 平台工作线程（操作系统上的平台线程，不是虚拟线程或请求槽位）。实现可用虚拟线程或非阻塞 IO，但不得改 `/v1/forward` 的 HTTP 约定，也不得再整包缓冲。
3. 实现优先在现有 Spring MVC 上用虚拟线程或 Servlet 异步，不要整模块改成 WebFlux。若用虚拟线程，抄写响应时不要把载体线程钉死。
4. 018 的出站范围、流式、白名单、票据、回环和探测约定保持不变。离开集群的 HTTP 仍须经 `/v1/forward`；Nacos（含 gRPC 9848）、MySQL、Redis、网关和本集群服务仍不走 sidecar。018 第 1 到 9 条仍须通过，官方探测仍须 `PROBE PASSED`。
5. 验收必须能独立判定：把 sidecar 的 Tomcat 平台工作线程故意压低后，仍须同时扛住超过该上限的长流式 `/v1/forward`，这些流要持续出块，首块须在上游结束前到达，期间 `/health` 和新的转发仍要在超时内可用。验收不能只调 `server.tomcat.threads.max`（启用虚拟线程时该值往往是请求槽位）。把线程数调低只用于验收，不把生产 `deploy/k8s` 的线程上限永久改低；若 ATDD 之后需要环境变量或启动参数，再由部署开 PR。
6. 从 020 起的流程继续有效：实现按本 spec 开 Pull Request，由 JaSAgent 代码review 对照本规格审查后再交给测试。测试全部通过后，部署如需调整 `deploy/k8s` 或 `deploy.md` 再开 PR，审查后再合并、再上集群。调研把本目录的 `review.md` 和 `spec.md` 先交到仓库，不挡开工。
7. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。口令、令牌、票据和密钥不进 Git、日志、异常或响应。变更记录不能代替本轮 ATDD。验收以部署的云电脑为准。
8. 界面仍只放在 `jasagent-web`。网关仍按单副本。公网入口维持 019。运行时全程异步不在本轮。
