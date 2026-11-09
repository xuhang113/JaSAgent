package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对同一批索引段跑词法、向量和标题三条召回。
 * 三条路都只保留得分大于零的段，并按得分从高到低、段标识从小到大排序。
 */
final class Retrieval {
    /** BM25 的词频饱和系数；词在段里再增加时，得分增长会按 1.2 迅速变平。 */
    static final double K1 = 1.2;
    /** BM25 的长度归一化系数；0.75 表示段的长短会明显压低或抬高词频权重。 */
    static final double B = 0.75;

    /**
     * 召回计算不保存文档频率之外的实例状态。
     * 副作用：不写入字段。
     */
    private Retrieval() {
    }

    /**
     * 用 BM25 给每段的正文打词法分。
     * 逆文档频率是 log(1 + (总段数 - 含词段数 + 0.5) / (含词段数 + 0.5))，词频再用 K1 和 B 按段长归一。查询或语料没有词时不打分。
     *
     * @param query 用户查询原文
     * @param chunks 参与打分的索引段，顺序对应返回前的段标识
     * @return 得分大于零的段，已按分数和段标识排好
     */
    static List<Ranked> lexical(String query, List<IndexedChunk> chunks) {
        List<String> queryTokens = Tokenizer.tokens(query);
        if (chunks.isEmpty() || queryTokens.isEmpty()) {
            return List.of();
        }
        List<List<String>> docs = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            docs.add(Tokenizer.tokens(chunk.content()));
        }
        int total = docs.size();
        double average = 0;
        for (List<String> doc : docs) {
            average += doc.size();
        }
        average /= total;
        if (average == 0) {
            return List.of();
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> doc : docs) {
            for (String term : new HashSet<>(doc)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : docs.get(i)) {
                termFrequency.merge(term, 1, Integer::sum);
            }
            int length = docs.get(i).size();
            double score = 0;
            for (String term : queryTokens) {
                int frequency = termFrequency.getOrDefault(term, 0);
                if (frequency == 0) {
                    continue;
                }
                int docsWithTerm = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (total - docsWithTerm + 0.5) / (docsWithTerm + 0.5));
                double denominator = frequency + K1 * (1 - B + B * length / average);
                score += idf * (frequency * (K1 + 1)) / denominator;
            }
            if (score > 0) {
                ranked.add(new Ranked(chunks.get(i).chunkId(), score));
            }
        }
        return sort(ranked);
    }

    /**
     * 用查询向量和段正文向量的余弦相似度打分。
     * 查询切不出词，或相似度不大于零的段，都不进入结果。
     *
     * @param query 用户查询原文
     * @param chunks 参与打分的索引段
     * @param embedder 把查询和正文变成同一空间向量的嵌入器
     * @return 相似度大于零的段，已按分数和段标识排好
     */
    static List<Ranked> vector(String query, List<IndexedChunk> chunks, Embedder embedder) {
        if (chunks.isEmpty() || Tokenizer.tokens(query).isEmpty()) {
            return List.of();
        }
        float[] queryVector = embedder.embed(query);
        List<Ranked> ranked = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            float[] docVector = embedder.embed(chunk.content());
            double cosine = cosine(queryVector, docVector);
            if (cosine > 0) {
                ranked.add(new Ranked(chunk.chunkId(), cosine));
            }
        }
        return sort(ranked);
    }

    /**
     * 数查询词有多少个出现在标题或文档路径里。
     * 得分就是命中的查询词个数，同一个查询词只算一次。
     *
     * @param query 用户查询原文
     * @param chunks 参与打分的索引段
     * @return 至少命中一个查询词的段，已按命中数和段标识排好
     */
    static List<Ranked> title(String query, List<IndexedChunk> chunks) {
        Set<String> queryTokens = new LinkedHashSet<>(Tokenizer.tokens(query));
        if (chunks.isEmpty() || queryTokens.isEmpty()) {
            return List.of();
        }
        List<Ranked> ranked = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            Set<String> haystack = new HashSet<>(Tokenizer.tokens(chunk.title() + " " + chunk.uri()));
            int matched = 0;
            for (String token : queryTokens) {
                if (haystack.contains(token)) {
                    matched++;
                }
            }
            if (matched > 0) {
                ranked.add(new Ranked(chunk.chunkId(), matched));
            }
        }
        return sort(ranked);
    }

    /**
     * 计算两个向量重叠前缀的余弦相似度。
     * 只比到较短的那个向量；任一侧长度能量为零时视为不相似。
     *
     * @param left 左侧向量，通常是查询
     * @param right 右侧向量，通常是段正文
     * @return 余弦值；无法比较时返回 0
     */
    private static double cosine(float[] left, float[] right) {
        int length = Math.min(left.length, right.length);
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    /**
     * 按得分从高到低排列，得分相同再按段标识的字典序。
     * 副作用：直接改传入的列表，不另建一份。
     *
     * @param ranked 待排序的召回结果，允许在原列表上重排
     * @return 排好序的同一个列表
     */
    private static List<Ranked> sort(List<Ranked> ranked) {
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::chunkId));
        return ranked;
    }
}
