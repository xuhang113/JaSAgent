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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住会话页上的模型选择、时间线顺序、纠正入口、右栏和思考折叠，以及上下文压缩阈值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleSessionPageTest {
    /**
     * 本类共用的临时家目录。类加载时创建，规则和技能写在用户目录下。
     */
    private static final Path home = create("jasagent-session-home");
    /**
     * 本类共用的临时工作区。类加载时创建，show-support 工作流写在这里。
     */
    private static final Path workspace = create("jasagent-session-ws");
    /**
     * 本类测试用户共用的口令，只用于登录夹具。
     */
    private static final String PASSWORD = "test-password-1";
    /**
     * 发给 show-support 的用户原话，时间线里的 human 消息必须带上它。
     */
    private static final String USER_TEXT = "请给出时间";
    /**
     * 写进用户规则说明的句子，系统消息里必须能找到它。
     */
    private static final String RULE = "遵守事实";
    /**
     * 写进技能说明的句子，系统消息里必须能找到它。
     */
    private static final String SKILL = "查找公开资料";

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
    }

    /**
     * 不带 Cookie 读取模型列表必须是 401，正文含 AUTH。
     * 副作用：请求 /api/models。断言失败则测试失败。
     */
    @Test
    void anonymousModelListIs401() {
        ResponseEntity<String> response = http.getForEntity("/api/models", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    /**
     * 前端脚本要有模型选择和当前会话模型的标记，样式里要有 model-select、深色 #1b1b18 和 8px。
     * 副作用：下载脚本和样式。断言失败则测试失败。
     */
    @Test
    void consoleShowsModelSelect() {
        String script = consoleScript();
        assertTrue(script.contains("data-model-select"), scriptSnippet(script, "model"));
        assertTrue(script.contains("data-session-model"), script);
        String css = consoleCss();
        assertTrue(css.contains("model-select"), css);
        assertTrue(css.contains("#1b1b18"), css);
        assertTrue(css.contains("8px"), css);
    }

    /**
     * 新建的两个会话都记下 deepseek-flash 和供应商 deepseek，再次读取第一个会话仍然是这组值。
     * 副作用：创建用户和两条会话。断言失败则测试失败。
     */
    @Test
    void sessionRemembersDeepseekFlash() throws Exception {
        String cookie = login("model-user");
        String first = sessionId(post("/api/sessions", cookie, Map.of("title", "甲")));
        String second = sessionId(post("/api/sessions", cookie, Map.of("title", "乙")));
        String firstBody = get("/api/sessions/" + first, cookie).getBody();
        String secondBody = get("/api/sessions/" + second, cookie).getBody();
        assertTrue(firstBody.contains("\"model\":\"deepseek-flash\""), firstBody);
        assertTrue(firstBody.contains("\"modelProvider\":\"deepseek\""), firstBody);
        assertTrue(secondBody.contains("\"model\":\"deepseek-flash\""), secondBody);
        assertTrue(get("/api/sessions/" + first, cookie).getBody().contains("\"model\":\"deepseek-flash\""));
    }

    /**
     * 把会话改成不存在的模型得到 400 和 WORKFLOW，会话上的模型仍是 deepseek-flash。
     * 副作用：创建会话并发送一次无效 PATCH。断言失败则测试失败。
     */
    @Test
    void unknownModelIsRejected() throws Exception {
        String cookie = login("model-reject");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "拒绝")));
        ResponseEntity<String> response = exchange(
                "/api/sessions/" + sessionId,
                HttpMethod.PATCH,
                cookie,
                Map.of("model", "not-a-real-model", "modelProvider", "deepseek"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("WORKFLOW"), response.getBody());
        assertTrue(get("/api/sessions/" + sessionId, cookie).getBody().contains("\"model\":\"deepseek-flash\""));
    }

    /**
     * 模型列表的 defaultModel 是 deepseek-flash。Gateway 三项环境变量没齐时，列表里不能出现 ai-gateway。
     * 副作用：创建用户并读取 /api/models。断言失败则测试失败。
     */
    @Test
    void modelListUsesDeepseekDefault() throws Exception {
        String cookie = login("model-list");
        String body = get("/api/models", cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"defaultModel\":\"deepseek-flash\""), body);
        if (System.getenv("JASAGENT_GATEWAY_BASE_URL") == null
                || System.getenv("JASAGENT_GATEWAY_API_KEY") == null
                || System.getenv("JASAGENT_GATEWAY_MODEL") == null) {
            assertFalse(body.contains("ai-gateway"), body);
        }
    }

    /**
     * 不带 Cookie 列会话必须是 401。正文有 errorCode，不能出现 Exception 或堆栈里的 at。
     * 副作用：请求 /api/sessions。断言失败则测试失败。
     */
    @Test
    void anonymousSessionListIs401() {
        ResponseEntity<String> response = http.getForEntity("/api/sessions", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("errorCode"));
        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("\tat "));
    }

    /**
     * 前端脚本把会话当成主区，并保留“子 agent”的说法。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleShowsSessionAsMain() {
        String script = consoleScript();
        assertTrue(script.contains("session-main"), scriptSnippet(script, "session"));
        assertTrue(script.contains("子 agent"));
    }

    /**
     * 纠正入口使用“写下纠正”和“追加纠正”，不再出现 harness note 或“纠正 note”这种内部说法。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleShowsCorrectionEntry() {
        String script = consoleScript();
        assertTrue(script.contains("写下纠正"), scriptSnippet(script, "纠正"));
        assertTrue(script.contains("追加纠正"), script);
        assertFalse(script.contains("harness note"), script);
        assertFalse(script.contains("纠正 note"), script);
    }

    /**
     * 右栏默认打开：有 panels-open 为 true、console-layout、right-rail 和 trace-turn。不能回到旧的 turn-trace、“执行过程”或默认隐藏。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void rightRailStaysOpen() {
        String script = consoleScript();
        assertTrue(script.contains("data-panels-open\":\"true\""), scriptSnippet(script, "data-panels-open"));
        assertTrue(script.contains("console-layout"), script);
        assertTrue(script.contains("right-rail"), script);
        assertTrue(script.contains("trace-turn"), script);
        assertFalse(script.contains("turn-trace"), script);
        assertFalse(script.contains("执行过程"), script);
        assertFalse(script.contains("data-default-panels\":\"hidden\""), script);
        assertFalse(script.contains("data-panels-open\":\"false\""), script);
    }

    /**
     * 前端脚本含 data-resize，分栏可以被拖动。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void panelsCanResize() {
        assertTrue(consoleScript().contains("data-resize"));
    }

    /**
     * 向会话发一条 answer-with-tool 后，会话详情里要有角色为 SUB 的代理。
     * 副作用：选择工作区、创建会话并提交消息。断言失败则测试失败。
     */
    @Test
    void sessionListsSubAgent() throws Exception {
        String cookie = login("agent-user");
        selectWorkspace(cookie);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "子代理")));
        post("/api/sessions/" + sessionId + "/messages", cookie, Map.of("text", "调度一次", "workflowId", "answer-with-tool"));
        String body = get("/api/sessions/" + sessionId, cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"role\":\"SUB\""), body);
    }

    /**
     * 写下“不要编造数字”后，会话详情里要有这句原文。
     * 副作用：创建会话和一条纠正。断言失败则测试失败。
     */
    @Test
    void sessionShowsCorrection() throws Exception {
        String cookie = login("note-user");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "纠正")));
        post("/api/sessions/" + sessionId + "/corrections", cookie, Map.of("text", "不要编造数字"));
        String body = get("/api/sessions/" + sessionId, cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("不要编造数字"), body);
    }

    /**
     * 乙读取甲的会话必须是 401，正文含 AUTH。
     * 副作用：创建两名用户和甲的会话。断言失败则测试失败。
     */
    @Test
    void otherUserCannotReadSession() throws Exception {
        String alpha = login("own-a");
        String beta = login("own-b");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的会话")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId, beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    /**
     * show-support 的时间线里，system 消息含有规则说明“遵守事实”。
     * 副作用：写规则、技能和工作流，并跑到失败终态。断言失败则测试失败。
     */
    @Test
    void timelineSystemContainsRule() throws Exception {
        String body = supportTimeline("rule-user");
        int system = body.indexOf("\"type\":\"system\"");
        assertTrue(system >= 0, body);
        int next = body.indexOf("\"type\":\"", system + 1);
        String message = next < 0 ? body.substring(system) : body.substring(system, next);
        assertTrue(message.contains(RULE), message);
    }

    /**
     * 同一条时间线的 system 消息含有技能说明“查找公开资料”。
     * 副作用：写规则、技能和工作流，并跑到失败终态。断言失败则测试失败。
     */
    @Test
    void timelineSystemContainsSkill() throws Exception {
        String body = supportTimeline("skill-user");
        int system = body.indexOf("\"type\":\"system\"");
        assertTrue(system >= 0, body);
        int next = body.indexOf("\"type\":\"", system + 1);
        String message = next < 0 ? body.substring(system) : body.substring(system, next);
        assertTrue(message.contains(SKILL), message);
    }

    /**
     * 时间线含有 human，正文里有用户原话“请给出时间”。
     * 副作用：准备支持材料并提交这条消息。断言失败则测试失败。
     */
    @Test
    void timelineHasHuman() throws Exception {
        String body = supportTimeline("human-user");
        assertTrue(body.contains("\"type\":\"human\""), body);
        assertTrue(body.contains(USER_TEXT), body);
    }

    /**
     * 没有模型时，时间线里的 ai 是“模型未配置”，不能假装已经收到请求。
     * 副作用：准备支持材料并等到执行失败。断言失败则测试失败。
     */
    @Test
    void timelineHasAiReply() throws Exception {
        String body = supportTimeline("ai-user");
        assertTrue(body.contains("\"type\":\"ai\""), body);
        assertTrue(body.contains("模型未配置"), body);
        assertFalse(body.contains("已收到"), body);
    }

    /**
     * 时间线里 clock 是 tool，它的 content 像一条带日期和 T 的时间。
     * 副作用：准备支持材料并等到执行失败。断言失败则测试失败。
     */
    @Test
    void timelineHasClockTool() throws Exception {
        String body = supportTimeline("tool-user");
        int name = body.indexOf("\"name\":\"clock\"");
        assertTrue(name > 0, body);
        int type = body.lastIndexOf("\"type\":\"tool\"", name);
        assertTrue(type >= 0 && type < name, body);
        int content = body.indexOf("\"content\":\"", name);
        assertTrue(content > name, body);
        String value = body.substring(content + "\"content\":\"".length(), Math.min(body.length(), content + 48));
        assertTrue(value.length() >= 11 && value.charAt(4) == '-' && value.contains("T"), value);
    }

    /**
     * 时间线顺序是 system、human、tool、ai，而不是把回复放在工具前面。
     * 副作用：准备支持材料并读取时间线。断言失败则测试失败。
     */
    @Test
    void timelineOrderIsLangGraph() throws Exception {
        String body = supportTimeline("order-user");
        int system = body.indexOf("\"type\":\"system\"");
        int human = body.indexOf("\"type\":\"human\"");
        int tool = body.indexOf("\"type\":\"tool\"");
        int ai = body.indexOf("\"type\":\"ai\"");
        assertTrue(system >= 0 && system < human && human < tool && tool < ai, body);
    }

    /**
     * 乙读取甲的时间线必须是 401，正文含 AUTH。
     * 副作用：创建两名用户和甲的会话。断言失败则测试失败。
     */
    @Test
    void otherUserCannotReadTimeline() throws Exception {
        String alpha = login("time-a");
        String beta = login("time-b");
        String sessionId = sessionId(post("/api/sessions", alpha, Map.of("title", "甲的时间线")));
        ResponseEntity<String> response = get("/api/sessions/" + sessionId + "/timeline", beta);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("AUTH"));
    }

    /**
     * application.properties 里的 jasagent.context.compress-at 默认是 0.85。
     * 副作用：只读配置文件。断言失败则测试失败。
     */
    @Test
    void compressAtPropertyDefaultsTo085() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("jasagent.context.compress-at=0.85"), props);
    }

    /**
     * 会话上下文接口返回的 compressAt 是 0.85，和配置一致。
     * 副作用：创建用户和会话并读取上下文。断言失败则测试失败。
     */
    @Test
    void contextReportsCompressAt() throws Exception {
        String cookie = login("ratio-user");
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "阈值")));
        String body = get("/api/sessions/" + sessionId + "/context", cookie).getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"compressAt\":0.85"), body);
    }

    /**
     * 前端脚本仍有“中止”，执行可以被停下。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleKeepsInterrupt() {
        assertTrue(consoleScript().contains("中止"));
    }

    /**
     * 前端脚本仍有“恢复”，挂起的执行可以继续。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleKeepsResume() {
        assertTrue(consoleScript().contains("恢复"));
    }

    /**
     * 前端脚本仍提到 SSE。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleKeepsSse() {
        assertTrue(consoleScript().contains("SSE"));
    }

    /**
     * 前端脚本仍有 /skill: 这种技能引用写法。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleKeepsSkillRef() {
        assertTrue(consoleScript().contains("/skill:"));
    }

    /**
     * 前端脚本仍有“通行证”。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleKeepsPasses() {
        assertTrue(consoleScript().contains("通行证"));
    }

    /**
     * 样式里的 .messages 不再写死 180px 高度，消息区可以随内容伸缩。
     * 副作用：下载样式。断言失败则测试失败。
     */
    @Test
    void messagesRuleIsCompact() {
        String css = consoleCss();
        int at = css.indexOf(".messages{");
        assertTrue(at >= 0, "样式里没有 .messages");
        int end = css.indexOf('}', at);
        assertTrue(end > at);
        String rule = css.substring(at, end);
        assertFalse(rule.contains("180px"), rule);
    }

    /**
     * 前端脚本声明 data-timeline 为 langgraph，时间线按这个顺序渲染。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleUsesLangGraphTimeline() {
        assertTrue(consoleScript().contains("data-timeline\":\"langgraph\""));
    }

    /**
     * 前端脚本不再出现“当前执行”这块常驻区域。
     * 副作用：下载前端脚本。断言失败则测试失败。
     */
    @Test
    void consoleDropsAlwaysOnExecution() {
        assertFalse(consoleScript().contains("当前执行"));
    }

    /**
     * 思考显示在回复上方：脚本含“思考”和 thinking，样式是 12px 的 #98a2b3。配置里 jasagent.deepseek.thinking 为 true。
     * 副作用：下载脚本和样式，并读取配置。断言失败则测试失败。
     */
    @Test
    void consoleFoldsThinkingAboveReply() {
        String script = consoleScript();
        assertTrue(script.contains("思考"), scriptSnippet(script, "思考"));
        assertTrue(script.contains("thinking"), scriptSnippet(script, "thinking"));
        String css = consoleCss();
        int at = css.indexOf(".thinking{");
        assertTrue(at >= 0, css);
        int end = css.indexOf('}', at);
        assertTrue(end > at, css);
        String rule = css.substring(at, end);
        assertTrue(rule.contains("#98a2b3"), rule);
        assertTrue(rule.contains("12px"), rule);
        String props = readProps();
        assertTrue(props.contains("jasagent.deepseek.thinking=true"), props);
    }

    /**
     * 读取应用配置。读失败时抛出非法状态异常。
     *
     * @return application.properties 的全文
     */
    private static String readProps() {
        try {
            return Files.readString(Path.of("src/main/resources/application.properties"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 为该用户写规则和技能，在工作区放上 show-support，发送“请给出时间”，等到失败后返回时间线正文。规则和技能原文不在这条注释里改写。
     * 副作用：写规则、技能、工作流，并创建会话和执行。
     *
     * @param user 登录名
     * @return 时间线 JSON
     */
    private String supportTimeline(String user) throws Exception {
        String cookie = login(user);
        selectWorkspace(cookie);
        Path rules = home.resolve(user).resolve("jasagent").resolve("rules");
        Files.createDirectories(rules);
        Files.writeString(rules.resolve("base.md"), """
                ---
                id: base
                description: %s
                ---
                只写能核对的话
                """.formatted(RULE));
        Path skill = home.resolve(user).resolve("jasagent").resolve("skills").resolve("research");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
                ---
                name: research
                description: %s
                ---
                先查再答
                """.formatted(SKILL));
        Path workflows = workspace.resolve("jasagent").resolve("workflows");
        Files.createDirectories(workflows);
        Files.writeString(workflows.resolve("show-support.json"), """
                {"id":"show-support","nodes":[{"id":"now","type":"tool","tool":"clock"},{"id":"act","type":"react","maxSteps":2,"tools":[],"grounding":false},{"id":"done","type":"end"}]}
                """);
        String sessionId = sessionId(post("/api/sessions", cookie, Map.of("title", "支持")));
        ResponseEntity<String> sent = post("/api/sessions/" + sessionId + "/messages", cookie, Map.of(
                "text", USER_TEXT,
                "workflowId", "show-support"));
        assertEquals(HttpStatus.OK, sent.getStatusCode(), sent.getBody());
        String executionId = field(sent.getBody(), "executionId");
        awaitBody(cookie, executionId, "\"status\":\"FAILED\"");
        ResponseEntity<String> timeline = get("/api/sessions/" + sessionId + "/timeline?executionId=" + executionId, cookie);
        assertEquals(HttpStatus.OK, timeline.getStatusCode(), timeline.getBody());
        assertNotNull(timeline.getBody());
        return timeline.getBody();
    }

    /**
     * 从登录页取出构建后的 js。
     *
     * @return 前端脚本正文
     */
    private String consoleScript() {
        return asset(page("/login"), ".js");
    }

    /**
     * 从登录页取出构建后的 css。
     *
     * @return 样式正文
     */
    private String consoleCss() {
        return asset(page("/login"), ".css");
    }

    /**
     * 在 HTML 里找 /assets/ 下以给定后缀结尾的地址，再取回它的正文。
     *
     * @param html 登录页 HTML
     * @param suffix 资源后缀，例如 .js 或 .css
     * @return 资源正文
     */
    private String asset(String html, String suffix) {
        int cursor = 0;
        while (cursor >= 0) {
            int src = html.indexOf("/assets/", cursor);
            assertTrue(src > 0, html);
            int end = html.indexOf('"', src);
            int quote = html.indexOf('\'', src);
            if (end < 0 || (quote >= 0 && quote < end)) {
                end = quote;
            }
            assertTrue(end > src, html);
            String path = html.substring(src, end);
            if (path.endsWith(suffix)) {
                String body = http.getForEntity(path, String.class).getBody();
                assertNotNull(body);
                return body;
            }
            cursor = end;
        }
        throw new AssertionError("登录页没有 " + suffix);
    }

    /**
     * 读取页面 HTML，正文不能是 null。
     *
     * @param path 页面路径
     * @return HTML 正文
     */
    private String page(String path) {
        String body = http.getForEntity(path, String.class).getBody();
        assertNotNull(body);
        return body;
    }

    /**
     * 确保用户存在后用本类口令登录，返回去掉属性后的会话 Cookie。
     * 副作用：用户不存在时会建号。
     *
     * @param user 登录名
     * @return Cookie 名值对
     */
    private String login(String user) throws Exception {
        ensureUser(user);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = http.exchange(
                "/login",
                HttpMethod.POST,
                new HttpEntity<>("user=" + user + "&password=" + PASSWORD, headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0];
    }

    /**
     * 替该会话选中本类工作区，响应必须成功。
     * 副作用：会话绑定到这个工作区。
     *
     * @param cookie 登录 Cookie
     */
    private void selectWorkspace(String cookie) {
        ResponseEntity<String> response = post("/api/workspace", cookie, Map.of("path", workspace.toString()));
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
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
        headers.add(HttpHeaders.COOKIE, cookie);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * 保证登录名可用。能登录就返回；否则建用户；已有别人时借用其管理员令牌。
     * 副作用：可能在家目录新增用户。
     *
     * @param user 登录名
     */
    private void ensureUser(String user) throws Exception {
        char[] secret = PASSWORD.toCharArray();
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
     * 最多约 3 秒轮询执行详情，直到正文含有标记。
     *
     * @param cookie 登录 Cookie
     * @param executionId 执行编号
     * @param marker 必须出现的片段
     * @return 含标记的详情
     */
    private String awaitBody(String cookie, String executionId, String marker) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 80; attempt++) {
            body = get("/api/executions/" + executionId, cookie).getBody();
            if (body != null && body.contains(marker)) {
                return body;
            }
            Thread.sleep(40);
        }
        assertTrue(body != null && body.contains(marker), body);
        return body;
    }

    /**
     * 要求会话响应是 200，再取出 sessionId。
     *
     * @param response 会话接口的响应
     * @return 会话编号
     */
    private static String sessionId(ResponseEntity<String> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "sessionId");
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
     * 截出脚本里关键字附近的一小段，断言失败时用来看上下文。找不到时返回说明文字。
     *
     * @param script 前端脚本
     * @param needle 要定位的文字
     * @return 关键字前后的片段，或找不到时的说明
     */
    private static String scriptSnippet(String script, String needle) {
        int at = script.indexOf(needle);
        if (at < 0) {
            return "没有 " + needle;
        }
        return script.substring(Math.max(0, at - 40), Math.min(script.length(), at + 80));
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
