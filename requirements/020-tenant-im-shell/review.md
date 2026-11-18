# 需求 020 多租户注册与 IM 会话壳评审记录

- 日期：2026-11-18（Asia/Shanghai）
- 块：网关与控制台（实现改 `jasagent-gateway`、`jasagent-web`；账号存储涉及 `jasagent-auth`；聊天会话已在 `jasagent-persist` / `jasagent-controller`。`deploy/k8s` 在 ATDD 之后由部署走 PR 补持久化清单）
- 结论：已确认。领队、实现、测试、部署均写「通过」，调研同意同一口径。Hang 于 2026-11-18 约 10:29（Asia/Shanghai）确认「开工」，一并确认总功能计划、020 开工，以及从 020 起改为「实现开 PR → 代码审查 → 测试 → 部署开 PR」，不再直接推 master。本文件和 `spec.md` 在确认之后写成。

## 候选

Hang 于 2026-11-18 约 09:08（Asia/Shanghai）在群里指出：公网控制台已经能打开，但产品要从本机助手改成面向 C 端的多租户 agent 助手，页面和后端都要整体重构，并列出 8 项能力（租户隔离且仅 admin 看集群监控、用户注册、全程异步、问答助手加日程提醒和手机推送、IM 式会话及每会话关注事项与 skill 自动总结、语音图片等多媒体、外部插件规范、面向用户的 CodeReview 助手）。随后要求领队按这些功能排完整重构计划，并由 reviewbot 参与后续代码审查。

领队按「范围不砍、一轮一块、模块顺序不变」排出总计划：第一轮仍是 020，改 `jasagent-gateway` 和 `jasagent-web`；后面再按 sidecar 出站占线程、运行时全程异步、每会话关注事项与 skill 自动总结及集群整理 skill、日程提醒与手机推送、多媒体（界面仍只改 `jasagent-web`）、外部插件（GitHub、飞书、Web 搜索）、面向用户的 CodeReview 助手依次推进。第一轮锁定口径如下。

1. 开放用户注册。一人一租户（C 端）；admin 是特权角色，不是普通租户成员。加入已有租户、团队邀请不在本轮。
2. 网关必须拒绝（不得只靠前端隐藏）非 admin 访问集群接口，以及访问他人会话。仅 admin 能看集群监控。
3. 用户侧做成 IM 会话壳，用户可以新建会话。已有的 `/api/sessions` 仍由 `jasagent-controller` 提供、经网关粘滞转发；本轮把壳和隔离做实，不改执行模型，不做关注事项/skill 自动总结。
4. 用户账号和会话必须落到已有 MySQL 或持久卷。当前网关 Deployment 把 `user.home` 指到 `/var/lib/jasagent`，并挂 `emptyDir`，重启会丢失账号。聊天会话账本 `SessionLedger` 已经写入 MySQL 表 `agent_session`；缺口主要在账号和登录会话仍走 `jasagent-auth` 的文件目录。
5. 公网仍只走现有 Funnel → Ingress-nginx → `jasagent-gateway`。不暴露 web、Nacos、监控或其它非网关组件。019 的主机名和 Cookie 约定继续有效。

对照当前 `master`：`GatewayLocalController` 明确「不提供自助注册」，第一个用户仍由本机命令创建，口令是用户目录里的 PBKDF2。`GET /api/cluster` 只要求已登录，没有 admin 校验，并按配置项 `jasagent.gateway.tenant-id`（默认 `local`）返回集群清单。`jasagent-web` 的 `/cluster` 路由只检查 `/api/me` 是否登录，没有角色判断。`SessionLedger` 会在 agent 侧拒绝他人会话，但网关转发前尚未强制同一条边界。

从 020 起的流程也一并锁定：实现按已确认的 spec 开 Pull Request，不再直接推 master；由 JaSAgent 代码review 对照 spec 看隔离、权限和会话边界，有阻断问题退回实现，通过后再交给测试。测试全部通过后，部署对 `deploy/k8s` 和 `deploy.md` 开 PR，审查后再合并、再上集群。调研的 spec 在 Hang 确认后先交到仓库，不挡开工。第一轮代码审查重点：网关是否真正拒绝非 admin 的集群接口和他人会话，以及账号和会话是否落到 MySQL 或持久卷，而不是 emptyDir。

候选表决时，调研、实现、测试、部署均写「通过」，并补上上述一人一租户、网关强制隔离、持久化和入口约束。领队请 Hang 一并确认总功能计划、020 开工和上述 PR 流程。Hang 于 10:29 回复「开工」。

## 表态

- 领队：通过。第一轮锁定为 020：改 `jasagent-gateway` 和 `jasagent-web`，做注册、一人一租户、网关强制隔离、仅 admin 看集群、IM 会话壳；用户和会话落到 MySQL 或持久卷；公网仍只走现有 Funnel → Ingress → 网关。总计划后面一轮一块，范围不砍。从 020 起改为实现开 PR → 代码审查 → 测试 → 部署开 PR。确认前不写 SDD、不改代码、不部署。
- 调研：通过。同意上述第一轮口径和总计划顺序。规格须写清：一人一租户、加入已有租户不在本轮；网关必须拒绝非 admin 的集群接口和他人会话；账号不得只写在 emptyDir；若会话数据在 app/运行时模块，须在模块表点名。
- 实现：通过。同意一人一租户（admin 为特权角色）。实现按确认后的 spec 改被点名的模块。确认前不改代码。
- 测试：通过。验收必须能独立判定：可注册、两用户互不可见会话、网关拒绝非 admin 的集群接口、可新建会话、未登录进不了控制台、重启后账号仍在。确认前不写 tdd、不改代码。
- 部署：通过。公网入口维持 Funnel → Ingress → 只到网关。账号和会话必须进已有 MySQL 或持久卷，不能只靠网关 Pod 的 emptyDir。ATDD 全部通过后再改 `deploy/k8s`，并走 PR。确认前不部署。
- Hang：于 2026-11-18 约 09:08 提出 C 端多租户整体重构和 8 项能力，并要求排出完整计划且由 reviewbot 参与代码审查。于约 10:29（Asia/Shanghai）确认「开工」，作为对总功能计划、020 开工和从 020 起「实现开 PR → 代码审查 → 测试 → 部署开 PR」的一并确认。

## 确认后的口径

1. 本轮编号为 **020-tenant-im-shell**。实现改 `jasagent-gateway` 和 `jasagent-web`。账号与登录会话当前在 `jasagent-auth` 的文件目录里，本轮须改为写入已有 MySQL，因此可以改 `jasagent-auth`。聊天会话已由 `jasagent-persist` 的 `SessionLedger` 写入 MySQL 表 `agent_session`，接口在 `jasagent-controller`（`/api/sessions` 等）经网关粘滞转发；本轮以网关强制隔离为准，若账本或控制器与一人一租户不一致，可以改 `jasagent-persist`、`jasagent-controller`（以及为接线所必需的 `jasagent-app`）。不改 sidecar，不改运行时执行模型。实现和测试阶段不改 `deploy/k8s`。
2. 开放自助注册。注册成功后为该用户创建独立租户，一人一租户，不得加入已有租户。团队、邀请、协作租户不在本轮。admin 是特权角色，不是普通租户成员。新注册用户不得获得 admin，也不得看见或调用集群监控。现有文件目录中的运维账号在迁到 MySQL 后仍可登录，并保留 admin 角色。
3. 网关必须拒绝非 admin 访问集群接口（至少包括 `GET /api/cluster`）以及非 admin 打开集群页；不得只靠 `jasagent-web` 隐藏入口。网关必须在转发前拒绝访问他人会话（列表、详情、消息、时间线、删除等），不得只依赖 agent 侧 `SessionLedger` 或前端隐藏。admin 可以看集群监控，但也不能通过会话接口读取他人会话。
4. 用户侧做成 IM 会话壳：已登录用户可以查看自己的会话列表并新建会话。本轮不做每会话关注事项、skill 自动总结、集群整理 skill，也不改问答执行模型（全程异步放到后续轮次）。
5. 账号、登录会话和聊天会话必须落到已有 MySQL（优先使用集群里已有的库，网关亲和性与 `SessionLedger` 已经在用）。不得把 emptyDir 或仅存在于网关 Pod 本地磁盘的文件目录当成唯一存储。持久卷可以作为工作区等文件的补充，但不能代替账号和会话账本。口令仍用 PBKDF2（或同等单向哈希）存放，不得明文入库；口令、JDBC 口令、会话令牌不进 Git、日志、异常或响应；JDBC 地址仍不能带 `password=`。
6. 公网入口维持 019：`https://jasagent-cloud.tail01f87a.ts.net` → Funnel → Ingress-nginx → 只转到 `jasagent-gateway:18721`。不通过该入口暴露 `jasagent-web`、Nacos、MySQL、Redis、agent 或监控。会话 Cookie `JASAGENT_SESSION` 仍为 HttpOnly、path `/`、SameSite=Lax；Secure 只在受信任上一跳声明 HTTPS 时加上。跳转保持相对路径。网关仍按单副本。
7. 从 020 起，实现按本 spec 提交时开 Pull Request，不再直接推 master。由 JaSAgent 代码review 对照本 spec 审查隔离、权限和会话边界；有阻断问题退回实现，通过后再交给测试写 tdd 并跑验收。测试全部通过后，部署对 `deploy/k8s` 和 `deploy.md` 开 PR，审查后再合并、再上集群。调研把本目录的 `review.md` 和 `spec.md` 先交到仓库，不挡开工。
8. 后面轮次（sidecar 出站占线程、运行时全程异步、每会话关注事项与 skill 自动总结、日程提醒和手机推送、多媒体、外部插件、面向用户的 CodeReview 助手）仍在总计划里，不在本轮实现，也不从总目标里删掉。
9. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。变更记录不能代替本轮 ATDD。官方探测仍须 `PROBE PASSED`，015–018 的 egress、gVisor 范围不缩小。验收以部署的云电脑为准。
10. 界面仍只放在 `jasagent-web`。
