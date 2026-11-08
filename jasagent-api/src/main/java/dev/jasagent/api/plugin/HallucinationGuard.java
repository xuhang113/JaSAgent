package dev.jasagent.api.plugin;

public interface HallucinationGuard {
    boolean check(String answer, String evidence);
}
