package dev.jasagent.api.rag;

import java.util.concurrent.CompletionStage;

/**
 * 本地检索管道。
 * 一次查询走词法、向量和标题三路召回，再做融合、重排和溯源校验。
 */
public interface RagPipeline {
    /**
     * 按查询检索并返回命中与原因。
     * 文件读取放在虚拟线程上，阶段在检索完成之前不会结束。
     *
     * @param query 执行、用户、工作区、查询正文、条数和范围
     * @return 完成后得到命中列表和原因的阶段
     */
    CompletionStage<RagResult> retrieve(RagQuery query);
}
