# sidecar 出站不再占用 Tomcat 平台工作线程

- 日期：2026-11-18（Asia/Shanghai）
- 做了什么：`/v1/forward` 仍是 Spring MVC。容器线程只开启 Servlet 异步并把这次转发交给虚拟线程，然后立刻返回。等待 agentpass、等待上游和按块抄写响应都在虚拟线程上。抄写只用 `WriteListener` 和非阻塞写出，等待发生在锁条件上，不进入会钉住载体的 `synchronized`。请求体和响应体仍按块转发，不整包读入。生产默认不替换 Tomcat 线程池；只有验收参数 `jasagent.sidecar.tomcat-platform-workers` 大于 0 时才安装固定数量的操作系统平台工作线程。这不是 `server.tomcat.threads.max`，也不把生产线程上限改低。`/v1/forward` 的方法、路径、目的地址头、票据头、流式正文、不跟随重定向、白名单和票据核销保持不变。
- 涉及模块：jasagent-sidecar、scripts。不改 jasagent-app、jasagent-llm、jasagent-web、jasagent-gateway，不改运行时执行模型，不改 deploy/k8s。
- 怎么验证：`bash scripts/test-021-sidecar-egress-threads.sh`。脚本覆盖验收第 1 到第 9 条：用 `jcmd Thread.print` 和 `/proc/<pid>/task` 核对操作系统平台线程（不是请求槽位），`server.tomcat.threads.max` 保持 64 而平台工作线程压到 2，并发长流式超过该上限且首块在上游结束前到达，期间 `/health` 和新的 `/v1/forward` 在超时内可用，并复跑 `scripts/test-018-sidecar-egress.sh`。第 10 到第 12 条留给部署，本记录不代替 ATDD，也不写 deploy.md。
