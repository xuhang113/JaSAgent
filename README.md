# JaSAgent

JaSAgent 是跑在使用者自己机器上的代理运行时，版本 `dev.jasagent:jasagent:0.1.0`，语言级别 Java 21。命令行、页面和 REST 调用同一个执行服务。

## 模块和依赖

父工程是 `dev.jasagent:jasagent:0.1.0`。业务代码的包名是 `dev.jasagent`。

`jasagent-api` 放执行编号、任务、事件、DSL 和全部端口。除 `jasagent-app` 以外，业务模块的 Maven 依赖只指向 `jasagent-api`。`jasagent-workflow` 和 `jasagent-core` 互不依赖实现，由 app 注入工作流引擎和调度器。`jasagent-mcp` 实现工具端口，由 app 登记。`jasagent-tool` 和 `jasagent-plugin` 在执行外部能力前调用 api 里的 `PassGate`，不依赖 `jasagent-agentpass` 的实现类。

Spring Boot 3 只出现在 `jasagent-app`、`jasagent-controller`、`jasagent-web`、`jasagent-cli`。本版不使用 Spring AI。模型调用使用 JDK `HttpClient`。

跨模块的 I/O 返回 `java.util.concurrent.CompletionStage`。阻塞的文件和 JDBC 放进 `Executors.newVirtualThreadPerTaskExecutor()`。

## 目录

运行时目录名是小写 `jasagent`，和仓库目录 `JaSAgent` 不是一回事。

用户目录是 `${user.home}/<登录名>/jasagent`。实现不写死登录名，也不创建操作系统账户。里面有 `auth/password.hash`、`auth/sessions`、`rules`、`skills`、`corrections`、`rag/docs`、`rag/index`。

工作区目录是 `<workspace>/jasagent`。允许列表 `jasagent.workspaces` 为空时，只允许进程当前工作目录。里面有 `workflows`、`rules`、`skills`、`rag/docs`、`rag/index`、`logs/{executionId}`、`data`。

规则和技能先装载工作区，再装载用户目录。相同 id 的规则、同名技能以用户目录为准。用户只能读自己的用户目录。

## 登录

口令使用 PBKDF2-HMAC-SHA256 和随机盐，哈希文件权限是 600。HTTP 不提供注册。还没有用户时，本机 `jasagent user add` 可以创建第一个用户；之后只有已登录的本机命令可以再创建。

页面是 `/login`。登录成功后发送 HttpOnly 会话 cookie。命令行是 `jasagent login --user <name>` 和 `jasagent logout`，令牌写在该用户目录的 `auth/sessions`，权限 600。当前命令行指针在 `${user.home}/.jasagent/cli-session`。

除了登录、健康检查和创建第一个用户的本机命令，业务接口都要先登录。登录后要用 `jasagent use --workspace <path>` 或页面选定允许列表内的工作区，否则不能提交执行。

## 一次执行

已登录用户提交请求后，服务生成 UUID `executionId`，写入 userId、sessionId、workspace，状态为 `ACCEPTED`，并创建追踪根 span。接口马上返回这个编号。没有传 sessionId 时，sessionId 等于 executionId。

任务进入主代理队列。主代理只调度：每次最多取出 `jasagent.scheduler.batch-size` 条，默认 4。sessionId 相同相关度 1.0，correlationKey 相同 0.9，否则用目标文本的 token Jaccard。达到合并阈值 0.6 就合并。合并后的工作优先交给相关且未完成的子代理；该子代理未完成数量达到 8 就新建子代理。主代理不调用领域工具。

每个代理一条队列，队列内串行，代理之间并行。子代理用工作流引擎执行。每跨一个节点边界提交检查点。终态只有 `SUCCEEDED`、`FAILED`、`CANCELLED`、`SUSPENDED`。

## 异步

公共 I/O 返回 `CompletionStage`。提交线程在落库并入队后返回。Web 返回 202，进度走 SSE。命令行默认附着日志，`--detach` 时打印 executionId 后退出。同一个子代理同时只跑一个任务。同一步 ReAct 里的多个工具调用可以并行。异步边界不吞掉异常后返回 null。

## executionId 与 traceId

`executionId` 是这一次执行的业务编号，恢复时始终用原来的编号。`traceId` 是同一次执行的 OpenTelemetry 追踪编号，记在执行记录里。根 span 名是 `jasagent.execution`。子 span 带属性 `jasagent.execution_id`。没有配置 `jasagent.otel.endpoint` 时不导出 OTLP。恢复后的 span 继续用保存的 traceId。

给人读的日志在 `<workspace>/jasagent/logs/{executionId}/trace.log` 和 `events.jsonl`。日志里会去掉 Authorization。

## 模型路由

只允许 `deepseek` 和 `ai-gateway`。点名第三个供应方会失败，错误码是 `LLM`。

DeepSeek 的基址是 `https://api.deepseek.com`，路径是 `POST /chat/completions`，发出的模型名只有 `deepseek-flash`。密钥只读环境变量 `JASAGENT_DEEPSEEK_API_KEY`。默认关闭 thinking，`jasagent.deepseek.thinking=true` 才打开。

Gateway 同样是 OpenAI 兼容的 `/chat/completions`。`JASAGENT_GATEWAY_BASE_URL` 为空就视为未配置。模型名用 `JASAGENT_GATEWAY_MODEL`。密钥只读 `JASAGENT_GATEWAY_API_KEY`。代码里不写 Gateway 后面的模型清单。

Gateway 未配置或不健康时，react、compress、rerank、judge 都走 `deepseek-flash`。Gateway 已配置且健康时，只有 react 走 Gateway，另外三个任务仍走 `deepseek-flash`。点名的供应方不可用就失败，不偷偷更换。react 走 Gateway 的这一次中途失败时，才改走 `deepseek-flash`，并在 trace 里写 fallback。压缩、重排、评判不改道。

embed 不走 DeepSeek。Gateway 提供 embeddings 时用 Gateway，否则用本机 hashing，并标记 `embedder=hashing`。

两个供应方都没配置时，页面里的示例执行使用离线回答，不访问网络，也不把离线回答登记成第三家模型。

## RAG

索引按 scope 分开放在用户目录和工作区目录的 `rag/index`，语料放对应的 `rag/docs`，支持 md 和 txt。先按标题切，再按约 800 字、重叠 100 字切开。

一次检索并行走 lexical、vector 余弦和标题路径 token 匹配。三路用 Reciprocal Rank Fusion 合并，k 为 60。最高的 20 条交给 rerank。没有完整溯源、重复 contentHash、或 rerank 分低于 `jasagent.rag.min-rerank`（默认 0.35）的命中会丢掉。一条都不剩就返回空结果，原因是 `quality-empty`，不编造段落。

溯源字段包括 sourceScope、uri、docId、chunkId、start、end、contentHash、fusionScore、rerankScore。指定 scope 时不串到另一个目录。`retrieve` 节点和 `rag.search` 走同一条管道。`required` 不是 true 时，空结果不让工作流失败。

## 通行证

外部工具和插件执行前调用 `PassGate.acquire`。本版的 `DisabledPassGate` 只返回 `SKIPPED`，原因是 `agentpass-not-enabled`。`DENY` 不执行，`ALLOW` 和 `SKIPPED` 才执行。内置 `clock` 是内部工具，不申请通行证。控制台可以对当前用户这次执行里的通行证提交 `ALLOW` 或 `DENY`。不做单独的策略管理站，也不做跨用户审批。

## 中止和恢复

中止会取消当前节点，停在最后一次成功检查点，状态变成 `SUSPENDED`。`POST /api/executions/{id}/resume` 用原来的 executionId 继续。不是 `SUSPENDED` 时，resume 返回当前快照。关闭 H2 后再打开，仍然可以恢复，traceId 不变。

## 命令行

在仓库根目录：

```bash
mvn -pl jasagent-app -am package
java -jar jasagent-app/target/jasagent-app-0.1.0.jar server
```

服务默认监听 `127.0.0.1:8090`。

```bash
java -jar jasagent-app/target/jasagent-app-0.1.0.jar user add --user <name> --password <password>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar login --user <name> --password <password>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar use --workspace <path>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar run --workflow answer-with-tool --input <text>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar status --execution <executionId>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar logs --execution <executionId>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar interrupt --execution <executionId>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar resume --execution <executionId>
java -jar jasagent-app/target/jasagent-app-0.1.0.jar workflows
java -jar jasagent-app/target/jasagent-app-0.1.0.jar logout
```

示例里的 `<password>` 是占位符，不要把真实口令写进仓库。

## 页面

页面在 `jasagent-web/frontend`，用 Vue 3、Vite 和 Element Plus。Spring 只托管构建结果和 REST，不使用 Spring AI。

```bash
cd jasagent-web/frontend
npm install
npm run build
```

构建写入 `jasagent-web/src/main/resources/static`。启动服务后打开 `http://127.0.0.1:8090/login`。本地改页面时可以另开 `npm run dev`，它把 `/api`、`/login`、`/logout` 代理到 `127.0.0.1:8090`。

登录后是管理控制台，同一屏放着 CLI 面板和对话区。可以选定工作区，提交、查询、中止、恢复执行，看日志和 SSE。对话输入 `/` 会列出当前用户可见的 skill，选中后插入 `/skill:名称`。上下文是按钮，点开后面板。会话按当前登录用户新建、删除和切换。详情里能看到这次执行包含的插件和工具、检索溯源、评估，以及对通行证提交 `ALLOW` 或 `DENY`。用户看不到另一用户的会话和执行。

## 配置和密钥

示例配置在 `jasagent-app/src/main/resources/application.properties`，里面没有密钥。

| 变量 | 作用 |
| --- | --- |
| `JASAGENT_DEEPSEEK_API_KEY` | DeepSeek 密钥，只从环境变量读取 |
| `JASAGENT_GATEWAY_API_KEY` | Gateway 密钥，只从环境变量读取 |
| `JASAGENT_GATEWAY_BASE_URL` | 为空表示 Gateway 未配置 |
| `JASAGENT_GATEWAY_MODEL` | Gateway 上的模型名 |
| `JASAGENT_JDBC_URL` | 换成外部数据库；不设置时用工作区里的 H2 文件 |
| `JASAGENT_JDBC_USER` | JDBC 用户，缺省 `sa` |
| `JASAGENT_JDBC_PASSWORD` | JDBC 口令，只从环境变量读取，缺省为空 |
| `JASAGENT_BASE_URL` | 命令行访问的服务地址，缺省 `http://127.0.0.1:8090` |
| `JASAGENT_START_DELAY_MS` | 执行开始前的等待，便于观察队列和中止 |

超时默认 60 秒。不要把真实密钥、token 或数据库口令写进 README、changelog、测试和示例配置。

## DSL

节点类型只允许 `react`、`tool`、`plugin`、`subagents`、`evaluate`、`interrupt`、`condition`、`retrieve`、`end`。仓库自带 `workflows/answer-with-tool.json`：retrieve、react（clock 与 rag.search，grounding）、evaluate、end。`rules/base.md` 和 `skills/research/SKILL.md` 只写用法。

react 的消息顺序是 system、压缩摘要、最近轮次、纠正 note、当前用户消息。纠正 note 紧挨当前用户消息。超过 `budgetTokens` 时走 compress 路由，但不删掉纠正 note、未完成任务和 executionId。

## SDD/TDD

每个需求一个目录 `requirements/<编号-短名>/`。先写 `spec.md`，再写 `tdd.md` 和 `tests/`，然后才写生产代码。实现还不存在时，验收脚本必须失败。全量测试通过之后才新增一篇 changelog，不改旧篇。

## 全量测试门

在仓库根目录执行：

```bash
mvn test
```

只跑新测试不算完成。此前需求留下的测试必须一起通过。默认测试使用假的模型和假的 reranker，不访问外网，也不把真实 DeepSeek 调用放进默认测试。

## changelog

模板是 `changelog/_template.md`，包含日期、做了什么、涉及模块、怎么验证。每完成一个需求新增 `changelog/YYYY-MM-DD-短名.md`。日期用 Asia/Shanghai 的当天，不修改已经写好的旧篇。
