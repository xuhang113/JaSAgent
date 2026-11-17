# 测试规范 019

**来源**: 同目录 `spec.md` 的测试要求

第 1 到第 8 条在 ATDD 阶段验收。第 9 到第 14 条（含 Tailscale Funnel 主机名、Ingress-nginx、公网登录、官方探测和 `deploy.md`）在部署阶段由部署验收，本文件不列入。

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 查看网关 web 上游配置 | `jasagent.gateway.web-origin` 和 `JASAGENT_GATEWAY_WEB_ORIGIN` 仍指向本机或集群内的 `jasagent-web` 上游，没有被改成公网主机名或 `*.ts.net`。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 查看受信任 HTTPS 下的会话 Cookie | 当受信任的 Ingress/Funnel 这一跳声明请求是 HTTPS 时，签发和清除的 `JASAGENT_SESSION` 都带 Secure，并仍为 HttpOnly、path `/`、SameSite=Lax。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 查看明文 HTTP 直连时的会话 Cookie | 本机或测试里以明文 HTTP 直连网关、没有受信任转发头时，会话 Cookie 不得被强行加上 Secure。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 查看客户端自带转发头是否被信任 | 任意客户端自己带来的 `X-Forwarded-*` 不被信任，不能据此把 Cookie 设成 Secure，也不能据此改跳转或协议。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 查看站点根、登录页和登录后跳转 | 站点根、登录页和登录后的跳转保持相对路径，响应里的 `Location` 不得写死为 `127.0.0.1` 或 `http://` 绝对地址。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 检查实现和测试改动覆盖的模块 | 实现只改 `jasagent-gateway`。`jasagent-web` 本轮不被修改。实现和测试提交不修改 `deploy/k8s` 下的清单文件。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 查看网关副本配置 | 网关仍按单副本配置。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 查看本轮注释、自动测试、可执行测试脚本和密文 | 本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、Funnel/Tailscale 凭证和密钥不出现在仓库、说明或日志里。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果
