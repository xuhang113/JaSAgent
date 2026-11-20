package dev.jasagent.agentpass.durable;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.agentpass.PolicyPassGate;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对租户账本上的策略、待批和越权决定。策略文件写了相反的决定也不能改变结果。
 */
class DurablePassGateTest {
    /**
     * 没有把持久化来源写成 mysql 时，门仍按用户目录里的策略文件裁决。
     */
    @Test
    void fileModeStaysOnPolicyFiles() {
        PassGate gate = PassSettings.create(Map.of(), user -> Path.of("unused"));
        assertTrue(gate instanceof PolicyPassGate);
    }

    /**
     * 账号不存在时拒绝。策略文件允许也不算数。未命中留下待批，同一用户可以批准，别人不能。
     * 副作用：写入临时目录和内存库。断言失败则测试失败。
     */
    @Test
    void pendingIsDurableAndOnlyTheSameUserDecides() throws Exception {
        String url = "jdbc:h2:mem:passgate;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        String password = secret();
        AgentPassLedger ledger = AgentPassLedger.open(url, "sa", password);
        ledger.saveAccount("owner", "tenant-owner");
        ledger.saveAccount("other", "tenant-other");
        Path home = Files.createTempDirectory("jasagent-pass-gate");
        Path policy = home.resolve("owner").resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.createDirectories(policy.getParent());
        Files.writeString(policy, """
                rules:
                  - tool: rag.search
                    decision: DENY
                  - tool: read_file
                    decision: DENY
                """);
        PassGate gate = PassSettings.create(Map.of(
                PassSettings.DURABLE, "mysql",
                PassSettings.JDBC_URL, url,
                PassSettings.JDBC_USER, "sa",
                PassSettings.JDBC_PASSWORD, password), user -> home.resolve(user).resolve("jasagent"));
        CompletionException missing = assertThrows(CompletionException.class, () -> gate.acquire(
                new PassRequest("stranger", "exec-stranger", "rag.search", null, "")).toCompletableFuture().join());
        assertTrue(missing.getCause() instanceof AgentException);
        assertEquals(ErrorCode.AUTH, ((AgentException) missing.getCause()).code());
        Pass read = gate.acquire(new PassRequest("owner", "exec-read", "read_file", null, "")).toCompletableFuture().join();
        assertEquals(PassDecision.ALLOW, read.decision());
        assertEquals(0, ledger.countPending("exec-read"));
        Pass pending = gate.acquire(new PassRequest("owner", "exec-hold", "rag.search", null, "")).toCompletableFuture().join();
        assertEquals(PassDecision.PENDING, pending.decision());
        assertEquals("approval-required", pending.reason());
        Pass again = gate.acquire(new PassRequest("owner", "exec-hold", "rag.search", null, "")).toCompletableFuture().join();
        assertEquals(pending.passId(), again.passId());
        assertEquals(1, ledger.countPending("exec-hold"));
        assertFalse(Files.isDirectory(home.resolve("owner").resolve("jasagent").resolve("auth").resolve("pending")));
        AgentPassLedger.DecisionOutcome forbidden = ((DurablePassGate) gate).decideStored(
                new PassApproval("other", "exec-hold", pending.passId(), PassDecision.ALLOW, null));
        assertEquals(AgentPassLedger.DecisionOutcome.FORBIDDEN, forbidden);
        Pass decided = gate.decide(new PassApproval("owner", "exec-hold", pending.passId(), PassDecision.ALLOW, null))
                .toCompletableFuture().join();
        assertEquals(PassDecision.ALLOW, decided.decision());
        Pass after = gate.acquire(new PassRequest("owner", "exec-hold", "rag.search", null, "")).toCompletableFuture().join();
        assertEquals(PassDecision.ALLOW, after.decision());
        assertEquals("approved", after.reason());
        ledger.savePolicy("tenant-owner", "tool", "rag.write", "DENY");
        Pass denied = gate.acquire(new PassRequest("owner", "exec-deny", "rag.write", null, "")).toCompletableFuture().join();
        assertEquals(PassDecision.DENY, denied.decision());
        assertEquals(0, ledger.countPending("exec-deny"));
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
