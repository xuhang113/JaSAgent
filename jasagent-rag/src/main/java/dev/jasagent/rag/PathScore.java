package dev.jasagent.rag;

/**
 * 一段在某一条召回路上的名次和该路自己的分。
 *
 * @param rank 从 1 开始的名次；0 表示这段没有进入该路
 * @param score 该路的原始分；没进入该路时为 0
 */
public record PathScore(int rank, double score) {
    /**
     * 表示这段没有出现在某一路召回里。
     *
     * @return 名次 0、分数 0 的占位
     */
    public static PathScore absent() {
        return new PathScore(0, 0.0);
    }
}
