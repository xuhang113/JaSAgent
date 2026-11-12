package dev.jasagent.controller;

/**
 * agent 不再提供登录接口。口令校验仍留在认证模块，由本机命令使用，不从这里签发 Cookie。
 */
public final class LoginController {
    /**
     * 禁止实例化。这个类型不再注册为控制器。
     */
    private LoginController() {
    }
}
