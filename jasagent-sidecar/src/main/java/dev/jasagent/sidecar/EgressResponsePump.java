package dev.jasagent.sidecar;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 把上游正文抄回调用方。抄写跑在虚拟线程上，只在 {@link ServletOutputStream#isReady()} 为真时写入。
 * 套接字暂时写不进去时，在 {@link ReentrantLock} 上等 {@link WriteListener}，不进入 Tomcat 写锁的
 * {@code synchronized}，因此不会把载体线程钉死，也不占用 Tomcat 平台工作线程干等。
 * 每次只保留一块，写出去之前不把整段响应收进内存。
 */
final class EgressResponsePump {
    /**
     * 从上游读、再写给调用方的块大小。只保留这一块。
     */
    static final int CHUNK = 8192;

    private final HttpServletResponse response;
    private final InputStream body;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition writable = lock.newCondition();
    private volatile boolean failed;

    /**
     * 绑定要写回的响应和上游正文。此时还不读正文，也不写响应。
     *
     * @param response 已经设好状态和类型、尚未提交正文的响应
     * @param body 上游或拒绝 JSON 的正文流
     */
    EgressResponsePump(HttpServletResponse response, InputStream body) {
        this.response = response;
        this.body = body;
    }

    /**
     * 按块抄完正文。每块写完并刷出后才读下一块，调用方不用等上游结束就能看到第一块。
     * 阻塞只发生在上游读取和“套接字可写”的条件上，这两处都不占用 Tomcat 平台工作线程。
     *
     * @throws IOException 调用方断开或写出失败时抛出，消息不含正文和地址
     */
    void copy() throws IOException {
        byte[] buf = new byte[CHUNK];
        int read;
        ServletOutputStream out = null;
        boolean nonBlocking = false;
        while ((read = body.read(buf)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (out == null) {
                out = response.getOutputStream();
                nonBlocking = arm(out);
            }
            if (nonBlocking) {
                writeChunk(out, buf, read);
            } else {
                out.write(buf, 0, read);
                response.flushBuffer();
            }
        }
    }

    /**
     * 打开非阻塞写出。Tomcat 会接受监听器，抄写因此不会进入会钉住载体的阻塞写。
     * 内存里的测试响应不支持监听器，这时退回按块写；那条路径没有真实套接字，也不会占用 Tomcat 平台线程。
     *
     * @param out 响应输出流
     * @return 已经切到非阻塞写出时为真
     */
    private boolean arm(ServletOutputStream out) {
        try {
            out.setWriteListener(listener());
            return true;
        } catch (UnsupportedOperationException ex) {
            return false;
        }
    }

    /**
     * 套接字可写时通知正在抄写的虚拟线程。回调本身不写字节，好马上把容器线程还回去。
     *
     * @return 只负责唤醒的监听器
     */
    private WriteListener listener() {
        return new WriteListener() {
            /**
             * 套接字可以继续写。唤醒抄写线程，由它在虚拟线程上写下一块。
             *
             * @throws IOException 本监听器不写数据，不会抛出
             */
            @Override
            public void onWritePossible() throws IOException {
                signal();
            }

            /**
             * 写出失败。只记住失败，不保存异常消息。
             *
             * @param throwable 容器给出的错误
             */
            @Override
            public void onError(Throwable throwable) {
                failed = true;
                signal();
            }
        };
    }

    /**
     * 唤醒等在 {@link #awaitReady(ServletOutputStream)} 里的抄写线程。
     */
    private void signal() {
        lock.lock();
        try {
            writable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 写出一块并刷到调用方。块是复制出来的，避免非阻塞写出还没完成时缓冲区被下一次读取盖掉。
     *
     * @param out 响应输出流
     * @param buf 刚从上游读到的缓冲区，方法返回后可以复用
     * @param len 这块的有效长度
     * @throws IOException 写出或刷出失败时抛出
     */
    private void writeChunk(ServletOutputStream out, byte[] buf, int len) throws IOException {
        byte[] chunk = Arrays.copyOf(buf, len);
        awaitReady(out);
        out.write(chunk);
        awaitReady(out);
        response.flushBuffer();
    }

    /**
     * 等到可以写而不阻塞。{@code isReady} 为假时容器会在套接字恢复后调用监听器。
     * 等待发生在条件变量上，虚拟线程会卸下载体，不进入 Tomcat 的 {@code synchronized} 写锁。
     *
     * @param out 响应输出流
     * @throws IOException 写出已经失败或等待被中断时抛出
     */
    private void awaitReady(ServletOutputStream out) throws IOException {
        lock.lock();
        try {
            while (!out.isReady()) {
                if (failed) {
                    throw new IOException("response write failed");
                }
                writable.await();
            }
            if (failed) {
                throw new IOException("response write failed");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("response write interrupted");
        } finally {
            lock.unlock();
        }
    }
}
