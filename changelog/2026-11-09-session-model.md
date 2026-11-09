# 2026-11-09 会话可选模型

- 日期：2026-11-09（Asia/Shanghai）
- 做了什么：会话输入区的发送按钮旁边可以选模型，对话标题旁显示这一会话当前的模型。新建会话默认 `deepseek-flash`。换模型只影响这一会话之后的消息，选择写在该会话自己的记录里，刷新后还在。列表来自已经接通的 DeepSeek：`https://api.deepseek.com/models` 现在是 `deepseek-flash` 和 `deepseek-v4-pro`，请求仍发到 `https://api.deepseek.com`。密钥只从环境读取。本机 Gateway 没有配齐，或者地址探不通时，选择器里不出现 Gateway。
- 涉及模块：jasagent-api、jasagent-llm、jasagent-controller、jasagent-app、jasagent-web
- 怎么验证：Java 21。`jasagent-web/frontend` 下 `npm test`，32 条通过。`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl jasagent-llm,jasagent-app -am test -Dtest=ReasoningTextTest,LlmRouterTest,SessionModelCatalogTest,SessionModelTest,WorkflowModelsThinkingTest,ConsoleSessionPageTest -Dsurefire.failIfNoSpecifiedTests=false`，ReasoningTextTest 7、LlmRouterTest 43、SessionModelCatalogTest 7、SessionModelTest 2、WorkflowModelsThinkingTest 1、ConsoleSessionPageTest 31，失败 0。`./scripts/stop.sh` 后再 `./scripts/start.sh`，`127.0.0.1:18721` 健康检查是 `storage=up`。页面脚本是 `index-kRe8MxGY.js`，样式是 `index-U27cEPjX.css`。选择 DeepSeek 后发出的消息回到了 `https://api.deepseek.com`，回复不是「模型未配置」。
