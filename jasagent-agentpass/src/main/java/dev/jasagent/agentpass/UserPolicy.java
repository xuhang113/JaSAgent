package dev.jasagent.agentpass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.PassDecision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读取用户自己的策略文件，并按工具名或插件标识给出允许或拒绝。
 */
final class UserPolicy {
    private final List<Rule> rules;

    /**
     * 保存已经解析好的规则，顺序与文件中的出现顺序一致。
     *
     * @param rules 按文件顺序排列的规则
     */
    private UserPolicy(List<Rule> rules) {
        this.rules = rules;
    }

    /**
     * 先按工具名找第一条同名规则，没有工具名时再按插件标识找。两者都空时拒绝继续裁决。
     *
     * @param toolName 申请调用的工具名，可以空
     * @param pluginId 申请调用的插件标识，可以空
     * @return 第一条命中的允许或拒绝；没有命中时为空
     */
    Optional<PassDecision> match(String toolName, String pluginId) {
        if (present(toolName)) {
            for (Rule rule : rules) {
                if (toolName.equals(rule.toolName())) {
                    return Optional.of(rule.decision());
                }
            }
            return Optional.empty();
        }
        if (present(pluginId)) {
            for (Rule rule : rules) {
                if (pluginId.equals(rule.pluginId())) {
                    return Optional.of(rule.decision());
                }
            }
            return Optional.empty();
        }
        throw new AgentException(ErrorCode.INTERNAL, "pass target missing");
    }

    /**
     * 解析用户策略文件。文件不存在或只剩注释时没有任何规则；格式不对时作为内部错误抛出。
     *
     * @param file 用户目录下 auth/policies.yaml 的路径
     * @return 文件中的规则；没有规则时为空策略
     */
    static UserPolicy read(Path file) {
        try {
            if (Files.notExists(file)) {
                return new UserPolicy(List.of());
            }
            List<String> content = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                content.add(trimmed);
            }
            if (content.isEmpty()) {
                return new UserPolicy(List.of());
            }
            if (!"rules:".equals(content.get(0))) {
                throw new AgentException(ErrorCode.INTERNAL, "policies.yaml");
            }
            List<Rule> rules = new ArrayList<>();
            int index = 1;
            while (index < content.size()) {
                String head = content.get(index);
                String toolName = null;
                String pluginId = null;
                if (head.startsWith("- tool:")) {
                    toolName = value(head.substring("- tool:".length()));
                } else if (head.startsWith("- plugin:")) {
                    pluginId = value(head.substring("- plugin:".length()));
                } else {
                    throw new AgentException(ErrorCode.INTERNAL, "policies.yaml");
                }
                if (index + 1 >= content.size() || !content.get(index + 1).startsWith("decision:")) {
                    throw new AgentException(ErrorCode.INTERNAL, "policies.yaml");
                }
                String decisionText = value(content.get(index + 1).substring("decision:".length()));
                PassDecision decision = switch (decisionText) {
                    case "ALLOW" -> PassDecision.ALLOW;
                    case "DENY" -> PassDecision.DENY;
                    default -> throw new AgentException(ErrorCode.INTERNAL, "policies.yaml");
                };
                rules.add(new Rule(toolName, pluginId, decision));
                index += 2;
            }
            return new UserPolicy(List.copyOf(rules));
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "policies.yaml", null, ex);
        }
    }

    /**
     * 去掉字段值两端空白和一对引号，空值视为策略格式错误。
     *
     * @param raw 冒号后面的原始文本
     * @return 去掉引号后的字段值
     */
    private static String value(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new AgentException(ErrorCode.INTERNAL, "policies.yaml");
        }
        return trimmed;
    }

    /**
     * 判断工具名或插件标识是否真的带了内容。
     *
     * @param value 待判断的名字
     * @return 非空且不是空白时为真
     */
    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 一条只针对工具或只针对插件的允许或拒绝。
     *
     * @param toolName 这条规则匹配的工具名；按插件匹配时为空
     * @param pluginId 这条规则匹配的插件标识；按工具匹配时为空
     * @param decision 命中后采用的允许或拒绝
     */
    private record Rule(String toolName, String pluginId, PassDecision decision) {
    }
}
