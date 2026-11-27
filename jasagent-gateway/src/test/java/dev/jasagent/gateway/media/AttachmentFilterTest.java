package dev.jasagent.gateway.media;

import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.auth.AccountRoles;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import dev.jasagent.persist.ExecutionDatabase;
import dev.jasagent.persist.SessionAttachmentLedger;
import dev.jasagent.persist.SessionLedger;
import dev.jasagent.persist.UragMysql;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过滤器自己结束附件请求。点段跳出后的 servlet 路径也不能继续转发。
 */
class AttachmentFilterTest {
    /**
     * 没有会话时 401，并且不调用后续链条。
     */
    @Test
    void missingSessionDoesNotContinue() throws Exception {
        Harness harness = harness();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/sessions/s1/attachments");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            harness.raw.doFilter(request, response, (req, res) -> called.set(true));
            assertFalse(called.get());
            assertEquals(401, response.getStatus());
            assertTrue(response.getContentAsString().contains("\"errorCode\":\"AUTH\""));
        } finally {
            harness.close();
        }
    }

    /**
     * 原始 URI 踩进附件前缀后再跳出时，即使 servlet 路径已经变成普通消息，也不转发。
     */
    @Test
    void dotDotDoesNotReachTheChain() throws Exception {
        Harness harness = harness();
        try {
            String sessionId = "s" + UUID.randomUUID().toString().replace("-", "");
            sign(harness, sessionId);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/" + sessionId + "/messages");
            request.setRequestURI("/api/sessions/" + sessionId + "/attachments/../messages");
            request.setServletPath("/api/sessions/" + sessionId + "/messages");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            FilterChain chain = (req, res) -> called.set(true);
            harness.filter.doFilter(request, response, chain);
            assertFalse(called.get());
            assertEquals(404, response.getStatus());
            assertTrue(response.getContentAsString().contains("\"errorCode\":\"NOT_FOUND\""));
            assertFalse(response.getContentAsString().contains(sessionId + "/messages"));
        } finally {
            harness.close();
        }
    }

    /**
     * 大小写不同的集合仍由过滤器列出，普通会话路径则继续向后。
     */
    @Test
    void caseVariantIsListedAndPlainSessionContinues() throws Exception {
        Harness harness = harness();
        try {
            String sessionId = "s" + UUID.randomUUID().toString().replace("-", "");
            sign(harness, sessionId);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/API/sessions/" + sessionId + "/ATTACHMENTS");
            request.setRequestURI("/API/sessions/" + sessionId + "/ATTACHMENTS");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean called = new AtomicBoolean();
            harness.filter.doFilter(request, response, (req, res) -> called.set(true));
            assertFalse(called.get());
            assertEquals(200, response.getStatus());
            assertEquals("[]", response.getContentAsString());
            MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/api/sessions/" + sessionId);
            AtomicBoolean forwarded = new AtomicBoolean();
            harness.filter.doFilter(plain, new MockHttpServletResponse(), (req, res) -> forwarded.set(true));
            assertTrue(forwarded.get());
        } finally {
            harness.close();
        }
    }

    /**
     * 放上当前用户，并在账本里写下这场会话。
     *
     * @param harness 测试账本
     * @param sessionId 会话编号
     */
    private static void sign(Harness harness, String sessionId) {
        String now = Instant.now().toString();
        harness.sessions.save(harness.tenant, sessionId, harness.user, "会话", "m", "p", false, now, now, "{}");
    }

    /**
     * 打开一份连到 jasagent 的过滤器和它的账本。
     *
     * @return 可关闭的测试夹具
     */
    private static Harness harness() throws Exception {
        UragMysql mysql = UragMysql.discover();
        assertTrue(mysql != null, "没有找到本机 MySQL 口令");
        Path workspace = Files.createTempDirectory("attf026");
        ExecutionDatabase database = ExecutionDatabase.open(workspace, Map.of(
                "JASAGENT_JDBC_URL", mysql.url(),
                "JASAGENT_JDBC_USER", mysql.user(),
                "JASAGENT_JDBC_PASSWORD", mysql.password()));
        SessionLedger sessions = SessionLedger.open(database);
        SessionAttachmentLedger attachments = SessionAttachmentLedger.open(database);
        String user = "f" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String tenant = "t" + UUID.randomUUID().toString().replace("-", "");
        AttachmentFilter raw = new AttachmentFilter(sessions, attachments);
        AttachmentFilter filter = new Filtering(sessions, attachments, user, tenant);
        return new Harness(mysql, database, sessions, raw, filter, user, tenant);
    }

    /**
     * 在过滤前把账号放到请求上。生产里这一步由会话过滤器完成。
     */
    private static final class Filtering extends AttachmentFilter {
        private final String user;
        private final String tenant;

        /**
         * 记下测试用户。每次请求都放上同一份账号。
         *
         * @param sessions 会话账本
         * @param attachments 附件账本
         * @param user 登录名
         * @param tenant 租户
         */
        private Filtering(SessionLedger sessions, SessionAttachmentLedger attachments, String user, String tenant) {
            super(sessions, attachments);
            this.user = user;
            this.tenant = tenant;
        }

        /**
         * 先放上会话和账号，再交给附件过滤。没有属性的用例不走这里。
         *
         * @param request 当前请求
         * @param response 响应
         * @param chain 后续链条
         */
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        FilterChain chain) throws jakarta.servlet.ServletException, java.io.IOException {
            if (request.getAttribute(GatewaySessionFilter.SESSION) == null && request.getRequestURI() != null
                    && request.getRequestURI().toLowerCase().contains("/attachments")) {
                request.setAttribute(GatewaySessionFilter.SESSION, new Session("sid", "session-token", user, "/ws", Instant.now()));
                request.setAttribute(GatewaySessionFilter.ACCOUNT, new AccountProfile(user, AccountRoles.USER, tenant));
            }
            super.doFilterInternal(request, response, chain);
        }
    }

    /**
     * 一次测试打开的库和过滤器。
     */
    private static final class Harness {
        private final UragMysql mysql;
        private final ExecutionDatabase database;
        private final SessionLedger sessions;
        private final AttachmentFilter raw;
        private final AttachmentFilter filter;
        private final String user;
        private final String tenant;

        /**
         * 记下清理时要用的连接和身份。
         *
         * @param mysql 本机库
         * @param database 执行库
         * @param sessions 会话账本
         * @param raw 不自动放上账号的过滤器
         * @param filter 会放上测试账号的过滤器
         * @param user 登录名
         * @param tenant 租户
         */
        private Harness(UragMysql mysql, ExecutionDatabase database, SessionLedger sessions, AttachmentFilter raw,
                        AttachmentFilter filter, String user, String tenant) {
            this.mysql = mysql;
            this.database = database;
            this.sessions = sessions;
            this.raw = raw;
            this.filter = filter;
            this.user = user;
            this.tenant = tenant;
        }

        /**
         * 关掉库并删掉这次写入的会话。说明里不放口令。
         */
        private void close() {
            database.close();
            try (Connection connection = DriverManager.getConnection(mysql.url(), mysql.user(), mysql.password());
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM agent_session WHERE user_id = ?")) {
                statement.setString(1, user);
                statement.executeUpdate();
            } catch (Exception ex) {
                throw new IllegalStateException("测试会话无法清理");
            }
        }
    }
}
