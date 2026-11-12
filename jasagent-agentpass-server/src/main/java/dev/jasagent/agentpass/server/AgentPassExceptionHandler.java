package dev.jasagent.agentpass.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把解析失败和未预料的异常收成固定正文。不把异常消息写进响应或日志，因为领证体里可能有登录令牌。
 */
@RestControllerAdvice
public class AgentPassExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentPassExceptionHandler.class);

    /**
     * 请求体不是合法 JSON 时返回 400，正文只有固定错误码。
     * 副作用是记一条不含请求体的日志。
     *
     * @param ex 解析异常，只用来触发处理，消息不会写出
     * @return 不含请求片段的错误正文
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformed(HttpMessageNotReadableException ex) {
        log.info("agentpass rejected malformed body");
        return ResponseEntity.badRequest().body(Map.of("error", "malformed"));
    }

    /**
     * 其他未捕获异常返回 500。日志只记异常类名，不记消息，避免消息里夹带凭证。
     *
     * @param ex 未预料的异常
     * @return 固定的内部错误正文
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception ex) {
        log.error("agentpass request failed: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal"));
    }
}
