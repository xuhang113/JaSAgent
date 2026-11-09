package dev.jasagent.api.pass;

/**
 * 通行证对一次工具或插件调用的决定。目录只在允许和跳过时执行工具体。
 */
public enum PassDecision {
    /** 策略或只读规则允许执行。 */
    ALLOW,
    /** 策略拒绝执行，目录不会调用工具体。 */
    DENY,
    /** 通行证功能未启用，这次调用不经审批直接继续。 */
    SKIPPED,
    /** 没有匹配策略，等待用户允许或拒绝；执行可以因此挂起。 */
    PENDING
}
