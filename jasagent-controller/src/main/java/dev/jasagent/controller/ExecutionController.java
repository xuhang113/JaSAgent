package dev.jasagent.controller;

import dev.jasagent.api.exec.ExecutionApi;
import dev.jasagent.api.runtime.AssignedExecution;
import dev.jasagent.api.runtime.CallerScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

/** 执行、工作区切换和检索的 HTTP 入口。身份取自网关请求头，不读取登录会话。 */
@RestController
public class ExecutionController {
    private final ExecutionApi executions;
    private final WorkspaceList workspaces;

    /**
     * 注入执行接口和允许的工作区。
     *
     * @param executions 提交、查询、中断、恢复和检索
     * @param workspaces 允许选中的工作区
     */
    public ExecutionController(ExecutionApi executions, WorkspaceList workspaces) {
        this.executions = executions;
        this.workspaces = workspaces;
    }

    /**
     * 用当前用户提交一条工作流执行。正文没有 input 时按空串。响应状态是已接受。
     *
     * @param body 含 workflowId 和可选 input
     * @param request 带当前会话的请求
     * @return 202 和创建出的执行
     */
    @PostMapping("/api/executions")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> body, HttpServletRequest request) {
        CallerScope caller = caller(request);
        AssignedExecution.bind(body.get("executionId"));
        try {
            Map<String, Object> created = executions.submit(caller.userId(), "", body.get("workflowId"), body.getOrDefault("input", ""));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
        } finally {
            AssignedExecution.clear();
        }
    }

    /**
     * 列出当前用户的执行。
     *
     * @param request 带当前会话的请求
     * @return 执行列表
     */
    @GetMapping("/api/executions")
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return executions.list(caller(request).userId());
    }

    /**
     * 读取一条执行的详情。
     *
     * @param id 执行编号
     * @param request 带当前会话的请求
     * @return 执行详情
     */
    @GetMapping("/api/executions/{id}")
    public Map<String, Object> detail(@PathVariable("id") String id, HttpServletRequest request) {
        return executions.detail(caller(request).userId(), id);
    }

    /**
     * 读取这条执行的日志，包在 logs 字段里返回。
     *
     * @param id 执行编号
     * @param request 带当前会话的请求
     * @return 只含 logs 的正文
     */
    @GetMapping("/api/executions/{id}/logs")
    public Map<String, String> logs(@PathVariable("id") String id, HttpServletRequest request) {
        return Map.of("logs", executions.logs(caller(request).userId(), id));
    }

    /**
     * 中断当前用户的这条执行。容器线程只把等待交给虚拟线程就返回，不在平台线程上 join。
     *
     * @param id 执行编号
     * @param request 带当前会话的请求
     * @param response 异步写回中断后的执行
     */
    @PostMapping("/api/executions/{id}/interrupt")
    public void interrupt(@PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response) {
        String userId = caller(request).userId();
        AsyncHandoff.start(request, response, () -> executions.interrupt(userId, id));
    }

    /**
     * 恢复当前用户的这条执行。容器线程只把剩余执行交给虚拟线程就返回，不在平台线程上 join。
     *
     * @param id 执行编号
     * @param request 带当前会话的请求
     * @param response 异步写回恢复后的执行
     */
    @PostMapping("/api/executions/{id}/resume")
    public void resume(@PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response) {
        String userId = caller(request).userId();
        AsyncHandoff.start(request, response, () -> executions.resume(userId, id));
    }

    /**
     * 用服务器推送这条执行的状态。虚拟线程大约每 200 毫秒拉一次详情，状态、日志、输出或思考变化时发送 status；思考内容变化时再发送 thinking。到达终态后结束流。连接关闭、超时或出错时停止轮询。
     *
     * @param id 执行编号
     * @param request 带当前会话的请求
     * @return 不超时的事件流
     */
    @GetMapping(value = "/api/executions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable("id") String id, HttpServletRequest request) {
        String userId = caller(request).userId();
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(ignored -> open.set(false));
        CallerCarry.Snapshot caller = CallerCarry.capture();
        Thread.ofVirtual().name(AsyncHandoff.THREAD, 0).start(() -> caller.run(() -> {
            try {
                String previous = "";
                String previousThinking = null;
                while (open.get()) {
                    Map<String, Object> detail = executions.detail(userId, id);
                    String status = String.valueOf(detail.get("status"));
                    String thinking = detail.get("thinking") == null ? "" : String.valueOf(detail.get("thinking"));
                    String signature = status + "\n" + detail.get("logs") + "\n" + detail.get("output") + "\n" + thinking;
                    if (!signature.equals(previous)) {
                        emitter.send(SseEmitter.event().name("status").data(detail));
                        if (previousThinking == null || !thinking.equals(previousThinking)) {
                            if (!thinking.isEmpty() || (previousThinking != null && !previousThinking.isEmpty())) {
                                emitter.send(SseEmitter.event().name("thinking").data(thinking));
                            }
                            previousThinking = thinking;
                        }
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
        }));
        return emitter;
    }

    /**
     * 判断状态是否不必再轮询。成功、失败、取消、挂起和离线都算终态。
     *
     * @param status 执行状态名
     * @return 属于上述终态时为真
     */
    private static boolean terminal(String status) {
        return Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SUSPENDED", "OFFLINE").contains(status);
    }

    /**
     * 把当前会话的工作区改成正文里的路径，路径必须落在允许列表里。
     *
     * @param body 含 path 的请求正文
     * @param request 带当前会话的请求
     * @return 只含更新后工作区的正文
     */
    @PostMapping("/api/workspace")
    public Map<String, String> workspace(@RequestBody Map<String, String> body, HttpServletRequest request) {
        CallerScope caller = caller(request);
        List<String> allowed = workspaces.paths().stream().map(Path::toString).toList();
        return executions.bindWorkspace(caller.userId(), body.get("path"), allowed);
    }

    /**
     * 按正文里的查询词检索。scopes 是列表时逐项转成字符串，否则同时查用户和当前工作区。
     *
     * @param body 含 query，以及可选的 scopes
     * @param request 带当前会话的请求
     * @return 检索结果
     */
    @PostMapping("/api/rag/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        CallerScope caller = caller(request);
        @SuppressWarnings("unchecked")
        List<String> scopes = body.get("scopes") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of("user", "workspace");
        return executions.search(caller.userId(), "", String.valueOf(body.get("query")), scopes);
    }

    /**
     * 取出过滤器放入请求的网关身份。
     *
     * @param request 当前 HTTP 请求
     * @return 租户、用户和会话
     */
    private static CallerScope caller(HttpServletRequest request) {
        return GatewayRequests.require(request);
    }
}
