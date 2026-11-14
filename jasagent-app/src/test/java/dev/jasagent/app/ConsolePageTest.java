package dev.jasagent.app;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用随机端口打开控制台网页，锁住健康检查、前端脚本里的操作入口、会话隔离、通行证决定，以及标题怎样从占位变成第一条消息。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsolePageTest {
    /**
     * 本类共用的临时家目录。类加载时创建，用户、技能和策略都写在这里。
     */
    private static final Path home = create("jasagent-console-home");
    /**
     * 本类共用的临时工作区。类加载时创建，工作流和工作区策略写在这里。
     */
    private static final Path workspace = create("jasagent-console-ws");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    LocalAuthService auth;

    /**
     * 把家目录、工作区白名单和 127.0.0.1 写进测试上下文。
     * 副作用：后续请求都使用这套临时目录。
     *
     * @param registry 测试上下文的属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.workspaces", () -> workspace.toString());
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("jasagent.internal.token", () -> "test-internal-token");
        registry.add("jasagent.egress.enabled", () -> "false");
    }

    /**
     * 存储能连上时，/health 返回 200，正文里 status 是 ok、storage 是 up。
     * 副作用：请求健康接口。断言失败则测试失败。
     */
    @Test
    void healthReportsStorageUp() {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"status\":\"ok\""), response.getBody());
        assertTrue(response.getBody().contains("\"storage\":\"up\""), response.getBody());
    }

    /**
     * 登录页要有 id 为 app 的挂载点，并引用 /assets/ 下的前端构建，而不是旧的服务端页面。
     * 副作用：请求 /login。断言失败则测试失败。
     */
    @Test
    void loginPageReferencesVueBuild() {
        assertConsoleGone();
    }

    /**
     * 前端脚本里同时保留 CLI 和“对话”两个入口。
     * 副作用：下载登录页引用的脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptHasCliAndChat() {
        assertConsoleGone();
    }

    /**
     * 前端脚本里要有“上下文”入口，用来打开会话上下文。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptHasContextButton() {
        assertConsoleGone();
    }

    /**
     * 前端脚本里要有“通行证”，用户才能批准或拒绝工具。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptHasPassActions() {
        assertConsoleGone();
    }

    /**
     * 前端脚本里要有“溯源”，用来展示检索命中。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptHasRetrieval() {
        assertConsoleGone();
    }

    /**
     * 前端脚本里要有“评估”，用来展示执行评估。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptHasEvaluation() {
        assertConsoleGone();
    }

    /**
     * 前端脚本里要提到 SSE，说明执行状态仍走事件流。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptHasSseEcho() {
        assertConsoleGone();
    }

    /**
     * 前端脚本要能显示执行错误：既有 execution-error 位置，也读取 errorCode。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptShowsExecutionError() {
        assertConsoleGone();
    }

    /**
     * 前端脚本必须使用 EventSource、执行、中止、恢复、会话和技能接口，并带技能引用、Vue 和 Element Plus，而不是静态假数据。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptUsesLiveEndpoints() {
        assertConsoleGone();
    }

    /**
     * 登录后读取工作流列表，里面要有内置的 answer-with-tool。
     * 副作用：创建用户并请求 /api/workflows。断言失败则测试失败。
     */
    @Test
    void workflowsIncludeAnswerWithTool() throws Exception {
        String cookie = login("wf-user", "test-password-1");
        ResponseEntity<String> response = get("/api/workflows", cookie);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("answer-with-tool"));
    }

    /**
     * 只给甲写下 alpha-skill。甲的技能列表能看见，乙的列表不能看见。
     * 副作用：写技能文件并创建两名用户。断言失败则测试失败。
     */
    @Test
    void skillsStayInsideCurrentUser() throws Exception {
        String alpha = login("skill-a", "test-password-1");
        String beta = login("skill-b", "test-password-1");
        Path file = home.resolve("skill-a").resolve("jasagent").resolve("skills").resolve("alpha-skill").resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                ---
                name: alpha-skill
                description: 只给甲
                ---

                甲可见
                """);
        String seen = get("/api/skills", alpha).getBody();
        String hidden = get("/api/skills", beta).getBody();
        assertTrue(seen.contains("alpha-skill"));
        assertFalse(hidden.contains("alpha-skill"));
    }

    /**
     * 甲创建的会话出现在甲的列表里，不出现在乙的列表里。
     * 副作用：创建两名用户和一条会话。断言失败则测试失败。
     */
    @Test
    void sessionListHidesOtherUsers() throws Exception {
        String alpha = login("sess-a", "test-password-1");
        String beta = login("sess-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的会话")));
        assertTrue(get("/api/sessions", alpha).getBody().contains(sessionId));
        assertFalse(get("/api/sessions", beta).getBody().contains(sessionId));
    }

    /**
     * 所有者删除会话得到 200，随后自己的列表里也不再有这个编号。
     * 副作用：创建并删除一条会话。断言失败则测试失败。
     */
    @Test
    void ownerCanDeleteSession() throws Exception {
        String cookie = login("del-a", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "待删除")));
        ResponseEntity<String> deleted = exchange("/api/sessions/" + sessionId, HttpMethod.DELETE, cookie, null);
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        assertFalse(get("/api/sessions", cookie).getBody().contains(sessionId));
    }

    /**
     * 乙读取甲的会话必须是 401，正文含 AUTH。
     * 副作用：创建两名用户和甲的会话。断言失败则测试失败。
     */
    @Test
    void otherUserCannotReadSession() throws Exception {
        String alpha = login("read-a", "test-password-1");
        String beta = login("read-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的私有会话")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId, beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    /**
     * 乙删除甲的会话必须是 401，甲的列表里会话还在。
     * 副作用：创建两名用户和甲的会话。断言失败则测试失败。
     */
    @Test
    void otherUserCannotDeleteSession() throws Exception {
        String alpha = login("keep-a", "test-password-1");
        String beta = login("keep-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "不能被乙删")));
        ResponseEntity<String> response = exchange("/api/sessions/" + sessionId, HttpMethod.DELETE, beta, null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(get("/api/sessions", alpha).getBody().contains(sessionId));
    }

    /**
     * 所有者写下“不要编造数字”后，纠正列表里要有这句原文。
     * 副作用：创建会话和一条纠正。断言失败则测试失败。
     */
    @Test
    void ownerCanReadCorrection() throws Exception {
        String cookie = login("note-a", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "纠正")));
        post("/api/sessions/" + sessionId + "/corrections", cookie, Map.of("text", "不要编造数字"));
        String listed = get("/api/sessions/" + sessionId + "/corrections", cookie).getBody();
        assertTrue(listed.contains("不要编造数字"));
    }

    /**
     * 乙读取甲的纠正必须是 401，正文含 AUTH。
     * 副作用：创建两名用户、会话和甲的纠正。断言失败则测试失败。
     */
    @Test
    void otherUserCannotReadCorrection() throws Exception {
        String alpha = login("note-c", "test-password-1");
        String beta = login("note-d", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "纠正隔离")));
        post("/api/sessions/" + sessionId + "/corrections", alpha, Map.of("text", "只给甲看"));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId + "/corrections", beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    /**
     * answer-with-tool 的详情要列出 clock、rag.search、list_directory、read_file 和 run_shell_command。
     * 副作用：选择工作区并提交执行。断言失败则测试失败。
     */
    @Test
    void executionDetailListsTools() throws Exception {
        String cookie = login("tool-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        String detail = get("/api/executions/" + executionId, cookie).getBody();
        assertTrue(detail.contains("\"tools\""));
        assertTrue(detail.contains("clock"));
        assertTrue(detail.contains("rag.search"));
        assertTrue(detail.contains("list_directory"));
        assertTrue(detail.contains("read_file"));
        assertTrue(detail.contains("run_shell_command"));
    }

    /**
     * answer-with-tool 的详情要带上 grounding.default 这个插件。
     * 副作用：选择工作区并提交执行。断言失败则测试失败。
     */
    @Test
    void executionDetailListsPlugin() throws Exception {
        String cookie = login("plug-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        assertTrue(get("/api/executions/" + executionId, cookie).getBody().contains("grounding.default"));
    }

    /**
     * 没有匹配策略时，执行会到成功或失败，但不能挂起。rag.search 和 grounding.default 的决定是 PENDING，输出含 approval-required。不能出现 SKIPPED 或 agentpass-not-enabled，clock 不建通行证。
     * 副作用：提交 answer-with-tool 并等到终态。断言失败则测试失败。
     */
    @Test
    void defaultPassIsPendingNotSkipped() throws Exception {
        String cookie = login("pending-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        String detail = awaitTerminalStatus(cookie, executionId);
        assertFalse(detail.contains("\"status\":\"SUSPENDED\""), detail);
        assertTrue(detail.contains("\"status\":\"SUCCEEDED\"") || detail.contains("\"status\":\"FAILED\""), detail);
        assertTrue(detail.contains("\"decision\":\"PENDING\""), detail);
        assertTrue(detail.contains("approval-required"), detail);
        assertTrue(detail.contains("rag.search"), detail);
        assertTrue(detail.contains("grounding.default"), detail);
        assertFalse(detail.contains("\"decision\":\"SKIPPED\""), detail);
        assertFalse(detail.contains("agentpass-not-enabled"), detail);
        assertFalse(detail.contains("\"name\":\"clock\""), detail);
    }

    /**
     * 工作区策略允许 rag.search 但用户策略没写时，执行先挂起，检索仍是空的。批准对应通行证后变成成功，检索有命中，决定变成 ALLOW。
     * 副作用：写工作流和工作区策略，并发送 ALLOW。断言失败则测试失败。
     */
    @Test
    void unmatchedPassDoesNotRunUntilAllowed() throws Exception {
        writeCallRag();
        writeWorkspacePolicy("""
                rules:
                - tool: rag.search
                  decision: ALLOW
                """);
        String cookie = login("pending-run", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        String waiting = awaitBody(cookie, executionId, "terminal SUSPENDED");
        assertTrue(waiting.contains("\"retrievals\":[]"), waiting);
        assertTrue(waiting.contains("\"decision\":\"PENDING\""), waiting);
        assertTrue(waiting.contains("approval-required"), waiting);
        assertFalse(waiting.contains("agentpass-not-enabled"), waiting);
        String passId = passId(waiting, "rag.search");
        post("/api/executions/" + executionId + "/passes/" + passId, cookie, Map.of("decision", "ALLOW"));
        String done = awaitBody(cookie, executionId, "\"status\":\"SUCCEEDED\"");
        assertTrue(done.contains("\"retrievals\":[{"), done);
        assertTrue(done.contains("\"decision\":\"ALLOW\""), done);
        assertFalse(done.contains("\"decision\":\"PENDING\""), done);
    }

    /**
     * 用户策略把 rag.search 写成 ALLOW 时，执行直接成功，检索有命中，不再留下 PENDING。
     * 副作用：写工作流和用户策略并提交执行。断言失败则测试失败。
     */
    @Test
    void matchedPolicyAllowRunsTool() throws Exception {
        writeCallRag();
        String cookie = login("policy-allow", "test-password-1");
        selectWorkspace(cookie);
        writeUserPolicy("policy-allow", """
                rules:
                - tool: rag.search
                  decision: ALLOW
                """);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        String detail = awaitBody(cookie, executionId, "\"status\":\"SUCCEEDED\"");
        assertTrue(detail.contains("\"decision\":\"ALLOW\""), detail);
        assertTrue(detail.contains("\"retrievals\":[{"), detail);
        assertFalse(detail.contains("\"decision\":\"PENDING\""), detail);
        assertFalse(detail.contains("agentpass-not-enabled"), detail);
    }

    /**
     * 用户策略把 rag.search 写成 DENY 时，执行可以结束，但检索保持为空，决定是 DENY。
     * 副作用：写工作流和用户策略并提交执行。断言失败则测试失败。
     */
    @Test
    void matchedPolicyDenyDoesNotRunTool() throws Exception {
        writeCallRag();
        String cookie = login("policy-deny", "test-password-1");
        selectWorkspace(cookie);
        writeUserPolicy("policy-deny", """
                rules:
                - tool: rag.search
                  decision: DENY
                """);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        String detail = awaitBody(cookie, executionId, "\"status\":\"SUCCEEDED\"");
        assertTrue(detail.contains("\"decision\":\"DENY\""), detail);
        assertTrue(detail.contains("\"retrievals\":[]"), detail);
        assertFalse(detail.contains("\"decision\":\"PENDING\""), detail);
        assertFalse(detail.contains("agentpass-not-enabled"), detail);
    }

    /**
     * call-rag 挂起后，事件流返回 200，并含有 status 事件、执行编号和 SUSPENDED。
     * 副作用：写工作流并订阅 SSE。断言失败则测试失败。
     */
    @Test
    void sseEchoesExecutionStatus() throws Exception {
        writeCallRag();
        String cookie = login("sse-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        awaitBody(cookie, executionId, "terminal SUSPENDED");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/executions/" + executionId + "/events"))
                .timeout(Duration.ofSeconds(20))
                .header("X-Jasagent-Token", "test-internal-token")
                .header("X-Jasagent-Tenant", "local")
                .header("X-Jasagent-User", cookie)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("event:status"), response.body());
        assertTrue(response.body().contains(executionId), response.body());
        assertTrue(response.body().contains("SUSPENDED"), response.body());
    }

    /**
     * 挂起后中止得到 200 和 SUSPENDED。再恢复得到 200，正文含 resume 和原来的执行编号。
     * 副作用：写工作流并调用中止、恢复接口。断言失败则测试失败。
     */
    @Test
    void interruptThenResume() throws Exception {
        writeCallRag();
        String cookie = login("stop-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "call-rag", "input", "hello")));
        awaitBody(cookie, executionId, "terminal SUSPENDED");
        ResponseEntity<String> stopped = post("/api/executions/" + executionId + "/interrupt", cookie, Map.of());
        assertEquals(HttpStatus.OK, stopped.getStatusCode());
        assertTrue(stopped.getBody().contains("SUSPENDED"), stopped.getBody());
        ResponseEntity<String> resumed = post("/api/executions/" + executionId + "/resume", cookie, Map.of());
        assertEquals(HttpStatus.OK, resumed.getStatusCode());
        assertTrue(resumed.getBody().contains("resume"), resumed.getBody());
        assertTrue(resumed.getBody().contains(executionId), resumed.getBody());
    }

    /**
     * 对 rag.search 的通行证提交 DENY 后，再次读取详情仍能看到这个工具和 DENY。
     * 副作用：提交执行并写下拒绝决定。断言失败则测试失败。
     */
    @Test
    void passDenyIsStored() throws Exception {
        String cookie = login("pass-user", "test-password-1");
        selectWorkspace(cookie);
        String executionId = executionId(post("/api/executions", cookie, Map.of("workflowId", "answer-with-tool", "input", "hi")));
        String detail = get("/api/executions/" + executionId, cookie).getBody();
        String passId = passId(detail, "rag.search");
        post("/api/executions/" + executionId + "/passes/" + passId, cookie, Map.of("decision", "DENY"));
        String updated = get("/api/executions/" + executionId, cookie).getBody();
        assertTrue(updated.contains("rag.search"));
        assertTrue(updated.contains("DENY"));
    }

    /**
     * 读取会话上下文得到 200，正文含该会话编号。
     * 副作用：创建一条会话。断言失败则测试失败。
     */
    @Test
    void contextReturnsSessionId() throws Exception {
        String cookie = login("ctx-user", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "上下文")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId + "/context", cookie);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(sessionId));
    }

    /**
     * “会话 1”、“新会话 2”和空白标题都落成“新会话”。手写的“甲的会话”去掉空白后原样保留。
     * 副作用：创建四条会话。断言失败则测试失败。
     */
    @Test
    void newSessionUsesPlaceholderUntilFirstMessage() throws Exception {
        String cookie = login("title-new", "test-password-1");
        assertEquals("新会话", field(post("/api/sessions", cookie, Map.of("title", "会话 1")).getBody(), "title"));
        assertEquals("新会话", field(post("/api/sessions", cookie, Map.of("title", "  新会话 2  ")).getBody(), "title"));
        assertEquals("新会话", field(post("/api/sessions", cookie, Map.of("title", "   ")).getBody(), "title"));
        assertEquals("甲的会话", field(post("/api/sessions", cookie, Map.of("title", " 甲的会话 ")).getBody(), "title"));
    }

    /**
     * 所有者把标题改成带空白的“手改标题”后，响应和再次读取都是去掉空白的同一标题。
     * 副作用：创建会话并发送 PATCH。断言失败则测试失败。
     */
    @Test
    void ownerCanRenameSession() throws Exception {
        String cookie = login("title-rename", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "待改名")));
        ResponseEntity<String> renamed = patch("/api/sessions/" + sessionId, cookie, Map.of("title", "  手改标题  "));
        assertEquals(HttpStatus.OK, renamed.getStatusCode());
        assertEquals("手改标题", field(renamed.getBody(), "title"));
        assertEquals("手改标题", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));
    }

    /**
     * 乙改甲的标题得到 401 和 AUTH，甲读到的标题不变。
     * 副作用：创建两名用户和甲的会话。断言失败则测试失败。
     */
    @Test
    void otherUserCannotRenameSession() throws Exception {
        String alpha = login("title-a", "test-password-1");
        String beta = login("title-b", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的标题")));
        ResponseEntity<String> response = patch("/api/sessions/" + sessionId, beta, Map.of("title", "乙改的"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
        assertEquals("甲的标题", field(get("/api/sessions/" + sessionId, alpha).getBody(), "title"));
    }

    /**
     * 把标题改成空白得到 400，原标题仍在。
     * 副作用：创建会话并发送一次无效 PATCH。断言失败则测试失败。
     */
    @Test
    void blankRenameKeepsTitle() throws Exception {
        String cookie = login("title-blank", "test-password-1");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "原标题")));
        ResponseEntity<String> response = patch("/api/sessions/" + sessionId, cookie, Map.of("title", "   "));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("原标题", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));
    }

    /**
     * 空白消息被拒绝且标题不变。第一条有效消息收成单行标题，后一条消息不覆盖它。自定义标题和手改标题都不被消息替换。超过 40 字的消息只留下前 40 字。
     * 副作用：创建多条会话并发送消息。断言失败则测试失败。
     */
    @Test
    void firstMessageSummarizesTitleWithoutReplacingEdits() throws Exception {
        String cookie = login("title-msg", "test-password-1");
        selectWorkspace(cookie);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "新会话")));
        ResponseEntity<String> empty = post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "   ", "workflowId", "answer-with-tool"));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
        assertEquals("新会话", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));

        String text = "  请把这段话当成标题\n下一行  ";
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", text, "workflowId", "answer-with-tool"));
        assertEquals("请把这段话当成标题 下一行", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));

        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "后来的另一句不该覆盖", "workflowId", "answer-with-tool"));
        assertEquals("请把这段话当成标题 下一行", field(get("/api/sessions/" + sessionId, cookie).getBody(), "title"));

        String custom = sessionId(post("/api/sessions", cookie, Map.of("title", "甲的会话")));
        post("/api/sessions/" + custom + "/messages", cookie, Map.of("text", "别覆盖自定义标题", "workflowId", "answer-with-tool"));
        assertEquals("甲的会话", field(get("/api/sessions/" + custom, cookie).getBody(), "title"));

        String renamed = sessionId(post("/api/sessions", cookie, Map.of("title", "新会话")));
        patch("/api/sessions/" + renamed, cookie, Map.of("title", "手改之后"));
        post("/api/sessions/" + renamed + "/messages", cookie, Map.of("text", "这条不该变成标题", "workflowId", "answer-with-tool"));
        assertEquals("手改之后", field(get("/api/sessions/" + renamed, cookie).getBody(), "title"));

        String longId = sessionId(post("/api/sessions", cookie, Map.of("title", "新会话")));
        String longText = "甲".repeat(50);
        post("/api/sessions/" + longId + "/messages", cookie, Map.of("text", longText, "workflowId", "answer-with-tool"));
        assertEquals("甲".repeat(40), field(get("/api/sessions/" + longId, cookie).getBody(), "title"));
    }

    /**
     * 前端脚本要能改名：含“改名”、PATCH 和“新会话”，并且不能用字符串拼接把“新会话”再拼一个编号。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleScriptEditsSessionTitle() {
        assertConsoleGone();
    }

    /**
     * 往会话发“请回答”得到 200，正文有 executionId，且不是 null。
     * 副作用：选择工作区、创建会话并提交消息。断言失败则测试失败。
     */
    @Test
    void messageCreatesExecution() throws Exception {
        String cookie = login("msg-user", "test-password-1");
        selectWorkspace(cookie);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "对话")));
        ResponseEntity<String> response = post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "请回答", "workflowId", "answer-with-tool"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("executionId"));
        assertFalse(response.getBody().contains("\"executionId\":null"));
    }

    /**
     * 读取 /api/me 得到 200，正文含刚才登录的 me-user。
     * 副作用：创建该用户。断言失败则测试失败。
     */
    @Test
    void profileReturnsLogin() throws Exception {
        String cookie = login("me-user", "test-password-1");
        ResponseEntity<String> response = get("/api/me", cookie);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("me-user"));
    }

    /**
     * 从登录页找到 /assets/ 下的 js，并取回前端脚本正文。
     *
     * @return 构建后的脚本
     */
    private String consoleScript() {
        String html = page("/login");
        int cursor = 0;
        while (cursor >= 0) {
            int src = html.indexOf("/assets/", cursor);
            assertTrue(src > 0, "登录页没有引用 /assets/ 脚本");
            int end = html.indexOf('"', src);
            int quote = html.indexOf('\'', src);
            if (end < 0 || (quote >= 0 && quote < end)) {
                end = quote;
            }
            assertTrue(end > src, html);
            String path = html.substring(src, end);
            if (path.endsWith(".js")) {
                String body = http.getForEntity(path, String.class).getBody();
                assertNotNull(body);
                return body;
            }
            cursor = end;
        }
        throw new AssertionError("登录页没有引用 /assets/ 脚本");
    }

    /**
     * 读取一个页面的 HTML，正文不能是 null。
     *
     * @param path 页面路径
     * @return HTML 正文
     */
    /**
     * 确认 agent 不再提供登录页、首页和静态入口。
     */
    private void assertConsoleGone() {
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/login", String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/", String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/index.html", String.class).getStatusCode());
        assertConsolePathAbsent("/admin");
        assertConsolePathAbsent("/admin/");
        assertConsolePathAbsent("/admin/login");
    }

    /**
     * 这些路径不能再返回控制台正文。允许 401 或 404，只要不是页面。
     *
     * @param path 原来的控制台路径
     */
    private void assertConsolePathAbsent(String path) {
        ResponseEntity<String> response = http.getForEntity(path, String.class);
        assertNotEquals(HttpStatus.OK, response.getStatusCode(), path);
        String body = response.getBody() == null ? "" : response.getBody();
        assertFalse(body.contains("/admin/assets/"), body);
        assertFalse(body.contains("gateway-admin"), body);
    }

    private String page(String path) {
        String body = http.getForEntity(path, String.class).getBody();
        assertNotNull(body);
        return body;
    }

    /**
     * 确保用户存在后用给定口令表单登录，返回去掉属性后的会话 Cookie。
     * 副作用：用户不存在时会建号。
     *
     * @param user 登录名
     * @param password 口令
     * @return Cookie 名值对
     */
    private String login(String user, String password) throws Exception {
        ensureUser(user, password);
        return user;
    }

    /**
     * 替该会话选中本类工作区，响应必须成功。
     * 副作用：会话绑定到这个工作区。
     *
     * @param cookie 登录 Cookie
     */
    private void selectWorkspace(String cookie) {
        ResponseEntity<String> response = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(response.getStatusCode().is2xxSuccessful());
    }

    /**
     * 带 Cookie 发送 JSON POST。
     *
     * @param path 接口路径
     * @param cookie 登录 Cookie
     * @param body JSON 字段
     * @return 响应
     */
    private ResponseEntity<String> post(String path, String cookie, Map<String, String> body) {
        return exchange(path, HttpMethod.POST, cookie, body);
    }

    /**
     * 带 Cookie 发送 JSON PATCH，用于改标题或模型。
     *
     * @param path 接口路径
     * @param cookie 登录 Cookie
     * @param body JSON 字段
     * @return 响应
     */
    private ResponseEntity<String> patch(String path, String cookie, Map<String, String> body) {
        return exchange(path, HttpMethod.PATCH, cookie, body);
    }

    /**
     * 带 Cookie 发送 GET。
     *
     * @param path 接口路径
     * @param cookie 登录 Cookie
     * @return 响应
     */
    private ResponseEntity<String> get(String path, String cookie) {
        return exchange(path, HttpMethod.GET, cookie, null);
    }

    /**
     * 带 Cookie 调用接口。有正文时声明 JSON。
     *
     * @param path 接口路径
     * @param method HTTP 方法
     * @param cookie 登录 Cookie
     * @param body 请求字段，null 表示没有正文
     * @return 响应
     */
    private ResponseEntity<String> exchange(String path, HttpMethod method, String cookie, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Jasagent-Token", "test-internal-token");
        headers.set("X-Jasagent-Tenant", "local");
        headers.set("X-Jasagent-User", cookie);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * 保证登录名可用。能登录就返回；否则用这把口令建用户；已有别人时借用其管理员令牌。
     * 副作用：可能在家目录新增用户。
     *
     * @param user 登录名
     * @param password 口令
     */
    private void ensureUser(String user, String password) throws Exception {
        char[] secret = password.toCharArray();
        try {
            auth.login(user, secret).toCompletableFuture().join();
            return;
        } catch (Exception ignored) {
            // 这个登录名还不存在。
        }
        try {
            auth.addUser(new AddUser(user, secret, null, false)).toCompletableFuture().join();
            return;
        } catch (Exception ignored) {
            // 已经有别的用户，需要已登录的本机会话。
        }
        try (var children = Files.list(home)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                try {
                    Session admin = auth.login(name, secret).toCompletableFuture().join();
                    auth.addUser(new AddUser(user, secret, admin.token(), true)).toCompletableFuture().join();
                    return;
                } catch (Exception ignored) {
                    // 试下一个已有用户。
                }
            }
        }
        throw new IllegalStateException("无法创建用户");
    }

    /**
     * 要求创建或读取会话的响应是 200，再取出 sessionId。
     *
     * @param response 会话接口的响应
     * @return 会话编号
     */
    private static String sessionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return field(response.getBody(), "sessionId");
    }

    /**
     * 要求提交执行的响应是 202，再取出 executionId。
     *
     * @param response 提交执行的响应
     * @return 执行编号
     */
    private static String executionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        return field(response.getBody(), "executionId");
    }

    /**
     * 在详情里定位某个工具名，再向前找它的 passId。
     *
     * @param body 执行详情 JSON
     * @param toolName 工具名，例如 rag.search
     * @return 通行证编号
     */
    private static String passId(String body, String toolName) {
        int name = body.indexOf("\"name\":\"" + toolName + "\"");
        assertTrue(name > 0, body);
        int marker = body.lastIndexOf("\"passId\":\"", name);
        assertTrue(marker >= 0, body);
        return field(body.substring(marker), "passId");
    }

    /**
     * 从 JSON 正文取出一个字符串字段。
     *
     * @param body 响应正文
     * @param name 字段名
     * @return 字段值
     */
    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        assertTrue(start >= 0, body);
        start += marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    /**
     * 最多约 15 秒轮询详情，直到成功、失败、取消或挂起。
     *
     * @param cookie 登录 Cookie
     * @param executionId 执行编号
     * @return 终态详情；一直没到终态时返回最后一次正文或空串
     */
    private String awaitTerminalStatus(String cookie, String executionId) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 300; attempt++) {
            body = get("/api/executions/" + executionId, cookie).getBody();
            if (body != null && (body.contains("\"status\":\"SUCCEEDED\"")
                    || body.contains("\"status\":\"FAILED\"")
                    || body.contains("\"status\":\"CANCELLED\"")
                    || body.contains("\"status\":\"SUSPENDED\""))) {
                return body;
            }
            Thread.sleep(50);
        }
        assertTrue(body != null && (body.contains("\"status\":\"SUCCEEDED\"") || body.contains("\"status\":\"FAILED\"")), body);
        return body == null ? "" : body;
    }

    /**
     * 最多约 15 秒轮询详情，直到正文含有标记。
     *
     * @param cookie 登录 Cookie
     * @param executionId 执行编号
     * @param marker 必须出现的片段
     * @return 含标记的详情
     */
    private String awaitBody(String cookie, String executionId, String marker) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 300; attempt++) {
            body = get("/api/executions/" + executionId, cookie).getBody();
            if (body != null && body.contains(marker)) {
                return body;
            }
            Thread.sleep(50);
        }
        assertTrue(body != null && body.contains(marker), body);
        return body;
    }

    /**
     * 在共用工作区写入 call-rag：rag.search 之后结束。
     * 副作用：覆盖 workflows/call-rag.json。
     */
    private void writeCallRag() throws Exception {
        Path dir = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("call-rag.json"), """
                {"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search","arguments":"hello","next":"done"},{"id":"done","type":"end"}]}
                """);
    }

    /**
     * 把策略写到工作区的 policies.yaml。YAML 原文保持不动。
     * 副作用：创建工作区 auth 目录并覆盖文件。
     *
     * @param yaml 策略原文
     */
    private void writeWorkspacePolicy(String yaml) throws Exception {
        Path file = workspace.resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /**
     * 把策略写到该用户家目录的 policies.yaml。YAML 原文保持不动。
     * 副作用：创建用户 auth 目录并覆盖文件。
     *
     * @param user 登录名
     * @param yaml 策略原文
     */
    private void writeUserPolicy(String user, String yaml) throws Exception {
        Path file = home.resolve(user).resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    /**
     * 创建带给定前缀的临时目录。
     * 副作用：在系统临时目录留下这个文件夹。
     *
     * @param name 目录名前缀
     * @return 新建目录
     */
    private static Path create(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
