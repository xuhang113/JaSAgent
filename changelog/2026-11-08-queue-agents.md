# 2026-11-08 队列与主子代理

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：主代理按批次取出工作，用 session、correlationKey 和 token Jaccard 合并，未完成子代理优先复用，队列满了才新建。子代理队列串行，子代理之间并行。提交在执行结束前返回。
- 涉及模块：jasagent-core
- 怎么验证：在仓库根目录执行 `mvn test`。`QueueAgentTest` 13 项与此前全部用例通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/006-queue-agents/tests/006-queue-agents.sh` 退出码为 0。
