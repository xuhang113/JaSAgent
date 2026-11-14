package dev.jasagent.web.upstream;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 把控制台的浏览器路径交到同一份 index.html。
 * 登录页和业务页都是这一个前端入口，具体画面由前端路由决定。这里没有登录接口。
 */
@Controller
public class ConsolePages {
    /**
     * 资源处理器会把这个路径对应到 classpath:/admin/index.html。
     */
    public static final String INDEX = "forward:/admin/index.html";

    /**
     * 登录页、控制台、集群和执行详情都回到单页入口。
     *
     * @return 指向 /admin/index.html 的转发
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
