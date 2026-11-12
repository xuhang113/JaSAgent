package dev.jasagent.gateway.agent;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 与一台 agent 保持一条 TCP 长连接。连接建立后实例记为存活，套接字断开则记为下线。
 * 这条连接上只重复发送 GET /health，不携带内部令牌，避免令牌进到访问日志。
 * 断开时只关闭本端套接字，不停止对方进程。
 */
public final class TcpAgentLink implements Runnable {
    /**
     * 长连接上探测用的路径。agent 的健康检查不要求内部令牌。
     */
    public static final String HEALTH_PATH = "/health";

    private final AgentInstance instance;
    private final InMemoryAgentLiveness liveness;
    private final Duration retryDelay;
    private final Duration heartbeatInterval;
    private final int connectTimeoutMillis;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket socket;
    private Thread thread;

    /**
     * 准备一条还没启动的长连接。
     *
     * @param instance 要连接的 agent
     * @param liveness 连接状态写到这里
     * @param retryDelay 断开后再次连接前的等待
     * @param heartbeatInterval 同一条连接上两次健康检查的间隔
     */
    public TcpAgentLink(AgentInstance instance, InMemoryAgentLiveness liveness, Duration retryDelay, Duration heartbeatInterval) {
        this.instance = instance;
        this.liveness = liveness;
        this.retryDelay = retryDelay;
        this.heartbeatInterval = heartbeatInterval;
        this.connectTimeoutMillis = 2000;
    }

    /**
     * 在后台线程上开始连接。重复调用不会再开一条线程。
     * 副作用：启动名为 jasagent-agent-link-端口 的守护线程。
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this, "jasagent-agent-link-" + instance.port());
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * 停掉这条长连接。只关闭网关这一端，不向 agent 发停止命令。
     * 副作用：中断后台线程并关掉套接字。
     */
    public void stop() {
        running.set(false);
        closeQuietly(socket);
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        liveness.markDown(instance);
    }

    /**
     * 反复建立同一条连接。连上则标记存活，读失败或对端关闭则标记下线并稍后重连。
     * 线程被中断或 {@link #stop()} 之后退出。
     */
    @Override
    public void run() {
        while (running.get()) {
            Socket opened = new Socket();
            try {
                opened.connect(new InetSocketAddress(instance.host(), instance.port()), connectTimeoutMillis);
                opened.setKeepAlive(true);
                socket = opened;
                liveness.markUp(instance);
                liveness.heartbeat(instance, Instant.now());
                hold(opened);
            } catch (IOException ex) {
                liveness.markDown(instance);
            } finally {
                closeQuietly(opened);
                if (socket == opened) {
                    socket = null;
                }
                liveness.markDown(instance);
            }
            if (!running.get() || !sleep(retryDelay)) {
                break;
            }
        }
        liveness.markDown(instance);
    }

    /**
     * 在已经建立的套接字上循环发送健康检查。任何读写失败都交给外层标记下线。
     *
     * @param opened 当前这条长连接
     * @throws IOException 对端关闭或读写失败时抛出
     */
    private void hold(Socket opened) throws IOException {
        while (running.get() && !opened.isClosed()) {
            writeHealth(opened.getOutputStream());
            readHttpResponse(opened.getInputStream());
            liveness.heartbeat(instance, Instant.now());
            if (!sleep(heartbeatInterval)) {
                return;
            }
        }
    }

    /**
     * 在当前套接字上写一次 GET /health。不写令牌，不写用户身份。
     *
     * @param out 套接字输出
     * @throws IOException 写失败时抛出
     */
    private void writeHealth(OutputStream out) throws IOException {
        String request = "GET " + HEALTH_PATH + " HTTP/1.1\r\nHost: " + instance.address()
                + "\r\nConnection: keep-alive\r\nAccept: application/json\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * 读完一次 HTTP 响应，避免下一次请求错位。没有长度也不是分块时视为对端不再保持连接。
     *
     * @param in 套接字输入
     * @throws IOException 对端关闭或响应无法消费时抛出
     */
    private static void readHttpResponse(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (true) {
            int value = in.read();
            if (value < 0) {
                throw new EOFException("agent 连接已断开");
            }
            header.write(value);
            if (matched == 0 && value == '\r') {
                matched = 1;
            } else if (matched == 1 && value == '\n') {
                matched = 2;
            } else if (matched == 2 && value == '\r') {
                matched = 3;
            } else if (matched == 3 && value == '\n') {
                break;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
            if (header.size() > 65536) {
                throw new IOException("agent 响应头过长");
            }
        }
        String text = header.toString(StandardCharsets.ISO_8859_1);
        if (chunked(text)) {
            readChunks(in);
            return;
        }
        int length = contentLength(text);
        if (length < 0) {
            throw new IOException("agent 响应没有长度");
        }
        readFully(in, length);
    }

    /**
     * 从头里取出 Content-Length。没有这个头时返回 -1。
     *
     * @param header 含结尾空行的响应头
     * @return 正文长度；没有时为 -1
     */
    private static int contentLength(String header) {
        for (String line : header.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ex) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * 判断响应是不是分块传输。
     *
     * @param header 响应头
     * @return Transfer-Encoding 含 chunked 时为真
     */
    private static boolean chunked(String header) {
        for (String line : header.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Transfer-Encoding")) {
                return line.substring(colon + 1).toLowerCase().contains("chunked");
            }
        }
        return false;
    }

    /**
     * 读完分块正文，直到长度为 0 的块。
     *
     * @param in 套接字输入
     * @throws IOException 块不完整时抛出
     */
    private static void readChunks(InputStream in) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            int semi = sizeLine.indexOf(';');
            String sizeText = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size = Integer.parseInt(sizeText, 16);
            if (size == 0) {
                readLine(in);
                return;
            }
            readFully(in, size + 2);
        }
    }

    /**
     * 读一行，不含行尾的回车换行。
     *
     * @param in 套接字输入
     * @return 一行文本
     * @throws IOException 还没读到行尾就结束时抛出
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = in.read();
            if (value < 0) {
                throw new EOFException("agent 连接已断开");
            }
            if (previous == '\r' && value == '\n') {
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            if (previous == '\r') {
                line.write('\r');
            }
            if (value != '\r') {
                line.write(value);
            }
            previous = value;
        }
    }

    /**
     * 读满指定字节数。长度为零时什么也不读。
     *
     * @param in 套接字输入
     * @param length 要读的字节数
     * @throws IOException 中途结束时抛出
     */
    private static void readFully(InputStream in, int length) throws IOException {
        int remaining = length;
        byte[] buffer = new byte[Math.min(Math.max(remaining, 1), 4096)];
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("agent 连接已断开");
            }
            remaining -= read;
        }
    }

    /**
     * 等待一段时间。线程被中断或连接已经要求停止时返回假。
     *
     * @param delay 等待时长
     * @return 等满且仍在运行时为真
     */
    private boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        return running.get();
    }

    /**
     * 关掉套接字并忽略关闭时的错误。
     *
     * @param target 要关的套接字，可以是空
     */
    private static void closeQuietly(Socket target) {
        if (target != null) {
            try {
                target.close();
            } catch (IOException ignored) {
                // 关闭失败时连接已经不可用，不需要再记录。
            }
        }
    }
}
