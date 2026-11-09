package dev.jasagent.plugin;

/**
 * 按链接取回正文。
 * 工具用它把网络访问隔开，测试可以换成不发请求的实现。
 */
public interface LinkFetcher {
    /**
     * 抓取一个链接。
     *
     * @param url 要抓取的链接
     * @return 内容类型和正文
     */
    LinkResponse fetch(String url);
}
