# 测试规范 016

**来源**: 同目录 `spec.md` 的测试要求

第 1 到第 12 条在 ATDD 阶段验收。第 13 到第 18 条在部署阶段由部署验收，本文件不列入。

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 查看一键脚本的打包模块 | `scripts/up-stack.sh` 或其调用的打包步骤把 `jasagent-web` 列入打包模块。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 在合同夹具里跑一键路径并查看镜像构建导入 | 一键脚本会构建并导入镜像 `jasagent-web:0.1.0`。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 查看一键脚本的 apply 列表和滚动等待 | 一键脚本的 apply 列表包含 `deploy/k8s/web.yaml`、`deploy/k8s/observability.yaml` 和 `deploy/k8s/rag.yaml`，并等待这些工作负载滚动完成。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 在 apply `rag.yaml` 时查看它所依赖的镜像 | 若脚本 apply `rag.yaml`，它还会构建并导入该清单所依赖的镜像。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 查看一键脚本是否仍部署原有组件 | 一键脚本仍会部署网关、agent、agentpass、sidecar、MySQL 和 Redis，而不是只部署本轮新增的组件。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 检查脚本的默认路径 | 脚本的默认路径不指向 Hang 的 Mac 绝对路径，包括不把 `/Users/xuhang/...`、`/opt/homebrew`、`colima` 或 lima 专用地址当成云电脑上的默认必经路径。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 在密钥文件缺失时运行密钥读取 | 口令、令牌和模型密钥只从仓库外可配置路径或 Secret 读取；必要文件缺失时脚本以非 0 退出，说明和日志里不出现这些密文。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 查看脚本写出的 JDBC 地址 | 脚本若写出 JDBC 地址，地址里不含 `password=`。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |
| T-009 | 测试要求 9 | 查看 k3s 与 gVisor 的准备脚本和固定步骤 | k3s 与 gVisor 的准备有脚本，可与 `up-stack.sh` 分开；按固定步骤能在云电脑上走通，不把手工搭集群当成唯一路径。 | `tests/T-009` | `results/T-009.txt` | `logs/T-009.log` |
| T-010 | 测试要求 10 | 查看镜像是在运行脚本的机器上构建还是从别处拷贝 | 镜像在运行脚本的那台机器上构建，不把从 Hang 的 Mac 拷贝镜像当成必经步骤。 | `tests/T-010` | `results/T-010.txt` | `logs/T-010.log` |
| T-011 | 测试要求 11 | 检查实现和测试是否改了清单或 sidecar 产品代码 | 实现和测试提交不修改 `deploy/k8s` 下的清单文件，也不修改 sidecar 产品代码。 | `tests/T-011` | `results/T-011.txt` | `logs/T-011.log` |
| T-012 | 测试要求 12 | 查看本轮改到的脚本注释、自动测试和可执行测试脚本 | 本轮改到的脚本有注释，有自动测试，并且有可执行的测试脚本。 | `tests/T-012` | `results/T-012.txt` | `logs/T-012.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果
