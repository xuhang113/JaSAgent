package dev.jasagent.evaluation;

/**
 * 评估时看到的一次工具调用，只保留工具名和是否成功。
 *
 * @param name 工具名
 * @param succeeded 该次调用是否成功
 */
public record ToolObservation(String name, boolean succeeded) {
}
