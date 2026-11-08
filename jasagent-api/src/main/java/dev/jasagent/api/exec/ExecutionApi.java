package dev.jasagent.api.exec;

import java.util.List;
import java.util.Map;

public interface ExecutionApi {
    Map<String, Object> submit(String userId, String token, String workflowId, String input);

    List<Map<String, Object>> list(String userId);

    Map<String, Object> detail(String userId, String executionId);

    String logs(String userId, String executionId);

    Map<String, Object> interrupt(String userId, String executionId);

    Map<String, Object> resume(String userId, String executionId);

    Map<String, Object> search(String userId, String token, String query, List<String> scopes);
}
