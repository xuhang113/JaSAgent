package dev.jasagent.api.plugin;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 插件调用的追加记录。
 * 每次通行证决定都追加一条，之后可以按执行回看。
 */
public interface PluginCallStore {
    /**
     * 追加一条插件调用记录。
     * 阶段完成表示这条记录已经留下。
     *
     * @param entry 要留下的调用、决定和输出
     * @return 写入完成后结束的阶段
     */
    CompletionStage<Void> append(PluginCallEntry entry);

    /**
     * 读出某次执行已经留下的插件调用记录。
     *
     * @param executionId 要查询的执行
     * @return 完成后得到该执行的记录列表
     */
    CompletionStage<List<PluginCallEntry>> find(String executionId);
}
