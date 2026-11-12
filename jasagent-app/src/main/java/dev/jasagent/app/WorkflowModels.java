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
import dev.jasagent.llm.GrokLlmClient;
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

/**
 * 把进程里的 DeepSeek 或 Gateway 配置包成工作流用的对话模型，并在提供工具时附上工作区工具的使用说明。
 */
final class WorkflowModels {
    /**
     * 附在系统消息后的工具使用说明：闲聊不要调工具，列目录必须采用 fileCount，写和删不能走 shell，拿到结果后不要重复同一次调用。
     */
    private static final String TOOL_GUIDE = """
            需要工作区里的事实时再调用工具。问候、闲聊、翻译直接用一句中文回答，不要调用工具，也不要写执行编号。
            问工作区有多少文件时调用 list_directory，path 用 .，回答里的数量必须使用结果中的 fileCount。
            读文件用 read_file，按文件名找用 glob_files 或 search_files，按内容找用 grep_files 或 search_workspace。
            run_shell_command 只用于上面做不到的只读命令。不要用它删除、写入、安装或提交。
            拿到工具结果后直接回答，不要重复同一次调用。
            """;

    /**
     * 禁止实例化。模型装配都通过静态方法完成。
     * 副作用：没有可调用的实例。
     */
    private WorkflowModels() {
    }

    /**
     * 当前环境是否至少配了一种模型：有 DeepSeek 密钥，或者 Gateway 的基址、密钥和模型名都有。
     *
     * @return 能尝试对话时为 true
     */
    static boolean configured() {
        LlmSettings settings = LlmSettings.fromEnvironment();
        return present(settings.deepseekApiKey())
                || settings.grokConfigured()
                || (present(settings.gatewayBaseUrl()) && present(settings.gatewayApiKey()) && present(settings.gatewayModel()));
    }

    /**
     * 用空配置和“不按执行选模型”的规则创建对话模型。
     *
     * @return 工作流可调用的对话模型；环境没配模型时每次调用都会失败
     */
    static ChatModel chat() {
        return chat(Map.of());
    }

    /**
     * 按配置创建对话模型，不读取单次执行选定的供应商和模型。
     *
     * @param configuration 含思考开关等覆盖项的配置，可以是空映射
     * @return 工作流可调用的对话模型
     */
    static ChatModel chat(Map<String, String> configuration) {
        return chat(configuration, executionId -> null);
    }

    /**
     * 按配置创建对话模型。每次补全时向 executionModel 要这次执行的供应商和模型，空白则交给路由自己决定。有工具时把工作区工具定义和工具说明一并送出。环境未配置模型时返回的模型会以“模型未配置”失败。
     *
     * @param configuration 思考开关等覆盖项
     * @param executionModel 按执行编号选择模型；返回 null 表示不指定
     * @return 会路由到 DeepSeek 或 Gateway 的对话模型
     */
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
        LlmClient grok = settings.grokConfigured() ? GrokLlmClient.create(settings) : new UnavailableClient();
        LlmRouter router = new LlmRouter(
                settings,
                () -> true,
                deepseek,
                gateway,
                grok,
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

    /**
     * 空白字符串改成 null，让路由把“没选模型”和“选了具体模型”区分开。
     *
     * @param value 供应商或模型 id
     * @return 原值；null 或空白时为 null
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 只在当前没开思考、配置不是 null、且 jasagent.deepseek.thinking 为 true 时，复制一份打开思考的设置。其余字段保持不变。已经打开、配置缺失或值不是 true 时返回原对象。
     *
     * @param settings 环境里的原始模型设置
     * @param configuration 运行时配置，可以是 null
     * @return 可能打开了 DeepSeek 思考的设置
     */
    static LlmSettings applyThinking(LlmSettings settings, Map<String, String> configuration) {
        if (settings.deepseekThinking() || configuration == null || !truthy(configuration.get("jasagent.deepseek.thinking"))) {
            return settings;
        }
        return settings.withDeepseekThinking(true);
    }

    /**
     * 是否正好是忽略大小写的 true。1、yes 或其他文字都不算。
     *
     * @param value 配置值，可以是 null
     * @return 是 true 时为 true
     */
    private static boolean truthy(String value) {
        return value != null && value.equalsIgnoreCase("true");
    }

    /**
     * 把模型返回的工具调用转成工作流工具调用，名字为空的项丢掉。
     *
     * @param calls 模型侧的工具调用，可以是 null
     * @return 可执行的工具调用，没有时是空列表
     */
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

    /**
     * 复制对话消息。需要工具时，把工具说明接到第一条系统消息后面；还没有系统消息就在最前面插入一条。
     *
     * @param messages 工作流侧的消息，可以是 null
     * @param tools 这次请求是否带了工具
     * @return 送给模型的消息
     */
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

    /**
     * 配置字符串是否含有非空白字符。
     *
     * @param value 密钥、基址或模型名，可以是 null
     * @return 有内容时为 true
     */
    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 按执行编号决定这次对话要用的供应商和模型。
     */
    interface ExecutionModel {
        /**
         * 为这次执行选择模型。
         *
         * @param executionId 执行编号
         * @return 供应商和模型；返回 null 表示沿用路由默认
         */
        Choice choose(String executionId);
    }

    /**
     * 一次执行选定的模型。两个字段都可以是空白，调用前会把空白收成 null。
     *
     * @param provider 供应商，deepseek 或 ai-gateway
     * @param model 模型 id
     */
    record Choice(String provider, String model) {
    }

    /**
     * 没有对应密钥或基址时代替真实客户端的占位实现。对话和向量都立即失败，错误是模型未配置。
     */
    private static final class UnavailableClient implements LlmClient {
        /**
         * 拒绝这次对话。
         *
         * @param request 原来要发送的对话请求，只用来带上执行编号
         * @return 以“模型未配置”失败的阶段
         */
        @Override
        public CompletionStage<dev.jasagent.api.llm.LlmChatResponse> chat(LlmChatRequest request) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }

        /**
         * 拒绝这次向量化。
         *
         * @param request 原来要发送的向量请求，只用来带上执行编号
         * @return 以“模型未配置”失败的阶段
         */
        @Override
        public CompletionStage<LlmEmbedResponse> embed(LlmEmbedRequest request) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.LLM, "模型未配置", request.executionId(), null));
        }
    }
}
