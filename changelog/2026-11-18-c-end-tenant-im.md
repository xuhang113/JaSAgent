# C 端注册与 IM 会话壳

- 日期：2026-11-18（Asia/Shanghai）
- 做了什么：开放自助注册。每个新用户得到独立租户，角色是普通用户，不能加入已有租户。账号和登录会话改存已有 MySQL（`jasagent_account`、`jasagent_login_session`），口令仍是 PBKDF2-HMAC-SHA256，登录令牌只存 SHA-256。网关启动时把家目录里尚未入库的运维账号迁入为 admin，并把该用户在 `local` 下的 `agent_session` 改到他自己的租户。`GET /api/me` 返回 `role` 和 `tenantId`。网关在转发前拒绝非 admin 的 `/api/cluster` 与 `/admin/cluster`，并按 `agent_session` 拒绝他人会话；列表只留下当前用户的会话。管理端增加注册页，登录后的会话壳可新建并进入自己的会话，非 admin 看不到集群入口。Cookie 与相对跳转仍按 019。不改 `deploy/k8s`，不改公网入口。
- 涉及模块：jasagent-auth、jasagent-persist、jasagent-gateway、jasagent-web、scripts/test-020-c-end-tenant-im.sh
- 怎么验证：`bash scripts/test-020-c-end-tenant-im.sh`。它覆盖规格第 1 到第 13 条：注册与登录、一人一租户、`/api/me` 的角色和租户、两人会话隔离、非 admin 的集群接口和集群页被网关拒绝、他人会话在转发前被拒绝、会话壳新建并列出、未登录不能读业务数据、账号与登录会话在 MySQL 且重新打开后仍能登录、聊天会话仍在 `agent_session`、019 的 Cookie 和相对跳转、改动范围与注释测试、运维账号迁入后仍是 admin。第 14 到第 16 条留给部署，本轮不改 `deploy/k8s`，也不在这里跑公网或官方探测。
