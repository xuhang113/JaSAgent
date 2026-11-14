# 需求 016 云环境 k8s 与一键部署评审记录

- 日期：2026-11-14（Asia/Shanghai）
- 块：云环境 k8s 与一键部署（`scripts` / `deploy/k8s`）
- 结论：已确认。领队、实现、测试、部署均写「通过」，Hang 确认「016开工」。确认记录如下。本文件和 `spec.md` 在确认之后写成。

## 候选

调研在群里交出的候选 **016-cloud-k8s-up-stack**：015 网关已经完成，本轮不改 sidecar。对照当时的 `master`，`scripts/up-stack.sh` 仍只打包 agent、网关、agentpass，只 apply mysql、redis、networkpolicy、agentpass、sidecar、agent、gateway，并且绑死本机 Colima（`colima ssh`、lima、`scutil`、`/opt/homebrew`，密钥还读 `/Users/xuhang/Workspace/imagine-harness/.env`）。`web.yaml` 和 `observability.yaml` 已在清单里，但脚本不会构建或导入 `jasagent-web:0.1.0`，也不会 apply 这两份。

建议改动：实现补齐 `scripts/up-stack.sh` 及相关一键脚本，打包并拉起 jasagent-web 和观测组件，覆盖 015 的反代、可配置 JDBC、单副本；脚本要能在部署的云电脑上对 k3s + gVisor 一条命令走通，默认路径不得指向 Hang 的 Mac；口令、令牌、模型密钥仍只来自仓库外可配置路径或 Secret。实现不改 `deploy/k8s`、不改 sidecar 产品代码。清单若有缺口，等 ATDD 通过后由部署补。部署在云电脑搭好 k8s，用一键脚本部署，重跑 `scripts/k8s/probe-egress.sh`，整体 `PROBE PASSED` 后写 `deploy.md`。验收以云电脑为准。候选最初建议本轮先不纳入 `rag.yaml`，因为它自己标明是示例且不使用 gVisor，按现状 apply 会再次让官方探测在 `runtime-class` 失败；若视为正式清单，则应一并 apply，并由部署补上 gVisor，探测范围不缩小。

## 表态

- 领队：通过。云电脑继续用 k3s + gVisor，沿用现有清单和探测。最初同意 `rag.yaml` 本轮先不纳入一键 apply，也不改探测范围。后同意实现的意见并改口：`rag.yaml` 已在正式清单里，本轮纳入一键部署；清单里只是注释写了不使用 gVisor，没有看到不能跑 gVisor 的硬限制；ATDD 通过后由部署补上 `runtimeClassName=gvisor`。其余按候选：实现只补脚本，不改 `deploy/k8s`、不改 sidecar 产品代码。
- 实现：通过。实现范围只补 `scripts/up-stack.sh` 及相关一键脚本，不改 `deploy/k8s`，不改 sidecar 产品代码；云电脑继续用 k3s + gVisor。不赞成只为了避开探测失败就本轮不纳入 `rag.yaml`：它已在 `deploy/k8s` 里，应和观测组件一样纳入一键部署并由部署补上 gVisor；只有确认无法使用 gVisor 时再暂缓。
- 测试：通过。验收按候选：脚本合同测试要能证明打包含 jasagent-web、apply 含 web 和观测组件、默认路径不指向 Hang 的 Mac；云上一键部署后，015 的 I-001 到 I-016 类检查仍过，官方 `probe-egress` 必须整体 `PROBE PASSED`，不要改探测脚本来凑通过。最初同意先不纳入 `rag.yaml`，前提是一键脚本和云上部署都不要 apply 它；如果要拉起 `jasagent-rag`，必须先补上 gVisor，不能靠缩小探测范围。领队改口后再次通过：`rag.yaml` 本轮纳入一键 apply，ATDD 通过后由部署补上 gVisor，官方探测必须整体通过，不改探测范围。
- 部署：通过。同意云环境继续用 k3s + runsc/gVisor。请明确两点：镜像在云电脑本机构建，脚本默认路径不得指向 Hang 的 Mac；k3s 与 gVisor 的准备也要脚本化（可与 `up-stack.sh` 分开，但必须能在云电脑上固定步骤走通），不能只靠手工搭集群。最初同意先不把 `rag.yaml` 纳入一键 apply。后改口：它若已在正式清单里，本轮应纳入一键 apply，并由部署补上 `runtimeClassName=gvisor`，不要为了让探测通过就缩小范围。领队改口后再次通过。
- Hang：确认「016开工」。确认对象是领队在群里向 Hang 锁定的口径，包括本轮不改 sidecar，实现只补一键脚本并拉起 jasagent-web、观测组件和 `rag.yaml`，默认路径不得指向 Hang 的 Mac，口令密钥仍只从仓库外读取，不改 `deploy/k8s`、不改 sidecar 产品代码，云电脑继续用 k3s + gVisor 且集群准备要脚本化，ATDD 通过后由部署给 `rag.yaml` 补上 gVisor，在云电脑上一键部署，官方 `probe-egress` 必须整体通过并写 `deploy.md`。

## 确认后的口径

1. 本轮不改 sidecar 产品代码。实现只补 `scripts/up-stack.sh` 及相关一键脚本，不改 `deploy/k8s`。
2. 一键脚本要打包、构建并导入 `jasagent-web`，apply `web.yaml`、`observability.yaml` 和 `rag.yaml` 并等待滚动，同时继续拉起现有的网关、agent、agentpass、sidecar、MySQL 和 Redis，覆盖 015 的反代、可配置 JDBC 和单副本。若 apply `rag.yaml`，还要构建并导入它所依赖的镜像。
3. 脚本默认路径不得指向 Hang 的 Mac。口令、令牌、模型密钥仍只从仓库外可配置路径或 Secret 读取，禁止写进仓库和日志，JDBC 地址不含 `password=`。镜像在运行脚本的云电脑本机构建。
4. 云电脑继续用 k3s + gVisor。k3s 与 gVisor 的准备也要脚本化，可与 `up-stack.sh` 分开，但必须能在云电脑上按固定步骤走通，不能只靠手工搭集群。
5. 实现和测试阶段不改 `deploy/k8s`。等本轮 ATDD 全部通过后，由部署给 `rag.yaml` 补上 `runtimeClassName=gvisor`，并补清单缺口；然后在云电脑上用一键脚本部署，重跑官方 `scripts/k8s/probe-egress.sh`，整体必须 `PROBE PASSED`。不改探测脚本，不缩小探测范围。`deploy.md` 要有环境地址、一键命令和集成测试结果。验收以部署的云电脑为准，不再依赖 Hang 的 Mac。
6. 本轮改到的脚本和清单必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。变更记录不能代替本轮 ATDD。
