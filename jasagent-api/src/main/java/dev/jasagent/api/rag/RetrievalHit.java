package dev.jasagent.api.rag;

import java.util.Map;

/**
 * 一条通过溯源校验的检索命中。
 * 词法、向量、标题三路都要写明名次和分数；某路没有命中时，该路名次和分数记为 0。
 *
 * @param sourceScope 片段来自用户目录还是工作区
 * @param uri 相对该范围 {@code rag/docs} 的路径，分隔符为 {@code /}
 * @param docId 文档标识，与 {@code uri} 一样使用相对 {@code rag/docs} 的路径
 * @param chunkId 带范围前缀的片段编号，避免两个目录的相同相对路径撞号
 * @param start 片段正文在原文件中的起始偏移
 * @param end 片段正文在原文件中的结束偏移，正文等于原文这一段
 * @param contentHash 片段按 UTF-8 计算的 SHA-256，小写十六进制
 * @param text 片段正文
 * @param channelRanks 三路名次。键为 {@code lexical}、{@code vector}、{@code title}，名次从 1 起，未入该路为 0
 * @param channelScores 三路原始分数，键与名次相同；未入该路时分数为 0
 * @param fusionScore 按倒数名次把三路融合成的分数
 * @param rerankScore 重排分数，留下来的命中落在 0 到 1；没有重排结果时为空
 */
public record RetrievalHit(
        SourceScope sourceScope,
        String uri,
        String docId,
        String chunkId,
        int start,
        int end,
        String contentHash,
        String text,
        Map<String, Integer> channelRanks,
        Map<String, Double> channelScores,
        double fusionScore,
        Double rerankScore) {
}
