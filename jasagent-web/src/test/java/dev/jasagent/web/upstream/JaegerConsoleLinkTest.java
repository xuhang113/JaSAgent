package dev.jasagent.web.upstream;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路入口是 admin 才看得到的普通链接。它不是前端路由，也不写进控制台页面表。
 */
class JaegerConsoleLinkTest {
    /**
     * 页面源码用普通锚点指向 /admin/jaeger，并且只在 admin 时渲染。路由和页面表都不登记这个地址。
     * 副作用：读取前端和页面控制器源码。
     */
    @Test
    void jaegerEntryIsAnAdminAnchorOutsideTheRouter() throws Exception {
        String view = Files.readString(Path.of("frontend/src/views/ConsoleView.vue"));
        int at = view.indexOf("data-jaeger-entry");
        assertTrue(at > 0);
        int start = view.lastIndexOf('<', at);
        int end = view.indexOf('>', at);
        String tag = view.substring(start, end + 1);
        assertTrue(tag.startsWith("<a "));
        assertTrue(tag.contains("v-if=\"isAdmin\""));
        assertTrue(tag.contains("href=\"/admin/jaeger\""));
        assertFalse(tag.contains("router-link"));
        String router = Files.readString(Path.of("frontend/src/router.js"));
        assertFalse(router.contains("jaeger"));
        String pages = Files.readString(Path.of("src/main/java/dev/jasagent/web/upstream/ConsolePages.java"));
        assertFalse(pages.contains("jaeger"));
        assertFalse(pages.contains("/admin/jaeger"));
    }
}
