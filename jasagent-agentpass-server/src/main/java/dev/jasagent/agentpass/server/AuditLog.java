package dev.jasagent.agentpass.server;

import dev.jasagent.agentpass.durable.AgentPassLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通行证流水。配置了账本时追加到已有 MySQL，查询按租户过滤。
 * 没有账本时只留在本进程。日志只打种类、编号和原因，不打登录令牌或证明文。
 */
public final class AuditLog {
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    /**
     * 从调用方抄来的租户、用户、目标和原因最多保留这么多字符，避免超长输入撑满内存或列。
     */
    private static final int CLIP = 128;

    private final Clock clock;
    private final CopyOnWriteArrayList<AuditEvent> events;
    private final AgentPassLedger ledger;

    /**
     * 用给定时钟给每条流水盖时间，记录只留在本进程。测试可以换一只可拨动的钟。
     *
     * @param clock 取当前时刻的时钟，不能为空
     */
    public AuditLog(Clock clock) {
        this(clock, null);
    }

    /**
     * 用给定时钟盖时间，并把流水追加到账本。HTTP 查询必须再按租户读取。
     *
     * @param clock 取当前时刻的时钟，不能为空
     * @param ledger 已建表的通行证账本；为空时退回进程内列表
     */
    public AuditLog(Clock clock, AgentPassLedger ledger) {
        this.clock = clock;
        this.ledger = ledger;
        this.events = ledger == null ? new CopyOnWriteArrayList<>() : null;
    }

    /**
     * 追加一条流水，并写一行不含凭证的信息日志。
     * 副作用是列表或流水表变长，以及向日志输出种类、编号和原因。
     *
     * @param kind 事件种类
     * @param passId 通行证编号；还没有证时为空
     * @param tenant 租户
     * @param user 登录名
     * @param executionId 执行编号
     * @param targetService 目标服务
     * @param toolName 工具名
     * @param reason 原因码
     */
    public void append(AuditKind kind, String passId, String tenant, String user, String executionId,
                       String targetService, String toolName, String reason) {
        Instant at = clock.instant();
        if (ledger == null) {
            events.add(new AuditEvent(kind, passId, clip(tenant), clip(user), clip(executionId),
                    clip(targetService), clip(toolName), clip(reason), at));
        } else {
            ledger.appendAudit(kind.name(), passId, tenant, user, executionId, targetService, toolName, reason, at);
        }
        log.info("pass audit kind={} passId={} reason={}", kind, passId == null ? "" : passId, clip(reason));
    }

    /**
     * 按写入顺序返回全部流水的副本。HTTP 查询不能调用它，避免把别的租户的流水交出去。
     *
     * @return 当前已经记下的事件
     */
    public List<AuditEvent> all() {
        if (ledger == null) {
            return List.copyOf(events);
        }
        return eventsOf(ledger.auditAll());
    }

    /**
     * 按写入顺序返回一个租户的流水。别的租户的行不会出现。
     *
     * @param tenantId 调用方自己的租户
     * @return 该租户的流水
     */
    public List<AuditEvent> forTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        if (ledger == null) {
            List<AuditEvent> found = new ArrayList<>();
            for (AuditEvent event : events) {
                if (tenantId.equals(event.tenant())) {
                    found.add(event);
                }
            }
            return List.copyOf(found);
        }
        return eventsOf(ledger.auditForTenant(tenantId));
    }

    /**
     * 把账本流水还原成 HTTP 使用的事件。空白的通行证编号还原成空，和拒绝发证时的记法一致。
     *
     * @param rows 账本中的流水
     * @return 事件列表
     */
    private static List<AuditEvent> eventsOf(List<AgentPassLedger.StoredAudit> rows) {
        List<AuditEvent> found = new ArrayList<>();
        for (AgentPassLedger.StoredAudit row : rows) {
            String passId = row.passId() == null || row.passId().isBlank() ? null : row.passId();
            found.add(new AuditEvent(AuditKind.valueOf(row.kind()), passId, row.tenant(), row.user(), row.executionId(),
                    row.target(), row.toolName(), row.reason(), row.at()));
        }
        return List.copyOf(found);
    }

    /**
     * 去掉换行并截断调用方给的文本，使流水里的一项不会带着多行或超长内容。
     *
     * @param value 原始文本，可以为空
     * @return 单行且长度不超过 {@link #CLIP} 的文本；空输入变成空字符串
     */
    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= CLIP ? flat : flat.substring(0, CLIP);
    }
}
