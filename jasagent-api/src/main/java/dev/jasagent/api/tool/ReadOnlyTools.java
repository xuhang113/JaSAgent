package dev.jasagent.api.tool;

import java.util.Set;

public final class ReadOnlyTools {
    private static final Set<String> NAMES = Set.of(
            "list_directory",
            "glob_files",
            "grep_files",
            "search_files",
            "search_workspace",
            "read_file",
            "read_files");

    private ReadOnlyTools() {
    }

    public static boolean is(String name) {
        return name != null && NAMES.contains(name);
    }
}
