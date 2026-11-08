package dev.jasagent.evaluation;

import java.util.Map;

public final class EvaluationSettings {
    public static final String JUDGE_ENABLED = "jasagent.evaluation.judge-enabled";

    private EvaluationSettings() {
    }

    public static boolean judgeEnabled(Map<String, String> properties) {
        if (properties == null) {
            return false;
        }
        return "true".equals(properties.get(JUDGE_ENABLED));
    }
}
