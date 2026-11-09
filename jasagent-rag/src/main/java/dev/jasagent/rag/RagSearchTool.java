package dev.jasagent.rag;

import java.util.concurrent.CompletionStage;

/**
 * 把检索管道暴露成名为 rag.search 的调用入口。
 * 本身不做切段或打分，只把查询交给已经建好的管道。
 */
public final class RagSearchTool {
    /** 检索工具的注册名，调用方用它从目录里找到这个入口。 */
    public static final String NAME = "rag.search";
    private final RagPipeline pipeline;

    /**
     * 绑住一条检索管道，之后的调用都走它。
     *
     * @param pipeline 负责建索引和检索的管道
     */
    public RagSearchTool(RagPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 按给定查询检索，并返回质量门筛过的命中。
     *
     * @param query 查询词、范围、条数上限和是否把空结果当成失败
     * @return 管道的检索结果，完成时带上命中或空结果原因
     */
    public CompletionStage<RagResult> call(RagQuery query) {
        return pipeline.search(query);
    }
}
