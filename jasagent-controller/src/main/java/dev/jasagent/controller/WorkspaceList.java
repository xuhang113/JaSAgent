package dev.jasagent.controller;

import java.nio.file.Path;
import java.util.List;

/**
 * 当前进程允许用户选中的工作区路径。
 *
 * @param paths 可选工作区，顺序与控制台展示一致
 */
public record WorkspaceList(List<Path> paths) {
}
