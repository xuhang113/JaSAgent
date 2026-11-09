package dev.jasagent.api.exec;

import java.util.List;
import java.util.Map;

public interface ConsoleApi {
    List<Map<String, Object>> workflows(String userId, String token);

    List<Map<String, Object>> skills(String userId, String token);

    List<Map<String, Object>> sessions(String userId);

    Map<String, Object> createSession(String userId, String title);

    Map<String, Object> renameSession(String userId, String sessionId, String title);

    Map<String, Object> deleteSession(String userId, String sessionId);

    Map<String, Object> session(String userId, String sessionId);

    Map<String, Object> appendMessage(String userId, String token, String sessionId, String text, String workflowId);

    Map<String, Object> context(String userId, String token, String sessionId);

    Map<String, Object> timeline(String userId, String sessionId, String executionId);

    Map<String, Object> addCorrection(String userId, String sessionId, String text, String executionId);

    List<Map<String, Object>> corrections(String userId, String sessionId);

    Map<String, Object> decidePass(String userId, String executionId, String passId, String decision);
}
