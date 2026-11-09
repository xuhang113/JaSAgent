package dev.jasagent.controller;

import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exec.ConsoleApi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ConsoleController {
    private final ConsoleApi console;
    private final WorkspaceList workspaces;

    public ConsoleController(ConsoleApi console, WorkspaceList workspaces) {
        this.console = console;
        this.workspaces = workspaces;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Session session = session(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", session.login());
        body.put("workspace", session.workspace() == null ? "" : session.workspace());
        return body;
    }

    @GetMapping("/api/workspaces")
    public Map<String, Object> workspaces(HttpServletRequest request) {
        Session session = session(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", session.workspace() == null ? "" : session.workspace());
        body.put("workspaces", workspaces.paths().stream().map(Path::toString).toList());
        return body;
    }

    @GetMapping("/api/workflows")
    public Object workflows(HttpServletRequest request) {
        Session session = session(request);
        return console.workflows(session.login(), session.token());
    }

    @GetMapping("/api/skills")
    public Object skills(HttpServletRequest request) {
        Session session = session(request);
        return console.skills(session.login(), session.token());
    }

    @GetMapping("/api/sessions")
    public Object sessions(HttpServletRequest request) {
        return console.sessions(session(request).login());
    }

    @PostMapping("/api/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, String> body, HttpServletRequest request) {
        return console.createSession(session(request).login(), body.get("title"));
    }

    @GetMapping("/api/models")
    public Map<String, Object> models(HttpServletRequest request) {
        return console.models(session(request).login());
    }

    @PatchMapping("/api/sessions/{id}")
    public Map<String, Object> patchSession(@PathVariable("id") String id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        String login = session(request).login();
        boolean hasModel = body.get("model") != null && !body.get("model").isBlank();
        boolean hasTitle = body.containsKey("title");
        Map<String, Object> result = null;
        if (hasModel) {
            result = console.updateModel(login, id, body.get("model"), body.get("modelProvider"));
        }
        if (hasTitle) {
            result = console.renameSession(login, id, body.get("title"));
        }
        if (result == null) {
            return console.renameSession(login, id, null);
        }
        return result;
    }

    @GetMapping("/api/sessions/{id}")
    public Map<String, Object> session(@PathVariable("id") String id, HttpServletRequest request) {
        return console.session(session(request).login(), id);
    }

    @DeleteMapping("/api/sessions/{id}")
    public Map<String, Object> deleteSession(@PathVariable("id") String id, HttpServletRequest request) {
        return console.deleteSession(session(request).login(), id);
    }

    @PostMapping("/api/sessions/{id}/messages")
    public Map<String, Object> message(@PathVariable("id") String id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        Session session = session(request);
        return console.appendMessage(
                session.login(),
                session.token(),
                id,
                body.get("text"),
                body.get("workflowId"),
                body.get("model"),
                body.get("modelProvider"));
    }

    @GetMapping("/api/sessions/{id}/context")
    public Map<String, Object> context(@PathVariable("id") String id, HttpServletRequest request) {
        Session session = session(request);
        return console.context(session.login(), session.token(), id);
    }

    @GetMapping("/api/sessions/{id}/timeline")
    public Map<String, Object> timeline(
            @PathVariable("id") String id,
            @RequestParam(value = "executionId", required = false) String executionId,
            HttpServletRequest request) {
        return console.timeline(session(request).login(), id, executionId);
    }

    @PostMapping("/api/sessions/{id}/corrections")
    public Map<String, Object> addCorrection(@PathVariable("id") String id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        return console.addCorrection(session(request).login(), id, body.get("text"), body.getOrDefault("executionId", ""));
    }

    @GetMapping("/api/sessions/{id}/corrections")
    public Object corrections(@PathVariable("id") String id, HttpServletRequest request) {
        return console.corrections(session(request).login(), id);
    }

    @PostMapping("/api/executions/{id}/passes/{passId}")
    public Map<String, Object> decidePass(
            @PathVariable("id") String id,
            @PathVariable("passId") String passId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        return console.decidePass(session(request).login(), id, passId, body.get("decision"));
    }

    private static Session session(HttpServletRequest request) {
        return (Session) request.getAttribute("jasagent.session");
    }
}
