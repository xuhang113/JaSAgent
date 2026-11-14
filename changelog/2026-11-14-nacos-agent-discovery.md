# 从 Nacos 发现 agent

- 日期：2026-11-14（Asia/Shanghai）
- 做了什么：agent 就绪后向 Nacos 注册网关能访问的非回环地址，退出时注销，并暴露进行中的会话作为扩缩容主指标，CPU 只作补充。网关只使用当时从 Nacos 查到的健康实例；Nacos 不可用或列表为空时转发失败，不再回退到静态目录或 127.0.0.1:18731 / 127.0.0.1:18732。粘性仍按 (tenant_id, user_id) 读写 jasagent_gateway_affinity；绑定实例消失或不健康时改派，没有可用实例时失败且不删行。登录会话和存活探测仍在网关进程内，本轮网关按单副本验收。本机 scripts/start.sh 会连接或拉起可配置的 Nacos，agent 监听 18731 / 18732 但注册非回环地址。Nacos 地址和口令只从仓库外路径或环境读取。未改 deploy/k8s，未改 sidecar 产品代码，未改官方 scripts/k8s/probe-egress.sh。
- 涉及模块：jasagent-app、jasagent-gateway、scripts
- 怎么验证：`bash scripts/test-017-nacos-agent-discovery.sh`。仓库内合同测试覆盖验收第 1 到第 12 条（注册与注销、只从 Nacos 发现、无静态兜底、粘性改派与保留、网关单副本、本机启动、主指标、注释与密文）。不在本轮验收第 13 到第 18 条。
