# 004 workflow 与 ReAct 的测试用例

## 1. unknownNodeTypeRejected

要求：节点类型不在允许列表中时解析失败，错误码为 `WORKFLOW`。

看到的结果：解析含有 `type` 为 `loop` 的文档时抛出 `AgentException`，错误码为 `WORKFLOW`。

## 2. exampleAnswerWithToolParses

要求：示例 `answer-with-tool` 解析出的标识、规则、技能、预算，以及 find、act、grade、done 的类型和参数，与规格中的示例一致。

看到的结果：标识是 `answer-with-tool`，规则是 `base`，技能是 `research`，预算是 8000。`find` 是 `retrieve`，`queryFrom` 是 `input`，`topK` 是 5，范围含有 `user` 和 `workspace`，`required` 不是 true。`act` 是 `react`，`maxSteps` 是 6，工具是 `clock` 和 `rag.search`，`grounding` 为 true。`grade` 是 `evaluate`，评估器是 `tool.success.rate`，`required` 不是 true。`done` 是 `end`。

## 3. modelMessagesFollowFixedOrder

要求：第一次模型请求里，system 同时含有角色、规则、技能和未完成任务板，紧跟着压缩摘要，再跟着按原顺序排列的最近轮次。

看到的结果：第一条消息的角色是 `system`，正文同时含有角色、规则、技能和未完成任务的原文。第二条是压缩摘要。其后两条与最近轮次的角色和正文一一对应，顺序不变。

## 4. correctionSitsImmediatelyBeforeUser

要求：第一次模型请求里，最后一条纠正 note 的下一条就是当前用户消息。

看到的结果：用户消息是纠正 note 块之后的第一条，中间没有其他消息，用户消息正文等于本次输入。

## 5. reactCompletesOneToolCall

要求：`react` 完成一次工具调用后状态为 `SUCCEEDED`，输出等于模型的最终回答。

看到的结果：`clock` 被调用一次，状态是 `SUCCEEDED`，输出等于第二次模型返回的正文。

## 6. parallelToolCallsJoinBeforeNextStep

要求：同一步的两个工具调用都进入执行后才返回，下一次模型请求发生在这两次返回之后。

看到的结果：两个工具都观察到对方已经进入；若没有并行，等待会超时。第二次模型请求发生时，两个工具都已经返回。

## 7. maxStepsFailsWithWorkflowMaxSteps

要求：`maxSteps` 用尽时状态为 `FAILED`，错误码为 `WORKFLOW_MAX_STEPS`，模型调用次数等于 `maxSteps`。

看到的结果：`maxSteps` 为 2 时状态是 `FAILED`，错误码是 `WORKFLOW_MAX_STEPS`，模型恰好被调用 2 次。

## 8. pluginPhasesAroundModelAndTool

要求：带一次工具调用的 `react` 上，插件阶段顺序是 `onNodeStart`、`beforeModel`、`afterModel`、`beforeTool`、`afterTool`、`beforeModel`、`afterModel`、`onNodeEnd`。

看到的结果：插件端口记录到的阶段列表与该顺序完全一致。

## 9. pluginFailureFailStopsNode

要求：`pluginFailure` 为 `fail` 时，插件异常使状态为 `FAILED`，错误码为 `PLUGIN`。

看到的结果：状态是 `FAILED`，错误码是 `PLUGIN`，模型没有被调用。

## 10. pluginFailureDefaultsToContinue

要求：未写 `pluginFailure` 时，插件异常出现在执行结果的插件错误里，状态仍为 `SUCCEEDED`。

看到的结果：状态是 `SUCCEEDED`，插件错误列表不是空的。

## 11. groundingRetriesOnlyOnce

要求：`grounding` 打开且两次回答都对不上时，纠正 note 的 `source` 含有两条 `GROUNDING`，模型只被调用两次，第二次请求里该 note 紧挨在当前用户消息之前。

看到的结果：模型调用次数是 2，纠正 note 来源是两条 `GROUNDING`，第二次请求中最后一条纠正 note 的下一条是当前用户消息。

## 12. conditionBooleanFalseSkipsBranch

要求：`condition` 的布尔比较为假时，为真分支上的工具不会被调用，状态为 `SUCCEEDED`。

看到的结果：为真分支的工具调用次数是 0，状态是 `SUCCEEDED`。

## 13. conditionRejectsUnsupportedCompare

要求：`condition` 的 `compare` 不是布尔、字符串相等或数值阈值时解析失败，错误码为 `WORKFLOW`。

看到的结果：`compare` 为 `contains` 时抛出 `AgentException`，错误码为 `WORKFLOW`。

## 14. conditionStringEqualitySelectsBranch

要求：字符串相等成立时，只调用为真分支的工具。

看到的结果：为真分支的工具被调用，为假分支的工具没有被调用。

## 15. conditionNumericThresholdSelectsBranch

要求：数值大于等于阈值成立时，只调用为真分支的工具。

看到的结果：为真分支的工具被调用，为假分支的工具没有被调用。

## 16. subagentsDefaultToReactOnlyWorkflow

要求：未写 `childWorkflow` 时，子执行收到的流程只有一个类型为 `react` 的节点，父流程输出等于子执行的返回正文。

看到的结果：子流程文档解析后只有一个节点且类型是 `react`，父流程输出等于子执行返回的正文。

## 17. subagentsPassChildWorkflowId

要求：写了 `childWorkflow` 时，子执行收到该流程标识。

看到的结果：子执行收到的标识等于文档里的 `childWorkflow`。

## 18. retrieveEmptyDoesNotFailWhenOptional

要求：`retrieve` 的 `required` 不是 true 且检索被调用并返回空结果时，状态为 `SUCCEEDED`。

看到的结果：检索被调用一次，状态是 `SUCCEEDED`。

## 19. evaluateFailureDoesNotFailWhenOptional

要求：`evaluate` 的 `required` 不是 true 且评估器被调用并返回未通过时，状态为 `SUCCEEDED`，结果里的评估通过标志为假。

看到的结果：评估器被调用一次，状态是 `SUCCEEDED`，评估通过标志为假。

## 20. interruptSuspendsAndResumeContinues

要求：`interrupt` 之后状态为 `SUSPENDED`，后续工具尚未调用；用原 `executionId` 从新的引擎实例恢复后状态为 `SUCCEEDED`，后续工具被调用。

看到的结果：第一次执行状态是 `SUSPENDED`，后续工具次数是 0。新引擎用同一执行标识和同一 SQLite 文件恢复后，状态是 `SUCCEEDED`，后续工具次数是 1。

## 21. checkpointCommittedBeforeNextNode

要求：进入后一个节点时，SQLite 的 `LG4JCheckpoint` 已有 `next_node_id` 指向该节点的记录，且保存时间不晚于该节点开始。

看到的结果：后一个节点开始时能读到指向自己的检查点，该记录的保存时间不晚于节点开始时间。

## 22. graphJsonComesFromLanggraphDsl

要求：执行结果中的图 JSON 含有 `langgraph4j` 类型和版本 `1.0`。

看到的结果：图 JSON 含有 `"type":"langgraph4j"` 和 `"version":"1.0"`。

## 23. defaultCheckpointSaverIsSqlite

要求：未提供 PostgreSQL URL 时，检查点保存器的运行时类是 `SQLiteSaverV2`。

看到的结果：执行结果里的保存器类名是 `org.bsc.langgraph4j.checkpoint.SQLiteSaverV2`。

## 24. postgresUrlUsesPostgresSaver

要求：JDBC URL 以 `jdbc:postgresql:` 开头时，初始化经过 `PostgresSaverV2`，并且不创建 SQLite 文件。

看到的结果：失败信息的异常链含有 `PostgresSaverV2`，指定的 SQLite 文件不存在。URL 里的口令只是占位符 `placeholder`。

## 25. classpathUsesLanggraphNotSpring

要求：运行期可以装载 langgraph4j 的核心、DSL、SQLite、PostgreSQL 和 OpenTelemetry 类，装载不到 Spring 与 Spring AI。

看到的结果：`StateGraph`、`JsonDslGenerator`、`SQLiteSaverV2`、`PostgresSaverV2`、`OTELWrapCallTraceHook` 可以装载。`ApplicationContext` 和 Spring AI 的 `ChatModel` 装载失败。

## 26. executeReturnsIncompleteStage

要求：`execute` 返回的 `CompletionStage` 在模型尚未返回时未完成。

看到的结果：模型仍阻塞时，返回的 `CompletionStage` 的 `isDone` 为假；放行后能得到 `SUCCEEDED`。

## 27. llmFailureIsNotNull

要求：模型以 `LLM` 失败时，返回的执行结果不是 null，状态为 `FAILED`，错误码为 `LLM`。

看到的结果：执行结果不是 null，状态是 `FAILED`，错误码是 `LLM`。

## 28. reactTaskNameIsReact

要求：`react` 发给模型的任务名是 `react`。

看到的结果：模型收到的任务名等于 `react`。

## 29. graphTraceSpanIsEvaluateNode

要求：图执行产生名为 `evaluateNode` 的 OpenTelemetry span。

看到的结果：内存导出器里至少有一个已经结束的 span，名称是 `evaluateNode`。
