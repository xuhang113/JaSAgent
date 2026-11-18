# 部署清单把账号和会话指到已有 MySQL

- 日期：2026-11-18（Asia/Shanghai）
- 做了什么：核对 020 的网关实现。账号表 `jasagent_account`、登录会话表 `jasagent_login_session` 和聊天会话表 `agent_session` 与粘性表共用 `jasagent.gateway.affinity.jdbc-url`、用户名和口令文件。`deploy/k8s/gateway.yaml` 已从 Secret `jasagent-mysql` 注入 JDBC 地址和用户名，本轮补上 `JASAGENT_GATEWAY_AFFINITY_PASSWORD_FILE`，并写明网关 `home` 的 emptyDir 不是这些数据的权威存储。agent 的 `JASAGENT_JDBC_*` 继续用同一份 `jdbc-url`。MySQL 仍是栈里已有的那一个，数据目录类型不变。公网路径、网关单副本、`JASAGENT_GATEWAY_WEB_ORIGIN` 和只信任 Ingress 的转发名单都没有改。官方探测脚本没有删检查。没有把口令、令牌或 Cookie 写入仓库。
- 涉及模块：deploy/k8s/gateway.yaml、deploy/k8s/agent.yaml、deploy/k8s/mysql.yaml、deploy/k8s/secret.yaml、requirements/020-c-end-tenant-im/deploy.md
- 怎么验证：本轮没有在云代理上部署，也没有跑官方探测。计划在部署云电脑上执行 `bash scripts/k8s/prepare-cluster.sh`、`bash scripts/up-stack.sh`，必要时 `bash scripts/k8s/configure-funnel.sh`，再跑 `bash scripts/k8s/probe-egress.sh`，并按 `requirements/020-c-end-tenant-im/deploy.md` 做网关 Pod 删除和公网注册登录。规格第 14 到第 16 条的集群结果待测。
