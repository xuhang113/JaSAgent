# 008 中止与恢复

## 这次要改什么

现状是执行可以跑完，但还不能在节点中途停下再按原执行编号继续。这次补上中止、恢复，以及进程重启后从 JDBC 检查点继续。不改页面按钮，那留给命令行和页面需求。

## 模块

这次新增 `jasagent-interrupt`，并让 `jasagent-app` 组装持久化和中止服务做重启测试。`jasagent-interrupt` 只依赖 `jasagent-api`。`jasagent-app` 是组装点，可以依赖实现模块。

## 改动地方

中止时取消当前节点的 `CompletionStage`，状态改为 `SUSPENDED`，检查点保持最后一次成功提交的节点。`resume` 使用原来的 `executionId`。不是 `SUSPENDED` 时，`resume` 返回当前快照，不重新执行。恢复后的快照继续使用保存的 traceId。

JDBC 检查点写入 `node_checkpoint`。关闭存储再打开后，仍能按原编号恢复，并跑完剩余节点。

## 数据库

这次使用已有的 `execution` 和 `node_checkpoint`，不新增表。

## 中间件

这次不启动 HTTP。重启测试使用本机 H2 文件。

## 测试要求

1. 中止后状态是 `SUSPENDED`。
2. 中止后检查点仍是最后一次成功的节点。
3. 用原来的 `executionId` 可以 `resume`，并得到恢复后的输出。
4. 不是 `SUSPENDED` 时，`resume` 返回当前快照且不再次执行。
5. 关闭 JDBC 后再打开，原 `executionId` 仍能 `resume` 到 `SUCCEEDED`。
6. 恢复后的 traceId 与中止前保存的 traceId 相同。

## 还没定

无。
