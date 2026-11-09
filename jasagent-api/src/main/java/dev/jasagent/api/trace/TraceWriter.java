package dev.jasagent.api.trace;

import dev.jasagent.api.event.TraceEvent;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * 把一次执行的过程写进工作区里的可读日志，并再读出来。
 * 受理、工具、插件、中止、恢复、评估和终态都通过事件留下。
 */
public interface TraceWriter {
    /**
     * 把一条事件追加到该工作区里这次执行的追踪日志。
     * 阶段完成表示这次追加已经写完。
     *
     * @param workspace 日志所在的工作区
     * @param event 要追加的事件，其中含执行标识、类型、正文和附加字段
     * @return 写入完成后结束的阶段
     */
    CompletionStage<Void> event(Path workspace, TraceEvent event);

    /**
     * 读回该执行已经写下的可读日志正文。
     *
     * @param workspace 日志所在的工作区
     * @param executionId 要读取的执行
     * @return 完成后得到日志正文的阶段
     */
    CompletionStage<String> readLog(Path workspace, String executionId);
}
