package dev.jasagent.api.tool;

import dev.jasagent.api.pass.Pass;

/**
 * 目录处理完一次工具调用后的结果，说明工具有没有真正执行、输出是什么、通行证如何决定。
 *
 * @param executionId 调用所属的执行编号；只有输出文本的简写结果里为 null
 * @param name 工具名；简写结果里为 null
 * @param executed 工具体是否已经执行。拒绝和等待审批时为 false
 * @param output 工具输出；未执行时为空串
 * @param pass 这次调用拿到的通行证；内部工具没有通行证时为 null
 */
public record ToolResult(String executionId, String name, boolean executed, String output, Pass pass) {
    /**
     * 用输出文本和是否成功构造简写结果。执行编号、工具名和通行证留空，成功与否写入 executed。
     *
     * @param text 工具输出或失败说明
     * @param success 工具体是否执行成功
     */
    public ToolResult(String text, boolean success) {
        this(null, null, success, text, null);
    }

    /**
     * 返回工具输出，与 {@code output} 是同一段文本。
     *
     * @return 工具输出；未执行时为空串
     */
    public String text() {
        return output;
    }

    /**
     * 返回工具体是否已经执行，与 {@code executed} 是同一个标志。
     *
     * @return 工具体已经执行时为 true
     */
    public boolean success() {
        return executed;
    }
}
