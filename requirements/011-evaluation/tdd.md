# 011 评估的测试用例

## 1. completionNonempty

要求：非空输出让 `completion.nonempty` 通过，空白输出不通过。

看到的结果：`ok` 通过，只含空白的输出不通过。

## 2. toolSuccessRate

要求：全部工具成功时 `tool.success.rate` 通过，存在失败时不通过。

看到的结果：2 次里 2 次成功通过，2 次里 1 次成功不通过。

## 3. judgeDisabledDoesNotCallModel

要求：judge 未启用时不调用模型。

看到的结果：模型完成次数是 0。

## 4. judgeEnabledUsesJudgeRoute

要求：judge 启用时，模型任务是 `judge`。

看到的结果：模型收到的任务是 `JUDGE`。

## 5. terminalRunsCompletionCheck

要求：终态之后会再跑 `completion.nonempty`，空输出记为不通过。

看到的结果：终态评估的名称是 `completion.nonempty`，并且不通过。

## 6. optionalFailureKeepsSuccess

要求：`required` 不是 true 时，评估不通过仍保持原成功状态。

看到的结果：状态仍是 `SUCCEEDED`。

## 7. requiredFailureBecomesFailed

要求：`required` 为 true 时，评估不通过把状态改成 `FAILED`。

看到的结果：状态是 `FAILED`。
