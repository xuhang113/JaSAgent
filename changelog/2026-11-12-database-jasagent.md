# 2026-11-12 库名改为 jasagent

- 日期：2026-11-12（Asia/Shanghai）
- 做了什么：JaSAgent 使用的 MySQL 库名改为 jasagent。JDBC 默认地址、集群 `MYSQL_DATABASE`、一键部署写入 Secret 的地址、本机启动脚本和测试都改为这个库名。口令仍只从仓库外的口令文件读取，不写入仓库、清单或日志。本机 MySQL 新建了库 jasagent。原来的 urag 库没有删除、清空或改名，里面的表也没有拷进 jasagent。表仍由服务启动时用 `CREATE TABLE IF NOT EXISTS` 创建。集群 MySQL 的数据目录是 emptyDir，应用清单后 Pod 换新，新目录里只创建并使用 jasagent。
- 涉及模块：jasagent-persist、jasagent-trace、jasagent-gateway、jasagent-app、deploy/k8s、scripts
- 怎么验证：Java 21 下 `mvn -pl jasagent-persist -am test -Dtest=PersistProcessTest#defaultCheckpointUsesMysqlAndKeepsExistingTables` 通过，默认地址是 `127.0.0.1:3306/jasagent`。`mvn -pl jasagent-gateway -am test -Dtest=StickyAgentRouterTest` 4 条通过。本机两台 runsc agent 的 `/health` 返回 `storage` 为 `up`，连接库名是 jasagent。`scripts/up-stack.sh` 结束后 `scripts/k8s/probe-egress.sh` 输出 `PROBE PASSED`，两台集群 agent 的 `storage` 也是 `up`。集群 Secret 里的 JDBC 地址路径是 `/jasagent`，地址中没有口令。
