package dev.jasagent.core;

import dev.jasagent.api.task.WorkItem;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class WorkCorrelation {
    private WorkCorrelation() {
    }

    public static double score(WorkItem left, WorkItem right) {
        if (same(left.sessionId(), right.sessionId())) {
            return 1.0;
        }
        if (same(left.correlationKey(), right.correlationKey())) {
            return 0.9;
        }
        return jaccard(left.targetText(), right.targetText());
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && !left.isBlank() && left.equals(right);
    }

    private static double jaccard(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                intersection++;
            }
        }
        int union = leftTokens.size() + rightTokens.size() - intersection;
        return intersection / (double) union;
    }

    private static Set<String> tokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < lower.length(); index++) {
            char ch = lower.charAt(index);
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch);
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
