# 2026-11-08 模型路由

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：接入 deepseek 与 ai-gateway 两个供应方，并按任务自动路由。模型 HTTP 使用 LangChain4j 的 OpenAI 客户端，不自写 HTTP 客户端，不依赖 Spring 或 Spring AI。Gateway 不可用时 react、compress、rerank、judge 都走 deepseek-flash；Gateway 健康时只有 react 走 Gateway。点名第三个供应方失败，错误码 LLM。embed 不走 DeepSeek，Gateway 不提供 embeddings 时用本机 hashing。
- 涉及模块：jasagent-api、jasagent-llm
- 怎么验证：在仓库根目录执行 `mvn test`，`LoginDirectoryTest` 18 项通过，`LlmRouterTest` 39 项通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/002-llm-router/tests/002-llm-router.sh` 退出码为 0。默认测试使用假的 LlmClient，不访问外网。
