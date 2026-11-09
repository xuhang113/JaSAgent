package dev.jasagent.tool;

import dev.jasagent.api.tool.ToolCallEntry;
import dev.jasagent.api.tool.ToolCallStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把工具调用记录留在内存里，并按执行编号查回。
 */
public final class MemoryToolCallStore implements ToolCallStore {
    private final List<ToolCallEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * 追加一条工具调用记录。
     *
     * @param entry 要保存的调用记录
     * @return 立即完成的空阶段
     */
    @Override
    public CompletionStage<Void> append(ToolCallEntry entry) {
        entries.add(entry);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 返回同一执行编号下已经保存的调用记录。
     *
     * @param executionId 要查询的执行编号
     * @return 立即完成、内容为匹配记录的阶段
     */
    @Override
    public CompletionStage<List<ToolCallEntry>> find(String executionId) {
        return CompletableFuture.completedFuture(entries.stream()
                .filter(entry -> executionId.equals(entry.executionId()))
                .toList());
    }
}
