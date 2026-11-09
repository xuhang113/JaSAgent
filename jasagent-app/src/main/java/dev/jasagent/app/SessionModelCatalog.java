package dev.jasagent.app;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.llm.LlmSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 会话可选模型目录。DeepSeek 有密钥就列出默认模型和接口返回的 id；Gateway 必须配置齐全且基址可达才出现。
 */
final class SessionModelCatalog {
    /**
     * 没有特别选择、或 DeepSeek 列表失败时仍保留的模型 id。有密钥时它总是排在列表最前。
     */
    static final String DEFAULT_MODEL = "deepseek-flash";
    /**
     * DeepSeek 模型在会话和执行里使用的供应商标识。
     */
    static final String DEEPSEEK = "deepseek";
    /**
     * AI Gateway 模型在会话和执行里使用的供应商标识。
     */
    static final String GATEWAY = "ai-gateway";

    private final LlmSettings settings;
    private final DeepSeekModels deepseek;
    private final GatewayProbe gateway;

    /**
     * 保存模型配置、DeepSeek 列表来源和 Gateway 可达性探针。
     * 副作用：只保存引用，不立刻访问网络。
     *
     * @param settings 密钥、基址和 Gateway 模型名
     * @param deepseek 按密钥拉取 DeepSeek 模型 id 的来源
     * @param gateway 判断 Gateway 基址能否连通的探针
     */
    SessionModelCatalog(LlmSettings settings, DeepSeekModels deepseek, GatewayProbe gateway) {
        this.settings = settings;
        this.deepseek = deepseek;
        this.gateway = gateway;
    }

    /**
     * 用进程环境里的模型配置、真实的 DeepSeek 目录和网络探针组装目录。
     *
     * @return 会访问外部模型服务的目录
     */
    static SessionModelCatalog fromEnvironment() {
        return new SessionModelCatalog(LlmSettings.fromEnvironment(), new DeepSeekModelDirectory(), new GatewayReachability());
    }

    /**
     * 组装当前真正可选的模型。DeepSeek 先放默认 id，再并入目录返回的非空 id。Gateway 仅在基址、密钥、模型名都有且探测成功时追加；id 与已有项相同时，展示名加上 Gateway 后缀。
     *
     * @return 不可变的可选项，可能为空
     */
    List<ModelChoice> available() {
        List<ModelChoice> options = new ArrayList<>();
        if (present(settings.deepseekApiKey())) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            ids.add(DEFAULT_MODEL);
            List<String> listed = deepseek.ids(settings.deepseekApiKey());
            if (listed != null) {
                for (String id : listed) {
                    if (id != null && !id.isBlank()) {
                        ids.add(id.trim());
                    }
                }
            }
            for (String id : ids) {
                options.add(new ModelChoice(id, DEEPSEEK, id));
            }
        }
        if (gatewayConfigured() && gateway.reachable(settings.gatewayBaseUrl())) {
            String id = settings.gatewayModel().trim();
            boolean collide = false;
            for (ModelChoice option : options) {
                if (option.id().equals(id)) {
                    collide = true;
                    break;
                }
            }
            options.add(new ModelChoice(id, GATEWAY, collide ? id + "（Gateway）" : id));
        }
        return List.copyOf(options);
    }

    /**
     * 在当前可选项里找出唯一匹配。供应商为空时只按模型 id 找，找到两个同名项就拒绝。供应商有值时必须 id 和供应商都相同。找不到时抛出“模型不可用”。
     *
     * @param model 模型 id，null 当空串
     * @param provider 供应商；null 或空白表示不限定
     * @return 匹配的可选项
     */
    ModelChoice require(String model, String provider) {
        String id = model == null ? "" : model.trim();
        String kind = provider == null ? "" : provider.trim();
        List<ModelChoice> options = available();
        if (kind.isEmpty()) {
            ModelChoice found = null;
            for (ModelChoice option : options) {
                if (option.id().equals(id)) {
                    if (found != null) {
                        throw unavailable();
                    }
                    found = option;
                }
            }
            if (found == null) {
                throw unavailable();
            }
            return found;
        }
        for (ModelChoice option : options) {
            if (option.id().equals(id) && option.provider().equals(kind)) {
                return option;
            }
        }
        throw unavailable();
    }

    /**
     * 从会话文档读取模型 id，缺失或空白时用 deepseek-flash。
     *
     * @param stored 会话 JSON 对应的映射
     * @return 去掉空白后的模型 id
     */
    static String model(Map<String, Object> stored) {
        return text(stored.get("model"), DEFAULT_MODEL);
    }

    /**
     * 从会话文档读取供应商，缺失或空白时用 deepseek。
     *
     * @param stored 会话 JSON 对应的映射
     * @return 供应商标识
     */
    static String provider(Map<String, Object> stored) {
        return text(stored.get("modelProvider"), DEEPSEEK);
    }

    /**
     * Gateway 的基址、密钥和模型名是否都不是空白。
     *
     * @return 三项都有值时为 true
     */
    private boolean gatewayConfigured() {
        return present(settings.gatewayBaseUrl()) && present(settings.gatewayApiKey()) && present(settings.gatewayModel());
    }

    /**
     * 把文档里的值收成去掉空白的字符串。null 或收成空串时用回退值。
     *
     * @param value 原始字段
     * @param fallback 缺失时使用的文本
     * @return 清洗后的文本
     */
    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = String.valueOf(value).trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /**
     * 字符串是否含有非空白字符。
     *
     * @param value 待检查的配置项，可以是 null
     * @return 不是 null 且去掉空白后仍有字符时为 true
     */
    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 构造“模型不可用”的工作流异常，供选择模型失败时抛出。
     *
     * @return 错误码为 WORKFLOW 的异常，此方法本身不抛
     */
    private static AgentException unavailable() {
        return new AgentException(ErrorCode.WORKFLOW, "模型不可用");
    }

    /**
     * 一条可选模型。id 发给对应供应商，label 给界面看；两者只在 Gateway 与已有 id 重名时不同。
     *
     * @param id 模型 id
     * @param provider deepseek 或 ai-gateway
     * @param label 界面展示名
     */
    record ModelChoice(String id, String provider, String label) {
    }

    /**
     * DeepSeek 模型 id 的来源。正式环境走网络目录，测试可以返回固定列表。
     */
    interface DeepSeekModels {
        /**
         * 按密钥列出 DeepSeek 模型 id。
         *
         * @param apiKey DeepSeek 密钥，空密钥通常不应再访问网络
         * @return 模型 id，允许为空
         */
        List<String> ids(String apiKey);
    }

    /**
     * 判断某个 Gateway 基址当前是否能连上。
     */
    interface GatewayProbe {
        /**
         * 探测基址是否有 HTTP 响应。
         *
         * @param baseUrl Gateway 基址
         * @return 可达时为 true
         */
        boolean reachable(String baseUrl);
    }
}
