package dev.jasagent.plugin;

import dev.jasagent.api.plugin.PluginCallEntry;
import dev.jasagent.api.plugin.PluginCallStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 只放在内存里的插件调用记录。
 * 测试用它查看宿主写出的通行证决定，进程退出后记录消失。
 */
public final class MemoryPluginCallStore implements PluginCallStore {
    private final List<PluginCallEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * 追加一条调用记录。
     * 副作用：把记录放进内存列表，之后按执行标识可以查到。
     *
     * @param entry 一次插件调用的决定、输出和成败
     * @return 已经完成的空结果
     */
    @Override
    public CompletionStage<Void> append(PluginCallEntry entry) {
        entries.add(entry);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 查出同一次执行写下的全部调用记录，顺序与追加顺序一致。
     *
     * @param executionId 执行标识
     * @return 该执行的记录；没有时为空列表
     */
    @Override
    public CompletionStage<List<PluginCallEntry>> find(String executionId) {
        return CompletableFuture.completedFuture(entries.stream()
                .filter(entry -> executionId.equals(entry.executionId()))
                .toList());
    }
}
