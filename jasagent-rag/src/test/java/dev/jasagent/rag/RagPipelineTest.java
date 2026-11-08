package dev.jasagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPipelineTest {
    @TempDir
    Path temp;

    @Test
    void fusionChangesLexicalOrder() {
        List<Hit> fused = ReciprocalRankFusion.fuse(Map.of(
                "lexical", List.of("A", "B", "C"),
                "vector", List.of("C", "B", "A"),
                "title", List.of("C", "A", "B")));
        assertEquals("A", List.of("A", "B", "C").get(0));
        assertEquals("C", fused.get(0).chunkId());
    }

    @Test
    void rrfScoreUsesK60() {
        List<Hit> fused = ReciprocalRankFusion.fuse(Map.of(
                "lexical", List.of("A", "B", "C"),
                "vector", List.of("C", "B", "A"),
                "title", List.of("C", "A", "B")));
        Hit a = fused.stream().filter(hit -> hit.chunkId().equals("A")).findFirst().orElseThrow();
        assertEquals(1.0 / 61 + 1.0 / 63 + 1.0 / 62, a.fusionScore(), 0.0000001);
    }

    @Test
    void onlyTopTwentyGoToRerank() {
        List<Hit> hits = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            hits.add(hit("c" + index, "hash-" + index, "uri-" + index, 1.0 - index / 100.0, 0.9));
        }
        List<Integer> sizes = new ArrayList<>();
        RagPipeline.rerankThenFilter(hits, (query, received) -> {
            sizes.add(received.size());
            return received.stream().map(item -> new ScoredHit(item.chunkId(), 0.9)).toList();
        }, 0.35);
        assertEquals(20, sizes.get(0));
    }

    @Test
    void rerankRewritesOrder() {
        List<Hit> hits = List.of(hit("first", "h1", "u1", 0.9, 0.2), hit("second", "h2", "u2", 0.5, 0.2));
        RagResult result = RagPipeline.rerankThenFilter(hits, (query, received) -> List.of(
                new ScoredHit("first", 0.4),
                new ScoredHit("second", 0.95)), 0.35);
        assertEquals("second", result.hits().get(0).chunkId());
    }

    @Test
    void missingProvenanceDropped() {
        Hit broken = new Hit("user", "", "doc", "missing", 0, 1, "hash", "text", 0.5, 0.9);
        RagResult result = QualityFilter.apply(List.of(broken, hit("kept", "hash-kept", "uri", 0.4, 0.8)), 0.35);
        assertTrue(result.hits().stream().noneMatch(hit -> hit.chunkId().equals("missing")));
        assertEquals("kept", result.hits().get(0).chunkId());
    }

    @Test
    void lowRerankDropped() {
        RagResult result = QualityFilter.apply(List.of(hit("low", "hash-low", "uri", 0.8, 0.1), hit("high", "hash-high", "uri", 0.2, 0.9)), 0.35);
        assertTrue(result.hits().stream().noneMatch(hit -> hit.chunkId().equals("low")));
    }

    @Test
    void duplicateHashCollapsed() {
        RagResult result = QualityFilter.apply(List.of(
                hit("a", "same-hash", "uri-a", 0.2, 0.4),
                hit("b", "same-hash", "uri-b", 0.9, 0.8)), 0.35);
        assertEquals(1, result.hits().stream().filter(hit -> "same-hash".equals(hit.contentHash())).count());
    }

    @Test
    void emptyWhenAllDropped() {
        RagResult result = QualityFilter.apply(List.of(hit("low", "hash", "uri", 0.9, 0.1)), 0.35);
        assertTrue(result.hits().isEmpty());
        assertEquals("quality-empty", result.reason());
    }

    @Test
    void userScopeSkipsWorkspaceDoc() throws Exception {
        Corpus corpus = corpus();
        RagResult result = new ScopedSearch(corpus).search("alpha-marker", List.of("user"));
        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "user".equals(hit.sourceScope())));
        assertFalse(result.hits().stream().anyMatch(hit -> hit.text().contains("beta-marker")));
    }

    @Test
    void workspaceScopeSkipsUserDoc() throws Exception {
        Corpus corpus = corpus();
        RagResult result = new ScopedSearch(corpus).search("beta-marker", List.of("workspace"));
        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "workspace".equals(hit.sourceScope())));
        assertFalse(result.hits().stream().anyMatch(hit -> hit.text().contains("alpha-marker")));
    }

    @Test
    void chunkByHeadingThenWindow() {
        String longBody = "甲".repeat(900);
        List<Chunk> chunks = Chunker.split("# 一\n" + longBody + "\n# 二\n短\n");
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("一")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("二")));
        List<Chunk> windows = chunks.stream().filter(chunk -> chunk.text().contains("甲")).toList();
        assertTrue(windows.size() >= 2);
        assertTrue(windows.stream().allMatch(chunk -> chunk.text().length() <= 800));
        String overlap = windows.get(0).text().substring(windows.get(0).text().length() - 100);
        assertTrue(windows.get(1).text().startsWith(overlap));
    }

    @Test
    void markdownAndTextAreIndexed() throws Exception {
        Path docs = temp.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("one.md"), "markdown-marker\n");
        Files.writeString(docs.resolve("two.txt"), "plaintext-marker\n");
        Corpus corpus = Corpus.index(docs, "user");
        RagResult result = new ScopedSearch(corpus).search("marker", List.of("user"));
        String joined = result.hits().stream().map(Hit::text).reduce("", String::concat);
        assertTrue(joined.contains("markdown-marker"));
        assertTrue(joined.contains("plaintext-marker"));
    }

    private Corpus corpus() throws Exception {
        Path user = temp.resolve("user");
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(user);
        Files.createDirectories(workspace);
        Files.writeString(user.resolve("a.md"), "alpha-marker lives in the user directory\n");
        Files.writeString(workspace.resolve("b.md"), "beta-marker lives in the workspace directory\n");
        return Corpus.index(user, "user").plus(Corpus.index(workspace, "workspace"));
    }

    private static Hit hit(String id, String hash, String uri, double fusion, double rerank) {
        return new Hit("user", uri, "doc", id, 0, 4, hash, "text", fusion, rerank);
    }
}
