package dev.jasagent.persist;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 在 MySQL 上准备执行记录、会话归属和会话附件需要的表。
 * 长文本用 MEDIUMTEXT，附件正文用 LONGBLOB。已有的表不会删掉，只在缺表时创建。
 * 不新建库，这些表都在已经打开的 jasagent 连接上。
 */
public final class MysqlSchemas {
    /**
     * 执行、消息、会话、工作区和会话附件表。tenant_id 与 user_id、session_id 一起区分租户。
     * 附件正文和元数据在同一行，路径列由服务端生成，不存用户文件名。
     */
    private static final String[] STATEMENTS = {
            """
            CREATE TABLE IF NOT EXISTS execution (
                execution_id VARCHAR(64) NOT NULL,
                user_id VARCHAR(255) NOT NULL,
                session_id VARCHAR(64) NOT NULL,
                workspace_path VARCHAR(1024) NOT NULL,
                status VARCHAR(32) NOT NULL,
                otel_trace_id VARCHAR(64),
                otel_root_span_id VARCHAR(32),
                tenant_id VARCHAR(128) NOT NULL DEFAULT 'local',
                CONSTRAINT pk_execution PRIMARY KEY (execution_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS agent (
                agent_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                role VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                CONSTRAINT pk_agent PRIMARY KEY (agent_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS task (
                task_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL,
                error_code VARCHAR(64),
                CONSTRAINT pk_task PRIMARY KEY (task_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS message (
                message_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                session_id VARCHAR(64) NOT NULL,
                role VARCHAR(32) NOT NULL,
                body MEDIUMTEXT NOT NULL,
                CONSTRAINT pk_message PRIMARY KEY (message_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS correction_note (
                note_id VARCHAR(64) NOT NULL,
                session_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                source VARCHAR(64) NOT NULL,
                text MEDIUMTEXT NOT NULL,
                CONSTRAINT pk_correction_note PRIMARY KEY (note_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS context_summary (
                summary_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                session_id VARCHAR(64) NOT NULL,
                text MEDIUMTEXT NOT NULL,
                CONSTRAINT pk_context_summary PRIMARY KEY (summary_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS llm_call (
                call_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                provider VARCHAR(64) NOT NULL,
                model_name VARCHAR(128) NOT NULL,
                latency_ms BIGINT NOT NULL,
                prompt_tokens INTEGER NOT NULL,
                completion_tokens INTEGER NOT NULL,
                total_tokens INTEGER NOT NULL,
                CONSTRAINT pk_llm_call PRIMARY KEY (call_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS tool_call (
                call_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                tool_name VARCHAR(255) NOT NULL,
                pass_decision VARCHAR(32),
                CONSTRAINT pk_tool_call PRIMARY KEY (call_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS plugin_call (
                call_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                plugin_id VARCHAR(255) NOT NULL,
                pass_decision VARCHAR(32),
                CONSTRAINT pk_plugin_call PRIMARY KEY (call_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS retrieval (
                retrieval_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                provenance MEDIUMTEXT NOT NULL,
                CONSTRAINT pk_retrieval PRIMARY KEY (retrieval_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS evaluation (
                evaluation_id VARCHAR(64) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                evaluator VARCHAR(128) NOT NULL,
                passed BOOLEAN NOT NULL,
                CONSTRAINT pk_evaluation PRIMARY KEY (evaluation_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS agent_session (
                tenant_id VARCHAR(128) NOT NULL,
                session_id VARCHAR(64) NOT NULL,
                user_id VARCHAR(255) NOT NULL,
                title VARCHAR(255) NOT NULL,
                model_name VARCHAR(128) NOT NULL,
                model_provider VARCHAR(64) NOT NULL,
                title_custom BOOLEAN NOT NULL,
                document MEDIUMTEXT NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                CONSTRAINT pk_agent_session PRIMARY KEY (tenant_id, session_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS agent_workspace (
                tenant_id VARCHAR(128) NOT NULL,
                user_id VARCHAR(255) NOT NULL,
                workspace_path VARCHAR(1024) NOT NULL,
                CONSTRAINT pk_agent_workspace PRIMARY KEY (tenant_id, user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS session_attachment (
                tenant_id VARCHAR(128) NOT NULL,
                session_id VARCHAR(64) NOT NULL,
                user_id VARCHAR(255) NOT NULL,
                attachment_id VARCHAR(64) NOT NULL,
                object_path VARCHAR(512) NOT NULL,
                media_type VARCHAR(64) NOT NULL,
                byte_size BIGINT NOT NULL,
                display_name VARCHAR(255) NOT NULL,
                content LONGBLOB NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                CONSTRAINT pk_session_attachment PRIMARY KEY (tenant_id, session_id, attachment_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
    };

    /**
     * 禁止实例化。建表通过 {@link #ensure(Connection)} 完成。
     */
    private MysqlSchemas() {
    }

    /**
     * 装上 MySQL 驱动并在这条连接上补齐表。驱动或建表失败时抛出 SQLException，消息里不含口令。
     *
     * @param connection 已经连上 jasagent 的连接
     * @throws SQLException 驱动缺失或某条建表语句失败
     */
    public static void ensure(Connection connection) throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("MySQL 驱动不在类路径上", ex);
        }
        try (Statement statement = connection.createStatement()) {
            for (String sql : STATEMENTS) {
                statement.execute(sql);
            }
            ensureSessionIndex(statement);
        }
    }

    /**
     * 给会话编号补索引，让网关能按 session_id 核对所有者，不必先知道租户。
     * 索引已经存在时忽略重复键名，不删已有数据。
     *
     * @param statement 当前连接上的语句
     * @throws SQLException 不是“索引已存在”的失败继续抛出
     */
    private static void ensureSessionIndex(Statement statement) throws SQLException {
        try {
            statement.execute("CREATE INDEX idx_agent_session_id ON agent_session (session_id)");
        } catch (SQLException ex) {
            if (ex.getErrorCode() != 1061) {
                throw ex;
            }
        }
    }
}
