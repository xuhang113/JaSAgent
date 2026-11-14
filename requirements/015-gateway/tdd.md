# 测试规范 015

**来源**: 同目录 `spec.md` 的测试要求

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 未设置主机和端口环境变量时查看网关默认监听配置 | 未设置 `JASAGENT_HOST` 和 `JASAGENT_PORT` 时，网关监听 `127.0.0.1:18721`。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 未登录访问健康检查 | 未登录访问 `GET /health` 返回 200，正文含 `status` 为 `up`。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 用正确登录名和口令登录 | `POST /login` 使用正确登录名和口令时返回 200，并设置名为 `JASAGENT_SESSION` 的 Cookie。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 检查这次登录的响应、响应头和日志 | 这次登录响应正文、日志和响应头都不出现这次提交的口令原文。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 未登录访问会话列表接口 | 未登录访问 `GET /api/sessions` 返回 401，正文 `errorCode` 为 `AUTH`。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 已登录并配置内部令牌后转发一次可转发请求 | 已登录且已配置内部令牌时，转发到 agent 的请求带有 `X-Jasagent-Token`、`X-Jasagent-Tenant`、`X-Jasagent-User`、`X-Jasagent-Session`。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 查看这次转发请求的浏览器头 | 这次转发请求没有 `Cookie` 头，也没有 `Authorization` 头。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 查看这次转发请求的会话头 | `X-Jasagent-Session` 的值是路径里的会话编号或登录会话编号，不是 Cookie 里的登录令牌原文。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |
| T-009 | 测试要求 9 | 同一租户同一登录名连续两次转发 | 同一租户同一登录名连续两次可转发请求，落到同一台仍存活的 agent。 | `tests/T-009` | `results/T-009.txt` | `logs/T-009.log` |
| T-010 | 测试要求 10 | 断开目标 agent 后再发一次请求 | 目标 agent 的长连接断开后，下一次请求改到另一台仍存活的 agent，并写回粘性库。 | `tests/T-010` | `results/T-010.txt` | `logs/T-010.log` |
| T-011 | 测试要求 11 | 两台 agent 都断开后再发一次请求 | 两台 agent 都断开时，这次请求失败，粘性库里该用户的行仍在。 | `tests/T-011` | `results/T-011.txt` | `logs/T-011.log` |
| T-012 | 测试要求 12 | 未配置内部令牌时转发已登录请求 | 未配置 `JASAGENT_INTERNAL_TOKEN` 时，已登录的可转发请求不会发到 agent，响应正文不含令牌原文。 | `tests/T-012` | `results/T-012.txt` | `logs/T-012.log` |
| T-013 | 测试要求 13 | 检查网关源码树和打包产物是否还含控制台构建产物 | jasagent-gateway 的源码和打包产物里不再包含作为控制台来源的 `gateway-admin` 构建产物。 | `tests/T-013` | `results/T-013.txt` | `logs/T-013.log` |
| T-014 | 测试要求 14 | 检查控制台页面源码所在模块 | 控制台页面源码只出现在 `jasagent-web`，不出现在 `jasagent-gateway` 的源码树里。 | `tests/T-014` | `results/T-014.txt` | `logs/T-014.log` |
| T-015 | 测试要求 15 | 已登录访问控制台首页 | 已登录访问 `GET /admin/` 时，网关把请求反向代理到配置的 web 上游，响应正文来自这个上游。 | `tests/T-015` | `results/T-015.txt` | `logs/T-015.log` |
| T-016 | 测试要求 16 | 关掉 web 上游后再访问控制台首页 | 把 web 上游关掉后，`GET /admin/` 返回错误，且不出现已删除的内嵌控制台页面。 | `tests/T-016` | `results/T-016.txt` | `logs/T-016.log` |
| T-017 | 测试要求 17 | 未登录访问登录页 | 未登录访问 `GET /admin/login` 可以打开登录页，不要求已有会话。 | `tests/T-017` | `results/T-017.txt` | `logs/T-017.log` |
| T-018 | 测试要求 18 | 未登录访问登录页所需静态资源 | 未登录访问登录页所需的 `GET /admin/assets/**` 静态资源可以成功。 | `tests/T-018` | `results/T-018.txt` | `logs/T-018.log` |
| T-019 | 测试要求 19 | 未登录访问控制台首页 | 未登录访问 `GET /admin/` 时被重定向到 `/admin/login`，或返回 401，不返回控制台业务正文。 | `tests/T-019` | `results/T-019.txt` | `logs/T-019.log` |
| T-020 | 测试要求 20 | 未登录访问当前用户接口 | 未登录访问 `GET /api/me` 返回 401，`errorCode` 为 `AUTH`。 | `tests/T-020` | `results/T-020.txt` | `logs/T-020.log` |
| T-021 | 测试要求 21 | 未登录访问集群接口 | 未登录访问 `GET /api/cluster` 返回 401，`errorCode` 为 `AUTH`。 | `tests/T-021` | `results/T-021.txt` | `logs/T-021.log` |
| T-022 | 测试要求 22 | 把粘性库 JDBC 地址改到非默认地址后读写 | 通过配置把粘性库 JDBC 地址改到非默认地址后，网关能在这个地址上读写 `jasagent_gateway_affinity`。 | `tests/T-022` | `results/T-022.txt` | `logs/T-022.log` |
| T-023 | 测试要求 23 | 在 JDBC 地址里放入 password= | JDBC 地址包含 `password=` 时，打开粘性库失败，失败说明里没有这个口令原文。 | `tests/T-023` | `results/T-023.txt` | `logs/T-023.log` |
| T-024 | 测试要求 24 | 在口令文件缺失时打开粘性库 | 粘性库口令只从仓库外的口令文件读取；文件缺失时网关或粘性库失败，说明和日志里没有口令原文。 | `tests/T-024` | `results/T-024.txt` | `logs/T-024.log` |
| T-025 | 测试要求 25 | 查看默认端口下的 start.sh 启动方式 | `scripts/start.sh` 在默认端口 18721 拉起的是网关进程，就绪判断不再依赖日志里的 `Started JasagentApplication`。 | `tests/T-025` | `results/T-025.txt` | `logs/T-025.log` |
| T-026 | 测试要求 26 | 按本机启动方式核对网关和 agent 端口 | 按本机启动方式拉起后，18721 是网关，18731 和 18732 是 agent，`jasagent-app` 不再默认监听 18721。 | `tests/T-026` | `results/T-026.txt` | `logs/T-026.log` |
| T-027 | 测试要求 27 | 查看规格、配置或源码注释中的单副本说明 | 规格、配置或源码注释写明：登录会话和存活探测都在网关进程内，本轮网关按单副本验收。 | `tests/T-027` | `results/T-027.txt` | `logs/T-027.log` |
| T-028 | 测试要求 28 | 检查本轮改到的类、常量和方法注释 | 本轮改到的模块里，新增和改动的类、常量、方法都有注释。 | `tests/T-028` | `results/T-028.txt` | `logs/T-028.log` |
| T-029 | 测试要求 29 | 查看本轮自动测试和可执行测试脚本 | 本轮改动有自动测试，并且有可执行的测试脚本。 | `tests/T-029` | `results/T-029.txt` | `logs/T-029.log` |
| T-030 | 测试要求 30 | 检查实现和测试是否改了 deploy/k8s | 实现和测试提交不修改 `deploy/k8s` 下的清单文件。开发规范里的 K8s 文件，等本轮 ATDD 全部通过后由部署补进清单并写 `deploy.md`。 | `tests/T-030` | `results/T-030.txt` | `logs/T-030.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果

