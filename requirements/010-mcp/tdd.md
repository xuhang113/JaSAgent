# 010 MCP 的测试用例

## 1. toolNameUsesServerAndTool

要求：远端工具登记名是 `mcp_<server>_<tool>`。

看到的结果：登记名等于 `mcp_demo_echo`。

## 2. callAcquiresPass

要求：调用 MCP 工具前会申请通行证。

看到的结果：通行证申请次数是 1，请求里的工具名等于 `mcp_demo_echo`。

## 3. failedServerDoesNotStopRegistration

要求：一个 server 连接失败时，其余工具仍然登记，登记方法不抛出。

看到的结果：失败 server 出现在错误列表里，成功 server 的工具仍然存在。

## 4. denyDoesNotCallRemote

要求：通行证为 `DENY` 时不调用远端。

看到的结果：远端调用次数是 0。
