package dev.jasagent.rag;

/**
 * 语料放在哪一级目录。
 * 用户级和工作区级各有自己的 docs 和索引，检索可以只查一边或两边一起查。
 */
public enum RagScope {
    /** 当前用户目录下的语料，协议名和索引前缀是 user。 */
    USER("user"),
    /** 当前工作区目录下的语料，协议名和索引前缀是 workspace。 */
    WORKSPACE("workspace");

    private final String wire;

    /**
     * 记下这个范围写进索引和命中时使用的协议名。
     *
     * @param wire 对外写出的范围名
     */
    RagScope(String wire) {
        this.wire = wire;
    }

    /**
     * 返回写进索引行、段标识前缀和命中字段的范围名。
     *
     * @return user 或 workspace
     */
    public String wire() {
        return wire;
    }
}
