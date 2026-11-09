package dev.jasagent.plugin;

/**
 * 抓取链接后得到的正文和内容类型。
 *
 * @param contentType 响应的内容类型，用来决定存成 md 还是 txt
 * @param body 响应正文，写入语料文件
 */
public record LinkResponse(String contentType, String body) {
}
