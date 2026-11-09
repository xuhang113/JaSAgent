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

class PersistProcessTest {
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
    private static final Set<String> EXECUTION_COLUMNS = Set.of(
            "execution_id",
            "user_id",
            "session_id",
            "workspace_path",
            "status",
            "otel_trace_id",
            "otel_root_span_id");
    private static final Set<String> CHECKPOINT_TABLES = Set.of(
            "node_checkpoint",
            "lg4jcheckpoint",
            "lg4jthread",
            "lg4jthreadtag");

    @TempDir
    Path temp;

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

    @Test
    void defaultH2FileUnderWorkspaceData() throws Exception {
        Path workspace = temp.resolve("workspace");
        try (ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of())) {
            Path file = workspace.resolve(".jasagent").resolve("data").resolve("jasagent");
            assertTrue(database.settings().url().startsWith("jdbc:h2:file:" + file.toAbsolutePath()));
            assertTrue(Files.isRegularFile(Path.of(file.toAbsolutePath() + ".mv.db")));
        }
    }

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

    @Test
    void executionColumnsExist() throws Exception {
        assertTrue(columnNames(temp.resolve("workspace"), "execution").containsAll(EXECUTION_COLUMNS));
    }

    @Test
    void processTablesExist() throws Exception {
        assertTrue(tableNames(temp.resolve("workspace")).containsAll(PROCESS_TABLES));
    }

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

    @Test
    void defaultCheckpointUsesMysqlAndKeepsExistingTables() throws Exception {
        Path workspace = temp.resolve("workspace");
        CheckpointSelection selection = CheckpointStores.select(Map.of());
        assertEquals(CheckpointBackend.MYSQL, selection.backend());
        assertTrue(selection.jdbcUrl().startsWith("jdbc:mysql://127.0.0.1:3306/urag"), selection.jdbcUrl());
        assertFalse(selection.jdbcUrl().contains("password="), selection.toString());
        assertFalse(selection.toString().contains(selection.password()));
        Set<String> before = mysqlTables(selection);
        assertTrue(before.stream().anyMatch(name -> name.equalsIgnoreCase("source_document")), before.toString());
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

    @Test
    void executionDatabaseHasNoCustomCheckpointTable() throws Exception {
        Set<String> names = tableNames(temp.resolve("workspace"));
        for (String table : CHECKPOINT_TABLES) {
            assertFalse(names.contains(table), table);
        }
    }

    private static Map<String, String> sqliteCheckpoint() {
        return Map.of("JASAGENT_CHECKPOINT", "sqlite");
    }

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

    private static ExecutionRow execution(String executionId, Path workspace) {
        return new ExecutionRow(executionId, "user-a", executionId, workspace.toString(), "ACCEPTED", "a".repeat(32), "b".repeat(16));
    }

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

    private static int sqliteCheckpointCount(Path sqlite) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath());
             ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM LG4JCheckpoint")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

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
