package dev.jasagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPipelineTest {
    @TempDir
    Path temp;

    @Test
    void chunksByHeadingThenWindow() {
        String text = "# Head\n\n" + "a".repeat(900);
        List<TextChunk> chunks = Chunker.chunk("guide.md", text);
        assertEquals(2, chunks.size());
        assertEquals("Head", chunks.get(0).title());
        assertEquals("guide.md#0", chunks.get(0).chunkId());
        assertEquals(0, chunks.get(0).start());
        assertEquals(800, chunks.get(0).end());
        assertEquals(text.substring(0, 800), chunks.get(0).content());
        assertEquals(700, chunks.get(1).start());
        assertEquals(text.length(), chunks.get(1).end());
        assertEquals(text.substring(700), chunks.get(1).content());
        assertEquals(text.substring(700, 800), chunks.get(0).content().substring(700));

        String two = "# Alpha\n\none\n\n## Beta\n\ntwo\n";
        List<TextChunk> parts = Chunker.chunk("guide.md", two);
        assertEquals(2, parts.size());
        assertEquals("Alpha", parts.get(0).title());
        assertEquals("Beta", parts.get(1).title());
        assertEquals(two.substring(parts.get(0).start(), parts.get(0).end()), parts.get(0).content());
        assertEquals(two.substring(parts.get(1).start(), parts.get(1).end()), parts.get(1).content());
        assertTrue(parts.get(0).content().contains("one"));
        assertFalse(parts.get(0).content().contains("Beta"));
        assertTrue(parts.get(1).content().startsWith("## Beta"));

        List<TextChunk> plain = Chunker.chunk("plain.txt", "hello");
        assertEquals(1, plain.size());
        assertEquals("plain", plain.get(0).title());
        assertEquals("hello", plain.get(0).content());
        assertEquals(0, plain.get(0).start());
        assertEquals(5, plain.get(0).end());
    }

    @Test
    void indexStoresMdAndTxtPerScope() throws Exception {
        Path user = temp.resolve("user");
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(user.resolve("rag").resolve("docs"));
        Files.writeString(user.resolve("rag").resolve("docs").resolve("keep.md"), "mdtoken appears");
        Files.writeString(user.resolve("rag").resolve("docs").resolve("note.txt"), "txttoken appears");
        Files.writeString(user.resolve("rag").resolve("docs").resolve("skip.pdf"), "pdftoken appears");
        RagPipeline pipeline = pipeline(user, workspace, constant(0.9));
        join(pipeline.index(RagScope.USER));
        assertTrue(Files.isRegularFile(user.resolve("rag").resolve("index").resolve("chunks.v1")));
        assertFalse(Files.exists(workspace.resolve("rag").resolve("index").resolve("chunks.v1")));
        RagResult markdown = join(pipeline.search(query("mdtoken", Set.of(RagScope.USER), null, false)));
        RagResult text = join(pipeline.search(query("txttoken", Set.of(RagScope.USER), null, false)));
        RagResult pdf = join(pipeline.search(query("pdftoken", Set.of(RagScope.USER), null, false)));
        assertTrue(markdown.hits().stream().anyMatch(hit -> hit.content().contains("mdtoken")));
        assertTrue(text.hits().stream().anyMatch(hit -> hit.content().contains("txttoken")));
        assertTrue(pdf.hits().isEmpty());
        assertEquals("quality-empty", pdf.reason());
    }

    @Test
    void threePathsRankDifferently() throws Exception {
        RagResult result = searchRankCorpus(constant(0.8), null);
        List<String> lexical = pathOrder(result.hits(), RagHit::lexical);
        List<String> vector = pathOrder(result.hits(), RagHit::vector);
        List<String> title = pathOrder(result.hits(), RagHit::title);
        assertNotEquals(lexical, vector);
        assertNotEquals(lexical, title);
        assertNotEquals(vector, title);
    }

    @Test
    void fusionUsesRrfK60AndChangesRanks() throws Exception {
        RagResult result = searchRankCorpus(constant(0.5), null);
        for (RagHit hit : result.hits()) {
            assertEquals(expectedFusion(hit), hit.fusionScore(), 1e-12);
        }
        List<String> fused = ids(result);
        assertNotEquals(fused, pathOrder(result.hits(), RagHit::lexical));
        assertNotEquals(fused, pathOrder(result.hits(), RagHit::vector));
        assertNotEquals(fused, pathOrder(result.hits(), RagHit::title));
    }

    @Test
    void rerankReceivesTopFusion20() throws Exception {
        Path user = temp.resolve("user");
        Path docs = user.resolve("rag").resolve("docs");
        Files.createDirectories(docs);
        for (int i = 0; i < 25; i++) {
            Files.writeString(docs.resolve("d%02d.md".formatted(i)), "zephyrtoken");
        }
        List<RerankCandidate> seen = new ArrayList<>();
        RagPipeline pipeline = pipeline(user, temp.resolve("workspace"), (query, candidates) -> {
            seen.addAll(candidates);
            return completed(constantScores(candidates, 0.9));
        });
        join(pipeline.index(RagScope.USER));
        join(pipeline.search(query("zephyrtoken", Set.of(RagScope.USER), null, false)));
        assertEquals(20, seen.size());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expected.add("user:d%02d.md#0".formatted(i));
        }
        assertEquals(expected, seen.stream().map(RerankCandidate::chunkId).toList());
    }

    @Test
    void rerankRewritesOrder() throws Exception {
        RagResult fused = searchRankCorpus(constant(0.5), null);
        RagResult rewritten = searchRankCorpus((query, candidates) -> {
            List<Double> scores = new ArrayList<>();
            for (RerankCandidate candidate : candidates) {
                scores.add(switch (candidate.chunkId()) {
                    case "user:alpha-beta.md#0" -> 0.99;
                    case "user:wide.md#0" -> 0.90;
                    case "user:notes.md#0" -> 0.80;
                    default -> 0.40;
                });
            }
            return completed(scores);
        }, null);
        assertEquals("user:alpha-beta.md#0", rewritten.hits().get(0).chunkId());
        assertNotEquals(ids(fused), ids(rewritten));
    }

    @Test
    void missingProvenanceDropped() {
        RagHit broken = sample("broken", "kept-body", 0.95, "");
        RagHit kept = sample("kept", "other-body", 0.60, "kept.md");
        RagResult result = QualityGate.apply(List.of(broken, kept), 0.35, null, false);
        assertEquals(List.of("kept"), ids(result));
    }

    @Test
    void incompleteHitDoesNotEraseSameHash() {
        RagHit broken = sample("broken", "same-body", 0.99, "");
        RagHit kept = sample("kept", "same-body", 0.50, "kept.md");
        RagResult result = QualityGate.apply(List.of(broken, kept), 0.35, null, false);
        assertEquals(List.of("kept"), ids(result));
    }

    @Test
    void sameContentHashKeepsHigherRerank() {
        RagHit low = sample("low", "same-body", 0.40, "low.md");
        RagHit high = sample("high", "same-body", 0.80, "high.md");
        RagResult result = QualityGate.apply(List.of(low, high), 0.35, null, false);
        assertEquals(List.of("high"), ids(result));
        assertEquals(0.80, result.hits().get(0).rerankScore());
    }

    @Test
    void defaultMinRerankKeepsThreshold() {
        assertEquals(0.35, RagConfig.defaults().minRerank());
        RagHit kept = sample("kept", "kept-body", 0.35, "kept.md");
        RagHit dropped = sample("dropped", "dropped-body", 0.349, "dropped.md");
        RagResult result = QualityGate.apply(List.of(dropped, kept), RagConfig.defaults().minRerank(), null, false);
        assertEquals(List.of("kept"), ids(result));
    }

    @Test
    void configuredMinRerankApplies() {
        String previous = System.getProperty("jasagent.rag.min-rerank");
        System.setProperty("jasagent.rag.min-rerank", "0.8");
        try {
            assertEquals(0.8, RagConfig.defaults().minRerank());
            RagHit kept = sample("kept", "kept-body", 0.8, "kept.md");
            RagHit dropped = sample("dropped", "dropped-body", 0.79, "dropped.md");
            RagResult result = QualityGate.apply(List.of(dropped, kept), RagConfig.defaults().minRerank(), null, false);
            assertEquals(List.of("kept"), ids(result));
        } finally {
            if (previous == null) {
                System.clearProperty("jasagent.rag.min-rerank");
            } else {
                System.setProperty("jasagent.rag.min-rerank", previous);
            }
        }
    }

    @Test
    void allDroppedReturnsQualityEmpty() {
        RagHit low = sample("low", "low-body", 0.1, "low.md");
        RagHit broken = sample("broken", "broken-body", 0.99, "");
        RagResult result = QualityGate.apply(List.of(low, broken), 0.35, null, false);
        assertTrue(result.hits().isEmpty());
        assertEquals("quality-empty", result.reason());
    }

    @Test
    void unspecifiedScopeSearchesBoth() throws Exception {
        RagPipeline pipeline = scopedCorpus();
        RagResult result = join(pipeline.search(query("sharedtoken", Set.of(), null, false)));
        assertEquals(Set.of("user", "workspace"), result.hits().stream().map(RagHit::sourceScope).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void userScopeSkipsWorkspace() throws Exception {
        RagPipeline pipeline = scopedCorpus();
        RagResult result = join(pipeline.search(query("sharedtoken", Set.of(RagScope.USER), null, false)));
        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "user".equals(hit.sourceScope())));
        assertTrue(result.hits().stream().noneMatch(hit -> hit.content().contains("wsonlytoken")));
    }

    @Test
    void workspaceScopeSkipsUser() throws Exception {
        RagPipeline pipeline = scopedCorpus();
        RagResult result = join(pipeline.search(query("sharedtoken", Set.of(RagScope.WORKSPACE), null, false)));
        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "workspace".equals(hit.sourceScope())));
        assertTrue(result.hits().stream().noneMatch(hit -> hit.content().contains("useronlytoken")));
    }

    @Test
    void hitCarriesProvenance() throws Exception {
        Path user = temp.resolve("user");
        Files.createDirectories(user.resolve("rag").resolve("docs"));
        String body = "provenance token lives here";
        Files.writeString(user.resolve("rag").resolve("docs").resolve("note.md"), body);
        RagPipeline pipeline = pipeline(user, temp.resolve("workspace"), constant(0.77));
        join(pipeline.index(RagScope.USER));
        RagResult result = join(pipeline.search(query("provenance", Set.of(RagScope.USER), null, false)));
        assertEquals(1, result.hits().size());
        RagHit hit = result.hits().get(0);
        assertEquals("user", hit.sourceScope());
        assertEquals("note.md", hit.uri());
        assertEquals("note.md", hit.docId());
        assertEquals("user:note.md#0", hit.chunkId());
        assertTrue(hit.start() >= 0);
        assertTrue(hit.end() > hit.start());
        assertEquals(body.substring(hit.start(), hit.end()), hit.content());
        assertEquals(Hashes.sha256(hit.content()), hit.contentHash());
        assertTrue(hit.lexical().rank() > 0);
        assertTrue(Double.isFinite(hit.lexical().score()));
        assertTrue(hit.vector().rank() > 0);
        assertTrue(Double.isFinite(hit.vector().score()));
        assertTrue(hit.title().rank() >= 0);
        assertTrue(Double.isFinite(hit.title().score()));
        assertTrue(Double.isFinite(hit.fusionScore()));
        assertEquals(0.77, hit.rerankScore());
    }

    @Test
    void requiredFalseDoesNotFail() {
        RagPipeline pipeline = pipeline(temp.resolve("user"), temp.resolve("workspace"), constant(0.9));
        RagResult result = join(pipeline.search(query("missing", Set.of(), null, false)));
        assertFalse(result.failed());
        assertEquals("quality-empty", result.reason());
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void requiredTrueMarksFailedWithoutText() {
        RagPipeline pipeline = pipeline(temp.resolve("user"), temp.resolve("workspace"), constant(0.9));
        RagResult result = join(pipeline.search(query("missing", Set.of(), null, true)));
        assertTrue(result.failed());
        assertEquals("quality-empty", result.reason());
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void threePathsOverlapInTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Executor executor = command -> {
            calls.incrementAndGet();
            command.run();
        };
        Path user = temp.resolve("user");
        Files.createDirectories(user.resolve("rag").resolve("docs"));
        Files.writeString(user.resolve("rag").resolve("docs").resolve("one.md"), "overlap token");
        RagPipeline pipeline = new RagPipeline(user, temp.resolve("workspace"), constant(0.9), new HashingEmbedder(), RagConfig.defaults(), executor);
        join(pipeline.index(RagScope.USER));
        join(pipeline.search(query("overlap", Set.of(RagScope.USER), null, false)));
        assertEquals(3, calls.get());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            PathFanout fanout = new PathFanout(pool);
            AtomicInteger current = new AtomicInteger();
            AtomicInteger max = new AtomicInteger();
            List<Callable<String>> tasks = new ArrayList<>();
            for (String name : List.of("lexical", "vector", "title")) {
                tasks.add(() -> {
                    int now = current.incrementAndGet();
                    max.accumulateAndGet(now, Math::max);
                    Thread.sleep(Duration.ofMillis(200));
                    current.decrementAndGet();
                    return name;
                });
            }
            long started = System.nanoTime();
            List<String> names = join(fanout.all(tasks));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(List.of("lexical", "vector", "title"), names);
            assertEquals(3, max.get());
            assertTrue(elapsed < 500, "elapsed=" + elapsed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void searchDoesNotSelectNetworkProxy() throws Exception {
        Path user = temp.resolve("user");
        Files.createDirectories(user.resolve("rag").resolve("docs"));
        Files.writeString(user.resolve("rag").resolve("docs").resolve("local.md"), "offline token");
        AtomicInteger rerankCalls = new AtomicInteger();
        RagPipeline pipeline = pipeline(user, temp.resolve("workspace"), (query, candidates) -> {
            rerankCalls.incrementAndGet();
            return completed(constantScores(candidates, 0.88));
        });
        join(pipeline.index(RagScope.USER));
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                throw new AssertionError("network " + uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        try {
            RagResult result = join(pipeline.search(query("offline", Set.of(RagScope.USER), null, false)));
            assertFalse(result.hits().isEmpty());
            assertEquals(1, rerankCalls.get());
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    @Test
    void topKKeepsHighestRerank() throws Exception {
        RagResult result = searchRankCorpus((query, candidates) -> {
            List<Double> scores = new ArrayList<>();
            for (RerankCandidate candidate : candidates) {
                scores.add("user:alpha-beta.md#0".equals(candidate.chunkId()) ? 0.99 : 0.40);
            }
            return completed(scores);
        }, 1);
        assertEquals(1, result.hits().size());
        assertEquals("user:alpha-beta.md#0", result.hits().get(0).chunkId());
    }

    @Test
    void ragSearchToolSharesPipeline() throws Exception {
        Path user = temp.resolve("user");
        Files.createDirectories(user.resolve("rag").resolve("docs"));
        Files.writeString(user.resolve("rag").resolve("docs").resolve("tool.md"), "tooltoken inside");
        RagPipeline pipeline = pipeline(user, temp.resolve("workspace"), constant(0.66));
        join(pipeline.index(RagScope.USER));
        RagQuery query = query("tooltoken", Set.of(RagScope.USER), null, false);
        RagSearchTool tool = new RagSearchTool(pipeline);
        assertEquals("rag.search", RagSearchTool.NAME);
        assertEquals(ids(join(pipeline.search(query))), ids(join(tool.call(query))));
    }

    private RagResult searchRankCorpus(Reranker reranker, Integer topK) throws Exception {
        Path user = writeRankCorpus(temp.resolve("rank-user"));
        RagPipeline pipeline = pipeline(user, temp.resolve("rank-workspace"), reranker);
        join(pipeline.index(RagScope.USER));
        return join(pipeline.search(query("alpha beta", Set.of(RagScope.USER), topK, false)));
    }

    private static Path writeRankCorpus(Path user) throws Exception {
        Path docs = user.resolve("rag").resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("notes.md"), "alpha alpha alpha alpha beta");
        Files.writeString(docs.resolve("misc.md"), "alpha beta");
        Files.writeString(docs.resolve("wide.md"), "alpha beta " + String.join(" ", fillers(30)));
        Files.writeString(docs.resolve("alpha-beta.md"), "qqqq rrrr ssss tttt uuuu");
        return user;
    }

    private RagPipeline scopedCorpus() throws Exception {
        Path user = temp.resolve("scope-user");
        Path workspace = temp.resolve("scope-workspace");
        Files.createDirectories(user.resolve("rag").resolve("docs"));
        Files.createDirectories(workspace.resolve("rag").resolve("docs"));
        Files.writeString(user.resolve("rag").resolve("docs").resolve("only-user.md"), "useronlytoken sharedtoken");
        Files.writeString(workspace.resolve("rag").resolve("docs").resolve("only-ws.md"), "wsonlytoken sharedtoken");
        RagPipeline pipeline = pipeline(user, workspace, constant(0.7));
        join(pipeline.index(RagScope.USER));
        join(pipeline.index(RagScope.WORKSPACE));
        return pipeline;
    }

    private static RagPipeline pipeline(Path user, Path workspace, Reranker reranker) {
        return new RagPipeline(user, workspace, reranker);
    }

    private static RagQuery query(String text, Set<RagScope> scopes, Integer topK, boolean required) {
        return new RagQuery(text, scopes, topK, required);
    }

    private static Reranker constant(double score) {
        return (query, candidates) -> completed(constantScores(candidates, score));
    }

    private static List<Double> constantScores(List<RerankCandidate> candidates, double score) {
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            scores.add(score);
        }
        return scores;
    }

    private static java.util.concurrent.CompletableFuture<List<Double>> completed(List<Double> scores) {
        return java.util.concurrent.CompletableFuture.completedFuture(scores);
    }

    private static RagHit sample(String chunkId, String content, double rerank, String uri) {
        return new RagHit("user", uri, "doc", chunkId, 0, content.length(), Hashes.sha256(content), content,
                new PathScore(1, 1.0), new PathScore(1, 1.0), new PathScore(0, 0.0), 0.03, rerank);
    }

    private static double expectedFusion(RagHit hit) {
        double sum = 0;
        for (PathScore score : List.of(hit.lexical(), hit.vector(), hit.title())) {
            if (score.rank() > 0) {
                sum += 1.0 / (60 + score.rank());
            }
        }
        return sum;
    }

    private static List<String> pathOrder(List<RagHit> hits, Function<RagHit, PathScore> path) {
        return hits.stream()
                .filter(hit -> path.apply(hit).rank() > 0)
                .sorted(Comparator.comparingInt(hit -> path.apply(hit).rank()))
                .map(RagHit::chunkId)
                .toList();
    }

    private static List<String> ids(RagResult result) {
        return result.hits().stream().map(RagHit::chunkId).toList();
    }

    private static List<String> fillers(int count) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            words.add("w" + i);
        }
        return words;
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
