package dev.jasagent.gateway.agent;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长连接的存活标记。用临时端口，不占用也不关闭 18731 和 18732。
 */
class TcpAgentLinkTest {
    /**
     * 套接字连上则实例存活，对端关闭则下线。整个过程只拨测试自己的端口。
     */
    @Test
    void connectionUpMeansAliveAndCloseMeansOffline() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            assertNotEquals(AgentInstance.PRIMARY_PORT, port);
            assertNotEquals(AgentInstance.SECONDARY_PORT, port);
            AtomicReference<Socket> accepted = new AtomicReference<>();
            Thread accepter = new Thread(() -> {
                try {
                    accepted.set(server.accept());
                } catch (Exception ignored) {
                    // 测试结束关监听口时，接受线程退出。
                }
            });
            accepter.start();
            AgentInstance instance = new AgentInstance("agent-test", "127.0.0.1", port);
            InMemoryAgentLiveness liveness = new InMemoryAgentLiveness();
            TcpAgentLink link = new TcpAgentLink(instance, liveness, Duration.ofMillis(200), Duration.ofSeconds(30));
            link.start();
            assertTrue(waitUntil(() -> liveness.connectionAlive(instance), 5000));
            Socket peer = accepted.get();
            if (peer != null) {
                peer.close();
            }
            server.close();
            assertTrue(waitUntil(() -> !liveness.connectionAlive(instance), 5000));
            link.stop();
            accepter.join(1000);
            assertFalse(liveness.connectionAlive(instance));
        }
    }

    /**
     * 等到条件成立或超时。
     *
     * @param condition 要等的状态
     * @param timeoutMillis 最长等待毫秒
     * @return 超时前成立时为真
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean waitUntil(Check condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.ok()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.ok();
    }

    /**
     * 轮询用的条件。成立时返回真。
     */
    private interface Check {
        /**
         * 当前是否已经满足。
         *
         * @return 满足时为真
         */
        boolean ok();
    }
}
