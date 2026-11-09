package dev.jasagent.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 把浏览器里的前端路径交给单页入口，由前端再决定显示登录、运行还是某次执行。
 */
@Controller
public class SpaController {
    /**
     * 把根路径、登录、运行和执行详情都转发到 index.html，不在服务端渲染页面。
     *
     * @return 指向 /index.html 的转发视图名
     */
    @GetMapping({"/", "/login", "/run", "/executions/{id}"})
    public String page() {
        return "forward:/index.html";
    }
}
