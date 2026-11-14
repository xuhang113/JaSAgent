package dev.jasagent.app.nacos;

import dev.jasagent.api.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册地址必须是网关能连上的非回环地址。
 */
class ReachableAddressTest {
    /**
     * 回环、localhost 和未指定地址都不能注册。普通 IPv4 可以。
     */
    @Test
    void rejectsLoopbackAndAcceptsRoutableAddresses() {
        assertFalse(ReachableAddress.usable("127.0.0.1"));
        assertFalse(ReachableAddress.usable("localhost"));
        assertFalse(ReachableAddress.usable("::1"));
        assertFalse(ReachableAddress.usable("0.0.0.0"));
        assertTrue(ReachableAddress.usable("10.1.2.3"));
        assertTrue(ReachableAddress.loopbackBind("127.0.0.1"));
        assertTrue(ReachableAddress.loopbackBind("localhost"));
        assertFalse(ReachableAddress.loopbackBind("0.0.0.0"));
        assertFalse(ReachableAddress.loopbackBind(""));
        AgentException rejected = assertThrows(AgentException.class, () -> ReachableAddress.require("127.0.0.1"));
        assertEquals(ReachableAddress.REJECTED, rejected.getMessage());
        assertFalse(rejected.getMessage().contains("127.0.0.1"));
    }

    /**
     * 自动选出的地址不是回环，并且本机监听 0.0.0.0 时能连上它。
     */
    @Test
    void detectedAddressIsReachable() throws Exception {
        String host = ReachableAddress.require("");
        assertTrue(ReachableAddress.usable(host));
        assertFalse(ReachableAddress.loopbackBind(host));
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("0.0.0.0", 0));
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, server.getLocalPort()), 2000);
            }
        }
    }
}
