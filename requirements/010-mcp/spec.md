# 010 MCP 客户端

## 这次要改什么

现状是工作流已经能通过工具端口调用本地工具，通行证端口也已经放在 `jasagent-api`。这次增加 MCP 客户端，把远端工具登记成模型可以调用的工具。不改登录、模型路由、工作流步骤机、检索、页面和命令行。不引入 Spring，不引入 Spring AI，不引入 MCP Studio，也不使用模型上下文协议的官方 SDK。不依赖 `jasagent-agentpass` 的实现类。

## 模块

这次新增 `jasagent-mcp`。它的 Maven 依赖只指向 `jasagent-api` 和 JSON 库。客户端实现本模块的 `ToolProvider`，每个远端工具实现 api 里的 `Tool`。组装点以后把 `tools()` 登记进工具目录。调用前使用 api 里的 `PassGate`，不引用 `dev.jasagent.agentpass`。

## 改动地方

服务器配置带服务器标识、传输方式和超时。传输只做两种：stdio 子进程，以及向一个 HTTP 地址发 POST。默认超时 30 秒。stdio 上每条 JSON-RPC 消息独占一行，消息内部不换行。HTTP 把同一次 POST 的响应体当作结果；响应可以是 JSON，也可以是 SSE。客户端请求的协议版本是 `2024-11-05`。握手成功后发送 `notifications/initialized`，再调用 `tools/list`。单个服务器握手、列工具或启动子进程失败时，记入不可达列表，其他服务器继续。`open` 仍然成功。

登记名是字面量 `mcp_`、配置里的服务器标识、`_`、远端工具名三段拼接，不改写字符。例如服务器 `demo` 的工具 `echo` 登记为 `mcp_demo_echo`。该工具不是内部工具。描述使用远端描述。`definitions()` 给出同名的模型工具定义，`parametersJson` 使用远端 `inputSchema`；远端没有 schema 时用 `{}`。已经连通的工具如果生成同一个登记名，`open` 失败，错误码是 `MCP`，已经打开的会话会关掉。

调用时先执行 `PassGate.acquire`。`PassRequest` 的 `toolName` 是登记名，带有 `userId` 和 `executionId`，`pluginId` 为空。`argumentsHash` 是参数 JSON 字符串按 UTF-8 编码后的 SHA-256 小写十六进制；参数为 null 时按空字符串计算。只有决定是 `ALLOW` 或 `SKIPPED` 时才向远端发送 `tools/call`，并且远端方法里的工具名是原来的名字，不是登记名。`DENY` 不发送。当前 api 的决定是 `ALLOW`、`DENY`、`SKIPPED`。以后如果增加 `PENDING`，同样不发送，因为只要不是 `ALLOW` 或 `SKIPPED` 就不调用远端。

远端成功时，把 `content` 里的文本按出现顺序用换行接起来，`success` 为真。远端把 `isError` 标为真时不抛异常，`success` 为假，文本仍然返回。JSON-RPC error、超时和传输失败都让这次调用失败，错误码是 `MCP`，不返回 null。阻塞的网络和子进程读写放在虚拟线程上。`call` 返回 `CompletionStage`，在远端回应之前这个阶段还没有完成。

## 数据库

这次不建表，也不改已有表。

## 中间件

这次不启动业务 HTTP 端口，也不连接消息中间件。测试里的远端是 `127.0.0.1` 上的临时服务器，或者本机 `python3` 子进程。不访问外网，不写真实密钥。

## 测试要求

1. 服务器标识 `demo` 上的远端工具 `echo` 登记后的名称是 `mcp_demo_echo`，描述与远端一致，并且不是内部工具。
2. 调用前先执行 api 的 `PassGate.acquire`，此时远端还没有收到 `tools/call`；请求里的工具名是登记名，并带有 `userId`、`executionId`，`pluginId` 为空。
3. `argumentsHash` 等于传入的参数 JSON 字符串按 UTF-8 编码后的 SHA-256 小写十六进制。
4. 决定为 `DENY` 时不发送 `tools/call`，调用结果的 `success` 为假。
5. 决定为 `ALLOW` 时，`tools/call` 使用远端原名 `echo`，结果文本等于远端文本，并且 `success` 为真。
6. 决定为 `SKIPPED` 时仍然发送 `tools/call`。
7. 一个服务器连不上时，`open` 仍然成功，另一个服务器的工具已经登记。
8. 连不上的服务器出现在不可达列表里，并且不会留下以它的标识拼出的工具。
9. 两个已经连通的服务器生成同一个登记名时，`open` 失败，错误码是 `MCP`。
10. 远端返回 JSON-RPC error 时，这次调用失败，错误码是 `MCP`，不会得到 null 结果。
11. `call` 返回的 `CompletionStage` 在远端尚未回应时还没有完成。
12. 执行这次调用并申请通行证时，当前线程是虚拟线程。
13. `jasagent-mcp` 的生产源码和 pom 不引用 `jasagent-agentpass`，pom 也不依赖 Spring 或 Spring AI。
14. stdio 传输的服务器能登记 `mcp_pipe_echo`，`ALLOW` 之后能取回远端文本。
15. 远端把 `isError` 标为真时不抛异常，`success` 为假，文本仍然返回。
16. 模型工具定义的名称是登记名，`parametersJson` 保留远端 `inputSchema` 里的对象类型。

## 还没定

无。超时 30 秒、登记名拼接方式和哈希算法按上一节实现。
