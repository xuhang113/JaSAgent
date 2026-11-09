package dev.jasagent.workflow;

import dev.jasagent.persist.CheckpointStores;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.nio.file.Path;

public final class CheckpointSavers {
    private CheckpointSavers() {
    }

    public static boolean postgres(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }

    public static String cacheKey(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "default-mysql";
        }
        return jdbcUrl;
    }

    public static <S extends AgentState> BaseCheckpointSaver open(
            Path sqliteFile,
            String jdbcUrl,
            StateSerializer<S> serializer) throws Exception {
        return CheckpointStores.openRequested(sqliteFile, jdbcUrl, serializer);
    }
}
