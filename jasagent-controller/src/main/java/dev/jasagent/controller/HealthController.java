package dev.jasagent.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    private final StorageStatus storage;

    public HealthController(StorageStatus storage) {
        this.storage = storage;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean up = storage.reachable();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", up ? "ok" : "down");
        body.put("storage", up ? "up" : "down");
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
