package dev.jasagent.llm.egress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站响应按块到达。第一块在上游写完之前就能读到，读取不使用会钉住载体的输入流处理器。
 */
class CarrierFreeReadTest {
    /**
     * 直连一个先写首块再停住的服务，确认交换在正文结束前返回，并且首块已经能读出。
     */
    @Test
    @Timeout(10)
    void firstBytesArriveBeforeUpstreamFinishes() throws Exception {
        CountDownLatch firstRead = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> serve(server, firstRead, finished));
            try (SidecarForward http = SidecarForward.open(Duration.ofSeconds(2), false)) {
                SidecarForward.Call call = http.exchange(
                        "POST",
                        URI.create("http://127.0.0.1:" + server.getLocalPort() + "/slow"),
                        java.util.Map.of(),
                        "ping".getBytes(StandardCharsets.UTF_8),
                        Duration.ofSeconds(5));
                byte[] first = call.body().readNBytes(4);
                assertEquals("ONE\n", new String(first, StandardCharsets.UTF_8));
                assertFalse(finished.get());
                firstRead.countDown();
                byte[] rest = call.body().readNBytes(4);
                assertEquals("TWO\n", new String(rest, StandardCharsets.UTF_8));
                call.close();
            }
        }
    }

    /**
     * 大模型出站源码不再使用 JDK 的 {@code ofInputStream}。那种读法会在同步方法里钉住虚拟线程的载体。
     */
    @Test
    void llmSourcesDoNotUseOfInputStream() throws Exception {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("ofInputStream"), file.toString());
            }
        }
    }

    /**
     * 读完请求头后先写出首块并刷新，等到调用方读到首块再写第二块。
     *
     * @param server 已经绑定的监听套接字
     * @param firstRead 调用方读到首块后放开
     * @param finished 第二块写出后置真
     */
    private static void serve(ServerSocket server, CountDownLatch firstRead, AtomicBoolean finished) {
        try (Socket socket = server.accept()) {
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            while (total < 4 || new String(buffer, 0, total, StandardCharsets.ISO_8859_1).contains("\r\n\r\n") == false) {
                int n = input.read(buffer, total, buffer.length - total);
                if (n < 0) {
                    return;
                }
                total += n;
            }
            socket.getOutputStream().write((
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: 8\r\n"
                            + "\r\n"
                            + "ONE\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            socket.getOutputStream().write("TWO\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            finished.set(true);
        } catch (Exception ex) {
            finished.set(true);
        }
    }
}
