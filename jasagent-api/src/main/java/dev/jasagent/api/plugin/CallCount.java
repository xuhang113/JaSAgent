package dev.jasagent.api.plugin;

/**
 * 某次调用统计里已经记下的成功次数和失败次数。
 * 这个名称还没有被记过时，两路都是 0。
 *
 * @param success 成功次数
 * @param failure 失败次数
 */
public record CallCount(long success, long failure) {
}
