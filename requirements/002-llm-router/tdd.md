# 002 模型路由的测试用例

## 1. gatewayMissingReactUsesDeepseekFlash

要求：Gateway 的 base URL 为空或只有空白时，react 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，正文等于假客户端返回的正文，DeepSeek 调用一次，Gateway 调用零次。

## 2. gatewayMissingCompressUsesDeepseekFlash

要求：Gateway 的 base URL 为空时，compress 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，DeepSeek 调用一次，Gateway 调用零次。

## 3. gatewayMissingRerankUsesDeepseekFlash

要求：Gateway 的 base URL 为空时，rerank 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，DeepSeek 调用一次，Gateway 调用零次。

## 4. gatewayMissingJudgeUsesDeepseekFlash

要求：Gateway 的 base URL 为空时，judge 调用 deepseek，模型名是 deepseek-flash，不调用 Gateway。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，DeepSeek 调用一次，Gateway 调用零次。

## 5. gatewayHealthyReactUsesGatewayModel

要求：Gateway 已配置且健康时，react 调用 ai-gateway，模型名等于配置里的 Gateway 模型名，不调用 DeepSeek。

看到的结果：响应供应方是 ai-gateway，模型名是 vendor-model，Gateway 调用一次，DeepSeek 调用零次。

## 6. gatewayHealthyCompressStaysOnDeepseek

要求：Gateway 已配置且健康时，compress 调用 deepseek-flash，不调用 Gateway。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，Gateway 调用零次。

## 7. gatewayHealthyRerankStaysOnDeepseek

要求：Gateway 已配置且健康时，rerank 调用 deepseek-flash，不调用 Gateway。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，Gateway 调用零次。

## 8. gatewayHealthyJudgeStaysOnDeepseek

要求：Gateway 已配置且健康时，judge 调用 deepseek-flash，不调用 Gateway。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，Gateway 调用零次。

## 9. pinnedDeepseekIgnoresHealthyGateway

要求：调用方点名 deepseek 时，即使 Gateway 健康，也只调用 deepseek-flash。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，Gateway 调用零次。

## 10. pinnedGatewayUnavailableDoesNotSwitch

要求：调用方点名 ai-gateway 但该供应方不健康时，调用失败，错误码是 LLM，并且不调用 DeepSeek。

看到的结果：异常的错误码是 LLM，DeepSeek 调用零次，Gateway 调用零次。

## 11. pinnedDeepseekUnavailableDoesNotSwitch

要求：调用方点名 deepseek 但 DeepSeek 密钥为空时，调用失败，错误码是 LLM，并且不改走 Gateway。

看到的结果：异常的错误码是 LLM，Gateway 调用零次，DeepSeek 调用零次。

## 12. thirdProviderFailsWithLlm

要求：调用方点名 deepseek 与 ai-gateway 以外的供应方时，调用失败，错误码是 LLM，两个客户端都不调用。

看到的结果：点名 other-vendor 时异常的错误码是 LLM，两个客户端的调用次数都是零。

## 13. reactFallsBackToDeepseekAndTracesFallback

要求：自动路由的 react 在 Gateway 调用失败后，这一次改走 deepseek-flash，trace 含有 fallback，调用记录的 fallback 为真。

看到的结果：响应供应方是 deepseek，模型名是 deepseek-flash，fallback 为真，trace 文本含有 fallback，调用记录有一条且 fallback 为真。

## 14. compressFailureDoesNotReroute

要求：compress 使用的 DeepSeek 调用失败时，不改道调用 Gateway。

看到的结果：异常的错误码是 LLM，Gateway 调用零次。

## 15. rerankFailureDoesNotReroute

要求：rerank 使用的 DeepSeek 调用失败时，不改道调用 Gateway。

看到的结果：异常的错误码是 LLM，Gateway 调用零次。

## 16. judgeFailureDoesNotReroute

要求：judge 使用的 DeepSeek 调用失败时，不改道调用 Gateway。

看到的结果：异常的错误码是 LLM，Gateway 调用零次。

## 17. reactGatewayTimeoutFallsBackWithoutRetryingGateway

要求：自动路由的 react 在 Gateway 超时时不重试 Gateway，改走 deepseek-flash。

看到的结果：Gateway 调用一次，DeepSeek 调用一次，响应模型名是 deepseek-flash，fallback 为真。

## 18. reactDeepseekTimeoutRetriesOnceWhenStepsRemain

要求：react 走 DeepSeek 并且超时，剩余步数大于等于 1 时，同一供应方再调用一次并返回成功。

看到的结果：DeepSeek 调用两次，响应模型名是 deepseek-flash，fallback 为假。

## 19. reactDeepseekTimeoutDoesNotRetryWithoutSteps

要求：react 走 DeepSeek 并且超时，剩余步数小于 1 时，不进行第二次调用，错误码是 LLM。

看到的结果：异常的错误码是 LLM，DeepSeek 调用一次。

## 20. reactTimeoutRetriesAtMostOnce

要求：react 走 DeepSeek 的超时重试最多一次，第二次仍然超时时错误码是 LLM。

看到的结果：异常的错误码是 LLM，DeepSeek 调用两次。

## 21. pinnedGatewayTimeoutRetriesGatewayOnly

要求：点名 ai-gateway 的 react 超时且剩余步数大于等于 1 时，再调用一次 ai-gateway，不调用 DeepSeek。

看到的结果：Gateway 调用两次，DeepSeek 调用零次，响应供应方是 ai-gateway。

## 22. embedUsesGatewayWhenConfigured

要求：Gateway 提供 embeddings 时，embed 使用该 Gateway 嵌入模型，不调用 DeepSeek。

看到的结果：embedder 是 ai-gateway，模型名是 embed-a，DeepSeek 的 embed 次数是零，trace 含有 embedder=ai-gateway。

## 23. embedUsesHashingWhenGatewayHasNoEmbeddings

要求：Gateway 不提供 embeddings 时，embed 使用本机 hashing，维度是 256，相同文本两次向量相同，不同文本向量不同，trace 含有 embedder=hashing，并且不调用 DeepSeek。

看到的结果：两次相同文本的向量长度都是 256 且逐项相等，不同文本的向量不相等，trace 含有 embedder=hashing，DeepSeek 和 Gateway 的 embed 次数都是零。

## 24. blankEmbeddingModelUsesHashing

要求：嵌入开关打开但嵌入模型名为空时，embed 使用 hashing，trace 含有 embedder=hashing。

看到的结果：embedder 是 hashing，trace 含有 embedder=hashing，Gateway 的 embed 次数是零。

## 25. unhealthyGatewayEmbedUsesHashing

要求：Gateway 不健康时，embed 使用 hashing，不调用 Gateway。

看到的结果：embedder 是 hashing，Gateway 的 embed 次数是零。

## 26. chatRecordStoresProviderModelLatencyAndTokens

要求：成功的对话调用会写入调用记录，其中供应方、模型名、延迟毫秒、prompt token、completion token 和 total token 与这次调用一致，延迟大于等于 0。

看到的结果：记录的供应方是 deepseek，模型名是 deepseek-flash，延迟大于等于 0，token 分别是 11、7、18。

## 27. authorizationRedactorStripsHeader

要求：日志文本里的 Authorization 头和 Bearer 密钥会被去掉。

看到的结果：处理后的文本不含 Authorization、Bearer 和 unit-test-secret-key。

## 28. traceDoesNotContainApiKey

要求：路由写出的 trace 和异常消息不包含 DeepSeek 密钥和 Gateway 密钥。

看到的结果：成功调用的 trace、失败调用的异常消息都不含 deepseek-placeholder-key 和 gateway-placeholder-key。

## 29. defaultTimeoutIsSixtySeconds

要求：未设置超时秒数时，超时是 60 秒。

看到的结果：由环境变量映射出的超时等于 60 秒。

## 30. timeoutSecondsCanBeOverridden

要求：环境变量 JASAGENT_LLM_TIMEOUT_SECONDS 为 15 时，超时是 15 秒。

看到的结果：映射出的超时等于 15 秒。

## 31. deepSeekModelNameAndBaseUrl

要求：DeepSeek 的 LangChain4j 请求只使用模型名 deepseek-flash，base URL 是 https://api.deepseek.com。

看到的结果：构建出的 OpenAiChatModel 默认模型名是 deepseek-flash，客户端里的 base URL 是 https://api.deepseek.com。

## 32. deepSeekThinkingDisabledByDefault

要求：未打开 thinking 时，DeepSeek 请求参数 thinking.type 是 disabled。

看到的结果：OpenAiChatModel 默认参数里的 thinking.type 是 disabled。

## 33. deepSeekThinkingEnabledFromEnvironment

要求：环境变量 JASAGENT_DEEPSEEK_THINKING 为 true 时，DeepSeek 请求参数 thinking.type 是 enabled。

看到的结果：OpenAiChatModel 默认参数里的 thinking.type 是 enabled。

## 34. langchain4jDisablesLibraryRetryAndRequestLogs

要求：交给 LangChain4j 的聊天模型关闭库自带重试和请求日志，maxRetries 是 0，并且不使用 LoggingHttpClient。

看到的结果：OpenAiChatModel 的 maxRetries 是 0，内部 HTTP 客户端的类名不含 Logging。

## 35. gatewayChatModelUsesConfiguredEndpoint

要求：Gateway 聊天模型的 base URL 和模型名来自配置，不写死具体下游模型。

看到的结果：OpenAiChatModel 的模型名是 vendor-model，base URL 是 https://gateway.example，默认参数里没有 thinking。

## 36. moduleDoesNotUseSpringOrHandWrittenHttpClient

要求：jasagent-llm 的源码不出现 java.net.http，也不调用 httpClientBuilder；pom 不依赖 Spring 或 Spring AI；测试能加载 OpenAiChatModel。

看到的结果：主源码和 pom 不含这些依赖或调用，OpenAiChatModel 能被加载，Spring 与 Spring AI 的类不在 classpath 上。

## 37. chatRunsOnVirtualThread

要求：对话调用在虚拟线程上执行客户端，方法返回 CompletionStage。

看到的结果：返回值是 CompletionStage，假客户端记录到的线程是虚拟线程。

## 38. unhealthyGatewayUsesDeepseekForEveryTask

要求：Gateway 已配置但不健康时，react、compress、rerank、judge 都调用 deepseek-flash，不调用 Gateway。

看到的结果：四个响应的供应方都是 deepseek，模型名都是 deepseek-flash，Gateway 调用零次。

## 39. deepSeekClientRejectsEmbed

要求：DeepSeek 客户端收到 embed 时直接失败，错误码是 LLM。

看到的结果：异常的错误码是 LLM。
