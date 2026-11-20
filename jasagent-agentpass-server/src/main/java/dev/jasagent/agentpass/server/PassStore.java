package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.durable.AgentPassLedger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 一次性证登记。配置了账本时，核销状态写进已有 MySQL，重启后仍然在。
 * 没有账本的构造只留在进程内存里，给仍要直接构造服务的测试使用。
 * 拒绝发证时不会写入。过期记录继续留着，用来区分“过期”和“已经用过”。
 */
public final class PassStore {
    private final ConcurrentHashMap<String, PassState> records;
    private final AgentPassLedger ledger;

    /**
     * 建立只活在本进程里的登记。进程退出后记录消失。
     */
    public PassStore() {
        this.records = new ConcurrentHashMap<>();
        this.ledger = null;
    }

    /**
     * 建立落到已有库的登记。同一库上的下一个进程还能看见这些证。
     *
     * @param ledger 已建表的通行证账本，不能为空
     */
    public PassStore(AgentPassLedger ledger) {
        if (ledger == null) {
            throw new IllegalStateException("agentpass store");
        }
        this.records = null;
        this.ledger = ledger;
    }

    /**
     * 放入一张新签发的证。编号重复时覆盖旧记录；签发方使用新的随机编号，正常不会重复。
     * 副作用：内存映射或证状态表增加或改写一行。
     *
     * @param state 尚未使用的通行证状态
     */
    public void save(PassState state) {
        if (ledger == null) {
            records.put(state.passId(), state);
            return;
        }
        ledger.saveTicket(state.passId(), state.tenant(), state.user(), state.sessionId(), state.executionId(),
                state.targetService(), state.toolName(), state.expiresAt(), state.used());
    }

    /**
     * 按编号查找证。没有签发过时返回空。
     *
     * @param passId 通行证编号
     * @return 已保存的状态；不存在时为空
     */
    public PassState find(String passId) {
        if (ledger == null) {
            return records.get(passId);
        }
        return state(ledger.findTicket(passId));
    }

    /**
     * 原子地读取并可能替换一张证。计算函数返回空表示删除该编号。
     * 账本模式下用行锁，避免两张并发核销都成功。
     * 副作用只发生在计算函数写回的新状态上。
     *
     * @param passId 通行证编号
     * @param updater 根据当前状态算出下一个状态；当前状态为空表示还没有这张证
     * @return 计算之后的状态；被删除或不存在时为空
     */
    public PassState compute(String passId, BiFunction<String, PassState, PassState> updater) {
        if (ledger == null) {
            return records.compute(passId, updater);
        }
        AgentPassLedger.StoredTicket stored = ledger.computeTicket(passId, (id, current) -> ticket(updater.apply(id, state(current))));
        return state(stored);
    }

    /**
     * 返回当前保存的通行证张数，包括已核销和已过期的。
     *
     * @return 内存或库里的记录数
     */
    public int size() {
        if (ledger == null) {
            return records.size();
        }
        return ledger.countTickets();
    }

    /**
     * 把库里的一行还原成服务使用的状态。没有行时为空。
     *
     * @param ticket 账本中的证
     * @return 通行证状态；没有时为空
     */
    private static PassState state(AgentPassLedger.StoredTicket ticket) {
        if (ticket == null) {
            return null;
        }
        return new PassState(ticket.passId(), ticket.tenant(), ticket.user(), ticket.sessionId(), ticket.executionId(),
                ticket.target(), ticket.toolName(), ticket.expiresAt(), ticket.used());
    }

    /**
     * 把服务里的状态收成账本要写回的证。计算函数返回空时保持为空，表示删除。
     *
     * @param state 计算之后的状态
     * @return 账本中的证；要删除时为空
     */
    private static AgentPassLedger.StoredTicket ticket(PassState state) {
        if (state == null) {
            return null;
        }
        return new AgentPassLedger.StoredTicket(state.passId(), state.tenant(), state.user(), state.sessionId(),
                state.executionId(), state.targetService(), state.toolName(), state.expiresAt(), state.used());
    }
}
