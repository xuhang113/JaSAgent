package dev.jasagent.api.plugin;

import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.util.concurrent.CompletionStage;

/**
 * 由插件模块提供、可被工具目录装载的外部工具。
 * 目录按规格里的名称登记；调用前仍申请通行证，通过之后才执行工具体。
 */
public interface PluginTool {
    /**
     * 给出登记这个工具所需的名称、说明，以及它是不是内部工具。
     *
     * @return 工具规格
     */
    ToolSpec spec();

    /**
     * 执行一次工具调用，并把工具体写出的文本交回去。
     * 阻塞的抓取或写文件放在虚拟线程上，返回的阶段要等文本出来才完成。
     *
     * @param call 本次调用的用户、执行、目录、工具名和参数
     * @return 完成后得到工具体文本的阶段
     */
    CompletionStage<String> call(ToolCall call);
}
