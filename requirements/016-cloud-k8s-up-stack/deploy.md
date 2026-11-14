# 部署记录 016-cloud-k8s-up-stack

**时间**：2026-11-14 22:35 Asia/Shanghai（官方 `probe-egress` 于 22:33 通过，015 风格 I-001 至 I-016 于 22:35 通过）

本轮 ATDD（T-001 至 T-012）此前已全部通过（`cacca8e`）。按规格只改 `deploy/k8s`，未改 `scripts/up-stack.sh`、`scripts/k8s/prepare-cluster.sh` 和官方 `scripts/k8s/probe-egress.sh`，也没有缩小探测范围。验收环境是部署角色的云电脑，不再依赖 Hang 的 Mac。没有环境地址或没有测试结果，不算完成。

## 环境

- 集群：部署角色云电脑上的 k3s（x86_64）
- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`
- 命名空间：`jasagent`
- 镜像：本机用官方 `scripts/up-stack.sh` 构建并导入 k3s，包括 `jasagent-web:0.1.0`、`jasagent-gateway:0.1.0`、`jasagent-agent:0.1.0`、`jasagent-agentpass:0.1.0`、`jasagent-rag:0.1.0`、`jasagent-sidecar:0.1.0`（web 使用 `jasagent-web-0.1.0-exec.jar` 打包）。默认路径不指向 Hang 的 Mac。
- 滚动结果：`jasagent-web`、`jasagent-grafana`、`jasagent-otel-collector`、`jasagent-prometheus`、`jasagent-rag` 均为 1/1 Ready；`jasagent-gateway`、`jasagent-agentpass`、`jasagent-sidecar` 均为 Ready；`jasagent-agent` 两个副本 Ready。上述工作负载均为 `runtimeClassName=gvisor`。`jasagent-mysql`、`jasagent-redis` 按约定不是 gVisor。
- 网关 `JASAGENT_GATEWAY_WEB_ORIGIN` 仍为 `http://jasagent-web.jasagent.svc.cluster.local:18722`。

## 环境地址

| 服务 | 地址 |
| --- | --- |
| 网关（集群内，对外入口） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722` |
| 本机访问 | `kubectl -n jasagent port-forward svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 网关 Service | ClusterIP `10.43.50.22`，端口 18721 |
| web Service | ClusterIP `10.43.177.207`，端口 18722 |
| agent-0 | `http://jasagent-agent-0.jasagent.svc.cluster.local:18731` |
| agent-1 | `http://jasagent-agent-1.jasagent.svc.cluster.local:18732` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741` |
| sidecar | `http://jasagent-sidecar.jasagent.svc.cluster.local:8080` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.228.197` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.207.174` |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.80.174` |
| grafana | ClusterIP `10.43.116.83`，端口 3000，NodePort 30300；集群内 `http://jasagent-grafana.jasagent.svc.cluster.local:3000` |
| otel-collector | ClusterIP `10.43.92.45`，端口 4318、8889 |
| prometheus | ClusterIP `10.43.50.218`，端口 9090 |

粘性库 JDBC 地址为 `jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?...`，账号来自 Secret `jasagent-mysql` 的 `username`，口令只从该 Secret 挂载到仓库外口令文件路径的文件读取。JDBC 地址里没有 `password=`。密钥文件只从仓库外可配置路径读取，本记录不写出文入口令。

## 一键命令

官方路径（仓库内脚本，本轮未改）：

```bash
bash scripts/k8s/prepare-cluster.sh
bash scripts/up-stack.sh
```

`up-stack.sh` 结束时会跑官方 `scripts/k8s/probe-egress.sh`，成功时打印「部署和探测都通过。」

这台云电脑没有可用的 systemd 作为 1 号进程，内核也缺少 VXLAN、ipset 等模块，所以还需要下列环境准备（不改官方脚本）：

1. 以进程方式启动 k3s，并加上 `--write-kubeconfig-mode 644 --flannel-backend=host-gw`（默认 flannel VXLAN 在本机不可用）。
2. Docker 使用 `overlay2`，关闭 containerd snapshotter，否则 `docker save` 出的包无法被 `k3s ctr images import` 完整导入。
3. 先 `kubectl apply -f deploy/k8s/namespace.yaml`。官方 `up-stack.sh` 会在创建命名空间之前写 Secret，首次会报 `namespaces "jasagent" not found`。
4. 先构建 `jasagent-agent:0.1.0`。官方脚本后半段清单顺序会先构建 sidecar，而 sidecar 镜像依赖已经存在的 agent 镜像。
5. 本机残留的 Docker `iptables-legacy` FORWARD 策略是 DROP，会丢掉 cni0 上的 Pod 流量。部署前执行 `/home/box/bin/jasagent-allow-cni-forward.sh`（在 `DOCKER-USER` 放行 `cni0`）。
6. 本机内核 `CONFIG_IP_SET` 未开启，k3s 会跳过 NetworkPolicy 控制器。清单里的 NetworkPolicy 仍会写入 API。为满足「agent 不能直连外网」的探测，部署后执行 `/home/box/bin/jasagent-enforce-agent-egress.sh`，用 nft set 按当前 agent Pod IP 只放行 `10.42.0.0/16` 和 `10.43.0.0/16`，其余丢弃。没有改探测脚本，也没有缩小探测范围。

口令、内部令牌和模型密钥只从仓库外文件注入，例如 `JASAGENT_MYSQL_PASSWORD_FILE`、`JASAGENT_INTERNAL_TOKEN_FILE`、`JASAGENT_LLM_ENV_FILE`。

## 本轮清单改动

- `deploy/k8s/rag.yaml`：为 rag 工作负载设置 `runtimeClassName: gvisor`。
- `deploy/k8s/networkpolicy.yaml`：新增 `jasagent-rag-egress`，只放行到 kube-dns 的 DNS 和集群内 MySQL 的 3306。

## 集成测试结果

测试时间：2026-11-14 22:33–22:35 Asia/Shanghai。

### 015 专项（I-001 至 I-016）

在本云电脑集群上按 015 的验收口径重跑，全部通过，总结果：`016 IT PASSED`（22:35 Asia/Shanghai）。

| 用例 | 结果 | 说明 |
| --- | --- | --- |
| I-001-single-replica | 通过 | 网关 Deployment 副本为 1，就绪 1，运行中的 Pod 1 个，容器只有 `gateway` |
| I-002-no-mysql-bridge | 通过 | 网关 Pod 里没有 mysql-bridge 容器 |
| I-003-web-origin | 通过 | `JASAGENT_GATEWAY_WEB_ORIGIN=http://jasagent-web.jasagent.svc.cluster.local:18722` |
| I-004-jdbc-config | 通过 | JDBC 指向集群内 MySQL，地址不含 `password=` |
| I-005-affinity-table | 通过 | 库中存在 `jasagent_gateway_affinity`。该表在首次读写时创建：用与网关相同的 JDBC 地址、账号和口令文件内容调用 `MysqlAffinityStore.list` 后表出现，随后复查通过。本条结果不包含口令或令牌原文。 |
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

首次在本集群对已就绪工作负载跑官方探测（2026-11-14 22:32 Asia/Shanghai）整体结果是 `PROBE FAILED`，失败项只有 `agent-direct-external-blocked`：当时 k3s 因内核没有 ipset 跳过了 NetworkPolicy 控制器，agent 仍能直接连到外网地址（解析到本机代理 `198.18.0.1:443`）。其余探测均已通过。没有缩小探测范围，也没有改探测脚本。

补上与 agent 出站策略等价的 nft 强制后，于 2026-11-14 22:33 Asia/Shanghai 重跑官方脚本，整体结果：`PROBE PASSED`。

`runtime-class` 通过：非 mysql/redis 的业务 Pod（含 gateway、web、agent-0、agent-1、agentpass、sidecar、grafana、otel-collector、prometheus、rag）均为 gvisor；mysql 和 redis 按约定不是 gvisor。

其余探测全部通过：`pods-ready`、`gvisor-agent-0`、`gvisor-agent-1`、`gvisor-gateway`、`gvisor-agentpass`、`gvisor-sidecar`、`gateway-identity`、`agentpass-identity`、`gateway-agent-0`、`gateway-agent-1`、`mysql-agent-0`、`mysql-agent-1`、`redis-agent-0`、`redis-agent-1`、`agent-direct-external-blocked`、`llm-deepseek-no-ticket`、`llm-xai-no-ticket`、`external-no-ticket-denied`、`ticket-issued`、`external-with-ticket`。

016 要求的 rag gVisor、云电脑一键部署、015 风格集成检查，以及官方 `probe-egress` 整体通过，均已落地。
