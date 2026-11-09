package dev.jasagent.core;

/**
 * 放进系统提示的一条技能，包含名称、短说明和可被压缩掉的正文。
 *
 * @param name 技能名
 * @param description 给模型看的短说明
 * @param body 技能正文；上下文放不下时可以变成空串
 */
public record SkillText(String name, String description, String body) {
}
