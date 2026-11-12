package dev.jasagent.agentpass.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.agentpass.PolicyPassGate;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.pass.PassGate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 通过 HTTP 核对领证、越权拒绝、一次核销和重放。登录令牌只留在内存里，断言它没有出现在响应、流水和日志中。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OneTimePassHttpTest {
    private static final Path HOME = createHome();
    private static Session alice;
    private static Session bob;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PassStore store;
    @Autowired
    private AuditLog auditLog;
    @Autowired
    private PassGate passGate;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 把服务的家目录指到临时目录，避免读到开发者自己的用户策略。
     *
     * @param registry Spring 测试用来覆盖配置的注册表
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jasagent.home", HOME::toString);
    }

    /**
     * 建两个已登录用户，并给 alice 写上允许检索、拒绝写入的策略。口令在建用户方法里清掉。
     */
    @BeforeAll
    static void users() throws IOException {
        alice = TestUsers.register(HOME, "alice", null);
        TestUsers.writePolicy(HOME, "alice", """
                rules:
                  - tool: rag.search
                    decision: ALLOW
                  - tool: rag.write
                    decision: DENY
                """);
        bob = TestUsers.register(HOME, "bob", alice.token());
    }

    /**
     * 删掉临时家目录，避免口令摘要留在磁盘上。
     */
    @AfterAll
    static void cleanup() throws IOException {
        delete(HOME);
    }

    /**
     * 服务使用的仍是按策略裁决的进程内门，ALLOW、DENY、PENDING 没有被换掉。
     */
    @Test
    void keepsInProcessPolicyGate() {
        assertTrue(passGate instanceof PolicyPassGate);
    }

    /**
     * 已登录且策略允许时发证，证上绑定租户、用户、会话编号、执行、目标服务和工具，响应里没有登录令牌。
     */
    @Test
    void issuesPassForLoggedInUser() throws Exception {
        ListAppender<ILoggingEvent> logs = capture();
        try {
            JsonNode body = postIssue("alice", alice.sessionId(), "exec-issue", "http://127.0.0.1:18751", "rag.search", 201);
            assertTrue(body.get("issued").asBoolean());
            assertFalse(body.get("passId").asText().isBlank());
            assertTrue(body.get("ticket").asText().startsWith("v1."));
            assertEquals("local", body.get("tenantId").asText());
            assertEquals("alice", body.get("userId").asText());
            assertEquals(alice.sessionId(), body.get("sessionId").asText());
            assertEquals("exec-issue", body.get("executionId").asText());
            assertEquals("http://127.0.0.1:18751", body.get("target").asText());
            assertEquals("rag.search", body.get("toolName").asText());
            assertNoSecret(body.toString());
            assertNoSecret(logs);
            assertTrue(auditLog.all().stream().anyMatch(event -> AuditKind.ISSUE == event.kind()
                    && "exec-issue".equals(event.executionId())));
        } finally {
            detach(logs);
        }
    }

    /**
     * 人已经登录，但策略拒绝该工具时不发证，存储不增加，流水记拒绝。
     */
    @Test
    void deniesWhenPolicyDeniesTarget() throws Exception {
        int before = store.size();
        JsonNode body = postIssue("alice", alice.sessionId(), "exec-deny", "http://127.0.0.1:18751", "rag.write", 403);
        assertFalse(body.get("issued").asBoolean());
        assertEquals(PassReasons.POLICY_DENY, body.get("reason").asText());
        assertFalse(body.has("ticket"));
        assertEquals(before, store.size());
        assertNoSecret(body.toString());
        assertTrue(auditLog.all().stream().anyMatch(event -> AuditKind.DENY == event.kind()
                && PassReasons.POLICY_DENY.equals(event.reason())
                && "exec-deny".equals(event.executionId())));
    }

    /**
     * 用别人的会话来给自己领证时拒绝，不发证。
     */
    @Test
    void deniesWhenSessionBelongsToAnotherUser() throws Exception {
        int before = store.size();
        JsonNode body = postIssue("alice", bob.sessionId(), "exec-cross", "http://127.0.0.1:18751", "rag.search", 403);
        assertFalse(body.get("issued").asBoolean());
        assertEquals(PassReasons.SESSION_MISMATCH, body.get("reason").asText());
        assertEquals(before, store.size());
        assertNoSecret(body.toString());
    }

    /**
     * 策略没有命中时进程内门是等待审批。一次性证不发，也不把等待改成允许。
     */
    @Test
    void doesNotIssueWhileApprovalIsPending() throws Exception {
        int before = store.size();
        JsonNode body = postIssue("alice", alice.sessionId(), "exec-pending", "http://127.0.0.1:18751", "rag.hold", 403);
        assertEquals(PassReasons.APPROVAL_REQUIRED, body.get("reason").asText());
        assertEquals(before, store.size());
    }

    /**
     * 校验只报告证明、未使用、未过期和用户目标匹配，不把证核销掉。随后才能核销一次，再核销被当成重放。
     */
    @Test
    void redeemsOnceAndRejectsReplay() throws Exception {
        JsonNode issued = postIssue("alice", alice.sessionId(), "exec-once", "http://127.0.0.1:18751", "rag.search", 201);
        String passId = issued.get("passId").asText();
        JsonNode verified = postProof(TicketPaths.VERIFY, issued, "alice", "rag.search", 200);
        assertTrue(verified.get("proofValid").asBoolean());
        assertFalse(verified.get("used").asBoolean());
        assertFalse(verified.get("expired").asBoolean());
        assertTrue(verified.get("userMatch").asBoolean());
        assertTrue(verified.get("targetMatch").asBoolean());
        assertTrue(verified.get("accepted").asBoolean());
        assertEquals(1, count(passId));

        JsonNode redeemed = postProof(TicketPaths.REDEEM, issued, "alice", "rag.search", 200);
        assertTrue(redeemed.get("redeemed").asBoolean());
        assertTrue(redeemed.get("used").asBoolean());

        JsonNode again = postProof(TicketPaths.VERIFY, issued, "alice", "rag.search", 200);
        assertTrue(again.get("used").asBoolean());
        assertFalse(again.get("accepted").asBoolean());

        JsonNode replay = postProof(TicketPaths.REDEEM, issued, "alice", "rag.search", 409);
        assertFalse(replay.get("redeemed").asBoolean());
        assertEquals(PassReasons.REPLAY, replay.get("reason").asText());
        List<AuditKind> kinds = auditLog.all().stream()
                .filter(event -> passId.equals(event.passId()))
                .map(AuditEvent::kind)
                .toList();
        assertEquals(List.of(AuditKind.ISSUE, AuditKind.REDEEM, AuditKind.REPLAY), kinds);
        assertNoSecret(replay.toString());
    }

    /**
     * 改过的证明文不能通过校验，也不会把原来那张证核销掉。
     */
    @Test
    void rejectsForgedProofWithoutConsumingOriginal() throws Exception {
        JsonNode issued = postIssue("alice", alice.sessionId(), "exec-forge", "http://127.0.0.1:18751", "rag.search", 201);
        String forged = flip(issued.get("ticket").asText());
        JsonNode verified = postRawProof(TicketPaths.VERIFY, forged, issued, "alice", "rag.search", 200);
        assertFalse(verified.get("proofValid").asBoolean());
        assertFalse(verified.get("accepted").asBoolean());
        JsonNode redeemed = postProof(TicketPaths.REDEEM, issued, "alice", "rag.search", 200);
        assertTrue(redeemed.get("redeemed").asBoolean());
    }

    /**
     * 用户对不上时校验报告标明不匹配，并且不核销。随后用原来的用户仍可核销。
     */
    @Test
    void reportsUserMismatchWithoutRedeeming() throws Exception {
        JsonNode issued = postIssue("alice", alice.sessionId(), "exec-user", "http://127.0.0.1:18751", "rag.search", 201);
        JsonNode mismatch = postProof(TicketPaths.VERIFY, issued, "bob", "rag.search", 200);
        assertTrue(mismatch.get("proofValid").asBoolean());
        assertFalse(mismatch.get("userMatch").asBoolean());
        assertFalse(mismatch.get("used").asBoolean());
        assertFalse(mismatch.get("accepted").asBoolean());
        JsonNode redeemed = postProof(TicketPaths.REDEEM, issued, "alice", "rag.search", 200);
        assertTrue(redeemed.get("redeemed").asBoolean());
    }

    /**
     * 统计某张证在流水里出现的次数，用来确认只读校验没有多写一条。
     *
     * @param passId 通行证编号
     * @return 该编号的流水条数
     */
    private int count(String passId) {
        return (int) auditLog.all().stream().filter(event -> passId.equals(event.passId())).count();
    }

    /**
     * 提交领证并读取 JSON。断言状态码，同时确认正文没有登录令牌。
     *
     * @param user 登录名
     * @param sessionId 会话编号，不是登录令牌
     * @param executionId 执行编号
     * @param target 目标地址或名字
     * @param tool 工具名
     * @param status 期望的 HTTP 状态码
     * @return 响应 JSON
     */
    private JsonNode postIssue(String user, String sessionId, String executionId, String target, String tool, int status)
            throws Exception {
        MvcResult result = mockMvc.perform(post(TicketPaths.ISSUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "tenantId", "local",
                                "userId", user,
                                "sessionId", sessionId,
                                "executionId", executionId,
                                "target", target,
                                "toolName", tool))))
                .andReturn();
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status, result.getResponse().getStatus(), text);
        assertNoSecret(text);
        return mapper.readTree(text);
    }

    /**
     * 用签发结果里的绑定字段提交校验或核销，用户和工具可以换成不匹配的值。
     *
     * @param path 校验或核销路径
     * @param issued 领证响应
     * @param user 声称的登录名
     * @param tool 声称的工具名
     * @param status 期望的 HTTP 状态码
     * @return 响应 JSON
     */
    private JsonNode postProof(String path, JsonNode issued, String user, String tool, int status) throws Exception {
        return postRawProof(path, issued.get("ticket").asText(), issued, user, tool, status);
    }

    /**
     * 用给定证明文提交校验或核销。证明文可以是签发原文，也可以是改过的伪证。
     *
     * @param path 接口路径
     * @param pass 证明文
     * @param issued 领证响应，用来提供绑定字段
     * @param user 声称的登录名
     * @param tool 声称的工具名
     * @param status 期望的 HTTP 状态码
     * @return 响应 JSON
     */
    private JsonNode postRawProof(String path, String pass, JsonNode issued, String user, String tool, int status)
            throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "ticket", pass,
                                "tenantId", issued.get("tenantId").asText(),
                                "userId", user,
                                "sessionId", issued.get("sessionId").asText(),
                                "executionId", issued.get("executionId").asText(),
                                "target", issued.get("target").asText(),
                                "toolName", tool))))
                .andReturn();
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status, result.getResponse().getStatus(), text);
        assertNoSecret(text);
        return mapper.readTree(text);
    }

    /**
     * 挂上内存日志，用来确认这次操作没有把凭证写进日志。
     *
     * @return 已经开始接收的追加器
     */
    private static ListAppender<ILoggingEvent> capture() {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.jasagent.agentpass.server");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 取下内存日志，避免影响别的测试。
     *
     * @param appender 之前挂上的追加器
     */
    private static void detach(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.jasagent.agentpass.server");
        logger.detachAppender(appender);
    }

    /**
     * 确认日志正文没有登录令牌，也没有整段证明文。
     *
     * @param logs 这次操作期间收集到的日志
     */
    private void assertNoSecret(ListAppender<ILoggingEvent> logs) throws Exception {
        String audit = mockMvc.perform(get(TicketPaths.AUDIT)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNoSecret(audit);
        for (ILoggingEvent event : logs.list) {
            assertNoSecret(event.getFormattedMessage());
        }
    }

    /**
     * 确认一段文本没有带上两个用户的登录令牌。
     *
     * @param text 响应、流水或日志
     */
    private static void assertNoSecret(String text) {
        assertFalse(text.contains(alice.token()));
        assertFalse(text.contains(bob.token()));
    }

    /**
     * 改掉证明文最后一个字符，得到一张签名对不上的伪证。
     *
     * @param pass 原始证明文
     * @return 被改过的证明文
     */
    private static String flip(String pass) {
        char last = pass.charAt(pass.length() - 1);
        char flipped = last == 'a' ? 'b' : 'a';
        return pass.substring(0, pass.length() - 1) + flipped;
    }

    /**
     * 创建临时家目录。放在类加载时完成，这样 Spring 覆盖配置时目录已经存在。
     *
     * @return 临时家目录
     */
    private static Path createHome() {
        try {
            return Files.createTempDirectory("jasagent-agentpass-http-");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 递归删除临时目录。
     *
     * @param root 要删除的目录
     */
    private static void delete(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
