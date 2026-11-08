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

public final class LocalCatalogLoader implements CatalogLoader {
    @Override
    public CompletionStage<Catalog> load(Path workspaceDirectory, Path userDirectory) {
        return VirtualIo.supply(() -> loadSync(workspaceDirectory, userDirectory));
    }

    private Catalog loadSync(Path workspaceDirectory, Path userDirectory) throws IOException {
        Map<String, RuleDocument> rules = new LinkedHashMap<>();
        Map<String, SkillDocument> skills = new LinkedHashMap<>();
        readRules(workspaceDirectory, rules);
        readRules(userDirectory, rules);
        readSkills(workspaceDirectory, skills);
        readSkills(userDirectory, skills);
        return new Catalog(new ArrayList<>(rules.values()), new ArrayList<>(skills.values()));
    }

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

    private static String stripExtension(String fileName) {
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
