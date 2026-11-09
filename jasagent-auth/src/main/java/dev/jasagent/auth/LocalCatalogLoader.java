package dev.jasagent.auth;

import dev.jasagent.api.auth.Catalog;
import dev.jasagent.api.auth.CatalogLoader;
import dev.jasagent.api.auth.RuleDocument;
import dev.jasagent.api.auth.SkillDocument;
import dev.jasagent.api.concurrent.VirtualIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * 从工作区和用户目录读取规则与技能文档，同名时让用户目录覆盖工作区。
 */
public final class LocalCatalogLoader implements CatalogLoader {
    /**
     * 在虚拟线程上合并两处目录中的规则和技能。
     *
     * @param workspaceDirectory 工作区下的 jasagent 目录，可以缺失
     * @param userDirectory 用户下的 jasagent 目录，可以缺失；其中的同名条目覆盖工作区
     * @return 完成后得到合并后的目录，规则和技能都按标识去重
     */
    @Override
    public CompletionStage<Catalog> load(Path workspaceDirectory, Path userDirectory) {
        return VirtualIo.supply(() -> loadSync(workspaceDirectory, userDirectory));
    }

    /**
     * 先读工作区再读用户目录，因此后写入映射的用户条目会留下。
     *
     * @param workspaceDirectory 工作区 jasagent 目录
     * @param userDirectory 用户 jasagent 目录
     * @return 规则和技能都已按标识覆盖合并的目录
     */
    private Catalog loadSync(Path workspaceDirectory, Path userDirectory) throws IOException {
        Map<String, RuleDocument> rules = new LinkedHashMap<>();
        Map<String, SkillDocument> skills = new LinkedHashMap<>();
        readRules(workspaceDirectory, rules);
        readRules(userDirectory, rules);
        readSkills(workspaceDirectory, skills);
        readSkills(userDirectory, skills);
        return new Catalog(new ArrayList<>(rules.values()), new ArrayList<>(skills.values()));
    }

    /**
     * 把目录下 rules 中的 Markdown 规则放进映射，同一标识会覆盖已有条目；目录不存在时不改映射。
     * 副作用是向传入的映射写入或替换规则文档。
     *
     * @param jasagentDirectory 含有 rules 子目录的 jasagent 根，空则跳过
     * @param rules 按规则标识累积的结果，调用后可能新增或被覆盖
     */
    private static void readRules(Path jasagentDirectory, Map<String, RuleDocument> rules) throws IOException {
        if (jasagentDirectory == null) {
            return;
        }
        Path root = jasagentDirectory.resolve("rules");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                FrontMatter parsed = FrontMatter.parse(Files.readString(file, StandardCharsets.UTF_8));
                String id = parsed.meta().getOrDefault("id", stripExtension(file.getFileName().toString()));
                rules.put(id, new RuleDocument(id, parsed.meta().getOrDefault("description", ""), parsed.body(), file.toAbsolutePath().normalize().toString()));
            }
        }
    }

    /**
     * 把 skills 下每个含 SKILL.md 的子目录读成技能，同名时覆盖映射中的旧条目；目录不存在时不改映射。
     * 副作用是向传入的映射写入或替换技能文档。
     *
     * @param jasagentDirectory 含有 skills 子目录的 jasagent 根，空则跳过
     * @param skills 按技能名累积的结果，调用后可能新增或被覆盖
     */
    private static void readSkills(Path jasagentDirectory, Map<String, SkillDocument> skills) throws IOException {
        if (jasagentDirectory == null) {
            return;
        }
        Path root = jasagentDirectory.resolve("skills");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> children = Files.list(root)) {
            for (Path dir : children.filter(Files::isDirectory).sorted().toList()) {
                Path file = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                FrontMatter parsed = FrontMatter.parse(Files.readString(file, StandardCharsets.UTF_8));
                String name = parsed.meta().getOrDefault("name", dir.getFileName().toString());
                skills.put(name, new SkillDocument(name, parsed.meta().getOrDefault("description", ""), parsed.body(), file.toAbsolutePath().normalize().toString()));
            }
        }
    }

    /**
     * 用规则文件名去掉 .md 后缀作为缺省标识。
     *
     * @param fileName 规则文件名
     * @return 去掉末尾 .md 后的名字；没有该后缀时原样返回
     */
    private static String stripExtension(String fileName) {
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
