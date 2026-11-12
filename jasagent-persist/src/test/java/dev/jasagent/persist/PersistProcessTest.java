package dev.jasagent.persist;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.MySQLSaverV2;
import org.bsc.langgraph4j.checkpoint.SQLiteSaverV2;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 核对执行库迁移、执行与模型调用读写，以及节点检查点在 SQLite、MySQL 和 PostgreSQL 选择下的落盘行为。 */
class PersistProcessTest {
    /** 迁移后执行库里必须存在的流程表，不含 execution 主表本身。 */
    private static final Set<String> PROCESS_TABLES = Set.of(
            "agent",
            "task",
            "message",
            "correction_note",
            "context_summary",
            "llm_call",
            "tool_call",
            "plugin_call",
            "retrieval",
            "evaluation");
    /** execution 表必须带上的列，含用户、会话、工作区和两条追踪编号。 */
    private static final Set<String> EXECUTION_COLUMNS = Set.of(
            "execution_id",
            "user_id",
            "session_id",
            "workspace_path",
            "status",
            "otel_trace_id",
            "otel_root_span_id");
    /** 这些表名不该出现在执行库里。检查点由 langgraph4j 的存储器另库存放。 */
    private static final Set<String> CHECKPOINT_TABLES = Set.of(
            "node_checkpoint",
            "lg4jcheckpoint",
            "lg4jthread",
            "lg4jthreadtag");

    @TempDir
    Path temp;

    /**
     * 工作区根上已经有一个名为 jasagent 的普通文件时，执行库和检查点仍应写到隐藏数据目录，不能把该文件当成目录。
     *
     * @throws Exception 建目录、开库或断言失败时抛出
     */
    @Test
    void hiddenDataDirectoryWhenJasagentIsAFile() throws Exception {
        Path workspace = temp.resolve("repo");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("jasagent"), "#!/bin/sh\n");
        Path database = workspace.resolve(".jasagent").resolve("data").resolve("jasagent");
        assertEquals(database.toAbsolutePath(), JdbcSettings.defaultH2File(workspace));
        try (ExecutionDatabase opened = ExecutionDatabase.open(workspace, Map.of())) {
            assertTrue(opened.settings().url().startsWith("jdbc:h2:file:" + database.toAbsolutePath()));
            assertTrue(Files.isRegularFile(Path.of(database.toAbsolutePath() + ".mv.db")));
        }
        assertTrue(Files.isRegularFile(workspace.resolve("jasagent")));
        assertFalse(Files.isDirectory(workspace.resolve("jasagent")));
        try (NodeCheckpoints checkpoints = NodeCheckpoints.open(workspace, sqliteCheckpoint())) {
            Path sqlite = workspace.resolve(".jasagent").resolve("data").resolve("checkpoints.db");
            assertEquals(sqlite.toAbsolutePath(), checkpoints.sqliteFile());
            assertTrue(Files.isRegularFile(sqlite));
        }
    }

    /**
     * 未配置 JDBC 时，执行库应落到工作区隐藏数据目录下的 H2 文件，并真正写出库文件。
     *
     * @throws Exception 开库或断言失败时抛出
     */
    @Test
    void defaultH2FileUnderWorkspaceData() throws Exception {
        Path workspace = temp.resolve("workspace");
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of())) {
            Path file = workspace.resolve(".jasagent").resolve("data").resolve("jasagent");
            assertTrue(database.settings().url().startsWith("jdbc:h2:file:" + file.toAbsolutePath()));
            assertTrue(Files.isRegularFile(Path.of(file.toAbsolutePath() + ".mv.db")));
        }
    }

    /**
     * 环境变量里的 JDBC 地址应盖过默认 H2 位置，并且不要在工作区数据目录再生成一份默认库文件。
     *
     * @throws Exception 开库或断言失败时抛出
     */
    @Test
    void jdbcUrlOverridesDefault() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path custom = temp.resolve("custom-db");
        String url = "jdbc:h2:file:" + custom.toAbsolutePath();
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of("JASAGENT_JDBC_URL", url))) {
            assertEquals(url, database.settings().url());
        }
        assertTrue(Files.isRegularFile(Path.of(custom.toAbsolutePath() + ".mv.db")));
        assertFalse(Files.exists(workspace.resolve(".jasagent").resolve("data").resolve("jasagent.mv.db")));
    }

    /**
     * 打开执行库后，execution 表应包含追踪和会话所需的列。
     *
     * @throws Exception 查询信息模式失败时抛出
     */
    @Test
    void executionColumnsExist() throws Exception {
        assertTrue(columnNames(temp.resolve("workspace"), "execution").containsAll(EXECUTION_COLUMNS));
    }

    /**
     * 打开执行库后，流程相关的表都应已经由迁移建出。
     *
     * @throws Exception 查询信息模式失败时抛出
     */
    @Test
    void processTablesExist() throws Exception {
        assertTrue(tableNames(temp.resolve("workspace")).containsAll(PROCESS_TABLES));
    }

    /**
     * 写入执行后再读回，OpenTelemetry 追踪编号和根 span 编号应保持原样。
     *
     * @throws Exception 写入、读取或等待异步结果失败时抛出
     */
    @Test
    void executionRoundTripKeepsOtelIds() throws Exception {
        Path workspace = temp.resolve("workspace");
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of())) {
            ExecutionStore store = new ExecutionStore(database);
            String executionId = UUID.randomUUID().toString();
            ExecutionRow row = new ExecutionRow(
                    executionId,
                    "user-a",
                    executionId,
                    workspace.toString(),
                    "ACCEPTED",
                    "a".repeat(32),
                    "b".repeat(16));
            join(store.insertExecution(row));
            assertEquals(row, join(store.findExecution(executionId)));
        }
    }

    /**
     * 模型调用应能按调用编号读回供应商、模型、耗时和 token 数。
     *
     * @throws Exception 写入、读取或等待异步结果失败时抛出
     */
    @Test
    void llmCallStoresProviderModelLatencyTokens() throws Exception {
        Path workspace = temp.resolve("workspace");
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of())) {
            ExecutionStore store = new ExecutionStore(database);
            String executionId = UUID.randomUUID().toString();
            join(store.insertExecution(execution(executionId, workspace)));
            LlmCallRow call = new LlmCallRow(UUID.randomUUID().toString(), executionId, "deepseek", "deepseek-flash", 42, 10, 4, 14);
            join(store.insertLlmCall(call));
            assertEquals(call, join(store.findLlmCall(call.callId())));
        }
    }

    /**
     * 任务先记为运行中，标记失败后应能读到失败状态和错误码。
     *
     * @throws Exception 写入或等待异步结果失败时抛出
     */
    @Test
    void taskFailedStatusIsStored() throws Exception {
        Path workspace = temp.resolve("workspace");
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of())) {
            ExecutionStore store = new ExecutionStore(database);
            String executionId = UUID.randomUUID().toString();
            String taskId = UUID.randomUUID().toString();
            join(store.insertExecution(execution(executionId, workspace)));
            join(store.insertTask(taskId, executionId, "RUNNING"));
            join(store.markTaskFailed(taskId, "TOOL"));
            assertEquals("FAILED", join(store.taskStatus(taskId)));
            assertEquals("TOOL", join(store.taskErrorCode(taskId)));
        }
    }

    /**
     * JDBC 口令只留在连接设置里，不应出现在工作区除数据库文件以外的普通文件中。
     *
     * @throws Exception 开库或扫描工作区失败时抛出
     */
    @Test
    void passwordIsNotWrittenIntoWorkspace() throws Exception {
        Path workspace = temp.resolve("workspace");
        String marker = "placeholder-not-stored";
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of("JASAGENT_JDBC_PASSWORD", marker))) {
            assertEquals(marker, database.settings().password());
        }
        try (Stream<Path> walk = Files.walk(workspace)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".mv.db") || name.endsWith(".trace.db")) {
                    continue;
                }
                assertFalse(Files.readString(path).contains(marker), path.toString());
            }
        }
    }

    /**
     * 指定 sqlite 模式时，节点检查点应使用 SQLite 存储器，提交后库里应出现检查点行。
     *
     * @throws Exception 开库、提交或统计行数失败时抛出
     */
    @Test
    void nodeCheckpointUsesSqliteSaver() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        try (NodeCheckpoints checkpoints = NodeCheckpoints.open(workspace, sqliteCheckpoint())) {
            assertEquals(CheckpointBackend.SQLITE, checkpoints.backend());
            assertInstanceOf(SQLiteSaverV2.class, checkpoints.saver());
            Path sqlite = workspace.resolve(".jasagent").resolve("data").resolve("checkpoints.db");
            assertEquals(sqlite.toAbsolutePath(), checkpoints.sqliteFile());
            join(checkpoints.commitBeforeNextNode(executionId, "find", "act", Map.<String, Object>of("step", "find"), () -> {
            }));
            assertTrue(sqliteCheckpointCount(sqlite) > 0);
        }
    }

    /**
     * 把检查点存储器交给图编译后，一次调用应留下该执行的检查点。
     *
     * @throws Exception 编译、调用或列出检查点失败时抛出
     */
    @Test
    void graphCompileUsesCheckpointSaver() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        try (NodeCheckpoints checkpoints = NodeCheckpoints.open(workspace, sqliteCheckpoint())) {
            StateGraph<AgentState> graph = new StateGraph<>(AgentState::new)
                    .addNode("find", node_async(state -> Map.<String, Object>of("seen", "yes")))
                    .addEdge(START, "find")
                    .addEdge("find", END);
            CompiledGraph<AgentState> compiled = graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpoints.saver())
                    .releaseThread(false)
                    .build());
            Optional<AgentState> output = compiled.invoke(
                    Map.<String, Object>of("input", "q"),
                    RunnableConfig.builder().threadId(executionId).build());
            assertTrue(output.isPresent());
            assertFalse(checkpoints.saver().list(RunnableConfig.builder().threadId(executionId).build()).isEmpty());
        }
    }

    /**
     * 关闭检查点后再打开同一工作区，应还能读到上次提交的节点、下一节点和状态。
     *
     * @throws Exception 提交、重开或读取失败时抛出
     */
    @Test
    void checkpointSurvivesReopen() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        try (NodeCheckpoints first = NodeCheckpoints.open(workspace, sqliteCheckpoint())) {
            join(first.commitBeforeNextNode(executionId, "find", "act", Map.<String, Object>of("step", "find"), () -> {
            }));
        }
        try (NodeCheckpoints second = NodeCheckpoints.open(workspace, sqliteCheckpoint())) {
            Optional<Checkpoint> latest = join(second.latest(executionId));
            assertTrue(latest.isPresent());
            assertEquals("find", latest.get().nodeId());
            assertEquals("act", latest.get().nextNodeId());
            assertEquals("find", latest.get().state().get("step"));
        }
    }

    /**
     * 状态无法序列化时提交应失败，并且不得执行进入下一节点的动作。
     *
     * @throws Exception 打开检查点失败时抛出
     */
    @Test
    void failedCheckpointDoesNotEnterNextNode() throws Exception {
        Path workspace = temp.resolve("workspace");
        String executionId = UUID.randomUUID().toString();
        try (NodeCheckpoints checkpoints = NodeCheckpoints.open(workspace, sqliteCheckpoint())) {
            AtomicBoolean entered = new AtomicBoolean(false);
            Map<String, Object> state = new HashMap<>();
            state.put("bad", new Object());
            AgentException error = assertThrows(AgentException.class, () -> join(
                    checkpoints.commitBeforeNextNode(executionId, "find", "act", state, () -> entered.set(true))));
            assertEquals(ErrorCode.PERSIST, error.code());
            assertEquals(executionId, error.executionId());
            assertFalse(entered.get());
        }
    }

    /** PostgreSQL 地址应选中 Postgres 存储器。本机没有该库时打开失败，且不应留下 SQLite 检查点文件。 */
    @Test
    void postgresUrlSelectsPostgresSaver() {
        Path workspace = temp.resolve("workspace");
        AgentException error = assertThrows(AgentException.class, () -> NodeCheckpoints.open(
                workspace,
                Map.of("JASAGENT_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/jasagent")));
        assertEquals(ErrorCode.PERSIST, error.code());
        assertTrue(stackOf(error).contains("org.bsc.langgraph4j.checkpoint.PostgresSaverV2"), stackOf(error));
        assertFalse(Files.exists(workspace.resolve(".jasagent").resolve("data").resolve("checkpoints.db")));
    }

    /**
     * 未指定后端时应走本机 jasagent 库，提交后能重开读回，并且不得删掉该库里原有的表，也不在工作区留下 SQLite 文件。
     *
     * @throws Exception 连库、提交或比对表清单失败时抛出
     */
    @Test
    void defaultCheckpointUsesMysqlAndKeepsExistingTables() throws Exception {
        Path workspace = temp.resolve("workspace");
        CheckpointSelection selection = CheckpointStores.select(Map.of());
        assertEquals(CheckpointBackend.MYSQL, selection.backend());
        assertTrue(selection.jdbcUrl().startsWith("jdbc:mysql://127.0.0.1:3306/jasagent"), selection.jdbcUrl());
        assertEquals("jasagent", selection.database());
        assertFalse(selection.jdbcUrl().contains("password="), selection.toString());
        assertFalse(selection.toString().contains(selection.password()));
        Set<String> before = mysqlTables(selection);
        String executionId = UUID.randomUUID().toString();
        try (NodeCheckpoints checkpoints = NodeCheckpoints.open(workspace, Map.of())) {
            assertEquals(CheckpointBackend.MYSQL, checkpoints.backend());
            assertInstanceOf(MySQLSaverV2.class, checkpoints.saver());
            assertNull(checkpoints.sqliteFile());
            join(checkpoints.commitBeforeNextNode(executionId, "find", "act", Map.<String, Object>of("step", "find"), () -> {
            }));
        }
        try (NodeCheckpoints again = NodeCheckpoints.open(workspace, Map.of())) {
            Optional<Checkpoint> latest = join(again.latest(executionId));
            assertTrue(latest.isPresent());
            assertEquals("find", latest.get().nodeId());
            assertEquals("act", latest.get().nextNodeId());
            assertEquals("find", latest.get().state().get("step"));
        }
        Set<String> after = mysqlTables(selection);
        assertTrue(after.containsAll(before), after.toString());
        for (String table : List.of("LG4JThread", "LG4JThreadTag", "LG4JCheckpoint")) {
            assertTrue(after.stream().anyMatch(table::equalsIgnoreCase), table + " " + after);
        }
        assertFalse(Files.exists(workspace.resolve(".jasagent").resolve("data").resolve("checkpoints.db")));
    }

    /**
     * 执行库迁移不应自建节点检查点表或 langgraph4j 的检查点表。
     *
     * @throws Exception 查询信息模式失败时抛出
     */
    @Test
    void executionDatabaseHasNoCustomCheckpointTable() throws Exception {
        Set<String> names = tableNames(temp.resolve("workspace"));
        for (String table : CHECKPOINT_TABLES) {
            assertFalse(names.contains(table), table);
        }
    }

    /**
     * 测试用的检查点环境，强制走 SQLite。
     *
     * @return 只含 sqlite 模式的环境
     */
    private static Map<String, String> sqliteCheckpoint() {
        return Map.of("JASAGENT_CHECKPOINT", "sqlite");
    }

    /**
     * 查询当前 MySQL 库里的全部表名，用来确认打开检查点没有丢掉原表。
     *
     * @param selection 含地址和账号的 MySQL 选择
     * @return 表名集合，保留库返回的大小写
     * @throws Exception 装载驱动或查询失败时抛出
     */
    private static Set<String> mysqlTables(CheckpointSelection selection) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = DriverManager.getConnection(selection.jdbcUrl(), selection.user(), selection.password());
             ResultSet rows = connection.createStatement().executeQuery(
                     "select table_name from information_schema.tables where table_schema = database()")) {
            Set<String> names = new HashSet<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names;
        }
    }

    /**
     * 造一行用于插入的执行记录，用户固定，追踪编号用重复字符占位，状态为已接受。
     *
     * @param executionId 执行编号
     * @param workspace 记到记录里的工作区
     * @return 可直接插入的执行行
     */
    private static ExecutionRow execution(String executionId, Path workspace) {
        return new ExecutionRow(executionId, "user-a", executionId, workspace.toString(), "ACCEPTED", "a".repeat(32), "b".repeat(16));
    }

    /**
     * 打开工作区执行库并读出公共模式下的表名，统一成小写。
     *
     * @param workspace 工作区
     * @return 小写表名
     * @throws Exception 开库或查询失败时抛出
     */
    private static Set<String> tableNames(Path workspace) throws Exception {
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of());
             Connection connection = DriverManager.getConnection(database.settings().url(), database.settings().user(), database.settings().password());
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            Set<String> names = new HashSet<>();
            while (rows.next()) {
                names.add(rows.getString(1).toLowerCase());
            }
            return names;
        }
    }

    /**
     * 读出指定表的列名，统一成小写。
     *
     * @param workspace 工作区
     * @param table 表名
     * @return 小写列名
     * @throws Exception 开库或查询失败时抛出
     */
    private static Set<String> columnNames(Path workspace, String table) throws Exception {
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of());
             Connection connection = DriverManager.getConnection(database.settings().url(), database.settings().user(), database.settings().password());
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = '" + table.toUpperCase() + "'")) {
            Set<String> names = new HashSet<>();
            while (rows.next()) {
                names.add(rows.getString(1).toLowerCase());
            }
            return names;
        }
    }

    /**
     * 统计 SQLite 检查点表里的行数。
     *
     * @param sqlite 检查点库文件
     * @return 行数
     * @throws Exception 连接或查询失败时抛出
     */
    private static int sqliteCheckpointCount(Path sqlite) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM LG4JCheckpoint")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /**
     * 把异常和它的原因链、栈展开成一段文本，用来确认失败发生在哪个存储器类。
     *
     * @param error 被断言抛出的异常
     * @return 异常链和栈的文本
     */
    private static String stackOf(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            builder.append(current).append('\n');
            for (StackTraceElement element : current.getStackTrace()) {
                builder.append(element).append('\n');
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    /**
     * 在三十秒内等待异步阶段结束。运行时异常和错误原样抛出；被中断时会重新置上当前线程的中断标记。
     *
     * @param <T> 阶段的结果类型
     * @param stage 要等待的阶段
     * @return 阶段的结果
     */
    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
