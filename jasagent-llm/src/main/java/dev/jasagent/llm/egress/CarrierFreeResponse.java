package dev.jasagent.llm.egress;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * agent 侧响应体订阅器。JDK 默认响应体处理器的 {@code read} 在 {@code synchronized} 里等下一块，
 * 虚拟线程会把载体钉死。这里只把已经到达的一块放进队列，读取方在队列上等待，等待本身不进入 {@code synchronized}。
 * 队列里最多留一块数据和结束标记，不把整段响应收进内存。
 */
final class CarrierFreeResponse {
    /**
     * 队列容量。一块尚未读走的数据，再加一个结束标记。
     */
    private static final int WINDOW = 2;
    /**
     * 正文结束标记。靠对象身份区分，不能当成真实的空块。
     */
    private static final byte[] END = new byte[0];

    /**
     * 禁止实例化。订阅器通过 {@link #handler()} 创建。
     */
    private CarrierFreeResponse() {
    }

    /**
     * 给 JDK 客户端的响应体处理器。响应头到达就把流交回去，正文稍后按块到达。
     *
     * @return 不把整段正文收进内存的处理器
     */
    static HttpResponse.BodyHandler<InputStream> handler() {
        return info -> new Subscriber();
    }

    /**
     * 把本次 {@code onNext} 里的缓冲区复制出来。客户端会在回调返回后复用原来的缓冲区。
     *
     * @param item 这一块的缓冲区，可以是空
     * @return 调用方可以保留的字节；没有数据时是空数组
     */
    private static byte[] copy(List<ByteBuffer> item) {
        if (item == null || item.isEmpty()) {
            return new byte[0];
        }
        int size = 0;
        for (ByteBuffer buffer : item) {
            if (buffer != null) {
                size += buffer.remaining();
            }
        }
        if (size == 0) {
            return new byte[0];
        }
        byte[] data = new byte[size];
        int offset = 0;
        for (ByteBuffer buffer : item) {
            if (buffer == null || !buffer.hasRemaining()) {
                continue;
            }
            int length = buffer.remaining();
            buffer.get(data, offset, length);
            offset += length;
        }
        return data;
    }

    /**
     * 接收上游块并放进 {@link Stream}。回调里不阻塞，避免占住客户端线程。
     */
    private static final class Subscriber implements HttpResponse.BodySubscriber<InputStream> {
        private final Stream stream = new Stream();
        private final CompletableFuture<InputStream> body = new CompletableFuture<>();

        /**
         * 响应头到达后完成。此时流里可能还没有字节，调用方读的时候再等。
         *
         * @return 上游正文流
         */
        @Override
        public CompletionStage<InputStream> getBody() {
            return body;
        }

        /**
         * 记下订阅并只要一块。随后的块由读取方消化完当前块之后再要。
         *
         * @param subscription 上游正文订阅
         */
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (subscription == null) {
                body.completeExceptionally(new IOException("upstream body has no subscription"));
                return;
            }
            stream.subscription.set(subscription);
            body.complete(stream);
            subscription.request(1);
        }

        /**
         * 复制这一块并放入队列。队列有空位时 {@code offer} 不会等待；放不进去就取消订阅。
         *
         * @param item 这一块的缓冲区
         */
        @Override
        public void onNext(List<ByteBuffer> item) {
            byte[] data = copy(item);
            Flow.Subscription subscription = stream.subscription.get();
            if (data.length == 0) {
                if (subscription != null && !stream.finished.get()) {
                    subscription.request(1);
                }
                return;
            }
            if (!stream.queue.offer(data)) {
                stream.fail();
                if (subscription != null) {
                    subscription.cancel();
                }
            }
        }

        /**
         * 上游读失败。不保留异常消息，消息里可能带有地址。
         *
         * @param throwable 客户端给出的错误
         */
        @Override
        public void onError(Throwable throwable) {
            stream.fail();
        }

        /**
         * 上游正文结束。再放入结束标记，让正在读的一方返回。
         */
        @Override
        public void onComplete() {
            stream.finish();
        }
    }

    /**
     * 按块读上游正文。{@code read} 不是 {@code synchronized}，阻塞发生在队列的锁上，虚拟线程可以卸下载体。
     */
    private static final class Stream extends InputStream {
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(WINDOW);
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private byte[] current;
        private int offset;
        private boolean closed;

        /**
         * 读一个字节。没有更多数据时返回 -1。
         *
         * @return 0 到 255 的字节；结束时为 -1
         * @throws IOException 上游取消或读取被中断时抛出，消息不含地址和正文
         */
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        /**
         * 尽量填满调用方的缓冲区。当前块读完才向客户端要下一块，窗口里不会堆整段正文。
         *
         * @param buf 调用方缓冲区
         * @param off 写入起点
         * @param len 最多写入的字节数
         * @return 实际写入的字节数；结束时为 -1
         * @throws IOException 上游取消或读取被中断时抛出，消息不含地址和正文
         */
        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (current == null || offset >= current.length) {
                byte[] next = take();
                if (next == null) {
                    return -1;
                }
                current = next;
                offset = 0;
            }
            int count = Math.min(len, current.length - offset);
            System.arraycopy(current, offset, buf, off, count);
            offset += count;
            if (offset >= current.length) {
                current = null;
                requestMore();
            }
            return count;
        }

        /**
         * 取消订阅并唤醒正在等下一块的读取方。重复关闭没有额外作用。
         */
        @Override
        public void close() {
            closed = true;
            finished.set(true);
            Flow.Subscription currentSubscription = subscription.getAndSet(null);
            if (currentSubscription != null) {
                currentSubscription.cancel();
            }
            signal(END);
        }

        /**
         * 从队列取出下一块。结束标记返回空，让 {@link #read(byte[], int, int)} 返回 -1。
         *
         * @return 下一块；正文结束时为空
         * @throws IOException 读取被中断或正文窗口溢出时抛出
         */
        private byte[] take() throws IOException {
            if (closed && queue.isEmpty()) {
                return null;
            }
            try {
                byte[] item = queue.take();
                if (item == END) {
                    if (failed.get()) {
                        throw new IOException("upstream body failed");
                    }
                    return null;
                }
                return item;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("upstream read interrupted");
            }
        }

        /**
         * 当前块已经交给调用方之后，再向客户端要一块。结束或关闭后不再要。
         */
        private void requestMore() {
            if (finished.get() || closed) {
                return;
            }
            Flow.Subscription currentSubscription = subscription.get();
            if (currentSubscription != null) {
                currentSubscription.request(1);
            }
        }

        /**
         * 正常结束。结束标记放不进队列时先丢掉窗口里尚未读的块，避免读取方停在 {@code take}。
         */
        private void finish() {
            finished.set(true);
            signal(END);
        }

        /**
         * 失败结束。不把原始异常存下来，避免以后打日志时带出地址。
         */
        private void fail() {
            failed.set(true);
            finished.set(true);
            signal(END);
        }

        /**
         * 放入结束标记。窗口满时丢掉一块旧数据再试，保证读取方一定能醒来。
         *
         * @param item 结束标记
         */
        private void signal(byte[] item) {
            while (!queue.offer(item)) {
                queue.poll();
            }
        }
    }
}
