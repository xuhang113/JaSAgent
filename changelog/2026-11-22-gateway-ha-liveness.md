# 网关存活探测跨副本

- 日期：2026-11-22（Asia/Shanghai）
- 做了什么：把 agent 存活从网关进程内的表改成写入已有库 jasagent 的 jasagent_gateway_liveness。地址、账号和口令文件继续用粘性库那一组 jasagent.gateway.affinity.jdbc-url、username、password-file，不新建库，也不加 Redis 或其它中间件。每个副本用自己的副本编号维护本进程的 TCP 长连接，并只改自己那一行的连接标记和心跳。一个副本断开只把自己标成未连接，不删心跳，也不清掉另一个副本仍在有效期内的心跳。选路和集群页读的是任一副本仍连接且心跳未过期的共享结果。控制台 EventSource 仍走 /api/executions/{id}/events，网关按长连接原样转发，不把响应整段读进内存，也不另存一份进程内订阅。登录会话仍在同一库，同一用户的会话 Cookie 在任一就绪副本上可读。转发头信任范围不变。本机缺省副本数仍是 1，扩到至少 2 个就绪副本留给部署。
- 涉及模块：jasagent-gateway。本轮不改 jasagent-web、jasagent-sidecar、jasagent-agentpass、agentpass-server、deploy/k8s、scripts/k8s/probe-egress.sh、scripts/k8s/prepare-cluster.sh、scripts/up-stack.sh。/v1/tickets 的领证、校验、核销约定不变。
- 怎么验证：`bash scripts/test-024-gateway-ha-liveness.sh`。脚本跑网关自动测试，并核对差异范围。对应规格第 1 到第 6 条里依赖网关代码的项：两个写入端看到同一份存活，一个副本断开不会把另一个副本仍活着的 agent 判死，选路走共享存活而不是只读本进程表；登录会话跨副本可见，租户隔离与非 admin 不能打集群接口的既有约定仍成立；事件流路径不被整段读进内存；转发头信任仍拒绝 0.0.0.0/0 与 ::/0；差异不含 sidecar、agentpass、web、部署清单和官方探测或集群准备脚本。第 7 到第 12 条是持久卷、至少 2 个就绪副本、探测、Funnel 和集群准备，留给部署。本记录不改 deploy/k8s，也不代替 ATDD。
