package dev.jasagent.rag;

public record RagConfig(double minRerank, int rrfK, int rerankPool) {
    public static final double DEFAULT_MIN_RERANK = 0.35;
    public static final int RRF_K = 60;
    public static final int RERANK_POOL = 20;

    public static RagConfig defaults() {
        String raw = System.getProperty("jasagent.rag.min-rerank");
        double min = DEFAULT_MIN_RERANK;
        if (raw != null && !raw.isBlank()) {
            min = Double.parseDouble(raw.trim());
        }
        return new RagConfig(min, RRF_K, RERANK_POOL);
    }
}
