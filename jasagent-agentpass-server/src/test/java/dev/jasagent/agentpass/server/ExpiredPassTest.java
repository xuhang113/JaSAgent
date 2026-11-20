package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.durable.AgentPassLedger;
import dev.jasagent.api.auth.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用可拨动的时钟核对过期报告。过期的证不能核销，也不会被标成已使用。
 */
class ExpiredPassTest {
    private static final AdjustableClock CLOCK = new AdjustableClock(Instant.parse("2026-11-12T03:00:00Z"));
    private static final String SIGNING_KEY = secret();
    private static Session alice;
    private static OneTimePassService service;
    private static PassStore store;

    /**
     * 在固定时刻把用户和允许策略写进账本，并用运行时密钥建证服务。不读策略文件。
     */
    @BeforeAll
    static void setup() {
        String jdbc = "jdbc:h2:mem:agentpassexpire;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        String password = secret();
        alice = new Session("alice-session-expire", secret(), "alice", null, CLOCK.instant());
        AgentPassLedger ledger = AgentPassLedger.open(jdbc, "sa", password);
        ledger.saveAccount("alice", "local");
        ledger.saveLogin("alice", alice.sessionId(), alice.token());
        ledger.savePolicy("local", "tool", "rag.search", "ALLOW");
        store = new PassStore(ledger);
        service = new OneTimePassService(
                new HumanAccess(ledger),
                PassSigner.create(SIGNING_KEY),
                store,
                new AuditLog(CLOCK),
                CLOCK,
                Duration.ofSeconds(60));
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
     * 生成只留在这次测试进程里的随机文本。不写进仓库。
     *
     * @return 十六进制文本
     */
    private static String secret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
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
