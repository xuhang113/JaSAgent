package dev.jasagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsTest {
    @TempDir
    Path root;

    @Test
    void listDirectoryCountsFiles() throws Exception {
        Files.writeString(root.resolve("a.txt"), "alpha");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("b.txt"), "beta");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("HEAD"), "ref");
        String listed = WorkspaceTools.execute(root, "list_directory", "{\"path\":\".\"}");
        assertTrue(listed.contains("fileCount: 2"), listed);
        assertTrue(listed.contains("文件总数是 2。"), listed);
        assertTrue(listed.contains("a.txt"), listed);
        assertFalse(listed.contains("HEAD"), listed);
    }

    @Test
    void readFileStaysInsideWorkspace() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        String read = WorkspaceTools.execute(root, "read_file", "{\"path\":\"note.txt\"}");
        assertTrue(read.contains("hello"), read);
        String escaped = WorkspaceTools.execute(root, "read_file", "{\"path\":\"../secret.txt\"}");
        assertTrue(escaped.startsWith("错误"), escaped);
        assertFalse(escaped.contains("secret"), escaped);
    }

    @Test
    void grepAndGlobFindTheFile() throws Exception {
        Files.writeString(root.resolve("Note.java"), "class Note {}");
        String glob = WorkspaceTools.execute(root, "glob_files", "{\"glob_pattern\":\"**/*.java\"}");
        assertTrue(glob.contains("Note.java"), glob);
        String grep = WorkspaceTools.execute(root, "grep_files", "{\"pattern\":\"class Note\"}");
        assertTrue(grep.contains("Note.java:1:"), grep);
    }

    @Test
    void readOnlyShellRunsAndDangerousShellDoesNot() throws Exception {
        Files.writeString(root.resolve("keep.txt"), "stay");
        assertTrue(WorkspaceTools.runsWithoutApproval("run_shell_command", "{\"command\":\"pwd\"}"));
        assertFalse(WorkspaceTools.runsWithoutApproval("run_shell_command", "{\"command\":\"rm keep.txt\"}"));
        String listed = WorkspaceTools.execute(root, "run_shell_command", "{\"command\":\"ls\"}");
        assertTrue(listed.contains("keep.txt"), listed);
        assertTrue(Files.exists(root.resolve("keep.txt")));
        assertTrue(WorkspaceTools.runsWithoutApproval("run_shell_command", "find . -type f | wc -l"));
        assertFalse(WorkspaceTools.runsWithoutApproval("run_shell_command", "rm -rf /"));
    }

    @Test
    void definitionsCoverReadTools() {
        var tools = WorkspaceTools.definitions(java.util.List.of("list_directory", "rag.search", "clock"));
        assertEquals(2, tools.size());
        assertEquals("list_directory", tools.get(0).name());
        assertEquals("clock", tools.get(1).name());
    }
}
