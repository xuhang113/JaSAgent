# 010 MCP 客户端的测试用例

## 1. demoEchoRegistersAsMcpDemoEcho

要求：服务器标识 `demo` 上的远端工具 `echo` 登记后的名称是 `mcp_demo_echo`，描述与远端一致，并且不是内部工具。

看到的结果：唯一工具的名称等于 `mcp_demo_echo`，描述等于 `Echoes text`，`internal` 为假。

## 2. acquireRunsBeforeRemoteCall

要求：调用前先执行 api 的 `PassGate.acquire`，此时远端还没有收到 `tools/call`；请求里的工具名是登记名，并带有 `userId`、`executionId`，`pluginId` 为空。

看到的结果：申请通行证时远端 `tools/call` 次数为 0，`toolName` 等于 `mcp_demo_echo`，`userId` 等于 `user-a`，`executionId` 等于 `exec-a`，`pluginId` 为空。

## 3. argumentsHashIsSha256Hex

要求：`argumentsHash` 等于传入的参数 JSON 字符串按 UTF-8 编码后的 SHA-256 小写十六进制。

看到的结果：哈希等于对 `{"k":"v"}` 独立计算的 SHA-256 小写十六进制。

## 4. denyDoesNotSendToolsCall

要求：决定为 `DENY` 时不发送 `tools/call`，调用结果的 `success` 为假。

看到的结果：远端 `tools/call` 次数为 0，结果对象不是 null，`success` 为假。

## 5. allowSendsOriginalNameAndReturnsText

要求：决定为 `ALLOW` 时，`tools/call` 使用远端原名 `echo`，结果文本等于远端文本，并且 `success` 为真。

看到的结果：远端记录的工具名等于 `echo`，结果文本等于 `pong`，`success` 为真。

## 6. skippedSendsToolsCall

要求：决定为 `SKIPPED` 时仍然发送 `tools/call`。

看到的结果：远端 `tools/call` 次数为 1。

## 7. unreachableServerDoesNotBlockOpen

要求：一个服务器连不上时，`open` 仍然成功，另一个服务器的工具已经登记。

看到的结果：`open` 返回提供者，工具名中有 `mcp_up_echo`。

## 8. unreachableServerRegistersNoTools

要求：连不上的服务器出现在不可达列表里，并且不会留下以它的标识拼出的工具。

看到的结果：不可达列表包含 `gone`，没有任何工具名以 `mcp_gone_` 开头。

## 9. duplicateNameFailsOpenWithMcp

要求：两个已经连通的服务器生成同一个登记名时，`open` 失败，错误码是 `MCP`。

看到的结果：异常的错误码等于 `MCP`。

## 10. jsonRpcErrorFailsWithMcp

要求：远端返回 JSON-RPC error 时，这次调用失败，错误码是 `MCP`，不会得到 null 结果。

看到的结果：抛出的异常不是 null，错误码等于 `MCP`。

## 11. callReturnsIncompleteStage

要求：`call` 返回的 `CompletionStage` 在远端尚未回应时还没有完成。

看到的结果：远端仍在等待时，返回的阶段 `isDone` 为假；放开远端后结果文本等于 `pong`。

## 12. acquireRunsOnVirtualThread

要求：执行这次调用并申请通行证时，当前线程是虚拟线程。

看到的结果：`acquire` 被调用时 `Thread.isVirtual` 为真，并且随后结果文本等于 `pong`。

## 13. sourcesDoNotReferenceAgentpassOrSpring

要求：`jasagent-mcp` 的生产源码和 pom 不引用 `jasagent-agentpass`，pom 也不依赖 Spring 或 Spring AI。

看到的结果：pom 和生产源码都不包含 `jasagent-agentpass`、`springframework` 和 `spring-ai`，并且生产源码目录里至少有一个 Java 文件。

## 14. stdioAllowReturnsRemoteText

要求：stdio 传输的服务器能登记 `mcp_pipe_echo`，`ALLOW` 之后能取回远端文本。

看到的结果：工具名等于 `mcp_pipe_echo`，结果文本等于 `stdio-pong`，`success` 为真。

## 15. remoteErrorFlagReturnsTextWithoutThrowing

要求：远端把 `isError` 标为真时不抛异常，`success` 为假，文本仍然返回。

看到的结果：结果文本等于 `bad`，`success` 为假。

## 16. definitionKeepsInputSchema

要求：模型工具定义的名称是登记名，`parametersJson` 保留远端 `inputSchema` 里的对象类型。

看到的结果：定义名称等于 `mcp_demo_echo`，描述等于 `Echoes text`，`parametersJson` 包含 `object`。
