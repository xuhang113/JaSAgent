package dev.jasagent.trace.lineage;

/**
 * 用户请求溯源和引用溯源用的库、表和字段长度。
 * 表建在本机已有的 jasagent 库里，和检查点用的是同一个 MySQL，不进执行过程的 H2 迁移。
 * 口令不属于这里：连接时只读用户主目录下的口令文件，本类的常量里没有口令。
 */
public final class LineageTables {
    /**
     * 本机 jasagent 库地址，指向 127.0.0.1:3306 的 jasagent。
     * 连接超时 2 秒，套接字超时 5 秒，允许公钥检索并关闭 SSL。地址里不放账号和口令。
     */
    public static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/jasagent"
            + "?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED";

    /**
     * 库账号。口令来自本机 MySQL 根口令文件，所以账号固定为 root，不从环境变量另取。
     */
    public static final String USER = "root";

    /**
     * 口令文件名。完整路径是用户主目录下的 {@code .config/urag-mysql-root.password}。
     * 只读第一行，不写回，不打进日志。
     */
    public static final String PASSWORD_FILE_NAME = "urag-mysql-root.password";

    /**
     * 用户请求表。一行是一次进入网关的请求，主键是 executionId。
     */
    public static final String REQUEST = "jasagent_request_lineage";

    /**
     * 模型调用表。同一次执行里的每次模型调用一行，traceId 与请求相同。
     */
    public static final String MODEL_CALL = "jasagent_request_model_call";

    /**
     * 工具调用表。同一次执行里的每次工具调用一行，traceId 与请求相同。
     */
    public static final String TOOL_CALL = "jasagent_request_tool_call";

    /**
     * 引用表。模型回答用到的检索片段一行，能按 executionId 追回这次回答。
     */
    public static final String CITATION = "jasagent_citation_lineage";

    /**
     * 示例 RAG 的服务名。检索命中没另给服务名时用它，表示片段来自本仓库的 RAG。
     */
    public static final String SERVICE_RAG = "rag";

    /**
     * 调用方没带 traceId 时生成的十六进制长度。和执行里已有的 traceId 长度一致。
     */
    public static final int GENERATED_TRACE_ID_LENGTH = 32;

    /**
     * executionId 的最大字符数，与执行表的 execution_id 列一致。
     */
    public static final int EXECUTION_ID_MAX = 64;

    /**
     * 调用方传入的 traceId 最大字符数。生成的 traceId 更短，见 {@link #GENERATED_TRACE_ID_LENGTH}。
     */
    public static final int TRACE_ID_MAX = 64;

    /**
     * 租户标识的最大字符数。
     */
    public static final int TENANT_ID_MAX = 255;

    /**
     * 用户标识的最大字符数。
     */
    public static final int USER_ID_MAX = 255;

    /**
     * 会话标识的最大字符数。
     */
    public static final int SESSION_ID_MAX = 255;

    /**
     * agent 实例标识的最大字符数。调用方把「打到哪台 agent」放进这个字段。
     */
    public static final int AGENT_ID_MAX = 255;

    /**
     * 网关名的最大字符数。这里只放网关标识，不放密钥或 Authorization。
     */
    public static final int GATEWAY_MAX = 255;

    /**
     * 模型调用、工具调用、引用行和回答标识的最大字符数。
     */
    public static final int CALL_ID_MAX = 64;

    /**
     * 模型供应方名的最大字符数。
     */
    public static final int PROVIDER_MAX = 64;

    /**
     * 模型名的最大字符数。
     */
    public static final int MODEL_MAX = 128;

    /**
     * 工具名的最大字符数。
     */
    public static final int TOOL_NAME_MAX = 255;

    /**
     * 回答标识的最大字符数。引用通过它挂到这次回答上。
     */
    public static final int ANSWER_ID_MAX = 64;

    /**
     * 检索服务名的最大字符数。示例 RAG 使用 {@link #SERVICE_RAG}。
     */
    public static final int SERVICE_MAX = 128;

    /**
     * 文档标识的最大字符数。RAG 的 docId 填在这里，可以是相对路径。
     */
    public static final int DOCUMENT_ID_MAX = 1024;

    /**
     * 片段标识的最大字符数。RAG 的 chunkId 填在这里。
     */
    public static final int FRAGMENT_ID_MAX = 1024;

    /**
     * 常量类不构造实例。
     */
    private LineageTables() {
    }
}
