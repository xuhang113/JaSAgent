package dev.jasagent.api.auth;

import java.util.List;
import java.util.Optional;

public record Catalog(List<RuleDocument> rules, List<SkillDocument> skills) {
    public Catalog {
        rules = List.copyOf(rules);
        skills = List.copyOf(skills);
    }

    public Optional<RuleDocument> rule(String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    public Optional<SkillDocument> skill(String name) {
        return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
    }
}
