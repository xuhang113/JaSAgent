# 部署记录 025-otel-jaeger-mvp

**时间**：2026-11-24（Asia/Shanghai）。本文件写规格第 9 到第 13 条在部署云电脑上的结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、Funnel/Tailscale 凭证和密钥原文不写入本文件。

ATDD 第 1 到第 8 条已在 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 通过，头提交是 [`475bc7b`](https://github.com/xuhang113/JaSAgent/commit/475bc7b21e9e8420f4d40c6f0b4607f0c45364b0)，产品实现提交是 [`ba44725`](https://github.com/xuhang113/JaSAgent/commit/ba44725c05c61f6dcf6b7a8d0452595ed2fdc7c6)，结果在 `requirements/025-otel-jaeger-mvp/results/`。该 PR 已于 19:01:19（Asia/Shanghai）用 merge commit [`7b97aac`](https://github.com/xuhang113/JaSAgent/commit/7b97aac94803813a59f12df33dd5d506ebcfa981) 合入 master，没有 squash，也没有直接推 master。本分支从这次合并之后的 master 另开，只含 `deploy/k8s`、官方脚本和本记录，不改 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 里的产品代码，也没有直接推 master。

第 9 条 Funnel、以及正式关闭 024 整块和 019，不在本轮。验 Jaeger 界面时，以导出后的根 span `jasagent.execution` 为准，不用内存里的执行详情 traceId 去对。

## 环境

集群地址、副本和探测结果将在本 PR 于云电脑集群跑完官方脚本和集成测试后回写。下面先记下本轮清单约定，避免把尚未跑出的结果写进去。

- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关至少 2 个就绪副本，web 上游仍是 `http://jasagent-web.jasagent.svc.cluster.local:18722`
- Ingress 后端仍只有 `jasagent-gateway:18721`。Jaeger、web、Nacos、MySQL、Redis、agent、agentpass 都不挂到公网入口
- Jaeger 使用内存存储、只要 ClusterIP，不挂 NodePort。query base-path 为 `/admin/jaeger`
- 本轮不执行 `tailscale up` 或 `tailscale login`，也不验收 Funnel
- NetworkPolicy 按现有风格补了规则，但本集群因缺少 `CONFIG_IP_SET` 不会真正生效，不当作通过条件
- 业务镜像用已合入 master 的 [PR #17](https://github.com/xuhang113/JaSAgent/pull/17) 产品提交打包，清单和官方脚本用本 PR

## 环境地址

待本 PR 在云电脑集群跑完后回写 Service、Pod 和入口地址。计划中的集群内地址如下，实际 ClusterIP 以跑完后的值为准：

| 服务 | 地址 |
| --- | --- |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722` |
| Jaeger query（仅 ClusterIP） | `http://jasagent-jaeger.jasagent.svc.cluster.local:16686`，经网关路径为 `/admin/jaeger` |
| otel-collector OTLP HTTP traces | `http://jasagent-otel-collector.jasagent.svc.cluster.local:4318/v1/traces` |
| Ingress | 仍只转到 `jasagent-gateway:18721`。公网 Funnel 本轮不验收 |

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
# 若本机 k3s 已在且节点就绪，不必重跑准备脚本
bash scripts/k8s/prepare-cluster.sh
# 用已合入 master 的 025 产品提交构建镜像后，执行官方一键脚本
bash scripts/up-stack.sh
bash scripts/k8s/probe-egress.sh
```

没有执行 `tailscale up` 或 `tailscale login`。没有跳过 `ticket-issued` 或 `external-with-ticket`。验 Jaeger 界面时，先走一条会打点的请求，再用 admin 经网关打开 `/admin/jaeger`，确认能看到根 span 为 `jasagent.execution` 的链路，不用内存里的执行详情 traceId 去对。

## 集成测试结果

待本 PR 在云电脑集群跑完官方脚本和集成测试后回写。尚未填写第 9 到第 13 条的通过或失败结论。
