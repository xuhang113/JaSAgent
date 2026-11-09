package dev.jasagent.api.otel;

import java.util.concurrent.CompletionStage;

/**
 * 为一次执行打开和结束追踪 span。
 * 根 span 定下 trace，之后的子 span 挂在调用方给出的父 span 上，并沿用同一个 trace。
 */
public interface AgentTracer {
    /**
     * 为这次执行打开根 span。
     * 根 span 使用执行 span 的名称，并带上该执行标识。
     *
     * @param executionId 要开始追踪的执行
     * @return 完成后得到已打开的根 span
     */
    CompletionStage<AgentSpan> startRoot(String executionId);

    /**
     * 在已有 trace 上打开一个子 span。
     * 子 span 沿用传入的 trace，父级是给出的 span，名称由调用方按这一步的操作传入。
     *
     * @param name 子 span 名称
     * @param executionId 这个 span 所属的执行，会写入 span 属性
     * @param traceId 要延续的 trace；同一次执行的根 span 和子 span 使用同一个
     * @param parentSpanId 父 span 的标识
     * @return 完成后得到已打开的子 span
     */
    CompletionStage<AgentSpan> startChild(String name, String executionId, String traceId, String parentSpanId);

    /**
     * 结束一个已经打开的 span，使它不再作为当前 span。
     * trace 标识和 span 标识在结束后仍留在这次执行的记录里。
     *
     * @param span 要结束的 span
     * @return span 结束后完成的阶段
     */
    CompletionStage<Void> end(AgentSpan span);
}
