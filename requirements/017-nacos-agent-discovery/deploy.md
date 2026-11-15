# 部署记录 017-nacos-agent-discovery

**时间**：2026-11-15 13:44 Asia/Shanghai（官方 `probe-egress` 于 07:28 通过，015 风格 I-001 至 I-016 于 13:44 通过）

本轮 ATDD（T-001 至 T-012）此前已全部通过（`35c78ca`，2026-11-15 07:19 Asia/Shanghai）。按规格在云电脑上补 Nacos、agent 扩缩容、网络策略、自定义指标，并把官方 `scripts/k8s/probe-egress.sh` 改成按当时从 Nacos 发现到的实例验收，没有缩小现有的 egress 与 gVisor 检查范围。验收环境是部署角色的云电脑，不再依赖 Hang 的 Mac。没有环境地址或没有测试结果，不算完成。

## 环境

- 集群：部署角色云电脑上的 k3s（x86_64）
- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`
- 命名空间：`jasagent`
- 镜像：本机用官方 `scripts/up-stack.sh` 构建并导入 k3s。本轮新增公共镜像 `nacos/nacos-server:v2.4.3`、`registry.k8s.io/prometheus-adapter/prometheus-adapter:v0.12.0`。默认路径不指向 Hang 的 Mac。
- 滚动结果：`jasagent-nacos`、`jasagent-metrics-adapter`、`jasagent-gateway`、`jasagent-web`、`jasagent-agentpass`、`jasagent-sidecar`、`jasagent-rag`、`jasagent-grafana`、`jasagent-otel-collector`、`jasagent-prometheus` 均为 1/1 Ready；`jasagent-agent` 两个副本 Ready，并由 HPA 管理。上述工作负载均为 `runtimeClassName=gvisor`。`jasagent-mysql`、`jasagent-redis` 按约定不是 gVisor。
- 网关仍是单副本。`JASAGENT_GATEWAY_WEB_ORIGIN` 仍为 `http://jasagent-web.jasagent.svc.cluster.local:18722`。网关环境变量已改为 `JASAGENT_NACOS_SERVER_ADDR=jasagent-nacos.jasagent.svc.cluster.local:8848`，不再设置 `JASAGENT_GATEWAY_AGENTS`。

## 环境地址

| 服务 | 地址 |
| --- | --- |
| 网关（集群内，对外入口） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722` |
| 本机访问 | `kubectl -n jasagent port-forward svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 网关 Service | ClusterIP `10.43.50.22`，端口 18721 |
| web Service | ClusterIP `10.43.177.207`，端口 18722 |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.54.232` |
| 发现到的 agent（当时） | `jasagent-agent-1` `10.42.0.144:8080`，`jasagent-agent-0` `10.42.0.150:8080`（均非回环，已在 Nacos 注册） |
| agent headless Service | `jasagent-agent.jasagent.svc.cluster.local:8080` |
| 自定义指标适配器 | `jasagent-metrics-adapter.jasagent.svc.cluster.local:6443`，ClusterIP `10.43.32.21` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，ClusterIP `10.43.191.15` |
| sidecar | `http://jasagent-sidecar.jasagent.svc.cluster.local:8080`，ClusterIP `10.43.241.133` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.228.197` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.207.174` |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.80.174` |
| grafana | ClusterIP `10.43.116.83`，端口 3000，NodePort 30300；集群内 `http://jasagent-grafana.jasagent.svc.cluster.local:3000` |
| otel-collector | ClusterIP `10.43.92.45`，端口 4318、8889 |
| prometheus | ClusterIP `10.43.50.218`，端口 9090 |

粘性库 JDBC 地址为 `jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?...`，账号来自 Secret `jasagent-mysql` 的 `username`，口令只从该 Secret 挂载到仓库外口令文件路径的文件读取。JDBC 地址里没有 `password=`。Nacos 在集群内关闭认证，地址只通过环境变量注入，本记录不写出文入口令或令牌。

## 一键命令

官方路径：

```bash
bash scripts/k8s/prepare-cluster.sh
bash scripts/up-stack.sh
```

为了让一键部署先拉起 Nacos、再拉起 agent 和网关，并装上自定义指标适配器，本轮改了 `scripts/up-stack.sh` 的清单顺序：`nacos.yaml` 放入早期清单并等待就绪，`metrics-adapter.yaml` 放入后期清单并等待就绪。`up-stack.sh` 结束时会跑官方 `scripts/k8s/probe-egress.sh`，成功时打印「部署和探测都通过。」

这台云电脑没有可用的 systemd 作为 1 号进程，内核也缺少 VXLAN、ipset 等模块，所以还需要下列环境准备：

1. 以进程方式启动 k3s，并加上 `--write-kubeconfig-mode 644 --flannel-backend=host-gw`（默认 flannel VXLAN 在本机不可用）。
2. Docker 使用 `overlay2`，关闭 containerd snapshotter，否则 `docker save` 出的包无法被 `k3s ctr images import` 完整导入。
3. 先 `kubectl apply -f deploy/k8s/namespace.yaml`。官方 `up-stack.sh` 会在创建命名空间之前写 Secret，首次会报 `namespaces "jasagent" not found`。
4. 先构建 `jasagent-agent:0.1.0`。官方脚本后半段清单顺序会先构建 sidecar，而 sidecar 镜像依赖已经存在的 agent 镜像。
5. 本机残留的 Docker `iptables-legacy` FORWARD 策略是 DROP，会丢掉 cni0 上的 Pod 流量。部署前执行 `/home/box/bin/jasagent-allow-cni-forward.sh`（在 `DOCKER-USER` 放行 `cni0`）。
6. 本机内核 `CONFIG_IP_SET` 未开启，k3s 会跳过 NetworkPolicy 控制器。清单里的 NetworkPolicy 仍会写入 API。为满足「agent 不能直连外网」的探测，部署后执行 `/home/box/bin/jasagent-enforce-agent-egress.sh`，用 nft set 按当前 agent Pod IP 只放行 `10.42.0.0/16` 和 `10.43.0.0/16`，其余丢弃。没有缩小探测范围。
7. 从 016 升级到 017 时，`kubectl apply` 不会自动删掉已经不在清单里的 `jasagent-agent-0`、`jasagent-agent-1` Service。本次部署后已删除这两条钉死端口的 Service。全新集群不会再创建它们。

口令、内部令牌和模型密钥只从仓库外文件注入，例如 `JASAGENT_MYSQL_PASSWORD_FILE`、`JASAGENT_INTERNAL_TOKEN_FILE`、`JASAGENT_LLM_ENV_FILE`。

## 本轮清单与脚本改动

- `deploy/k8s/nacos.yaml`：新增独立模式 Nacos（`nacos/nacos-server:v2.4.3`），`runtimeClassName=gvisor`，认证关闭，Service 暴露 8848 和 9848。
- `deploy/k8s/agent.yaml`：去掉把 `jasagent-agent-0` / `jasagent-agent-1` 钉在 18731 / 18732 的 Service；agent 向 Nacos 注册 Pod IP；新增 HPA，最小 2、最大 10，主指标 `jasagent_sessions_active`（平均值 8），CPU 利用率 80% 只作补充。
- `deploy/k8s/gateway.yaml`：去掉静态目录环境变量，改为只配置 Nacos 地址；网关仍单副本。
- `deploy/k8s/networkpolicy.yaml`：agent、网关出站放行到 Nacos 的 8848 和 9848；新增 `jasagent-nacos-egress`，只放行到 kube-dns 的 DNS。
- `deploy/k8s/observability.yaml`：Prometheus 增加对 agent `/health/load` 的刮取，并赋予只读 Pod 的 ServiceAccount，以便按发现到的实例采集 `jasagent_sessions_active`。
- `deploy/k8s/metrics-adapter.yaml`：把该主指标接到 `custom.metrics.k8s.io`，供 HPA 使用。适配器同样使用 gVisor。
- `deploy/k8s/entrypoint.sh`：集群内部地址不走代理的名单补上 `jasagent-nacos`。
- `scripts/k8s/probe-egress.sh`：按当时 Nacos 发现结果验收 agent，不再写死 `jasagent-agent-0` / `jasagent-agent-1`；保留并继续检查原有 egress 与 gVisor 范围，并覆盖 Nacos。
- `scripts/up-stack.sh`：一键 apply 增加 `nacos.yaml` 和 `metrics-adapter.yaml`，并等待它们就绪。

## 集成测试结果

### 官方 `scripts/k8s/probe-egress.sh`

测试时间：2026-11-15 07:28 Asia/Shanghai。整体结果：`PROBE PASSED`。`up-stack.sh` 结束时打印「部署和探测都通过。」

当时从 Nacos 发现到两台健康实例：`jasagent-agent-1` `10.42.0.144:8080`，`jasagent-agent-0` `10.42.0.150:8080`，都不是回环地址。`discovered-instances` 通过。

`runtime-class` 通过：非 mysql/redis 的业务 Pod（含 gateway、web、发现到的 agent、agentpass、sidecar、nacos、metrics-adapter、grafana、otel-collector、prometheus、rag）均为 gvisor；mysql 和 redis 按约定不是 gvisor。

其余探测全部通过：`pods-ready`、`gvisor-discovered-agents`、`gvisor-gateway`、`gvisor-agentpass`、`gvisor-sidecar`、`gvisor-nacos`、`gateway-identity`、`agentpass-identity`、`gateway-discovered-agents`、`mysql-discovered-agents`、`redis-discovered-agents`、`agent-direct-external-blocked`、`llm-deepseek-no-ticket`、`llm-xai-no-ticket`、`external-no-ticket-denied`、`ticket-issued`、`external-with-ticket`。

### 015 专项（I-001 至 I-016）

测试时间：2026-11-15 13:44 Asia/Shanghai。全部通过，总结果：`016 IT PASSED`（沿用 015/016 同一组检查脚本，覆盖 I-001 到 I-016）。

| 用例 | 结果 | 说明 |
| --- | --- | --- |
| I-001-single-replica | 通过 | 网关 Deployment 副本为 1，就绪 1，运行中的 Pod 1 个，容器只有 `gateway` |
| I-002-no-mysql-bridge | 通过 | 网关 Pod 里没有 mysql-bridge 容器 |
| I-003-web-origin | 通过 | `JASAGENT_GATEWAY_WEB_ORIGIN=http://jasagent-web.jasagent.svc.cluster.local:18722` |
| I-004-jdbc-config | 通过 | JDBC 指向集群内 MySQL，地址不含 `password=` |
| I-005-affinity-table | 通过 | 库中存在 `jasagent_gateway_affinity` |
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

### 规格第 13 到第 18 条

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| 13. 集群内 Nacos，agent 能注册，网关能发现并转发 | 通过 | Nacos Ready；发现结果为两台 Pod IP:8080；官方探测 `gateway-discovered-agents` 通过 |
| 14. HPA 最小 2、最大 10，主指标为进行中的会话，CPU 只作补充 | 通过 | `HorizontalPodAutoscaler/jasagent-agent` 目标是 StatefulSet，`minReplicas=2`，`maxReplicas=10`，主指标 `jasagent_sessions_active`（平均值 8），CPU 利用率 80% 为补充；当时当前值会话 0、CPU 约 11%–12%，副本 2 |
| 15. 本轮网络策略已补上，egress 与 gVisor 范围未缩小 | 通过 | 已增加 Nacos 相关策略；官方探测 `runtime-class`、`gvisor-*`、`agent-direct-external-blocked` 仍通过 |
| 16. `probe-egress.sh` 按发现实例验收，整体 `PROBE PASSED` | 通过 | 不再写死 `jasagent-agent-0`/`jasagent-agent-1`；07:28 整体通过 |
| 17. I-001 到 I-016 仍通过，网关仍是单副本 | 通过 | 13:44 全部通过；网关副本为 1 |
| 18. `deploy.md` 写有环境地址、命令和集成测试结果 | 通过 | 本文件。验收环境是部署的云电脑 |

017 在云电脑上的部署与探测已完成。
