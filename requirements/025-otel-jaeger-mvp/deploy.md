# 部署记录 025-otel-jaeger-mvp

**时间**：2026-11-24（Asia/Shanghai）。本文件写规格第 9 到第 13 条在部署云电脑上的结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、Funnel/Tailscale 凭证和密钥原文不写入本文件。

ATDD 第 1 到第 8 条已在 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 通过，头提交是 [`475bc7b`](https://github.com/xuhang113/JaSAgent/commit/475bc7b21e9e8420f4d40c6f0b4607f0c45364b0)，产品实现提交是 [`ba44725`](https://github.com/xuhang113/JaSAgent/commit/ba44725c05c61f6dcf6b7a8d0452595ed2fdc7c6)，结果在 `requirements/025-otel-jaeger-mvp/results/`。该 PR 已于 19:01:19（Asia/Shanghai）用 merge commit [`7b97aac`](https://github.com/xuhang113/JaSAgent/commit/7b97aac94803813a59f12df33dd5d506ebcfa981) 合入 master，没有 squash，也没有直接推 master。本分支从这次合并之后的 master 另开，只含 `deploy/k8s`、官方脚本和本记录，不改 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 里的产品代码，也没有直接推 master。

第 9 条 Funnel、以及正式关闭 024 整块和 019，不在本轮。验 Jaeger 界面时，以导出后的根 span `jasagent.execution` 为准，不用内存里的执行详情 traceId 去对。

## 环境

- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`，没有 ExternalIP
- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关副本：实测 `spec.replicas=2` 且 `readyReplicas=2`。两个 Pod 分别是 `jasagent-gateway-597dd88c7b-nqgzq`（`10.42.0.22`）和 `jasagent-gateway-597dd88c7b-kbkk5`（`10.42.0.23`）
- `JASAGENT_GATEWAY_WEB_ORIGIN`：`http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_JAEGER_ORIGIN`：`http://jasagent-jaeger.jasagent.svc.cluster.local:16686`（只含根地址，不含路径）
- `JASAGENT_OTEL_ENDPOINT`（agent）：`http://jasagent-otel-collector.jasagent.svc.cluster.local:4318/v1/traces`
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED`：`10.42.0.21/32`（Ingress 控制器 Pod IP），不是 `0.0.0.0/0` 或 `::/0`
- Ingress 后端仍只有 `jasagent-gateway:18721`。Jaeger、web、Nacos、MySQL、Redis、agent、agentpass 都不在 Ingress 上。Jaeger Service 类型是 ClusterIP，端口 16686 和 4317，没有 nodePort
- 本轮不执行 `tailscale up` 或 `tailscale login`，也不验收 Funnel
- NetworkPolicy 按现有风格补了规则，但本集群因缺少 `CONFIG_IP_SET` 不会真正生效，不当作通过条件
- 业务容器除 MySQL、Redis 外仍使用 gVisor，Jaeger 和 otel-collector 也是 gVisor。官方探测没有删除或跳过 018–024 的既有项
- 业务镜像用已合入 master 的 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 产品提交打包，清单和官方脚本用本 PR

本轮云电脑在验收前经历过一次环境重建：`/var/lib/rancher`、`/etc/rancher` 和 `/var/lib/docker` 不在了。用官方 `bash scripts/k8s/prepare-cluster.sh` 于 20:14:09（Asia/Shanghai）重新拉起 k3s（flannel `host-gw`，RuntimeClass `gvisor` 已创建）。镜像构建仍走官方 `bash scripts/up-stack.sh`。本机 Docker 静态二进制是 29.10.0，并以进程方式运行 dockerd。Docker 29 默认的 containerd snapshotter 会让 `docker save` 的 tarball 缺架构层，`k3s ctr images import` 失败；在 `/etc/docker/daemon.json` 里改为 `"storage-driver": "overlay2"` 且 `"features": {"containerd-snapshotter": false}` 后，官方导入（含 `jaegertracing/all-in-one:1.73.0`）成功。全新集群上官方脚本先执行 `write_secrets` 再 `apply_stack`，会因命名空间 `jasagent` 还不存在而失败；本轮没有改官方脚本的这一顺序，先执行了 `kubectl apply -f deploy/k8s/namespace.yaml`，再重跑官方 `up-stack.sh`，清单（含 `observability.yaml`）随后应用成功。

## 环境地址

| 服务 | 实测地址 |
| --- | --- |
| 本机 Ingress-nginx NodePort | Service `ingress-nginx/ingress-nginx-controller` 为 NodePort HTTP 30080 / HTTPS 30443，ClusterIP `10.43.59.187`，控制器 Pod `ingress-nginx-controller-788bbd94df-k6gkc`，Pod IP `10.42.0.21`。Ingress 对象 `jasagent/jasagent-gateway` 的后端仍只有 `jasagent-gateway:18721`。本轮从宿主机访问 `127.0.0.1:30080` 和 `172.30.0.2:30080` 未能连通：k3s kube-proxy 的 `iptables-restore` 报 `Extension statistic revision 0 not supported, missing kernel module?`，本机内核 `6.12.113+` 没有 `xt_statistic` 模块，`KUBE-NODEPORTS` 链因此是空的。官方探测里的入口检查已通过。第 9 条经网关的验收改走网关 Pod IP（见下） |
| 公网 Funnel | 本轮不验收。没有执行 `tailscale up` 或 `tailscale login` |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721`，ClusterIP `10.43.74.18`。两个就绪副本的 Pod IP 是 `10.42.0.22` 和 `10.42.0.23` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722`，ClusterIP `10.43.169.63`，Pod IP `10.42.0.14` |
| Jaeger query（仅 ClusterIP） | `http://jasagent-jaeger.jasagent.svc.cluster.local:16686`，ClusterIP `10.43.232.42`，Pod `jasagent-jaeger-bcfd89f54-72ngn`，Pod IP `10.42.0.15`。经网关路径为 `/admin/jaeger`。OTLP gRPC 端口 4317 也在同一 ClusterIP 上 |
| otel-collector OTLP HTTP traces | `http://jasagent-otel-collector.jasagent.svc.cluster.local:4318/v1/traces`，ClusterIP `10.43.121.142`，Pod IP `10.42.0.16` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，ClusterIP `10.43.85.164`，Pod IP `10.42.0.9` |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.154.98`，Pod IP `10.42.0.7` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.15.2`，Pod IP `10.42.0.19` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.168.34`，Pod `jasagent-mysql-6dc59b87c7-n9xq2`，Pod IP `10.42.0.8`。本轮是环境重建后的新数据目录 |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.64.78`，Pod IP `10.42.0.5` |
| 发现到的 agent | `jasagent-agent-0` `10.42.0.10:8080`，`jasagent-agent-1` `10.42.0.11:8080`，Service 为 ClusterIP None |

## 本轮清单和脚本

- `deploy/k8s/observability.yaml`：采集器保留原 metrics 管道，增加 traces 管道，OTLP gRPC 报到 `jasagent-jaeger.jasagent.svc.cluster.local:4317`。新增内存存储的 Jaeger Deployment / Service，镜像 `jaegertracing/all-in-one:1.73.0`，`SPAN_STORAGE_TYPE=memory`，`--query.base-path=/admin/jaeger`，Service 为 ClusterIP（16686、4317），使用 gVisor
- `deploy/k8s/agent.yaml`：增加 `JASAGENT_OTEL_ENDPOINT=http://jasagent-otel-collector.jasagent.svc.cluster.local:4318/v1/traces`
- `deploy/k8s/gateway.yaml`：增加 `JASAGENT_GATEWAY_JAEGER_ORIGIN=http://jasagent-jaeger.jasagent.svc.cluster.local:16686`（只含根地址，不含路径）
- `deploy/k8s/networkpolicy.yaml`：网关出站增加 Jaeger 16686；采集器增加出站到 Jaeger 4317/4318。这些规则不当作通过条件
- `scripts/up-stack.sh`：`JASAGENT_LATE_WAITS` 和重启目标补上 `deployment/jasagent-jaeger`。`observability.yaml` 本来就在后期清单里
- `scripts/k8s/probe-egress.sh`：在原有 018–024 检查之后增加 `jaeger-clusterip-not-nodeport` 和 `jaeger-not-on-ingress`，没有删除或跳过既有项

没有改 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 的产品代码。凭证不进 Git。Ingress 清单未改。

## 验收命令

```bash
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
bash scripts/k8s/prepare-cluster.sh
bash scripts/up-stack.sh
bash scripts/k8s/probe-egress.sh
```

没有执行 `tailscale up` 或 `tailscale login`。没有跳过 `ticket-issued` 或 `external-with-ticket`。验 Jaeger 界面时，先走一条会打点的请求，再用 admin 经网关打开 `/admin/jaeger`，确认能看到根 span 为 `jasagent.execution` 的链路，不用内存里的执行详情 traceId 去对。

## 集成测试结果

### 官方探测

通过。官方 `bash scripts/up-stack.sh` 在清单应用后于 20:25:13（Asia/Shanghai）自动跑探测，结果是 `PROBE FAILED`，失败项只有 `ticket-issued` 和随后的 `external-with-ticket`，其余项（含新增的 `jaeger-clusterip-not-nodeport`、`jaeger-not-on-ingress`，以及 `ingress-only-gateway`、`gateway-multi-replica-web-origin`，replicas=2 ready=2）当时已经通过。原因与 023 / 024 相同：官方探测仍按文件会话准备用户 `stack` / 会话 `s1`，023 发证只认集群 MySQL 里的会话和策略。本轮没有改探测脚本，也没有跳过这两项。在同一库 `jasagent` 写入探测用登录会话和 `net.egress` 允许策略后，于 20:27:19（Asia/Shanghai）重跑官方 `bash scripts/k8s/probe-egress.sh`，脚本退出码 0，打印 `PROBE PASSED`。结果包括：`PASS pods-ready`、`PASS runtime-class`、`PASS gvisor-discovered-agents`、`PASS gvisor-gateway`、`PASS gvisor-agentpass`、`PASS gvisor-sidecar`、`PASS gvisor-nacos`、网关与 agentpass 身份、发现到的 agent 均可访问，以及 018 的出站约定：`PASS agent-direct-external-blocked`、`PASS llm-deepseek-no-ticket`、`PASS llm-xai-no-ticket`、`PASS external-no-ticket-denied`、`PASS ticket-issued`、`PASS external-with-ticket`。019 / 020 / 024 入口和副本检查也仍在：`PASS ingress-nginx-runtime`、`PASS ingress-only-gateway`（`jasagent-gateway:18721`）、`PASS forwarded-trusted-ingress-only`（`trusted=10.42.0.21/32`）、`PASS gateway-multi-replica-web-origin`（`replicas=2 ready=2 origin=http://jasagent-web.jasagent.svc.cluster.local:18722`）。025 新增项为 `PASS jaeger-clusterip-not-nodeport`（`type=ClusterIP`，没有 nodePort）和 `PASS jaeger-not-on-ingress`（后端仍是 `jasagent-gateway:18721`）。探测脚本相对本 PR 没有再改，018–024 的检查范围没有缩小，也没有跳过既有项。

### 第 9 条：admin 经网关查看导出链路

通过。20:36:16（Asia/Shanghai）用 admin 登录网关后，未登录访问 `/admin/jaeger` 返回 302，Location 为 `/admin/login`；已登录 admin 在两个网关副本（`10.42.0.22:18721` 和 `10.42.0.23:18721`）上打开 `/admin/jaeger` 均为 200，正文是 Jaeger 界面。随后提交一条会打点的 `end-only` 执行（`executionId=ae759848-520a-41ca-b3ec-071b1765551a`，状态 `SUCCEEDED`）。经网关查询 `/admin/jaeger/api/traces?service=unknown_service:java`，看到根 span `jasagent.execution`（无父引用），同一条链路里还有子 span `workflow.node`。导出后的 traceID 是 `828c8786fff91d26db21a918cd9aa114`。执行详情里的内存 traceId 是 `05acd4c09e784ad7a059a45b193216c5`，二者不同，验收时没有用内存编号去对。Jaeger 里的服务名是 OpenTelemetry SDK 的缺省 `unknown_service:java`，跨度上的 `otel.scope.name` 是 `dev.jasagent`。规格第 9 条要看的是根 span 名称，该项通过。本轮 MVP 下 admin 能看到这条 Jaeger 里的链路，没有另做按租户隔离。因为宿主机 NodePort 30080 当时不通，这条验收走的是网关 Pod IP，仍然是经网关反代，没有直连 Jaeger ClusterIP 来代替 admin 入口。

### 第 10 条：官方探测整体通过且未缩小范围

通过。见上文「官方探测」。`PROBE PASSED` 包含 018–024 既有项和本轮新增的两项 Jaeger 检查。

### 第 11 条：Ingress 仍只到网关，Jaeger 只走 ClusterIP

通过。官方探测 `PASS ingress-only-gateway` 和 `PASS jaeger-not-on-ingress` 的后端都是 `jasagent-gateway:18721`。`PASS jaeger-clusterip-not-nodeport` 为 `type=ClusterIP` 且没有 nodePort。Ingress 对象只有这一条后端。web、Nacos、MySQL、Redis、agent、agentpass 都没有出现在 Ingress 上。Grafana 仍有既有 NodePort，Jaeger 没有沿用该先例。宿主机访问 Ingress NodePort 30080 本轮不通，原因写在环境地址里，不改变这条关于 Ingress 后端和 Jaeger Service 类型的结论。

### 第 12 条：重启丢数据和 NetworkPolicy

本条不是失败条件。进程或 Pod 重启丢掉内存 Jaeger 里已有链路不算失败，本轮没有把它当成缺陷。本集群 NetworkPolicy 因缺少 `CONFIG_IP_SET` 不会真正生效，不当作通过条件。

### 第 13 条：以 PR 提交清单、脚本和本记录

清单和官方脚本已在 [PR #18](https://github.com/xuhang113/JaSAgent/pull/18) 提交，本记录的环境地址和集成测试结果回写到同一 PR，没有直接推 master。验收环境是部署的云电脑，没有改 Hang 的 Mac。

Funnel 仍按领队口径搁置。024 整块和 019 仍不正式关闭。本 PR 等审查、本记录和测试通过后再合并，部署侧现在不合并。
