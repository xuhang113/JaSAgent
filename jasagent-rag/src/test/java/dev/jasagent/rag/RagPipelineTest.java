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

/**
 * 覆盖切段、建索引、三路召回、融合、重排、质量门和检索入口。
 * 语料都写在临时目录，用来确认本地索引不会去访问网络。
 */
class RagPipelineTest {
    @TempDir
    Path temp;

    /**
     * 确认先按标题分节，超长节再按 800 字窗口、100 字重叠滑动。
     * 两级标题各自成段，纯文本没有标题时用去掉扩展名的文件名。
     * 副作用：不写文件，只在内存里切三段样例。
     */
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

    /**
     * 确认建索引只收当前范围里的 Markdown 和纯文本，pdf 不进索引，另一个范围也不会生成索引文件。
     * 副作用：在临时目录写入 md、txt 和 pdf，并生成用户范围索引。
     */
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

    /**
     * 确认同一批文档上，词法、向量、标题三路给出的名次顺序互不相同。
     * 副作用：写入四篇排序用语料并生成用户索引。
     */
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

    /**
     * 确认融合分等于各路 1/(60+名次) 之和，而且融合后的顺序和任何单路都不同。
     * 副作用：写入四篇排序用语料并生成用户索引。
     */
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

    /**
     * 确认重排器只收到融合分最高的 20 段，并且顺序就是融合名次。
     * 副作用：写入 25 篇含同一个词的文档并生成用户索引。
     */
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

    /**
     * 确认重排分会改写最终顺序，不再沿用融合顺序。
     * 副作用：写入四篇排序用语料，并按段标识指定的重排分再查一次。
     */
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

    /**
     * 确认缺文档路径的命中被丢掉，同批里出处完整的另一条仍保留。
     * 副作用：不写文件，只把构造的命中送进质量门。
     */
    @Test
    void missingProvenanceDropped() {
        RagHit broken = sample("broken", "kept-body", 0.95, "");
        RagHit kept = sample("kept", "other-body", 0.60, "kept.md");
        RagResult result = QualityGate.apply(List.of(broken, kept), 0.35, null, false);
        assertEquals(List.of("kept"), ids(result));
    }

    /**
     * 确认同一正文哈希下，出处残缺的高分命中不能挤掉出处完整的低分命中。
     * 副作用：不写文件。
     */
    @Test
    void incompleteHitDoesNotEraseSameHash() {
        RagHit broken = sample("broken", "same-body", 0.99, "");
        RagHit kept = sample("kept", "same-body", 0.50, "kept.md");
        RagResult result = QualityGate.apply(List.of(broken, kept), 0.35, null, false);
        assertEquals(List.of("kept"), ids(result));
    }

    /**
     * 确认正文哈希相同的多条完整命中只留下重排分更高的那条。
     * 副作用：不写文件。
     */
    @Test
    void sameContentHashKeepsHigherRerank() {
        RagHit low = sample("low", "same-body", 0.40, "low.md");
        RagHit high = sample("high", "same-body", 0.80, "high.md");
        RagResult result = QualityGate.apply(List.of(low, high), 0.35, null, false);
        assertEquals(List.of("high"), ids(result));
        assertEquals(0.80, result.hits().get(0).rerankScore());
    }

    /**
     * 确认默认重排下限是 0.35，等于下限的留下，低于下限的丢掉。
     * 副作用：只读取默认配置，不修改系统属性。
     */
    @Test
    void defaultMinRerankKeepsThreshold() {
        assertEquals(0.35, RagConfig.defaults().minRerank());
        RagHit kept = sample("kept", "kept-body", 0.35, "kept.md");
        RagHit dropped = sample("dropped", "dropped-body", 0.349, "dropped.md");
        RagResult result = QualityGate.apply(List.of(dropped, kept), RagConfig.defaults().minRerank(), null, false);
        assertEquals(List.of("kept"), ids(result));
    }

    /**
     * 确认系统属性 jasagent.rag.min-rerank 会抬高重排下限。
     * 副作用：临时把该属性设为 0.8，结束时恢复进入测试前的值。
     */
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

    /**
     * 确认低分和残缺命中都被丢掉后，结果为空且原因是 quality-empty。
     * 副作用：不写文件。
     */
    @Test
    void allDroppedReturnsQualityEmpty() {
        RagHit low = sample("low", "low-body", 0.1, "low.md");
        RagHit broken = sample("broken", "broken-body", 0.99, "");
        RagResult result = QualityGate.apply(List.of(low, broken), 0.35, null, false);
        assertTrue(result.hits().isEmpty());
        assertEquals("quality-empty", result.reason());
    }

    /**
     * 确认范围为空集合时，用户目录和工作区的命中都会返回。
     * 副作用：在两个临时范围各写一篇文档并分别建索引。
     */
    @Test
    void unspecifiedScopeSearchesBoth() throws Exception {
        RagPipeline pipeline = scopedCorpus();
        RagResult result = join(pipeline.search(query("sharedtoken", Set.of(), null, false)));
        assertEquals(Set.of("user", "workspace"), result.hits().stream().map(RagHit::sourceScope).collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * 确认只查 user 时不返回工作区正文。
     * 副作用：在两个临时范围各写一篇文档并分别建索引。
     */
    @Test
    void userScopeSkipsWorkspace() throws Exception {
        RagPipeline pipeline = scopedCorpus();
        RagResult result = join(pipeline.search(query("sharedtoken", Set.of(RagScope.USER), null, false)));
        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "user".equals(hit.sourceScope())));
        assertTrue(result.hits().stream().noneMatch(hit -> hit.content().contains("wsonlytoken")));
    }

    /**
     * 确认只查 workspace 时不返回用户目录正文。
     * 副作用：在两个临时范围各写一篇文档并分别建索引。
     */
    @Test
    void workspaceScopeSkipsUser() throws Exception {
        RagPipeline pipeline = scopedCorpus();
        RagResult result = join(pipeline.search(query("sharedtoken", Set.of(RagScope.WORKSPACE), null, false)));
        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "workspace".equals(hit.sourceScope())));
        assertTrue(result.hits().stream().noneMatch(hit -> hit.content().contains("useronlytoken")));
    }

    /**
     * 确认命中带回范围、路径、段界、正文、内容哈希、三路分数、融合分和重排分。
     * 副作用：写入一篇用户文档并生成索引。
     */
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

    /**
     * 确认不要求必须命中时，零结果只记 quality-empty，不算失败。
     * 副作用：对空目录检索，不创建索引文件。
     */
    @Test
    void requiredFalseDoesNotFail() {
        RagPipeline pipeline = pipeline(temp.resolve("user"), temp.resolve("workspace"), constant(0.9));
        RagResult result = join(pipeline.search(query("missing", Set.of(), null, false)));
        assertFalse(result.failed());
        assertEquals("quality-empty", result.reason());
        assertTrue(result.hits().isEmpty());
    }

    /**
     * 确认要求必须命中但没有任何段时，结果标记为失败，并且不编造正文。
     * 副作用：对空目录检索，不创建索引文件。
     */
    @Test
    void requiredTrueMarksFailedWithoutText() {
        RagPipeline pipeline = pipeline(temp.resolve("user"), temp.resolve("workspace"), constant(0.9));
        RagResult result = join(pipeline.search(query("missing", Set.of(), null, true)));
        assertTrue(result.failed());
        assertEquals("quality-empty", result.reason());
        assertTrue(result.hits().isEmpty());
    }

    /**
     * 确认一次检索会把词法、向量、标题三路都提交出去，而且扇出能重叠执行。
     * 副作用：写入一篇用户文档；测试末尾关掉自己创建的三线程池。
     */
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

    /**
     * 确认建索引和检索期间不会向默认代理选择器要连接。
     * 副作用：暂时换上一旦被调用就失败的代理选择器，结束后恢复原来的选择器。
     */
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
        /**
         * 代理选择器一旦被检索代码调用，就把测试打失败。
         */
        ProxySelector.setDefault(new ProxySelector() {
            /**
             * 拒绝为任何地址挑选代理。
             *
             * @param uri 正要连接的地址
             * @return 不返回代理列表；一被调用就抛出断言错误
             */
            @Override
            public List<Proxy> select(URI uri) {
                throw new AssertionError("network " + uri);
            }

            /**
             * 连接失败时不做补救，因为这条测试不发起连接。
             * 副作用：不记录地址，也不更换代理。
             *
             * @param uri 连接失败的地址
             * @param sa 失败的套接字地址
             * @param ioe 连接时的输入输出异常
             */
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

    /**
     * 确认条数上限为 1 时只留下重排分最高的那一段。
     * 副作用：写入四篇排序用语料并生成用户索引。
     */
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

    /**
     * 确认工具名是 rag.search，并且工具调用和直接检索同一条管道得到相同段序。
     * 副作用：写入一篇用户文档并生成索引。
     */
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

    /**
     * 用固定的四篇排序语料建好用户索引，再按给定重排器和条数上限检索 alpha beta。
     *
     * @param reranker 这次检索使用的重排器
     * @param topK 质量门之后的条数上限，空引用表示不截断
     * @return 这次检索的结果
     */
    private RagResult searchRankCorpus(Reranker reranker, Integer topK) throws Exception {
        Path user = writeRankCorpus(temp.resolve("rank-user"));
        RagPipeline pipeline = pipeline(user, temp.resolve("rank-workspace"), reranker);
        join(pipeline.index(RagScope.USER));
        return join(pipeline.search(query("alpha beta", Set.of(RagScope.USER), topK, false)));
    }

    /**
     * 在用户 docs 下写出词频、标题和长度都不同的四篇文档，供三路排序对照。
     * 副作用：创建 docs 目录并覆盖这四篇文件。
     *
     * @param user 用户范围根目录
     * @return 传入的同一个根目录
     */
    private static Path writeRankCorpus(Path user) throws Exception {
        Path docs = user.resolve("rag").resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("notes.md"), "alpha alpha alpha alpha beta");
        Files.writeString(docs.resolve("misc.md"), "alpha beta");
        Files.writeString(docs.resolve("wide.md"), "alpha beta " + String.join(" ", fillers(30)));
        Files.writeString(docs.resolve("alpha-beta.md"), "qqqq rrrr ssss tttt uuuu");
        return user;
    }

    /**
     * 在用户和工作区各放一篇含 sharedtoken 的文档，并两边都建好索引。
     * 副作用：写入 only-user.md 和 only-ws.md 以及两份索引。
     *
     * @return 已经建完两个范围索引的管道
     */
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

    /**
     * 用默认嵌入和默认配置包一层管道，只替换重排器。
     *
     * @param user 用户范围根目录
     * @param workspace 工作区范围根目录
     * @param reranker 测试注入的重排器
     * @return 绑在这两个目录上的管道
     */
    private static RagPipeline pipeline(Path user, Path workspace, Reranker reranker) {
        return new RagPipeline(user, workspace, reranker);
    }

    /**
     * 组装一条检索请求。
     *
     * @param text 查询原文
     * @param scopes 要查的范围，空集合表示两边都查
     * @param topK 条数上限，空引用表示不截断
     * @param required 零命中是否算失败
     * @return 对应的查询对象
     */
    private static RagQuery query(String text, Set<RagScope> scopes, Integer topK, boolean required) {
        return new RagQuery(text, scopes, topK, required);
    }

    /**
     * 做一个对每个候选都给同一分数的重排器。
     *
     * @param score 每段候选都使用的重排分
     * @return 立即完成的重排器
     */
    private static Reranker constant(double score) {
        return (query, candidates) -> completed(constantScores(candidates, score));
    }

    /**
     * 按候选个数生成同样的分数，长度和候选列表一致。
     *
     * @param candidates 重排器收到的候选，只使用它的个数
     * @param score 填进每个位置的分数
     * @return 与候选等长的分数列表
     */
    private static List<Double> constantScores(List<RerankCandidate> candidates, double score) {
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            scores.add(score);
        }
        return scores;
    }

    /**
     * 把分数包成已经完成的异步结果，避免重排器再开任务。
     *
     * @param scores 要原样交回的分数
     * @return 已完成的分数阶段
     */
    private static java.util.concurrent.CompletableFuture<List<Double>> completed(List<Double> scores) {
        return java.util.concurrent.CompletableFuture.completedFuture(scores);
    }

    /**
     * 造一条用户范围的命中，正文哈希按正文现算，词法和向量路都有名次。
     *
     * @param chunkId 段标识
     * @param content 段正文，同时决定结束下标和哈希
     * @param rerank 重排分
     * @param uri 文档路径，空串用来模拟缺出处
     * @return 字段按上述规则填好的命中
     */
    private static RagHit sample(String chunkId, String content, double rerank, String uri) {
        return new RagHit("user", uri, "doc", chunkId, 0, content.length(), Hashes.sha256(content), content,
                new PathScore(1, 1.0), new PathScore(1, 1.0), new PathScore(0, 0.0), 0.03, rerank);
    }

    /**
     * 按名次大于 0 的三路计算平滑常数为 60 的倒数排名融合分。
     *
     * @param hit 带三路名次的命中
     * @return 名次大于 0 的各路 1/(60+名次) 之和
     */
    private static double expectedFusion(RagHit hit) {
        double sum = 0;
        for (PathScore score : List.of(hit.lexical(), hit.vector(), hit.title())) {
            if (score.rank() > 0) {
                sum += 1.0 / (60 + score.rank());
            }
        }
        return sum;
    }

    /**
     * 取出某一路名次大于 0 的段，按名次从先到后排段标识。
     *
     * @param hits 已经融合过的命中
     * @param path 选择词法、向量或标题路分数的函数
     * @return 该路的段标识，名次小的在前
     */
    private static List<String> pathOrder(List<RagHit> hits, Function<RagHit, PathScore> path) {
        return hits.stream()
                .filter(hit -> path.apply(hit).rank() > 0)
                .sorted(Comparator.comparingInt(hit -> path.apply(hit).rank()))
                .map(RagHit::chunkId)
                .toList();
    }

    /**
     * 按结果里的现有顺序抽出段标识。
     *
     * @param result 检索或质量门的结果
     * @return 命中的段标识，顺序与结果一致
     */
    private static List<String> ids(RagResult result) {
        return result.hits().stream().map(RagHit::chunkId).toList();
    }

    /**
     * 生成互不相同的填充词，用来把一篇文档拉长。
     *
     * @param count 要生成的词数
     * @return w0、w1 这种形式的词
     */
    private static List<String> fillers(int count) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            words.add("w" + i);
        }
        return words;
    }

    /**
     * 在 10 秒内取回异步结果，并把包装异常还原成原来的运行时异常。
     * 副作用：等待被中断时重新置上当前线程的中断标记。
     *
     * @param <T> 阶段的结果类型
     * @param stage 要等待的异步阶段
     * @return 阶段的正常结果
     */
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
