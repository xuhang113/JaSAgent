# 004 workflow 与 ReAct

## 这次要改什么

现状是仓库已有登录、口令、会话和目录装载，还没有工作流图。这次在 `jasagent-workflow` 里用 langgraph4j 执行 DSL 和 ReAct。图的推进只用编译后的 `CompiledGraph`，不另写一套图执行器。检查点只用 `langgraph4j-sqlite-saver` 的 `SQLiteSaverV2`；调用方传入的 JDBC URL 以 `jdbc:postgresql:` 开头时改用 `langgraph4j-postgres-saver` 的 `PostgresSaverV2`。图的 JSON 只用 `langgraph4j-dsl` 的 `JsonDslGenerator`。图的节点和边上的 trace 只用 `langgraph4j-opentelemetry` 的 hook，不另写图 trace。不使用 Spring，也不使用 Spring AI。

这次不改登录和目录、模型供应方路由、主从队列、RAG 索引、通行证策略、中止接口、HTTP 和命令行。模型、工具、插件、检索、评估和子 agent 都由调用方注入。密钥、口令和真实 JDBC 密码不写入代码、测试和示例。

## 模块

这次新增 `jasagent-workflow`，Maven 依赖 `jasagent-api`，以及 `langgraph4j-core`、`langgraph4j-dsl`、`langgraph4j-sqlite-saver`、`langgraph4j-postgres-saver`、`langgraph4j-opentelemetry`。不依赖其他业务模块，也不依赖 Spring。父工程的模块列表加入 `jasagent-workflow`，否则仓库根的 `mvn test` 看不到这个模块。`jasagent-api` 的 `ErrorCode` 补上本需求要抛出的 `WORKFLOW`、`WORKFLOW_MAX_STEPS`、`PLUGIN`、`LLM` 和 `TOOL`。

## 改动地方

工作流文档是 JSON。字段有 `id`、`rules`、`skills`、`budgetTokens` 和 `nodes`。`budgetTokens` 缺省为 8000。节点类型只允许 `react`、`tool`、`plugin`、`subagents`、`evaluate`、`interrupt`、`condition`、`retrieve`、`end`。未知类型、重复标识、空白标识，或标识以 `__` 开头，都拒绝，错误码为 `WORKFLOW`。

没有被 `condition` 点名的节点按文档顺序连成主链，入口接在图的开始之后。`condition` 不走这条默认出边，只按 `whenTrue` 和 `whenFalse` 分支。分支节点执行完回到该 `condition` 之后的下一个主链节点；后面没有主链节点就结束。`end` 连到图的结束。`react` 在图里展开成模型节点和工具节点，由条件边在工具、纠正后的再试一次和离开之间选择。

`react` 发给模型的消息顺序是：一条 system，内容同时含有角色、规则、技能和未完成任务板；然后是压缩摘要；然后是最近轮次，保留原有角色和正文；然后是全部纠正 note；然后是当前用户消息。纠正 note 整段紧挨在当前用户消息之前。同一次 `react` 里已经返回的工具结果接在当前用户消息之后。模型请求的任务名是 `react`。模型返回工具调用时，同一步里的多个调用并行，全部结束后才进入下一步。没有工具调用时，这一步的正文成为候选回答。`maxSteps` 是模型请求次数的上限，用尽后失败，错误码为 `WORKFLOW_MAX_STEPS`，不再请求模型。

`grounding` 为 true 时，候选回答里的十进制数字，以及双引号或「」中的片段，必须能在本次工具结果、检索命中或当前用户消息里找到。找不到就追加一条 `source` 为 `GROUNDING` 的纠正 note，并且只再请求一次模型。第二次仍然找不到就保留这次回答，不再请求。

节点上的 `plugins` 按阶段调用。`react` 一次工具往返的阶段顺序是 `onNodeStart`、`beforeModel`、`afterModel`、`beforeTool`、`afterTool`，离开该节点时再调用 `onNodeEnd`。`onNodeStart` 和 `onNodeEnd` 各只调用一轮。`pluginFailure` 为 `fail` 时，插件异常使执行失败，错误码为 `PLUGIN`。缺省时记下异常并继续。

`condition` 的 `compare` 只允许 `boolean`、`equals`、`gt`、`gte`、`lt`、`lte`、`eq`。`boolean` 只看上一节点输出是不是布尔真。`equals` 做字符串相等。其余五个做数值阈值，上一节点输出可以是数字，或可以按十进制解析的字符串。其他 `compare` 拒绝，错误码为 `WORKFLOW`。

`subagents` 写了 `childWorkflow` 时，把该标识交给子执行。没写时，交给子执行的流程文档只有一个类型为 `react` 的节点。父流程等待子执行返回，再把返回正文交给后续节点。

`retrieve` 的 `queryFrom` 只接受 `input`，查询正文用当前用户消息，并带上 `topK` 和 `scopes`。`required` 不是 true 时，空结果不失败。`evaluate` 调用指定评估器。`required` 不是 true 时，评估未通过不把状态改成失败。`tool` 节点调用指定工具。`plugin` 节点调用指定插件的 `onNodeStart` 和 `onNodeEnd`。`interrupt` 用编译配置在该节点前停下，状态为 `SUSPENDED`。用同一个 `executionId` 作为线程标识，可以从已有的检查点恢复并继续后面的节点。`end` 正常结束时状态为 `SUCCEEDED`。模型端口抛出 `LLM` 时，返回的执行结果不是 null，状态为 `FAILED`，错误码为 `LLM`。

仓库内置示例文档 `answer-with-tool`：规则 `base`，技能 `research`，`budgetTokens` 为 8000。节点 `find` 是 `retrieve`，`queryFrom` 为 `input`，`topK` 为 5，范围是 `user` 和 `workspace`，`required` 为 false。节点 `act` 是 `react`，`maxSteps` 为 6，工具是 `clock` 和 `rag.search`，`grounding` 为 true。节点 `grade` 是 `evaluate`，评估器是 `tool.success.rate`，`required` 为 false。节点 `done` 是 `end`。

每跨一个节点边界，由 langgraph4j 把检查点提交进保存器，再进入下一节点。默认保存器是 `SQLiteSaverV2`，数据库文件路径由调用方传入。PostgreSQL URL 由调用方传入，本模块不从环境变量读取密码。图 JSON 含有 `type` 为 `langgraph4j`、`version` 为 `1.0`。节点执行产生名为 `evaluateNode` 的 span。公共执行方法返回 `CompletionStage`，模型还没返回时这个阶段尚未完成。阻塞的图调用放在 `VirtualIo` 的虚拟线程里。

## 数据库

这次不写 Flyway，也不手写检查点表。表由 `SQLiteSaverV2` 或 `PostgresSaverV2` 自己的脚本创建。默认不连接 PostgreSQL。

## 中间件

不连接消息中间件。不使用 Spring。图 trace 只注册 langgraph4j 的 OpenTelemetry hook。本需求不配置导出端点，测试里可以安装内存里的 span 导出器来观察 hook 是否生效。

## 测试要求

1. 节点类型不在允许列表中时解析失败，错误码为 `WORKFLOW`。
2. 示例 `answer-with-tool` 解析出的标识、规则、技能、预算，以及 find、act、grade、done 的类型和参数，与规格中的示例一致。
3. 第一次模型请求里，system 同时含有角色、规则、技能和未完成任务板，紧跟着压缩摘要，再跟着按原顺序排列的最近轮次。
4. 第一次模型请求里，最后一条纠正 note 的下一条就是当前用户消息。
5. `react` 完成一次工具调用后状态为 `SUCCEEDED`，输出等于模型的最终回答。
6. 同一步的两个工具调用都进入执行后才返回，下一次模型请求发生在这两次返回之后。
7. `maxSteps` 用尽时状态为 `FAILED`，错误码为 `WORKFLOW_MAX_STEPS`，模型调用次数等于 `maxSteps`。
8. 带一次工具调用的 `react` 上，插件阶段顺序是 `onNodeStart`、`beforeModel`、`afterModel`、`beforeTool`、`afterTool`、`beforeModel`、`afterModel`、`onNodeEnd`。
9. `pluginFailure` 为 `fail` 时，插件异常使状态为 `FAILED`，错误码为 `PLUGIN`。
10. 未写 `pluginFailure` 时，插件异常出现在执行结果的插件错误里，状态仍为 `SUCCEEDED`。
11. `grounding` 打开且两次回答都对不上时，纠正 note 的 `source` 含有两条 `GROUNDING`，模型只被调用两次，第二次请求里该 note 紧挨在当前用户消息之前。
12. `condition` 的布尔比较为假时，为真分支上的工具不会被调用，状态为 `SUCCEEDED`。
13. `condition` 的 `compare` 不是布尔、字符串相等或数值阈值时解析失败，错误码为 `WORKFLOW`。
14. 字符串相等成立时，只调用为真分支的工具。
15. 数值大于等于阈值成立时，只调用为真分支的工具。
16. 未写 `childWorkflow` 时，子执行收到的流程只有一个类型为 `react` 的节点，父流程输出等于子执行的返回正文。
17. 写了 `childWorkflow` 时，子执行收到该流程标识。
18. `retrieve` 的 `required` 不是 true 且检索被调用并返回空结果时，状态为 `SUCCEEDED`。
19. `evaluate` 的 `required` 不是 true 且评估器被调用并返回未通过时，状态为 `SUCCEEDED`，结果里的评估通过标志为假。
20. `interrupt` 之后状态为 `SUSPENDED`，后续工具尚未调用；用原 `executionId` 从新的引擎实例恢复后状态为 `SUCCEEDED`，后续工具被调用。
21. 进入后一个节点时，SQLite 的 `LG4JCheckpoint` 已有 `next_node_id` 指向该节点的记录，且保存时间不晚于该节点开始。
22. 执行结果中的图 JSON 含有 `langgraph4j` 类型和版本 `1.0`。
23. 未提供 PostgreSQL URL 时，检查点保存器的运行时类是 `SQLiteSaverV2`。
24. JDBC URL 以 `jdbc:postgresql:` 开头时，初始化经过 `PostgresSaverV2`，并且不创建 SQLite 文件。
25. 运行期可以装载 langgraph4j 的核心、DSL、SQLite、PostgreSQL 和 OpenTelemetry 类，装载不到 Spring 与 Spring AI。
26. `execute` 返回的 `CompletionStage` 在模型尚未返回时未完成。
27. 模型以 `LLM` 失败时，返回的执行结果不是 null，状态为 `FAILED`，错误码为 `LLM`。
28. `react` 发给模型的任务名是 `react`。
29. 图执行产生名为 `evaluateNode` 的 OpenTelemetry span。

## 还没定

无。文档格式、比较运算符、检查点保存器和示例节点参数按上面的决定实现。
