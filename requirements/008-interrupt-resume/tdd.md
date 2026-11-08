# 008 中止与恢复的测试用例

## 1. interruptSetsSuspended

要求：中止后状态是 `SUSPENDED`。

看到的结果：中止返回的状态等于 `SUSPENDED`。

## 2. interruptKeepsCheckpoint

要求：中止后检查点仍是最后一次成功的节点。

看到的结果：检查点节点标识仍是 `find`。

## 3. resumeUsesOriginalId

要求：用原来的 `executionId` 可以 `resume`，并得到恢复后的输出。

看到的结果：恢复后的执行编号不变，输出等于 `done`。

## 4. resumeOfActiveReturnsSnapshot

要求：不是 `SUSPENDED` 时，`resume` 返回当前快照且不再次执行。

看到的结果：状态仍是 `SUCCEEDED`，恢复动作没有被调用。

## 5. reopenJdbcThenResume

要求：关闭 JDBC 后再打开，原 `executionId` 仍能 `resume` 到 `SUCCEEDED`。

看到的结果：新打开的存储上恢复后的状态是 `SUCCEEDED`，执行编号与关闭前相同。

## 6. resumeKeepsTraceId

要求：恢复后的 traceId 与中止前保存的 traceId 相同。

看到的结果：恢复快照的 traceId 等于中止前写入的 traceId。
