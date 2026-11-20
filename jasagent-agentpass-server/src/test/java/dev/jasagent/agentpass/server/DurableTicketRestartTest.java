package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.durable.AgentPassLedger;
import dev.jasagent.api.auth.Session;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一库、同一签名密钥上新建一份服务后，已核销的证仍拒绝重放，未使用的证仍可核销。
 */
class DurableTicketRestartTest {
    /**
     * 第一份服务核销一张证并留下另一张。第二份服务连同一库，重放失败，未使用的证成功，流水不串租户。
     * 副作用：写入内存库。断言失败则测试失败。
     */
    @Test
    void redeemedTicketStaysRejectedAfterReopen() throws Exception {
        String url = "jdbc:h2:mem:ticketrestart;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        String password = secret();
        String key = secret();
        AgentPassLedger ledger = AgentPassLedger.open(url, "sa", password);
        Session alice = session("alice");
        Session bob = session("bob");
        seed(ledger, alice, "tenant-a");
        seed(ledger, bob, "tenant-b");
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), Clock.systemUTC().getZone());
        OneTimePassService first = service(ledger, key, clock);
        OneTimePassService.IssueResult used = first.issue(request(alice, "tenant-a", "exec-used"));
        OneTimePassService.IssueResult fresh = first.issue(request(bob, "tenant-b", "exec-fresh"));
        assertTrue(used.issued());
        assertTrue(fresh.issued());
        assertFalse(used.pass().ticket().contains(alice.token()));
        assertFalse(fresh.pass().ticket().contains(bob.token()));
        OneTimePassService.RedeemResult redeemed = first.redeem(proof(used.pass()));
        assertEquals(OneTimePassService.RedeemStatus.REDEEMED, redeemed.status());
        OneTimePassService second = service(ledger, key, clock);
        OneTimePassService.RedeemResult replay = second.redeem(proof(used.pass()));
        assertEquals(OneTimePassService.RedeemStatus.REPLAY, replay.status());
        OneTimePassService.RedeemResult again = second.redeem(proof(fresh.pass()));
        assertEquals(OneTimePassService.RedeemStatus.REDEEMED, again.status());
        List<AgentPassLedger.StoredAudit> aliceAudit = ledger.auditForTenant("tenant-a");
        List<AgentPassLedger.StoredAudit> bobAudit = ledger.auditForTenant("tenant-b");
        assertTrue(aliceAudit.stream().anyMatch(event -> "exec-used".equals(event.executionId())));
        assertTrue(bobAudit.stream().anyMatch(event -> "exec-fresh".equals(event.executionId())));
        assertTrue(aliceAudit.stream().noneMatch(event -> "exec-fresh".equals(event.executionId())));
        assertTrue(bobAudit.stream().noneMatch(event -> "exec-used".equals(event.executionId())));
        OneTimePassService.IssueResult third = second.issue(request(bob, "tenant-b", "exec-race"));
        assertTrue(third.issued());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            Future<OneTimePassService.RedeemResult> left = pool.submit(() -> redeemTogether(second, third.pass(), ready));
            Future<OneTimePassService.RedeemResult> right = pool.submit(() -> redeemTogether(second, third.pass(), ready));
            int redeemedCount = 0;
            int replayCount = 0;
            for (OneTimePassService.RedeemResult result : List.of(left.get(), right.get())) {
                if (result.status() == OneTimePassService.RedeemStatus.REDEEMED) {
                    redeemedCount++;
                }
                if (result.status() == OneTimePassService.RedeemStatus.REPLAY) {
                    replayCount++;
                }
            }
            assertEquals(1, redeemedCount);
            assertEquals(1, replayCount);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 两张核销同时起跑，用来确认只有一张成功。
     *
     * @param service 证服务
     * @param pass 已签发的证
     * @param ready 两边都进入后再一起核销
     * @return 核销结果
     */
    private static OneTimePassService.RedeemResult redeemTogether(OneTimePassService service, IssuedPass pass, CountDownLatch ready)
            throws Exception {
        ready.countDown();
        ready.await();
        return service.redeem(proof(pass));
    }

    /**
     * 用同一库和同一密钥再开一份服务。
     *
     * @param ledger 账本
     * @param key 运行时签名密钥
     * @param clock 固定时钟
     * @return 证服务
     */
    private static OneTimePassService service(AgentPassLedger ledger, String key, Clock clock) {
        return new OneTimePassService(new HumanAccess(ledger), PassSigner.create(key), new PassStore(ledger),
                new AuditLog(clock, ledger), clock, Duration.ofSeconds(120));
    }

    /**
     * 写入账号、会话和允许检索的策略。
     *
     * @param ledger 账本
     * @param session 登录会话
     * @param tenant 该用户独占的租户
     */
    private static void seed(AgentPassLedger ledger, Session session, String tenant) {
        ledger.saveAccount(session.login(), tenant);
        ledger.saveLogin(session.login(), session.sessionId(), session.token());
        ledger.savePolicy(tenant, "tool", "rag.search", "ALLOW");
    }

    /**
     * 造一个只在内存里的会话。令牌不写进仓库。
     *
     * @param login 登录名
     * @return 会话
     */
    private static Session session(String login) {
        return new Session(login + "-session", secret(), login, null, Instant.parse("2026-11-20T00:00:00Z"));
    }

    /**
     * 组装领证请求。
     *
     * @param session 登录会话
     * @param tenant 租户
     * @param executionId 执行编号
     * @return 领证请求
     */
    private static IssuePassRequest request(Session session, String tenant, String executionId) {
        return new IssuePassRequest(tenant, session.login(), session.sessionId(), executionId, "http://127.0.0.1:18751", "rag.search");
    }

    /**
     * 用签发结果里的绑定字段组装核销请求。
     *
     * @param pass 已签发的证
     * @return 核销请求
     */
    private static PassProofRequest proof(IssuedPass pass) {
        return new PassProofRequest(pass.ticket(), pass.tenantId(), pass.userId(), pass.sessionId(), pass.executionId(),
                pass.target(), pass.toolName());
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
