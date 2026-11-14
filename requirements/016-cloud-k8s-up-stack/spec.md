# 需求 016 云环境 k8s 与一键部署

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-14（Asia/Shanghai）领队、实现、测试、部署和 Hang 均已通过。

## 这次要改什么

015 网关已经完成。`deploy/k8s` 里已有 `web.yaml`、`observability.yaml` 和 `rag.yaml`，网关清单也已按 015 改成反代 `jasagent-web`、可配置 JDBC 和单副本。但 `scripts/up-stack.sh` 仍只打包 agent、网关、agentpass，只 apply mysql、redis、networkpolicy、agentpass、sidecar、agent、gateway，并且绑死 Hang 的 Mac 上的 Colima k3s：使用 `colima ssh`、lima 域名、`scutil`，Java 默认落到 `/opt/homebrew`，密钥还读 `/Users/xuhang/Workspace/imagine-harness/.env`。脚本不会构建或导入 `jasagent-web:0.1.0`，也不会 apply `web.yaml`、`observability.yaml` 和 `rag.yaml`。本轮要把发现的这些问题一并调整，并把 k8s 环境整理到部署的云电脑上，整套用脚本一键部署，不再依赖 Hang 的 Mac。sidecar 放到这一轮之后，本轮不改 sidecar 产品代码。

这次改成这几件事。

1. 补齐 `scripts/up-stack.sh` 及相关一键脚本。打包、构建并导入 `jasagent-web`，apply `web.yaml`、`observability.yaml` 和 `rag.yaml` 并等待滚动；同时继续拉起现有的网关、agent、agentpass、sidecar、MySQL 和 Redis，覆盖 015 的反代、可配置 JDBC 和单副本。若 apply `rag.yaml`，还要构建并导入它所依赖的镜像。
2. 脚本不再绑死 Hang 的 Mac 和 Colima。默认路径不得指向 Hang 的 Mac。在部署的云电脑上，对 k3s + gVisor 用固定脚本步骤走通，一条命令完成部署。
3. 镜像在运行脚本的云电脑本机构建。k3s 与 gVisor 的准备也要脚本化，可与 `up-stack.sh` 分开，但不能只靠手工搭集群。
4. 口令、令牌、模型密钥仍只来自仓库外可配置路径或 Secret，禁止写进仓库和日志；脚本若写出 JDBC 地址，地址里不能带 `password=`。
5. 实现和测试阶段不改 `deploy/k8s`，不改 sidecar 产品代码。等本轮 ATDD 全部通过后，由部署给 `rag.yaml` 补上 `runtimeClassName=gvisor`，并补清单缺口；然后在云电脑上用一键脚本部署，重跑官方 `probe-egress`，整体通过后写 `deploy.md`。

不改探测脚本，不缩小探测范围。不改 sidecar 产品行为。本机 `scripts/start.sh` 已在 015 拉起网关和 `jasagent-web`，本轮不以它为改动对象，除非一键脚本必须与它对齐时做必要修正。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| scripts | `up-stack.sh` 把部分组件部署进本机 Colima k3s，不打包、不拉起 jasagent-web 和观测组件，也不 apply `rag.yaml` | 补齐打包、镜像构建导入、apply 列表和云电脑可用路径；把 k3s 与 gVisor 的准备脚本化 |
| deploy/k8s | 已有 web、观测组件和 rag 等清单；`rag.yaml` 尚未标明 gVisor | 实现和测试阶段不动。ATDD 全部通过后，由部署给 `rag.yaml` 补上 gVisor，并补清单缺口，在云电脑上部署并写 `deploy.md` |
| sidecar 产品代码 | 现有 sidecar 行为 | 本轮不改 |

## 改动地方

**scripts**

补齐 `scripts/up-stack.sh` 以及完成一键部署所必需的相关脚本，包括集群准备脚本。集群准备脚本可以与 `up-stack.sh` 分开，但必须能在部署的云电脑上按文档或脚本给出的固定步骤走通 k3s 和 runsc/gVisor，不能把手工搭集群当成唯一路径。

打包步骤要把 `jasagent-web` 列入打包模块。脚本要在运行它的机器上构建并导入 `jasagent-web:0.1.0`。apply 列表必须包含 `deploy/k8s/web.yaml`、`deploy/k8s/observability.yaml` 和 `deploy/k8s/rag.yaml`，并等待这些工作负载滚动完成。若 apply `rag.yaml`，还要构建并导入该清单所依赖的镜像。现有的 mysql、redis、networkpolicy、agentpass、sidecar、agent、gateway 仍要一并部署，网关继续反代集群内的 `jasagent-web`，JDBC 仍指向可配置的集群地址且不含口令，网关仍按单副本。

脚本要能在部署的云电脑（Linux 上的 k3s + gVisor）走通，不能再把 Colima、`colima ssh`、lima 域名、`scutil`、`/opt/homebrew` 或 `/Users/xuhang/...` 当成默认必经路径。Java 21 从 `JAVA_HOME` 或该机器上已安装的 Java 21 解析，不得默认指向 Hang 的 Mac 路径。口令、令牌和模型密钥只从仓库外可配置文件或环境读取，默认路径不得写死到 Hang 的 Mac 绝对路径；文件缺失时以非 0 退出，说明和日志里不出现这些密文。镜像在运行脚本的云电脑本机构建并导入该机器上的 k3s，不把从 Hang 的 Mac 拷贝镜像当成必经步骤。

相关脚本和新增的函数、常量、用法说明都要有注释。找不到 Java 21、必要 jar、清单或密钥文件时以非 0 退出。

**deploy/k8s**

实现和测试提交不得修改这个目录。等本轮 ATDD 全部通过后，由部署给 `rag.yaml` 补上 `runtimeClassName=gvisor`，并补本轮一键部署所需的清单缺口（例如网络策略）。然后在部署的云电脑上用一键脚本部署，重跑官方 `scripts/k8s/probe-egress.sh`，整体必须 `PROBE PASSED`。`deploy.md` 写明环境地址、一键命令和集成测试结果。验收环境以这台云电脑为准，不再依赖 Hang 的 Mac。

**sidecar 产品代码**

本轮不改。

## 数据库

继续使用现有的 `jasagent` 库和 015 已有的粘性表约定。这次不改表结构。脚本若写入或使用 JDBC 地址，地址里不能带 `password=`，口令仍只从仓库外文件或 Secret 读取。

## 中间件

云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。本轮不新引进其它中间件，只把清单里已有的 web、grafana、otel-collector、prometheus 和 rag 纳入一键部署。官方探测脚本本身不改，探测范围不缩小。

## 开发规范

本轮改到的脚本必须同时满足：有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌和密钥不写进仓库、日志、异常或响应。开发规范里的 K8s 文件按这个时机补：实现和测试阶段不改 `deploy/k8s`；等本轮 ATDD 全部通过后，由部署给 `rag.yaml` 补上 gVisor 并补清单缺口，部署并交出带环境地址、一键命令和集成测试结果的 `deploy.md`。变更记录不能代替本轮 ATDD。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在脚本仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. `scripts/up-stack.sh` 或其调用的打包步骤把 `jasagent-web` 列入打包模块。
2. 一键脚本会构建并导入镜像 `jasagent-web:0.1.0`。
3. 一键脚本的 apply 列表包含 `deploy/k8s/web.yaml`、`deploy/k8s/observability.yaml` 和 `deploy/k8s/rag.yaml`，并等待这些工作负载滚动完成。
4. 若脚本 apply `rag.yaml`，它还会构建并导入该清单所依赖的镜像。
5. 一键脚本仍会部署网关、agent、agentpass、sidecar、MySQL 和 Redis，而不是只部署本轮新增的组件。
6. 脚本的默认路径不指向 Hang 的 Mac 绝对路径，包括不把 `/Users/xuhang/...`、`/opt/homebrew`、`colima` 或 lima 专用地址当成云电脑上的默认必经路径。
7. 口令、令牌和模型密钥只从仓库外可配置路径或 Secret 读取；必要文件缺失时脚本以非 0 退出，说明和日志里不出现这些密文。
8. 脚本若写出 JDBC 地址，地址里不含 `password=`。
9. k3s 与 gVisor 的准备有脚本，可与 `up-stack.sh` 分开；按固定步骤能在云电脑上走通，不把手工搭集群当成唯一路径。
10. 镜像在运行脚本的那台机器上构建，不把从 Hang 的 Mac 拷贝镜像当成必经步骤。
11. 实现和测试提交不修改 `deploy/k8s` 下的清单文件，也不修改 sidecar 产品代码。
12. 本轮改到的脚本有注释，有自动测试，并且有可执行的测试脚本。
13. ATDD 全部通过并由部署给 `rag.yaml` 补上 `runtimeClassName=gvisor` 后，在部署的云电脑上用一键脚本部署，`jasagent-web`、`jasagent-grafana`、`jasagent-otel-collector`、`jasagent-prometheus`、`jasagent-rag` 均就绪。
14. 上述组件以及网关、agent、agentpass、sidecar 的运行时为 gVisor；MySQL 和 Redis 按既有约定可以不是 gVisor。
15. 网关的 web 上游仍指向集群内 `http://jasagent-web.jasagent.svc.cluster.local:18722`。
16. 015 的 I-001 到 I-016 类检查在云电脑上仍然通过。
17. 官方 `scripts/k8s/probe-egress.sh` 整体结果为 `PROBE PASSED`。探测脚本未被修改，探测范围未被缩小。
18. `requirements/016-cloud-k8s-up-stack/deploy.md` 写有环境地址、一键命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

第 13 到第 18 条在部署阶段验收。第 1 到第 12 条在 ATDD 阶段验收。

## 还没定

没有还没定。`rag.yaml` 本轮纳入一键部署，不改探测范围；gVisor 标记等 ATDD 全部通过后由部署补进清单。云电脑继续用 k3s + gVisor。K8s 清单在本轮 ATDD 全部通过后由部署补全，实现和测试阶段不动。
