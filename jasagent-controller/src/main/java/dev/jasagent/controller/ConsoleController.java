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

/** 控制台用的会话、工作流、技能、模型和人工放行入口。身份取自过滤器放进请求的会话。 */
@RestController
public class ConsoleController {
    private final ConsoleApi console;
    private final WorkspaceList workspaces;

    /**
     * 注入控制台接口和允许选择的工作区。
     *
     * @param console 会话、模型和放行的业务接口
     * @param workspaces 当前进程允许的工作区
     */
    public ConsoleController(ConsoleApi console, WorkspaceList workspaces) {
        this.console = console;
        this.workspaces = workspaces;
    }

    /**
     * 返回当前登录名和正在使用的工作区。工作区还没选时用空串。
     *
     * @param request 带当前会话的请求
     * @return 含 login 和 workspace 的正文
     */
    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Session session = session(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", session.login());
        body.put("workspace", session.workspace() == null ? "" : session.workspace());
        return body;
    }

    /**
     * 返回当前工作区，以及允许切换的工作区路径。
     *
     * @param request 带当前会话的请求
     * @return 含当前工作区和可选路径列表的正文
     */
    @GetMapping("/api/workspaces")
    public Map<String, Object> workspaces(HttpServletRequest request) {
        Session session = session(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", session.workspace() == null ? "" : session.workspace());
        body.put("workspaces", workspaces.paths().stream().map(Path::toString).toList());
        return body;
    }

    /**
     * 列出当前用户在当前令牌下能用的工作流。
     *
     * @param request 带当前会话的请求
     * @return 工作流列表
     */
    @GetMapping("/api/workflows")
    public Object workflows(HttpServletRequest request) {
        Session session = session(request);
        return console.workflows(session.login(), session.token());
    }

    /**
     * 列出当前用户在当前令牌下能用的技能。
     *
     * @param request 带当前会话的请求
     * @return 技能列表
     */
    @GetMapping("/api/skills")
    public Object skills(HttpServletRequest request) {
        Session session = session(request);
        return console.skills(session.login(), session.token());
    }

    /**
     * 列出当前用户的会话。
     *
     * @param request 带当前会话的请求
     * @return 会话列表
     */
    @GetMapping("/api/sessions")
    public Object sessions(HttpServletRequest request) {
        return console.sessions(session(request).login());
    }

    /**
     * 为当前用户新建一个会话，标题取自正文。
     *
     * @param body 含 title 的请求正文
     * @param request 带当前会话的请求
     * @return 新建的会话
     */
    @PostMapping("/api/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, String> body, HttpServletRequest request) {
        return console.createSession(session(request).login(), body.get("title"));
    }

    /**
     * 列出当前用户可选的模型。
     *
     * @param request 带当前会话的请求
     * @return 含可选模型的正文
     */
    @GetMapping("/api/models")
    public Map<String, Object> models(HttpServletRequest request) {
        return console.models(session(request).login());
    }

    /**
     * 修改一个会话。正文里有非空模型时更新模型和供应商；正文含标题键时再改标题。两样都没有时仍按空标题走一次改名。
     *
     * @param id 会话编号
     * @param body 可含 model、modelProvider 和 title
     * @param request 带当前会话的请求
     * @return 最后一次修改后的会话
     */
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

    /**
     * 读取一个会话的详情。
     *
     * @param id 会话编号
     * @param request 带当前会话的请求
     * @return 会话详情
     */
    @GetMapping("/api/sessions/{id}")
    public Map<String, Object> session(@PathVariable("id") String id, HttpServletRequest request) {
        return console.session(session(request).login(), id);
    }

    /**
     * 删除当前用户的一个会话。
     *
     * @param id 会话编号
     * @param request 带当前会话的请求
     * @return 删除结果
     */
    @DeleteMapping("/api/sessions/{id}")
    public Map<String, Object> deleteSession(@PathVariable("id") String id, HttpServletRequest request) {
        return console.deleteSession(session(request).login(), id);
    }

    /**
     * 往会话里追加一条用户消息，并带上可选的工作流、模型和供应商。
     *
     * @param id 会话编号
     * @param body 含 text，以及可选的 workflowId、model、modelProvider
     * @param request 带当前会话的请求
     * @return 追加后的结果，通常含新的执行
     */
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

    /**
     * 读取这个会话当前拼好的上下文。
     *
     * @param id 会话编号
     * @param request 带当前会话的请求
     * @return 上下文正文
     */
    @GetMapping("/api/sessions/{id}/context")
    public Map<String, Object> context(@PathVariable("id") String id, HttpServletRequest request) {
        Session session = session(request);
        return console.context(session.login(), session.token(), id);
    }

    /**
     * 读取会话时间线。给了执行编号时只看那一次执行。
     *
     * @param id 会话编号
     * @param executionId 要筛选的执行编号，可以不传
     * @param request 带当前会话的请求
     * @return 时间线正文
     */
    @GetMapping("/api/sessions/{id}/timeline")
    public Map<String, Object> timeline(
            @PathVariable("id") String id,
            @RequestParam(value = "executionId", required = false) String executionId,
            HttpServletRequest request) {
        return console.timeline(session(request).login(), id, executionId);
    }

    /**
     * 给会话加一条纠正说明。正文没写执行编号时用空串。
     *
     * @param id 会话编号
     * @param body 含 text，以及可选的 executionId
     * @param request 带当前会话的请求
     * @return 新的纠正记录
     */
    @PostMapping("/api/sessions/{id}/corrections")
    public Map<String, Object> addCorrection(@PathVariable("id") String id, @RequestBody Map<String, String> body, HttpServletRequest request) {
        return console.addCorrection(session(request).login(), id, body.get("text"), body.getOrDefault("executionId", ""));
    }

    /**
     * 列出这个会话上的纠正说明。
     *
     * @param id 会话编号
     * @param request 带当前会话的请求
     * @return 纠正列表
     */
    @GetMapping("/api/sessions/{id}/corrections")
    public Object corrections(@PathVariable("id") String id, HttpServletRequest request) {
        return console.corrections(session(request).login(), id);
    }

    /**
     * 对一次执行上的人工放行做决定，决定值取自正文。
     *
     * @param id 执行编号
     * @param passId 放行点编号
     * @param body 含 decision 的请求正文
     * @param request 带当前会话的请求
     * @return 决定之后的放行结果
     */
    @PostMapping("/api/executions/{id}/passes/{passId}")
    public Map<String, Object> decidePass(
            @PathVariable("id") String id,
            @PathVariable("passId") String passId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        return console.decidePass(session(request).login(), id, passId, body.get("decision"));
    }

    /**
     * 取出过滤器放入请求的当前会话。
     *
     * @param request 当前 HTTP 请求
     * @return 已登录会话
     */
    private static Session session(HttpServletRequest request) {
        return (Session) request.getAttribute("jasagent.session");
    }
}
