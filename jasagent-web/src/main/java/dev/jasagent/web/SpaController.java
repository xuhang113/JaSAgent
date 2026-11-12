package dev.jasagent.web;

/**
 * agent 不再提供自己的控制台页面。浏览器路径由过滤器直接拒绝，这里不再转发到单页入口。
 */
public final class SpaController {
    /**
     * 禁止实例化。这个类型不再注册为页面控制器。
     */
    private SpaController() {
    }
}
