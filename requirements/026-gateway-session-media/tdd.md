# 测试规范 026

**来源**: 同目录 `spec.md` 的测试要求

第 1 到第 8 条在仓库内判定，于 ATDD 阶段验收，不依赖集群里的附件卷或 Funnel。第 9 到第 12 条（删或重建网关 Pod 后附件仍可取回且不新建库、官方探测仍须 `PROBE PASSED`、Ingress 仍只到网关且不另暴露附件服务、不恢复 Funnel 且 `deploy.md` 写有环境地址和结果）在部署阶段由部署验收，本文件不列入。

本目录用例不调用 `scripts/test-026-gateway-session-media.sh`：该脚本把对比范围写死为 `jasagent-gateway/`、`jasagent-web/`、`jasagent-persist/`、本轮脚本和变更记录，ATDD 文件会被判成超范围。模块测试由 `tests/persist-tests.sh`、`tests/gateway-tests.sh`、`tests/web-tests.sh` 独立复跑，不改产品代码，也不改官方合同脚本。实现侧本地脚本通过不能代替本目录正式 ATDD。

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 查看未登录的上传和下载 | 未登录访问附件的上传和下载，都返回 401，且 `errorCode` 为 `AUTH`。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 查看租户隔离和并发越权 | 租户隔离要有并发覆盖：已登录的非所有者按 020 已有约定被拒绝，且不能靠猜测附件 ID 读到内容；租户 A 不能读、改、删租户 B 的附件。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 查看类型、大小和超限是否落库 | 类型和大小按本规格写明的允许名单和上限执行。服务端以探测到的实际内容类型为准，不能只看扩展名或客户端 `Content-Type`。不在名单中的类型（含 SVG、HTML、XML 和宽泛的 `video/*`）被拒绝；超过对应类型大小上限的请求被拒绝；超限时元数据和内容都不落库。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 查看实际上传、取回和 IM 壳展示 | 已登录用户经网关、用现有登录会话 Cookie，在自己的会话里实际上传一份允许的图片、一份允许的语音和一份允许的视频，随后能取回与上传完全相同的字节，并在 IM 壳中展示图片或播放语音、视频。不能只查存储类名或路径存在。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 查看跨副本取回同一份字节 | 从一个就绪网关副本上传后，必须从「不是接收上传的那一个就绪副本」取回同一份字节。ATDD 用共享的已有 MySQL（或同等可被双进程同时用的存储）验证，不依赖集群附件卷。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 查看下载 Cookie、直链和响应头 | 下载和播放仍携带登录 Cookie，令牌不出现在查询参数里，不提供公开直链。下载响应带 `X-Content-Type-Options: nosniff`，且不改写 `JASAGENT_SESSION`。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 查看前缀绕过和 admin 旁路 | `/admin/cluster` 和 `/admin/jaeger` 不能作为跨租户取附件的旁路。附件路径规范化后不能绕过前缀，也不能落到默认的 agent 转发。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 检查改动范围、注释、自动测试、脚本和密文 | 实现只改 `jasagent-gateway`、`jasagent-web` 及必要的 persist/app 接线，不修改 `deploy/k8s`，不改 sidecar、agentpass 和 otel 的产品约定，不把附件转给 sidecar/agent。本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、凭证和密钥不出现在仓库、说明、日志或 span 里。019 的 Cookie、受信任转发头和相对跳转约定，以及 025 的 `/admin/jaeger` 拦截约定，保持不变。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果
