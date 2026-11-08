# 2026-11-08 合入 DeepSeek 与三列会话

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：把 DeepSeek 运行时和三列会话页合进默认分支。数据目录仍是工作区 `.jasagent/data`。模型仍是 `deepseek-flash`。启动时若进程没有 `JASAGENT_DEEPSEEK_API_KEY`，从 `llgraph.env` 的 `LLGRAPH_API_KEY` 填入，密钥不入库。会话页保持满高三列，右栏常驻 Trace、工具、上下文和日志。执行错误码仍显示在对话和右栏。测试断言没有放宽。
- 涉及模块：jasagent-app、jasagent-web、jasagent-api、jasagent-core、jasagent-interrupt、jasagent-persist、scripts
- 怎么验证：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test`，以及 `jasagent-web/frontend` 下 `npm test`。
