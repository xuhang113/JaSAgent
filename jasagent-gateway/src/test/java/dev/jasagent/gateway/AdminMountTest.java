package dev.jasagent.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制台挂在 /admin/，登录页不再转到 agent。未登录的集群接口返回 401。
 * 长连接在测试配置里关掉，因此不会拨 18731 和 18732。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminMountTest {
    @Autowired
    private MockMvc mvc;

    /**
     * 入口 /admin/ 返回已构建的控制台页面，脚本和样式仍是原来的文件名。
     */
    @Test
    void adminEntryServesTheBuiltConsole() throws Exception {
        mvc.perform(get("/admin/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/index.html"));
        mvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/assets/index-BA871a2a.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/assets/index-DIdf20W1.css")));
        mvc.perform(get("/admin/assets/index-BA871a2a.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
        mvc.perform(get("/admin/assets/index-DIdf20W1.css"))
                .andExpect(status().isOk());
    }

    /**
     * 旧的页面地址转到 /admin。登录接口拒绝未知用户，不创建账号。
     */
    @Test
    void loginPageMovesToAdminAndUnknownUserIsRejected() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/admin/"));
        mvc.perform(get("/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/admin/login"));
        mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=gateway-nobody&password=not-a-real-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AUTH")));
        mvc.perform(get("/api/cluster"))
                .andExpect(status().isUnauthorized());
    }
}
