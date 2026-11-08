package dev.jasagent.api.plugin;

public final class HallucinationGuards {
    private static volatile HallucinationGuard current = (answer, evidence) -> true;

    private HallucinationGuards() {
    }

    public static void register(HallucinationGuard guard) {
        current = guard == null ? (answer, evidence) -> true : guard;
    }

    public static HallucinationGuard get() {
        return current;
    }
}
