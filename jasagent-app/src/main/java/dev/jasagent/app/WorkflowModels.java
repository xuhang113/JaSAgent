package dev.jasagent.app;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.LlmChatRequest;
import dev.jasagent.api.llm.LlmClient;
import dev.jasagent.api.llm.LlmEmbedRequest;
import dev.jasagent.api.llm.LlmEmbedResponse;
import dev.jasagent.api.llm.LlmMessage;
import dev.jasagent.api.llm.LlmTask;
import dev.jasagent.api.llm.LlmToolCall;
import dev.jasagent.api.llm.ToolDefinition;
import dev.jasagent.llm.LangChain4jLlmClient;
import dev.jasagent.llm.LlmRouter;
import dev.jasagent.llm.LlmSettings;
import dev.jasagent.tool.WorkspaceTools;
import dev.jasagent.workflow.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class WorkflowModels {
    private static final String TOOL_GUIDE = """
            需要工作区里的事实时再调用工具。问候、闲聊、翻译直接用一句中文回答，不要调用工具，也不要写执行编号。
            问工作区有多少文件时调用 list_directory，path 用 .，回答里的数量必须使用结果中的 fileCount。
            读文件用 read_file，按文件名找用 glob_files 或 search_files，按内容找用 grep_files 或 search_workspace。
            run_shell_command 只用于上面做不到的只读命令。不要用它删除、写入、安装或提交。
            拿到工具结果后直接回答，不要重复同一次调用。
            """;

    private WorkflowModels() {
    }

    static boolean configured() {
        LlmSettings settings = LlmSettings.fromEnvironment();
        return present(settings.deepseekApiKey())
                || (present(settings.gatewayBaseUrl()) && present(settings.gatewayApiKey()) && present(settings.gatewayModel()));
    }

    static ChatModel chat() {
        return chat(Map.of());
    }

    static ChatModel chat(Map<String, String> configuration) {
        return chat(configuration, executionId -> null);
    }

    static ChatModel chat(Map<String, String> configuration, ExecutionModel executionModel) {
        if (!configured()) {
            return request -> CompletableFuture.failedFuture(
                    new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }
        LlmSettings settings = applyThinking(LlmSettings.fromEnvironment(), configuration);
        LlmClient deepseek = present(settings.deepseekApiKey())
                ? LangChain4jLlmClient.deepSeek(settings)
                : new UnavailableClient();
        LlmClient gateway = present(settings.gatewayBaseUrl()) && present(settings.gatewayApiKey())
                ? LangChain4jLlmClient.gateway(settings)
                : new UnavailableClient();
        LlmRouter router = new LlmRouter(
                settings,
                () -> true,
                deepseek,
                gateway,
                call -> CompletableFuture.completedFuture(null),
                (executionId, line) -> CompletableFuture.completedFuture(null));
        return request -> {
            Choice choice = executionModel == null ? null : executionModel.choose(request.executionId());
            String provider = choice == null ? null : blankToNull(choice.provider());
            String model = choice == null ? null : blankToNull(choice.model());
            List<ToolDefinition> tools = WorkspaceTools.definitions(request.tools());
            return router.chat(new LlmChatRequest(
                            request.executionId(),
                            LlmTask.REACT,
                            provider,
                            messages(request.messages(), !tools.isEmpty()),
                            1,
                            model,
                            tools))
                    .thenApply(response -> new ChatModel.ModelReply(
                            response.text() == null ? "" : response.text(),
                            toolCalls(response.toolCalls()),
                            response.thinking() == null ? "" : response.thinking()));
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static LlmSettings applyThinking(LlmSettings settings, Map<String, String> configuration) {
        if (settings.deepseekThinking() || configuration == null || !truthy(configuration.get("jasagent.deepseek.thinking"))) {
            return settings;
        }
        return new LlmSettings(
                settings.deepseekApiKey(),
                true,
                settings.gatewayBaseUrl(),
                settings.gatewayApiKey(),
                settings.gatewayModel(),
                settings.gatewayEmbeddings(),
                settings.gatewayEmbeddingModel(),
                settings.timeout());
    }

    private static boolean truthy(String value) {
        return value != null && value.equalsIgnoreCase("true");
    }

    private static List<ChatModel.ToolCall> toolCalls(List<LlmToolCall> calls) {
        List<ChatModel.ToolCall> converted = new ArrayList<>();
        if (calls == null) {
            return converted;
        }
        for (LlmToolCall call : calls) {
            if (call.name() == null || call.name().isBlank()) {
                continue;
            }
            converted.add(new ChatModel.ToolCall(call.id(), call.name(), call.arguments()));
        }
        return converted;
    }

    private static List<LlmMessage> messages(List<ChatModel.ChatMessage> messages, boolean tools) {
        List<LlmMessage> converted = new ArrayList<>();
        if (messages == null) {
            return converted;
        }
        boolean guided = false;
        for (ChatModel.ChatMessage message : messages) {
            String role = message.role() == null ? "" : message.role();
            String content = message.content() == null ? "" : message.content();
            if (tools && !guided && "system".equals(role)) {
                content = content + "\n" + TOOL_GUIDE;
                guided = true;
            }
            converted.add(new LlmMessage(role, content));
        }
        if (tools && !guided) {
            converted.add(0, new LlmMessage("system", TOOL_GUIDE));
        }
        return converted;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    interface ExecutionModel {
        Choice choose(String executionId);
    }

    record Choice(String provider, String model) {
    }

    private static final class UnavailableClient implements LlmClient {
        @Override
        public CompletionStage<dev.jasagent.api.llm.LlmChatResponse> chat(LlmChatRequest request) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }

        @Override
        public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }
    }
}
