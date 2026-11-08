package dev.jasagent.api.llm;

import java.util.Map;

public interface RouteTrace {
    void event(String type, Map<String, String> fields);
}
