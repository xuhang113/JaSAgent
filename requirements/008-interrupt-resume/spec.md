# 008 中止与原 id 恢复

## 这次要改什么

现状是执行可以靠 langgraph4j 的检查点往下走。`jasagent-workflow` 里的 `interrupt` 节点会在该节点前停下，并用原来的 executionId 从检查点继续。那是图上的停顿点，不是执行过程中取消正在跑的节点。`jasagent-interrupt` 只有模块坐标，没有实现。`jasagent-api` 的 `RunControl` 已经声明 `interrupt` 和 `resume`，`ExecutionService` 也已经声明这两个方法。`AgentTracer.startChild` 已经带 traceId 和父 span id。`WorkflowEngine` 只有 `execute`。持久化模块用 `SQLiteSaverV2` 或 `PostgresSaverV2` 保存检查点，不把图状态写进自建的 `node_checkpoint` 表。

这次补上执行过程中的中止和按原 executionId 恢复。中止取消当前节点返回的 `CompletionStage`，不把这个未完成节点记成成功，停在最后一次已经提交成功的检查点，状态改为 `SUSPENDED`。`resume` 用原来的 executionId 从那个检查点继续。状态不是 `SUSPENDED` 时，`resume` 返回当前快照，不启动节点。把控制对象关掉，再用同一个检查点文件打开新的控制对象，仍然可以用原 executionId 恢复。恢复之后新开的 span 继续使用检查点里保存的 traceId，不再申请新的根 trace。检查点仍然只经过 langgraph4j 的 saver。

不改 `jasagent-web`，不改工作流步骤机、持久化、追踪、OpenTelemetry、控制器、命令行和应用组装。不新建检查点表，也不用手写的读写代替 saver 的 `put` 和 `get`。页面和 REST 这次不实现，以后由它们调用 `RunControl`。

## 模块

实现放在 `jasagent-interrupt`，类实现 `RunControl`。Maven 依赖只指向 `jasagent-api`，另外使用 langgraph4j 的 core、sqlite-saver 和 postgres-saver。不依赖 Spring，也不依赖其他业务模块。

`jasagent-api` 这次不增加端口。中止和恢复沿用已有的 `RunControl`。恢复后的 span 沿用已有的 `AgentTracer.startChild`，把保存的 traceId 和根 span id 传进去。执行快照沿用已有的 `ExecutionSnapshot`。

## 改动地方

`CheckpointRunControl.open` 打开检查点。默认 saver 是 `SQLiteSaverV2`，文件是 `<workspace>/jasagent/data/checkpoints.db`。`JASAGENT_JDBC_URL` 以 `jdbc:postgresql:` 开头时改用 `PostgresSaverV2`，主机、端口和库名从该 URL 解析，用户名取 `JASAGENT_JDBC_USER`，密码只取 `JASAGENT_JDBC_PASSWORD`。打不开 saver 时错误码为 `PERSIST`，并且不创建默认的 SQLite 文件。

一次运行由按顺序排列的节点组成。节点标识不能为空、不能重复，也不能是 `__START__` 或 `__END__`。节点的 `run` 立刻返回 `CompletionStage`，中止取消的就是这个阶段。`start` 先调用 `AgentTracer.startRoot`，把返回的 traceId 和根 span id 放进检查点状态，再在进入第一个节点之前用 saver 提交检查点。线程 id 使用 executionId。每个节点成功之后、进入下一节点之前，再提交一次检查点。提交失败时错误码为 `PERSIST`，下一节点不会执行。

检查点状态保存 userId、sessionId、workspace、status、traceId、rootSpanId、output 和 errorCode。成功节点的输出按完成顺序用换行接在 output 后面。未完成的节点不写入 output。不传 sessionId，或 sessionId 为空白时，sessionId 等于 executionId。

中止只处理状态为 `RUNNING` 的执行。它取消当前节点的 `CompletionStage`，再提交一条检查点：nodeId 和 nextNodeId 仍是最后一次成功检查点的位置，status 为 `SUSPENDED`，output 不包含被取消的节点。随后交给 `TraceWriter` 一条 type 为 `INTERRUPT` 的事件。状态不是 `RUNNING` 时，中止不取消节点，也不改状态，返回当前快照。另一用户中止或恢复时错误码为 `AUTH`，原状态保持不变。没有这条执行时错误码为 `PERSIST`，不返回 null。

`resume` 从 saver 读出原 executionId 的最新检查点。状态不是 `SUSPENDED` 时返回当前快照，不调用节点，也不调用 `startRoot`。状态是 `SUSPENDED` 时，用同一 executionId 从 nextNodeId 继续，被取消的节点会再次执行。继续之前不调用 `startRoot`。每个再次执行的节点通过 `startChild` 打开 span，名称是 `workflow.node`，traceId 和父 span id 用检查点里保存的值。恢复开始时交给 `TraceWriter` 一条 type 为 `RESUME` 的事件。节点全部成功后状态为 `SUCCEEDED`。节点以 `AgentException` 失败时状态为 `FAILED`，错误码沿用该异常；其他失败的错误码为 `INTERNAL`。失败节点不写入 output，后面的节点不执行。

阻塞的 saver 调用放进 `VirtualIo` 使用的虚拟线程执行器，对外返回 `CompletionStage`。

## 数据库

不建业务表，也不建 `node_checkpoint`。检查点进入 langgraph4j saver 自己的库。默认是工作区下的 SQLite 文件，表是 saver 的 `LG4JCheckpoint`。JDBC URL 为 PostgreSQL 时进入 `PostgresSaverV2`。执行状态和 traceId 放在检查点状态里，新的控制对象打开同一个 saver 后仍能读到。

## 中间件

不使用消息中间件，不监听 HTTP，不启动 Spring。

## 测试要求

1. 当前节点的 `CompletionStage` 被中止取消，该阶段 `isCancelled` 为真，后面的节点没有开始。
2. 中止后最新检查点的 nodeId 仍是上一成功节点，nextNodeId 仍是被取消的节点，output 不包含被取消节点的结果，saver 中的线程名是原来的 executionId。
3. 中止后的状态是 `SUSPENDED`，executionId 不变。
4. 对 `SUSPENDED` 执行调用 resume 后，executionId 不变，被取消的节点会再执行，后续节点会执行，状态变为 `SUCCEEDED`。
5. 对 `SUCCEEDED` 执行调用 resume，返回的快照与当前快照一致，节点不会再执行。
6. 对 `FAILED` 执行调用 resume，返回的状态仍是 `FAILED`，错误码不变，尚未执行的节点不会执行。
7. 对 `RUNNING` 执行调用 resume，返回的状态仍是 `RUNNING`，当前节点的阶段不会被取消，节点不会因此多执行一次。
8. 关掉控制对象后，用同一个检查点文件构造新的控制对象，resume 仍使用原 executionId 继续，已成功的节点不会重跑。
9. 新的控制对象恢复时，`startChild` 收到的 traceId 等于原来 `startRoot` 保存的 traceId，并且这次恢复不会调用 `startRoot`。
10. 默认检查点的 saver 是 `SQLiteSaverV2`，文件为工作区下的 `jasagent/data/checkpoints.db`，该文件中有 `LG4JCheckpoint` 行，没有 `node_checkpoint` 表。
11. JDBC URL 以 `jdbc:postgresql:` 开头时打开 `PostgresSaverV2`，错误码为 `PERSIST`，并且不创建默认的 SQLite 检查点文件。
12. 另一用户调用 resume 时失败，错误码为 `AUTH`，执行状态仍是 `SUSPENDED`。
13. 对不存在的 executionId 调用 resume 时失败，错误码为 `PERSIST`，结果不是 null。
14. 中止会交给 `TraceWriter` 一条 type 为 `INTERRUPT` 的事件，executionId 是原来的那一个。
15. 从 `SUSPENDED` 恢复会交给 `TraceWriter` 一条 type 为 `RESUME` 的事件，executionId 是原来的那一个。
16. 不传 sessionId 时，快照里的 sessionId 等于 executionId。
17. 对已经 `SUCCEEDED` 的执行再调用中止，返回的状态仍是 `SUCCEEDED`。

## 还没定

无。节点输出按成功顺序用换行拼接。检查点文件名使用 `checkpoints.db`。PostgreSQL 的主机、端口和库名从 JDBC URL 解析，用户名和密码只取环境变量。
