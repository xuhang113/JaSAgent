package dev.jasagent.agentpass.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 缺少签名密钥时必须失败。异常和日志里不能出现数据库口令，也不能出现临时生成的密钥。
 */
class SigningKeyRequiredTest {
    /**
     * 空白密钥直接拒绝，消息只有配置名。
     */
    @Test
    void blankKeyIsRejected() {
        String sentinel = secret();
        IllegalStateException blank = assertThrows(IllegalStateException.class, () -> PassSigner.create(""));
        assertEquals("jasagent.agentpass.signing-key", blank.getMessage());
        assertFalse(blank.toString().contains(sentinel));
        IllegalStateException spaces = assertThrows(IllegalStateException.class, () -> PassSigner.create("  "));
        assertEquals("jasagent.agentpass.signing-key", spaces.getMessage());
        PassSigner signer = PassSigner.create(sentinel);
        assertFalse(signer.ephemeral());
    }

    /**
     * 库地址已经给出、签名密钥仍是空白时，Spring 上下文起不来。日志不包含口令，也不声称生成了进程内密钥。
     * 副作用：尝试启动并关闭上下文。断言失败则测试失败。
     */
    @Test
    void contextRefusesBlankSigningKey() {
        String sentinel = secret();
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        SpringApplication application = new SpringApplication(AgentPassServerApplication.class);
        application.setDefaultProperties(Map.of(
                "server.port", "0",
                "jasagent.agentpass.jdbc-url", "jdbc:h2:mem:nokey;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "jasagent.agentpass.jdbc-user", "sa",
                "jasagent.agentpass.jdbc-password", sentinel,
                "jasagent.agentpass.signing-key", " "));
        ConfigurableApplicationContext context = null;
        try {
            context = application.run();
            fail("缺少签名密钥时不应启动");
        } catch (RuntimeException ex) {
            assertTrue(mentionsSigningKey(ex), "启动失败原因不是缺少签名密钥");
            assertFalse(trace(ex).contains(sentinel));
        } finally {
            if (context != null) {
                context.close();
            }
            logger.detachAppender(logs);
        }
        StringBuilder written = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            written.append(event.getFormattedMessage()).append('\n');
        }
        assertFalse(written.toString().contains(sentinel));
        assertFalse(written.toString().contains("process-local"));
    }

    /**
     * 沿原因链查找签名密钥配置名。
     *
     * @param failure 启动失败
     * @return 原因里出现配置名时为真
     */
    private static boolean mentionsSigningKey(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if ("jasagent.agentpass.signing-key".equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 收集原因链上的消息，用来确认口令没有被带出来。不把这条文本写进断言说明以外的地方。
     *
     * @param failure 启动失败
     * @return 原因消息拼在一起的文本
     */
    private static String trace(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return text.toString();
    }

    /**
     * 生成只留在这次测试进程里的随机文本。不写进仓库。
     *
     * @return 十六进制文本
     */
    private static String secret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
