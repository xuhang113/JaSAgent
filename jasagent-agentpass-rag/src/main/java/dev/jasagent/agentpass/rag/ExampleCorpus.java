package dev.jasagent.agentpass.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 示例用的一小段固定资料。查询整句能对上正文时排在前面，用来演示有效通行证能取回检索结果。
 */
public final class ExampleCorpus {
    /**
     * 固定资料。不读取外部目录，也不包含密钥。
     */
    private static final List<Doc> DOCS = List.of(
            new Doc("agentpass.md", "一次性通行证绑定租户、用户、会话、执行和目标服务，核销后不能再使用。"),
            new Doc("rag.md", "示例检索在通行证有效时返回命中的文档片段。"));

    /**
     * 禁止实例化。检索通过静态方法完成。
     */
    private ExampleCorpus() {
    }

    /**
     * 按查询文本在固定资料里找命中。空查询没有命中。整句包含的文档得分高于只命中个别词的文档。
     *
     * @param query 调用方的查询文本
     * @return 得分大于零的命中，按得分从高到低
     */
    public static List<Hit> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.trim();
        List<Hit> hits = new ArrayList<>();
        for (Doc doc : DOCS) {
            int score = score(doc.text(), needle);
            if (score > 0) {
                hits.add(new Hit(doc.source(), doc.text(), score));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        return List.copyOf(hits);
    }

    /**
     * 给一段正文相对查询打分。整句出现记 100，每个空白分开的词出现再加 10。
     *
     * @param text 资料正文
     * @param query 已经去掉两端空白的查询
     * @return 不小于零的分数
     */
    private static int score(String text, String query) {
        int score = text.contains(query) ? 100 : 0;
        for (String part : query.split("\\s+")) {
            if (!part.isEmpty() && text.contains(part)) {
                score += 10;
            }
        }
        return score;
    }

    /**
     * 一份固定资料。
     *
     * @param source 资料名
     * @param text 可被检索的正文
     */
    private record Doc(String source, String text) {
    }
}
