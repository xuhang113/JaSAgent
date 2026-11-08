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

final class UserPolicy {
    private final List<Rule> rules;

    private UserPolicy(List<Rule> rules) {
        this.rules = rules;
    }

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

    static UserPolicy read(Path file) {
        try {
            if (file == null || Files.notExists(file)) {
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

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record Rule(String toolName, String pluginId, PassDecision decision) {
    }
}
