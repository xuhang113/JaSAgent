package dev.jasagent.evaluation;

import java.util.Map;

/**
 * 读取是否启用模型评判的配置。
 */
public final class EvaluationSettings {
    /**
     * 模型评判开关的配置键，只有小写 true 才表示启用。
     */
    public static final String JUDGE_ENABLED = "jasagent.evaluation.judge-enabled";

    /**
     * 禁止实例化，开关只通过静态方法读取。
     */
    private EvaluationSettings() {
    }

    /**
     * 判断配置是否明确打开模型评判。缺省、空值和其他写法都视为关闭。
     *
     * @param properties 配置项；空映射或空引用都表示关闭
     * @return 该键的值正好是小写 true 时为真
     */
    public static boolean judgeEnabled(Map<String, String> properties) {
        if (properties == null) {
            return false;
        }
        return "true".equals(properties.get(JUDGE_ENABLED));
    }
}
