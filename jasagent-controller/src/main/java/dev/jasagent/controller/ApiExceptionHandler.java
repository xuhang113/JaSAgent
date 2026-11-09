package dev.jasagent.controller;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把业务异常收成固定的 JSON。认证错误用 401，其它用 400。正文只有执行编号和错误码，不带异常消息。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * 按异常里的错误码决定 HTTP 状态，并写出执行编号和错误码。
     *
     * @param exception 控制器抛出的业务异常
     * @return 带状态码和错误正文的响应
     */
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, Object>> handle(AgentException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", exception.executionId());
        body.put("errorCode", exception.code().name());
        int status = exception.code() == ErrorCode.AUTH ? 401 : 400;
        return ResponseEntity.status(status).body(body);
    }
}
