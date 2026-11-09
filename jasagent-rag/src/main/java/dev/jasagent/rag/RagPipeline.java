package dev.jasagent.rag;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 把建索引、三路召回、倒数排名融合、重排和质量门串成一次检索。
 * 词法、向量、标题三路一起提交，融合后只把靠前的一段候选送去重排。
 */
public final class RagPipeline {
    private final IndexStore store;
    private final Reranker reranker;
    private final Embedder embedder;
    private final RagConfig config;
    private final PathFanout fanout;

    /**
     * 用哈希嵌入、默认阈值和虚拟线程执行器建一条管道。
     *
     * @param userDirectory 用户级语料和索引的根目录
     * @param workspaceDirectory 工作区级语料和索引的根目录
     * @param reranker 给融合候选打相关性分的重排器
     */
    public RagPipeline(Path userDirectory, Path workspaceDirectory, Reranker reranker) {
        this(userDirectory, workspaceDirectory, reranker, new HashingEmbedder(), RagConfig.defaults(), VirtualIo.executor());
    }

    /**
     * 按调用方给的嵌入器、阈值和执行器装配管道。
     *
     * @param userDirectory 用户级语料和索引的根目录
     * @param workspaceDirectory 工作区级语料和索引的根目录
     * @param reranker 给融合候选打相关性分的重排器
     * @param embedder 把查询和正文变成向量的嵌入器
     * @param config 重排下限、融合平滑常数和重排候选池大小
     * @param pathExecutor 并行跑三路召回的执行器
     */
    public RagPipeline(Path userDirectory, Path workspaceDirectory, Reranker reranker, Embedder embedder, RagConfig config, Executor pathExecutor) {
        this.store = new IndexStore(userDirectory, workspaceDirectory);
        this.reranker = reranker;
        this.embedder = embedder;
        this.config = config;
        this.fanout = new PathFanout(pathExecutor);
    }

    /**
     * 重建一个范围的索引，并在完成后给出写入的段数。
     * 副作用：重写该范围 rag/index 下的索引文件。
     *
     * @param scope 要重建的用户级或工作区级语料
     * @return 写入索引的段数；该范围没有可索引文件时为零
     */
    public CompletionStage<Integer> index(RagScope scope) {
        return VirtualIo.supply(() -> store.rebuild(scope));
    }

    /**
     * 读出范围内的索引，并行跑三路召回，再融合、重排并过质量门。
     * 查询原文为空引用时按空串检索，因而不会召回段。
     *
     * @param query 查询词、范围、条数上限和空结果是否算失败
     * @return 质量门筛过的命中；没有合格段时带上空结果原因
     */
    public CompletionStage<RagResult> search(RagQuery query) {
        String text = query.text() == null ? "" : query.text();
        return VirtualIo.supply(() -> load(query)).thenCompose(chunks -> {
            List<Callable<List<Ranked>>> tasks = List.of(
                    () -> Retrieval.lexical(text, chunks),
                    () -> Retrieval.vector(text, chunks, embedder),
                    () -> Retrieval.title(text, chunks));
            return fanout.all(tasks).thenCompose(paths -> rerank(query, text, chunks, paths));
        });
    }

    /**
     * 把三路名次融合成一个序，只把融合分最高的若干段交给重排器。
     * 三路顺序固定为词法、向量、标题。重排分数个数和候选个数不一致时以内部错误失败。没有候选时直接走质量门的空结果。
     *
     * @param query 提供条数上限，以及空结果是否算失败
     * @param text 交给重排器的查询原文
     * @param chunks 这次读到的全部索引段，用来按段标识补回正文和出处
     * @param paths 三路召回结果，下标 0、1、2 分别是词法、向量、标题
     * @return 重排并过完质量门的结果
     */
    private CompletionStage<RagResult> rerank(RagQuery query, String text, List<IndexedChunk> chunks, List<List<Ranked>> paths) {
        Map<String, List<Ranked>> named = new LinkedHashMap<>();
        named.put("lexical", paths.get(0));
        named.put("vector", paths.get(1));
        named.put("title", paths.get(2));
        List<ReciprocalRankFusion.Fused> fused = ReciprocalRankFusion.fuse(named, config.rrfK());
        int pool = Math.min(config.rerankPool(), fused.size());
        List<ReciprocalRankFusion.Fused> top = fused.subList(0, pool);
        if (top.isEmpty()) {
            return CompletableFuture.completedFuture(QualityGate.apply(List.of(), config.minRerank(), query.topK(), query.required()));
        }
        Map<String, IndexedChunk> byId = new HashMap<>();
        for (IndexedChunk chunk : chunks) {
            byId.put(chunk.chunkId(), chunk);
        }
        List<RerankCandidate> candidates = new ArrayList<>();
        for (ReciprocalRankFusion.Fused row : top) {
            IndexedChunk chunk = byId.get(row.chunkId());
            candidates.add(new RerankCandidate(chunk.chunkId(), chunk.content(), chunk.title(), chunk.uri(), chunk.scope().wire()));
        }
        return reranker.score(text, List.copyOf(candidates)).thenApply(scores -> {
            if (scores == null || scores.size() != top.size()) {
                throw new AgentException(ErrorCode.INTERNAL, "rerank 分数数量不匹配");
            }
            List<RagHit> hits = new ArrayList<>();
            for (int i = 0; i < top.size(); i++) {
                ReciprocalRankFusion.Fused row = top.get(i);
                IndexedChunk chunk = byId.get(row.chunkId());
                Double raw = scores.get(i);
                double score = raw == null ? Double.NaN : raw;
                hits.add(new RagHit(
                        chunk.scope().wire(),
                        chunk.uri(),
                        chunk.docId(),
                        chunk.chunkId(),
                        chunk.start(),
                        chunk.end(),
                        chunk.contentHash(),
                        chunk.content(),
                        row.path("lexical"),
                        row.path("vector"),
                        row.path("title"),
                        row.fusionScore(),
                        score));
            }
            return QualityGate.apply(hits, config.minRerank(), query.topK(), query.required());
        });
    }

    /**
     * 按查询指定的范围读索引；没指定范围时两个范围都读。
     *
     * @param query 提供要读的范围，空范围表示全部
     * @return 这些范围里已经建好的索引段，某个范围还没有索引文件时跳过它
     */
    private List<IndexedChunk> load(RagQuery query) throws java.io.IOException {
        Set<RagScope> scopes = query.scopes() == null || query.scopes().isEmpty()
                ? EnumSet.allOf(RagScope.class)
                : EnumSet.copyOf(query.scopes());
        List<IndexedChunk> chunks = new ArrayList<>();
        for (RagScope scope : scopes) {
            chunks.addAll(store.read(scope));
        }
        return chunks;
    }
}
