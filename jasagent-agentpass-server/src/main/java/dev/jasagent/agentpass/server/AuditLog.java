package dev.jasagent.agentpass.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内追加的通行证流水。查询返回副本，调用方改不了已经记下的事件。
 * 日志只打种类、编号和原因，避免登录令牌或证明文进入日志。
 */
public final class AuditLog {
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    /**
     * 从调用方抄来的租户、用户、目标和原因最多保留这么多字符，避免超长输入撑满内存。
     */
    private static final int CLIP = 128;

    private final Clock clock;
    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    /**
     * 用给定时钟给每条流水盖时间。测试可以换一只可拨动的钟。
     *
     * @param clock 取当前时刻的时钟，不能为空
     */
    public AuditLog(Clock clock) {
        this.clock = clock;
    }

    /**
     * 追加一条流水，并写一行不含凭证的信息日志。
     * 副作用是列表变长，以及向日志输出种类、编号和原因。
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
        events.add(new AuditEvent(kind, passId, clip(tenant), clip(user), clip(executionId),
                clip(targetService), clip(toolName), clip(reason), at));
        log.info("pass audit kind={} passId={} reason={}", kind, passId == null ? "" : passId, clip(reason));
    }

    /**
     * 按写入顺序返回全部流水的副本。
     *
     * @return 当前已经记下的事件，调用方持有的列表与内部列表无关
     */
    public List<AuditEvent> all() {
        return List.copyOf(events);
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
