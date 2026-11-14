# 部署记录 015-gateway

**时间**：2026-11-14 14:24 Asia/Shanghai

本轮 ATDD（T-001 至 T-030）此前已全部通过。按规格只改 `deploy/k8s`，未改 `scripts/up-stack.sh`。部署后跑了集成测试。没有环境地址或没有测试结果，不算完成。

## 环境

- 集群：Hang 的 Mac 上的 Colima k3s（aarch64）
- 节点：`colima`，Kubernetes `v1.35.0+k3s1`，containerd `2.1.5-k3s1`，操作系统 Ubuntu 24.04.4 LTS
- 命名空间：`jasagent`
- 镜像：`jasagent-gateway:0.1.0`、`jasagent-web:0.1.0`（web 使用 `jasagent-web-0.1.0-exec.jar` 打包）。因为本轮不改 `scripts/up-stack.sh`，web 镜像是手工构建、导入 k3s 后再 apply 的。
- 滚动结果：`jasagent-gateway` 1/1（只剩 gateway 容器，已去掉 mysql-bridge），`jasagent-web` 1/1。集群里原有的 grafana、otel-collector、prometheus 未删除。
- 后续约定：按 Hang 和领队的要求，之后的 k8s 环境改放到部署角色的云电脑，不再依赖这台 Mac。本轮记录的仍是这次实际跑通的 Mac 集群地址和结果。

## 环境地址

| 服务 | 地址 |
| --- | --- |
| 网关（集群内，对外入口） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722` |
| 本机访问 | `kubectl -n jasagent port-forward svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 网关 Service | ClusterIP `10.43.96.249`，端口 18721 |
| web Service | ClusterIP `10.43.239.46`，端口 18722 |
| agent-0 | `http://jasagent-agent-0.jasagent.svc.cluster.local:18731` |
| agent-1 | `http://jasagent-agent-1.jasagent.svc.cluster.local:18732` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741` |
| sidecar | `http://jasagent-sidecar.jasagent.svc.cluster.local:8080` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306` |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379` |

粘性库 JDBC 地址为 `jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?...`，账号来自 Secret `jasagent-mysql` 的 `username`，口令只从该 Secret 挂载到仓库外口令文件路径的文件读取。JDBC 地址里没有 `password=`。

## 本轮清单改动

- `deploy/k8s/gateway.yaml`：去掉 mysql-bridge sidecar；`JASAGENT_GATEWAY_WEB_ORIGIN` 指向 `http://jasagent-web.jasagent.svc.cluster.local:18722`；JDBC 地址和账号从 Secret 注入；`replicas: 1`，并写明登录会话和存活探测都在网关进程内，本轮按单副本验收。
- `deploy/k8s/web.yaml`：新增 `jasagent-web` Deployment 和 Service，端口 18722，runtimeClass 为 gvisor，就绪探测为 `GET /admin/login`。
- `deploy/k8s/networkpolicy.yaml`：允许网关访问 web 的 18722；新增 `jasagent-web-egress`，只放行到 kube-dns 的 DNS。
- `deploy/k8s/entrypoint.sh`：`non_proxy` 角色加入 `jasagent-web`。

## 集成测试结果

测试时间：2026-11-14 14:24 Asia/Shanghai。

### 015 专项（I-001 至 I-016）

全部通过，总结果：`015 IT PASSED`。

| 用例 | 结果 | 说明 |
| --- | --- | --- |
| I-001-single-replica | 通过 | 网关 Deployment 副本为 1，就绪 1，运行中的 Pod 1 个，容器只有 `gateway` |
| I-002-no-mysql-bridge | 通过 | 网关 Pod 里没有 mysql-bridge 容器 |
| I-003-web-origin | 通过 | `JASAGENT_GATEWAY_WEB_ORIGIN=http://jasagent-web.jasagent.svc.cluster.local:18722` |
| I-004-jdbc-config | 通过 | JDBC 指向集群内 MySQL，地址不含 `password=` |
| I-005-affinity-table | 通过 | 库中存在 `jasagent_gateway_affinity`。该表在首次读写时创建：用网关 Pod 内同一套 JDBC 地址、账号和口令文件调用 `MysqlAffinityStore.list` 后表出现，随后复查通过。本条结果不包含口令或令牌原文。 |
| I-006-gateway-reaches-mysql | 通过 | 网关 Pod 能连到 `jasagent-mysql:3306` |
| I-007-gateway-health | 通过 | `GET /health` 返回 200，正文含 `"status":"up"`，不含 `"storage"` |
| I-008-admin-login-via-gateway | 通过 | 未登录经网关访问 `GET /admin/login` 返回 200 |
| I-009-admin-root-unauth | 通过 | 未登录访问 `GET /admin/` 被重定向到 `/admin/login`，或返回 401 |
| I-010-api-me-unauth | 通过 | 未登录 `GET /api/me` 返回 401，`errorCode` 为 `AUTH` |
| I-011-api-cluster-unauth | 通过 | 未登录 `GET /api/cluster` 返回 401，`errorCode` 为 `AUTH` |
| I-012-web-direct-login | 通过 | 直接访问 web 上游 `GET /admin/login` 返回 200 |
| I-013-gateway-reaches-web | 通过 | 网关 Pod 能访问 `jasagent-web:18722/admin/login` 并得到 200 |
| I-014-web-gvisor | 通过 | web Pod 运行在 gVisor |
| I-015-gateway-gvisor | 通过 | 网关 Pod 运行在 gVisor |
| I-016-host-port-forward | 通过 | 本机 `127.0.0.1:18721` 上 `/health` 和 `/admin/login` 均为 200 |

### `scripts/k8s/probe-egress.sh`

整体结果：`PROBE FAILED`。失败项只有 `runtime-class`。

原因：集群里原有的 `jasagent-grafana`、`jasagent-otel-collector`、`jasagent-prometheus` 没有设置 `runtimeClassName=gvisor`。这些工作负载不在本轮 `deploy/k8s` 清单里，本轮也没有删除它们。脚本把非 mysql/redis 且未标明 gvisor 的 Pod 判为失败，因此在 `jasagent-grafana` 处返回失败。mysql 和 redis 按约定不是 gvisor。

本轮相关组件的 runtimeClass 均为 gvisor：gateway、web、agent-0、agent-1、agentpass、sidecar。这些 gVisor 探测全部通过。

其余探测全部通过：`pods-ready`、`gvisor-agent-0`、`gvisor-agent-1`、`gvisor-gateway`、`gvisor-agentpass`、`gvisor-sidecar`、`gateway-identity`、`agentpass-identity`、`gateway-agent-0`、`gateway-agent-1`、`mysql-agent-0`、`mysql-agent-1`、`redis-agent-0`、`redis-agent-1`、`agent-direct-external-blocked`、`llm-deepseek-no-ticket`、`llm-xai-no-ticket`、`external-no-ticket-denied`、`ticket-issued`、`external-with-ticket`。

015 要求补进清单的反代、可配置 JDBC 和单副本，在本次部署中已落地，并且专项集成测试全部通过。
