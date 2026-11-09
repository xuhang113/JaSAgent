package dev.jasagent.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从工作流状态的数据映射里按键读取文本、整数、字符串列表和字符串映射列表。
 */
final class StateValues {
    /**
     * 禁止实例化。读取只通过静态方法完成。
     */
    private StateValues() {
    }

    /**
     * 读取一个键的文本。缺失时返回空串。
     *
     * @param state 当前图状态
     * @param key 状态键
     * @return 该键的文本；缺失时返回空串
     */
    static String text(WorkflowState state, String key) {
        Object value = state.data().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 读取一个键的整数。数字和整数字符串都认，缺失时返回 0。
     *
     * @param state 当前图状态
     * @param key 状态键
     * @return 整数值；缺失时为 0
     */
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

    /**
     * 读取一个键的字符串列表。值不是列表时返回空列表，空元素收成空串。
     *
     * @param state 当前图状态
     * @param key 状态键
     * @return 字符串副本；没有列表时返回空列表
     */
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

    /**
     * 读取一个键里的映射列表。只保留元素本身是映射的项，键值都收成字符串，空值收成空串。
     *
     * @param state 当前图状态
     * @param key 状态键
     * @return 字符串映射的副本；没有列表时返回空列表
     */
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
