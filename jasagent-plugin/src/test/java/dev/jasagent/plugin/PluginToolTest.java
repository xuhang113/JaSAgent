package dev.jasagent.plugin;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.plugin.AgentPlugin;
import dev.jasagent.api.plugin.CallCount;
import dev.jasagent.api.plugin.CallStats;
import dev.jasagent.api.plugin.PluginCall;
import dev.jasagent.api.plugin.PluginContext;
import dev.jasagent.api.plugin.PluginFailure;
import dev.jasagent.api.plugin.PluginPhase;
import dev.jasagent.api.plugin.PluginTool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolResult;
import dev.jasagent.api.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginToolTest {
    private static final String WEB_DEFAULT = String.join("\n",
            "title: jasagent",
            "url: https://example.invalid/search?q=jasagent",
            "summary: local:jasagent");
    private static final String WEB_ONE = String.join("\n",
            "title: Alpha",
            "url: https://example.invalid/alpha",
            "summary: brief");
    private static final String WEB_TWO = String.join("\n",
            "title: Alpha",
            "url: https://example.invalid/alpha",
            "summary: brief",
            "",
            "title: Beta",
            "url: https://example.invalid/beta",
            "summary: more");
    private static final String GITHUB_MAIN = String.join("\n",
            "repository: acme/widgets",
            "defaultBranch: main",
            "url: https://github.com/acme/widgets",
            "tree: https://github.com/acme/widgets/tree/main");
    private static final String GITHUB_DEVELOP = String.join("\n",
            "repository: acme/widgets",
            "defaultBranch: develop",
            "url: https://github.com/acme/widgets",
            "tree: https://github.com/acme/widgets/tree/develop");
    private static final String RAG_GUIDE = String.join("\n",
            "uri: guide.md",
            "docId: guide.md");

    @TempDir
    Path temp;

    @Test
    void serviceLoaderLoadsThreeNames() {
        PluginToolCatalog catalog = PluginToolCatalog.load(new ScriptedPassGate(PassDecision.ALLOW));
        assertTrue(catalog.names().containsAll(List.of("web.search", "rag.link", "github.link")));
    }

    @Test
    void loadedToolsAreExternal() {
        PluginToolCatalog catalog = PluginToolCatalog.load(new ScriptedPassGate(PassDecision.ALLOW));
        boolean internal = List.of("web.search", "rag.link", "github.link").stream()
                .anyMatch(name -> catalog.require(name).spec().internal());
        assertFalse(internal);
    }

    @Test
    void duplicateNameFailsStartup() {
        AgentException error = assertThrows(AgentException.class, () -> new PluginToolCatalog(
                new ScriptedPassGate(PassDecision.ALLOW),
                List.of(named("web.search"), named("web.search"))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void defaultWebSearchReturnsThreeLines() {
        ToolResult result = join(allow(new WebSearchTool()).call(call("exec-web-default", "web.search", Map.of("query", "jasagent"))));
        assertEquals(WEB_DEFAULT, result.output());
    }

    @Test
    void injectedSearchReturnsClientLines() {
        WebSearchTool tool = new WebSearchTool(query -> List.of(new SearchHit("Alpha", "https://example.invalid/alpha", "brief")));
        ToolResult result = join(allow(tool).call(call("exec-web-one", "web.search", Map.of("query", "jasagent"))));
        assertEquals(WEB_ONE, result.output());
    }

    @Test
    void twoHitsSeparatedByBlankLine() {
        WebSearchTool tool = new WebSearchTool(query -> List.of(
                new SearchHit("Alpha", "https://example.invalid/alpha", "brief"),
                new SearchHit("Beta", "https://example.invalid/beta", "more")));
        ToolResult result = join(allow(tool).call(call("exec-web-two", "web.search", Map.of("query", "jasagent"))));
        assertEquals(WEB_TWO, result.output());
    }

    @Test
    void acquireSeesZeroExecutions() {
        AtomicInteger hits = new AtomicInteger();
        List<Integer> seen = new ArrayList<>();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW, () -> seen.add(hits.get()));
        PluginToolCatalog catalog = new PluginToolCatalog(gate, List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-before", "web.search", Map.of("query", "jasagent"))));
        assertEquals(0, seen.stream().findFirst().orElse(-1));
    }

    @Test
    void acquireToolNameIsWebSearch() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertEquals("web.search", gate.request().toolName());
    }

    @Test
    void acquireUserIdMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertEquals("user-web", gate.request().userId());
    }

    @Test
    void acquireExecutionIdMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertEquals("exec-web", gate.request().executionId());
    }

    @Test
    void acquirePluginIdIsEmpty() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertNull(gate.request().pluginId());
    }

    @Test
    void acquireUserDirectoryMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        ToolCall call = webCall();
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(call));
        assertEquals(call.userDirectory(), gate.request().userDirectory());
    }

    @Test
    void acquireWorkspaceDirectoryMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        ToolCall call = webCall();
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(call));
        assertEquals(call.workspaceDirectory(), gate.request().workspaceDirectory());
    }

    @Test
    void argumentHashMatchesIndependentSha256() throws Exception {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(digest.digest("query=jasagent".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, gate.request().argumentHash());
    }

    @Test
    void denyDoesNotExecuteWebSearch() {
        AtomicInteger hits = new AtomicInteger();
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.DENY), List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-deny", "web.search", Map.of("query", "jasagent"))));
        assertEquals(0, hits.get());
    }

    @Test
    void pendingDoesNotExecuteWebSearch() {
        AtomicInteger hits = new AtomicInteger();
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.PENDING), List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-pending", "web.search", Map.of("query", "jasagent"))));
        assertEquals(0, hits.get());
    }

    @Test
    void skippedExecutesWebSearch() {
        AtomicInteger hits = new AtomicInteger();
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.SKIPPED), List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-skip", "web.search", Map.of("query", "jasagent"))));
        assertEquals(1, hits.get());
    }

    @Test
    void ragLinkWritesGuideBody() throws Exception {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "guide body"));
        join(allow(tool).call(call("exec-rag-body", "rag.link", Map.of(
                "url", "https://example.invalid/guide.md",
                "scope", "user"))));
        assertEquals("guide body", Files.readString(temp.resolve("user").resolve("rag").resolve("docs").resolve("guide.md")));
    }

    @Test
    void ragLinkReturnsUriAndDocId() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "guide body"));
        ToolResult result = join(allow(tool).call(call("exec-rag-uri", "rag.link", Map.of(
                "url", "https://example.invalid/guide.md",
                "scope", "user"))));
        assertEquals(RAG_GUIDE, result.output());
    }

    @Test
    void ragLinkWritesWorkspacePlainText() throws Exception {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/plain", "plain notes"));
        join(allow(tool).call(call("exec-rag-txt", "rag.link", Map.of(
                "url", "https://example.invalid/notes",
                "scope", "workspace"))));
        Path file = temp.resolve("workspace").resolve("rag").resolve("docs").resolve("notes.txt");
        assertEquals("plain notes", Files.readString(file));
    }

    @Test
    void nonHttpLinkFailsWithTool() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/plain", "nope"));
        AgentException error = assertThrows(AgentException.class, () -> join(allow(tool).call(call("exec-file", "rag.link", Map.of(
                "url", "file:///tmp/guide.md",
                "scope", "user")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void nonHttpLinkWritesNoFile() throws Exception {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/plain", "nope"));
        try {
            join(allow(tool).call(call("exec-file-quiet", "rag.link", Map.of(
                    "url", "file:///tmp/guide.md",
                    "scope", "user"))));
        } catch (RuntimeException ignored) {
            // 本条只看没有写出文件。
        }
        assertEquals(List.of(), regularFiles(temp.resolve("user").resolve("rag").resolve("docs")));
    }

    @Test
    void fakeRagLinkDoesNotSelectProxy() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "guide body"));
        assertNoProxySelect(() -> join(allow(tool).call(call("exec-rag-proxy", "rag.link", Map.of(
                "url", "https://example.invalid/guide.md",
                "scope", "user")))));
    }

    @Test
    void localhostRagLinkWritesBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "hello rag".getBytes(StandardCharsets.UTF_8);
        server.createContext("/guide.md", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/markdown; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/guide.md";
            join(allow(new RagLinkTool()).call(call("exec-local", "rag.link", Map.of("url", url, "scope", "user"))));
            assertEquals("hello rag", Files.readString(temp.resolve("user").resolve("rag").resolve("docs").resolve("guide.md")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void githubOwnerRepoReturnsLinks() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call("exec-gh", "github.link", Map.of("repo", "acme/widgets"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    @Test
    void githubGitUrlReturnsSameLinks() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call("exec-gh-git", "github.link", Map.of("url", "https://github.com/acme/widgets.git"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    @Test
    void githubHttpUrlReturnsSameLinks() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call("exec-gh-http", "github.link", Map.of("url", "http://github.com/acme/widgets"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    @Test
    void injectedBranchReturnsDevelop() {
        GithubLinkTool tool = new GithubLinkTool((owner, name) -> "develop");
        ToolResult result = join(allow(tool).call(call("exec-gh-dev", "github.link", Map.of("repo", "acme/widgets"))));
        assertEquals(GITHUB_DEVELOP, result.output());
    }

    @Test
    void treeUrlDoesNotReplaceDefaultBranch() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call(
                "exec-gh-tree",
                "github.link",
                Map.of("url", "https://github.com/acme/widgets/tree/develop"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    @Test
    void gitlabHostFailsWithTool() {
        AgentException error = assertThrows(AgentException.class, () -> join(allow(new GithubLinkTool()).call(call(
                "exec-gitlab",
                "github.link",
                Map.of("url", "https://gitlab.com/acme/widgets")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void defaultGithubDoesNotSelectProxy() {
        assertNoProxySelect(() -> join(allow(new GithubLinkTool()).call(call("exec-gh-proxy", "github.link", Map.of("repo", "acme/widgets")))));
    }

    @Test
    void defaultWebSearchDoesNotSelectProxy() {
        assertNoProxySelect(() -> join(allow(new WebSearchTool()).call(call("exec-web-proxy", "web.search", Map.of("query", "jasagent")))));
    }

    @Test
    void webSearchSuccessIncrementsOnce() {
        CallCount before = count("web.search");
        join(allow(new WebSearchTool()).call(call("exec-stat-ok", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.success() + 1, count("web.search").success());
    }

    @Test
    void webSearchSuccessLeavesFailureUnchanged() {
        CallCount before = count("web.search");
        join(allow(new WebSearchTool()).call(call("exec-stat-fail-same", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.failure(), count("web.search").failure());
    }

    @Test
    void webSearchFailureIncrementsOnce() {
        CallCount before = count("web.search");
        assertThrows(RuntimeException.class, () -> join(allow(failing("web.search")).call(call("exec-stat-down", "web.search", Map.of("query", "jasagent")))));
        assertEquals(before.failure() + 1, count("web.search").failure());
    }

    @Test
    void webSearchFailureLeavesSuccessUnchanged() {
        CallCount before = count("web.search");
        assertThrows(RuntimeException.class, () -> join(allow(failing("web.search")).call(call("exec-stat-down-ok", "web.search", Map.of("query", "jasagent")))));
        assertEquals(before.success(), count("web.search").success());
    }

    @Test
    void pluginSuccessIncrementsOnce() {
        CallCount before = count("echo.stats");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(echo("echo.stats")));
        join(host.invoke(pluginCall("exec-plugin-ok", "echo.stats", null), new PluginContext(null)));
        assertEquals(before.success() + 1, count("echo.stats").success());
    }

    @Test
    void pluginFailIncrementsFailureOnce() {
        CallCount before = count("fail.stop");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken("fail.stop")));
        assertThrows(AgentException.class, () -> join(host.invoke(pluginCall("exec-plugin-fail", "fail.stop", PluginFailure.FAIL), new PluginContext(null))));
        assertEquals(before.failure() + 1, count("fail.stop").failure());
    }

    @Test
    void pluginFailErrorCodeIsPlugin() {
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken("fail.code")));
        AgentException error = assertThrows(AgentException.class, () -> join(host.invoke(
                pluginCall("exec-plugin-code", "fail.code", PluginFailure.FAIL),
                new PluginContext(null))));
        assertEquals(ErrorCode.PLUGIN, error.code());
    }

    @Test
    void pluginContinueFailureIncrementsOnce() {
        CallCount before = count("fail.continue");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken("fail.continue")));
        join(host.invoke(pluginCall("exec-plugin-continue", "fail.continue", null), new PluginContext(null)));
        assertEquals(before.failure() + 1, count("fail.continue").failure());
    }

    @Test
    void secondServiceLoaderSeesRecordedSuccess() {
        String name = "shared-" + UUID.randomUUID();
        CallStats first = CallStats.load();
        long before = first.count(name).success();
        first.recordSuccess(name);
        CallStats second = CallStats.load();
        assertEquals(before + 1, second.count(name).success());
    }

    @Test
    void webSearchSuccessLeavesGithubUnchanged() {
        CallCount before = count("github.link");
        join(allow(new WebSearchTool()).call(call("exec-isolate", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.success(), count("github.link").success());
    }

    @Test
    void denyLeavesWebSearchSuccessUnchanged() {
        CallCount before = count("web.search");
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.DENY), List.of(new WebSearchTool()));
        join(catalog.call(call("exec-deny-success", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.success(), count("web.search").success());
    }

    @Test
    void denyLeavesWebSearchFailureUnchanged() {
        CallCount before = count("web.search");
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.DENY), List.of(new WebSearchTool()));
        join(catalog.call(call("exec-deny-failure", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.failure(), count("web.search").failure());
    }

    @Test
    void missingQueryFailsWithTool() {
        AgentException error = assertThrows(AgentException.class, () -> join(allow(new WebSearchTool()).call(call("exec-no-query", "web.search", Map.of()))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void missingScopeFailsWithTool() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/plain", "x"));
        AgentException error = assertThrows(AgentException.class, () -> join(allow(tool).call(call(
                "exec-no-scope",
                "rag.link",
                Map.of("url", "https://example.invalid/notes")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void missingGithubTargetFailsWithTool() {
        AgentException error = assertThrows(AgentException.class, () -> join(allow(new GithubLinkTool()).call(call("exec-no-repo", "github.link", Map.of()))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void pluginDenyLeavesSuccessUnchanged() {
        CallCount before = count("deny.plugin");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.DENY), new MemoryPluginCallStore(), List.of(echo("deny.plugin")));
        join(host.invoke(pluginCall("exec-plugin-deny", "deny.plugin", null), new PluginContext(null)));
        assertEquals(before.success(), count("deny.plugin").success());
    }

    @Test
    void pluginDenyLeavesFailureUnchanged() {
        CallCount before = count("deny.plugin");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.DENY), new MemoryPluginCallStore(), List.of(echo("deny.plugin")));
        join(host.invoke(pluginCall("exec-plugin-deny-fail", "deny.plugin", null), new PluginContext(null)));
        assertEquals(before.failure(), count("deny.plugin").failure());
    }

    @Test
    void parentSegmentFailsWithTool() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "secret"));
        AgentException error = assertThrows(AgentException.class, () -> join(allow(tool).call(call(
                "exec-dotdot",
                "rag.link",
                Map.of("url", "https://example.invalid/../secret.md", "scope", "user")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    @Test
    void parentSegmentWritesNoFile() throws Exception {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "secret"));
        try {
            join(allow(tool).call(call(
                    "exec-dotdot-quiet",
                    "rag.link",
                    Map.of("url", "https://example.invalid/../secret.md", "scope", "user"))));
        } catch (RuntimeException ignored) {
            // 本条只看没有写出文件。
        }
        assertEquals(List.of(), regularFiles(temp.resolve("user").resolve("rag").resolve("docs")));
    }

    private PluginToolCatalog allow(PluginTool tool) {
        return new PluginToolCatalog(new ScriptedPassGate(PassDecision.ALLOW), List.of(tool));
    }

    private ToolCall call(String executionId, String name, Map<String, String> arguments) {
        return new ToolCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), name, arguments);
    }

    private ToolCall webCall() {
        return new ToolCall("user-web", "exec-web", temp.resolve("user"), temp.resolve("workspace"), "web.search", Map.of("query", "jasagent"));
    }

    private PluginCall pluginCall(String executionId, String pluginId, PluginFailure failure) {
        return new PluginCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), pluginId, PluginPhase.beforeTool, "query", "", failure);
    }

    private static CallCount count(String toolName) {
        return CallStats.load().count(toolName);
    }

    private static void assertNoProxySelect(Runnable action) {
        AtomicInteger selects = new AtomicInteger();
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                selects.incrementAndGet();
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException error) {
            }
        });
        try {
            action.run();
        } finally {
            ProxySelector.setDefault(previous);
        }
        assertEquals(0, selects.get());
    }

    private static List<Path> regularFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).toList();
        }
    }

    private static PluginTool named(String name) {
        return counting(name, new AtomicInteger());
    }

    private static PluginTool counting(String name, AtomicInteger hits) {
        return new PluginTool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, false);
            }

            @Override
            public CompletionStage<String> call(ToolCall call) {
                hits.incrementAndGet();
                return CompletableFuture.completedFuture("ok");
            }
        };
    }

    private static PluginTool failing(String name) {
        return new PluginTool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, false);
            }

            @Override
            public CompletionStage<String> call(ToolCall call) {
                return CompletableFuture.failedFuture(new IllegalStateException("down"));
            }
        };
    }

    private static AgentPlugin echo(String id) {
        return new AgentPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                return CompletableFuture.completedFuture("echoed");
            }
        };
    }

    private static AgentPlugin broken(String id) {
        return new AgentPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        };
    }

    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class ScriptedPassGate implements PassGate {
        private final List<PassRequest> requests = new CopyOnWriteArrayList<>();
        private final PassDecision decision;
        private final Runnable beforeAcquire;

        private ScriptedPassGate(PassDecision decision) {
            this(decision, () -> {
            });
        }

        private ScriptedPassGate(PassDecision decision, Runnable beforeAcquire) {
            this.decision = decision;
            this.beforeAcquire = beforeAcquire;
        }

        private PassRequest request() {
            return requests.get(0);
        }

        @Override
        public CompletionStage<Pass> acquire(PassRequest request) {
            beforeAcquire.run();
            requests.add(request);
            return CompletableFuture.completedFuture(new Pass(
                    UUID.randomUUID().toString(),
                    request.userId(),
                    request.executionId(),
                    request.toolName(),
                    request.pluginId(),
                    request.argumentHash(),
                    decision,
                    decision == PassDecision.SKIPPED ? "agentpass-not-enabled" : "policy"));
        }
    }
}
