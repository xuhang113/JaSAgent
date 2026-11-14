# 网关反代控制台

- 日期：2026-11-14（Asia/Shanghai）
- 做了什么：控制台页面只留在 jasagent-web，由它提供可被反代的上游。网关按 `jasagent.gateway.web-origin` 反向代理浏览器的控制台请求，删除内嵌的 `gateway-admin`，上游不可用时返回错误。未登录只放行 `GET /health`、`GET /`、`GET /login`、`POST /login`、`POST /logout`、`GET /admin/login` 和登录页所需的 `GET /admin/assets/**`。控制台业务页、`/api/me`、`/api/cluster` 和转发到 agent 的 `/api/**` 必须有会话。粘性库 JDBC 地址和账号可配置，默认仍是 `127.0.0.1:3306/jasagent` 和 `root`，口令仍只从仓库外的口令文件读取，地址里带 `password=` 会拒绝。登录会话和存活探测仍在网关进程内，本轮网关按单副本验收。`jasagent-app` 默认端口改为 18731，不再提供登录页和控制台。`scripts/start.sh` 在默认 18721 拉起网关，用 `/health` 判断就绪，并拉起控制台上游和 18731、18732 的 agent。`scripts/sandbox/start-gateway.sh` 改走同一入口。没有改工作流、PBKDF2 登录、本机建用户命令、LangChain4j 路由、进程内通行证、sidecar、agentpass，也没有改 `deploy/k8s`。
- 涉及模块：jasagent-gateway、jasagent-web、jasagent-app、scripts
- 怎么验证：Java 21。`scripts/test-015-gateway.sh`。jasagent-gateway 38 项通过，jasagent-web 的 WebUpstreamTest 2 项通过，jasagent-app 的 DefaultPortTest 5 项和 ConsolePageTest 的 loginPageReferencesVueBuild 1 项通过。网关包不含 `gateway-admin`，控制台上游包含 `admin/index.html`。缺少 Java 21、网关 jar 或控制台上游时 `start.sh` 以非 0 退出，说明里没有口令或内部令牌。
