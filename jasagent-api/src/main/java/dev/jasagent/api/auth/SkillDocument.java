package dev.jasagent.api.auth;

/**
 * 从技能目录读出的一份技能。控制台用名称和说明列目录，正文在运行时注入上下文。
 *
 * @param name 技能名，来自 front matter 的 name，缺省时用所在目录名
 * @param description 技能的简短说明，来自 front matter 的 description
 * @param body front matter 之后的 Markdown 正文
 * @param source SKILL.md 的规范化绝对路径
 */
public record SkillDocument(String name, String description, String body, String source) {
}
