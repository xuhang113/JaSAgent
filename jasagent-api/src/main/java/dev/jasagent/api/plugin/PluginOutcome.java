package dev.jasagent.api.plugin;

public record PluginOutcome(boolean continued, String detail) {
    public static PluginOutcome ok() {
        return new PluginOutcome(true, "ok");
    }
}
