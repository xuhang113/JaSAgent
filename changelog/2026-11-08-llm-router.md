# 2026-11-08 模型路由

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：用 JDK HttpClient 接入 DeepSeek 和 AI Gateway，按任务自动路由，密钥只从环境变量读取，embed 在 Gateway 不提供向量时改用本机 hashing。
- 涉及模块：jasagent-api、jasagent-llm
- 怎么验证：在仓库根目录执行 `mvn test`。`LoginDirectoryTest` 18 项、`LlmRouterTest` 15 项、`DeepSeekWireTest` 7 项全部通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/002-llm-router/tests/002-llm-router.sh` 退出码为 0。
