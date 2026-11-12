package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.PolicyPassGate;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalDirectories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用可拨动的时钟核对过期报告。过期的证不能核销，也不会被标成已使用。
 */
class ExpiredPassTest {
    private static final Path HOME = createHome();
    private static final AdjustableClock CLOCK = new AdjustableClock(Instant.parse("2026-11-12T03:00:00Z"));
    private static Session alice;
    private static OneTimePassService service;
    private static PassStore store;

    /**
     * 在固定时刻建好用户、允许策略和证服务。口令不留在字段里。
     */
    @BeforeAll
    static void setup() throws IOException {
        alice = TestUsers.register(HOME, "alice", null);
        TestUsers.writePolicy(HOME, "alice", """
                rules:
                  - tool: rag.search
                    decision: ALLOW
                """);
        LocalDirectories directories = new LocalDirectories(HOME);
        store = new PassStore();
        service = new OneTimePassService(
                new HumanAccess(directories, new PolicyPassGate(directories::userDirectory)),
                PassSigner.create(""),
                store,
                new AuditLog(CLOCK),
                CLOCK,
                Duration.ofSeconds(60));
    }

    /**
     * 删除临时家目录里的口令摘要和会话。
     */
    @AfterAll
    static void cleanup() throws IOException {
        delete(HOME);
    }

    /**
     * 证在有效期内可以校验通过；拨过过期时间后报告过期，核销被拒绝，证仍是未使用。
     */
    @Test
    void expiredPassIsReportedAndNotRedeemed() {
        OneTimePassService.IssueResult issued = service.issue(new IssuePassRequest(
                "local", "alice", alice.sessionId(), "exec-expire", "http://127.0.0.1:18751", "rag.search"));
        assertTrue(issued.issued());
        PassProofRequest proof = new PassProofRequest(issued.pass().ticket(), "local", "alice", issued.pass().sessionId(),
                "exec-expire", "http://127.0.0.1:18751", "rag.search");
        VerifyReport fresh = service.verify(proof);
        assertTrue(fresh.proofValid());
        assertFalse(fresh.expired());
        assertTrue(fresh.accepted());
        CLOCK.advance(Duration.ofSeconds(61));
        VerifyReport expired = service.verify(proof);
        assertTrue(expired.proofValid());
        assertTrue(expired.expired());
        assertFalse(expired.used());
        assertFalse(expired.accepted());
        assertEquals(PassReasons.EXPIRED, expired.reason());
        OneTimePassService.RedeemResult redeem = service.redeem(proof);
        assertEquals(OneTimePassService.RedeemStatus.REJECTED, redeem.status());
        assertFalse(redeem.body().redeemed());
        assertFalse(store.find(issued.pass().passId()).used());
        assertTrue(issued.pass().ticket().indexOf(alice.token()) < 0);
    }

    /**
     * 创建这个测试专用的临时家目录。
     *
     * @return 临时目录
     */
    private static Path createHome() {
        try {
            return Files.createTempDirectory("jasagent-agentpass-expire-");
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

    /**
     * 测试里可以拨动的时钟。生产代码不使用这一类。
     */
    private static final class AdjustableClock extends Clock {
        private Instant now;

        /**
         * 从给定时刻开始。
         *
         * @param now 当前时刻
         */
        private AdjustableClock(Instant now) {
            this.now = now;
        }

        /**
         * 把当前时刻往后拨。
         *
         * @param duration 增加的时长
         */
        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        /**
         * 返回 UTC。过期比较只用瞬时，不看时区。
         *
         * @return UTC
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 保持这只钟本身。测试不切换时区。
         *
         * @param zone 调用方给出的时区，这里不使用
         * @return 这只钟
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * 返回测试设定的当前时刻。
         *
         * @return 当前时刻
         */
        @Override
        public Instant instant() {
            return now;
        }
    }
}
