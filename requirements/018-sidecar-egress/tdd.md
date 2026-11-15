# 测试规范 018

**来源**: 同目录 `spec.md` 的测试要求

第 1 到第 9 条在 ATDD 阶段验收。第 10 到第 15 条在部署阶段由部署验收，本文件不列入。

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 查看离开集群的 HTTP 是否经 sidecar 发出 | 离开集群的 HTTP（大模型、外部工具、MCP 等）由 agent 进程经 sidecar 的 `/v1/forward` 发出，而不是由 agent 进程直接向外网拨号。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 查看 Nacos、MySQL、Redis、网关和本集群服务是否直连 | Nacos 注册与发现（包括 gRPC 9848）、MySQL、Redis、网关以及本集群内的其它服务，不经 sidecar。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 查看向 Nacos 注册的地址 | agent 向 Nacos 注册的地址仍是网关能访问的非回环地址，不是 `127.0.0.1`、`localhost` 或其它回环地址。回环只用于同一进程组或同一 Pod 内访问 sidecar。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 查看 `/v1/forward` 是否流式转发 | sidecar 对 `/v1/forward` 的请求体和响应体做流式转发，不再整包缓冲。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 查看大模型白名单和票据核销 | 现有的大模型白名单和票据核销规则仍然有效。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 查看本机启动脚本如何拉起 sidecar | 本机启动脚本拉起 sidecar；本机两台 agent 可以共用一个回环 sidecar，出站路径与集群约定一致。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 查看实现改动覆盖的模块 | 实现改动覆盖 `jasagent-sidecar`，以及把出站挂到 sidecar 所必需的 `jasagent-app`、`jasagent-llm` 和脚本。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 检查实现和测试是否改了清单 | 实现和测试提交不修改 `deploy/k8s` 下的清单文件。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |
| T-009 | 测试要求 9 | 查看本轮注释、自动测试、可执行测试脚本和密文 | 本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、票据和密钥不出现在仓库、说明或日志里。 | `tests/T-009` | `results/T-009.txt` | `logs/T-009.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果
