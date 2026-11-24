# 需求 025 OpenTelemetry 到 Jaeger 的链路 MVP 评审记录

- 日期：2026-11-24（Asia/Shanghai）
- 块：运行时接线、网关 admin 反代和控制台入口（`jasagent-app`、`jasagent-gateway`、`jasagent-web`），以及集群里的内存 Jaeger 与采集器 traces 管道（`deploy/k8s`，含官方一键拉起和探测脚本中与本轮相关的等待项）。实现阶段只改 app、gateway、web，不动 `deploy/k8s`
- 结论：已确认。领队、调研、实现、测试、部署、代码review 均写「通过」，补充已并进口径。Hang 于 2026-11-24 约 09:23（Asia/Shanghai）确认「开工」。本文件和 `spec.md` 在确认之后写成。024-k8s-durable-ha 的实现 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15)、部署 [PR #16](https://github.com/xuhang113/JaSAgent/pull/16) 已按顺序合入（合并提交分别为 [`4043ebc`](https://github.com/xuhang113/JaSAgent/commit/4043ebccdb86b29540f254cd07a79cec5e036a33)、[`5d7c4fe`](https://github.com/xuhang113/JaSAgent/commit/5d7c4fe60595a32ce4c2e8fc50132c381297526c)）。第 9 条 Funnel 继续搁置，024 整块和 019 仍不正式关闭。确认时 master 为 [`40fa7b4`](https://github.com/xuhang113/JaSAgent/commit/40fa7b40cf4f6989ce5588ed09ea747187f80cd7)（2026-11-24 07:16 Asia/Shanghai 的文档提交），未改产品代码。

## 候选

调研在群里交出的候选 **025-otel-jaeger-mvp**。对照当时的 `master`（[`40fa7b4`](https://github.com/xuhang113/JaSAgent/commit/40fa7b40cf4f6989ce5588ed09ea747187f80cd7)）：集群里已有 `jasagent-otel-collector`，但配置只有 metrics 管道接到 Prometheus，没有 traces 管道，也没有 Jaeger。`jasagent-otel` 已能按 `jasagent.otel.endpoint` 用 OTLP HTTP 导出，但运行时 `LocalRuntime` 用的是内存里的 `SavedTraceTracer`，明确不外发，`ExecutionTelemetry` 也没有接到 `jasagent-app`。只加 Jaeger 会是空的。Ingress 仍只到网关。Grafana 虽有 NodePort，但本轮 Jaeger 不沿用该先例。

建议本轮改 `jasagent-app`（配了端点就打开导出，接到现有 `AgentTracer`）、`jasagent-gateway`（admin 反代）、`jasagent-web`（admin 入口）和 `deploy/k8s`（内存 Jaeger、采集器 traces 管道、agent 端点、网关上游、官方脚本等待 Jaeger）。不改 `jasagent-trace`、sidecar、Ingress 和 agentpass 约定，也不把 Jaeger 做成 NodePort。Jaeger 只用 ClusterIP，query base-path 为 `/admin/jaeger`，由网关按 `/admin/cluster` 同一套判断反代；控制台只给 admin 普通链接，不要做成 Vue 路由。重启丢掉已有链路是 MVP 的取舍。第 9 条 Funnel 继续搁置。确认前不写文件、不改代码、不部署。

## 表态

- 领队：通过。认下范围和验收，并在全员表态后锁定口径：改 `jasagent-app`、`jasagent-gateway`、`jasagent-web`、`deploy/k8s`；不改 `jasagent-trace`、sidecar、Ingress 和 agentpass 约定。配了 `jasagent.otel.endpoint` 才导出，接到现有 `AgentTracer`；未配置则不外发、现有行为不变。Jaeger 用内存存储、只走 ClusterIP，不挂 NodePort，Ingress 仍只到网关。网关把 `/admin/jaeger` 及子路径按 `/admin/cluster` 同一套判断反代：未登录的页面转到 `/admin/login`，非 admin 回 403，前缀不能被绕过，上游不能被请求改写；不转发登录 Cookie，也不接受 Jaeger 的 Set-Cookie 覆盖网关会话。控制台只给 admin 普通链接，不是 Vue 路由。本轮任一 admin 能看到该 Jaeger 里全部租户的链路；Span 里不得带密码、Token、Authorization 或 Cookie。重启丢链路可以接受。本集群 NetworkPolicy 因缺少 `CONFIG_IP_SET` 不会真正生效，不当作通过条件。验收分开：实现和 ATDD 在配置端点后走一条会打点的执行（不走 Funnel），用 OTLP 接收端能收到根 span `jasagent.execution`，并覆盖上述网关隔离；Jaeger 界面能看到这条链路、官方探测仍须 `PROBE PASSED`、018–024 范围不缩小，留给部署。实现先改 app、gateway、web，不动 `deploy/k8s`。第 9 条 Funnel 继续搁置，024 整块和 019 仍不正式关闭。确认前不写 spec、不改代码、不部署。
- 调研：交出候选 025-otel-jaeger-mvp，并收下实现、测试、部署和代码审查的补充，同意上述锁定口径。确认前不写文件、不改代码。
- 实现：通过。实现这轮只改 `jasagent-app`、`jasagent-gateway`、`jasagent-web` 和必要接线，不动 `deploy/k8s`、sidecar 和 agentpass 约定。未配置 `jasagent.otel.endpoint` 时不外发、现有行为不变；配置后以测试用 OTLP 接收端收到根 span `jasagent.execution` 为准，不只查导出器类名。admin 反代与 `/admin/cluster` 同一套登录判断，不依赖单进程状态；`/admin/jaeger` 及子路径未登录的页面请求转到 `/admin/login`，非 admin 回 403，上游不可被请求改写；反代不转发登录 Cookie，也不接受 Jaeger 的 Set-Cookie 覆盖会话。Span 里不带密码、Token、Authorization 或 Cookie；本轮 MVP 下任一 admin 能看到全部租户链路，按写明的口径做。确认前不改代码。
- 测试：通过。验收把 ATDD 和部署分开。实现侧不依赖集群里的 Jaeger：未配置端点不外发、现有行为不变；配置后走一条会打点的执行（不走 Funnel），测试用 OTLP 接收端能收到根 span `jasagent.execution`，不要只查导出器类名。`/admin/jaeger` 及其子路径与 `/admin/cluster` 同一套判断：未登录的页面请求转到 `/admin/login`，非 admin 回 403，且不能落到控制台单页；控制台只给 admin 普通链接，不是 Vue 路由，也不进 ConsolePages。ATDD 还覆盖：前缀不能被绕过，上游不能被请求改写；反代不转发登录会话 Cookie，也不接受 Jaeger 的 Set-Cookie 覆盖网关会话；Span 里不得带密码、Token、Authorization 或 Cookie。本轮 MVP 下任一 admin 能看到该 Jaeger 里全部租户的链路，按写明的口径验收，不另做租户隔离。Jaeger 界面里能看到这条链路、官方探测仍须 `PROBE PASSED`、Ingress 不暴露 Jaeger，留给部署。同意 NetworkPolicy 不作为通过条件，进程或 Pod 重启丢掉已有链路不算失败。第 9 条 Funnel 继续搁置，018–024 范围不缩小。确认前不写 tdd、不改代码。
- 部署：通过。Jaeger 只走 ClusterIP、Ingress 仍只到网关、由网关按 `/admin/cluster` 同一套判断把 `/admin/jaeger` 反代给 admin，官方脚本补 traces 管道并等待 Jaeger，这些部署侧都能做。本集群 NetworkPolicy 因缺少 `CONFIG_IP_SET` 仍不会真正生效，不要把它当作通过条件；走一条会打点的请求后，admin 经网关打开 `/admin/jaeger` 能看到这条链路（根 span 为 `jasagent.execution`），进程或 Pod 重启丢掉已有链路不算失败。官方探测仍须 `PROBE PASSED`，018–024 范围不缩小。第 9 条 Funnel 继续搁置。确认前不动 `deploy/k8s`，也不部署。
- 代码review：通过。请把隔离要求写进验收：`/admin/jaeger` 及其所有子路径都走与 `/admin/cluster` 同一套登录和 admin 判断，未登录先拦，非 admin 回 403，前缀不能被绕过；网关只反代到集群内 Jaeger 的 ClusterIP，上游不能被请求改写。反代时不要把登录会话 Cookie 转给 Jaeger，也不允许 Jaeger 的 Set-Cookie 覆盖网关会话 Cookie。本轮 MVP 下任一 admin 能看到该 Jaeger 里全部租户的链路，这一点要写明；Span 里不得带密码、Token、Authorization 或 Cookie。未配置 `jasagent.otel.endpoint` 时不外发，Ingress 仍只到网关，Jaeger 不对公网或 NodePort 暴露。
- Hang：于 2026-11-24 约 09:23（Asia/Shanghai）确认「开工」，作为对上述已锁定口径的确认。

## 确认后的口径

1. 本轮编号为 **025-otel-jaeger-mvp**。改 `jasagent-app`、`jasagent-gateway`、`jasagent-web` 和 `deploy/k8s`。不改 `jasagent-trace`、sidecar、Ingress 和 agentpass 约定。复用已有 `jasagent-otel` 的 OTLP HTTP 导出约定，由 `jasagent-app` 接到现有 `AgentTracer`。界面仍只用 `jasagent-web`。实现阶段只改 app、gateway、web，不动 `deploy/k8s`。
2. 配置了 `jasagent.otel.endpoint` 才导出；未配置则不外发，现有行为不变。
3. 集群内部署内存存储的 Jaeger，只走 ClusterIP，不挂 NodePort，不对公网暴露。Ingress 仍只转到 `jasagent-gateway:18721`，不把 Jaeger、web、Nacos、MySQL、Redis、agent 或 agentpass 挂到公网入口。采集器补上 traces 管道，把链路报到该 Jaeger。agent 在集群里配置导出端点。Jaeger 的 query base-path 为 `/admin/jaeger`。
4. 网关把 `/admin/jaeger` 及其所有子路径按 `/admin/cluster` 同一套判断反代到集群内 Jaeger 的 ClusterIP：未登录的页面请求转到 `/admin/login`，非 admin 回 403，前缀不能被绕过，且不能落到控制台单页。上游地址不能被请求改写。反代不把登录会话 Cookie 转给 Jaeger，也不允许 Jaeger 的 Set-Cookie 覆盖网关会话 Cookie。该反代不依赖单进程状态。控制台只给 admin 普通链接，不是 Vue 路由，也不进 ConsolePages。
5. 本轮 MVP 下任一 admin 能看到该 Jaeger 里全部租户的链路，不另做按租户隔离展示。Span 里不得带密码、Token、Authorization 或 Cookie。
6. 进程或 Pod 重启丢掉已有链路不算失败。本集群 NetworkPolicy 因缺少 `CONFIG_IP_SET` 不会真正生效，不当作通过条件。
7. 验收分开。实现和 ATDD 不依赖集群里的 Jaeger：未配置端点时不外发；配置后走一条会打点的执行（不走 Funnel），测试用 OTLP 接收端能收到根 span `jasagent.execution`，不只查导出器类名，并覆盖上述网关隔离。部署阶段验收：走一条会打点的请求后，admin 经网关打开 `/admin/jaeger` 能在 Jaeger 界面看到这条链路；官方探测仍须 `PROBE PASSED`，018–024 范围不缩小；Ingress 不暴露 Jaeger。
8. 从 020 起的流程继续有效：实现按本 spec 开 Pull Request（只改 app、gateway、web，不动 `deploy/k8s`），由 JaSAgent 代码review 对照本规格先审隔离和会话边界，再交给测试。测试全部通过后，部署再开 PR 调整 `deploy/k8s` 和官方脚本，审查后再合并、再上集群。调研把本目录的 `review.md` 和 `spec.md` 先交到仓库，不挡开工。不直接推产品代码或清单到 master。
9. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。口令、令牌、票据和密钥不进 Git、日志、异常或响应。变更记录不能代替本轮 ATDD。验收以部署的云电脑为准。
10. 第 9 条 Funnel 继续搁置，024 整块和 019 仍不正式关闭。每会话关注点和技能自动摘要、日程推送、多媒体、外部插件、CodeReview，以及按租户隔离 Jaeger 里的链路，都不在本轮。
