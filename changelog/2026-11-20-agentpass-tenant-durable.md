# agentpass 改为按租户持久化

- 日期：2026-11-20（Asia/Shanghai）
- 做了什么：签发前的登录会话、租户和权限改从 020 的 MySQL 读取，不再读 home 里的 auth/sessions 或 auth/policies.yaml。票据状态、审计流水和未命中策略时的待批记录写在同一个 jasagent 库的新表里，不另开数据库，也不加中间件。进程带着同一把签名密钥重启后，已核销的证仍拒绝重放，未过期且未使用的证仍可核销。不同租户不能签发、核验或核销对方的证，审计也不跨租户。GET /v1/tickets/audit 必须带登录令牌，未登录返回 401，正文里没有流水；登录后只返回自己的租户。/v1/tickets 的签发、核验、核销路径、请求字段、绑定范围和一次核销语义不变。策略未命中时除了 403 还会留下持久的待批记录。同一用户通过控制台已有的 POST /api/executions/{id}/passes/{passId} 批准或拒绝，批准后沿 022 的恢复路径继续原来的 executionId；别人不能代批。生产缺少 JASAGENT_AGENTPASS_SIGNING_KEY 时拒绝启动，不再在内存里另生成一把。密钥、令牌和证明文不进仓库、日志、异常和响应。
- 涉及模块：jasagent-agentpass、jasagent-agentpass-server、jasagent-app。审批仍走 jasagent-web 和网关里已有的通行证路径，本轮不改 jasagent-web、jasagent-gateway、jasagent-controller、jasagent-sidecar，也不改 deploy/k8s。
- 怎么验证：`bash scripts/test-023-agentpass-tenant-durable.sh`。脚本独立判断第 1 到第 9 条：模块测试，两进程重启，并发租户隔离，审计鉴权，以及经现有通行证接口批准后恢复原执行。不放宽 018、021、022 和 probe-egress 的路径白名单，也不用未跑的 021 代替本轮证明。第 10 到第 12 条留给部署：本记录不改 deploy/k8s、tdd.md、results/ 或 deploy.md，也不代替 ATDD。集群里的 agentpass 仍要由部署补上签名密钥和 JDBC 环境；当前 probe-egress 仍按文件准备用户，接上这份代码后要先在 MySQL 里有会话和权限才会签发。agentpass 继续只绑在本机，不暴露公网。
