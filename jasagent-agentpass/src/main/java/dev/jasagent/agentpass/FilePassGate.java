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

/**
 * 按用户目录里的策略裁决通行证，未命中时把待审批记录落到该用户自己的 pending 目录。
 */
public final class FilePassGate implements PassGate {
    private final boolean enabled;
    private final Map<String, Stored> memory = new ConcurrentHashMap<>();
    private final Map<String, ExecutionStatus> statuses = new ConcurrentHashMap<>();

    /**
     * 创建一个开启的文件门，未命中策略的申请会挂起执行并落盘。
     */
    public FilePassGate() {
        this(true);
    }

    /**
     * 按开关创建文件门。关闭时申请直接跳过，不再读策略。
     *
     * @param enabled 为假时所有申请都记为未启用
     */
    public FilePassGate(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 在虚拟线程上申请通行证，命中策略立即决定，未命中则挂起并写入待审批文件。
     *
     * @param request 含用户、执行、目录、工具或插件的申请
     * @return 完成后的通行证
     */
    @Override
    public CompletionStage<Pass> acquire(PassRequest request) {
        return VirtualIo.supply(() -> acquireSync(request));
    }

    /**
     * 在虚拟线程上把仍在等待的通行证改成允许或拒绝，并写回原文件。
     *
     * @param approval 审批人、执行、通行证标识和决定
     * @return 更新后的通行证
     */
    @Override
    public CompletionStage<Pass> decide(PassApproval approval) {
        return VirtualIo.supply(() -> decideSync(approval));
    }

    /**
     * 在虚拟线程上列出该用户仍在等待的通行证，合并磁盘和内存中的记录。
     *
     * @param userId 要查看的用户
     * @param userDirectory 该用户的 jasagent 目录
     * @return 该用户尚未裁决的通行证
     */
    @Override
    public CompletionStage<List<Pass>> pending(String userId, Path userDirectory) {
        return VirtualIo.supply(() -> pendingSync(userId, userDirectory));
    }

    /**
     * 查看某次执行是否因为等待审批而被挂起。
     *
     * @param executionId 执行标识
     * @return 已挂起的状态；这次执行没有被挂起时为空
     */
    @Override
    public CompletionStage<ExecutionStatus> executionStatus(String executionId) {
        return VirtualIo.supply(() -> statuses.get(executionId));
    }

    /**
     * 同步申请通行证。关闭时记为跳过；策略命中时采用策略；否则记为等待审批、挂起执行并落盘。
     *
     * @param request 本次申请，用户、执行和用户目录都不能空
     * @return 已放进内存的通行证
     */
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

    /**
     * 同步审批。只接受允许或拒绝，且用户和执行必须与原申请一致，已经裁决过的不能再改。副作用是更新内存并重写待审批文件。
     *
     * @param approval 审批请求
     * @return 改写决定和原因后的通行证
     */
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

    /**
     * 合并该用户目录里仍为等待的文件，以及内存中属于同一目录、尚未落进文件结果的等待记录。
     *
     * @param userId 要查看的用户
     * @param userDirectory 用户目录；为空时只跳过磁盘扫描
     * @return 去重后的等待中通行证
     */
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

    /**
     * 生成新的通行证标识并放进内存，供随后查询和审批。副作用是向内存表放入这条通行证。
     *
     * @param request 原始申请
     * @param decision 本次决定
     * @param reason 决定原因
     * @return 已记住的通行证
     */
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

    /**
     * 把通行证正文写到用户目录的 auth/pending 下，文件名是通行证标识。副作用是创建或替换该文件。
     *
     * @param userDirectory 用户的 jasagent 目录
     * @param pass 要落盘的通行证
     */
    private static void write(Path userDirectory, Pass pass) {
        try {
            PendingFiles.write(userDirectory.resolve("auth").resolve("pending").resolve(pass.passId()), encode(pass));
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "pending", null, ex);
        }
    }

    /**
     * 从用户的 pending 目录读回一份通行证。
     *
     * @param userDirectory 用户目录；为空时不读磁盘
     * @param passId 已经规范化的通行证标识
     * @return 文件和所属目录；文件不存在时为空
     */
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

    /**
     * 把通行证标识规范成标准 UUID 文本，拒绝空值和非 UUID。
     *
     * @param passId 调用方给出的标识
     * @return 标准 UUID 文本
     */
    private static String canonical(String passId) {
        try {
            return UUID.fromString(passId).toString();
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new AgentException(ErrorCode.AUTH, "pass", null, ex);
        }
    }

    /**
     * 把通行证的标识、用户、执行、工具、插件、参数摘要、决定和原因写成按行存储的正文。
     *
     * @param pass 要编码的通行证
     * @return 可写入 pending 文件的正文
     */
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

    /**
     * 从按行正文还原通行证，缺字段或决定无法识别时作为内部错误抛出。
     *
     * @param body pending 文件全文
     * @return 还原出的通行证
     */
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

    /**
     * 编码时把空字段写成空串，避免正文里出现空引用。
     *
     * @param value 字段值
     * @return 原值；空引用时为空串
     */
    private static String empty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 解码时把空白字段还原成空，表示这项没有工具名或插件标识。
     *
     * @param value 正文中的字段
     * @return 有内容时的原值；空白时为空
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 内存或磁盘上的一份通行证，以及它所属的用户目录。
     *
     * @param pass 通行证内容
     * @param directory 写回 pending 文件时使用的用户目录
     */
    private record Stored(Pass pass, Path directory) {
    }
}
