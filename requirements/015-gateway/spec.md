# 需求 015 网关

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-14（Asia/Shanghai）领队、实现、测试、部署和 Hang 均已通过。

## 这次要改什么

仓库里已经有独立的 `jasagent-gateway`。它默认监听 `127.0.0.1:18721`，把登录、会话、`/api/me`、`/api/cluster` 和健康检查留在网关，其余 `/api` 按租户加登录名粘到 `127.0.0.1:18731` 和 `127.0.0.1:18732`。转发给 agent 时使用四个约定头：`X-Jasagent-Token`、`X-Jasagent-Tenant`、`X-Jasagent-User`、`X-Jasagent-Session`。令牌只来自环境变量 `JASAGENT_INTERNAL_TOKEN`。粘性写在 MySQL 库 `jasagent` 的 `jasagent_gateway_affinity`。口令只从仓库外的口令文件读取。这些约定这次保持。

现在控制台静态产物打在 `jasagent-gateway` 的 `gateway-admin` 里，和界面只许放在 `jasagent-web` 的规则不一致。本机 `scripts/start.sh` 仍按单进程 `jasagent-app` 占用 18721，会和网关抢端口。粘性库 JDBC 地址写死为 `127.0.0.1:3306/jasagent`。`/admin/**` 在会话过滤器里全部匿名放行。存活探测和登录会话都在网关进程内，但验收还没写明必须单副本。

这次改成这几件事。

1. 控制台页面源码只放在 `jasagent-web`。网关把浏览器对控制台的请求反向代理到可配置的 `jasagent-web` 上游，不再内嵌或挂载一份前端构建产物。
2. 本机日常入口统一为网关加 agent。`scripts/start.sh` 在 18721 拉起网关；`jasagent-app` 不再默认占用 18721，约定 agent 端口仍是 18731 和 18732。
3. 粘性库 JDBC 地址和账号改为可配置，口令仍只从仓库外文件读取，地址里不能带口令。
4. 收紧匿名范围：未登录只放行健康检查、登录和退出接口、登录页和登录页所需静态资源。控制台业务页和 `/api` 业务接口必须有会话。
5. 登录会话和存活探测仍留在网关进程内。验收写明网关按单副本运行，不只针对存活探测。
6. 本轮改到的模块补齐注释、常量、类、方法、测试代码和可执行测试脚本。变更记录不能代替本轮 ATDD。

不改工作流图、PBKDF2 登录规则和本机建用户命令。不改 LangChain4j 路由、进程内通行证、sidecar 和 agentpass 服务。实现和测试阶段不改 `deploy/k8s`。等本轮 ATDD 全部通过后，由部署把反代、可配置 JDBC 和单副本补进清单，部署后交出带环境地址和集成测试结果的 `deploy.md`。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-gateway | 对外入口、登录会话、粘性转发、内嵌管理页 | 改为反代控制台，收紧匿名范围，JDBC 可配置，去掉内嵌前端 |
| jasagent-web | 控制台与页面源码 | 作为控制台的唯一前端，提供可被网关反代的上游 |
| jasagent-app | 本机单进程服务，默认也听 18721 | 默认端口不再占用 18721，作为 18731/18732 的 agent 进程 |
| scripts | `start.sh` 拉起 `jasagent-app`；沙箱脚本拉起网关 | `start.sh` 改为在 18721 拉起网关，并与 agent 启动路径对齐 |
| deploy/k8s | 现有网关与集群清单 | 实现和测试阶段不动。ATDD 全部通过后，由部署补进反代、可配置 JDBC 和单副本，并写 `deploy.md` |

## 改动地方

**jasagent-web**

页面源码只在这个模块维护，包括登录页和控制台业务页。本模块提供可被网关反向代理的上游，由本机启动脚本拉起。测试可以用本地桩服务代替这个上游。不把控制台源码再复制进 `jasagent-gateway`。

**jasagent-gateway**

删除或停止使用 `src/main/resources/gateway-admin` 这份内嵌构建产物。浏览器访问控制台路径时，网关把请求反向代理到配置项给出的上游地址，例如 `jasagent.gateway.web-origin`。上游地址必须可配置。上游不可用时返回错误，不回退到内嵌页面。

会话过滤器按下面规则划分路径。未登录可访问：`GET /health`、`GET /`、`GET /login`、`POST /login`、`POST /logout`、`GET /admin/login`，以及登录页所需的 `GET /admin/assets/**`。必须有会话：`GET /admin/` 及其余控制台业务路径、`GET /api/me`、`GET /api/cluster`，以及所有要转发到 agent 的 `/api/**`。未登录访问必须有会话的页面时，重定向到 `/admin/login`；未登录访问必须有会话的 `/api` 时，返回 401，`errorCode` 为 `AUTH`。`GET /` 仍转到 `/admin/`，`GET /login` 仍转到 `/admin/login`。`POST /login` 仍是登录接口。

粘性库连接改为读取配置中的 JDBC 地址和账号，默认值可以仍是 `127.0.0.1:3306/jasagent` 和 `root`，以便本机沿用现有库。口令仍只从仓库外文件读取，默认文件仍是用户主目录下的 `.config/urag-mysql-root.password`。JDBC 地址若包含 `password=` 则拒绝打开粘性库。异常、日志和 `toString()` 都不出现口令或令牌。配置了非回环主机或其它库名时，网关用这个地址读写 `jasagent_gateway_affinity`，不再写死只能连 `127.0.0.1`。

存活探测仍用进程内长连接状态。规格、配置或源码注释必须写明：登录会话和存活探测都在网关进程内，本轮网关按单副本验收。

四个内部头、按用户粘性、令牌环境变量和去 Cookie 转发的规则保持现有约定，不另起头名。

本轮改到的类、常量、方法都要有注释。

**jasagent-app**

默认监听地址和端口不再占用 18721。作为本机 agent 时使用约定端口 18731 或 18732。不在这个模块里重新提供登录页和控制台。

**scripts**

`scripts/start.sh` 在 `JASAGENT_PORT` 默认 18721 时拉起的是网关，等待网关进程就绪，而不是等待 `Started JasagentApplication`。本机启动还要拉起可被反代的 `jasagent-web` 上游，以及 18731、18732 的 agent。`scripts/sandbox/start-gateway.sh` 与这条日常入口对齐，不再和 `start.sh` 抢两套 18721 语义。找不到网关 jar、上游或 Java 21 时以非 0 退出，日志和说明里不出现口令或内部令牌。

## 数据库

继续使用本机或配置地址上的 `jasagent` 库和表 `jasagent_gateway_affinity`。主键仍是租户加用户，不把令牌或口令写入表。这次不改表结构，只把连接地址和账号改成可配置。

## 中间件

不加消息队列。网关继续使用 Spring Cloud Gateway MVC 做转发和反向代理。内部令牌仍只从环境变量读取。实现和测试阶段不改 Redis、sidecar 或 `deploy/k8s` 清单。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌和密钥不写进仓库、日志、异常或响应。开发规范里的 K8s 文件按这个时机补：实现和测试阶段不改 `deploy/k8s`；等本轮 ATDD 全部通过后，由部署把反代、可配置 JDBC 和单副本补进清单，部署并交出带环境地址和集成测试结果的 `deploy.md`。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。

1. 未设置 `JASAGENT_HOST` 和 `JASAGENT_PORT` 时，网关监听 `127.0.0.1:18721`。
2. 未登录访问 `GET /health` 返回 200，正文含 `status` 为 `up`。
3. `POST /login` 使用正确登录名和口令时返回 200，并设置名为 `JASAGENT_SESSION` 的 Cookie。
4. 这次登录响应正文、日志和响应头都不出现这次提交的口令原文。
5. 未登录访问 `GET /api/sessions` 返回 401，正文 `errorCode` 为 `AUTH`。
6. 已登录且已配置内部令牌时，转发到 agent 的请求带有 `X-Jasagent-Token`、`X-Jasagent-Tenant`、`X-Jasagent-User`、`X-Jasagent-Session`。
7. 这次转发请求没有 `Cookie` 头，也没有 `Authorization` 头。
8. `X-Jasagent-Session` 的值是路径里的会话编号或登录会话编号，不是 Cookie 里的登录令牌原文。
9. 同一租户同一登录名连续两次可转发请求，落到同一台仍存活的 agent。
10. 目标 agent 的长连接断开后，下一次请求改到另一台仍存活的 agent，并写回粘性库。
11. 两台 agent 都断开时，这次请求失败，粘性库里该用户的行仍在。
12. 未配置 `JASAGENT_INTERNAL_TOKEN` 时，已登录的可转发请求不会发到 agent，响应正文不含令牌原文。
13. `jasagent-gateway` 的源码和打包产物里不再包含作为控制台来源的 `gateway-admin` 构建产物。
14. 控制台页面源码只出现在 `jasagent-web`，不出现在 `jasagent-gateway` 的源码树里。
15. 已登录访问 `GET /admin/` 时，网关把请求反向代理到配置的 web 上游，响应正文来自这个上游。
16. 把 web 上游关掉后，`GET /admin/` 返回错误，且不出现已删除的内嵌控制台页面。
17. 未登录访问 `GET /admin/login` 可以打开登录页，不要求已有会话。
18. 未登录访问登录页所需的 `GET /admin/assets/**` 静态资源可以成功。
19. 未登录访问 `GET /admin/` 时被重定向到 `/admin/login`，或返回 401，不返回控制台业务正文。
20. 未登录访问 `GET /api/me` 返回 401，`errorCode` 为 `AUTH`。
21. 未登录访问 `GET /api/cluster` 返回 401，`errorCode` 为 `AUTH`。
22. 通过配置把粘性库 JDBC 地址改到非默认地址后，网关能在这个地址上读写 `jasagent_gateway_affinity`。
23. JDBC 地址包含 `password=` 时，打开粘性库失败，失败说明里没有这个口令原文。
24. 粘性库口令只从仓库外的口令文件读取；文件缺失时网关或粘性库失败，说明和日志里没有口令原文。
25. `scripts/start.sh` 在默认端口 18721 拉起的是网关进程，就绪判断不再依赖日志里的 `Started JasagentApplication`。
26. 按本机启动方式拉起后，18721 是网关，18731 和 18732 是 agent，`jasagent-app` 不再默认监听 18721。
27. 规格、配置或源码注释写明：登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
28. 本轮改到的模块里，新增和改动的类、常量、方法都有注释。
29. 本轮改动有自动测试，并且有可执行的测试脚本。
30. 实现和测试提交不修改 `deploy/k8s` 下的清单文件。开发规范里的 K8s 文件，等本轮 ATDD 全部通过后由部署补进清单并写 `deploy.md`。

## 还没定

没有还没定。网关对控制台采用反向代理，不采用挂载。未登录路径和必须有会话的路径按「改动地方」和「测试要求」执行。K8s 清单在本轮 ATDD 全部通过后由部署补全，实现和测试阶段不动。
