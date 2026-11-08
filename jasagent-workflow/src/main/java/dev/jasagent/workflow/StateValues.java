package dev.jasagent.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class StateValues {
    private StateValues() {
    }

    static String text(WorkflowState state, String key) {
        Object value = state.data().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    static int number(WorkflowState state, String key) {
        Object value = state.data().get(key);
        if (value instanceof Number numeric) {
            return numeric.intValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            return Integer.parseInt(raw);
        }
        return 0;
    }

    static List<String> strings(WorkflowState state, String key) {
        Object value = state.data().get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (Object item : list) {
            copy.add(item == null ? "" : String.valueOf(item));
        }
        return copy;
    }

    static List<Map<String, String>> maps(WorkflowState state, String key) {
        Object value = state.data().get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, String>> copy = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> converted = new HashMap<>();
                map.forEach((entryKey, entryValue) -> converted.put(
                        String.valueOf(entryKey),
                        entryValue == null ? "" : String.valueOf(entryValue)));
                copy.add(converted);
            }
        }
        return copy;
    }
}
