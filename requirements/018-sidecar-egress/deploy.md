# 部署记录 018-sidecar-egress

**时间**：2026-11-15 22:35 Asia/Shanghai（官方 `probe-egress` 于 22:35:31 通过，015 风格 I-001 至 I-016 于 22:35:42 通过）

本轮 ATDD（T-001 至 T-009）此前已全部通过（`aedb791`，2026-11-15 22:16 Asia/Shanghai）。按规格在云电脑上把 sidecar 放进每个 agent Pod，补网络策略和进程级出站规则，改掉按 agent Pod IP 丢弃出站的兜底，并把官方 `scripts/k8s/probe-egress.sh` 改成证明是 agent 进程经本机回环 `/v1/forward` 出站、且不能直连外网。没有缩小现有的 egress 与 gVisor 检查范围。验收环境是部署角色的云电脑，不再依赖 Hang 的 Mac。没有环境地址或没有测试结果，不算完成。019 未开工。

## 环境

- 集群：部署角色云电脑上的 k3s（x86_64）
- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`
- 命名空间：`jasagent`
- 镜像：本机用官方 `scripts/up-stack.sh` 构建并导入 k3s。agent 与 sidecar 镜像均为 `0.1.0`，其中 sidecar 在 Pod 里覆盖监听 `0.0.0.0:18761`。默认路径不指向 Hang 的 Mac。
- 滚动结果：`jasagent-nacos`、`jasagent-metrics-adapter`、`jasagent-gateway`、`jasagent-web`、`jasagent-agentpass`、`jasagent-rag`、`jasagent-grafana`、`jasagent-otel-collector`、`jasagent-prometheus` 均为 1/1 Ready；`jasagent-agent` 两个副本均为 2/2 Ready（容器 `agent` + `sidecar`），并由 HPA 管理。上述工作负载均为 `runtimeClassName=gvisor`。`jasagent-mysql`、`jasagent-redis` 按约定不是 gVisor。独立的共享 sidecar Deployment / Service 已删除。
- 网关仍是单副本。`JASAGENT_GATEWAY_WEB_ORIGIN` 仍为 `http://jasagent-web.jasagent.svc.cluster.local:18722`。

## 环境地址

| 服务 | 地址 |
| --- | --- |
| 网关（集群内，对外入口） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722` |
| 本机访问 | `kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 网关 Service | ClusterIP `10.43.50.22`，端口 18721 |
| web Service | ClusterIP `10.43.177.207`，端口 18722 |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.54.232` |
| 发现到的 agent（当时） | `jasagent-agent-1` `10.42.0.213:8080`，`jasagent-agent-0` `10.42.0.215:8080`（均非回环，已在 Nacos 注册） |
| 每 Pod 内 sidecar | `http://127.0.0.1:18761`（agent 经本机回环访问 `/v1/forward`；sidecar 容器绑在 `0.0.0.0:18761`，供 kubelet 探针使用） |
| agent headless Service | `jasagent-agent.jasagent.svc.cluster.local:8080` |
| 自定义指标适配器 | `jasagent-metrics-adapter.jasagent.svc.cluster.local:6443`，ClusterIP `10.43.32.21` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，ClusterIP `10.43.191.15` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.228.197` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.207.174` |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.80.174` |
| grafana | ClusterIP `10.43.116.83`，端口 3000，NodePort 30300；集群内 `http://jasagent-grafana.jasagent.svc.cluster.local:3000` |
| otel-collector | ClusterIP `10.43.92.45`，端口 4318、8889 |
| prometheus | ClusterIP `10.43.50.218`，端口 9090 |

已不再提供 `jasagent-sidecar` Service。粘性库 JDBC 地址为 `jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?...`，账号来自 Secret `jasagent-mysql` 的 `username`，口令只从该 Secret 挂载到仓库外口令文件路径的文件读取。JDBC 地址里没有 `password=`。Nacos 在集群内关闭认证，地址只通过环境变量注入，本记录不写出文入口令或令牌。

## 一键命令

官方路径：

```bash
bash scripts/k8s/prepare-cluster.sh
bash scripts/up-stack.sh
```

`up-stack.sh` 会先编译本机出站过滤库（`scripts/k8s/install-egress-filter.sh`），再打包、构建并导入镜像，apply 清单，删除遗留的共享 sidecar Deployment / Service，等待命名空间内 Pod 全部就绪，刷新本机兜底，最后跑官方 `scripts/k8s/probe-egress.sh`。成功时打印「部署和探测都通过。」

这台云电脑没有可用的 systemd 作为 1 号进程，内核也缺少 VXLAN、ipset、xt_owner、xt_cgroup 等模块，所以还需要下列环境准备：

1. 以进程方式启动 k3s，并加上 `--write-kubeconfig-mode 644 --flannel-backend=host-gw`（默认 flannel VXLAN 在本机不可用）。
2. Docker 使用 `overlay2`，关闭 containerd snapshotter，否则 `docker save` 出的包无法被 `k3s ctr images import` 完整导入。
3. 先 `kubectl apply -f deploy/k8s/namespace.yaml`。官方 `up-stack.sh` 会在创建命名空间之前写 Secret，首次会报 `namespaces "jasagent" not found`。
4. 本机残留的 Docker `iptables-legacy` FORWARD 策略是 DROP，会丢掉 cni0 上的 Pod 流量。部署前或探测前执行 `/home/box/bin/jasagent-allow-cni-forward.sh`（在 `DOCKER-USER` 放行 `cni0`）。一键脚本若发现该文件会自动刷新。
5. 本机内核 `CONFIG_IP_SET` 未开启，k3s 会跳过 NetworkPolicy 控制器。清单里的 NetworkPolicy 仍会写入 API，但不会被 kube-router / NP 控制器执行。不得把「已 apply NetworkPolicy」写成集群已强制执行。
6. 本机内核也没有 `xt_owner` / `xt_cgroup`。gVisor 使用 `runsc --network=host`（hostinet，platform=systrap）。同一 agent Pod 里的 agent 与 sidecar 共用一个 runsc sandbox，宿主机上看这些出站套接字都属于 `gvisor_sentry`（UID 0），落在 sandbox cgroup 里。因此：
   - 017 的 nft 表 `inet jasagent_np` 按 agent Pod IP 丢弃非 `10.42.0.0/16`、`10.43.0.0/16` 的出站，在 sidecar 进入同一 Pod 后会误伤 sidecar 的合法出站，必须删除。本机 `/home/box/bin/jasagent-enforce-agent-egress.sh` 已改为先删掉该表，并不再按 Pod IP 或 sandbox cgroup 丢包。
   - 实际强制执行是进程级：只给 agent 容器设置 `LD_PRELOAD=/opt/jasagent/libjasagent-egress-filter.so`（源码在 `scripts/k8s/jasagent-egress-filter.c`），拦截 `connect()`，放行本集群与回环，拒绝直连公网。sidecar 容器不加载该库，可以访问公网 80/443。库文件安装在节点 `/home/box/lib/libjasagent-egress-filter.so`，由 agent 容器以 hostPath 只读挂载。
7. 从 017 升级到 018 时，`kubectl apply` 不会自动删掉已经不在清单里的 `deployment/jasagent-sidecar`、`service/jasagent-sidecar` 和 `networkpolicy/jasagent-sidecar-egress`。一键脚本会在 apply 后删除这些遗留对象。全新集群不会再创建它们。

口令、内部令牌和模型密钥只从仓库外文件注入，例如 `JASAGENT_MYSQL_PASSWORD_FILE`、`JASAGENT_INTERNAL_TOKEN_FILE`、`JASAGENT_LLM_ENV_FILE`。

## 本轮清单与脚本改动

- `deploy/k8s/agent.yaml`：每个 agent Pod 增加 sidecar 容器，命令覆盖为 `--server.address=0.0.0.0 --server.port=18761`（不改 sidecar 镜像或 Java 默认 8080）。agent 设置 `JASAGENT_SIDECAR_URL=http://127.0.0.1:18761`。sidecar 以 UID 10002 运行，agent 仍是 UID 10001。agent 容器挂载并预加载出站过滤库。HPA 仍是最小 2、最大 10，主指标 `jasagent_sessions_active`（平均值 8），CPU 利用率 80% 只作补充。headless Service `jasagent-agent:8080` 不变。
- `deploy/k8s/sidecar.yaml`：不再包含可 apply 的共享 Deployment / Service，只保留说明。镜像仍由 `sidecar-local.Dockerfile` 构建。
- `deploy/k8s/networkpolicy.yaml`：去掉按 `component=sidecar` 选择独立 Pod 的 `jasagent-sidecar-egress`，把公网 80/443（排除 RFC1918）合并进 `jasagent-agent-egress`，保留 DNS、Nacos 8848/9848、MySQL 3306、Redis 6379、agentpass 18741、otel 4318。同一网络命名空间内，agent 进程不能直连外网由 LD_PRELOAD 执行，而不是再靠这条 NP。
- `scripts/k8s/probe-egress.sh`：`/v1/forward` 改为在发现到的 agent Pod 里访问本机回环 `127.0.0.1:18761`，不再打已删除的 sidecar Service。`gvisor-sidecar` 改为检查该 sidecar 容器的 `/proc/version` 含 gvisor。`agent-direct-external-blocked` 仍要求 agent 进程不能直连外网，并额外打印 sidecar 是否还能出站。发现逻辑、拒绝回环、至少两台健康实例，以及原有 egress / gVisor 检查范围均未缩小。
- `scripts/up-stack.sh`：不再 apply / 等待 / 重启 `deployment/jasagent-sidecar`；代理配置改打到 `statefulset/jasagent-agent`；apply 后删除遗留共享 sidecar；部署前安装过滤库；探测前等待全部 Pod 就绪并刷新本机兜底。
- `scripts/k8s/jasagent-egress-filter.c`、`scripts/k8s/install-egress-filter.sh`：本机进程级出站过滤库及其编译安装脚本。
- 仓库外本机脚本 `/home/box/bin/jasagent-enforce-agent-egress.sh` 已更新（删除旧 nft 表、不再按 Pod IP 丢包）。原 `/tmp/jasagent-017-deploy/nft-refresh.sh` 循环已停掉并换成 `/tmp/jasagent-018-deploy/nft-refresh.sh`。

## 集成测试结果

### 官方 `scripts/k8s/probe-egress.sh`

测试时间：2026-11-15 22:35:31 Asia/Shanghai。整体结果：`PROBE PASSED`。

当时从 Nacos 发现到两台健康实例：`jasagent-agent-1` `10.42.0.213:8080`，`jasagent-agent-0` `10.42.0.215:8080`，都不是回环地址。`discovered-instances` 通过。

`runtime-class` 通过：非 mysql/redis 的业务 Pod（含 gateway、web、发现到的 agent、agentpass、nacos、metrics-adapter、grafana、otel-collector、prometheus、rag）均为 gvisor；mysql 和 redis 按约定不是 gvisor。agent Pod 里两个容器均为 gvisor。

其余探测全部通过：`pods-ready`、`gvisor-discovered-agents`、`gvisor-gateway`、`gvisor-agentpass`、`gvisor-sidecar`（在发现到的 agent Pod 的 sidecar 容器上检查 `/proc/version`）、`gvisor-nacos`、`gateway-identity`、`agentpass-identity`、`gateway-discovered-agents`、`mysql-discovered-agents`、`redis-discovered-agents`、`agent-direct-external-blocked`（agent 直连被拦，同 Pod sidecar 仍可出站）、`llm-deepseek-no-ticket`、`llm-xai-no-ticket`、`external-no-ticket-denied`、`ticket-issued`、`external-with-ticket`。后四项均经 agent 容器访问本机回环 `127.0.0.1:18761/v1/forward`，不是旧的 sidecar Service。

### 015 专项（I-001 至 I-016）

测试时间：2026-11-15 22:35:42 Asia/Shanghai。全部通过，总结果：`016 IT PASSED`（沿用 015/016/017 同一组检查脚本，覆盖 I-001 到 I-016）。

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

### 规格第 10 到第 15 条

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| 10. 每个 agent Pod 带 sidecar，经回环访问，随 agent 扩缩，不再使用独立共享 Deployment | 通过 | 两个 agent 副本均为 2/2；独立 `jasagent-sidecar` Deployment / Service 已删除 |
| 11. 探测证明是 agent 进程经 `/v1/forward` 出站，且不能直连外网 | 通过 | 官方探测走 `127.0.0.1:18761`；`agent-direct-external-blocked` 通过 |
| 12. 同一网络命名空间采用进程或用户级规则；按 Pod IP 的兜底已改掉 | 通过 | agent 容器 LD_PRELOAD 拦截 `connect()`；旧 nft `jasagent_np` 已删除；sidecar 仍可出站 |
| 13. 本轮网络策略已补上，egress 与 gVisor 范围未缩小，整体 `PROBE PASSED` | 通过 | 22:35:31 官方探测通过；清单已 apply，但本机 NP 控制器仍未启用 |
| 14. I-001 到 I-016 仍通过，网关仍是单副本，Nacos 发现和注册仍有效 | 通过 | 22:35:42 全部通过；网关副本为 1；发现结果为两台非回环 Pod IP:8080 |
| 15. `deploy.md` 写有环境地址、命令和集成测试结果 | 通过 | 本文件。验收环境是部署的云电脑 |

## 出站强制执行的说明与剩余风险

在这台云电脑上，UID 级 nft / iptables `owner` 匹配不可用；把 cgroup BPF 挂到 gVisor sandbox cgroup 会同时拦住 sidecar。因此采用 agent 容器进程级 `LD_PRELOAD` 作为等价手段，并已经用官方探测验证：agent 直连外网失败，同 Pod sidecar 可以出站，白名单与带票转发通过。

剩余风险（需知悉，不阻塞本轮验收）：

- 过滤库拦截的是动态链接的 `connect()`。agent 进程是 Java / bash，走 glibc，官方探测和业务出站覆盖到了。如果有人在 agent 容器里用 `env -u LD_PRELOAD` 启动新进程，或使用完全静态链接、直接发起 syscall 的程序，可以绕过。这不是默认的 agent 进程路径。
- UDP `sendto` 未拦截，集群 DNS 和 `getent` 仍可用；对公网的 TCP 连接会被拒绝。
- 过滤库以 hostPath 挂到节点路径 `/home/box/lib/libjasagent-egress-filter.so`。一键脚本会在本机编译安装；若该文件缺失，agent Pod 会无法启动。
- NetworkPolicy 仍只是 API 对象，k3s 在本机不会执行它们。

018 在云电脑上的部署与探测已完成。
