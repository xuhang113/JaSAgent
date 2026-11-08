package dev.jasagent.rag;

public enum RagScope {
    USER("user"),
    WORKSPACE("workspace");

    private final String wire;

    RagScope(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
