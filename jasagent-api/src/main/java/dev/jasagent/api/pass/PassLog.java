package dev.jasagent.api.pass;

public interface PassLog {
    void record(String executionId, String kind, String name, Pass pass);
}
