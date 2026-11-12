package dev.jasagent.agentpass.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 本进程内的一次性证登记。核销用映射的原子计算完成，避免两张并发请求都通过。
 * 拒绝发证时不会写入。过期记录继续留着，用来区分“过期”和“已经用过”。
 */
public final class PassStore {
    private final ConcurrentHashMap<String, PassState> records = new ConcurrentHashMap<>();

    /**
     * 放入一张新签发的证。编号重复时覆盖旧记录；签发方使用新的随机编号，正常不会重复。
     *
     * @param state 尚未使用的通行证状态
     */
    public void save(PassState state) {
        records.put(state.passId(), state);
    }

    /**
     * 按编号查找证。没有签发过时返回空。
     *
     * @param passId 通行证编号
     * @return 已保存的状态；不存在时为空
     */
    public PassState find(String passId) {
        return records.get(passId);
    }

    /**
     * 原子地读取并可能替换一张证。计算函数返回空表示删除该编号。
     * 副作用只发生在计算函数写回的新状态上，调用方用它完成“只有第一次核销成功”。
     *
     * @param passId 通行证编号
     * @param updater 根据当前状态算出下一个状态；当前状态为空表示还没有这张证
     * @return 计算之后映射里的状态；被删除或不存在时为空
     */
    public PassState compute(String passId, BiFunction<String, PassState, PassState> updater) {
        return records.compute(passId, updater);
    }

    /**
     * 返回当前保存的通行证张数，包括已核销和已过期的。
     *
     * @return 内存里的记录数
     */
    public int size() {
        return records.size();
    }
}
