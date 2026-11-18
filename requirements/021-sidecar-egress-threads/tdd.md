# 测试规范 021

**来源**: 同目录 `spec.md` 的测试要求

第 1 到第 9 条在 ATDD 阶段验收。第 10 到第 12 条（官方探测、生产线程上限、`deploy.md`）在部署阶段由部署验收，本文件不列入。

本目录用例不调用 `scripts/test-021-sidecar-egress-threads.sh`：该脚本把对比范围写死为 sidecar、本轮脚本和变更记录，ATDD 文件会被判成超范围。

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 查看等待和抄写是否占用 Tomcat 平台工作线程 | `/v1/forward` 在等待 agentpass、上游或抄写流式响应期间，不占用 Tomcat 平台工作线程（操作系统上的平台线程，不是虚拟线程或请求槽位）。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 查看压低平台线程后的并发长流和首块 | 把 sidecar 的 Tomcat 平台工作线程故意压低后，仍能同时扛住超过该上限的长流式 `/v1/forward`。这些流要持续出块，首块须在上游结束前到达，证明没有整包缓冲。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 查看长流期间的健康检查和新的转发 | 在第 2 条的并发长流式进行期间，`/health` 和新的 `/v1/forward` 仍要在超时内可用。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 查看压低是否只调了 `server.tomcat.threads.max` | 第 2 条的压低不得只调 `server.tomcat.threads.max`。启用虚拟线程时，该值往往是请求槽位而不是操作系统平台线程，只调它可能反而限制并发，测不出「等待和抄写时不占平台线程」。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 查看 `/v1/forward` 约定和是否整包缓冲 | `/v1/forward` 的 HTTP 约定不变，且不再整包缓冲。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 复跑 018 第 1 到第 9 条的行为约定 | 018 规格第 1 到 9 条的行为约定仍然成立，本轮复跑须通过：离开集群的 HTTP 仍由 agent 经 `/v1/forward` 发出；Nacos（含 gRPC 9848）、MySQL、Redis、网关和本集群服务不经 sidecar；向 Nacos 注册的仍是非回环地址；`/v1/forward` 对请求体和响应体做流式转发；大模型白名单和票据核销规则仍然有效；本机启动脚本拉起 sidecar，两台 agent 可共用一个回环 sidecar。本轮不要求再改 `jasagent-app` / `jasagent-llm`。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 查看实现改动覆盖的模块 | 实现改动只覆盖 `jasagent-sidecar` 以及对应测试和脚本，不改 `jasagent-app` / `jasagent-llm`，不改运行时执行模型。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
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
