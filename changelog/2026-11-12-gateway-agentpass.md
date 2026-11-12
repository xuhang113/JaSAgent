# 2026-11-12 合入网关、通行证、溯源和 gVisor

- 日期：2026-11-12（Asia/Shanghai）
- 做了什么：把溯源、通行证服务、示例检索、网关、运行时和沙箱清单合进主检出。网关对外 18721，用四个约定头把请求粘到 18731 和 18732 的 runsc agent。请求被接受后按 executionId 记租户、用户、会话和 agent。检索命中另记引用。领证、校验和核销分别走 18741 的三个路径，检索走 18751，证放在 `X-Jasagent-Ticket`。本机启动脚本把内部令牌和 `XAI_API_KEY` 放进进程环境或仓库外文件。K8s 清单仍是占位 Secret，agent 副本 2，RuntimeClass 为 gvisor。没有改工作流、PBKDF2 登录、LangChain4j 路由和进程内通行证。
- 涉及模块：jasagent-gateway、jasagent-agentpass-server、jasagent-agentpass-starter、jasagent-agentpass-rag、jasagent-trace、jasagent-app、jasagent-controller、jasagent-api、jasagent-web、deploy、scripts/sandbox
- 怎么验证：Java 21 下跑粘性路由、MySQL 恢复、同进程隔离、领证与重放、Filter、示例 RAG、溯源和 GrokLiveCallTest，均通过。本机登录后发消息落到 runsc 的 agent-18731，停掉后改到 agent-18732 并能从 MySQL 读回原会话。别人的会话编号被拒绝且执行行数不变。无证检索失败，有效证成功，同一证第二次失败，流水和引用能按 executionId 查回。`/admin/` 能打开，模型列表含 grok-4.7。容器内这次 grok 调用超时，没有拿到模型正文；宿主机的 GrokLiveCallTest 已打到 api.x.ai 并得到回复。日志里没有密钥明文。部署由 `scripts/up-stack.sh` 应用到本机集群。
