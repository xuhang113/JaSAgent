package dev.jasagent.web.upstream;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把控制台构建产物挂到 /admin。产物只存在于本模块，网关不复制这份文件。
 */
@Configuration
public class ConsoleResources implements WebMvcConfigurer {
    /**
     * 静态文件的匹配模式。index.html 和 assets 都从构建目录取。
     */
    public static final String RESOURCE_PATTERN = "/admin/**";

    /**
     * 登记 /admin 下的文件位置。只增加静态资源，不增加登录或业务接口。
     *
     * @param registry Spring 用来登记静态资源的注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(RESOURCE_PATTERN).addResourceLocations(WebUpstream.CLASSPATH_LOCATION);
    }
}
