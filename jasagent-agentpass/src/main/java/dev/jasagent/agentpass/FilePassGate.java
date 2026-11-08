package dev.jasagent.agentpass;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.execution.ExecutionStatus;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassApproval;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class FilePassGate implements PassGate {
    private final boolean enabled;
    private final Map<String, Stored> memory = new ConcurrentHashMap<>();
    private final Map<String, ExecutionStatus> statuses = new ConcurrentHashMap<>();

    public FilePassGate() {
        this(true);
    }

    public FilePassGate(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public CompletionStage<Pass> acquire(PassRequest request) {
        return VirtualIo.supply(() -> acquireSync(request));
    }

    @Override
    public CompletionStage<Pass> decide(PassApproval approval) {
        return VirtualIo.supply(() -> decideSync(approval));
    }

    @Override
    public CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
        return VirtualIo.supply(() -> pendingSync(userId, userDirectory));
    }

    @Override
    public CompletionStage<ExecutionStatus> executionStatus(String executionId) {
        return VirtualIo.supply(() -> statuses.get(executionId));
    }

    private Pass acquireSync(PassRequest request) {
        if (request.userId() == null || request.userId().isBlank()
                || request.executionId() == null || request.executionId().isBlank()
                || request.userDirectory() == null) {
            throw new AgentException(ErrorCode.INTERNAL, "pass request");
        }
        if (!enabled) {
            return remember(request, PassDecision.SKIPPED, "agentpass-not-enabled");
        }
        UserPolicy policy = UserPolicy.read(request.userDirectory().resolve("auth").resolve("policies.yaml"));
        Optional<PassDecision> matched = policy.match(request.toolName(), request.pluginId());
        if (matched.isPresent()) {
            return remember(request, matched.get(), "policy");
        }
        Pass pass = remember(request, PassDecision.PENDING, "approval-required");
        statuses.put(request.executionId(), ExecutionStatus.SUSPENDED);
        write(request.userDirectory(), pass);
        return pass;
    }

    private Pass decideSync(PassApproval approval) {
        if (approval.decision() != PassDecision.ALLOW && approval.decision() != PassDecision.DENY) {
            throw new AgentException(ErrorCode.INTERNAL, "decision");
        }
        String passId = canonical(approval.passId());
        Stored stored = memory.get(passId);
        if (stored == null) {
            stored = readStored(approval.userDirectory(), passId);
        }
        if (stored == null) {
            throw new AgentException(ErrorCode.AUTH, "pass");
        }
        Pass current = stored.pass();
        if (!current.userId().equals(approval.userId()) || !current.executionId().equals(approval.executionId())) {
            throw new AgentException(ErrorCode.AUTH, "pass");
        }
        if (current.decision() != PassDecision.PENDING) {
            throw new AgentException(ErrorCode.INTERNAL, "pass already decided");
        }
        Pass updated = new Pass(
                current.passId(),
                current.userId(),
                current.executionId(),
                current.toolName(),
                current.pluginId(),
                current.argumentHash(),
                approval.decision(),
                approval.decision() == PassDecision.ALLOW ? "approved" : "denied");
        memory.put(updated.passId(), new Stored(updated, stored.directory()));
        write(stored.directory(), updated);
        return updated;
    }

    private List<Pass> pendingSync(String userId, Path userDirectory) {
        Map<String, Pass> found = new LinkedHashMap<>();
        if (userDirectory != null) {
            Path dir = userDirectory.resolve("auth").resolve("pending");
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.toList()) {
                        if (!Files.isRegularFile(file) || file.getFileName().toString().endsWith(".tmp")) {
                            continue;
                        }
                        Pass pass = decode(Files.readString(file, StandardCharsets.UTF_8));
                        if (userId.equals(pass.userId()) && pass.decision() == PassDecision.PENDING) {
                            found.put(pass.passId(), pass);
                        }
                    }
                } catch (IOException ex) {
                    throw new AgentException(ErrorCode.INTERNAL, "pending", null, ex);
                }
            }
        }
        for (Stored stored : memory.values()) {
            Pass pass = stored.pass();
            if (userId.equals(pass.userId())
                    && pass.decision() == PassDecision.PENDING
                    && userDirectory != null
                    && userDirectory.equals(stored.directory())
                    && !found.containsKey(pass.passId())) {
                found.put(pass.passId(), pass);
            }
        }
        return List.copyOf(found.values());
    }

    private Pass remember(PassRequest request, PassDecision decision, String reason) {
        Pass pass = new Pass(
                UUID.randomUUID().toString(),
                request.userId(),
                request.executionId(),
                request.toolName(),
                request.pluginId(),
                request.argumentHash(),
                decision,
                reason);
        memory.put(pass.passId(), new Stored(pass, request.userDirectory()));
        return pass;
    }

    private static void write(Path userDirectory, Pass pass) {
        try {
            PendingFiles.write(userDirectory.resolve("auth").resolve("pending").resolve(pass.passId()), encode(pass));
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "pending", null, ex);
        }
    }

    private static Stored readStored(Path userDirectory, String passId) {
        if (userDirectory == null) {
            return null;
        }
        Path file = userDirectory.resolve("auth").resolve("pending").resolve(passId);
        if (Files.notExists(file)) {
            return null;
        }
        try {
            return new Stored(decode(Files.readString(file, StandardCharsets.UTF_8)), userDirectory);
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "pending", null, ex);
        }
    }

    private static String canonical(String passId) {
        try {
            return UUID.fromString(passId).toString();
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new AgentException(ErrorCode.AUTH, "pass", null, ex);
        }
    }

    private static String encode(Pass pass) {
        return "passId=" + pass.passId() + "\n"
                + "userId=" + pass.userId() + "\n"
                + "executionId=" + pass.executionId() + "\n"
                + "toolName=" + empty(pass.toolName()) + "\n"
                + "pluginId=" + empty(pass.pluginId()) + "\n"
                + "argumentHash=" + empty(pass.argumentHash()) + "\n"
                + "decision=" + pass.decision().name() + "\n"
                + "reason=" + empty(pass.reason()) + "\n";
    }

    private static Pass decode(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int split = line.indexOf('=');
            if (split < 0) {
                throw new AgentException(ErrorCode.INTERNAL, "pending");
            }
            fields.put(line.substring(0, split), line.substring(split + 1));
        }
        return new Pass(
                fields.get("passId"),
                fields.get("userId"),
                fields.get("executionId"),
                blankToNull(fields.get("toolName")),
                blankToNull(fields.get("pluginId")),
                fields.get("argumentHash"),
                PassDecision.valueOf(fields.get("decision")),
                fields.get("reason"));
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Stored(Pass pass, Path directory) {
    }
}
