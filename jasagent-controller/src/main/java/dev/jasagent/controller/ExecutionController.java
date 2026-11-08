package dev.jasagent.controller;

import dev.jasagent.api.auth.AuthService;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exec.ExecutionApi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class ExecutionController {
    private final ExecutionApi executions;
    private final AuthService auth;
    private final WorkspaceList workspaces;

    public ExecutionController(ExecutionApi executions, AuthService auth, WorkspaceList workspaces) {
        this.executions = executions;
        this.auth = auth;
        this.workspaces = workspaces;
    }

    @PostMapping("/api/executions")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Session session = session(request);
        Map<String, Object> created = executions.submit(session.login(), session.token(), body.get("workflowId"), body.getOrDefault("input", ""));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @GetMapping("/api/executions")
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return executions.list(session(request).login());
    }

    @GetMapping("/api/executions/{id}")
    public Map<String, Object> detail(@PathVariable("id") String id, HttpServletRequest request) {
        return executions.detail(session(request).login(), id);
    }

    @GetMapping("/api/executions/{id}/logs")
    public Map<String, String> logs(@PathVariable("id") String id, HttpServletRequest request) {
        return Map.of("logs", executions.logs(session(request).login(), id));
    }

    @PostMapping("/api/executions/{id}/interrupt")
    public Map<String, Object> interrupt(@PathVariable("id") String id, HttpServletRequest request) {
        return executions.interrupt(session(request).login(), id);
    }

    @PostMapping("/api/executions/{id}/resume")
    public Map<String, Object> resume(@PathVariable("id") String id, HttpServletRequest request) {
        return executions.resume(session(request).login(), id);
    }

    @GetMapping(value = "/api/executions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") String id, HttpServletRequest request) {
        Session session = session(request);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        Thread.ofVirtual().start(() -> {
            try {
                String previous = "";
                while (open.get()) {
                    Map<String, Object> detail = executions.detail(session.login(), id);
                    String status = String.valueOf(detail.get("status"));
                    String signature = status + "\n" + detail.get("logs") + "\n" + detail.get("output");
                    if (!signature.equals(previous)) {
                        emitter.send(SseEmitter.event().name("status").data(detail));
                        previous = signature;
                    }
                    if (terminal(status)) {
                        break;
                    }
                    Thread.sleep(200);
                }
                emitter.complete();
            } catch (IOException | InterruptedException ex) {
                emitter.complete();
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private static boolean terminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SUSPENDED", "OFFLINE").contains(status);
    }

    @PostMapping("/api/workspace")
    public Map<String, String> workspace(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Session session = session(request);
        Session updated = auth.selectWorkspace(session.token(), Path.of(body.get("path")), workspaces.paths(), Path.of("").toAbsolutePath()).toCompletableFuture().join();
        return Map.of("workspace", updated.workspace());
    }

    @PostMapping("/api/rag/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Session session = session(request);
        @SuppressWarnings("unchecked")
        List<String> scopes = body.get("scopes") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of("user", "workspace");
        return executions.search(session.login(), session.token(), String.valueOf(body.get("query")), scopes);
    }

    private static Session session(HttpServletRequest request) {
        return (Session) request.getAttribute("jasagent.session");
    }
}
