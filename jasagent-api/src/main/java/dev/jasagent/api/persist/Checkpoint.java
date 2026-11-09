package dev.jasagent.api.persist;

/**
 * 某次执行停在某个节点时留下的检查点。
 * 恢复时按执行标识取最新一条，从该节点和步号继续。
 *
 * @param executionId 所属执行
 * @param nodeId 检查点所在的节点
 * @param stepIndex 该节点上的步序号
 * @param stateJson 当时的图状态，用 JSON 文本保存
 */
public record Checkpoint(String executionId, String nodeId, int stepIndex, String stateJson) {
}
