package dev.jasagent.mcp;

import dev.jasagent.api.pass.ExternalEgress;
import dev.jasagent.api.pass.OneTimeTicket;
import dev.jasagent.api.pass.OneTimeTicketClient;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.TicketRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部调用前的一次性证。否决和等待不领证；没有有效证不执行；时钟和只读工具不领证。
 */
class ExternalEgressTest {
    /**
     * 策略是 DENY 时不访问领证端口，也不允许执行。
     */
    @Test
    void denyDoesNotAskForTicketOrInvoke() {
        AtomicInteger asks = new AtomicInteger();
        ExternalEgress.Outcome outcome = ExternalEgress.permit(
                PassDecision.DENY,
                ExternalEgress.Kind.EXTERNAL,
                request -> {
                    asks.incrementAndGet();
                    return new OneTimeTicket("should-not");
                },
                sample());
        assertEquals(0, asks.get());
        assertFalse(outcome.askedForTicket());
        assertFalse(outcome.invoke());
    }

    /**
     * 策略是 PENDING 时不领证，也不允许执行。
     */
    @Test
    void pendingDoesNotAskForTicketOrInvoke() {
        AtomicInteger asks = new AtomicInteger();
        ExternalEgress.Outcome outcome = ExternalEgress.permit(
                PassDecision.PENDING,
                ExternalEgress.Kind.EXTERNAL,
                request -> {
                    asks.incrementAndGet();
                    return new OneTimeTicket("should-not");
                },
                sample());
        assertEquals(0, asks.get());
        assertFalse(outcome.invoke());
    }

    /**
     * 允许之后证是空白时，已经问过领证端口，但仍然不能执行。
     */
    @Test
    void allowWithoutTicketDoesNotInvoke() {
        AtomicInteger asks = new AtomicInteger();
        ExternalEgress.Outcome outcome = ExternalEgress.permit(
                PassDecision.ALLOW,
                ExternalEgress.Kind.EXTERNAL,
                request -> {
                    asks.incrementAndGet();
                    return new OneTimeTicket("  ");
                },
                sample());
        assertEquals(1, asks.get());
        assertTrue(outcome.askedForTicket());
        assertFalse(outcome.invoke());
        assertEquals(ExternalEgress.TICKET_MISSING, outcome.detail());
    }

    /**
     * 允许且证有效时可以执行，证文本带回调用方。
     */
    @Test
    void allowWithTicketInvokes() {
        ExternalEgress.Outcome outcome = ExternalEgress.permit(
                PassDecision.ALLOW,
                ExternalEgress.Kind.EXTERNAL,
                request -> new OneTimeTicket("ticket-1"),
                sample());
        assertTrue(outcome.invoke());
        assertEquals("ticket-1", outcome.detail());
    }

    /**
     * 时钟和只读工具不领证，直接允许执行。
     */
    @Test
    void clockAndReadOnlySkipTicket() {
        AtomicInteger asks = new AtomicInteger();
        OneTimeTicketClient client = request -> {
            asks.incrementAndGet();
            return new OneTimeTicket("");
        };
        ExternalEgress.Outcome clock = ExternalEgress.permit(null, ExternalEgress.Kind.CLOCK, client, sample());
        ExternalEgress.Outcome readOnly = ExternalEgress.permit(PassDecision.ALLOW, ExternalEgress.Kind.READ_ONLY, client, sample());
        assertTrue(clock.invoke());
        assertTrue(readOnly.invoke());
        assertEquals(0, asks.get());
    }

    /**
     * 造一条带租户、用户、会话、执行和示例检索地址的领证请求。
     *
     * @return 测试用请求
     */
    private static TicketRequest sample() {
        return new TicketRequest("tenant-a", "user-a", "session-a", "exec-a", ExternalEgress.RAG_TARGET, "rag.search");
    }
}
