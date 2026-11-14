package dev.jasagent.web.upstream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jasagent-web 作为控制台上游提供登录页和静态资源。它不监听 18721，也不提供登录接口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebUpstreamTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    /**
     * 配置和启动类的默认端口是 18722，不是网关的 18721。
     */
    @Test
    void defaultsDoNotUseTheGatewayPort() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("server.address=127.0.0.1"));
        assertTrue(props.contains("server.port=18722"));
        assertFalse(props.contains("server.port=18721"));
        String source = Files.readString(Path.of("src/main/java/dev/jasagent/web/upstream/WebUpstreamApplication.java"));
        assertTrue(source.contains("WebUpstream.DEFAULT_PORT"));
        assertEquals(18722, WebUpstream.DEFAULT_PORT);
        assertNotEquals(18721, port);
    }

    /**
     * 登录页和控制台入口都返回本模块构建出的页面，静态资源也能读到。登录接口不在这里。
     */
    @Test
    void servesLoginPageAndAssetsOnly() throws Exception {
        ResponseEntity<String> login = http.getForEntity("/admin/login", String.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        String html = login.getBody();
        assertNotNull(html);
        assertTrue(html.contains("/admin/assets/"), html);
        int src = html.indexOf("/admin/assets/");
        int end = html.indexOf('"', src);
        assertTrue(end > src);
        String asset = html.substring(src, end);
        ResponseEntity<String> file = http.getForEntity(asset, String.class);
        assertEquals(HttpStatus.OK, file.getStatusCode());
        assertNotNull(file.getBody());
        assertFalse(file.getBody().isBlank());
        ResponseEntity<String> console = http.getForEntity("/admin/", String.class);
        assertEquals(HttpStatus.OK, console.getStatusCode());
        assertTrue(String.valueOf(console.getBody()).contains("/admin/assets/"));
        assertEquals(HttpStatus.NOT_FOUND, http.postForEntity("/login", "user=a&password=b", String.class).getStatusCode());
    }
}
