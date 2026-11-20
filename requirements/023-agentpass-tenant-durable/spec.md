# 需求 023 通行证多租户持久化

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-20（Asia/Shanghai）领队、调研、实现、测试、部署、代码review 均已通过，补充已并进口径。Hang 于 2026-11-20 约 08:21（Asia/Shanghai）确认「通行证需要持久化权限判断 开始做吧」。022-runtime-full-async 已整块完成。对照确认时的产品 master [`19d1629`](https://github.com/xuhang113/JaSAgent/commit/19d16294100a223776b7377d1431423e4d0007eb)；领队随后写入的 022 `status.md` 在 [`9fa78a4`](https://github.com/xuhang113/JaSAgent/commit/9fa78a4469d6d3c6943e310f705349c759741062)，未改产品代码。

## 这次要改什么

005 已有进程内 `PassGate`，015 后又有 `jasagent-agentpass-server` 的一次性票，018 出站已走这套票。现在的问题是：发证前的会话和策略仍读家目录文件，证状态和流水只在进程内存里，重启后已核销的证可能被重放；生产清单未挂签名密钥，缺省会每次启动另生成一把；`GET /v1/tickets/audit` 无鉴权会倒出全部流水；未命中策略只回 403，不留下待批，也不能在控制台用原来的 executionId 接着做。这些都对不上已经落地的 020 C 端多租户。

这次改成这几件事。

1. 发证前的会话、租户和权限以 020 的 MySQL 为准，不以 emptyDir 上的会话文件或 `policies.yaml` 为真相来源。
2. 证状态和流水落到已有 MySQL。用同一库和同一签名密钥重启 agentpass 后，已核销的证仍须拒绝重放。
3. 租户 A 不能领、验、核销 B 的证；`GET /v1/tickets/audit` 必须鉴权并按租户过滤。
4. 未命中策略须留下待批。同一用户经网关在 `jasagent-web` 批准或拒绝，批准后走已有 resume 路径、用原 executionId 做完这次调用，他人不能代批。
5. 生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 必须失败，不得再静默生成临时密钥。
6. 实现只改 agentpass 及相关接线模块（见「模块」），不改 sidecar，不改 `/v1/tickets` 的领证、校验、核销约定。实现和测试不改 `deploy/k8s`。
7. 实现开 PR，代码审查通过后再交给测试。ATDD 全部通过后，若需要把签名密钥挂进 Secret，或调整 agentpass 的 Service、环境变量，由部署开 PR。

界面仍只放在 `jasagent-web`。网关仍按单副本。不把 agentpass 暴露到公网。验收以部署的云电脑为准。每会话关注点和技能自动摘要、日程推送、多媒体、外部插件、CodeReview、网关高可用和整块 K8s 不在本轮。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-agentpass | 进程内 `PassGate` 仍读用户目录 `auth/policies.yaml`；未命中则 PENDING；待批仍可走本机文件和 CLI | 策略和待批改为以租户为单位的持久化来源，不再把 emptyDir 上的文件当真相来源。未命中须留下待批，供同一用户批准或拒绝 |
| jasagent-agentpass-server | `HumanAccess` 对会话文件后再走 `PolicyPassGate`；`PassStore` / `AuditLog` 是进程内集合；缺签名密钥时在内存里另生成一把；`GET /v1/tickets/audit` 无鉴权返回全部流水；领证遇到 PENDING 只回 403，不记待批、不挂起 | 发证前的会话、租户和权限改走 020 的 MySQL。证状态和流水落到已有 MySQL。生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 必须失败。audit 必须鉴权并按租户过滤。未命中须留下待批，而不是只回 403。`/v1/tickets` 的领证、校验、核销约定不变。集群内 Service 必须能访问到本服务 |
| jasagent-agentpass-starter | 一次性票客户端和过滤器，按现有约定带票访问受保护接口 | 仅在接线需要时改动，保持现有领证、校验、核销约定 |
| jasagent-api / jasagent-auth / jasagent-persist | 通行证接口、认证和已有 MySQL 持久化（020 已落账号和登录会话） | 仅在接线需要时改动：让发证、策略、待批和流水使用 020 已有的租户、用户和会话身份，并在已有库中增加本轮所需的表 |
| jasagent-app / jasagent-controller | 022 已落地运行时异步和 resume 路径 | 仅在接线需要时改动：PENDING 时把执行挂起，批准后走已有 resume 路径、用原 executionId 继续完成该次调用。不改 022 已锁定的异步验收 |
| jasagent-web | C 端界面；目前没有通行证审批 | 只在本模块放审批界面：同一用户经网关查看待批并批准或拒绝。不把 agentpass 暴露到公网 |
| jasagent-gateway | 公网入口；C 端请求经网关进入 | 仅在审批或待批查询的现有路由接不上时改接线。不得把 agentpass 端口暴露到公网，也不得改变 019 / 020 的公网入口约定 |
| jasagent-sidecar | 018 / 021 已锁定出站票据和平台线程验收 | 本轮不改 |
| jasagent-agentpass-rag | 示例检索服务，不是产品界面 | 不是本轮改动重点。若现有合同测试依赖它，须继续能按新的持久化和鉴权约定通过 |
| scripts | 已有 018 / 021 / 022 验收脚本 | 补本轮可执行测试脚本，须能按下面的验收独立判定租户隔离、重启后拒绝重放、audit 鉴权、待批审批和 resume |
| deploy/k8s | agentpass 单副本，home 为 emptyDir，清单未挂签名密钥 | 实现和测试阶段不动。ATDD 全部通过后，由部署开 PR 挂签名密钥 Secret，并按需要调整 Service 或环境变量；不得把密钥写入 Git |

## 改动地方

**jasagent-agentpass**

策略和待批改为按租户持久化，与 020「一人一租户」对齐。不得再把 emptyDir 或用户家目录里的 `auth/policies.yaml`、`auth/sessions`、`auth/pending` 当作 C 端真相来源。未命中策略必须留下待批，供同一用户批准或拒绝；他人不能代批。本轮改到的类、常量、方法都要有注释。

**jasagent-agentpass-server**

发证前须按 020 的 MySQL 核对会话、租户和权限，而不是只对会话文件。证的签发、核销状态和审计流水须写入已有 MySQL（与 020 同一套库），不得再只放在进程内存里。用同一库和同一签名密钥环境变量重启进程后，已核销的证仍须拒绝重放；未过期、未使用的证在签名密钥未变时仍可核销。

`/v1/tickets` 的领证、校验、核销 HTTP 约定不变：路径、请求字段（租户、用户、会话、执行、目标、工具）、证的绑定范围和一次性核销语义保持现有约定。`GET /v1/tickets/audit` 必须鉴权并按租户过滤；未鉴权应 401 或 403，不得再回全部流水；已登录也只能看本租户。

生产环境缺少 `JASAGENT_AGENTPASS_SIGNING_KEY`（或同等已约定的配置项）时，服务必须失败并拒绝发证，不得再静默在内存里另生成一把临时密钥。本地和验收可另给测试密钥。密钥只在运行时注入，不写进仓库、说明、日志、异常或响应。集群内 Service 必须能访问到本服务。不把本服务暴露到公网。

领证遇到需要人批的情况时，必须留下待批并让原执行可以挂起，而不是只回 403 且不记记录。本轮改到的类、常量、方法都要有注释。

**jasagent-agentpass-starter**

只在接线需要时改动。继续按现有约定领取、携带和核销一次性票，不改 sidecar 出站用票的方式。

**jasagent-api / jasagent-auth / jasagent-persist**

只在接线需要时改动。发证、策略和待批须复用 020 已落地的租户、用户和会话身份，不要再维护一套平行的文件账号。在已有 MySQL 中增加本轮证状态、流水、策略和待批所需的表，不新增独立数据库。

**jasagent-app / jasagent-controller**

只在接线需要时改动。未命中策略而进入 PENDING 时，须把该次执行挂起；同一用户批准后，走 022 已落地的 resume 路径，用原来的 `executionId` 继续完成该次调用。不要另做一套互不相通的恢复，也不要削弱 022 已锁定的异步验收。本轮改到的类、常量、方法都要有注释。

**jasagent-web**

审批界面只放在本模块。登录用户经网关查看属于自己的待批，并批准或拒绝。他人提交的待批不能被代批。不要把 agentpass 的地址或端口暴露给浏览器直接访问。

**jasagent-gateway**

只在审批或待批查询的现有路由接不上时改接线。公网入口仍是 Funnel → Ingress → `jasagent-gateway`，不得把 agentpass 端口暴露到公网，也不得改变 019 / 020 已锁定的 Cookie 与租户隔离约定。网关仍按单副本。

**jasagent-sidecar**

本轮不改。018 出站范围、票据头和 021 sidecar 线程验收继续成立。

**scripts**

为本轮验收提供可执行测试脚本。脚本须能独立判定：不同租户并发时，A 不能领、验、核销 B 的证，流水按租户隔离；`GET /v1/tickets/audit` 未鉴权得到 401 或 403，已登录只看到本租户；用同一库和同一签名密钥环境变量重启 agentpass 进程后，已核销的证仍被拒绝重放；未命中策略会留下待批，同一用户经 `jasagent-web`、走网关可以批准或拒绝，批准后用原来的 `executionId` 继续完成该次调用，他人代批被拒绝。不能只测领证返回 403。本地和验收使用测试密钥，密钥不得写入仓库、说明或日志。实现提交可以按需要修正与本轮验收对齐的脚本，但不得修改 `deploy/k8s` 下的清单。

**deploy/k8s**

实现和测试提交不得修改这个目录。等本轮 ATDD 全部通过后，由部署开 Pull Request 把生产签名密钥挂进 Secret（或同等环境变量），并按需要调整 agentpass 的 Service、监听地址或环境变量，审查后再合并、再上集群。生产缺少该密钥必须失败。密钥不进 Git、日志和群消息。官方探测仍须 `PROBE PASSED`，现有 egress、sidecar 线程、运行时异步与 gVisor 检查范围不得缩小。`deploy.md` 要有环境地址、命令和集成测试结果。验收以部署的云电脑为准，不再依赖 Hang 的 Mac。

## 数据库

使用已有 MySQL（与 020 同一套库），不新增独立数据库。为本轮证状态、审计流水、策略和待批增加所需的表。用户、租户和登录会话仍以 020 已落地的表为准，租户隔离约定与 020 保持一致。不把 emptyDir 上的会话文件或策略文件当作这些数据的真相来源。

## 中间件

不引进新的中间件。Nacos、MySQL、Redis 的访问仍不经 sidecar。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。不加消息队列。签名密钥只通过环境变量或 Secret 在运行时注入。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、票据和密钥不写进仓库、日志、异常或响应。实现按本 spec 开 Pull Request，由 JaSAgent 代码review 对照本规格审查后再交给测试。实现和测试阶段不改 `deploy/k8s`；等本轮 ATDD 全部通过后，若需要调整清单，由部署开 PR。变更记录不能代替本轮 ATDD。不直接推产品代码到 master。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. 发证前的会话、租户和权限以 020 的 MySQL 为准，不以 emptyDir 上的会话文件或 `policies.yaml` 为真相来源。策略和待批不能再只靠 emptyDir 上的文件。
2. 证状态和流水已落到已有 MySQL。用同一库和同一签名密钥环境变量重启 agentpass 进程后，已核销的证仍须拒绝重放；未过期、未使用的证在签名密钥未变时仍可核销。
3. 不同租户须并发覆盖：A 不能领、验、核销 B 的证；流水按租户隔离，不得串到别的租户。
4. `GET /v1/tickets/audit` 未鉴权应 401 或 403，不得再回全部流水；已登录也只能看本租户。
5. `/v1/tickets` 的领证、校验、核销约定不变。018 出站、021 sidecar 线程和 022 运行时异步验收仍然成立。
6. 未命中策略须留下待批，不能只测领证 403。同一用户经 `jasagent-web`、走网关批准或拒绝；批准后走已有 resume 路径，用原来的 `executionId` 继续完成该次调用；他人代批须被拒绝。
7. 生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 必须失败，不得再静默在内存里另生成一把。本地和验收可另给测试密钥。密钥只在运行时注入，不出现在仓库、说明或日志里。
8. 实现改动只覆盖 agentpass 及相关接线模块（`jasagent-agentpass`、`jasagent-agentpass-server`、`jasagent-agentpass-starter`，以及接线所需的 `jasagent-api` / `jasagent-auth` / `jasagent-persist`；要把发证前的会话和权限改走 020 的 MySQL，以及 PENDING 挂起、控制台审批后用原 executionId 继续，已经在这条链上的模块也可以改，尤其是 `jasagent-app`、`jasagent-controller` 和 `jasagent-web`；审批或待批查询的现有网关路由接不上时，也可改 `jasagent-gateway` 的接线）。不改 sidecar。不把 agentpass 暴露到公网。
9. 实现和测试提交不修改 `deploy/k8s` 下的清单文件。本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。

第 1 到第 9 条在 ATDD 阶段验收。下面几条在部署阶段验收。

10. 官方 `scripts/k8s/probe-egress.sh` 整体为 `PROBE PASSED`；现有 egress、sidecar 线程、运行时异步与 gVisor 检查范围没有被缩小。018 / 021 / 022 在云电脑上已通过的集成检查仍然成立。网关仍是单副本。agentpass 没有暴露到公网。015 / 016 / 017 / 018 / 020 / 021 / 022 在云电脑上已通过的集成检查仍然成立。
11. 若本轮需要把签名密钥挂进 Secret，或调整 agentpass 的 Service、环境变量，已由部署开 PR 调整，而不是直接推 master。生产缺少该密钥必须失败。密钥不在 Git、日志或说明里。
12. `requirements/023-agentpass-tenant-durable/deploy.md` 写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

## 还没定

没有还没定的口径。证状态、流水、策略和待批在已有 MySQL 里的具体表名和列，由实现在「与 020 同一套库、不以 emptyDir 文件为真相来源」的约束内决定。每会话关注点和技能自动摘要、日程推送、多媒体、外部插件、CodeReview、MySQL 数据目录、网关高可用、整块 K8s，以及 019 尚未正式关闭的项，不在本轮。
