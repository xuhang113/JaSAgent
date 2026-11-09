package dev.jasagent.workflow;

import dev.jasagent.persist.CheckpointStores;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.nio.file.Path;

/**
 * 按 JDBC 地址判断数据库种类、生成缓存键，并打开工作流检查点保存器。
 */
public final class CheckpointSavers {
    /**
     * 禁止实例化。保存器只通过静态方法打开。
     */
    private CheckpointSavers() {
    }

    /**
     * 判断 JDBC 地址是不是 PostgreSQL。
     *
     * @param jdbcUrl 数据源地址
     * @return 以 jdbc:postgresql: 开头时为 true
     */
    public static boolean postgres(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }

    /**
     * 给检查点保存器缓存用的键。地址空白时共用默认 MySQL 那一项。
     *
     * @param jdbcUrl 数据源地址
     * @return 缓存键；空白地址返回 default-mysql
     */
    public static String cacheKey(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "default-mysql";
        }
        return jdbcUrl;
    }

    /**
     * 按检查点文件和 JDBC 地址打开这次请求要使用的保存器。
     *
     * @param <S> 图状态的类型
     * @param sqliteFile SQLite 检查点文件，给需要本地文件的保存器使用
     * @param jdbcUrl JDBC 地址；空白时走默认保存器
     * @param serializer 图状态的序列化器
     * @return 已打开的检查点保存器
     */
    public static <S extends AgentState> BaseCheckpointSaver open(
            Path sqliteFile,
            String jdbcUrl,
            StateSerializer<S> serializer) throws Exception {
        return CheckpointStores.openRequested(sqliteFile, jdbcUrl, serializer);
    }
}
