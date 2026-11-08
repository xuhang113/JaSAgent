# 006 队列与主子代理的测试用例

## 1. defaultBatchSizeIsFour

要求：默认 `batch-size` 是 4。

看到的结果：默认配置的 `batchSize` 等于 4。

## 2. scheduleTakesOnlyBatch

要求：一次调度最多取出 `batch-size` 条，多出来的仍留在主队列。

看到的结果：放入 6 条互不相似的工作后，主队列还剩 2 条。

## 3. sameSessionSimilarityIsOne

要求：`sessionId` 相同的相关度是 1.0。

看到的结果：相关度等于 1.0。

## 4. sameCorrelationSimilarityIsPointNine

要求：`correlationKey` 相同且 session 不同的相关度是 0.9。

看到的结果：相关度等于 0.9。

## 5. otherwiseTokenJaccard

要求：其余情况用目标文本的 token Jaccard。

看到的结果：`red cat` 和 `red dog` 的相关度等于三分之一。

## 6. mergeAtThreshold

要求：相关度达到 0.6 时，一个批次里的两条工作合并成一条。

看到的结果：同一个 session 的两条工作调度后只产生一个子代理任务，目标文本同时包含两边的原文。

## 7. appendToUnfinishedSubagent

要求：合并后的工作项追加到相关且未完成的子代理，而不是再新建一个。

看到的结果：第二条相关工作调度后，子代理数量仍是 1。

## 8. capCreatesNewSubagent

要求：该子代理未完成数量达到 8 时，下一条相关工作新建子代理。

看到的结果：第 9 条相关工作调度后，子代理数量是 2。

## 9. mainAgentDoesNotExecuteWorkflow

要求：主代理不会把自己的角色送进工作流引擎。

看到的结果：引擎记录到的角色里没有 `MAIN`。

## 10. subagentQueueIsSerial

要求：同一个子代理上的第二个任务要等第一个结束后才开始。

看到的结果：第一个任务还堵住时，引擎只被调用了 1 次；放开之后才出现第 2 次。

## 11. subagentsRunInParallel

要求：不同子代理的任务会同时开始。

看到的结果：两个任务都已开始时，两边都还没有结束。

## 12. missingSessionBecomesExecutionId

要求：不传 `sessionId` 时，入队后的 `sessionId` 等于 `executionId`。

看到的结果：调度后工作项上的 `sessionId` 等于它的 `executionId`。

## 13. submitReturnsBeforeCompletion

要求：`submit` 返回时，被堵住的执行状态还不是 `SUCCEEDED`。

看到的结果：`submit` 已完成并返回执行编号，此时状态不是 `SUCCEEDED`。
