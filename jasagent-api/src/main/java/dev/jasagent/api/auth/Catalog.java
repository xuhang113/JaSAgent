package dev.jasagent.api.auth;

import java.util.List;
import java.util.Optional;

/**
 * 一次装入的规则和技能。列表在构造时复制，之后修改传入的原列表不会改变这份目录。
 *
 * @param rules 规则文档，按装入顺序保存，同编号时保留后装入的那份
 * @param skills 技能文档，按装入顺序保存，同名时保留后装入的那份
 */
public record Catalog(List<RuleDocument> rules, List<SkillDocument> skills) {
    /**
     * 把规则和技能收成不可变列表。
     *
     * @param rules 规则文档，按调用方给出的顺序保存
     * @param skills 技能文档，按调用方给出的顺序保存
     */
    public Catalog {
        rules = List.copyOf(rules);
        skills = List.copyOf(skills);
    }

    /**
     * 按编号查找第一条规则。
     *
     * @param id 规则编号，与 {@link RuleDocument#id()} 全字匹配
     * @return 找到的规则；没有匹配时为空
     */
    public Optional<RuleDocument> rule(String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    /**
     * 按名称查找第一条技能。
     *
     * @param name 技能名，与 {@link SkillDocument#name()} 全字匹配
     * @return 找到的技能；没有匹配时为空
     */
    public Optional<SkillDocument> skill(String name) {
        return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
    }
}
