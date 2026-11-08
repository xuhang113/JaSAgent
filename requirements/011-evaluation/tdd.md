# 011 轨迹和输出评估的测试用例

## 1. nonemptyPassesWhenTextRemains

要求：`completion.nonempty` 在收尾文本去掉首尾空白后仍有字符时通过，得分为 1，并且不调用模型。

看到的结果：评估器标识是 `completion.nonempty`，通过，得分是 1，原因是 `nonempty`，`ChatModel` 调用次数是 0。

## 2. nonemptyFailsWhenBlank

要求：`completion.nonempty` 在收尾为 null、空串或只有空白时不通过，得分为 0。

看到的结果：这三种收尾都不通过，得分都是 0，原因都是 `empty`。

## 3. toolSuccessRatePassesWithoutCalls

要求：`tool.success.rate` 在没有工具调用时得分为 1，评估通过。

看到的结果：通过，得分是 1，原因是 `success=0;total=0`。

## 4. toolSuccessRatePassesWhenAllSucceed

要求：`tool.success.rate` 在全部工具调用成功时得分为 1，评估通过，并且不调用模型。

看到的结果：通过，得分是 1，原因是 `success=2;total=2`，`ChatModel` 调用次数是 0。

## 5. toolSuccessRateFailsOnPartial

要求：`tool.success.rate` 在存在失败的工具调用时不通过，得分等于成功次数除以总次数。

看到的结果：一次成功、一次失败时不通过，得分是 0.5，原因是 `success=1;total=2`。

## 6. judgeDisabledDoesNotCallRouter

要求：`jasagent.evaluation.judge-enabled` 的值不是 `true` 时，`llm.judge` 不调用 `ChatModel`，评估不通过，得分为 0，原因是 `judge-disabled`。

看到的结果：通过为假，得分是 0，原因是 `judge-disabled`，`ChatModel` 调用次数是 0。

## 7. judgeEnabledCallsJudgeTask

要求：`jasagent.evaluation.judge-enabled` 为 `true` 时，`llm.judge` 调用已有 `ChatModel.complete`，任务为 `JUDGE`，不点名供应方，工具列表为空，执行 id 原样传入，用户消息包含收尾文本和工具轨迹。

看到的结果：只调用一次；任务是 `JUDGE`；`namedProvider` 为空；工具列表为空；执行 id 等于请求中的执行 id；system 在 user 之前；user 正文含有收尾文本、工具名，以及该工具的 `success`。

## 8. judgeJsonIsParsed

要求：`llm.judge` 把模型返回的 JSON 对象解析为 `passed`、`score` 和 `reason`，字段顺序不影响解析。

看到的结果：字段按 `score`、`reason`、`passed` 排列时，评估不通过，得分是 0.25，原因等于 JSON 里的 reason，执行 id 和评估器标识保持为这次请求的值。

## 9. judgeUnparseableFails

要求：`llm.judge` 在模型正文无法按约定解析时不通过，得分为 0，原因是 `judge-unparseable`，返回的评估结果不是 null。

看到的结果：完成阶段给出非 null 的结果，不通过，得分是 0，原因是 `judge-unparseable`。

## 10. optionalFailureKeepsStatus

要求：`required` 不是 true 时，评估不通过不把执行状态改成 `FAILED`。

看到的结果：空收尾使 `completion.nonempty` 不通过，返回状态仍是传入的 `SUCCEEDED`。

## 11. requiredFailureSetsFailed

要求：`required` 为 true 时，评估不通过把执行状态改成 `FAILED`。

看到的结果：工具成功率不通过，返回状态是 `FAILED`。

## 12. passingEvaluationKeepsStatus

要求：评估通过时保持原来的执行状态。

看到的结果：非空收尾通过，`required` 为 true，返回状态仍是传入的 `CANCELLED`。

## 13. afterTerminalRunsNonempty

要求：执行进入 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `SUSPENDED` 之后运行 `completion.nonempty`，非空收尾通过。

看到的结果：四种终态都能完成；对 `SUCCEEDED` 和非空收尾，评估器标识是 `completion.nonempty`，通过，得分是 1，状态仍是 `SUCCEEDED`。

## 14. afterTerminalEmptyDoesNotFailExecution

要求：终态后的 `completion.nonempty` 遇到空收尾时不通过，并且不把 `SUCCEEDED` 改成 `FAILED`。

看到的结果：不通过，原因是 `empty`，返回状态仍是 `SUCCEEDED`，`ChatModel` 调用次数是 0。

## 15. beforeTerminalRejects

要求：执行还不是终态时，终态评估以错误码 `INTERNAL` 失败，不返回 null，也不调用模型。

看到的结果：`ACCEPTED` 使完成阶段抛出 `INTERNAL`，`ChatModel` 调用次数是 0。

## 16. unknownEvaluatorDoesNotCallModel

要求：未知评估器 id 不通过，得分为 0，原因是 `unknown-evaluator`，并且不调用模型。

看到的结果：不通过，得分是 0，原因是 `unknown-evaluator`，`ChatModel` 调用次数是 0。

## 17. judgeRouterFailurePropagates

要求：`ChatModel` 调用失败时，评估的 `CompletionStage` 带上原来的异常，不返回 null。

看到的结果：完成阶段抛出原来的 `LLM` 异常，执行 id 仍是这次请求的执行 id。

## 18. modulePomHasNoSpring

要求：`jasagent-evaluation` 的构建描述不依赖 Spring，也不依赖 Spring AI。

看到的结果：模块 `pom.xml` 的正文不包含 `spring`。

## 19. judgeEnabledOnlyAcceptsTrue

要求：只有配置值恰好是 `true` 时才启用 `llm.judge`，其他值视为关闭。

看到的结果：`true` 启用；缺省、空串、`false` 和 `TRUE` 都关闭。
