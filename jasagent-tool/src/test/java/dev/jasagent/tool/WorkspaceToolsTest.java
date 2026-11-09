package dev.jasagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对工作区工具的文件计数、路径边界、搜索和只读 shell。
 */
class WorkspaceToolsTest {
    @TempDir
    Path root;

    /**
     * 断言列目录时文件总数不含 .git 里的文件，但会列出工作区里的普通文件。
     */
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

    /**
     * 断言工作区内的文件能读到正文，逃出工作区的路径返回错误且不带出区外内容。
     */
    @Test
    void readFileStaysInsideWorkspace() throws Exception {
        Files.writeString(root.resolve("note.txt"), "hello");
        String read = WorkspaceTools.execute(root, "read_file", "{\"path\":\"note.txt\"}");
        assertTrue(read.contains("hello"), read);
        String escaped = WorkspaceTools.execute(root, "read_file", "{\"path\":\"../secret.txt\"}");
        assertTrue(escaped.startsWith("错误"), escaped);
        assertFalse(escaped.contains("secret"), escaped);
    }

    /**
     * 断言 glob 能按扩展名找到 Java 文件，内容搜索能带回该文件的命中行。
     */
    @Test
    void grepAndGlobFindTheFile() throws Exception {
        Files.writeString(root.resolve("Note.java"), "class Note {}");
        String glob = WorkspaceTools.execute(root, "glob_files", "{\"glob_pattern\":\"**/*.java\"}");
        assertTrue(glob.contains("Note.java"), glob);
        String grep = WorkspaceTools.execute(root, "grep_files", "{\"pattern\":\"class Note\"}");
        assertTrue(grep.contains("Note.java:1:"), grep);
    }

    /**
     * 断言只读命令可以免批准并列出文件，删除命令需要批准，高危命令不能免批准，且原文件仍在。
     */
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

    /**
     * 断言工具定义保留认识的名字并保持顺序，不认识的检索工具名被跳过。
     */
    @Test
    void definitionsCoverReadTools() {
        var tools = WorkspaceTools.definitions(java.util.List.of("list_directory", "rag.search", "clock"));
        assertEquals(2, tools.size());
        assertEquals("list_directory", tools.get(0).name());
        assertEquals("clock", tools.get(1).name());
    }
}
