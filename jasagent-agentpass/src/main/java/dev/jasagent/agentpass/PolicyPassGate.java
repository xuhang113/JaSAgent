package dev.jasagent.agentpass;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.ReadOnlyTools;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.CompletionStage;

/**
 * 只看用户自己的策略来裁决工具或插件；未命中的只读工具直接允许，其余等待审批。
 */
public final class PolicyPassGate implements PassGate {
    private final Function<String, Path> userDirectories;

    /**
     * 记下如何按用户标识找到其 jasagent 目录。
     *
     * @param userDirectories 用户标识到用户目录的函数，不能为空
     */
    public PolicyPassGate(Function<String, Path> userDirectories) {
        if (userDirectories == null) {
            throw new AgentException(ErrorCode.INTERNAL, "pass directory");
        }
        this.userDirectories = userDirectories;
    }

    /**
     * 在虚拟线程上按用户策略、只读工具名单给出允许、拒绝、等待或跳过之外的裁决。
     *
     * @param request 含用户、工具或插件的申请
     * @return 完成后的通行证；未命中且不是只读工具时为等待审批
     */
    @Override
    public CompletionStage<Pass> acquire(PassRequest request) {
        return VirtualIo.supply(() -> decide(request));
    }

    /**
     * 同步读取该用户的策略。命中则采用策略决定；未命中的只读工具允许执行；其余等待审批。不读取工作区策略。
     *
     * @param request 本次通行证申请
     * @return 带原因的裁决
     */
    private Pass decide(PassRequest request) {
        Path directory = null;
        if (request.userId() != null && !request.userId().isBlank()) {
            directory = userDirectories.apply(request.userId());
        }
        Path policy = directory == null ? null : directory.resolve("auth").resolve("policies.yaml");
        if (policy == null) {
            return new Pass(PassDecision.PENDING, "approval-required");
        }
        Optional<PassDecision> matched = UserPolicy.read(policy).match(request.toolName(), request.pluginId());
        if (matched.isPresent()) {
            return new Pass(matched.get(), "policy");
        }
        if (ReadOnlyTools.is(request.toolName())) {
            return new Pass(PassDecision.ALLOW, "read-only");
        }
        return new Pass(PassDecision.PENDING, "approval-required");
    }
}
