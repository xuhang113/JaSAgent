package dev.jasagent.gateway.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 把网关管理控制台的前端路径转发到同一份 index.html。
 * 登录、会话、集群和执行详情由前端路由决定显示什么。
 * 这里没有接口：不处理登录表单、会话或集群数据。
 * 本类不在 agent 的扫描范围内，随 {@link GatewayAdminStaticMount} 被网关导入后才生效。
 */
@Controller
public class GatewayAdminPages {
    /**
     * 转发目标。资源挂载把这个路径对应到 gateway-admin/index.html。
     */
    public static final String INDEX = "forward:/admin/index.html";

    /**
     * 把控制台的几个浏览器路径都交给单页入口。
     * 覆盖 /admin、/admin/、登录、集群、运行，以及带执行编号的详情。
     *
     * @return 指向 /admin/index.html 的转发视图名
     */
    @GetMapping({
            "/admin",
            "/admin/",
            "/admin/login",
            "/admin/cluster",
            "/admin/run",
            "/admin/executions/{id}"
    })
    public String page() {
        return INDEX;
    }
}
