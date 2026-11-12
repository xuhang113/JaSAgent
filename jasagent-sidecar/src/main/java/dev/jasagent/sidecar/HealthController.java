package dev.jasagent.sidecar;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活探针。只说明本进程能回答 HTTP，不访问 agentpass，也不转发。
 * 就算请求上带了通行证头，这里也不核销，避免探针把一次性证用掉。
 */
@RestController
public class HealthController {
    /**
     * 回答 {@code GET /health}。正文固定为 {@code {"status":"up"}}。
     * 副作用只有写出这个响应；不读配置里的上游，也不拨号。
     *
     * @return 200 和不缓存的 JSON
     */
    @GetMapping(EgressPaths.HEALTH)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body("{\"status\":\"up\"}");
    }
}
