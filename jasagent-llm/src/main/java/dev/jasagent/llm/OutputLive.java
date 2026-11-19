package dev.jasagent.llm;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存当前这次运行的正文增量出口，让流式模型在上游结束前把正文推出去。
 * 思考仍走 {@link ThinkingLive}，正文不混进思考字段。
 */
public final class OutputLive {
    /**
     * 当前绑定的正文增量出口。空引用表示没有人在收实时正文。
     */
    private static final AtomicReference<Sink> SINK = new AtomicReference<>();

    /**
     * 禁止实例化。出口的绑定和推送都走静态方法。
     */
    private OutputLive() {
    }

    /**
     * 换成新的正文增量出口。之后的增量都交给这个出口，直到被清掉或再次绑定。
     *
     * @param sink 接收增量的出口；null 表示先关掉实时推送
     */
    public static void bind(Sink sink) {
        SINK.set(sink);
    }

    /**
     * 卸掉当前正文出口，使后续增量不再向外推送。
     */
    public static void clear() {
        SINK.set(null);
    }

    /**
     * 判断现在有没有正文出口在接收增量。
     *
     * @return 已绑定出口时为 true
     */
    public static boolean active() {
        return SINK.get() != null;
    }

    /**
     * 把一段正文增量交给当前出口。没有出口、执行编号为空或文本为空时什么都不做。
     *
     * @param executionId 这段增量所属的执行编号
     * @param text 本次新增的正文
     */
    public static void delta(String executionId, String text) {
        Sink sink = SINK.get();
        if (sink == null || executionId == null || executionId.isBlank() || text == null || text.isEmpty()) {
            return;
        }
        sink.onDelta(executionId, text);
    }

    /**
     * 接收某一执行过程中逐段到来的正文。
     */
    @FunctionalInterface
    public interface Sink {
        /**
         * 处理一段刚到的正文增量。
         *
         * @param executionId 这段增量所属的执行编号
         * @param text 本次新增的正文
         */
        void onDelta(String executionId, String text);
    }
}
