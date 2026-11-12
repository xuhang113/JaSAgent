-- 用户请求溯源和引用溯源。表只服务这条链路，不改执行库、检查点或通行证。
-- 口令不出现在本文件里。连接账号由代码从用户主目录的口令文件读取。

-- 一次从网关进入的用户请求。按 execution_id 回查租户、用户、会话和打到哪台 agent。
CREATE TABLE IF NOT EXISTS jasagent_request_lineage (
    execution_id VARCHAR(64) NOT NULL COMMENT 'agent 执行标识，回查主键',
    trace_id VARCHAR(64) NOT NULL COMMENT '贯穿网关、执行、模型调用和工具调用的追踪标识',
    tenant_id VARCHAR(255) NOT NULL COMMENT '租户',
    user_id VARCHAR(255) NOT NULL COMMENT '用户',
    session_id VARCHAR(255) NOT NULL COMMENT '会话',
    agent_id VARCHAR(255) NOT NULL COMMENT '处理这次请求的 agent 实例',
    gateway VARCHAR(255) NOT NULL COMMENT '请求进入的网关',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '第一次写入时间',
    PRIMARY KEY (execution_id),
    KEY idx_jasagent_request_lineage_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 这次请求带上的模型调用。trace_id 与请求行相同。
CREATE TABLE IF NOT EXISTS jasagent_request_model_call (
    execution_id VARCHAR(64) NOT NULL COMMENT '所属执行',
    call_id VARCHAR(64) NOT NULL COMMENT '模型调用标识，同一次执行内唯一',
    trace_id VARCHAR(64) NOT NULL COMMENT '与请求相同的追踪标识',
    provider VARCHAR(64) NOT NULL COMMENT '供应方，例如 deepseek 或 ai-gateway',
    model_name VARCHAR(128) NOT NULL COMMENT '模型名',
    PRIMARY KEY (execution_id, call_id),
    KEY idx_jasagent_request_model_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 这次请求带上的工具调用。trace_id 与请求行相同。
CREATE TABLE IF NOT EXISTS jasagent_request_tool_call (
    execution_id VARCHAR(64) NOT NULL COMMENT '所属执行',
    call_id VARCHAR(64) NOT NULL COMMENT '工具调用标识，同一次执行内唯一',
    trace_id VARCHAR(64) NOT NULL COMMENT '与请求相同的追踪标识',
    tool_name VARCHAR(255) NOT NULL COMMENT '工具名',
    PRIMARY KEY (execution_id, call_id),
    KEY idx_jasagent_request_tool_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模型回答引用的检索片段。同一执行、回答、服务、文档和片段只留一行。
CREATE TABLE IF NOT EXISTS jasagent_citation_lineage (
    citation_id VARCHAR(64) NOT NULL COMMENT '引用行标识',
    citation_key CHAR(64) NOT NULL COMMENT '执行、回答、服务、文档、片段的 SHA-256，用来合并重复命中',
    execution_id VARCHAR(64) NOT NULL COMMENT '所属执行',
    trace_id VARCHAR(64) NOT NULL COMMENT '与请求相同的追踪标识；请求还没记下时先写空串',
    session_id VARCHAR(255) NOT NULL COMMENT '会话',
    answer_id VARCHAR(64) NOT NULL COMMENT '引用这些片段的回答',
    service_name VARCHAR(128) NOT NULL COMMENT '检索服务，示例 RAG 为 rag',
    document_id VARCHAR(1024) NOT NULL COMMENT '文档标识',
    fragment_id VARCHAR(1024) NOT NULL COMMENT '片段标识',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '第一次写入时间',
    PRIMARY KEY (citation_id),
    UNIQUE KEY uk_jasagent_citation_key (citation_key),
    KEY idx_jasagent_citation_execution (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
