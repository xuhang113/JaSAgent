CREATE TABLE execution (
    execution_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    workspace_path VARCHAR(2048) NOT NULL,
    status VARCHAR(32) NOT NULL,
    otel_trace_id VARCHAR(64),
    otel_root_span_id VARCHAR(32),
    error_code VARCHAR(64),
    output_text TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE agent (
    agent_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE TABLE task (
    task_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json TEXT,
    correlation_key VARCHAR(256),
    target_text VARCHAR(4000)
);

CREATE TABLE message (
    message_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE correction_note (
    note_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    source VARCHAR(64) NOT NULL,
    text TEXT NOT NULL
);

CREATE TABLE context_summary (
    summary_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE node_checkpoint (
    checkpoint_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    step_index INT NOT NULL,
    state_json TEXT NOT NULL,
    committed_at TIMESTAMP NOT NULL
);

CREATE TABLE llm_call (
    call_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    latency_ms BIGINT NOT NULL,
    prompt_tokens INT NOT NULL,
    completion_tokens INT NOT NULL,
    task VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE tool_call (
    call_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(256) NOT NULL,
    args_hash VARCHAR(128),
    pass_decision VARCHAR(32),
    pass_reason VARCHAR(512),
    result_json TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE plugin_call (
    call_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    plugin_id VARCHAR(256) NOT NULL,
    phase VARCHAR(64),
    pass_decision VARCHAR(32),
    pass_reason VARCHAR(512),
    status VARCHAR(32),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE retrieval (
    retrieval_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    scope VARCHAR(32),
    hits_json TEXT,
    reason VARCHAR(128),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE evaluation (
    evaluation_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    evaluator VARCHAR(128) NOT NULL,
    passed BOOLEAN NOT NULL,
    detail VARCHAR(4000),
    created_at TIMESTAMP NOT NULL
);
