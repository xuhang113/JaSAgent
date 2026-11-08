# 004 工作流与 ReAct 的测试用例

## 1. unknownNodeTypeRejected

要求：节点类型不在允许集合内时，装载失败，错误码是 `WORKFLOW`。

看到的结果：装载含 `type` 为 `nope` 的文档抛出 `WORKFLOW`。

## 2. exampleWorkflowLoads

要求：示例工作流 `answer-with-tool` 能装载，节点标识依次包含 `find`、`act`、`grade`、`done`。

看到的结果：装载结果的标识等于 `answer-with-tool`，四个节点标识按顺序出现。

## 3. reactMessageOrder

要求：`react` 的消息顺序是 system、压缩摘要、最近轮次、纠正 note、当前用户消息，且纠正 note 紧挨当前用户消息。

看到的结果：第一条是 system 且包含规则、技能、任务板和 executionId；第二条包含摘要；倒数第二条包含纠正 note；最后一条是当前用户消息。

## 4. reactCompletesOneToolCall

要求：`react` 能完成一次工具调用后再结束，工具只被调用一次。

看到的结果：工具名 `clock` 的调用次数是 1，工作流状态是 `SUCCEEDED`，输出等于模型的最终正文。

## 5. maxStepsFails

要求：`maxSteps` 用尽时失败，错误码是 `WORKFLOW_MAX_STEPS`。

看到的结果：状态是 `FAILED`，错误码是 `WORKFLOW_MAX_STEPS`。

## 6. groundingAddsNote

要求：grounding 找不到依据时追加来源为 `GROUNDING` 的纠正 note。

看到的结果：结果里的纠正 note 有且只有一条，来源是 `GROUNDING`。

## 7. groundingRetriesOnlyOnce

要求：grounding 只再请求一次模型。

看到的结果：模型完成次数是 2。

## 8. parallelToolCalls

要求：同一步的多个工具调用会并行启动，汇合后才进入下一步。

看到的结果：两个工具都已开始时，下一步模型调用还没有发生；放开等待后工作流才成功。

## 9. conditionStringEquals

要求：`condition` 能按字符串相等选择分支。

看到的结果：输入 `yes` 时输出等于相等分支的标记。

## 10. conditionNumericThreshold

要求：`condition` 能按数值阈值选择分支。

看到的结果：输入 `10` 且阈值是大于等于 5 时，输出等于达标分支的标记。

## 11. conditionBoolean

要求：`condition` 能按布尔值选择分支。

看到的结果：输入 `true` 时输出等于真分支的标记。

## 12. emptyRetrieveDoesNotFail

要求：`required` 不是 true 的 retrieve 得到空结果时，工作流状态仍是 `SUCCEEDED`。

看到的结果：状态是 `SUCCEEDED`。

## 13. checkpointFailureSkipsNode

要求：检查点提交失败时，失败节点的工具不会执行，错误码是 `PERSIST`。

看到的结果：第一个工具执行过，第二个工具没有执行，错误码是 `PERSIST`。

## 14. pluginPhasesAroundTool

要求：有工具调用时，`afterModel` 出现在 `beforeTool` 之前，`beforeTool` 出现在 `afterTool` 之前，并且 `onNodeStart` 和 `onNodeEnd` 都会出现。

看到的结果：四个阶段都出现，且顺序满足上述先后关系。

## 15. subagentsDefaultIsReactOnly

要求：`subagents` 没有 `childWorkflow` 时，子流程文档里只有一个类型为 `react` 的节点。

看到的结果：传给子 agent 运行器的文档解析后只有一个节点，类型是 `react`。
