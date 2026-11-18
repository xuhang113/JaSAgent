# 需求 020 多租户注册与 IM 会话壳

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-18（Asia/Shanghai）领队、实现、测试、部署均已通过。Hang 于约 10:29 确认「开工」，一并确认总功能计划、020 开工，以及从 020 起改为「实现开 PR → 代码审查 → 测试 → 部署开 PR」。

## 这次要改什么

019 已经把公网控制台经 `https://jasagent-cloud.tail01f87a.ts.net`（Funnel → Ingress-nginx → 只到网关）打通。产品要从本机助手改成面向 C 端的多租户 agent 助手。当前缺口是：网关不提供自助注册；账号和登录会话写在 `jasagent-auth` 的文件目录，集群里 `user.home` 挂在网关 Pod 的 emptyDir，重启会丢账号；`GET /api/cluster` 只要求已登录，没有 admin 校验；控制台集群页也只检查是否登录；网关转发前没有强制拒绝他人会话。

这次改成这几件事。

1. 开放注册，一人一租户。新用户不是 admin。现有运维账号保留 admin。
2. 网关强制隔离：拒绝非 admin 的集群接口和集群页，拒绝访问他人会话，不得只靠前端隐藏。
3. `jasagent-web` 做成 IM 会话壳，用户可以新建并进入自己的会话；非 admin 看不到集群入口。
4. 账号、登录会话和聊天会话落到已有 MySQL，不能只写在 emptyDir。
5. 公网入口、Cookie 和跳转约定沿用 019。实现和测试不改 `deploy/k8s`；ATDD 全部通过后由部署开 PR 再上集群。

从 020 起，实现按本 spec 开 Pull Request，由 JaSAgent 代码review 对照本规格审查后再交给测试。不改 sidecar，不改运行时执行模型。界面仍只放在 `jasagent-web`。验收以部署的云电脑为准。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-gateway | 唯一对外入口；登录、退出、`/api/me`、`/health`、页面跳到 `/admin/`；不提供自助注册；`GET /api/cluster` 只要求已登录，按配置租户（默认 `local`）返回清单；会话 Cookie 约定已按 019 落地；其它 `/api/**` 按 `(tenant_id, user_id)` 粘滞转发 | 增加自助注册；注册后为该用户创建独立租户。`/api/me` 须带上角色（至少区分 admin 与普通用户）和租户，供页面判断。非 admin 访问集群接口和集群页由网关拒绝（例如 403）。转发会话接口前拒绝他人会话。账号和登录会话改为写入已有 MySQL。019 的 Cookie、受信任转发头和相对跳转约定保持不变。网关仍按单副本 |
| jasagent-web | 登录页、控制台、集群页；路由除登录页外只检查 `/api/me` 是否登录；前端用同源相对路径的 `fetch` 和 EventSource | 增加注册页或登录页上的注册入口。用户侧做成 IM 会话壳：列出自己的会话并支持新建会话。非 admin 不展示集群入口，直接访问集群页时应失败或被带回会话壳。不把公网主机名写进前端配置 |
| jasagent-auth | 用户和登录会话在 `${user.home}/<登录名>/jasagent` 的文件目录里；口令为 PBKDF2-HMAC-SHA256；第一个用户可由本机命令创建，之后创建需要本机 CLI 会话 | 账号和登录会话改为写入已有 MySQL，不再把文件目录或 emptyDir 当作唯一存储。保留口令单向哈希，不得明文存口令。提供自助注册。迁库后现有运维账号仍可登录并保留 admin；新注册用户为普通用户 |
| jasagent-persist | `SessionLedger` 已把聊天会话写入 MySQL 表 `agent_session`（按 `tenant_id, session_id`，含 `user_id` 所有者），并在账本层拒绝他人会话；工作区在 `agent_workspace` | 聊天会话继续落在已有 MySQL，不得改回仅内存或 emptyDir。若归属字段或校验与一人一租户不一致，对齐隔离。网关可用同一套归属数据在转发前做拒绝，不另搞一份会分叉的会话账本 |
| jasagent-controller | agent 上的控制台接口：`/api/sessions` 的列表、新建、详情、改名、删除、消息、时间线等；身份来自网关头 | 仅在与一人一租户或会话隔离不一致时改。不改执行模型，不在本轮加关注事项或 skill 自动总结 |
| jasagent-app | 进程内接线，控制台和持久化从这里组装 | 仅在为账号/会话新存储接线所必需时改。不改 ReAct 或调度线程模型 |
| deploy/k8s | 网关 `JAVA_TOOL_OPTIONS=-Duser.home=/var/lib/jasagent`，volume `home` 为 `emptyDir`；公网路径已按 019 落到 Funnel → Ingress → 网关 | 实现和测试阶段不动。ATDD 全部通过后，由部署开 PR：账号和登录会话不再只依赖 emptyDir；继续使用已有 MySQL；公网入口不变，不暴露 web、Nacos、监控；官方探测仍须 `PROBE PASSED` |

## 改动地方

**jasagent-gateway**

增加未登录即可调用的注册接口（例如 `POST /register`），接收登录名和口令，成功后创建该用户及其独立租户。登录名规则与现有 `LoginNames` 一致或在实现里写明并测试；口令不得出现在日志、异常或响应里。注册成功不自动授予 admin。

登录、退出和 Cookie 行为沿用 019：`JASAGENT_SESSION` 为 HttpOnly、path `/`、SameSite=Lax；Secure 只在受信任上一跳声明 HTTPS，或网关自己终止 TLS 时加上；本机明文直连不得强行加 Secure。跳转继续用相对路径，不得写死 `127.0.0.1` 或 `http://`。

`GET /api/me` 在登录名之外返回角色和租户（字段名在实现里固定并写进测试），让前端能判断是否展示集群入口。`GET /api/cluster` 以及其它集群监控接口：未登录仍拒绝；已登录但非 admin 必须由网关拒绝，不能再只按配置项 `jasagent.gateway.tenant-id` 把清单交给任意登录用户。非 admin 请求 `/admin/cluster` 也由网关拒绝，不得只靠前端藏入口。

对 `/api/sessions` 及子路径（详情、消息、上下文、时间线、纠正、删除、改名等），网关在转发前核对当前登录用户是该会话所有者。不是所有者则拒绝，并且不把请求转到他人会话所在的 agent 去碰数据。admin 看集群不等于可以读他人会话。列表接口只返回当前用户自己的会话。

账号和登录会话的存储改为已有 MySQL，由网关（经 `jasagent-auth`）读写。网关已有的亲和性 JDBC 和 agent 侧执行库都可以复用已有 MySQL，不新建一套外部数据库服务。JDBC 地址不得带 `password=`，口令只从仓库外配置或 Secret 读取。

本轮改到的类、常量、方法都要有注释。网关仍按单副本。

**jasagent-web**

提供注册界面（独立页或登录页上的注册入口）。登录后的用户界面做成 IM 会话壳：展示当前用户的会话列表，可以新建会话并进入会话查看消息。继续用同源相对路径访问接口和 EventSource，不把 Funnel 主机名写进前端配置。

非 admin 不展示集群入口。未登录只能进入登录或注册，进不了控制台。本轮不做语音、图片、推送或插件界面。

**jasagent-auth**

把用户目录和 `auth/sessions/` 文件会话，改成已有 MySQL 中的账号表和登录会话表（具体表名由实现确定，须可迁移、可测试）。口令继续用 PBKDF2-HMAC-SHA256 或同等单向哈希，权限与强度不得弱于 001。切换存储后，须把现有文件目录中的运维账号导入或在首次启动时迁入，使其仍可登录并具有 admin 角色。自助注册走同一套存储。

**jasagent-persist 与 jasagent-controller**

聊天会话继续使用 `agent_session`（及已有的消息、执行相关表）。不要把会话文档改回只存在于工作区磁盘或 emptyDir。若需要补索引或让网关能在转发前做归属判断，可以在本模块补齐，但必须与网关使用同一套归属，避免两边各写各的。控制器仅在隔离口径需要对齐时修改，不改执行调度。

**deploy/k8s**

实现和测试提交不得修改这个目录。等本轮 ATDD 全部通过、代码审查已经通过测试交接之后，由部署开 Pull Request，再合并、再上集群。

部署须做到：网关重启或 Pod 重建后，已注册账号仍能登录，已有聊天会话仍在；`emptyDir` 不得再作为账号和会话的唯一存储。公网路径保持 `https://jasagent-cloud.tail01f87a.ts.net` → Funnel → Ingress-nginx → 只到 `jasagent-gateway:18721`，不暴露 `jasagent-web`、Nacos、MySQL、Redis、agent 或监控。官方 `scripts/k8s/probe-egress.sh` 仍须 `PROBE PASSED`，015–018 的 egress、gVisor 范围不缩小。结果写入 `deploy.md`，须有环境地址和集成测试结果。凭证不进 Git。

## 数据库

使用集群里已有的 MySQL，不新增外部数据库服务。本轮可以新增账号、租户、登录会话所需的表，也可以复用、扩展已有表；聊天会话继续使用已有的 `agent_session`、`agent_workspace` 及执行相关表。口令只存哈希。JDBC 地址不能带 `password=`。响应和日志不得出现口令或会话令牌明文。

## 中间件

不引进新的消息队列或其它业务中间件。Nacos、MySQL、Redis、Ingress-nginx、Tailscale Funnel 的访问方式和 017–019 的约定不变。网关仍单副本。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。

## 开发规范和提交方式

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、JDBC 口令和密钥不写进仓库、日志、异常或响应。

从 020 起，实现按本 spec 在 Git 上开 Pull Request，不再直接推 master。由 JaSAgent 代码review 对照本规格审查隔离、权限和会话边界（重点：网关是否真正拒绝非 admin 的集群接口和他人会话，账号和会话是否落到 MySQL 或持久卷而不是 emptyDir）。有阻断问题退回实现；通过后再由测试写 `tdd.md`、跑验收并把结果写入 `results/`。测试全部通过后，部署对 `deploy/k8s` 和 `deploy.md` 开 PR，审查后再合并、再上集群。变更记录不能代替本轮 ATDD。开发规范里的 K8s 文件按这个时机补。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交并经代码审查通过后，由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. 未登录可以直接注册（登录名和口令），注册成功后可以用该账号登录，并进入控制台。
2. 每次注册都创建独立租户，一人一租户；没有加入已有租户或邀请入租户的入口或接口。
3. 新注册用户的角色不是 admin。`GET /api/me` 能区分 admin 与普通用户，并带上该用户自己的租户。
4. 两个普通用户各自登录后，只能看见和操作自己的会话，不能列出或读取对方的会话。
5. 普通用户调用 `GET /api/cluster`（以及本轮存在的其它集群监控接口）被网关拒绝；直接访问 `/admin/cluster` 也被网关拒绝。不得只靠前端隐藏。
6. 普通用户（以及 admin）构造对他人会话的详情、消息、时间线或删除等请求时，网关在转发前拒绝。不得只依赖 agent 侧 `SessionLedger` 才失败。
7. 已登录用户可以在 IM 会话壳里新建会话，并在自己的会话列表里看到它。
8. 未登录不能进入控制台；访问需要登录的页面或 `/api/me`、会话接口时被拒绝或被带到登录/注册，不能读到业务数据。
9. 账号和登录会话写在已有 MySQL 里，而不是只写在 `${user.home}` 文件目录或 emptyDir。仓库内的测试须能证明存储后端是 MySQL（或同等已有库），并且进程重启后账号仍可登录。
10. 聊天会话仍落在 MySQL 的 `agent_session`（或本轮明确指定的同一已有库中的表），而不是只写在 emptyDir 或内存。
11. 019 的 Cookie 和跳转约定仍然成立：`JASAGENT_SESSION` 为 HttpOnly、path `/`、SameSite=Lax；Secure 只在受信任上一跳声明 HTTPS 时出现；本机明文直连不加 Secure；跳转 Location 不得写死 `127.0.0.1` 或 `http://` 绝对地址。
12. 实现提交不修改 `deploy/k8s`。本轮不改公网入口路径，不把 `JASAGENT_GATEWAY_WEB_ORIGIN` 改成公网主机名。口令、令牌和密钥不出现在仓库、说明或日志里。本轮改到的模块有注释、自动测试和可执行测试脚本。
13. 现有运维账号在存储切换后仍可登录，并仍是 admin，可以调用集群接口；该能力不得被新注册用户获得。

第 1 到第 13 条在 ATDD 阶段验收。下面几条在部署阶段验收。

14. 在部署的云电脑上删除或重启网关 Pod 后，此前注册的账号仍能登录，已有聊天会话仍在。网关清单不再把 emptyDir 当作账号和会话的唯一存储。
15. 经 `https://jasagent-cloud.tail01f87a.ts.net`（若部署记录中的主机名有更新，以当时 `deploy.md` 为准）可以注册和登录；普通用户看不到、也调不到集群监控；两名用户的会话仍然隔离。公网入口仍只转到网关，不暴露 web、Nacos、MySQL、Redis、agent 或监控。
16. 官方 `scripts/k8s/probe-egress.sh` 整体为 `PROBE PASSED`；现有 egress 与 gVisor 检查范围没有被缩小。018 的每 Pod sidecar 与出站约定仍然有效。网关仍是单副本。`requirements/020-tenant-im-shell/deploy.md` 写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

## 还没定

没有还没定的口径。加入已有租户、全程异步、每会话关注事项与 skill 自动总结、日程提醒和手机推送、多媒体、外部插件、面向用户的 CodeReview 助手不在本轮，仍留在已确认的总计划后续轮次。
