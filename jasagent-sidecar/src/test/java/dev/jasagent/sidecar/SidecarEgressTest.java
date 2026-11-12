package dev.jasagent.sidecar;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用假的通行证服务和假的上游核对出站规则：大模型主机无证放行，其它主机无证拒绝，
 * 有效证才转发，同一张证第二次不转发。这些用例不监听 18721、18731、18732、18741 或 18751。
 */
class SidecarEgressTest {
    /**
     * 测试用的证明文。日志断言要确认这串不会出现。
     */
    private static final String TICKET = "ticket-do-not-log-9f3a";
    /**
     * 测试用的授权值。它应该跟到上游，但不应该出现在日志里。
     */
    private static final String BEARER = "Bearer test-token";

    private RecordingUpstream upstream;
    private ScriptedOffice office;
    private MockMvc mvc;

    /**
     * 每个用例都从新的上游和通行证替身开始，避免上一张证的核销影响下一例。
     */
    @BeforeEach
    void setUp() {
        upstream = new RecordingUpstream();
        office = new ScriptedOffice();
        EgressService service = new EgressService(LlmAllowlist.parse(""), office, upstream);
        mvc = MockMvcBuilders.standaloneSetup(new EgressController(service), new HealthController()).build();
    }

    /**
     * {@code /health} 不访问通行证，也不转发。带上证明文头也不会核销。
     */
    @Test
    void healthDoesNotForwardOrRedeem() throws Exception {
        mvc.perform(get(EgressPaths.HEALTH).header(EgressHeaders.TICKET, TICKET))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"up\"}"));
        assertEquals(0, upstream.calls);
        assertEquals(0, office.verifies);
        assertEquals(0, office.redeems);
    }

    /**
     * api.deepseek.com 和 api.x.ai 不带证也转发，并且不去问 agentpass。
     */
    @Test
    void modelHostWithoutTicketIsForwarded() throws Exception {
        office.throwOnCall = true;
        for (String url : List.of(
                "https://api.deepseek.com/chat/completions",
                "https://api.x.ai/v1/chat/completions",
                "https://API.X.AI/v1/models")) {
            mvc.perform(post(EgressPaths.FORWARD)
                            .header(EgressHeaders.URL, url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ping\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("llm-ok"));
        }
        assertEquals(3, upstream.calls);
        assertEquals(0, office.verifies);
        assertEquals(0, office.redeems);
        assertEquals("api.x.ai", upstream.lastUri.getHost());
        assertFalse(headerNamed(upstream.lastHeaders, EgressHeaders.TICKET));
    }

    /**
     * 额外名单里的主机同样无证转发。名单外的相似名字不行。
     */
    @Test
    void extraAllowlistHostWithoutTicketIsForwarded() throws Exception {
        EgressService service = new EgressService(LlmAllowlist.parse("hooks.example.com"), new ScriptedOffice(), upstream);
        EgressOutcome outcome = service.forward(new ForwardRequest("POST", "https://hooks.example.com/hook",
                Map.of(), "ping".getBytes(StandardCharsets.UTF_8), "", "", "", "", "", ""));
        assertTrue(outcome.forwarded());
        assertEquals(200, outcome.status());
        assertEquals(1, upstream.calls);
        assertEquals("hooks.example.com", upstream.lastUri.getHost());
    }

    /**
     * 其它主机不带证时拒绝，上游收不到请求，agentpass 也不会被问到。
     * {@code X-Forwarded-Host} 不能把名单外的地址伪装成大模型主机。
     */
    @Test
    void otherHostWithoutTicketIsRejected() throws Exception {
        office.throwOnCall = true;
        for (String url : List.of(
                "https://example.com/v1",
                "https://api.deepseek.com.evil.com/chat/completions",
                "https://evil.api.x.ai/v1")) {
            mvc.perform(post(EgressPaths.FORWARD)
                            .header(EgressHeaders.URL, url)
                            .header("X-Forwarded-Host", "api.deepseek.com")
                            .content("secret"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"admitted\":false,\"reason\":\"missing-ticket\"}"));
        }
        assertEquals(0, upstream.calls);
        assertEquals(0, office.verifies);
    }

    /**
     * 用户信息不能借大模型主机的名字混过放行，这种地址直接拒绝并且不转发。
     */
    @Test
    void userInfoDoesNotBypassAllowlist() throws Exception {
        office.throwOnCall = true;
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, "https://api.deepseek.com@evil.example/chat"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"admitted\":false,\"reason\":\"bad-target\"}"));
        assertEquals(0, upstream.calls);
    }

    /**
     * 有效证先校验再核销，然后才把正文送到上游。通行证头不到上游，授权头要到。
     * 日志里不能出现证明文和测试令牌。
     */
    @Test
    void validTicketIsForwarded() throws Exception {
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            mvc.perform(post(EgressPaths.FORWARD)
                            .header(EgressHeaders.URL, "https://example.com/hook?x=1")
                            .header(EgressHeaders.TICKET, TICKET)
                            .header(EgressHeaders.TENANT, "local")
                            .header(EgressHeaders.USER, "alice")
                            .header(EgressHeaders.SESSION, "session-1")
                            .header(EgressHeaders.EXECUTION, "exec-1")
                            .header(EgressHeaders.TOOL, "web.fetch")
                            .header("Authorization", BEARER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"n\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("llm-ok"));
            assertEquals(1, upstream.calls);
            assertEquals(1, office.verifies);
            assertEquals(1, office.redeems);
            assertEquals("https://example.com/hook?x=1", office.last.target());
            assertEquals("local", office.last.tenantId());
            assertEquals("alice", office.last.userId());
            assertEquals("session-1", office.last.sessionId());
            assertEquals("exec-1", office.last.executionId());
            assertEquals("web.fetch", office.last.toolName());
            assertEquals("{\"n\":1}", new String(upstream.lastBody, StandardCharsets.UTF_8));
            assertFalse(headerNamed(upstream.lastHeaders, EgressHeaders.TICKET));
            assertFalse(valuesContain(upstream.lastHeaders, TICKET));
            assertTrue(valuesContain(upstream.lastHeaders, BEARER));
            String text = logText(logs);
            assertFalse(text.contains(TICKET));
            assertFalse(text.contains("test-token"));
        } finally {
            detachLogs(logs);
        }
    }

    /**
     * 只有 {@code X-Jasagent-Pass} 时同样可以核销并转发。
     */
    @Test
    void passHeaderIsForwardedOnce() throws Exception {
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, "https://example.com/pass")
                        .header(EgressHeaders.PASS, TICKET)
                        .content("once"))
                .andExpect(status().isOk());
        assertEquals(1, upstream.calls);
        assertEquals(1, office.redeems);
        assertFalse(headerNamed(upstream.lastHeaders, EgressHeaders.PASS));
    }

    /**
     * 同一张证第二次校验会看到已用，上游只在第一次收到请求。
     */
    @Test
    void sameTicketIsRejectedTheSecondTime() throws Exception {
        String url = "https://example.com/once";
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, url)
                        .header(EgressHeaders.TICKET, TICKET)
                        .content("first"))
                .andExpect(status().isOk());
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, url)
                        .header(EgressHeaders.TICKET, TICKET)
                        .content("second"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"admitted\":false,\"reason\":\"used\"}"));
        assertEquals(1, upstream.calls);
        assertEquals("first", new String(upstream.lastBody, StandardCharsets.UTF_8));
        assertTrue(office.verifies >= 2);
    }

    /**
     * 伪证、过期和目标不匹配都不转发。
     */
    @Test
    void forgedExpiredAndMismatchedTicketsAreNotForwarded() throws Exception {
        office.preset = TicketVerdict.invalid(EgressReasons.PROOF_INVALID);
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, "https://example.com/forged")
                        .header(EgressHeaders.TICKET, "forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"admitted\":false,\"reason\":\"proof-invalid\"}"));
        office.preset = new TicketVerdict(true, false, true, true, true, true, true, true, false, "expired");
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, "https://example.com/expired")
                        .header(EgressHeaders.TICKET, "expired"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"admitted\":false,\"reason\":\"expired\"}"));
        office.preset = new TicketVerdict(true, false, false, true, false, true, true, true, false, "target-mismatch");
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, "https://example.com/mismatch")
                        .header(EgressHeaders.TICKET, "mismatch"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"admitted\":false,\"reason\":\"target-mismatch\"}"));
        assertEquals(0, upstream.calls);
    }

    /**
     * 大模型主机即使带了证也不核销，避免一次模型调用把别的证用掉。
     */
    @Test
    void modelHostDoesNotRedeemPresentedTicket() throws Exception {
        mvc.perform(post(EgressPaths.FORWARD)
                        .header(EgressHeaders.URL, "https://api.deepseek.com/chat/completions")
                        .header(EgressHeaders.TICKET, TICKET)
                        .content("model"))
                .andExpect(status().isOk());
        assertEquals(1, upstream.calls);
        assertEquals(0, office.verifies);
        assertEquals(0, office.redeems);
    }

    /**
     * 把出站服务的日志接到内存列表，用来确认没有写下证明文。
     *
     * @return 已经挂上的列表
     */
    private static ListAppender<ILoggingEvent> attachLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(EgressService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 取下日志列表，避免影响后面的用例。
     *
     * @param appender 先前挂上的列表
     */
    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(EgressService.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * 把记到的日志拼成一段文本。只用于检查某些串不存在。
     *
     * @param appender 日志列表
     * @return 全部格式化消息
     */
    private static String logText(ListAppender<ILoggingEvent> appender) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    /**
     * 判断头映射里有没有这个名字，忽略大小写。
     *
     * @param headers 送到上游的头
     * @param name 要找的头
     * @return 找到时为真
     */
    private static boolean headerNamed(Map<String, List<String>> headers, String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断任一取值是否包含给定文本。用来确认授权头还在、证明文不在。
     *
     * @param headers 送到上游的头
     * @param needle 要找的文本
     * @return 某个值包含它时为真
     */
    private static boolean valuesContain(Map<String, List<String>> headers, String needle) {
        for (List<String> values : headers.values()) {
            for (String value : values) {
                if (value != null && value.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 记下上游实际收到的请求，不拨号。
     */
    private static final class RecordingUpstream implements UpstreamClient {
        private int calls;
        private URI lastUri;
        private Map<String, List<String>> lastHeaders = Map.of();
        private byte[] lastBody = new byte[0];

        /**
         * 记下这一次并返回固定正文，表示转发已经发生。
         *
         * @param method 方法
         * @param uri 规范地址
         * @param headers 过滤后的头
         * @param body 正文
         * @return 200 和 {@code llm-ok}
         */
        @Override
        public UpstreamResponse exchange(String method, URI uri, Map<String, List<String>> headers, byte[] body) {
            calls++;
            lastUri = uri;
            lastHeaders = headers == null ? Map.of() : headers;
            lastBody = body == null ? new byte[0] : body;
            return UpstreamResponse.reached(200, "text/plain;charset=UTF-8", "llm-ok".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 按预设报告或一次性内存状态回答校验和核销，不连接 18741。
     */
    private static final class ScriptedOffice implements TicketOffice {
        private TicketVerdict preset;
        private final Map<String, Boolean> used = new LinkedHashMap<>();
        private int verifies;
        private int redeems;
        private TicketCall last;
        private boolean throwOnCall;

        /**
         * 返回预设报告，或按这张证有没有核销过现造一份。
         *
         * @param call 绑定字段
         * @param ticket 证明文
         * @return 校验报告
         */
        @Override
        public TicketVerdict verify(TicketCall call, String ticket) {
            if (throwOnCall) {
                throw new AssertionError("agentpass must not be called");
            }
            verifies++;
            last = call;
            if (preset != null) {
                return preset;
            }
            if (!TICKET.equals(ticket)) {
                return TicketVerdict.invalid(EgressReasons.PROOF_INVALID);
            }
            if (used.containsKey(ticket)) {
                return new TicketVerdict(true, true, false, true, true, true, true, true, false, EgressReasons.SERVER_REPLAY);
            }
            return TicketVerdict.ready();
        }

        /**
         * 第一次把证标成已用并成功；之后或预设失败时不成功。
         *
         * @param call 绑定字段
         * @param ticket 证明文
         * @return 核销结果
         */
        @Override
        public TicketRedeem redeem(TicketCall call, String ticket) {
            if (throwOnCall) {
                throw new AssertionError("agentpass must not be called");
            }
            redeems++;
            last = call;
            if (preset != null) {
                return new TicketRedeem(false, preset);
            }
            if (!TICKET.equals(ticket) || used.containsKey(ticket)) {
                return new TicketRedeem(false, new TicketVerdict(true, true, false, true, true, true, true, true, false,
                        EgressReasons.SERVER_REPLAY));
            }
            used.put(ticket, Boolean.TRUE);
            return new TicketRedeem(true, TicketVerdict.ready());
        }
    }
}
