package dev.jasagent.llm;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存当前这次运行的思考增量出口，让流式模型在回答结束前把思考推出去。
 */
public final class ThinkingLive {
    /**
     * 当前绑定的思考增量出口。空引用表示没有人在收实时思考。
     */
    private static final AtomicReference<Sink> SINK = new AtomicReference<>();

    /**
     * 禁止实例化。出口的绑定和推送都走静态方法。
     */
    private ThinkingLive() {
    }

    /**
     * 换成新的思考增量出口。之后的增量都交给这个出口，直到被清掉或再次绑定。
     *
     * @param sink 接收增量的出口；null 表示先关掉实时推送
     */
    public static void bind(Sink sink) {
        SINK.set(sink);
    }

    /**
     * 卸掉当前思考出口，使后续增量不再向外推送。
     */
    public static void clear() {
        SINK.set(null);
    }

    /**
     * 判断现在有没有思考出口在接收增量。
     *
     * @return 已绑定出口时为 true
     */
    public static boolean active() {
        return SINK.get() != null;
    }

    /**
     * 把一段思考增量交给当前出口。没有出口、执行编号为空或文本为空时什么都不做。
     *
     * @param executionId 这段增量所属的执行编号
     * @param text 本次新增的思考文本
     */
    public static void delta(String executionId, String text) {
        Sink sink = SINK.get();
        if (sink == null || executionId == null || executionId.isBlank() || text == null || text.isEmpty()) {
            return;
        }
        sink.onDelta(executionId, text);
    }

    /**
     * 接收某一执行过程中逐段到来的思考文本。
     */
    @FunctionalInterface
    public interface Sink {
        /**
         * 处理一段刚到的思考增量。
         *
         * @param executionId 这段增量所属的执行编号
         * @param text 本次新增的思考文本
         */
        void onDelta(String executionId, String text);
    }
}
