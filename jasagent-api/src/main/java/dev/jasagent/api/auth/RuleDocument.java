package dev.jasagent.api.auth;

/**
 * 从规则目录读出的一条规则。控制台列出编号和说明，正文在组装上下文时使用。
 *
 * @param id 规则编号，来自 front matter 的 id，缺省时用去掉扩展名的文件名
 * @param description 规则的简短说明，来自 front matter 的 description
 * @param body front matter 之后的 Markdown 正文
 * @param source 规则 Markdown 文件的规范化绝对路径
 */
public record RuleDocument(String id, String description, String body, String source) {
}
