package dev.jasagent.api.llm;

public enum LlmTask {
    REACT("react"),
    COMPRESS("compress"),
    RERANK("rerank"),
    JUDGE("judge");

    private final String wireName;

    LlmTask(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
