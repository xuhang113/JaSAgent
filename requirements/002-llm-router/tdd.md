# 002 模型路由的测试用例

## 1. gatewayUnconfiguredReactUsesDeepseekFlash

要求：Gateway 未配置时，`react` 的供应方是 `deepseek`，模型名是 `deepseek-flash`。

看到的结果：响应的供应方等于 `deepseek`，模型名等于 `deepseek-flash`。

## 2. gatewayUnconfiguredCompressUsesDeepseekFlash

要求：Gateway 未配置时，`compress` 的模型名是 `deepseek-flash`。

看到的结果：响应的模型名等于 `deepseek-flash`，并且供应方是 `deepseek`。

## 3. gatewayUnconfiguredRerankUsesDeepseekFlash

要求：Gateway 未配置时，`rerank` 的模型名是 `deepseek-flash`。

看到的结果：响应的模型名等于 `deepseek-flash`。

## 4. gatewayUnconfiguredJudgeUsesDeepseekFlash

要求：Gateway 未配置时，`judge` 的模型名是 `deepseek-flash`。

看到的结果：响应的模型名等于 `deepseek-flash`。

## 5. gatewayHealthyReactUsesGateway

要求：Gateway 已配置且健康时，`react` 的供应方是 `ai-gateway`。

看到的结果：响应供应方等于 `ai-gateway`，DeepSeek 客户端调用次数为 0。

## 6. gatewayHealthyCompressStaysDeepseek

要求：Gateway 已配置且健康时，`compress` 的模型名仍是 `deepseek-flash`。

看到的结果：响应模型名等于 `deepseek-flash`，Gateway 客户端调用次数为 0。

## 7. gatewayHealthyRerankStaysDeepseek

要求：Gateway 已配置且健康时，`rerank` 的模型名仍是 `deepseek-flash`。

看到的结果：响应模型名等于 `deepseek-flash`，Gateway 客户端调用次数为 0。

## 8. gatewayHealthyJudgeStaysDeepseek

要求：Gateway 已配置且健康时，`judge` 的模型名仍是 `deepseek-flash`。

看到的结果：响应模型名等于 `deepseek-flash`，Gateway 客户端调用次数为 0。

## 9. thirdProviderFailsWithLlm

要求：点名第三个供应方时调用失败，错误码是 `LLM`，两个已有客户端都不会被调用。

看到的结果：错误码为 `LLM`，DeepSeek 和 Gateway 的调用次数都是 0。

## 10. namedProviderUnavailableDoesNotSwitch

要求：点名的 DeepSeek 不可用时失败，错误码是 `LLM`，不会改走 Gateway。

看到的结果：错误码为 `LLM`，Gateway 调用次数是 0。

## 11. reactGatewayFailureFallsBackToDeepseek

要求：Gateway 在 `react` 调用中途失败时，这一次改走 `deepseek-flash`，并且 trace 里有 `fallback=deepseek-flash`。

看到的结果：响应模型名是 `deepseek-flash`，trace 字段 `fallback` 等于 `deepseek-flash`。

## 12. compressFailureDoesNotFallBack

要求：`compress` 调用失败时不会改道到 Gateway。

看到的结果：错误码为 `LLM`，Gateway 调用次数是 0。

## 13. deepseekRequestUsesOnlyDeepseekFlash

要求：发给 DeepSeek 的请求体里模型名是 `deepseek-flash`。

看到的结果：请求 JSON 的 `model` 字段等于 `deepseek-flash`。

## 14. thinkingDisabledUnlessConfigured

要求：默认请求体关闭 thinking；配置打开时请求体才打开 thinking。

看到的结果：默认 `thinking.type` 为 `disabled`，打开配置后为 `enabled`。

## 15. deepseekKeyComesOnlyFromEnv

要求：DeepSeek 密钥只从环境变量 `JASAGENT_DEEPSEEK_API_KEY` 读取，配置映射里的同名值无效。

看到的结果：只在环境映射中放置占位符时能读到该占位符；只在配置映射中放置同名值时结果为空。

## 16. gatewayKeyComesOnlyFromEnv

要求：Gateway 密钥只从环境变量 `JASAGENT_GATEWAY_API_KEY` 读取，配置映射里的同名值无效。

看到的结果：只在环境映射中放置占位符时能读到该占位符；只在配置映射中放置同名值时结果为空。

## 17. authorizationIsRedacted

要求：含有 `Authorization` 的日志文本会被去掉密钥。

看到的结果：脱敏后的文本不包含原来的密钥占位符。

## 18. blankGatewayBaseIsUnconfigured

要求：Gateway 基址为空时视为未配置。

看到的结果：`gatewayConfigured` 为假，`react` 仍返回 `deepseek-flash`。

## 19. embedDoesNotCallDeepseekAndUsesHashing

要求：`embed` 不调用 DeepSeek；Gateway 不提供 embeddings 时使用 hashing，并且 trace 标记 `embedder=hashing`。

看到的结果：DeepSeek 的 embed 次数为 0，直接调用 DeepSeek 的 embed 方法以 `LLM` 失败，路由结果的 embedder 为 `hashing`，trace 字段 `embedder` 等于 `hashing`。

## 20. successfulCallIsRecorded

要求：成功的模型调用会把供应方、模型名、延迟和 token 用量交给记录端口。

看到的结果：记录里的供应方、模型名和 token 用量与这次响应一致，延迟大于等于 0。

## 21. gatewayBodyUsesCallerModel

要求：Gateway 请求体使用调用方传入的模型名。

看到的结果：请求 JSON 的 `model` 等于传入的名称，而不是某个写死的清单项。

## 22. loopbackDeepseekOmitsKeyFromLog

要求：本机回环上的 DeepSeek 客户端会发送密钥头，但交给日志的文本不包含该密钥。

看到的结果：回环服务器收到的 `Authorization` 等于 `Bearer` 加测试占位符，日志收集器里的文本不包含该占位符。
