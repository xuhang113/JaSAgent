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

/**
 * 覆盖网页检索、链接入库、GitHub 链接三个工具，以及通行证拦截和调用统计。
 * 网络相关行为用注入的客户端或本机回环服务代替，避免测试依赖外网。
 */
class PluginToolTest {
    /** 默认本地检索对 jasagent 的三行结果：标题、百分号编码后的示例链接、local 摘要。 */
    private static final String WEB_DEFAULT = String.join("\n",
            "title: jasagent",
            "url: https://example.invalid/search?q=jasagent",
            "summary: local:jasagent");
    /** 单条 Alpha 命中渲染后的标题、链接和摘要三行。 */
    private static final String WEB_ONE = String.join("\n",
            "title: Alpha",
            "url: https://example.invalid/alpha",
            "summary: brief");
    /** Alpha 和 Beta 两条命中的渲染文本，两条之间有一个空行。 */
    private static final String WEB_TWO = String.join("\n",
            "title: Alpha",
            "url: https://example.invalid/alpha",
            "summary: brief",
            "",
            "title: Beta",
            "url: https://example.invalid/beta",
            "summary: more");
    /** acme/widgets 的默认分支是 main 时，工具应返回的仓库、分支、主页和 tree 四行。 */
    private static final String GITHUB_MAIN = String.join("\n",
            "repository: acme/widgets",
            "defaultBranch: main",
            "url: https://github.com/acme/widgets",
            "tree: https://github.com/acme/widgets/tree/main");
    /** 同一仓库的默认分支是 develop 时，工具应返回的四行链接。 */
    private static final String GITHUB_DEVELOP = String.join("\n",
            "repository: acme/widgets",
            "defaultBranch: develop",
            "url: https://github.com/acme/widgets",
            "tree: https://github.com/acme/widgets/tree/develop");
    /** 把 guide.md 写入语料后，工具应返回的 uri 和 docId 两行。 */
    private static final String RAG_GUIDE = String.join("\n",
            "uri: guide.md",
            "docId: guide.md");

    @TempDir
    Path temp;

    /**
     * 确认服务加载器能发现 web.search、rag.link 和 github.link。
     * 副作用：只装目录，不执行这三个工具。
     */
    @Test
    void serviceLoaderLoadsThreeNames() {
        PluginToolCatalog catalog = PluginToolCatalog.load(new ScriptedPassGate(PassDecision.ALLOW));
        assertTrue(catalog.names().containsAll(List.of("web.search", "rag.link", "github.link")));
    }

    /**
     * 确认这三个内置工具的规格都不是内部工具。
     * 副作用：只读规格，不执行工具。
     */
    @Test
    void loadedToolsAreExternal() {
        PluginToolCatalog catalog = PluginToolCatalog.load(new ScriptedPassGate(PassDecision.ALLOW));
        boolean internal = List.of("web.search", "rag.link", "github.link").stream()
                .anyMatch(name -> catalog.require(name).spec().internal());
        assertFalse(internal);
    }

    /**
     * 确认目录里出现两个 web.search 时构造失败，错误码是工具错误。
     * 副作用：目录不会登记成功。
     */
    @Test
    void duplicateNameFailsStartup() {
        AgentException error = assertThrows(AgentException.class, () -> new PluginToolCatalog(
                new ScriptedPassGate(PassDecision.ALLOW),
                List.of(named("web.search"), named("web.search"))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 确认不注入客户端时，网页检索返回本地示例的三行文本。
     * 副作用：执行一次被允许的 web.search，全局成功次数加一。
     */
    @Test
    void defaultWebSearchReturnsThreeLines() {
        ToolResult result = join(allow(new WebSearchTool()).call(call("exec-web-default", "web.search", Map.of("query", "jasagent"))));
        assertEquals(WEB_DEFAULT, result.output());
    }

    /**
     * 确认注入的单条命中按标题、链接、摘要三行返回。
     * 副作用：执行一次被允许的检索。
     */
    @Test
    void injectedSearchReturnsClientLines() {
        WebSearchTool tool = new WebSearchTool(query -> List.of(new SearchHit("Alpha", "https://example.invalid/alpha", "brief")));
        ToolResult result = join(allow(tool).call(call("exec-web-one", "web.search", Map.of("query", "jasagent"))));
        assertEquals(WEB_ONE, result.output());
    }

    /**
     * 确认两条命中之间用空行隔开，每条仍是三行。
     * 副作用：执行一次被允许的检索。
     */
    @Test
    void twoHitsSeparatedByBlankLine() {
        WebSearchTool tool = new WebSearchTool(query -> List.of(
                new SearchHit("Alpha", "https://example.invalid/alpha", "brief"),
                new SearchHit("Beta", "https://example.invalid/beta", "more")));
        ToolResult result = join(allow(tool).call(call("exec-web-two", "web.search", Map.of("query", "jasagent"))));
        assertEquals(WEB_TWO, result.output());
    }

    /**
     * 确认申请通行证时工具体还没执行，计数器仍是 0。
     * 副作用：随后把计数工具执行一次。
     */
    @Test
    void acquireSeesZeroExecutions() {
        AtomicInteger hits = new AtomicInteger();
        List<Integer> seen = new ArrayList<>();
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW, () -> seen.add(hits.get()));
        PluginToolCatalog catalog = new PluginToolCatalog(gate, List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-before", "web.search", Map.of("query", "jasagent"))));
        assertEquals(0, seen.stream().findFirst().orElse(-1));
    }

    /**
     * 确认网页检索申请通行证时带上的工具名是 web.search。
     * 副作用：执行一次被允许的默认检索。
     */
    @Test
    void acquireToolNameIsWebSearch() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertEquals("web.search", gate.request().toolName());
    }

    /**
     * 确认通行证申请里的用户标识就是调用里的 user-web。
     * 副作用：执行一次被允许的默认检索。
     */
    @Test
    void acquireUserIdMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertEquals("user-web", gate.request().userId());
    }

    /**
     * 确认通行证申请里的执行标识就是调用里的 exec-web。
     * 副作用：执行一次被允许的默认检索。
     */
    @Test
    void acquireExecutionIdMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertEquals("exec-web", gate.request().executionId());
    }

    /**
     * 确认工具申请不填写插件标识。
     * 副作用：执行一次被允许的默认检索。
     */
    @Test
    void acquirePluginIdIsEmpty() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        assertNull(gate.request().pluginId());
    }

    /**
     * 确认通行证申请里的用户目录就是这次调用的用户目录。
     * 副作用：执行一次被允许的默认检索。
     */
    @Test
    void acquireUserDirectoryMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        ToolCall call = webCall();
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(call));
        assertEquals(call.userDirectory(), gate.request().userDirectory());
    }

    /**
     * 确认通行证申请里的工作区目录就是这次调用的工作区目录。
     * 副作用：执行一次被允许的默认检索。
     */
    @Test
    void acquireWorkspaceDirectoryMatchesCall() {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        ToolCall call = webCall();
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(call));
        assertEquals(call.workspaceDirectory(), gate.request().workspaceDirectory());
    }

    /**
     * 确认参数哈希等于对 query=jasagent 这串字节独立计算的 SHA-256 十六进制。
     * 副作用：执行一次被允许的默认检索。
     */
    @Test
    void argumentHashMatchesIndependentSha256() throws Exception {
        ScriptedPassGate gate = new ScriptedPassGate(PassDecision.ALLOW);
        join(new PluginToolCatalog(gate, List.of(new WebSearchTool())).call(webCall()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(digest.digest("query=jasagent".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, gate.request().argumentHash());
    }

    /**
     * 确认通行证被拒绝时，网页检索的工具体一次都不执行。
     * 副作用：记下未执行的调用，不增加该工具的计数。
     */
    @Test
    void denyDoesNotExecuteWebSearch() {
        AtomicInteger hits = new AtomicInteger();
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.DENY), List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-deny", "web.search", Map.of("query", "jasagent"))));
        assertEquals(0, hits.get());
    }

    /**
     * 确认通行证待审批时，网页检索的工具体一次都不执行。
     * 副作用：记下未执行的调用，不增加该工具的计数。
     */
    @Test
    void pendingDoesNotExecuteWebSearch() {
        AtomicInteger hits = new AtomicInteger();
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.PENDING), List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-pending", "web.search", Map.of("query", "jasagent"))));
        assertEquals(0, hits.get());
    }

    /**
     * 确认通行证被跳过时仍然执行网页检索。
     * 副作用：计数工具加一。
     */
    @Test
    void skippedExecutesWebSearch() {
        AtomicInteger hits = new AtomicInteger();
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.SKIPPED), List.of(counting("web.search", hits)));
        join(catalog.call(call("exec-skip", "web.search", Map.of("query", "jasagent"))));
        assertEquals(1, hits.get());
    }

    /**
     * 确认注入的 markdown 正文被写到用户语料目录的 guide.md。
     * 副作用：创建 docs 目录并写入该文件。
     */
    @Test
    void ragLinkWritesGuideBody() throws Exception {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "guide body"));
        join(allow(tool).call(call("exec-rag-body", "rag.link", Map.of(
                "url", "https://example.invalid/guide.md",
                "scope", "user"))));
        assertEquals("guide body", Files.readString(temp.resolve("user").resolve("rag").resolve("docs").resolve("guide.md")));
    }

    /**
     * 确认链接入库返回的 uri 和 docId 都是 guide.md。
     * 副作用：把 guide body 写入用户语料目录的 guide.md。
     */
    @Test
    void ragLinkReturnsUriAndDocId() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "guide body"));
        ToolResult result = join(allow(tool).call(call("exec-rag-uri", "rag.link", Map.of(
                "url", "https://example.invalid/guide.md",
                "scope", "user"))));
        assertEquals(RAG_GUIDE, result.output());
    }

    /**
     * 确认没有后缀的链接按纯文本写到工作区语料目录的 notes.txt。
     * 副作用：创建工作区 docs 并写入 notes.txt。
     */
    @Test
    void ragLinkWritesWorkspacePlainText() throws Exception {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/plain", "plain notes"));
        join(allow(tool).call(call("exec-rag-txt", "rag.link", Map.of(
                "url", "https://example.invalid/notes",
                "scope", "workspace"))));
        Path file = temp.resolve("workspace").resolve("rag").resolve("docs").resolve("notes.txt");
        assertEquals("plain notes", Files.readString(file));
    }

    /**
     * 确认 file 协议的链接以工具错误失败。
     * 副作用：不写入语料文件。
     */
    @Test
    void nonHttpLinkFailsWithTool() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/plain", "nope"));
        AgentException error = assertThrows(AgentException.class, () -> join(allow(tool).call(call("exec-file", "rag.link", Map.of(
                "url", "file:///tmp/guide.md",
                "scope", "user")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 确认 file 协议失败后，用户语料目录里没有任何普通文件。
     * 副作用：调用会失败，但不留下文件。
     */
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

    /**
     * 确认注入抓取器的链接入库不会去选网络代理。
     * 副作用：写入 guide.md，并暂时换上计数用的代理选择器，结束时恢复。
     */
    @Test
    void fakeRagLinkDoesNotSelectProxy() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "guide body"));
        assertNoProxySelect(() -> join(allow(tool).call(call("exec-rag-proxy", "rag.link", Map.of(
                "url", "https://example.invalid/guide.md",
                "scope", "user")))));
    }

    /**
     * 确认真实 HTTP 抓取能把本机服务返回的正文写入 guide.md。
     * 副作用：在回环地址启动临时服务，写入文件后停止服务。
     */
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

    /**
     * 确认 owner/repo 文本按默认分支 main 渲染四行链接。
     * 副作用：执行一次被允许的 github.link。
     */
    @Test
    void githubOwnerRepoReturnsLinks() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call("exec-gh", "github.link", Map.of("repo", "acme/widgets"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    /**
     * 确认带 .git 后缀的 https 仓库地址和简写得到同一组 main 链接。
     * 副作用：执行一次被允许的 github.link。
     */
    @Test
    void githubGitUrlReturnsSameLinks() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call("exec-gh-git", "github.link", Map.of("url", "https://github.com/acme/widgets.git"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    /**
     * 确认 http 的 github.com 地址同样解析成 main 的四行链接。
     * 副作用：执行一次被允许的 github.link。
     */
    @Test
    void githubHttpUrlReturnsSameLinks() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call("exec-gh-http", "github.link", Map.of("url", "http://github.com/acme/widgets"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    /**
     * 确认注入的默认分支 develop 会写进四行结果，而不是固定的 main。
     * 副作用：执行一次被允许的 github.link。
     */
    @Test
    void injectedBranchReturnsDevelop() {
        GithubLinkTool tool = new GithubLinkTool((owner, name) -> "develop");
        ToolResult result = join(allow(tool).call(call("exec-gh-dev", "github.link", Map.of("repo", "acme/widgets"))));
        assertEquals(GITHUB_DEVELOP, result.output());
    }

    /**
     * 确认链接路径里的 tree/develop 不会取代客户端返回的默认分支 main。
     * 副作用：执行一次被允许的 github.link。
     */
    @Test
    void treeUrlDoesNotReplaceDefaultBranch() {
        ToolResult result = join(allow(new GithubLinkTool()).call(call(
                "exec-gh-tree",
                "github.link",
                Map.of("url", "https://github.com/acme/widgets/tree/develop"))));
        assertEquals(GITHUB_MAIN, result.output());
    }

    /**
     * 确认 gitlab.com 上的仓库地址以工具错误失败。
     * 副作用：不生成链接文本。
     */
    @Test
    void gitlabHostFailsWithTool() {
        AgentException error = assertThrows(AgentException.class, () -> join(allow(new GithubLinkTool()).call(call(
                "exec-gitlab",
                "github.link",
                Map.of("url", "https://gitlab.com/acme/widgets")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 确认默认 GitHub 工具解析仓库时不会去选网络代理。
     * 副作用：暂时换上计数用的代理选择器，结束时恢复。
     */
    @Test
    void defaultGithubDoesNotSelectProxy() {
        assertNoProxySelect(() -> join(allow(new GithubLinkTool()).call(call("exec-gh-proxy", "github.link", Map.of("repo", "acme/widgets")))));
    }

    /**
     * 确认默认网页检索不会去选网络代理。
     * 副作用：暂时换上计数用的代理选择器，结束时恢复。
     */
    @Test
    void defaultWebSearchDoesNotSelectProxy() {
        assertNoProxySelect(() -> join(allow(new WebSearchTool()).call(call("exec-web-proxy", "web.search", Map.of("query", "jasagent")))));
    }

    /**
     * 确认网页检索成功时，web.search 的成功次数只加一。
     * 副作用：改全局调用统计里的成功次数。
     */
    @Test
    void webSearchSuccessIncrementsOnce() {
        CallCount before = count("web.search");
        join(allow(new WebSearchTool()).call(call("exec-stat-ok", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.success() + 1, count("web.search").success());
    }

    /**
     * 确认网页检索成功时，web.search 的失败次数不变。
     * 副作用：全局成功次数加一，失败次数不动。
     */
    @Test
    void webSearchSuccessLeavesFailureUnchanged() {
        CallCount before = count("web.search");
        join(allow(new WebSearchTool()).call(call("exec-stat-fail-same", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.failure(), count("web.search").failure());
    }

    /**
     * 确认工具体失败时，web.search 的失败次数只加一。
     * 副作用：改全局调用统计里的失败次数。
     */
    @Test
    void webSearchFailureIncrementsOnce() {
        CallCount before = count("web.search");
        assertThrows(RuntimeException.class, () -> join(allow(failing("web.search")).call(call("exec-stat-down", "web.search", Map.of("query", "jasagent")))));
        assertEquals(before.failure() + 1, count("web.search").failure());
    }

    /**
     * 确认工具体失败时，web.search 的成功次数不变。
     * 副作用：全局失败次数加一，成功次数不动。
     */
    @Test
    void webSearchFailureLeavesSuccessUnchanged() {
        CallCount before = count("web.search");
        assertThrows(RuntimeException.class, () -> join(allow(failing("web.search")).call(call("exec-stat-down-ok", "web.search", Map.of("query", "jasagent")))));
        assertEquals(before.success(), count("web.search").success());
    }

    /**
     * 确认插件正常返回时，该插件标识的成功次数只加一。
     * 副作用：改全局统计里 echo.stats 的成功次数。
     */
    @Test
    void pluginSuccessIncrementsOnce() {
        CallCount before = count("echo.stats");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(echo("echo.stats")));
        join(host.invoke(pluginCall("exec-plugin-ok", "echo.stats", null), new PluginContext(null)));
        assertEquals(before.success() + 1, count("echo.stats").success());
    }

    /**
     * 确认失败策略为中断时，插件标识的失败次数只加一。
     * 副作用：改全局统计里 fail.stop 的失败次数，并把插件错误抛给调用方。
     */
    @Test
    void pluginFailIncrementsFailureOnce() {
        CallCount before = count("fail.stop");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken("fail.stop")));
        assertThrows(AgentException.class, () -> join(host.invoke(pluginCall("exec-plugin-fail", "fail.stop", PluginFailure.FAIL), new PluginContext(null))));
        assertEquals(before.failure() + 1, count("fail.stop").failure());
    }

    /**
     * 确认失败策略为中断时，抛出的错误码是插件错误。
     * 副作用：改全局统计里 fail.code 的失败次数。
     */
    @Test
    void pluginFailErrorCodeIsPlugin() {
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken("fail.code")));
        AgentException error = assertThrows(AgentException.class, () -> join(host.invoke(
                pluginCall("exec-plugin-code", "fail.code", PluginFailure.FAIL),
                new PluginContext(null))));
        assertEquals(ErrorCode.PLUGIN, error.code());
    }

    /**
     * 确认默认失败策略不把异常抛出调用方，但失败次数仍加一。
     * 副作用：改全局统计里 fail.continue 的失败次数。
     */
    @Test
    void pluginContinueFailureIncrementsOnce() {
        CallCount before = count("fail.continue");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.ALLOW), new MemoryPluginCallStore(), List.of(broken("fail.continue")));
        join(host.invoke(pluginCall("exec-plugin-continue", "fail.continue", null), new PluginContext(null)));
        assertEquals(before.failure() + 1, count("fail.continue").failure());
    }

    /**
     * 确认再次加载的调用统计能看到刚才记下的同名成功次数。
     * 副作用：给一个随机工具名的全局成功次数加一。
     */
    @Test
    void secondServiceLoaderSeesRecordedSuccess() {
        String name = "shared-" + UUID.randomUUID();
        CallStats first = CallStats.load();
        long before = first.count(name).success();
        first.recordSuccess(name);
        CallStats second = CallStats.load();
        assertEquals(before + 1, second.count(name).success());
    }

    /**
     * 确认网页检索成功不会改 github.link 的成功次数。
     * 副作用：只增加 web.search 的成功次数。
     */
    @Test
    void webSearchSuccessLeavesGithubUnchanged() {
        CallCount before = count("github.link");
        join(allow(new WebSearchTool()).call(call("exec-isolate", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.success(), count("github.link").success());
    }

    /**
     * 确认通行证被拒绝时，web.search 的成功次数不变。
     * 副作用：不执行工具，也不改成功次数。
     */
    @Test
    void denyLeavesWebSearchSuccessUnchanged() {
        CallCount before = count("web.search");
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.DENY), List.of(new WebSearchTool()));
        join(catalog.call(call("exec-deny-success", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.success(), count("web.search").success());
    }

    /**
     * 确认通行证被拒绝时，web.search 的失败次数不变。
     * 副作用：不执行工具，也不改失败次数。
     */
    @Test
    void denyLeavesWebSearchFailureUnchanged() {
        CallCount before = count("web.search");
        PluginToolCatalog catalog = new PluginToolCatalog(new ScriptedPassGate(PassDecision.DENY), List.of(new WebSearchTool()));
        join(catalog.call(call("exec-deny-failure", "web.search", Map.of("query", "jasagent"))));
        assertEquals(before.failure(), count("web.search").failure());
    }

    /**
     * 确认网页检索缺少 query 时错误码是工具错误。
     * 副作用：不调用检索客户端。
     */
    @Test
    void missingQueryFailsWithTool() {
        AgentException error = assertThrows(AgentException.class, () -> join(allow(new WebSearchTool()).call(call("exec-no-query", "web.search", Map.of()))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 确认链接入库缺少 scope 时错误码是工具错误。
     * 副作用：不写语料文件。
     */
    @Test
    void missingScopeFailsWithTool() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/plain", "x"));
        AgentException error = assertThrows(AgentException.class, () -> join(allow(tool).call(call(
                "exec-no-scope",
                "rag.link",
                Map.of("url", "https://example.invalid/notes")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 确认 GitHub 工具既没有 url 也没有 repo 时错误码是工具错误。
     * 副作用：不解析仓库。
     */
    @Test
    void missingGithubTargetFailsWithTool() {
        AgentException error = assertThrows(AgentException.class, () -> join(allow(new GithubLinkTool()).call(call("exec-no-repo", "github.link", Map.of()))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 确认插件通行证被拒绝时，该插件的成功次数不变。
     * 副作用：不进入插件体。
     */
    @Test
    void pluginDenyLeavesSuccessUnchanged() {
        CallCount before = count("deny.plugin");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.DENY), new MemoryPluginCallStore(), List.of(echo("deny.plugin")));
        join(host.invoke(pluginCall("exec-plugin-deny", "deny.plugin", null), new PluginContext(null)));
        assertEquals(before.success(), count("deny.plugin").success());
    }

    /**
     * 确认插件通行证被拒绝时，该插件的失败次数不变。
     * 副作用：不进入插件体。
     */
    @Test
    void pluginDenyLeavesFailureUnchanged() {
        CallCount before = count("deny.plugin");
        ServicePluginHost host = new ServicePluginHost(new ScriptedPassGate(PassDecision.DENY), new MemoryPluginCallStore(), List.of(echo("deny.plugin")));
        join(host.invoke(pluginCall("exec-plugin-deny-fail", "deny.plugin", null), new PluginContext(null)));
        assertEquals(before.failure(), count("deny.plugin").failure());
    }

    /**
     * 确认链接路径里的 .. 以工具错误失败，避免写到语料目录外面。
     * 副作用：不写文件。
     */
    @Test
    void parentSegmentFailsWithTool() {
        RagLinkTool tool = new RagLinkTool(url -> new LinkResponse("text/markdown", "secret"));
        AgentException error = assertThrows(AgentException.class, () -> join(allow(tool).call(call(
                "exec-dotdot",
                "rag.link",
                Map.of("url", "https://example.invalid/../secret.md", "scope", "user")))));
        assertEquals(ErrorCode.TOOL, error.code());
    }

    /**
     * 确认含 .. 的链接失败后，用户语料目录里没有普通文件。
     * 副作用：调用会失败，但不留下文件。
     */
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

    /**
     * 用一律允许的通行证门包住单个工具。
     *
     * @param tool 放进目录的唯一工具
     * @return 允许该工具执行的目录
     */
    private PluginToolCatalog allow(PluginTool tool) {
        return new PluginToolCatalog(new ScriptedPassGate(PassDecision.ALLOW), List.of(tool));
    }

    /**
     * 组装用户 aa、临时目录和给定参数的一次工具调用。
     *
     * @param executionId 执行标识
     * @param name 工具名
     * @param arguments 工具参数
     * @return 指向本次临时用户目录和工作区目录的调用
     */
    private ToolCall call(String executionId, String name, Map<String, String> arguments) {
        return new ToolCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), name, arguments);
    }

    /**
     * 组装核对通行证字段时使用的网页检索调用。
     *
     * @return 用户 user-web、执行 exec-web、查询 jasagent 的调用
     */
    private ToolCall webCall() {
        return new ToolCall("user-web", "exec-web", temp.resolve("user"), temp.resolve("workspace"), "web.search", Map.of("query", "jasagent"));
    }

    /**
     * 组装 beforeTool 阶段、参数为 query 的插件调用。
     *
     * @param executionId 执行标识
     * @param pluginId 插件标识，也是统计用的名字
     * @param failure 失败时是继续还是中断，空引用表示默认继续
     * @return 指向本次临时目录的插件调用
     */
    private PluginCall pluginCall(String executionId, String pluginId, PluginFailure failure) {
        return new PluginCall("aa", executionId, temp.resolve("user"), temp.resolve("workspace"), pluginId, PluginPhase.beforeTool, "query", "", failure);
    }

    /**
     * 读取全局统计里某个工具或插件目前的成功和失败次数。
     *
     * @param toolName 工具名或插件标识
     * @return 该名字当前的计数
     */
    private static CallCount count(String toolName) {
        return CallStats.load().count(toolName);
    }

    /**
     * 执行动作期间如果有代码来选代理，就让断言失败。
     * 副作用：暂时换成计数用的默认代理选择器，动作结束后恢复原来的选择器。
     *
     * @param action 不应触发代理选择的动作
     */
    private static void assertNoProxySelect(Runnable action) {
        AtomicInteger selects = new AtomicInteger();
        ProxySelector previous = ProxySelector.getDefault();
        /**
         * 计数用的代理选择器，被选中时只记录次数并声明不走代理。
         */
        ProxySelector.setDefault(new ProxySelector() {
            /**
             * 记录一次代理选择，并告诉调用方直接连接。
             * 副作用：选择次数加一。
             *
             * @param uri 正要连接的地址
             * @return 只含不走代理这一项的列表
             */
            @Override
            public List<Proxy> select(URI uri) {
                selects.incrementAndGet();
                return List.of(Proxy.NO_PROXY);
            }

            /**
             * 连接失败时不更换代理，测试只关心有没有发生选择。
             * 副作用：不记录失败，也不发起新的连接。
             *
             * @param uri 连接失败的地址
             * @param address 失败的套接字地址
             * @param error 连接时的输入输出异常
             */
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

    /**
     * 列出目录下的普通文件，用来确认失败的写入没有留下文件。
     *
     * @param dir 语料目录，可以尚不存在
     * @return 目录里的普通文件；目录不存在时为空列表
     */
    private static List<Path> regularFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).toList();
        }
    }

    /**
     * 做一个指定名字的计数工具，计数器不向外暴露，只用来制造重名。
     *
     * @param name 工具规格里的名字
     * @return 被调用时会计数并返回 ok 的工具
     */
    private static PluginTool named(String name) {
        return counting(name, new AtomicInteger());
    }

    /**
     * 做一个指定名字的工具，每次执行都把计数器加一。
     *
     * @param name 工具规格里的名字
     * @param hits 记录工具体实际执行次数的计数器
     * @return 被调用时计数并返回 ok 的工具
     */
    private static PluginTool counting(String name, AtomicInteger hits) {
        /**
         * 可观察调用次数的工具，规格名和说明都用传入的名字。
         */
        return new PluginTool() {
            /**
             * 公布测试工具的名字，并标明它不是内部工具。
             *
             * @return 名称和说明都是构造时传入的名字
             */
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, false);
            }

            /**
             * 把调用次数加一，表示工具体已经执行。
             *
             * @param call 本次工具调用，这里不读取参数
             * @return 已完成的文本 ok
             */
            @Override
            public CompletionStage<String> call(ToolCall call) {
                hits.incrementAndGet();
                return CompletableFuture.completedFuture("ok");
            }
        };
    }

    /**
     * 做一个一调用就失败的工具，用来观察失败统计。
     *
     * @param name 工具规格里的名字
     * @return 调用阶段会以 down 失败的工具
     */
    private static PluginTool failing(String name) {
        /**
         * 规格正常、调用必定失败的工具。
         */
        return new PluginTool() {
            /**
             * 公布这个失败工具的名字。
             *
             * @return 名称和说明都是构造时传入的名字
             */
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, false);
            }

            /**
             * 让这次调用以非法状态异常结束，消息是 down。
             *
             * @param call 本次工具调用，这里不读取参数
             * @return 已经失败的阶段，不会给出文本
             */
            @Override
            public CompletionStage<String> call(ToolCall call) {
                return CompletableFuture.failedFuture(new IllegalStateException("down"));
            }
        };
    }

    /**
     * 做一个按给定标识正常返回 echoed 的插件。
     *
     * @param id 插件标识，同时作为统计名
     * @return 不检查上下文的回声插件
     */
    private static AgentPlugin echo(String id) {
        /**
         * 标识由外层参数决定、调用结果固定为 echoed 的插件。
         */
        return new AgentPlugin() {
            /**
             * 返回外层传入的插件标识。
             *
             * @return 构造回声插件时给定的标识
             */
            @Override
            public String id() {
                return id;
            }

            /**
             * 不读取调用内容，直接返回 echoed。
             *
             * @param call 插件调用，这里不使用
             * @param context 插件上下文，这里不使用
             * @return 已完成的文本 echoed
             */
            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                return CompletableFuture.completedFuture("echoed");
            }
        };
    }

    /**
     * 做一个按给定标识一调用就失败的插件。
     *
     * @param id 插件标识，同时作为统计名
     * @return 以 boom 失败的插件
     */
    private static AgentPlugin broken(String id) {
        /**
         * 标识由外层参数决定、调用必定失败的插件。
         */
        return new AgentPlugin() {
            /**
             * 返回外层传入的插件标识。
             *
             * @return 构造失败插件时给定的标识
             */
            @Override
            public String id() {
                return id;
            }

            /**
             * 让插件调用以非法状态异常结束，消息是 boom。
             *
             * @param call 插件调用，这里不使用
             * @param context 插件上下文，这里不使用
             * @return 已经失败的阶段
             */
            @Override
            public CompletionStage<String> invoke(PluginCall call, PluginContext context) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        };
    }

    /**
     * 在 30 秒内取回异步结果。运行时异常和错误原样抛出，其它原因包成非法状态。
     * 副作用：等待被中断时重新置上当前线程的中断标记。
     *
     * @param <T> 阶段的结果类型
     * @param stage 要等待的异步阶段
     * @return 阶段的正常结果
     */
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

    /**
     * 测试用通行证门，每次申请都返回构造时指定的同一种决定。
     * 跳过的原因写成未启用，其它决定的原因写成策略。
     */
    private static final class ScriptedPassGate implements PassGate {
        private final List<PassRequest> requests = new CopyOnWriteArrayList<>();
        private final PassDecision decision;
        private final Runnable beforeAcquire;

        /**
         * 申请前不额外执行动作。
         *
         * @param decision 每次申请都返回的决定
         */
        private ScriptedPassGate(PassDecision decision) {
            this(decision, () -> {
            });
        }

        /**
         * 记住决定，并在登记申请之前运行给定动作。
         *
         * @param decision 每次申请都返回的决定
         * @param beforeAcquire 申请被记下之前要跑的动作，用来观察工具是否已经执行
         */
        private ScriptedPassGate(PassDecision decision, Runnable beforeAcquire) {
            this.decision = decision;
            this.beforeAcquire = beforeAcquire;
        }

        /**
         * 取出第一次收到的通行证申请。
         *
         * @return 最早的那次申请，供断言核对用户、执行、目录和哈希
         */
        private PassRequest request() {
            return requests.get(0);
        }

        /**
         * 先跑前置动作，再记下申请并立即给出决定。
         * 副作用：把申请追加到列表。跳过决定的原因是未启用，其它决定的原因是策略。
         *
         * @param request 工具或插件带来的申请
         * @return 已完成的通行证，决定与构造时相同
         */
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
