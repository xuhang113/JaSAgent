package dev.jasagent.gateway.nacos;

/**
 * 在 Nacos 没有带回实例编号时，按和 agent 相同的规则补一个。长度不超过粘性表的 64。
 */
public final class NacosInstanceIds {
    /**
     * 粘性表 {@code instance_id} 的上限。
     */
    public static final int MAX_LENGTH = 64;

    /**
     * 禁止实例化。编号只通过 {@link #of(String, int)} 生成。
     */
    private NacosInstanceIds() {
    }

    /**
     * 用主机和端口生成稳定编号。算法与 agent 侧相同。
     *
     * @param host 实例主机
     * @param port 实例端口
     * @return 不超过 {@link #MAX_LENGTH} 的编号
     */
    public static String of(String host, int port) {
        String sanitized = host == null ? "" : host.trim().replace(':', '-').replace('.', '-');
        String raw = "agent-" + sanitized + "-" + port;
        if (raw.length() <= MAX_LENGTH) {
            return raw;
        }
        String hash = Integer.toUnsignedString(raw.hashCode(), 36);
        return raw.substring(0, MAX_LENGTH - hash.length() - 1) + "-" + hash;
    }
}
