package dev.jasagent.gateway.proxy;

import dev.jasagent.persist.SessionLedger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在转发 {@code /api/sessions/{id}} 之前，用 {@code agent_session} 判断当前用户是不是所有者。
 * 不另写一份会话账本。库里没有这场会话时允许转给当前用户自己的 agent；库里有、但不是他的，就拒绝。
 */
public final class ChatSessionGate {
    /**
     * 某一场会话及其子路径。第二段是会话编号。列表和新建没有这一段。
     */
    private static final Pattern SESSION = Pattern.compile("^/api/sessions/([^/]+)(?:/.*)?$");

    /**
     * 禁止实例化。判断只通过静态方法使用。
     */
    private ChatSessionGate() {
    }

    /**
     * 这条路径是不是某一场已有编号的会话，而不是列表或新建。
     *
     * @param path 请求路径
     * @return 带会话编号时为真
     */
    public static boolean targetsSession(String path) {
        return sessionId(path) != null;
    }

    /**
     * 取出路径上的会话编号。列表、新建和空段都没有编号。
     *
     * @param path 请求路径
     * @return 会话编号；不是某一场会话时为空
     */
    public static String sessionId(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = SESSION.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        String id = matcher.group(1);
        if (id == null || id.isBlank()) {
            return null;
        }
        return id;
    }

    /**
     * 库里已经有这场会话，且没有任何一行同时属于当前租户和当前用户。
     * 空列表表示库里还没有这场会话，不算他人的会话。
     *
     * @param rows {@link SessionLedger#findBySessionId(String)} 的结果
     * @param tenantId 当前用户的租户
     * @param userId 当前用户的登录名
     * @return 应在转发前拒绝时为真
     */
    public static boolean foreign(List<SessionLedger.Row> rows, String tenantId, String userId) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        for (SessionLedger.Row row : rows) {
            if (row.tenantId() != null && row.tenantId().equals(tenantId) && row.userId() != null && row.userId().equals(userId)) {
                return false;
            }
        }
        return true;
    }
}
