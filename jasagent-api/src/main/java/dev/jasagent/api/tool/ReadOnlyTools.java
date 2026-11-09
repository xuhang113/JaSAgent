package dev.jasagent.api.tool;

import java.util.Set;

/**
 * 只读工具名册。策略通行证把这里的工具视为读操作，未命中用户策略时直接允许执行。
 */
public final class ReadOnlyTools {
    /**
     * 不改工作区、可在缺少匹配策略时直接放行的工具名。
     */
    private static final Set<String> NAMES = Set.of(
            "list_directory",
            "glob_files",
            "grep_files",
            "search_files",
            "search_workspace",
            "read_file",
            "read_files");

    /**
     * 阻止实例化。工具名判断只通过 {@link #is(String)} 完成。
     */
    private ReadOnlyTools() {
    }

    /**
     * 判断给定工具是不是只读工具。
     *
     * @param name 待判断的工具名
     * @return 名称属于只读名册时为 true；name 为 null 时为 false
     */
    public static boolean is(String name) {
        return name != null && NAMES.contains(name);
    }
}
