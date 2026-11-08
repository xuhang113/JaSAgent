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

public final class RagPipeline {
    private final IndexStore store;
    private final Reranker reranker;
    private final Embedder embedder;
    private final RagConfig config;
    private final PathFanout fanout;

    public RagPipeline(Path userDirectory, Path workspaceDirectory, Reranker reranker) {
        this(userDirectory, workspaceDirectory, reranker, new HashingEmbedder(), RagConfig.defaults(), VirtualIo.executor());
    }

    public RagPipeline(Path userDirectory, Path workspaceDirectory, Reranker reranker, Embedder embedder, RagConfig config, Executor pathExecutor) {
        this.store = new IndexStore(userDirectory, workspaceDirectory);
        this.reranker = reranker;
        this.embedder = embedder;
        this.config = config;
        this.fanout = new PathFanout(pathExecutor);
    }

    public CompletionStage<Integer> index(RagScope scope) {
        return VirtualIo.supply(() -> store.rebuild(scope));
    }

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
