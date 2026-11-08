CREATE TABLE execution (
    execution_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    workspace_path VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    otel_trace_id VARCHAR(64),
    otel_root_span_id VARCHAR(32),
    CONSTRAINT pk_execution PRIMARY KEY (execution_id)
);

CREATE TABLE agent (
    agent_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    CONSTRAINT pk_agent PRIMARY KEY (agent_id)
);

CREATE TABLE task (
    task_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    CONSTRAINT pk_task PRIMARY KEY (task_id)
);

CREATE TABLE message (
    message_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    body VARCHAR(1000000) NOT NULL,
    CONSTRAINT pk_message PRIMARY KEY (message_id)
);

CREATE TABLE correction_note (
    note_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    text VARCHAR(1000000) NOT NULL,
    CONSTRAINT pk_correction_note PRIMARY KEY (note_id)
);

CREATE TABLE context_summary (
    summary_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    text VARCHAR(1000000) NOT NULL,
    CONSTRAINT pk_context_summary PRIMARY KEY (summary_id)
);

CREATE TABLE llm_call (
    call_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    latency_ms BIGINT NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    total_tokens INTEGER NOT NULL,
    CONSTRAINT pk_llm_call PRIMARY KEY (call_id)
);

CREATE TABLE tool_call (
    call_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    pass_decision VARCHAR(32),
    CONSTRAINT pk_tool_call PRIMARY KEY (call_id)
);

CREATE TABLE plugin_call (
    call_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    plugin_id VARCHAR(255) NOT NULL,
    pass_decision VARCHAR(32),
    CONSTRAINT pk_plugin_call PRIMARY KEY (call_id)
);

CREATE TABLE retrieval (
    retrieval_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    provenance VARCHAR(1000000) NOT NULL,
    CONSTRAINT pk_retrieval PRIMARY KEY (retrieval_id)
);

CREATE TABLE evaluation (
    evaluation_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    evaluator VARCHAR(128) NOT NULL,
    passed BOOLEAN NOT NULL,
    CONSTRAINT pk_evaluation PRIMARY KEY (evaluation_id)
);
