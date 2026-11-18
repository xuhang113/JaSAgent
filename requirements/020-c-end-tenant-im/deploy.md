# 部署记录 020-c-end-tenant-im

**时间**：2026-11-18（Asia/Shanghai）。本文件补写规格第 14 到第 16 条在部署云电脑上的实测结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、Cookie 和模型密钥不写入本文件。

ATDD 第 1 到第 13 条已在 [PR #5](https://github.com/xuhang113/JaSAgent/pull/5) 的 `cebfd2fe2cc13265fc29079e0d68b9306efc1b7a` 通过，结果在 `requirements/020-c-end-tenant-im/results/`。部署清单已由 [PR #6](https://github.com/xuhang113/JaSAgent/pull/6) 合进 master（`6a7a92c2f8e1b8da23cfef13856028d9fa2a8b5b`）。本文件是合并后在集群上测完再回写的结果。

## 环境

- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`，没有 ExternalIP，也没有公网 IP
- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关副本：1，`JASAGENT_GATEWAY_REPLICAS=1`。实测 `spec.replicas=1` 且 `readyReplicas=1`
- `JASAGENT_GATEWAY_WEB_ORIGIN`：`http://jasagent-web.jasagent.svc.cluster.local:18722`。不指向公网主机名
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED`：ConfigMap `jasagent-gateway-forwarded` 的键 `trusted` 为 Ingress 控制器 Pod IP `10.42.0.18/32`。不是 `0.0.0.0/0` 或 `::/0`
- 公网路径：`https://jasagent-cloud.tail01f87a.ts.net` → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`
- 业务容器除 MySQL、Redis 外仍使用 gVisor。官方 `scripts/k8s/probe-egress.sh` 本轮没有修改，既有 egress 与 gVisor 检查没有删除
- 本轮没有执行 `tailscale up` 或 `tailscale login`

## 环境地址

| 服务 | 实测地址 |
| --- | --- |
| 公网入口 | `https://jasagent-cloud.tail01f87a.ts.net`。路径为该主机名 → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`。公网 DNS 解析为 `209.177.145.97`、`209.177.145.192`。本机 MagicDNS 会把该主机名解析到 `198.18.0.1`，从这台云电脑访问时使用 `curl --resolve jasagent-cloud.tail01f87a.ts.net:443:<公网 IP>` |
| 本机 Ingress-nginx | `http://127.0.0.1:30080`（Service `ingress-nginx/ingress-nginx-controller`，ClusterIP `10.43.196.119`，NodePort HTTP 30080 / HTTPS 30443） |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721`，ClusterIP `10.43.183.90`。第 14 条删除 Pod 后的网关 Pod 是 `jasagent-gateway-7fd99d7ff7-prsmn`，Pod IP `10.42.0.32` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722`，ClusterIP `10.43.106.194`，Pod `jasagent-web-787f99576-p9d94`，Pod IP `10.42.0.23` |
| 本机访问网关 | `kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 发现到的 agent（当时） | `jasagent-agent-0` `10.42.0.31:8080`，`jasagent-agent-1` `10.42.0.30:8080`（均非回环，已在 Nacos 注册） |
| 每 Pod 内 sidecar | `http://127.0.0.1:18761` |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.197.129` |
| 自定义指标适配器 | `jasagent-metrics-adapter.jasagent.svc.cluster.local:6443`，ClusterIP `10.43.175.19` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，ClusterIP `10.43.159.39` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.149.229` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.157.90`，Pod `jasagent-mysql-565b56b555-l5qjr`，Pod IP `10.42.0.6`。本轮没有删除或重建这个 Pod |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.210.7`，Pod `jasagent-redis-679668c9f-lcjrd`，Pod IP `10.42.0.5` |
| grafana | ClusterIP `10.43.20.61`，端口 3000，NodePort 30300 |
| otel-collector | ClusterIP `10.43.8.204`，端口 4318、8889 |
| prometheus | ClusterIP `10.43.106.71`，端口 9090 |
| Ingress 控制器 Pod | `ingress-nginx-controller-788bbd94df-jrxz6`，Pod IP `10.42.0.18` |
| 本机 Tailscale | 主机名 `jasagent-cloud`，稳定主机名 `jasagent-cloud.tail01f87a.ts.net`，IPv4 `100.69.17.4` |

粘性库、账号库和聊天会话账本使用的 JDBC 地址，写入 Secret `jasagent-mysql` 的 `jdbc-url`，实测为：

`jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?connectTimeout=10000&socketTimeout=15000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED`

这条地址不含 `password=`。账号来自同一 Secret 的 `username`。口令只在同一 Secret 的 `password`。网关把该键挂到 `/var/lib/jasagent/.config/urag-mysql-root.password`，环境变量 `JASAGENT_GATEWAY_AFFINITY_PASSWORD_FILE` 指向这个路径。`JASAGENT_GATEWAY_AFFINITY_JDBC_URL` 和 `JASAGENT_GATEWAY_AFFINITY_USERNAME` 从同一 Secret 注入。

## 存储

020 的网关用同一套亲和性 JDBC 打开三处：

- `MysqlAccountAuth`：表 `jasagent_account`、`jasagent_login_session`
- `SessionLedger`：表 `agent_session`
- `MysqlAffinityStore`：表 `jasagent_gateway_affinity`

第 16 条里的 I-005 在库 `jasagent` 中见到了这三张表，以及原有的 `agent_session`、`agent_workspace` 等执行表。

- 网关 `volume home` 仍是 emptyDir，挂在 `/var/lib/jasagent`。它只放其它家目录文件，不是账号或会话的权威存储。第 14 条删除网关 Pod 后，新 Pod 里没有运维账号的 `password.hash`，已注册账号和聊天会话仍在
- agent 的 `volume home` 仍是 emptyDir。聊天会话写在 `agent_session`
- MySQL Deployment 的数据目录仍是本栈已有的 emptyDir，挂在 `/var/lib/mysql`，库名 `jasagent`。删掉或重启网关 Pod 不会清掉这个目录。删掉 MySQL Pod 会清掉它。本轮只删了网关 Pod，MySQL Pod `jasagent-mysql-565b56b555-l5qjr` 的 uid 保持为 `5af2e226-a9ff-4d09-ae0c-e809a0dece1a`

## 一键命令

在这台云电脑上实际执行过：

```bash
bash scripts/up-stack.sh
bash scripts/k8s/probe-egress.sh
```

`up-stack.sh` 于 14:50（Asia/Shanghai）结束，打印「部署和探测都通过。」当时官方探测已是 `PROBE PASSED`。滚动范围包含网关和 web 等业务工作负载，没有滚动重启 MySQL。MySQL Pod 从当天上午一直保留到验收结束。

本轮没有修改 `scripts/up-stack.sh`、`scripts/k8s/prepare-cluster.sh`、`scripts/k8s/configure-funnel.sh` 或 `scripts/k8s/probe-egress.sh`。Funnel 在验收前曾因本机 `tailscaled` 与协调服务断线而从这台机器访问公网边缘出现 TLS unexpected EOF。恢复时只按原参数重启了已有状态的 `tailscaled`，并再次执行 `tailscale funnel --bg http://127.0.0.1:30080`。没有执行 `tailscale up` 或 `tailscale login`。

019 记录的云电脑准备仍然适用：这台机器没有可用的 systemd 作为 1 号进程，k3s 需要 `--write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik`；Docker `iptables-legacy` FORWARD 为 DROP 时要放行 `cni0`；内核未开 `CONFIG_IP_SET` 时 NetworkPolicy 只写入 API；节点没有公网 IP，入口必须再接 Tailscale Funnel 到 NodePort `30080`。

## 本轮清单改动

清单改动已在 [PR #6](https://github.com/xuhang113/JaSAgent/pull/6) 合并，本文件不再重复未上集群时的计划说明。集群里已生效的要点：

- 网关环境有 `JASAGENT_GATEWAY_AFFINITY_JDBC_URL`、`JASAGENT_GATEWAY_AFFINITY_USERNAME` 和 `JASAGENT_GATEWAY_AFFINITY_PASSWORD_FILE=/var/lib/jasagent/.config/urag-mysql-root.password`
- 网关副本仍为 1，`JASAGENT_GATEWAY_WEB_ORIGIN` 仍指向集群内 web
- Ingress 后端仍只有 `jasagent-gateway:18721`
- 未改官方探测脚本和一键脚本

## 验收命令

第 14 条实际执行（口令和 Cookie 未写入仓库或回显）：

```bash
kubectl -n jasagent get pod -l app.kubernetes.io/component=gateway
kubectl -n jasagent delete pod -l app.kubernetes.io/component=gateway
kubectl -n jasagent rollout status deploy/jasagent-gateway
```

删除前网关 Pod 是 `jasagent-gateway-7fd99d7ff7-646lc`。15:53:41（Asia/Shanghai）删除后，15:53:47 新 Pod `jasagent-gateway-7fd99d7ff7-prsmn` Ready。随后用删除前已注册的账号重新登录，并读取删除前创建的聊天会话。

第 15 条实际经公网主机名访问（本机使用 `--resolve` 到 `209.177.145.97`）：

```bash
curl -fsS --resolve jasagent-cloud.tail01f87a.ts.net:443:209.177.145.97 https://jasagent-cloud.tail01f87a.ts.net/health
kubectl -n jasagent get ingress jasagent-gateway -o jsonpath='{range .spec.rules[*].http.paths[*]}{.backend.service.name}:{.backend.service.port.number}{"\n"}{end}'
```

Ingress 后端实测为 `jasagent-gateway:18721`。同一主机名上执行了注册、登录、`GET /api/me`、`GET /api/cluster`、`GET /admin/cluster` 以及会话列表和跨用户会话读取。

第 16 条：

```bash
bash scripts/k8s/probe-egress.sh
```

官方探测在 `up-stack.sh` 结束时（14:50）和删除网关 Pod 之后（15:54:43）都打印 `PROBE PASSED`。随后用本机 016 到 019 沿用的 015 风格 I-001 至 I-016 运行器（不在仓库里）再跑一遍，15:56:14 打印 `016 IT PASSED`。

## 集成测试结果

### 官方 `scripts/k8s/probe-egress.sh`

通过。`up-stack.sh` 于 14:50（Asia/Shanghai）打印 `PROBE PASSED` 和「部署和探测都通过。」删除网关 Pod 并完成第 14 条复验后，于 15:54:43 再次执行同一脚本，仍打印 `PROBE PASSED`。第二次结果包括：`PASS ingress-nginx-runtime`（控制器 Pod `ingress-nginx-controller-788bbd94df-jrxz6` 为 gVisor，且 `hostNetwork=false`）、`PASS ingress-only-gateway`（`jasagent-gateway:18721`）、`PASS forwarded-trusted-ingress-only`（`trusted=10.42.0.18/32`，对端是该 Ingress Pod IP）、`PASS gateway-single-replica-web-origin`（`replicas=1 ready=1 origin=http://jasagent-web.jasagent.svc.cluster.local:18722`），以及既有的 egress、sidecar 与出站检查。探测脚本相对仓库没有改动，检查项没有缩小。

### 015 风格 I-001 至 I-016

通过。15:56:14（Asia/Shanghai）使用本机既有运行器 `/tmp/jasagent-019-deploy/run-it-001-016.sh`（仓库里没有这份脚本）。I-001 至 I-016 全部为 `PASS`，摘要为 `016 IT PASSED`。其中库 `jasagent` 里能看到 `jasagent_account`、`jasagent_login_session`、`jasagent_gateway_affinity` 和 `agent_session`；网关与 web 的 `/proc/version` 均为 gVisor。I-007 及之后依赖本机 `127.0.0.1:18721` 的端口转发，已按文档中的 `kubectl port-forward` 打开后再跑。

### 经公网 Funnel 的注册、登录和隔离

通过。15:52 至 15:54（Asia/Shanghai）经 `https://jasagent-cloud.tail01f87a.ts.net` 实测：

- `GET /health` 返回 200，正文为 `{"status":"up"}`
- 用一次性测试账号 `it020a`、`it020b` 完成注册并登录。两人 `/api/me` 的角色都是 `user`，租户编号不同。登录响应设置 `JASAGENT_SESSION`，属性为 `Path=/`、`Secure`、`HttpOnly`、`SameSite=Lax`。口令和 Cookie 值未写入本文件
- 运维账号 `admin` 登录后 `/api/me` 的角色是 `admin`，`GET /api/cluster` 返回 200
- 普通用户 `GET /api/cluster` 和 `GET /admin/cluster` 都被网关拒绝，状态码 403，正文 `errorCode` 为 `FORBIDDEN`
- `it020a` 新建会话 `020-a-session`，`it020b` 新建会话 `020-b-session`。各自列表只看到自己的会话。对方读取详情或时间线得到 403，`errorCode` 为 `FORBIDDEN`；读取自己的会话是 200
- Ingress 只转到 `jasagent-gateway:18721`，没有把 web、Nacos、MySQL、Redis、agent 或监控挂到这条入口

这些测试账号只用于本轮验收，口令未写入仓库。

### 规格第 14 到第 16 条

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| 14. 删除或重启网关 Pod 后，已注册账号仍能登录，已有聊天会话仍在。清单不再把 emptyDir 当作账号和会话的唯一存储 | 通过 | 15:53:41 只删除了网关 Pod `jasagent-gateway-7fd99d7ff7-646lc`。新 Pod `jasagent-gateway-7fd99d7ff7-prsmn` 于 15:53:47 Ready，家目录 emptyDir 里没有 `password.hash`。MySQL Pod `jasagent-mysql-565b56b555-l5qjr` 未被删除，uid 不变。之后 `it020a`、`it020b` 和 `admin` 都能再次登录；`it020a` 的 `020-a-session` 与 `it020b` 的 `020-b-session` 仍在，会话编号与删除前相同。网关环境有亲和性 JDBC 三项，JDBC 地址不含 `password=` |
| 15. 经 `https://jasagent-cloud.tail01f87a.ts.net` 可以注册和登录；普通用户看不到、也调不到集群监控；两名用户的会话仍然隔离。公网入口仍只转到网关 | 通过 | 见上一节。删除网关 Pod 之后，同一公网主机名的 `/health`、登录和会话读取仍然成功。普通用户删除后再次调用 `GET /api/cluster` 仍是 403 |
| 16. 官方探测整体为 `PROBE PASSED`；egress 与 gVisor 范围没有缩小；018 sidecar 仍有效；网关仍是单副本；本文件写有环境地址、命令和集成测试结果 | 通过 | 官方探测在 14:50 与 15:54:43 两次都是 `PROBE PASSED`。I-001 至 I-016 于 15:56:14 全部通过，摘要为 `016 IT PASSED`。网关仍是单副本。本文件已写入环境地址、命令和结果 |

## 清单与集群核对

- 网关 Deployment `replicas: 1`，就绪副本为 1
- `JASAGENT_GATEWAY_WEB_ORIGIN` 为 `http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED` 为 `10.42.0.18/32`，不是 `0.0.0.0/0` 或 `::/0`
- Ingress 后端只有 `jasagent-gateway:18721`
- Secret 里的 JDBC 地址不含 `password=`
- `scripts/k8s/probe-egress.sh` 本轮没有改动

代码审查对 [PR #6](https://github.com/xuhang113/JaSAgent/pull/6) 的非阻塞说明仍然成立：MySQL 数据目录还是原栈 emptyDir，删网关 Pod 不会丢数据，但删 MySQL Pod 会丢。本轮没有删除 MySQL Pod。
