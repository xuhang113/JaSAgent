package dev.jasagent.api.tool;

/**
 * 一个工具的登记信息。目录用名称查找工具，并用 internal 决定这次调用要不要先过通行证。
 *
 * @param name 工具名，目录内必须唯一
 * @param description 给人看的用途说明，也会成为交给模型的工具描述
 * @param internal 为 true 时目录直接执行，不向通行证门申请审批
 */
public record ToolSpec(String name, String description, boolean internal) {
}
