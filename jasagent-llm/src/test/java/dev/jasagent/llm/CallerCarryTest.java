package dev.jasagent.llm;

import dev.jasagent.api.runtime.AssignedExecution;
import dev.jasagent.api.runtime.CallerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 身份快照可以带到另一条线程，两条快照不会串，任务结束会恢复进入前的值。
 */
class CallerCarryTest {
    /**
     * 每个用例结束后清掉当前线程上的身份，避免留到下一个测试。
     */
    @AfterEach
    void clear() {
        CallerScope.clear();
        AssignedExecution.clear();
    }

    /**
     * 两条虚拟线程各持一份快照。每条只看见自己的租户和执行编号，调用线程上的后一份身份还在。
     */
    @Test
    void snapshotsStayOnTheirOwnThreads() throws Exception {
        CallerScope.bind(new CallerScope("tenant-a", "user-a", "sess-a"));
        AssignedExecution.bind("exec-a");
        CallerCarry.Snapshot first = CallerCarry.capture();
        CallerScope.bind(new CallerScope("tenant-b", "user-b", "sess-b"));
        AssignedExecution.bind("exec-b");
        CallerCarry.Snapshot second = CallerCarry.capture();
        AtomicReference<String> left = new AtomicReference<>();
        AtomicReference<String> right = new AtomicReference<>();
        Thread one = Thread.ofVirtual().start(() -> first.run(() -> left.set(text())));
        Thread two = Thread.ofVirtual().start(() -> second.run(() -> right.set(text())));
        one.join();
        two.join();
        assertEquals("tenant-a user-a sess-a exec-a", left.get());
        assertEquals("tenant-b user-b sess-b exec-b", right.get());
        assertEquals("tenant-b user-b sess-b exec-b", text());
    }

    /**
     * 显式带上的身份在任务结束后不会留在线程上，也不会盖住外层已经绑好的身份。
     */
    @Test
    void holdRestoresThePreviousScope() {
        CallerScope.bind(new CallerScope("outer", "outer-user", "outer-session"));
        AssignedExecution.bind("outer-exec");
        CallerCarry.hold(new CallerScope("inner", "inner-user", "inner-session"), "inner-exec").run(() -> {
            assertEquals("inner inner-user inner-session inner-exec", text());
        });
        assertEquals("outer outer-user outer-session outer-exec", text());
        CallerCarry.hold(null, null).run(() -> {
            assertNull(CallerScope.current());
            assertNull(AssignedExecution.current());
        });
        assertEquals("outer outer-user outer-session outer-exec", text());
    }

    /**
     * 把当前线程的租户、用户、会话和执行编号收成一行。
     *
     * @return 用空格隔开的四个值；没有身份时对应位置是 null
     */
    private static String text() {
        CallerScope scope = CallerScope.current();
        String tenant = scope == null ? "null" : scope.tenantId();
        String user = scope == null ? "null" : scope.userId();
        String session = scope == null ? "null" : scope.sessionId();
        String execution = AssignedExecution.current() == null ? "null" : AssignedExecution.current();
        return tenant + " " + user + " " + session + " " + execution;
    }
}
