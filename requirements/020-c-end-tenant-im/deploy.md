# 部署记录 020-c-end-tenant-im

**时间**：2026-11-18（Asia/Shanghai）。本文件随部署 PR 写入。规格第 14 到第 16 条要在部署角色的云电脑上实测。本云代理没有对任何集群执行 `kubectl apply`、`scripts/up-stack.sh` 或 `scripts/k8s/probe-egress.sh`。下面凡是实测结果都标为待测，不填写通过或失败。

ATDD 第 1 到第 13 条已在 `cursor/c-end-tenant-im-40b2` 的 `cebfd2fe2cc13265fc29079e0d68b9306efc1b7a` 通过，结果在 `requirements/020-c-end-tenant-im/results/`。本 PR 只补 `deploy/k8s`、本文件和对应变更记录。不改产品 Java、前端、sidecar 或运行时执行模型。

## 环境

计划验收环境是部署角色的云电脑，不再依赖 Hang 的 Mac。公网主机名沿用 019 `requirements/019-gateway-public-ingress/deploy.md` 记录的 `https://jasagent-cloud.tail01f87a.ts.net`。该记录没有写下更新后的主机名。本 PR 没有登录该集群，因此不重写节点名、Kubernetes 版本、Pod 名、Pod IP 或 ClusterIP。那些值以部署当时的 `kubectl` 输出为准，测完再写回本节。

清单里已经固定、不依赖当时 IP 的约定：

- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关副本：1。`JASAGENT_GATEWAY_REPLICAS` 仍是 `1`
- `JASAGENT_GATEWAY_WEB_ORIGIN`：`http://jasagent-web.jasagent.svc.cluster.local:18722`。不指向公网主机名
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED`：仍由 ConfigMap `jasagent-gateway-forwarded` 的键 `trusted` 注入。一键脚本按 Ingress 控制器 Pod IP 写成 `/32`。清单不把该值写成 `0.0.0.0/0` 或 `::/0`
- 公网路径：`https://jasagent-cloud.tail01f87a.ts.net` → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`
- 业务容器除 MySQL、Redis 外仍使用 gVisor。官方 `scripts/k8s/probe-egress.sh` 本轮没有修改，既有 egress 与 gVisor 检查没有删除

## 环境地址

| 服务 | 计划地址 |
| --- | --- |
| 公网入口 | `https://jasagent-cloud.tail01f87a.ts.net`。路径为该主机名 → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721` |
| 本机 Ingress-nginx | `http://127.0.0.1:30080`（Service `ingress-nginx/ingress-nginx-controller`，NodePort HTTP 30080 / HTTPS 30443） |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722` |
| 本机访问网关 | `kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306` |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379` |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848` |
| agent | 只从 Nacos 发现，headless Service `jasagent-agent.jasagent.svc.cluster.local:8080`。不把 agent 挂到 Ingress |
| 每 Pod 内 sidecar | `http://127.0.0.1:18761` |

ClusterIP、Pod IP 和 Ingress 控制器 Pod IP 本轮没有重新采集。`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 的具体 `/32` 以一键脚本当时写入的 ConfigMap 为准。

粘性库、账号库和聊天会话账本使用的 JDBC 地址，按 `scripts/up-stack.sh` 的 `jdbc_url_for_host` 拼出，默认是：

`jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?connectTimeout=10000&socketTimeout=15000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED`

这条地址写入 Secret `jasagent-mysql` 的 `jdbc-url`。账号来自同一 Secret 的 `username`。口令只在同一 Secret 的 `password`，不放进 JDBC 地址。网关把该键挂到 `/var/lib/jasagent/.config/urag-mysql-root.password`，`JASAGENT_GATEWAY_AFFINITY_PASSWORD_FILE` 指向这个路径。agent 的 `JASAGENT_JDBC_PASSWORD` 与 `JASAGENT_CHECKPOINT_JDBC_PASSWORD` 引用同一个 Secret 键。本记录不写口令、令牌、Cookie 或模型密钥。

## 存储

020 的 `GatewayConfiguration` 用 `jasagent.gateway.affinity.jdbc-url`、`jasagent.gateway.affinity.username` 和 `jasagent.gateway.affinity.password-file` 打开三处：

- `MysqlAccountAuth`：表 `jasagent_account`、`jasagent_login_session`
- `SessionLedger`：表 `agent_session`
- `MysqlAffinityStore`：表 `jasagent_gateway_affinity`

这三项已经由环境变量 `JASAGENT_GATEWAY_AFFINITY_JDBC_URL`、`JASAGENT_GATEWAY_AFFINITY_USERNAME` 从 Secret 注入。本轮把口令文件路径也写进清单，避免只靠 `user.home` 间接拼路径。agent 侧聊天会话继续用已有的 `JASAGENT_JDBC_URL`，取值是同一份 `jdbc-url`。

- 网关 `volume home` 仍是 emptyDir，挂在 `/var/lib/jasagent`。它只放其它家目录文件。启动时若该目录里还有未入库的运维账号，才会读一次并迁入 MySQL。它不是账号或会话的权威存储。
- agent 的 `volume home` 仍是 emptyDir。聊天会话写在 `agent_session`，不写在这块盘上。
- MySQL Deployment 的数据目录仍是本栈已有的 emptyDir，挂在 `/var/lib/mysql`，库名 `jasagent`。不新建外部数据库，也不把这个卷换成另一套存储。删掉或重启网关 Pod 不会清掉这个目录。删掉 MySQL Pod 会清掉它。第 14 条测的是网关 Pod。

## 一键命令

在部署云电脑上执行。不要在本云代理上执行。

```bash
bash scripts/k8s/prepare-cluster.sh
bash scripts/up-stack.sh
```

`up-stack.sh` 会构建并导入镜像，apply 清单（含本轮的 `gateway.yaml`），按 Ingress 控制器 Pod IP 写入 `jasagent-gateway-forwarded`，并跑官方 `scripts/k8s/probe-egress.sh`。成功时打印「部署和探测都通过。」

若本机 Tailscale 已经登录，并且该 tailnet 已开启 Serve：

```bash
bash scripts/k8s/configure-funnel.sh
```

该脚本不会发起 `tailscale up` 或 `tailscale login`。本轮没有修改 `scripts/up-stack.sh`、`scripts/k8s/prepare-cluster.sh`、`scripts/k8s/configure-funnel.sh` 或 `scripts/k8s/probe-egress.sh`。

019 记录的云电脑准备仍然适用：这台机器没有可用的 systemd 作为 1 号进程，k3s 需要 `--write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik`；首次要先 `kubectl apply -f deploy/k8s/namespace.yaml` 再写 Secret；Docker `iptables-legacy` FORWARD 为 DROP 时要放行 `cni0`；内核未开 `CONFIG_IP_SET` 时 NetworkPolicy 只写入 API、不会被控制器执行；节点没有公网 IP，入口必须再接 Tailscale Funnel 到 NodePort `30080`。本文件不把这些步骤重写成新的实测。

口令、内部令牌和模型密钥只从仓库外文件注入，例如 `JASAGENT_MYSQL_PASSWORD_FILE`、`JASAGENT_INTERNAL_TOKEN_FILE`、`JASAGENT_LLM_ENV_FILE`。

## 本轮清单改动

- `deploy/k8s/gateway.yaml`：写明账号、登录会话、聊天会话和粘性共用 Secret `jasagent-mysql`。新增 `JASAGENT_GATEWAY_AFFINITY_PASSWORD_FILE=/var/lib/jasagent/.config/urag-mysql-root.password`。`home` 仍是 emptyDir，并写明它不是权威存储。副本仍为 1。`JASAGENT_GATEWAY_WEB_ORIGIN` 不变。`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 仍只从 ConfigMap 读取。
- `deploy/k8s/agent.yaml`：写明 `JASAGENT_JDBC_*` 指向同一条 `jdbc-url`，`home` emptyDir 不是聊天会话的权威存储。环境变量的取值方式不变。
- `deploy/k8s/mysql.yaml`：写明库 `jasagent` 存放上述表，数据目录仍是已有 emptyDir。
- `deploy/k8s/secret.yaml`：注明 `jdbc-url` 同时给网关账号库、会话账本和 agent 执行库使用。该文件仍不参与 `kubectl apply`，仍不包含口令。
- 未改 `deploy/k8s/ingress-gateway.yaml` 与 `deploy/k8s/ingress-nginx.yaml`。Ingress 后端仍只有 `jasagent-gateway:18721`。
- 未改 Java、前端、sidecar 或执行模型。

## 计划中的验收命令

第 14 条。口令和 Cookie 不要写进仓库、本文件或命令回显。先用已注册账号登录，并确认已有聊天会话还在，再删网关 Pod。

```bash
kubectl -n jasagent get pod -l app.kubernetes.io/component=gateway
kubectl -n jasagent delete pod -l app.kubernetes.io/component=gateway
kubectl -n jasagent rollout status deploy/jasagent-gateway
```

新 Pod Ready 后，用同一账号再登录，确认账号仍能登录、该会话仍在。同时核对网关环境里有 `JASAGENT_GATEWAY_AFFINITY_JDBC_URL`、`JASAGENT_GATEWAY_AFFINITY_USERNAME` 和 `JASAGENT_GATEWAY_AFFINITY_PASSWORD_FILE`，且 JDBC 地址不含 `password=`：

```bash
kubectl -n jasagent get deploy jasagent-gateway -o jsonpath='{range .spec.template.spec.containers[?(@.name=="gateway")].env[*]}{.name}{"\n"}{end}'
```

第 15 条。

```bash
curl -fsS https://jasagent-cloud.tail01f87a.ts.net/health
kubectl -n jasagent get ingress jasagent-gateway -o jsonpath='{range .spec.rules[*].http.paths[*]}{.backend.service.name}:{.backend.service.port.number}{"\n"}{end}'
```

Ingress 后端应只有 `jasagent-gateway:18721`。经该 HTTPS 主机名注册并登录。普通用户调用 `GET /api/cluster`、打开 `/admin/cluster` 应被网关拒绝。两名用户互不可见会话。这条入口不挂 `jasagent-web`、Nacos、MySQL、Redis、agent 或监控。

第 16 条。

```bash
bash scripts/k8s/probe-egress.sh
```

整体应为 `PROBE PASSED`。既有 egress 与 gVisor 检查一项都不能少。018 的每 Pod sidecar 与 `LD_PRELOAD` 出站约定仍有效。网关副本仍为 1。

若部署机上仍使用 016 到 019 那套 015 风格 I-001 至 I-016 运行器，再跑一遍。仓库里没有单独的 I-001 脚本路径。跑完前不要把结果写成通过。

## 集成测试结果

本轮没有在集群上执行上述命令。不记录 `PROBE PASSED`、`016 IT PASSED`，也不记录公网注册、登录或隔离的通过与否。

### 官方 `scripts/k8s/probe-egress.sh`

待测。

### 015 风格 I-001 至 I-016

待测。若部署时不使用该运行器，在这里写明未使用。不要补造逐条结果。

### 经公网 Funnel 的注册、登录和隔离

待测。

### 规格第 14 到第 16 条

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| 14. 删除或重启网关 Pod 后，已注册账号仍能登录，已有聊天会话仍在。清单不再把 emptyDir 当作账号和会话的唯一存储 | 待测 | 清单已把权威存储写到已有 MySQL，并保留 home emptyDir 只做其它家目录文件。云电脑上的删除 Pod 复验还没有做 |
| 15. 经 `https://jasagent-cloud.tail01f87a.ts.net` 可以注册和登录；普通用户看不到、也调不到集群监控；两名用户的会话仍然隔离。公网入口仍只转到网关 | 待测 | 主机名沿用 019。Ingress 清单仍只转到 `jasagent-gateway:18721`。公网注册、登录和隔离还没有做 |
| 16. 官方探测整体为 `PROBE PASSED`；egress 与 gVisor 范围没有缩小；018 sidecar 仍有效；网关仍是单副本；本文件写有环境地址、命令和集成测试结果 | 部分完成 | 环境地址、命令和待测状态已写入本文件。探测脚本未改、未删检查。网关清单副本仍为 1。官方探测和 I-001 至 I-016 的当次结果待测。验收环境仍是部署云电脑 |

## 清单核对（未上集群）

只读了仓库里的清单，没有连接集群：

- `deploy/k8s/gateway.yaml` 仍有 `replicas: 1`
- `JASAGENT_GATEWAY_WEB_ORIGIN` 仍是 `http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED` 仍从 ConfigMap `jasagent-gateway-forwarded` 读取。清单里没有把该值写成 `0.0.0.0/0` 或 `::/0`
- `deploy/k8s/ingress-gateway.yaml` 的后端仍是 `jasagent-gateway` 端口 `18721`
- 本轮 diff 里的 JDBC 字样没有 `password=`
- `scripts/k8s/probe-egress.sh` 相对本 PR 基线没有改动

这些核对不能代替第 14 到第 16 条。
