package dev.jasagent.rag;

public record PathScore(int rank, double score) {
    public static PathScore absent() {
        return new PathScore(0, 0.0);
    }
}
