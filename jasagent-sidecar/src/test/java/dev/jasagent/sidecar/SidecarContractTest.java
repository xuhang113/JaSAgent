package dev.jasagent.sidecar;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对镜像约定和配置里的环境变量名还在。不构建镜像，也不读取密钥文件。
 */
class SidecarContractTest {
    /**
     * Dockerfile 写明镜像名、runsc、非 root 和容器端口 8080。进程常量与此相同。
     */
    @Test
    void dockerfileMatchesImageAndPort() throws Exception {
        Path dockerfile = Path.of("src/main/docker/Dockerfile");
        if (!Files.exists(dockerfile)) {
            dockerfile = Path.of("jasagent-sidecar/src/main/docker/Dockerfile");
        }
        String text = Files.readString(dockerfile);
        assertTrue(text.contains("jasagent-sidecar:0.1.0"));
        assertTrue(text.contains("runsc"));
        assertTrue(text.contains("EXPOSE 8080"));
        assertTrue(text.contains("USER 10001"));
        assertTrue(text.contains("--server.port=8080"));
        assertFalse(text.contains("BEGIN PRIVATE KEY"));
        assertEquals(8080, SidecarApplication.PORT);
        assertEquals("0.0.0.0", SidecarApplication.ADDRESS);
    }

    /**
     * 额外放行主机的环境变量名是 {@code JASAGENT_LLM_ALLOWLIST}，配置文件里没有密钥原文。
     */
    @Test
    void propertiesNameTheAllowlistEnvironment() throws Exception {
        Path properties = Path.of("src/main/resources/application.properties");
        if (!Files.exists(properties)) {
            properties = Path.of("jasagent-sidecar/src/main/resources/application.properties");
        }
        String text = Files.readString(properties);
        assertTrue(text.contains("JASAGENT_LLM_ALLOWLIST"));
        assertTrue(text.contains("server.port=8080"));
        assertFalse(text.contains("BEGIN PRIVATE KEY"));
        assertEquals(LlmAllowlist.ALLOWLIST_ENV, "JASAGENT_LLM_ALLOWLIST");
    }

    /**
     * 转发实现不再把整段正文读进内存。生产源码里不能再出现这两种整包读法。
     */
    @Test
    void forwardPathDoesNotBufferTheWholeBody() throws Exception {
        Path root = Path.of("src/main/java");
        if (!Files.exists(root)) {
            root = Path.of("jasagent-sidecar/src/main/java");
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(file -> file.toString().endsWith(".java")).toList()) {
                String text = Files.readString(path);
                assertFalse(text.contains("readAllBytes"), path.toString());
                assertFalse(text.contains("ofByteArray"), path.toString());
            }
        }
    }
}
