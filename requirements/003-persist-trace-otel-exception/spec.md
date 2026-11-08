# 003 持久化、追踪、OpenTelemetry 与异常

## 这次要改什么

现状是仓库已经有 `jasagent-api` 和 `jasagent-auth`。`AgentException` 与 `ErrorCode` 已在 api 中，错误码目前只有 `AUTH` 和 `INTERNAL`。执行过程还没有 JDBC 存储，节点状态没有检查点，工作区里还没有按执行 id 写的可读日志，也没有同一次执行共用的 traceId，异步边界还没有统一的失败处理。

这次补上执行过程的 JDBC 存储、节点检查点、可读 trace、OpenTelemetry 根 span 与子 span，以及异步边界上的异常。不改登录、口令、会话和目录装载的实现，不改模型路由、工作流步骤机、工具、插件、通行证、中止恢复、检索、评估、HTTP 和命令行。那些模块即使并行存在，这次也不改它们的实现。

节点检查点使用 langgraph4j 的 `SQLiteSaverV2` 或 `PostgresSaverV2`。不自建一套检查点表去保存图状态，也不用自写的读写逻辑代替 saver 的 `put`、`get` 和 `list`。执行过程的其余记录仍按 JDBC 与 Flyway 落库。

## 模块

这次新增 `jasagent-persist`、`jasagent-trace`、`jasagent-otel`、`jasagent-exception`。四个模块的 Maven 依赖只指向 `jasagent-api`，可以再依赖 JDBC、Flyway、H2、PostgreSQL 驱动、langgraph4j 的 sqlite-saver 与 postgres-saver，以及 OpenTelemetry SDK。四个模块都不依赖 Spring，也不互相依赖。

`jasagent-api` 里的 `ErrorCode` 补上 `LLM`、`TOOL`、`PLUGIN`、`MCP`、`RAG`、`WORKFLOW`、`WORKFLOW_MAX_STEPS`、`PERSIST`、`INTERRUPTED`、`CANCELLED`。已有的 `AUTH` 和 `INTERNAL` 保留。`AgentException` 的构造方法和字段保持不变。除此之外不改 api 里已有类型的行为，不改 `jasagent-auth`。

## 改动地方

执行过程的默认数据库是 H2 文件，路径为 `<workspace>/jasagent/data/jasagent`。连接 URL 由该路径生成。环境变量 `JASAGENT_JDBC_URL` 非空时改用这个 URL，因此可以换成 PostgreSQL。用户名来自 `JASAGENT_JDBC_USER`，未设置时 H2 使用 `sa`，PostgreSQL 使用空用户名。密码只来自 `JASAGENT_JDBC_PASSWORD`，未设置时使用空字符串。密码不写入源码、迁移脚本、日志、示例配置或工作区里的文本文件。

Flyway 在打开数据库时执行迁移，脚本只放在本模块的 `dev/jasagent/persist/db/migration`，避免把 langgraph4j saver 自带的 SQL 当成执行库迁移。`execution` 表含有 `execution_id`、`user_id`、`session_id`、`workspace_path`、`status`、`otel_trace_id`、`otel_root_span_id`。同时建立 `agent`、`task`、`message`、`correction_note`、`context_summary`、`llm_call`、`tool_call`、`plugin_call`、`retrieval`、`evaluation`。`llm_call` 记下供应方、模型名、延迟和 token 用量。`task` 可以标成 `FAILED` 并记下错误码。这些表建在执行过程使用的 JDBC 库里。H2 执行库中不建 `node_checkpoint`，也不建 langgraph4j 的 `LG4JCheckpoint` 表。

节点检查点由 `NodeCheckpoints` 打开 langgraph4j 的 saver。默认使用 `SQLiteSaverV2`，数据库文件是 `<workspace>/jasagent/data/checkpoints.db`，线程 id 使用 executionId。`JASAGENT_JDBC_URL` 以 `jdbc:postgresql:` 开头时改用 `PostgresSaverV2`，主机、端口和库名从该 URL 解析，密码仍只取环境变量，不从示例或源码里写死。调用方把返回的 `BaseCheckpointSaver` 交给 `CompileConfig.checkpointSaver`。进入下一节点之前必须先 `put` 成功；`put` 失败时完成异常，错误码为 `PERSIST`，下一节点的动作不会执行。已经提交的检查点在重新打开同一个 saver 文件后仍能按 executionId 读回。

阻塞的 JDBC 与文件写入放进 `VirtualIo` 使用的 `Executors.newVirtualThreadPerTaskExecutor()`，对外返回 `CompletionStage`。

可读日志写到 `<workspace>/jasagent/logs/{executionId}/`。目录里有 `trace.log` 和 `events.jsonl`。事件种类覆盖受理、登录用户、目录、调度、合并、节点、模型路由、检索和溯源、通行证决定、工具、插件、压缩、纠正 note、中止、恢复、评估、终态。写入前去掉 `Authorization` 头的值，日志正文里不保留令牌。异常堆栈写入 `trace.log`。

OpenTelemetry 根 span 的名字是 `jasagent.execution`。子 span 的名字是 `agent.schedule`、`workflow.node`、`llm.chat`、`tool.call`、`plugin.invoke`、`mcp.call`、`rag.retrieve`、`rag.rerank`、`eval.run`。每个 span 带属性 `jasagent.execution_id`。同一次执行的根 span 和子 span 共用一个 traceId，这个 traceId 与根 span id 可以从该次执行读出，供执行记录保存。异步交接时显式把这次执行的 `Context` 设为当前上下文，交接之后的当前 span 仍属于同一个 traceId，调用方线程上的当前 span 不被这次交接留下。配置项 `jasagent.otel.endpoint` 为空时不创建 OTLP 导出器。配置为非空 URL 时使用 OTLP HTTP 的 `OtlpHttpSpanExporter`，导出地址就是该 URL。

异步边界上的调用如果抛出异常，完成结果是异常，而不是成功的空值。调用方自己抓住异常后返回空，也同样以错误码 `INTERNAL` 失败，并带上 executionId。消费者收到未捕获异常时，把该任务标为失败，错误码与 executionId 交给失败回调，然后本次消费得到失败结果；随后另一次消费仍能完成。进程不因为这一次失败而停止接受后面的调用。HTTP 错误体只有 executionId 和错误码。堆栈文本交给 trace 回调，不放进错误体。

## 数据库

执行过程使用 JDBC。默认是工作区下的 H2 文件，Flyway 管理上一节列出的业务表。节点图状态不进入这些表，而进入 langgraph4j saver 自己的库：默认是旁边的 SQLite 文件，JDBC URL 为 PostgreSQL 时进入同一个 PostgreSQL 库里 saver 自带的表。

## 中间件

这次不使用消息中间件，也不监听 HTTP。OpenTelemetry 默认不向外部导出。只有给出 `jasagent.otel.endpoint` 时才装配 OTLP HTTP 导出器。

## 测试要求

1. 未设置 `JASAGENT_JDBC_URL` 时，执行库是 H2 文件，路径为工作区下的 `jasagent/data/jasagent`。
2. 设置 `JASAGENT_JDBC_URL` 后，执行库使用该 URL，并且不会另建默认 H2 文件。
3. Flyway 迁移之后，`execution` 表含有 `execution_id`、`user_id`、`session_id`、`workspace_path`、`status`、`otel_trace_id`、`otel_root_span_id`。
4. Flyway 迁移之后，存在 `agent`、`task`、`message`、`correction_note`、`context_summary`、`llm_call`、`tool_call`、`plugin_call`、`retrieval`、`evaluation`。
5. 写入的执行记录能按 executionId 读回，其中包含原来的 traceId 和根 span id。
6. 模型调用写入供应方、模型名、延迟和 token 用量，并能按调用 id 读回。
7. 任务可以标为 `FAILED`，并记下错误码。
8. `JASAGENT_JDBC_PASSWORD` 只留在连接设置中，不写入工作区里除数据库文件以外的文件。
9. 默认节点检查点的 saver 是 `SQLiteSaverV2`，文件为工作区下的 `jasagent/data/checkpoints.db`，提交后该 SQLite 库中有 `LG4JCheckpoint` 行。
10. 把该 saver 交给 langgraph4j 的 `CompileConfig` 后，图执行能在 saver 中留下对应线程的检查点。
11. 关闭并重新打开同一个检查点文件后，仍能按 executionId 读回已提交的节点和状态。
12. 检查点提交失败时，完成异常的错误码是 `PERSIST`，下一节点的动作不会执行。
13. JDBC URL 以 `jdbc:postgresql:` 开头时，打开检查点会进入 `PostgresSaverV2`，并且不创建默认的 SQLite 检查点文件。
14. 执行过程使用的 H2 库中不存在 `node_checkpoint`、`LG4JCheckpoint`、`LG4JThread` 或 `LG4JThreadTag`。
15. 一次执行的 `trace.log` 和 `events.jsonl` 位于工作区下的 `jasagent/logs/{executionId}`。
16. 受理、登录用户、目录、调度、合并、节点、模型路由、检索和溯源、通行证决定、工具、插件、压缩、纠正 note、中止、恢复、评估、终态都会写入 `trace.log` 和 `events.jsonl`。
17. 事件正文里的 `Authorization` 值不会出现在 `trace.log` 或 `events.jsonl` 中。
18. 异常堆栈写入该次执行的 `trace.log`。
19. 根 span 的名字是 `jasagent.execution`，并且带有 `jasagent.execution_id`。
20. 同一次执行的根 span 和全部子 span 共用一个 traceId，该 traceId 与根 span id 能从这次执行的已保存标识读出。
21. 异步交接之后，虚拟线程上的当前 span 仍属于已保存的 traceId，交接返回后调用方线程上没有留下该 span。
22. 未配置 `jasagent.otel.endpoint` 时不启用 OTLP 导出，导出器类名为空。
23. 配置 `jasagent.otel.endpoint` 后，导出器是 `OtlpHttpSpanExporter`，并且导出已启用。
24. `ErrorCode` 含有 `LLM`、`TOOL`、`PLUGIN`、`MCP`、`RAG`、`WORKFLOW`、`WORKFLOW_MAX_STEPS`、`PERSIST`、`AUTH`、`INTERRUPTED`、`CANCELLED`、`INTERNAL`。
25. 异步调用自己吞掉异常并返回空时，边界以错误码 `INTERNAL` 失败，并带上 executionId。
26. 异步调用直接抛出异常时，边界以异常完成，结果不是空值，并且异常带有 executionId。
27. 消费者遇到未捕获异常时把该任务标为失败，本次结果为失败，下一次消费仍能成功完成。
28. HTTP 错误体含有 executionId 和错误码，堆栈文本只进入 trace 回调，不进入错误体。
29. 异步边界在虚拟线程上执行调用。

## 还没定

无。SQLite 检查点文件名使用 `checkpoints.db`。OTLP 使用 HTTP 导出器，地址原样取自 `jasagent.otel.endpoint`。错误码补在已有的 `ErrorCode` 上。PostgreSQL 的主机、端口和库名从 JDBC URL 解析，用户名和密码只取环境变量。
