# 2026-11-08 MCP 客户端

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：用 JSON-RPC 客户端连接 stdio 和 HTTP 的 MCP server。远端工具登记为 `mcp_<server>_<tool>`。调用前走 api 的 PassGate，只有 ALLOW 和 SKIPPED 才向远端发送 tools/call。单个 server 连不上不影响 open。同名登记失败，错误码是 MCP。
- 涉及模块：jasagent-mcp
- 怎么验证：在仓库根目录执行 `mvn test`。`McpClientTest` 16 项与此前全部用例通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/010-mcp/tests/010-mcp.sh` 退出码为 0。
