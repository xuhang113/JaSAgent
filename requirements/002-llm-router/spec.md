# 002 模型路由

## 这次要改什么

现状是登录和目录已经落地，仓库里还没有模型调用。构建规格里的模型传输曾写成 JDK HTTP 客户端。这次改为使用 LangChain4j 的 OpenAI 客户端访问 OpenAI 兼容的 `/chat/completions` 和 `/embeddings`，不自写 HTTP 客户端，不依赖 Spring，也不使用 Spring AI。路由规则仍按构建规格：只有 deepseek 和 ai-gateway 两个供应方，任务按 Gateway 是否可用在两者之间选择。

这次不改登录、口令、会话、目录、规则和技能的实现，不实现工作流、队列、检索管道、JDBC 持久化、页面和命令行。

## 模块

这次在 `jasagent-api` 增加错误码 `LLM` 和模型端口，并新增 `jasagent-llm` 的路由与 LangChain4j 适配。`jasagent-llm` 对仓库内模块只依赖 `jasagent-api`。第三方库使用 `dev.langchain4j:langchain4j-open-ai:1.23.0`，由它在运行时带上自己的 HTTP 实现。不引入 Spring、Spring Boot 或 Spring AI。

## 改动地方

供应方标识只有两个字符串：`deepseek` 和 `ai-gateway`。调用方点名时按这两个字符串原样比较。除此之外的名字都视为第三个供应方，调用失败，错误码 `LLM`，不向任何一个已配置的供应方发请求。

DeepSeek 的 base URL 固定为 `https://api.deepseek.com`。LangChain4j 会在这个基址后拼接 `chat/completions`，完整路径是 `POST /chat/completions`。发给 DeepSeek 的模型名只有 `deepseek-flash`。密钥只从环境变量 `JASAGENT_DEEPSEEK_API_KEY` 读取，测试和示例只用占位符。thinking 默认关闭，请求体里的 `thinking.type` 为 `disabled`。环境变量 `JASAGENT_DEEPSEEK_THINKING=true` 时，`thinking.type` 为 `enabled`。这个环境变量对应组装模块将来要映射的 `jasagent.deepseek.thinking`，本模块不读取 Spring 配置。

AI Gateway 同样走 LangChain4j 的 OpenAI 客户端。`JASAGENT_GATEWAY_BASE_URL` 为空或只有空白时视为未配置。模型名只使用 `JASAGENT_GATEWAY_MODEL`，代码里不写死 Gateway 后面的模型清单。密钥只从 `JASAGENT_GATEWAY_API_KEY` 读取。

Gateway 可被自动路由使用，需要同时满足：base URL 不是空白、密钥不是空白、模型名不是空白、`GatewayHealth.healthy()` 为真。健康检查的具体 URL 不在本模块里发请求，由调用方传入 `GatewayHealth`。缺任何一项都视为不可用。

自动路由：

- Gateway 不可用时，react、compress、rerank、judge 都调用 deepseek，模型名 `deepseek-flash`。
- Gateway 可用时，只有 react 调用 ai-gateway，模型名等于 `JASAGENT_GATEWAY_MODEL`。compress、rerank、judge 仍调用 `deepseek-flash`。

调用方点名供应方时只用那一个。点名的供应方不可用就失败，错误码 `LLM`，不换另一个。自动路由的 react 在 Gateway 这次调用失败时，包括超时，不重试 Gateway，改调用一次 `deepseek-flash`，并在 trace 写入含有 `fallback` 的一行。compress、rerank、judge 失败后不改道。

react 走 DeepSeek，或者调用方点名了 ai-gateway 时，超时可以再试一次，条件是剩余步数大于等于 1。第二次仍超时则失败，错误码 `LLM`。剩余步数小于 1 时不重试。点名 ai-gateway 的超时重试仍然打在 ai-gateway 上，不改道。

embed 不调用 DeepSeek。Gateway 提供 embeddings 是指 base URL、密钥、`JASAGENT_GATEWAY_EMBEDDING_MODEL` 都不是空白，`JASAGENT_GATEWAY_EMBEDDINGS` 为 true，且健康检查为真。满足时用 LangChain4j 的 `OpenAiEmbeddingModel` 调用 Gateway 的 `/embeddings`，trace 写入 `embedder=ai-gateway`。否则用本机 hashing embedder，维度 256，对同一文本稳定，trace 写入 `embedder=hashing`。hashing 不是第三个供应方，不会对它发 HTTP。

每次成功的模型调用把供应方、模型名、延迟毫秒、prompt token、completion token、total token 和是否 fallback 交给 `LlmCallStore`。trace 和异常消息去掉 Authorization 头和 Bearer 密钥。交给 LangChain4j 的客户端关闭请求日志和响应日志，并把库的 `maxRetries` 设为 0，重试只由本模块按上面的规则执行。超时默认 60 秒，可用 `JASAGENT_LLM_TIMEOUT_SECONDS` 覆盖。

公共方法返回 `CompletionStage`。阻塞的模型调用放进 `VirtualIo` 使用的 `Executors.newVirtualThreadPerTaskExecutor()`。

## 数据库

这次不建表，也不写 JDBC。调用记录通过 `LlmCallStore` 交出，字段为 executionId、task、provider、model、latencyMillis、promptTokens、completionTokens、totalTokens、fallback。以后的持久化模块再写入 `llm_call`。

## 中间件

不使用消息中间件，不使用 Spring。模型 HTTP 只通过 LangChain4j 1.23.0 的 `OpenAiChatModel` 和 `OpenAiEmbeddingModel`。本模块源码不声明 `java.net.http`，也不调用 LangChain4j 的 `httpClientBuilder`。

## 测试要求

1. Gateway 的 base URL 为空或只有空白时，react 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。
2. Gateway 的 base URL 为空时，compress 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。
3. Gateway 的 base URL 为空时，rerank 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。
4. Gateway 的 base URL 为空时，judge 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。
5. Gateway 已配置且健康时，react 调用 ai-gateway，模型名等于配置里的 Gateway 模型名，不调用 DeepSeek。
6. Gateway 已配置且健康时，compress 调用 deepseek-flash，不调用 Gateway。
7. Gateway 已配置且健康时，rerank 调用 deepseek-flash，不调用 Gateway。
8. Gateway 已配置且健康时，judge 调用 deepseek-flash，不调用 Gateway。
9. 调用方点名 deepseek 时，即使 Gateway 健康，也只调用 deepseek-flash。
10. 调用方点名 ai-gateway 但该供应方不健康时，调用失败，错误码是 LLM，并且不调用 DeepSeek。
11. 调用方点名 deepseek 但 DeepSeek 密钥为空时，调用失败，错误码是 LLM，并且不改走 Gateway。
12. 调用方点名 deepseek 与 ai-gateway 以外的供应方时，调用失败，错误码是 LLM，两个客户端都不调用。
13. 自动路由的 react 在 Gateway 调用失败后，这一次改走 deepseek-flash，trace 含有 fallback，调用记录的 fallback 为真。
14. compress 使用的 DeepSeek 调用失败时，不改道调用 Gateway。
15. rerank 使用的 DeepSeek 调用失败时，不改道调用 Gateway。
16. judge 使用的 DeepSeek 调用失败时，不改道调用 Gateway。
17. 自动路由的 react 在 Gateway 超时时不重试 Gateway，改走 deepseek-flash。
18. react 走 DeepSeek 并且超时，剩余步数大于等于 1 时，同一供应方再调用一次并返回成功。
19. react 走 DeepSeek 并且超时，剩余步数小于 1 时，不进行第二次调用，错误码是 LLM。
20. react 走 DeepSeek 的超时重试最多一次，第二次仍然超时时错误码是 LLM。
21. 点名 ai-gateway 的 react 超时且剩余步数大于等于 1 时，再调用一次 ai-gateway，不调用 DeepSeek。
22. Gateway 提供 embeddings 时，embed 使用该 Gateway 嵌入模型，不调用 DeepSeek。
23. Gateway 不提供 embeddings 时，embed 使用本机 hashing，维度是 256，相同文本两次向量相同，不同文本向量不同，trace 含有 embedder=hashing，并且不调用 DeepSeek。
24. 嵌入开关打开但嵌入模型名为空时，embed 使用 hashing，trace 含有 embedder=hashing。
25. Gateway 不健康时，embed 使用 hashing，不调用 Gateway。
26. 成功的对话调用会写入调用记录，其中供应方、模型名、延迟毫秒、prompt token、completion token 和 total token 与这次调用一致，延迟大于等于 0。
27. 日志文本里的 Authorization 头和 Bearer 密钥会被去掉。
28. 路由写出的 trace 和异常消息不包含 DeepSeek 密钥和 Gateway 密钥。
29. 未设置超时秒数时，超时是 60 秒。
30. 环境变量 JASAGENT_LLM_TIMEOUT_SECONDS 为 15 时，超时是 15 秒。
31. DeepSeek 的 LangChain4j 请求只使用模型名 deepseek-flash，base URL 是 https://api.deepseek.com。
32. 未打开 thinking 时，DeepSeek 请求参数 thinking.type 是 disabled。
33. 环境变量 JASAGENT_DEEPSEEK_THINKING 为 true 时，DeepSeek 请求参数 thinking.type 是 enabled。
34. 交给 LangChain4j 的聊天模型关闭库自带重试和请求日志，maxRetries 是 0，并且不使用 LoggingHttpClient。
35. Gateway 聊天模型的 base URL 和模型名来自配置，不写死具体下游模型。
36. jasagent-llm 的源码不出现 java.net.http，也不调用 httpClientBuilder；pom 不依赖 Spring 或 Spring AI；测试能加载 OpenAiChatModel。
37. 对话调用在虚拟线程上执行客户端，方法返回 CompletionStage。
38. Gateway 已配置但不健康时，react、compress、rerank、judge 都调用 deepseek-flash，不调用 Gateway。
39. DeepSeek 客户端收到 embed 时直接失败，错误码是 LLM。

## 还没定

无。健康结果由 `GatewayHealth` 传入，嵌入维度定为 256，超时默认 60 秒，thinking 用 `JASAGENT_DEEPSEEK_THINKING` 表示。
