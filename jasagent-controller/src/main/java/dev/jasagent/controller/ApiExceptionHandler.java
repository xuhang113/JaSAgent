package dev.jasagent.controller;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, Object>> handle(AgentException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", exception.executionId());
        body.put("errorCode", exception.code().name());
        int status = exception.code() == ErrorCode.AUTH ? 401 : 400;
        return ResponseEntity.status(status).body(body);
    }
}
