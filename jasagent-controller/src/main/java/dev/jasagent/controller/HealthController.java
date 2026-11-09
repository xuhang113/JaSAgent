package dev.jasagent.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 报告进程和存储是否可用。存储不可达时返回 503，正文里的状态和存储都是 down。 */
@RestController
public class HealthController {
    private final StorageStatus storage;

    /**
     * 注入用来探测存储的组件。
     *
     * @param storage 存储可达性
     */
    public HealthController(StorageStatus storage) {
        this.storage = storage;
    }

    /**
     * 探测存储。可达时返回 200，status 为 ok、storage 为 up；不可达时返回 503，两者都是 down。
     *
     * @return 带状态码的健康检查正文
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean up = storage.reachable();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", up ? "ok" : "down");
        body.put("storage", up ? "up" : "down");
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
