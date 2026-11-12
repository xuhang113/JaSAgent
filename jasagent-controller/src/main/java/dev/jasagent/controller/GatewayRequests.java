package dev.jasagent.controller;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.runtime.CallerScope;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求里取出网关传来的身份。没有身份时拒绝，不回落到登录会话。
 */
public final class GatewayRequests {
    /**
     * 禁止实例化。身份从请求属性读取。
     */
    private GatewayRequests() {
    }

    /**
     * 读取过滤器放进请求的身份。
     *
     * @param request 当前 HTTP 请求
     * @return 租户、用户和会话
     */
    public static CallerScope require(HttpServletRequest request) {
        Object value = request.getAttribute(CallerScope.ATTRIBUTE);
        if (value instanceof CallerScope scope && scope.userId() != null && !scope.userId().isBlank()) {
            return scope;
        }
        throw new AgentException(ErrorCode.AUTH, "缺少网关身份");
    }
}
