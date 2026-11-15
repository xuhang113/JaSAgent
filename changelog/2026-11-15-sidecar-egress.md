# 离开集群的 HTTP 走 sidecar

- 日期：2026-11-15（Asia/Shanghai）
- 做了什么：agent 进程里大模型调用和模型目录等离开集群的 HTTP 改为经本机回环上 sidecar 的 /v1/forward 发出，不再由这些调用自己拨公网。同一条出站判断把公网主机（含外部工具、MCP 这类地址）送进 sidecar，把 Nacos（含配置里的 gRPC 9848 主机）、MySQL、Redis、网关、agentpass、检索和其它本集群地址留在直连。向 Nacos 注册的仍是网关能访问的非回环地址。sidecar 按流转发请求体和响应体，大模型流式响应可以在整段结束前送回。api.deepseek.com、api.x.ai 和 JASAGENT_LLM_ALLOWLIST 仍免证，其它主机仍先核销票据。本机 scripts/start.sh 拉起一个回环 sidecar，18731 和 18732 两台 agent 共用它。本记录只覆盖验收第 1 到第 9 条，不包含每 Pod sidecar、网络策略、进程出站规则、官方探测和 deploy.md（第 10 到第 15 条）。未改 deploy/k8s，未改 scripts/k8s/probe-egress.sh，未改 jasagent-gateway 和 jasagent-web。
- 涉及模块：jasagent-sidecar、jasagent-app、jasagent-llm、scripts
- 怎么验证：`bash scripts/test-018-sidecar-egress.sh`
