package dev.jasagent.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 不需要通行证就可以转发的主机。内置 {@code api.deepseek.com} 和 {@code api.x.ai}，
 * 再加上环境变量 {@code JASAGENT_LLM_ALLOWLIST} 里的额外主机。
 * 命中的是完整主机名，端口不影响判断；子域、后缀和带用户信息的地址都不算命中。
 */
public final class LlmAllowlist {
    private static final Logger log = LoggerFactory.getLogger(LlmAllowlist.class);
    /**
     * DeepSeek 的 API 主机。{@code https://api.deepseek.com} 及其路径免证放行。
     */
    public static final String DEEPSEEK_HOST = "api.deepseek.com";
    /**
     * xAI 的 API 主机。{@code https://api.x.ai} 及其路径免证放行。{@code x.ai} 本身不在名单里。
     */
    public static final String XAI_HOST = "api.x.ai";
    /**
     * 额外主机的环境变量名。值是逗号或空白分隔的主机，不放密钥。
     */
    public static final String ALLOWLIST_ENV = "JASAGENT_LLM_ALLOWLIST";

    private final Set<String> hosts;

    /**
     * 保存已经整理好的主机集合。调用方应使用 {@link #parse(String)}，不要直接塞未整理的名字。
     *
     * @param hosts 小写的完整主机名，至少包含两家内置大模型主机
     */
    private LlmAllowlist(Set<String> hosts) {
        this.hosts = hosts;
    }

    /**
     * 组装放行名单。内置两家主机始终在内；额外项里无法识别的丢掉，不因此拒绝启动。
     * 副作用：有丢掉的项时记一条不含原文的日志，避免把误写进变量的密钥打出来。
     *
     * @param extra {@code JASAGENT_LLM_ALLOWLIST} 的原文，可以是空
     * @return 免证主机名单
     */
    public static LlmAllowlist parse(String extra) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add(DEEPSEEK_HOST);
        hosts.add(XAI_HOST);
        int ignored = 0;
        if (extra != null && !extra.isBlank()) {
            for (String token : extra.split("[,\\s]+")) {
                if (token.isBlank()) {
                    continue;
                }
                String host = HostNames.fromToken(token);
                if (host == null) {
                    ignored++;
                } else {
                    hosts.add(host);
                }
            }
        }
        if (ignored > 0) {
            log.info("llm allowlist ignored {} extra entries", ignored);
        }
        return new LlmAllowlist(Set.copyOf(hosts));
    }

    /**
     * 判断这个主机是不是免证。大小写和末尾的点不影响结果，端口应在调用前去掉。
     *
     * @param host 目的地址的主机，可以带大小写
     * @return 在内置名单或额外名单里时为真
     */
    public boolean permits(String host) {
        String canonical = HostNames.canonical(host);
        return canonical != null && hosts.contains(canonical);
    }
}
