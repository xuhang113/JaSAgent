package dev.jasagent.agentpass.starter;

import java.util.List;

/**
 * 区分豁免、普通流量和代理流量。
 * <p>
 * 普通非 agent 流量要同时满足下面几条才放行，并且不访问 agentpass：
 * 请求路径不在豁免名单里时才继续判断；豁免名单默认只有 {@code /health}，命中后无论带了什么头都放行，
 * 这是给健康检查用的，不要把业务路径加进去。
 * 路径不以代理前缀开头。前缀默认是 {@code /agent/}，只按容器给出的路径做前缀匹配，
 * 所以 {@code /agent/search} 是代理流量，{@code /agent} 和 {@code /agentive} 不是。
 * 没有 {@code X-Jasagent-Pass} 头，并且 {@code X-Jasagent-Agent} 为空、{@code false}、{@code 0} 或 {@code no}。
 * <p>
 * 只要路径命中前缀，或者出现了通行证头（值为空也算），或者代理头是 {@code true}、{@code 1}、{@code yes}
 * 或其他无法识别的值，就是代理流量，必须校验并在成功后核销。agentpass 不可达时代理流量拒绝，普通流量仍然放行。
 */
public final class AgentTraffic {
    /**
     * 一种请求该怎么过过滤器。
     */
    public enum Kind {
        /**
         * 豁免路径，不读通行证，直接进入后面的处理。
         */
        EXEMPT,
        /**
         * 普通流量，不要求通行证，也不调用 agentpass。
         */
        ORDINARY,
        /**
         * 代理流量，必须带可核销的通行证，否则拒绝且不进入业务。
         */
        AGENT
    }

    /**
     * 禁止实例化。分类只通过静态方法完成。
     */
    private AgentTraffic() {
    }

    /**
     * 按路径和两个头决定请求种类。豁免优先于路径和头，避免健康检查被通行证头挡住。
     *
     * @param path 容器给出的请求路径，不含查询串
     * @param agentHeader {@code X-Jasagent-Agent} 的值，没有头时为空
     * @param passHeaderPresent 请求里是否出现了 {@code X-Jasagent-Pass}，空值也算出现
     * @param prefix 代理路径前缀
     * @param exemptPaths 精确匹配即放行的路径
     * @return 豁免、普通或代理
     */
    public static Kind classify(String path, String agentHeader, boolean passHeaderPresent, String prefix,
                                List<String> exemptPaths) {
        String normalized = path == null ? "" : path;
        if (exempt(normalized, exemptPaths)) {
            return Kind.EXEMPT;
        }
        if (prefix != null && !prefix.isEmpty() && normalized.startsWith(prefix)) {
            return Kind.AGENT;
        }
        if (passHeaderPresent) {
            return Kind.AGENT;
        }
        if (markedAgent(agentHeader)) {
            return Kind.AGENT;
        }
        return Kind.ORDINARY;
    }

    /**
     * 判断路径是不是豁免名单中的某一项。只做精确匹配，{@code /health} 不会放行 {@code /healthcheck}。
     *
     * @param path 请求路径
     * @param exemptPaths 豁免路径，可以为空
     * @return 精确命中时为真
     */
    private static boolean exempt(String path, List<String> exemptPaths) {
        if (exemptPaths == null) {
            return false;
        }
        for (String candidate : exemptPaths) {
            if (candidate != null && candidate.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断代理头是不是把请求标成代理流量。空、{@code false}、{@code 0}、{@code no} 不是；其他非空值都是，避免用陌生值绕过。
     *
     * @param value 代理头的原始值
     * @return 应视为代理流量时为真
     */
    public static boolean markedAgent(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        return !text.equalsIgnoreCase("false") && !text.equals("0") && !text.equalsIgnoreCase("no");
    }
}
