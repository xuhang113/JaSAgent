# 云电脑 k3s 一键部署

- 日期：2026-11-14（Asia/Shanghai）
- 做了什么：一键脚本改为在运行它的 Linux 机器上打包 jasagent-web，构建并导入 `jasagent-web:0.1.0`，apply `web.yaml`、`observability.yaml` 和 `rag.yaml` 并等待滚动；同时继续部署网关、agent、agentpass、sidecar、MySQL 和 Redis。apply `rag.yaml` 时会构建并导入 `jasagent-rag:0.1.0`。Java 21 从 `JAVA_HOME` 或本机已安装的 Java 21 解析。口令、令牌和模型密钥只从仓库外可配置路径或环境读取，缺失时非 0 退出且不打印密文。JDBC 地址指向可配置的集群主机且不含 `password=`。新增 `scripts/k8s/prepare-cluster.sh`，按固定步骤在当前机器上准备 k3s 和 gVisor。镜像在当前机器上构建或拉取后导入本机 k3s。未改 `deploy/k8s`，未改 sidecar 产品代码，未改官方 `scripts/k8s/probe-egress.sh`。
- 涉及模块：scripts
- 怎么验证：`bash scripts/test-016-cloud-k8s-up-stack.sh`。仓库内合同测试覆盖验收第 1 到第 12 条（一键脚本与集群准备脚本；不在本轮验收第 13 到第 18 条）。
