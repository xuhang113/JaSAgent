package dev.jasagent.api.auth;

import java.time.Instant;

/**
 * 一次已登录会话。令牌用来后续鉴权，工作区在用户选定之前为空。
 *
 * @param sessionId 会话编号，同时作为会话文件名
 * @param token 随机生成的登录令牌，后续请求靠它找到会话
 * @param login 登录名
 * @param workspace 已选工作区的绝对路径；登录后尚未选择时为 null
 * @param createdAt 会话创建时间
 */
public record Session(String sessionId, String token, String login, String workspace, Instant createdAt) {
}
