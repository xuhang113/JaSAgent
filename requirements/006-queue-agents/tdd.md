# 006 主从 agent 队列的测试用例

## 1. defaultsWhenUnset

要求：配置为 null，或映射里没有调度键时，batch-size 是 4，merge-threshold 是 0.6，queue-cap 是 8。

看到的结果：null 配置和空映射读出的 batch-size 都是 4，merge-threshold 与 0.6 的差小于 1e-9，queue-cap 都是 8。

## 2. configurationReplacesDefaults

要求：映射里写出的 batch-size、merge-threshold 和 queue-cap 替换缺省值。

看到的结果：`2`、`0.9`、`3` 分别读回为 2、0.9、3。

## 3. configuredBatchSizeLimitsTake

要求：batch-size 配成 2 时，主队列里有 3 条互不相关的工作项，第一次 `scheduleOnce` 的 `taken` 是 2，主队列还剩 1 条。

看到的结果：`taken` 是 2，`assigned` 有 2 条，主队列只剩第三条。

## 4. invalidSettingsFailConstruction

要求：batch-size 或 queue-cap 不是大于等于 1 的整数，或者 merge-threshold 不是 0 到 1 的数时，构造调度器失败，错误码为 `INTERNAL`。

看到的结果：`0`、`-3`、`2.5`、`abc`、空白的 batch-size，`1.1`、`-0.01`、`abc`、空白、`NaN` 的 merge-threshold，以及 `0`、`abc` 的 queue-cap，构造时都抛出 `AgentException`，错误码为 `INTERNAL`。

## 5. sameSessionScoresOne

要求：`sessionId` 完全相同的两条工作项，相关度是 1.0，即使 `correlationKey` 和目标文本都不同。

看到的结果：`WorkCorrelation.score` 是 1.0。

## 6. sameCorrelationKeyScoresPointNine

要求：`sessionId` 不同且 `correlationKey` 完全相同的两条工作项，相关度是 0.9，即使两边目标文本完全相同。

看到的结果：`WorkCorrelation.score` 是 0.9。

## 7. otherwiseTokenJaccard

要求：`sessionId` 和 `correlationKey` 都不同时，相关度是两边目标文本 token 集合的交集大小除以并集大小。

看到的结果：`red apple` 与 `red banana` 的相关度是 1/3，`alpha beta` 与 `gamma delta` 的相关度是 0。

## 8. blankSessionDoesNotMatch

要求：两条工作项的 `sessionId` 都是空白、`correlationKey` 不同、目标文本没有共同 token 时，相关度是 0。

看到的结果：`WorkCorrelation.score` 是 0。

## 9. blankCorrelationKeyDoesNotMatch

要求：两条工作项的 `correlationKey` 都是空白、`sessionId` 不同、目标文本没有共同 token 时，相关度是 0。

看到的结果：`WorkCorrelation.score` 是 0。

## 10. caseDifferenceDoesNotMatch

要求：`sessionId` 和 `correlationKey` 都只有大小写不同，并且目标文本没有共同 token 时，相关度是 0。

看到的结果：`WorkCorrelation.score` 是 0。

## 11. tokensIgnoreCasePunctuationAndDuplicates

要求：token 忽略大小写和标点，连续字母或数字算一个 token，重复 token 只留一个；两边都没有 token 时，相关度是 0。

看到的结果：`Red, Apple!` 与 `red apple` 的相关度是 1，`red red blue` 与 `red blue` 的相关度是 1，`你好 世界` 与 `你好` 的相关度是 0.5，空文本与 null 的相关度是 0。

## 12. blankSessionBecomesExecutionId

要求：入队时 `sessionId` 为空白的工作项，进入主队列后 `sessionId` 等于自己的 `executionId`。

看到的结果：保存后的 `sessionId` 等于 `exec-blank`。

## 13. enqueueStaysOnMainUntilScheduled

要求：`enqueue` 完成后，工作项在主 agent 队列，id 是 `main`，角色是 `MAIN`，状态是 `QUEUED`，`SubAgentRunner` 还没有被调用。

看到的结果：主队列有这一条，角色和状态如上，runner 进入次数是 0。

## 14. emptyScheduleTakesNothing

要求：主队列为空时，`scheduleOnce` 的 `taken` 是 0，`assigned` 为空，`SubAgentRunner` 没有被调用。

看到的结果：`taken` 是 0，`assigned` 为空，runner 进入次数是 0。

## 15. scheduleOnceTakesAtMostBatchSize

要求：缺省 batch-size 下，主队列有 5 条互不相关的工作项时，第一次 `scheduleOnce` 的 `taken` 是 4，主队列只剩最后一条；第二次把这最后一条取出。

看到的结果：第一次 `taken` 是 4，主队列的 `taskId` 只有第五条；第二次 `taken` 是 1，主队列为空。

## 16. sameSessionMergesToOneSubAgent

要求：同一批里 `sessionId` 相同的工作项分到同一个子 agent，这个子 agent 的 id 是 `sub-1`。

看到的结果：两条的 agent id 都是 `sub-1`，子 agent 数量是 1。

## 17. belowThresholdStaysSeparate

要求：同一批里相关度低于 merge-threshold 的工作项分到不同的子 agent。

看到的结果：`a b` 与 `a c` 的相关度低于 0.6，两条的 agent id 不同。

## 18. equalThresholdMerges

要求：相关度等于 merge-threshold 时，这两条工作项分到同一个子 agent。

看到的结果：`a b c d` 与 `a b c e` 的相关度与 0.6 的差小于 1e-9，两条的 agent id 相同。

## 19. singleLinkageMergesChain

要求：A 与 B 达到阈值、B 与 C 达到阈值、A 与 C 低于阈值时，A、B、C 进入同一个子 agent，同一批里与它们都不相关的工作项进入另一个子 agent。

看到的结果：A 与 C 的相关度低于 0.6，A、B、C 的 agent id 相同且队列顺序是 A、B、C，D 的 agent id 不同。

## 20. mergedItemsKeepEnqueueOrder

要求：合并后的工作项在子 agent 队列里的顺序与入队顺序一致。

看到的结果：同一个子 agent 的 `taskId` 顺序是 `p`、`q`、`r`。

## 21. mergedGroupAppendsToUnfinishedAgent

要求：同一批合并成一组的工作项，按顺序追加到相关且未完成、未完成条数小于 cap 的子 agent，不新建。

看到的结果：先调度的一条和后一批两条都在 `sub-1`，顺序是这三条的入队顺序，子 agent 数量是 1。

## 22. reusesHighestRelatedUnfinished

要求：两个未完成子 agent 都相关时，工作项追加到相关度更高的那个。

看到的结果：新工作项与先创建的子 agent 同 `sessionId`，与后创建的子 agent 同 `correlationKey`，它进入先创建的那个子 agent。

## 23. fullBestAgentOpensNewInsteadOfWeaker

要求：相关度最高的未完成子 agent 未完成条数已经达到 cap 时，新建子 agent；有空位但相关度更低的子 agent 接不到这条工作项。

看到的结果：queue-cap 为 2 时，新工作项的 agent id 既不是相关度 1.0 的那个，也不是相关度 0.9 的那个，子 agent 数量是 3。

## 24. overflowWithinGroupOpensNewAgent

要求：选中的子 agent 在追加过程中达到 cap 后，同一组剩下的工作项进入新建的子 agent，前两条在 `sub-1`，后两条在 `sub-2`。

看到的结果：queue-cap 为 2、batch-size 为 4 时，同一 `sessionId` 的四条里，前两条在 `sub-1`，后两条在 `sub-2`。

## 25. unrelatedUnfinishedAgentIsNotReused

要求：已有的未完成子 agent 与新工作项的相关度低于阈值时，`scheduleOnce` 新建子 agent。

看到的结果：第二条的 agent id 与第一条不同，子 agent 数量是 2。

## 26. finishedAgentIsNotReused

要求：子 agent 上的工作项都已经是 `SUCCEEDED` 或 `FAILED` 之后，再来的相关工作项会新建子 agent。

看到的结果：第一条完成后状态是 `SUCCEEDED`，同 `sessionId` 的第二条在另一个子 agent 上。

## 27. eachAgentHasSingleQueue

要求：每个 agent 只有一条队列，同一条工作项只出现在一个 agent 的队列中，主 agent 的 id 在 agent 列表里只出现一次。

看到的结果：agent id 没有重复，`main` 只出现一次，每条 `taskId` 只出现在一个队列里。

## 28. subAgentQueueIsSerial

要求：同一个子 agent 上，前一条还在 `RUNNING` 时后一条保持 `QUEUED` 且不会进入 runner；前一条完成后下一条才进入 runner，顺序与入队顺序一致。

看到的结果：第一条进入 runner 时进入次数是 1，第二条仍是 `QUEUED`；放开第一条之后第二条才进入，顺序是第一条然后第二条，同时执行的最大次数是 1。

## 29. subAgentsRunInParallel

要求：不同子 agent 的 runner 可以同时处于执行中。

看到的结果：两条互不相关的工作项都进入 runner 时，同时执行的最大次数是 2，子 agent 数量是 2。

## 30. mainAgentOnlySchedules

要求：`scheduleOnce` 分出去的工作项角色都是 `SUB`，agent id 不是 `main`；runner 接到的工作项都不在主队列里。

看到的结果：`assigned` 里每条角色都是 `SUB`，agent id 都不是 `main`；runner 进入后，这些 `taskId` 不在主队列里。

## 31. scheduleReturnsBeforeRunFinishes

要求：`enqueue` 完成时 runner 还没有被调用；`scheduleOnce` 在 runner 结束前返回，返回快照的状态是 `QUEUED`。

看到的结果：`enqueue` 完成时进入次数是 0；`scheduleOnce` 在 2 秒内返回，`taken` 是 1，快照状态是 `QUEUED`，此时 runner 的等待还没有被放开。

## 32. runnerFailureContinuesQueue

要求：runner 失败时该工作项状态变为 `FAILED`，同一子 agent 的下一条仍会执行并变为 `SUCCEEDED`。

看到的结果：两条在同一个子 agent 上，执行顺序是失败的那条然后成功的那条，状态分别是 `FAILED` 和 `SUCCEEDED`。

## 33. missingRunnerFailsConstruction

要求：没有 `SubAgentRunner` 时构造失败，错误码为 `INTERNAL`。

看到的结果：构造抛出 `AgentException`，错误码为 `INTERNAL`。

## 34. duplicateOrBlankIdentityRejected

要求：`taskId` 为空白、`executionId` 为空白，或者 `taskId` 重复时，入队失败，错误码为 `INTERNAL`，已经入队的工作项保持不变。

看到的结果：这三种入队的异常链里都有 `AgentException` 且错误码为 `INTERNAL`；重复入队之后主队列仍只有原来那一条。

## 35. concurrentScheduleAssignsEachItemOnce

要求：两次并发的 `scheduleOnce` 不会把同一条工作项分出去两次，取出的条数之和等于入队条数。

看到的结果：两次 `taken` 之和是 4，`assigned` 里的 `taskId` 有 4 个且互不相同，主队列为空。

## 36. coreStaysOnApi

要求：`jasagent-core` 的构建文件依赖 `jasagent-api`，不依赖 Spring、Spring AI、langgraph4j、LangChain4j、workflow、tool、plugin 或 web；主源码也不引用这些实现包。

看到的结果：`pom.xml` 包含 `jasagent-api`，构建文件和 `src/main/java` 下的源码都不包含这些依赖或包名，并且主源码至少有一个 Java 文件。
