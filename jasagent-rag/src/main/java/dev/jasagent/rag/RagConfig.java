package dev.jasagent.rag;

/**
 * 检索后处理用的三个数：重排下限、融合平滑常数、送去重排的段数。
 *
 * @param minRerank 重排分低于它的命中会被丢掉
 * @param rrfK 倒数排名融合的平滑常数，名次贡献的分母是它加名次
 * @param rerankPool 融合结果里最多拿去重排的段数
 */
public record RagConfig(double minRerank, int rrfK, int rerankPool) {
    /** 没有系统属性覆盖时使用的重排下限，等于 0.35 的命中留下，更低的丢掉。 */
    public static final double DEFAULT_MIN_RERANK = 0.35;
    /** 倒数排名融合的默认平滑常数，某一路第 rank 名贡献 1/(60+rank)。 */
    public static final int RRF_K = 60;
    /** 默认只把融合分最高的 20 段交给重排器。 */
    public static final int RERANK_POOL = 20;

    /**
     * 组装默认配置；系统属性 jasagent.rag.min-rerank 有非空白值时用它替换重排下限。
     * 副作用：只读取系统属性，不修改它。
     *
     * @return 带有生效重排下限、融合常数 60 和候选池 20 的配置
     */
    public static RagConfig defaults() {
        String raw = System.getProperty("jasagent.rag.min-rerank");
        double min = DEFAULT_MIN_RERANK;
        if (raw != null && !raw.isBlank()) {
            min = Double.parseDouble(raw.trim());
        }
        return new RagConfig(min, RRF_K, RERANK_POOL);
    }
}
