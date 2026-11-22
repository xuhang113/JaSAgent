# 部署记录 024-k8s-durable-ha

**时间**：2026-11-22（Asia/Shanghai）。本文件写规格第 7 到第 12 条在部署云电脑上的结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、Funnel/Tailscale 凭证和密钥原文不写入本文件。

ATDD 第 1 到第 6 条已在 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 的 [`33a1c4bb`](https://github.com/xuhang113/JaSAgent/commit/33a1c4bbca339d201c70bb7443954b16eee10b3b) 通过，产品实现提交是 [`c42fdda8`](https://github.com/xuhang113/JaSAgent/commit/c42fdda8d4f69accea112480443d0d569fbb377d)，结果在 `requirements/024-k8s-durable-ha/results/`。本 PR 从 master [`390c4c8`](https://github.com/xuhang113/JaSAgent/commit/390c4c82861726b2d58500c4f38b00647bcddf06) 另开，只含 `deploy/k8s`、官方脚本和本记录，不改 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 里的产品代码，也没有直接推 master。

2026-11-21 云电脑更新后，本机 k3s、Docker 和 `/var/lib/tailscale` 都不在了。原来的 `jasagent` 库随 emptyDir 和数据盘一起丢失。整机或数据盘清空不在本轮承诺里。验收按领队确认的路径：先在当前 MySQL（emptyDir）写入 020 会话和 023 通行证/审计，再迁到持久卷，然后删/重建 MySQL Pod，这些数据必须还在。不会把已经写好的库直接滚到空卷上，也不另建库。

集群实测结果（官方探测、删/重建 Pod 后的表检查、网关 2 个就绪副本、I-001 至 I-016）将在同一条 PR 回写，当前尚未跑完，不记为通过。

## 环境

- 节点：`grok-bot-vm-209170388`。2026-11-22 12:49（Asia/Shanghai）核对时，k3s 未运行，`/etc/rancher/k3s`、`/var/lib/rancher`、`/var/lib/tailscale`、`/var/run/docker.sock` 都不在；PID 1 是 tini，没有可用的 systemd。内核 `6.12.113+`
- 命名空间（恢复后）：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 本 PR 清单中网关 `spec.replicas` 为 2，`JASAGENT_GATEWAY_REPLICAS=2`。官方探测把原来的 `gateway-single-replica-web-origin` 改成 `gateway-multi-replica-web-origin`，要求至少 2 个就绪副本，web 上游仍是 `http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED` 仍只信任 Ingress 这一跳，禁止 `0.0.0.0/0` 或 `::/0`
- 公网路径仍应是 `*.ts.net` → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`。更新后 Tailscale 状态不在，本文件提交时还没有恢复 Funnel，也没有执行 `tailscale up` 或 `tailscale login`。若要重新登录，只在与 Hang 的私信里办理
- Ingress 后端仍只有 `jasagent-gateway:18721`。不得把 web、Nacos、MySQL、Redis、agent 或 agentpass 挂到公网入口
- MySQL 数据目录改为 PersistentVolumeClaim `jasagent-mysql-data`（StorageClass `local-path`，10Gi）。该卷能挺过 Pod 删除，挺不过整机或 `/var/lib/rancher` 被清空
- 官方 `scripts/k8s/prepare-cluster.sh` 在没有可用 systemd 时以进程方式拉起 k3s（`--write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik`），并预先写入 runsc 模板（`platform=systrap`，`network=host`）。有 systemd 的机器仍走原来的官方安装脚本

## 环境地址

集群尚未按本轮清单恢复完成，下表只记录当前已知入口约定，具体 ClusterIP、Pod IP 和镜像 ID 待实测后回写。

| 服务 | 当前说明 |
| --- | --- |
| 公网入口 | 历史主机名是 `https://jasagent-cloud.tail01f87a.ts.net`。本轮提交时 Funnel 状态不在，公网 `/health` 未复查 |
| 本机 Ingress-nginx | 恢复后应为 `http://127.0.0.1:30080`，只转到 `jasagent-gateway:18721` |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，数据目录使用 PVC `jasagent-mysql-data` |
| agentpass | 仍为 ClusterIP，不暴露到公网 |

## 本轮清单和脚本

- `deploy/k8s/mysql.yaml`：增加 PVC `jasagent-mysql-data`，Deployment 不再使用 emptyDir
- `deploy/k8s/gateway.yaml`：副本改为 2，环境变量同步为 2，注释改为会话和存活探测在已有 MySQL 库
- `scripts/up-stack.sh`：apply `mysql.yaml` 之前，若当前 MySQL 仍不是目标 PVC 且库 `jasagent` 已有表，先把 `/var/lib/mysql` 拷到持久卷，再切换 Deployment。也可单独执行 `bash scripts/up-stack.sh --migrate-mysql`
- `scripts/k8s/probe-egress.sh`：将单副本断言改为至少 2 个就绪副本，web 上游不变。018–023 的其余检查（含 `ticket-issued` / `external-with-ticket`、入口只到网关、转发头只信任 Ingress）没有删除或跳过
- `scripts/k8s/prepare-cluster.sh`：无 systemd 时走进程拉起，并预先写入 runsc 模板

没有改 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 的产品代码。凭证不进 Git。

## 验收命令

```bash
# 1. 用官方脚本在无 systemd 的云电脑上准备 k3s 和 gVisor
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
bash scripts/k8s/prepare-cluster.sh

# 2. 先按原 emptyDir 拉起 MySQL，写入 020 会话和 023 通行证/审计（不另建库）
# 3. 再执行官方迁移，切到持久卷；不要直接用空卷滚动掉已有库的 Pod
bash scripts/up-stack.sh --migrate-mysql

# 4. 删除或重建 MySQL Pod，核对自己刚写入的会话和证还在
# 5. 用 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 的产品提交构建网关镜像后执行官方一键脚本
#    网关至少 2 个就绪副本
bash scripts/up-stack.sh
bash scripts/k8s/probe-egress.sh
```

没有执行 `tailscale up` 或 `tailscale login`。没有跳过 `ticket-issued` 或 `external-with-ticket`。

## 集成测试结果

### 第 7 条：先迁现有库再切持久卷，删/重建 Pod 后数据还在

待测。提交本文件时集群尚未恢复，还没有可迁移的现有库。将按上面的命令先写入再迁移，再删/重建 Pod 核对，结果回写到本小节。

### 官方 `scripts/k8s/probe-egress.sh`

待测。须打印 `PROBE PASSED`。`gateway-multi-replica-web-origin` 须看到至少 2 个就绪副本，且 web 上游仍是集群内地址。018–023 的其余检查范围不得缩小。

### 015 风格 I-001 至 I-016

待测。通过后回写时间和摘要。

### 第 9 条：Funnel 与集群外登录

未复查。2026-11-21 更新后 `/var/lib/tailscale` 不在了，本轮提交时没有执行 `tailscale up` 或 `tailscale login`。集群外或手机登录、控制台 EventSource，以及 019 尚未正式关闭的 `/admin/cluster` 和未登录拦截，不能只在集群内代替。若要恢复 Funnel，只在与 Hang 的私信里办理。

### 第 10 条：无 systemd 时官方 prepare-cluster.sh 能拉起集群

待测。将用本 PR 中的 `scripts/k8s/prepare-cluster.sh` 在这台没有可用 systemd 的云电脑上执行，成功后再回写节点版本和 RuntimeClass 结果。

### 第 11 条：清单和脚本走 PR

本文件所在分支从 master `390c4c8` 另开，不直接推 master，也不改 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15)。Funnel 和 Tailscale 凭证不在 Git、日志或本文件里。

### 第 12 条：本文件

已写环境约定和将要执行的命令。环境地址和集成测试的实测结果待集群恢复后回写到本 PR，没有结果之前第 7、8、10、12 条不算完成。
