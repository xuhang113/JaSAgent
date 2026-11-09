package dev.jasagent.tool;

import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 提供当前 UTC 时间。它是内部工具，调用前不申请通行证。
 */
public final class ClockTool implements Tool {
    /**
     * 返回时钟工具的名字和说明，并把它标成内部工具。
     *
     * @return 名字为 clock、标记为内部的工具规格
     */
    @Override
    public ToolSpec spec() {
        return new ToolSpec("clock", "UTC time", true);
    }

    /**
     * 立即返回当前 UTC 时刻的文本，不读取调用参数。
     *
     * @param call 本次工具调用；时钟不使用其中的参数
     * @return 立即完成、内容为当前时刻的阶段
     */
    @Override
    public CompletionStage<String> call(ToolCall call) {
        return CompletableFuture.completedFuture(Instant.now().toString());
    }
}
