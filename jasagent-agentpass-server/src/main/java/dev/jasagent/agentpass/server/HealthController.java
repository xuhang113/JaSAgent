package dev.jasagent.agentpass.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通行证服务的健康检查。不要求通行证，只说明进程已经起来。
 */
@RestController
public class HealthController {
    /**
     * 返回固定的正常状态。没有外部存储要探测。
     *
     * @return {@code status=ok} 且 {@code service=agentpass}
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "agentpass");
    }
}
