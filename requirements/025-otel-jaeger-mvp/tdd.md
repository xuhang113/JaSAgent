# 测试规范 025

**来源**: 同目录 `spec.md` 的测试要求

第 1 到第 8 条在仓库内判定，于 ATDD 阶段验收，不依赖集群里的 Jaeger。第 9 到第 13 条（Jaeger 界面能看到根 span 为 `jasagent.execution` 的链路、官方探测仍须 `PROBE PASSED`、Ingress 仍只到网关且 Jaeger 只走 ClusterIP、重启丢链路和 NetworkPolicy 不当作通过条件、部署 PR 与 `deploy.md`）在部署阶段由部署验收，本文件不列入。

本目录用例不调用 `scripts/test-025-otel-jaeger-mvp.sh`：该脚本把对比范围写死为 `jasagent-app/`、`jasagent-gateway/`、`jasagent-web/`、本轮脚本和变更记录，ATDD 文件会被判成超范围。模块测试由 `tests/app-tests.sh`、`tests/gateway-tests.sh`、`tests/web-tests.sh` 独立复跑，不改产品代码，也不改官方合同脚本。导出的 OTLP traceId 与内存执行详情不是同一条，本目录不以内存 traceId 去对接收端里的链路。

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 查看未配置端点时是否外发 | 未配置 `jasagent.otel.endpoint` 时不启用 OTLP 导出，现有行为不变。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 查看配置端点后是否真正导出根 span | 配置了非空 `jasagent.otel.endpoint` 之后，走一条会打点的执行（不走 Funnel）。测试用 OTLP 接收端能收到根 span `jasagent.execution`，不只查导出器类名。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 查看 `/admin/jaeger` 登录与 admin 判断 | `/admin/jaeger` 及其所有子路径与 `/admin/cluster` 同一套判断：未登录的页面请求转到 `/admin/login`；已登录的非 admin 回 403，且不能落到控制台单页。前缀不能被绕过。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 查看 Jaeger 上游是否可被请求改写 | 网关只反代到配置里的 Jaeger 上游，地址不能被请求改写。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 查看会话 Cookie 与单进程状态 | 反代不把登录会话 Cookie 转给 Jaeger，也不接受 Jaeger 的 `Set-Cookie` 覆盖网关会话 Cookie。该判断和反代不依赖单进程状态。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 查看 Span 是否带出口令或鉴权材料 | Span 里不得带密码、Token、Authorization 或 Cookie。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 查看控制台 Jaeger 入口 | 控制台只给 admin 普通链接，不是 Vue 路由，也不把 `/admin/jaeger` 加入 ConsolePages。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 检查改动范围、注释、自动测试、脚本和密文 | 实现只改 `jasagent-app`、`jasagent-gateway`、`jasagent-web` 及必要接线，不修改 `deploy/k8s`，不改 sidecar 和 agentpass 的产品约定。本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、凭证和密钥不出现在仓库、说明或日志里。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果
