# 需求 025 OpenTelemetry 到 Jaeger 的链路 MVP

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-24（Asia/Shanghai）领队、调研、实现、测试、部署、代码review 均已通过，补充已并进口径。Hang 于 2026-11-24 约 09:23（Asia/Shanghai）确认「开工」。024-k8s-durable-ha 的实现和部署已合入 master，但第 9 条 Funnel 继续搁置，024 整块和 019 仍不正式关闭。对照确认时的 master [`40fa7b4`](https://github.com/xuhang113/JaSAgent/commit/40fa7b40cf4f6989ce5588ed09ea747187f80cd7)，未改产品代码。

## 这次要改什么

024 已经把 MySQL 换成持久卷、并把网关扩到至少 2 个就绪副本，相关实现和部署已在 master。现在的问题是：仓库里已有 `jasagent-otel`，可按 `jasagent.otel.endpoint` 用 OTLP HTTP 导出，但运行时 `LocalRuntime` 用的是内存里的 `SavedTraceTracer`，明确不把事件写到外部采集器，`ExecutionTelemetry` 也没有接到 `jasagent-app`。集群里已有 otel-collector，却只有 metrics 管道接到 Prometheus，没有 Jaeger，也没有 traces 管道。Hang 要求先做链路 MVP：Jaeger 基于内存存储、在 K8s 里部署，地址在网关 admin 账号下能访问到。第 9 条 Funnel 测试先忽略，不挡住本轮。

这次改成这几件事。

1. 在 `jasagent-app` 里，配置了 `jasagent.otel.endpoint` 就把已有 `jasagent-otel` 导出接到现有 `AgentTracer`；未配置则不外发，现有行为不变。
2. 网关把 `/admin/jaeger` 及其所有子路径，按 `/admin/cluster` 同一套登录和 admin 判断，反代到集群内 Jaeger 的 ClusterIP。控制台只在 `jasagent-web` 给 admin 一个普通链接。
3. 在 `deploy/k8s` 里加上内存存储的 Jaeger（只走 ClusterIP，不挂 NodePort），给采集器补上 traces 管道，并给 agent 配上导出端点、给网关配上 Jaeger 上游。官方脚本等待 Jaeger。Ingress 仍只到网关。
4. 实现先改 app、gateway、web 并开 PR，不动 `deploy/k8s`。ATDD 通过后，再由部署开 PR 上集群。

界面仍只用 `jasagent-web`。不改 `jasagent-trace`、sidecar、Ingress 和 agentpass 约定。不把 Jaeger、web、Nacos、MySQL、Redis、agent 或 agentpass 暴露到公网。验收以部署的云电脑为准。第 9 条 Funnel、以及正式关闭 024 / 019，不在本轮。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-app | 执行运行时。追踪器是内存里的 `SavedTraceTracer`，不外发。`jasagent-otel` 的 `ExecutionTelemetry` 尚未接线 | 配置了 `jasagent.otel.endpoint` 时打开 OTLP HTTP 导出，接到现有 `AgentTracer`；未配置时不外发，现有行为不变。Span 里不得带密码、Token、Authorization 或 Cookie。实现开 PR 时不动 `deploy/k8s` |
| jasagent-otel | 已能按 `jasagent.otel.endpoint` 用 `OtlpHttpSpanExporter` 导出；根 span 名为 `jasagent.execution` | 复用现有导出约定，本轮不为接线去改它的产品约定。若要用适配器接到 `AgentTracer`，适配器放在 `jasagent-app` |
| jasagent-gateway | 对外入口；`/admin` 反代到 `jasagent-web`；`/admin/cluster` 由 `ClusterAccessFilter` 做 admin 判断；至少 2 个就绪副本 | 在反代控制台之前拦截 `/admin/jaeger` 及其所有子路径，反代到集群内 Jaeger 的 ClusterIP。登录和 admin 判断与 `/admin/cluster` 相同，且不依赖单进程状态。实现开 PR 时不动 `deploy/k8s` |
| jasagent-web | 只提供页面，由网关反向代理；admin 可进 `/admin/cluster` | 只给 admin 一个指向 `/admin/jaeger` 的普通链接，不是 Vue 路由，也不把该路径加入 ConsolePages |
| deploy/k8s | 有 otel-collector，但只有 metrics 管道；无 Jaeger；Ingress 只转到网关；agent 未配置 `jasagent.otel.endpoint` | 增加内存存储的 Jaeger（ClusterIP，不挂 NodePort）。采集器增加 traces 管道，把链路报到该 Jaeger。给 agent 配置导出端点，给网关配置 Jaeger 上游。Ingress 仍只转到 `jasagent-gateway:18721`。实现阶段不动本目录 |
| 官方一键拉起与探测脚本 | 等待 otel-collector 等已有工作负载；官方探测须 `PROBE PASSED`，已含 018–024 | 补上 traces 管道相关等待（含 Jaeger 就绪）。官方探测仍须 `PROBE PASSED`，018–024 的检查范围不得缩小。实现阶段不改这些脚本 |
| jasagent-trace / jasagent-sidecar / jasagent-agentpass | 本地或 JDBC 血缘；018–024 已锁定的出站、票和租户持久化约定 | 不改产品约定 |

## 改动地方

**jasagent-app**

未配置 `jasagent.otel.endpoint`（空或未设置）时，不得启用 OTLP 导出，现有执行、会话和 `SavedTraceTracer` 的行为保持不变。配置为非空 URL 时，使用已有 `jasagent-otel` 的 OTLP HTTP 导出，并接到现有 `AgentTracer`，使一次会打点的执行真正外发链路。根 span 名称仍是 `jasagent.execution`。Span 属性或内容里不得带密码、Token、Authorization 或 Cookie。本轮改到的类、常量、方法都要有注释。实现提交不动 `deploy/k8s`。

**jasagent-gateway**

对 `/admin/jaeger` 及其所有子路径，必须先走与 `/admin/cluster` 同一套判断，再反代，且这条路径不能落到 `jasagent-web` 的控制台单页。未登录的页面请求转到 `/admin/login`；已登录但非 admin 回 403，正文不含 Jaeger 内容、口令或令牌。前缀不能被绕过。上游只指向集群内 Jaeger 的 ClusterIP，地址不能被请求改写。反代时不得把登录会话 Cookie 转给 Jaeger，也不得让 Jaeger 的 `Set-Cookie` 覆盖网关会话 Cookie。该判断和反代不依赖单进程状态，在至少 2 个就绪网关副本下仍然成立。实现提交不动 `deploy/k8s`。

**jasagent-web**

控制台只在 admin 可见位置放一个普通链接，目标是 `/admin/jaeger`。不要做成 Vue 路由，也不要把 `/admin/jaeger` 加进 ConsolePages，以免被单页应用吃掉或在网关拦截之前返回控制台页面。非 admin 看不到这个入口；真正的拦截仍在网关，不能只靠页面隐藏。

**deploy/k8s 与官方脚本**

实现和测试提交不得修改这些目录和脚本。部署阶段增加内存存储的 Jaeger Deployment 与 Service，Service 只为 ClusterIP，不挂 NodePort，也不出现在 Ingress。Jaeger 的 query base-path 为 `/admin/jaeger`。otel-collector 增加 traces 管道，把收到的链路报到该 Jaeger，不得拆掉现有 metrics 管道。agent 工作负载配置 `jasagent.otel.endpoint`，指向集群内采集器的 OTLP HTTP traces 地址（如 `http://jasagent-otel-collector.jasagent.svc.cluster.local:4318/v1/traces`）。网关工作负载配置 Jaeger 上游，只指向该 ClusterIP。Ingress 仍只转到 `jasagent-gateway:18721`，不得把 Jaeger、web、Nacos、MySQL、Redis、agent 或 agentpass 挂到这条公网入口。官方一键拉起脚本须等待 Jaeger 就绪，并带上 traces 管道。可以按现有风格补 NetworkPolicy 规则，但本集群因缺少 `CONFIG_IP_SET` 不会真正生效，不得把 NetworkPolicy 当作通过条件。

**不改的模块**

不改 `jasagent-trace`。不改 sidecar 和 agentpass 的产品约定。不改 Ingress 只到网关的约定。018 出站、021 sidecar 线程、022 运行时异步、023 通行证按租户持久化、024 网关多副本和 MySQL 持久卷的验收保持有效。

## 数据库

不新建库，也不把 Jaeger 的链路持久化到 MySQL。本轮 Jaeger 使用内存存储，进程或 Pod 重启丢掉已有链路不算失败。口令、令牌、密钥仍只从仓库外文件或 Secret 读取。

## 中间件

本轮新增的是集群内、内存存储、ClusterIP 的 Jaeger，以及采集器上的 traces 管道。不把 Jaeger 做成 NodePort 或公网入口。不引进新的消息队列。不把 Nacos 改成集群，也不给 Redis 做持久化。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。本轮不恢复或验收 Funnel。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、密钥、Authorization 和 Cookie 不写进仓库、日志、异常、响应或 Span。实现和测试阶段不改 `deploy/k8s` 和官方集群脚本；ATDD 全部通过后，由部署补 Jaeger、traces 管道、端点和上游配置，并交出带环境地址、命令和集成测试结果的 `deploy.md`。变更记录不能代替本轮 ATDD。验收环境以部署的云电脑为准，不再依赖 Hang 的 Mac。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成，不依赖集群里的 Jaeger；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. 未配置 `jasagent.otel.endpoint` 时不启用 OTLP 导出，现有行为不变。
2. 配置了非空 `jasagent.otel.endpoint` 之后，走一条会打点的执行（不走 Funnel）。测试用 OTLP 接收端能收到根 span `jasagent.execution`，不只查导出器类名。
3. `/admin/jaeger` 及其所有子路径与 `/admin/cluster` 同一套判断：未登录的页面请求转到 `/admin/login`；已登录的非 admin 回 403，且不能落到控制台单页。前缀不能被绕过。
4. 网关只反代到集群内 Jaeger 的 ClusterIP，上游地址不能被请求改写。
5. 反代不把登录会话 Cookie 转给 Jaeger，也不接受 Jaeger 的 `Set-Cookie` 覆盖网关会话 Cookie。该判断和反代不依赖单进程状态。
6. Span 里不得带密码、Token、Authorization 或 Cookie。
7. 控制台只给 admin 普通链接，不是 Vue 路由，也不把 `/admin/jaeger` 加入 ConsolePages。
8. 实现只改 `jasagent-app`、`jasagent-gateway`、`jasagent-web` 及必要接线，不修改 `deploy/k8s`，不改 sidecar 和 agentpass 的产品约定。本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、凭证和密钥不出现在仓库、说明或日志里。

第 1 到第 8 条在 ATDD 阶段验收。下面几条在部署阶段验收。

9. 走一条会打点的请求之后，admin 经网关打开 `/admin/jaeger`，能在 Jaeger 界面看到这条链路，且根 span 为 `jasagent.execution`。本轮 MVP 下任一 admin 能看到该 Jaeger 里全部租户的链路，不另做按租户隔离展示。
10. 官方 `scripts/k8s/probe-egress.sh` 整体为 `PROBE PASSED`。018–024 的检查范围没有被缩小，也没有跳过既有项。
11. Ingress 仍只转到 `jasagent-gateway:18721`。Jaeger 只走 ClusterIP，不对公网或 NodePort 暴露。不把 web、Nacos、MySQL、Redis、agent 或 agentpass 挂到公网入口。
12. 进程或 Pod 重启丢掉已有链路不算失败。本集群 NetworkPolicy 因缺少 `CONFIG_IP_SET` 不会真正生效，不当作通过条件。
13. 清单和脚本改动已由部署开 PR 提交，而不是直接推 master。`requirements/025-otel-jaeger-mvp/deploy.md` 写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

## 还没定

没有还没定的口径。Jaeger 镜像版本、采集器 traces 导出协议的具体端口，以及 agent、网关环境变量的具体名字，由实现和部署在「内存存储、ClusterIP、OTLP HTTP 导出、query base-path 为 `/admin/jaeger`、Ingress 仍只到网关」的约束内决定。第 9 条 Funnel、正式关闭 024 整块和 019、按租户隔离 Jaeger 里的链路，以及每会话关注点和技能自动摘要、日程推送、多媒体、外部插件、CodeReview，不在本轮。
