package dev.jasagent.agentpass.durable;

/**
 * 通行证在已有 MySQL 里使用的表。和 020 的账号、登录会话放在同一个库，不另开数据库。
 * 建表语句不带口令。H2 测试会去掉只给 MySQL 用的引擎子句。
 */
public final class AgentPassTables {
    /**
     * 020 账号表。本模块只读登录名和租户，不改口令列的含义。
     */
    public static final String ACCOUNT = "jasagent_account";
    /**
     * 020 登录会话表。令牌列只存哈希。
     */
    public static final String LOGIN_SESSION = "jasagent_login_session";
    /**
     * 按租户保存的允许或拒绝。不再读用户目录里的 policies.yaml。
     */
    public static final String POLICY = "jasagent_agentpass_policy";
    /**
     * 未命中策略时留下的待批。同一用户批准或拒绝后仍留在这张表里。
     */
    public static final String PENDING = "jasagent_agentpass_pending";
    /**
     * 已签发一次性证的状态。核销标记重启后还在。
     */
    public static final String TICKET = "jasagent_agentpass_ticket";
    /**
     * 领证、核销、拒绝和重放流水。查询必须再按租户过滤。
     */
    public static final String AUDIT = "jasagent_agentpass_audit";

    /**
     * 账号表。口令列只占位，通行证不把口令原文写进来。
     */
    static final String ACCOUNT_DDL = """
            CREATE TABLE IF NOT EXISTS jasagent_account (
              login VARCHAR(64) NOT NULL,
              password_hash VARCHAR(512) NOT NULL,
              role VARCHAR(16) NOT NULL,
              tenant_id VARCHAR(64) NOT NULL,
              created_at VARCHAR(64) NOT NULL,
              PRIMARY KEY (login),
              UNIQUE KEY uk_jasagent_account_tenant (tenant_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
    /**
     * 登录会话表。token_hash 唯一，用来按登录令牌找回租户。
     */
    static final String LOGIN_SESSION_DDL = """
            CREATE TABLE IF NOT EXISTS jasagent_login_session (
              session_id VARCHAR(64) NOT NULL,
              token_hash CHAR(64) NOT NULL,
              login VARCHAR(64) NOT NULL,
              workspace VARCHAR(1024) NULL,
              created_at VARCHAR(64) NOT NULL,
              PRIMARY KEY (session_id),
              UNIQUE KEY uk_jasagent_login_token (token_hash),
              KEY idx_jasagent_login_session_login (login)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
    /**
     * 策略表。主键是租户、种类和对象，一行就是一条允许或拒绝。
     */
    static final String POLICY_DDL = """
            CREATE TABLE IF NOT EXISTS jasagent_agentpass_policy (
              tenant_id VARCHAR(64) NOT NULL,
              kind VARCHAR(16) NOT NULL,
              subject VARCHAR(128) NOT NULL,
              decision VARCHAR(16) NOT NULL,
              PRIMARY KEY (tenant_id, kind, subject)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
    /**
     * 待批表。同一次执行上的同一个工具只留一行，批准后更新决定而不是另开一行。
     */
    static final String PENDING_DDL = """
            CREATE TABLE IF NOT EXISTS jasagent_agentpass_pending (
              pass_id VARCHAR(64) NOT NULL,
              tenant_id VARCHAR(64) NOT NULL,
              user_id VARCHAR(64) NOT NULL,
              session_id VARCHAR(64) NOT NULL,
              execution_id VARCHAR(128) NOT NULL,
              tool_name VARCHAR(128) NOT NULL,
              plugin_id VARCHAR(128) NOT NULL,
              target VARCHAR(512) NOT NULL,
              decision VARCHAR(16) NOT NULL,
              reason VARCHAR(64) NOT NULL,
              created_at VARCHAR(64) NOT NULL,
              decided_at VARCHAR(64) NULL,
              PRIMARY KEY (pass_id),
              UNIQUE KEY uk_agentpass_pending_call (tenant_id, user_id, execution_id, tool_name, plugin_id),
              INDEX idx_agentpass_pending_user (tenant_id, user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
    /**
     * 证状态表。used 为 1 表示已经核销，进程重启后仍拒绝重放。
     */
    static final String TICKET_DDL = """
            CREATE TABLE IF NOT EXISTS jasagent_agentpass_ticket (
              pass_id VARCHAR(64) NOT NULL,
              tenant_id VARCHAR(128) NOT NULL,
              user_id VARCHAR(128) NOT NULL,
              session_id VARCHAR(128) NOT NULL,
              execution_id VARCHAR(128) NOT NULL,
              target_service VARCHAR(512) NOT NULL,
              tool_name VARCHAR(128) NOT NULL,
              expires_at VARCHAR(64) NOT NULL,
              used SMALLINT NOT NULL,
              PRIMARY KEY (pass_id),
              INDEX idx_agentpass_ticket_tenant (tenant_id, pass_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
    /**
     * 流水表。seq 只用来排序，不存放令牌或证明文。
     */
    static final String AUDIT_DDL = """
            CREATE TABLE IF NOT EXISTS jasagent_agentpass_audit (
              event_id VARCHAR(64) NOT NULL,
              seq BIGINT NOT NULL,
              kind VARCHAR(16) NOT NULL,
              pass_id VARCHAR(64) NOT NULL,
              tenant_id VARCHAR(128) NOT NULL,
              user_id VARCHAR(128) NOT NULL,
              execution_id VARCHAR(128) NOT NULL,
              target_service VARCHAR(512) NOT NULL,
              tool_name VARCHAR(128) NOT NULL,
              reason VARCHAR(128) NOT NULL,
              created_at VARCHAR(64) NOT NULL,
              PRIMARY KEY (event_id),
              INDEX idx_agentpass_audit_tenant (tenant_id, seq)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    /**
     * 禁止实例化。表名和建表语句只通过常量使用。
     */
    private AgentPassTables() {
    }
}
