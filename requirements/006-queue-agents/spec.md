# 006 主从 agent 队列

## 这次要改什么

现状是父工程已经登记 `jasagent-core`，这个模块只有构建文件，依赖 `jasagent-api`，没有调度实现。api 里已经有 `AgentScheduler`、`ScheduleBatch`、`WorkItem`、`TaskSnapshot` 和 `AgentRole`。`AgentScheduler` 能入队，也能做一次调度。`WorkflowEngine.execute` 接收的是 `WorkflowRequest`，里面要有 `userId`、`workspace` 和 `WorkflowDefinition`。`WorkItem` 没有这三项。workflow 的图、检查点和 ReAct 已经在 `jasagent-workflow` 里，这次不改那里。

这次让主 agent 只做调度。工作项先进入主 agent 的队列。主 agent 每次最多取出 `jasagent.scheduler.batch-size` 条，缺省 4。`sessionId` 相同，相关度是 1.0。`sessionId` 不同而 `correlationKey` 相同，相关度是 0.9。两个都不相同，就用目标文本的 token Jaccard。相关度达到 `jasagent.scheduler.merge-threshold` 就合并，缺省 0.6。合并后的工作项优先追加给相关且未完成的子 agent。该子 agent 的未完成条数达到 `jasagent.scheduler.queue-cap` 就新建子 agent，缺省 8。每个 agent 一条队列。子 agent 队列里串行，子 agent 之间并行。主 agent 不调用领域工具，也不担任领域 ReAct。

不改登录、模型路由、持久化、workflow 图、工具、插件、通行证、RAG、MCP、评估、中止恢复、命令行和页面。不做 007，不做 012。不改 `jasagent-web`。除了下面这条调度端口，不改 api 里已有的类型。

## 模块

`jasagent-core` 实现队列和调度，Maven 依赖只指向 `jasagent-api`。测试依赖 JUnit。父工程已经包含这个模块，这次不改父 POM。

`jasagent-api` 增加 `SubAgentRunner`。这是调度真正缺的端口：子 agent 要执行工作项，现有的 `WorkflowEngine` 吃不进 `WorkItem`，core 又不能依赖 workflow 的实现类。组装模块以后把 `WorkflowEngine` 适配成这个端口。这次不改 `jasagent-app`。

## 改动地方

`SubAgentRunner.run` 接收一条 `WorkItem`，返回 `CompletionStage<Void>`。调度器构造时必须有这个端口。没有就失败，错误码是 `INTERNAL`。

配置从字符串映射读取。映射是 null，或者没有对应的键，就用缺省值。`jasagent.scheduler.batch-size` 缺省 4，`jasagent.scheduler.merge-threshold` 缺省 0.6，`jasagent.scheduler.queue-cap` 缺省 8。键存在时，batch-size 和 queue-cap 必须是大于等于 1 的整数，merge-threshold 必须是 0 到 1 的数，含两端。空白、小数形式的条数、NaN 和其他无法解析的值，构造失败，错误码是 `INTERNAL`。

`WorkCorrelation.score` 按这个顺序计算，命中就返回，不取几条规则的最大值。两边 `sessionId` 都非空白且完全相同，返回 1.0。否则两边 `correlationKey` 都非空白且完全相同，返回 0.9。否则计算目标文本的 token Jaccard。比较区分大小写，空白字符串不算相同。

token 用 `Locale.ROOT` 转成小写。连续的 Unicode 字母或数字是一个 token，标点和空白把它们切开。同一个集合里重复的 token 只留一个。Jaccard 是交集大小除以并集大小。两边都没有 token 时，Jaccard 是 0。目标文本是 null 时，当作没有 token。

`enqueue` 把工作项放进主 agent 队列后，返回的 `CompletionStage` 就完成。它不等待调度，也不调用 `SubAgentRunner`。`taskId` 或 `executionId` 是 null 或空白、工作项本身是 null、或者 `taskId` 已经入过队，这次入队失败，错误码是 `INTERNAL`，已经在队列里的工作项保持原样。`sessionId` 是 null 或空白时，保存下来的 `sessionId` 改成该工作项的 `executionId`。相关度使用保存后的值。主 agent 的 id 是 `main`，角色是 `MAIN`，入队后的状态是 `QUEUED`。

`scheduleOnce` 从主队列头部最多取出 batch-size 条。主队列是空的时候，`taken` 是 0，`assigned` 是空的，不调用 runner。`ScheduleBatch.taken` 等于这次取出的条数，没取出的仍留在主队列，顺序不变。同一次取出的工作项按单链接合并：任意两条的相关度大于等于 merge-threshold，就通过并查集进同一组，包括只经过中间项才连上的情况。组按组内最早一条的原顺序处理，组内也保持原顺序。

放置一组时，在未完成的子 agent 里找相关度最高的一个。相关度是这组每一条和该子 agent 每一条未完成工作项的分数的最大值，并且要大于等于 merge-threshold。分数相同就选更早创建的子 agent。没有合格的子 agent，或者选中的那个未完成条数已经大于等于 cap，就新建子 agent，不改派给相关度更低的子 agent。向选中的子 agent 追加时，未完成条数一旦达到 cap，同一组剩下的工作项交给一个新建的子 agent，仍然不改派给其他已有子 agent。

子 agent 的 id 是 `sub-` 加上从 1 开始的序号。分派出去的角色是 `SUB`。`scheduleOnce` 返回的快照是分派当时的状态 `QUEUED`。这个方法在 runner 结束之前就完成。

未完成指状态不是 `SUCCEEDED`、`FAILED`、`CANCELLED`、`SUSPENDED`。cap 只数未完成条数。已经终态的工作项留在该 agent 的队列记录里，供观察，不占 cap，也不再参加相关度。子 agent 上没有未完成工作项时，它视为完成，后来的相关工作项新建子 agent。

每个 agent 只有一条队列。一条工作项只出现在一个 agent 的队列里。子 agent 开始执行后，状态从 `QUEUED` 变成 `RUNNING`，同一条队列里下一条仍是 `QUEUED`。前一条的 runner 没完成时，下一条不会进入 runner。完成顺序和入队顺序一致。runner 正常完成则状态为 `SUCCEEDED`。runner 抛错或返回的阶段以异常完成，则该条为 `FAILED`，同一条队列的下一条继续执行。不同子 agent 的 runner 可以同时处于执行中。主 agent 不调用 runner。并发的两次 `scheduleOnce` 不会把同一条工作项分出去两次。

队列操作放在内存里。`enqueue` 和 `scheduleOnce` 通过 `VirtualIo` 返回 `CompletionStage`。runner 在虚拟线程上执行，调度方法不 join 它。

## 数据库

这次不建表，不连接 JDBC，也不写持久化模块里已有的 `agent` 和 `task` 表。队列只在当前进程的内存里。

## 中间件

不使用消息中间件，不监听 HTTP，不启动 Spring。不使用 langgraph4j 和 LangChain4j。

## 测试要求

1. 配置为 null，或映射里没有调度键时，batch-size 是 4，merge-threshold 是 0.6，queue-cap 是 8。
2. 映射里写出的 batch-size、merge-threshold 和 queue-cap 替换缺省值。
3. batch-size 配成 2 时，主队列里有 3 条互不相关的工作项，第一次 `scheduleOnce` 的 `taken` 是 2，主队列还剩 1 条。
4. batch-size 或 queue-cap 不是大于等于 1 的整数，或者 merge-threshold 不是 0 到 1 的数时，构造调度器失败，错误码为 `INTERNAL`。
5. `sessionId` 完全相同的两条工作项，相关度是 1.0，即使 `correlationKey` 和目标文本都不同。
6. `sessionId` 不同且 `correlationKey` 完全相同的两条工作项，相关度是 0.9，即使两边目标文本完全相同。
7. `sessionId` 和 `correlationKey` 都不同时，相关度是两边目标文本 token 集合的交集大小除以并集大小。
8. 两条工作项的 `sessionId` 都是空白、`correlationKey` 不同、目标文本没有共同 token 时，相关度是 0。
9. 两条工作项的 `correlationKey` 都是空白、`sessionId` 不同、目标文本没有共同 token 时，相关度是 0。
10. `sessionId` 和 `correlationKey` 都只有大小写不同，并且目标文本没有共同 token 时，相关度是 0。
11. token 忽略大小写和标点，连续字母或数字算一个 token，重复 token 只留一个；两边都没有 token 时，相关度是 0。
12. 入队时 `sessionId` 为空白的工作项，进入主队列后 `sessionId` 等于自己的 `executionId`。
13. `enqueue` 完成后，工作项在主 agent 队列，id 是 `main`，角色是 `MAIN`，状态是 `QUEUED`，`SubAgentRunner` 还没有被调用。
14. 主队列为空时，`scheduleOnce` 的 `taken` 是 0，`assigned` 为空，`SubAgentRunner` 没有被调用。
15. 缺省 batch-size 下，主队列有 5 条互不相关的工作项时，第一次 `scheduleOnce` 的 `taken` 是 4，主队列只剩最后一条；第二次把这最后一条取出。
16. 同一批里 `sessionId` 相同的工作项分到同一个子 agent，这个子 agent 的 id 是 `sub-1`。
17. 同一批里相关度低于 merge-threshold 的工作项分到不同的子 agent。
18. 相关度等于 merge-threshold 时，这两条工作项分到同一个子 agent。
19. A 与 B 达到阈值、B 与 C 达到阈值、A 与 C 低于阈值时，A、B、C 进入同一个子 agent，同一批里与它们都不相关的工作项进入另一个子 agent。
20. 合并后的工作项在子 agent 队列里的顺序与入队顺序一致。
21. 同一批合并成一组的工作项，按顺序追加到相关且未完成、未完成条数小于 cap 的子 agent，不新建。
22. 两个未完成子 agent 都相关时，工作项追加到相关度更高的那个。
23. 相关度最高的未完成子 agent 未完成条数已经达到 cap 时，新建子 agent；有空位但相关度更低的子 agent 接不到这条工作项。
24. 选中的子 agent 在追加过程中达到 cap 后，同一组剩下的工作项进入新建的子 agent，前两条在 `sub-1`，后两条在 `sub-2`。
25. 已有的未完成子 agent 与新工作项的相关度低于阈值时，`scheduleOnce` 新建子 agent。
26. 子 agent 上的工作项都已经是 `SUCCEEDED` 或 `FAILED` 之后，再来的相关工作项会新建子 agent。
27. 每个 agent 只有一条队列，同一条工作项只出现在一个 agent 的队列中，主 agent 的 id 在 agent 列表里只出现一次。
28. 同一个子 agent 上，前一条还在 `RUNNING` 时后一条保持 `QUEUED` 且不会进入 runner；前一条完成后下一条才进入 runner，顺序与入队顺序一致。
29. 不同子 agent 的 runner 可以同时处于执行中。
30. `scheduleOnce` 分出去的工作项角色都是 `SUB`，agent id 不是 `main`；runner 接到的工作项都不在主队列里。
31. `enqueue` 完成时 runner 还没有被调用；`scheduleOnce` 在 runner 结束前返回，返回快照的状态是 `QUEUED`。
32. runner 失败时该工作项状态变为 `FAILED`，同一子 agent 的下一条仍会执行并变为 `SUCCEEDED`。
33. 没有 `SubAgentRunner` 时构造失败，错误码为 `INTERNAL`。
34. `taskId` 为空白、`executionId` 为空白，或者 `taskId` 重复时，入队失败，错误码为 `INTERNAL`，已经入队的工作项保持不变。
35. 两次并发的 `scheduleOnce` 不会把同一条工作项分出去两次，取出的条数之和等于入队条数。
36. `jasagent-core` 的构建文件依赖 `jasagent-api`，不依赖 Spring、Spring AI、langgraph4j、LangChain4j、workflow、tool、plugin 或 web；主源码也不引用这些实现包。

## 还没定

无。把 `SubAgentRunner` 接到 `WorkflowEngine`、队列写入 JDBC、`agent.schedule` 的 span、`AsyncGuard`、中止后改成 `CANCELLED` 或 `SUSPENDED`，以及页面上的队列，都留给后面的组装、008 和 012。这次如果任务状态已经是 `CANCELLED` 或 `SUSPENDED`，把它当成已完成。
