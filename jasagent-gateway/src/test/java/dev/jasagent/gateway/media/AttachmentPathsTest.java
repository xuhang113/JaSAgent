package dev.jasagent.gateway.media;

import dev.jasagent.gateway.route.GatewayPaths;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件前缀在规范化之后仍然由网关截住。普通会话和事件流继续转发。
 */
class AttachmentPathsTest {
    /**
     * 集合、单条和多余段都能认出来。大小写不改变会话编号。
     */
    @Test
    void collectionAndItemStayOnTheGateway() {
        AttachmentPaths.Target collection = AttachmentPaths.inspect("/api/sessions/s1/attachments");
        assertEquals(AttachmentPaths.Kind.COLLECTION, collection.kind());
        assertEquals("s1", collection.sessionId());
        AttachmentPaths.Target item = AttachmentPaths.inspect("/API/sessions/AbC/ATTACHMENTS/file-1/");
        assertEquals(AttachmentPaths.Kind.ITEM, item.kind());
        assertEquals("AbC", item.sessionId());
        assertEquals("file-1", item.attachmentId());
        assertEquals(AttachmentPaths.Kind.EXTRA, AttachmentPaths.inspect("/api/sessions/s1/attachments/file-1/extra").kind());
        assertTrue(GatewayPaths.local(HttpMethod.POST, "/api/sessions/s1/attachments"));
        assertFalse(GatewayPaths.proxied(HttpMethod.POST, "/api/sessions/s1/attachments"));
        assertFalse(GatewayPaths.proxied(HttpMethod.GET, "/API/sessions/s1/ATTACHMENTS/file-1"));
        assertTrue(GatewayPaths.proxied(HttpMethod.GET, "/api/sessions"));
        assertTrue(GatewayPaths.proxied(HttpMethod.GET, "/api/sessions/s1"));
        assertTrue(GatewayPaths.proxied(HttpMethod.GET, "/api/executions/exec-1/events"));
    }

    /**
     * 点段、编码、多余斜杠和反斜杠都不能把附件请求送去转发。
     */
    @Test
    void obfuscationStillIntersectsThePrefix() {
        assertEquals(AttachmentPaths.Kind.BLOCKED, AttachmentPaths.inspect("/api/sessions/s1/attachments/../messages").kind());
        assertEquals(AttachmentPaths.Kind.BLOCKED, AttachmentPaths.inspect("/api/sessions/s1/attachments/%2e%2e/messages").kind());
        assertEquals(AttachmentPaths.Kind.BLOCKED, AttachmentPaths.inspect("/api/sessions/s1/attachments/%252e%252e/messages").kind());
        assertEquals(AttachmentPaths.Kind.BLOCKED, AttachmentPaths.inspect("/api/sessions/s1/attachments\\..\\messages").kind());
        assertEquals(AttachmentPaths.Kind.COLLECTION, AttachmentPaths.inspect("//api//sessions//s1//attachments").kind());
        assertEquals(AttachmentPaths.Kind.COLLECTION, AttachmentPaths.inspect("/api/sessions/s1/%61ttachments").kind());
        assertEquals(AttachmentPaths.Kind.BLOCKED, AttachmentPaths.inspect("/api/sessions/s1/attachments%00/../messages").kind());
        assertEquals(AttachmentPaths.Kind.BLOCKED, AttachmentPaths.inspect("/api/sessions/bad..id/attachments").kind());
        assertFalse(GatewayPaths.proxied(HttpMethod.GET, "/api/sessions/s1/attachments/../messages"));
        assertTrue(GatewayPaths.local(HttpMethod.GET, "/api/sessions/s1/%61ttachments"));
    }

    /**
     * 请求 URI 和 servlet 路径不一致，或 URI 里先踩进前缀时，也不能放行。
     */
    @Test
    void requestUriAndServletPathMustAgree() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sessions/s1/messages");
        request.setRequestURI("/api/sessions/s1/attachments/../messages");
        request.setServletPath("/api/sessions/s1/messages");
        assertEquals(AttachmentPaths.Kind.BLOCKED, AttachmentPaths.resolve(request).kind());
        MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/api/sessions/s1");
        assertEquals(AttachmentPaths.Kind.MISS, AttachmentPaths.resolve(plain).kind());
        assertFalse(AttachmentPaths.intercepts("/admin/jaeger"));
        assertFalse(AttachmentPaths.intercepts("/admin/cluster"));
        assertFalse(AttachmentPaths.intercepts("/api/cluster"));
    }
}
