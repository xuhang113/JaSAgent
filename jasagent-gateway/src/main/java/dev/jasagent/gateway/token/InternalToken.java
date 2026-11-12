package dev.jasagent.gateway.token;

import dev.jasagent.gateway.route.AgentCallHeaders;

import java.util.Map;

/**
 * 网关与 agent 之间的内部令牌。这不是用户登录口令，也不写进仓库或日志。
 * 值只来自环境变量 {@link AgentCallHeaders#TOKEN_ENV}。没有该变量时视为未配置，不另造一份令牌。
 */
public final class InternalToken {
    private final String secret;

    /**
     * 记住已经去掉空白的令牌。空串表示未配置。
     *
     * @param secret 令牌原文；空白会收成空串
     */
    private InternalToken(String secret) {
        this.secret = secret == null ? "" : secret.trim();
    }

    /**
     * 用调用方已经准备好的令牌构造。测试用假令牌走这里，生产走 {@link #fromEnvironment()}。
     *
     * @param secret 令牌原文
     * @return 对应的令牌对象
     */
    public static InternalToken of(String secret) {
        return new InternalToken(secret);
    }

    /**
     * 从进程环境读取 {@link AgentCallHeaders#TOKEN_ENV}。变量缺失或只有空白时，得到未配置的令牌。
     *
     * @return 环境中的令牌；没有时未配置
     */
    public static InternalToken fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * 从给定的环境映射读取令牌。方便测试注入一份假环境，而不打印真实进程里的值。
     *
     * @param env 环境变量名到值；空映射当作没有配置
     * @return 读到的令牌；没有时未配置
     */
    public static InternalToken fromEnvironment(Map<String, String> env) {
        if (env == null) {
            return new InternalToken("");
        }
        return new InternalToken(env.get(AgentCallHeaders.TOKEN_ENV));
    }

    /**
     * 环境变量里是否有非空白令牌。
     *
     * @return 有令牌时为真
     */
    public boolean configured() {
        return !secret.isEmpty();
    }

    /**
     * 发给 agent 的令牌原文。只应放进 {@link AgentCallHeaders#TOKEN}，不能写入日志、异常或响应。
     *
     * @return 令牌原文；未配置时是空串
     */
    public String headerValue() {
        return secret;
    }

    /**
     * 返回不含令牌原文的说明，避免调试输出把令牌带出去。
     *
     * @return 只标明是否已配置
     */
    @Override
    public String toString() {
        return "InternalToken{configured=" + configured() + "}";
    }
}
