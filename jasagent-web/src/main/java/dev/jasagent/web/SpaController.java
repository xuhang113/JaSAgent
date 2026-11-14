package dev.jasagent.web;

/**
 * agent 不再提供自己的控制台页面。页面由 {@code dev.jasagent.web.upstream} 单独进程提供，网关只做反向代理。
 * 本类不是控制器，agent 也不会扫描这个包。
 */
public final class SpaController {
    /**
     * 禁止实例化。这个类型不再注册为页面控制器。
     */
    private SpaController() {
    }
}
