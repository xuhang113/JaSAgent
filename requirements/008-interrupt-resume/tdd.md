# 008 中止与原 id 恢复的测试用例

## 1. interruptCancelsCurrentStage

要求：当前节点的 `CompletionStage` 被中止取消，该阶段 `isCancelled` 为真，后面的节点没有开始。

看到的结果：被中止的阶段 `isCancelled` 为真。后续节点的执行次数是 0。

## 2. interruptStopsAtLastCheckpoint

要求：中止后最新检查点的 nodeId 仍是上一成功节点，nextNodeId 仍是被取消的节点，output 不包含被取消节点的结果，saver 中的线程名是原来的 executionId。

看到的结果：最新检查点的 nodeId 是上一成功节点，nextNodeId 是被取消的节点，状态里的 output 只有上一节点的输出。`LG4JThread.thread_name` 等于原来的 executionId。

## 3. suspendedStatusAfterInterrupt

要求：中止后的状态是 `SUSPENDED`，executionId 不变。

看到的结果：中止返回的快照和这次运行完成时的快照，状态都是 `SUSPENDED`，executionId 都是提交时的那一个。

## 4. resumeContinuesWithSameExecutionId

要求：对 `SUSPENDED` 执行调用 resume 后，executionId 不变，被取消的节点会再执行，后续节点会执行，状态变为 `SUCCEEDED`。

看到的结果：resume 返回的 executionId 与提交时相同，状态是 `SUCCEEDED`。已成功节点只执行了一次，被取消节点在恢复时又执行了一次，后续节点执行了一次。

## 5. resumeSucceededReturnsCurrentSnapshot

要求：对 `SUCCEEDED` 执行调用 resume，返回的快照与当前快照一致，节点不会再执行。

看到的结果：resume 返回的快照与完成后的快照相等，节点执行次数仍是 1。

## 6. resumeFailedReturnsCurrentSnapshot

要求：对 `FAILED` 执行调用 resume，返回的状态仍是 `FAILED`，错误码不变，尚未执行的节点不会执行。

看到的结果：resume 返回的状态是 `FAILED`，错误码是 `TOOL`，executionId 不变。失败节点之后的节点执行次数是 0。

## 7. resumeRunningReturnsCurrentSnapshot

要求：对 `RUNNING` 执行调用 resume，返回的状态仍是 `RUNNING`，当前节点的阶段不会被取消，节点不会因此多执行一次。

看到的结果：resume 返回的状态是 `RUNNING`，当前阶段 `isCancelled` 为假，此时节点执行次数是 1。阶段完成后执行次数仍是 1，状态变为 `SUCCEEDED`。

## 8. resumeAfterProcessRestart

要求：关掉控制对象后，用同一个检查点文件构造新的控制对象，resume 仍使用原 executionId 继续，已成功的节点不会重跑。

看到的结果：新控制对象 resume 返回的 executionId 与原来相同，状态是 `SUCCEEDED`。新对象上已成功节点的执行次数是 0，被中止的节点和后续节点各执行一次。

## 9. resumedSpansUseSavedTraceId

要求：新的控制对象恢复时，`startChild` 收到的 traceId 等于原来 `startRoot` 保存的 traceId，并且这次恢复不会调用 `startRoot`。

看到的结果：恢复后的快照 traceId 等于第一次 `startRoot` 使用的 traceId。新控制对象的 `startRoot` 次数是 0，它的每个 `startChild` 都收到这个 traceId。

## 10. checkpointsStayOnLanggraphSaver

要求：默认检查点的 saver 是 `SQLiteSaverV2`，文件为工作区下的 `jasagent/data/checkpoints.db`，该文件中有 `LG4JCheckpoint` 行，没有 `node_checkpoint` 表。

看到的结果：saver 的类型是 `SQLiteSaverV2`，文件路径是工作区下的 `jasagent/data/checkpoints.db`。该库中 `LG4JCheckpoint` 行数大于 0，表名里没有 `node_checkpoint`。

## 11. postgresUrlSelectsPostgresSaver

要求：JDBC URL 以 `jdbc:postgresql:` 开头时打开 `PostgresSaverV2`，错误码为 `PERSIST`，并且不创建默认的 SQLite 检查点文件。

看到的结果：打开时抛出 `AgentException`，错误码是 `PERSIST`，异常链中有 `PostgresSaverV2`。工作区下不存在 `jasagent/data/checkpoints.db`。

## 12. otherUserCannotResume

要求：另一用户调用 resume 时失败，错误码为 `AUTH`，执行状态仍是 `SUSPENDED`。

看到的结果：另一用户的 resume 抛出 `AgentException`，错误码是 `AUTH`。原执行的最新检查点状态仍是 `SUSPENDED`。

## 13. missingResumeFails

要求：对不存在的 executionId 调用 resume 时失败，错误码为 `PERSIST`，结果不是 null。

看到的结果：resume 抛出 `AgentException`，错误码是 `PERSIST`，异常上的 executionId 就是请求的那一个。

## 14. interruptEventRecorded

要求：中止会交给 `TraceWriter` 一条 type 为 `INTERRUPT` 的事件，executionId 是原来的那一个。

看到的结果：`TraceWriter` 收到的事件里有一条 type 为 `INTERRUPT`，executionId 等于原来的执行。

## 15. resumeEventRecorded

要求：从 `SUSPENDED` 恢复会交给 `TraceWriter` 一条 type 为 `RESUME` 的事件，executionId 是原来的那一个。

看到的结果：resume 之后，`TraceWriter` 收到的事件里有一条 type 为 `RESUME`，executionId 等于原来的执行。

## 16. blankSessionIdUsesExecutionId

要求：不传 sessionId 时，快照里的 sessionId 等于 executionId。

看到的结果：start 返回的快照里，sessionId 等于这次的 executionId。

## 17. interruptOfFinishedReturnsSnapshot

要求：对已经 `SUCCEEDED` 的执行再调用中止，返回的状态仍是 `SUCCEEDED`。

看到的结果：中止返回的状态是 `SUCCEEDED`，executionId 和 output 与完成后的快照相同。
