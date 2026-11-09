package dev.jasagent.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查回答里的数字和引号片段是不是都能在依据文本里找到。
 */
public final class Grounding {
    /**
     * 匹配回答中的整数或小数，含可选的负号。
     */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    /**
     * 匹配英文双引号或中文引号里的片段。
     */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|「([^」]+)」");

    /**
     * 禁止实例化。核对只通过静态方法完成。
     */
    private Grounding() {
    }

    /**
     * 找出回答里没有出现在依据文本中的数字和引号片段。
     *
     * @param answer 模型回答；空白时视为没有需要核对的内容
     * @param corpus 用户输入、工具结果和检索命中拼成的依据
     * @return 对不上的片段；回答为空白时返回空列表
     */
    public static List<String> unmatched(String answer, String corpus) {
        List<String> missing = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return missing;
        }
        String haystack = corpus == null ? "" : corpus;
        Matcher numbers = NUMBER.matcher(answer);
        while (numbers.find()) {
            if (!haystack.contains(numbers.group())) {
                missing.add(numbers.group());
            }
        }
        Matcher quotes = QUOTED.matcher(answer);
        while (quotes.find()) {
            String token = quotes.group(1) != null ? quotes.group(1) : quotes.group(2);
            if (token != null && !haystack.contains(token)) {
                missing.add(token);
            }
        }
        return missing;
    }
}
