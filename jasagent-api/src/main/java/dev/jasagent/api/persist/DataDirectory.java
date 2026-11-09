package dev.jasagent.api.persist;

import java.nio.file.Path;

/**
 * 从工作区根目录换算本系统的数据目录。
 * 这里只计算路径，不创建目录，也没有需要保存的状态。
 */
public final class DataDirectory {
    /**
     * 禁止从外面构造。
     * 没有字段要初始化，数据目录一律用 {@link #of(java.nio.file.Path)} 计算。
     */
    private DataDirectory() {
    }

    /**
     * 把工作区换成绝对且规范化之后的数据目录。
     * 结果是该工作区下的 {@code .jasagent/data}，目录尚不存在时也不创建。
     *
     * @param workspace 工作区根目录
     * @return 数据目录的绝对路径
     */
    public static Path of(Path workspace) {
        return workspace.toAbsolutePath().normalize().resolve(".jasagent").resolve("data");
    }
}
