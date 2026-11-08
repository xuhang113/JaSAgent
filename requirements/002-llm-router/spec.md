# 002 模型路由

## 这次要改什么

现状是登录和目录已经可用，还没有模型调用。这次只增加两个供应方：`deepseek` 和 `ai-gateway`。模型调用使用 JDK `HttpClient` 访问 OpenAI 兼容的 `/chat/completions`。不引入 Spring，也不引入 Spring AI。不改登录、工作流步数、检索管道和页面。

## 模块

这次扩展 `jasagent-api` 的错误码、脱敏和模型端口，并新增 `jasagent-llm`。`jasagent-llm` 的 Maven 依赖只指向 `jasagent-api` 以及 JSON 库，不依赖其他业务模块。

## 改动地方

DeepSeek 的基址是 `https://api.deepseek.com`，路径是 `POST /chat/completions`。发出的模型名只有 `deepseek-flash`。密钥只读取环境变量 `JASAGENT_DEEPSEEK_API_KEY`。配置文件里的同名项忽略。默认在请求体里写 `thinking.type=disabled`。只有 `jasagent.deepseek.thinking=true` 时才写 `enabled`。

AI Gateway 使用同样的 `/chat/completions`。`JASAGENT_GATEWAY_BASE_URL` 为空就视为未配置。模型名只用 `JASAGENT_GATEWAY_MODEL`，代码里不写 Gateway 后面的模型清单。密钥只读取 `JASAGENT_GATEWAY_API_KEY`。

未点名供应方时：Gateway 未配置或不健康，`react`、`compress`、`rerank`、`judge` 都走 `deepseek-flash`。Gateway 已配置且健康时，只有 `react` 走 `ai-gateway`，其余三个任务仍走 `deepseek-flash`。点名供应方时用被点名的那个；不可用就失败，错误码 `LLM`，不改走另一个。第三个供应方直接失败，错误码 `LLM`。

`react` 走 Gateway 且这次调用失败时，同一请求改走 `deepseek-flash`，并在 trace 里写 `fallback=deepseek-flash`。`compress`、`rerank`、`judge` 失败不改道。

`embed` 不调用 DeepSeek。Gateway 客户端标明提供 embeddings 时用 Gateway，否则用本机 hashing embedder，并在 trace 标记 `embedder=hashing`。

每次成功的模型调用把供应方、模型名、延迟和 token 用量交给记录端口。日志文本先去掉 `Authorization`。超时默认 60 秒。工作流里的步数内重试留给后续需求。

## 数据库

这次不建表。记录端口只是接口，真正落库留给持久化需求。

## 中间件

这次不启动 HTTP 服务，也不连接消息中间件。测试里的 DeepSeek 请求打到本机回环上的临时服务器，不访问外网，也不使用真实密钥。

## 测试要求

1. Gateway 未配置时，`react` 的供应方是 `deepseek`，模型名是 `deepseek-flash`。
2. Gateway 未配置时，`compress` 的模型名是 `deepseek-flash`。
3. Gateway 未配置时，`rerank` 的模型名是 `deepseek-flash`。
4. Gateway 未配置时，`judge` 的模型名是 `deepseek-flash`。
5. Gateway 已配置且健康时，`react` 的供应方是 `ai-gateway`。
6. Gateway 已配置且健康时，`compress` 的模型名仍是 `deepseek-flash`。
7. Gateway 已配置且健康时，`rerank` 的模型名仍是 `deepseek-flash`。
8. Gateway 已配置且健康时，`judge` 的模型名仍是 `deepseek-flash`。
9. 点名第三个供应方时调用失败，错误码是 `LLM`，两个已有客户端都不会被调用。
10. 点名的 DeepSeek 不可用时失败，错误码是 `LLM`，不会改走 Gateway。
11. Gateway 在 `react` 调用中途失败时，这一次改走 `deepseek-flash`，并且 trace 里有 `fallback=deepseek-flash`。
12. `compress` 调用失败时不会改道到 Gateway。
13. 发给 DeepSeek 的请求体里模型名是 `deepseek-flash`。
14. 默认请求体关闭 thinking；配置打开时请求体才打开 thinking。
15. DeepSeek 密钥只从环境变量 `JASAGENT_DEEPSEEK_API_KEY` 读取，配置映射里的同名值无效。
16. Gateway 密钥只从环境变量 `JASAGENT_GATEWAY_API_KEY` 读取，配置映射里的同名值无效。
17. 含有 `Authorization` 的日志文本会被去掉密钥。
18. Gateway 基址为空时视为未配置。
19. `embed` 不调用 DeepSeek；Gateway 不提供 embeddings 时使用 hashing，并且 trace 标记 `embedder=hashing`。
20. 成功的模型调用会把供应方、模型名、延迟和 token 用量交给记录端口。
21. Gateway 请求体使用调用方传入的模型名。
22. 本机回环上的 DeepSeek 客户端会发送密钥头，但交给日志的文本不包含该密钥。

## 还没定

无。超时 60 秒、thinking 字段和 hashing 维度按本节实现。
