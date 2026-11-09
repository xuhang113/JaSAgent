package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;
import dev.jasagent.api.llm.ChatModel;
import dev.jasagent.api.llm.ChatRequest;
import dev.jasagent.api.llm.ChatResponse;
import dev.jasagent.api.llm.LlmTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按令牌预算组装上下文；占用达到压缩线时，只摘要较早的非工具轮次，并在仍然放不下时把旧工具结果收成短摘要，没有要点就删掉。
 */
public final class ContextWindow {
    /**
     * 未单独指定时，一段上下文允许占用的令牌数。
     */
    public static final int DEFAULT_BUDGET_TOKENS = 8000;
    /**
     * 缺省压缩线：估算占用达到预算的 85% 时开始压缩。
     */
    public static final double DEFAULT_COMPRESS_AT = 0.85;
    /**
     * 压缩线配置键，值必须是大于 0 且不超过 1 的小数。
     */
    public static final String COMPRESS_AT = "jasagent.context.compress-at";
    /**
     * 压缩时原样保留的最近消息条数，更早的非工具消息才送给摘要。
     */
    private static final int RECENT_TURNS = 4;
    /**
     * 从工具结果里认出目录列举的文件总数。
     */
    private static final Pattern FILE_COUNT = Pattern.compile("文件总数是\\s*(\\d+)|fileCount:\\s*(\\d+)");
    /**
     * 摘要里关键结果最多保留的字数，避免摘要自己又撑破预算。
     */
    private static final int DIGEST_RESULT_CAP = 32;

    private final ChatModel model;
    private final double compressAt;
    private final Object publishLock = new Object();
    private ContextPrepared published;
    private boolean compressing;

    /**
     * 用缺省压缩线创建上下文窗口。
     *
     * @param model 占用达到压缩线时用来摘要旧对话的模型
     */
    public ContextWindow(ChatModel model) {
        this(model, DEFAULT_COMPRESS_AT);
    }

    /**
     * 绑定摘要模型和压缩线。模型为空，或压缩线不是 (0, 1] 内的有限数时失败。
     *
     * @param model 用来摘要旧对话的模型
     * @param compressAt 触发压缩的预算占比
     */
    public ContextWindow(ChatModel model, double compressAt) {
        if (model == null) {
            throw new AgentException(ErrorCode.INTERNAL, "ChatModel");
        }
        if (Double.isNaN(compressAt) || compressAt <= 0.0 || compressAt > 1.0) {
            throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT);
        }
        this.model = model;
        this.compressAt = compressAt;
    }

    /**
     * 按与准备时相同的渲染规则估算这段输入会占多少令牌。
     *
     * @param input 待估算的上下文输入；空则占零
     * @return 按字符长度折算的令牌数
     */
    public static int estimatedTokens(ContextInput input) {
        if (input == null) {
            return 0;
        }
        return estimate(render(input, new ArrayList<>(input.turns()), new ArrayList<>(input.skills()), ""));
    }

    /**
     * 从配置读取压缩线。没有该键时用缺省 0.85；有键但空白、非数字或越界时失败。
     *
     * @param configuration 配置表；空则用缺省压缩线
     * @return 大于 0 且不超过 1 的压缩线
     */
    public static double compressAt(Map<String, String> configuration) {
        if (configuration == null || !configuration.containsKey(COMPRESS_AT)) {
            return DEFAULT_COMPRESS_AT;
        }
        String raw = configuration.get(COMPRESS_AT);
        if (raw == null || raw.isBlank()) {
            throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT);
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (Double.isNaN(parsed) || parsed <= 0.0 || parsed > 1.0) {
                throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new AgentException(ErrorCode.INTERNAL, COMPRESS_AT, null, ex);
        }
    }

    /**
     * 返回最近一次已经发布的上下文。压缩尚未完成时仍是上一份。
     *
     * @return 已发布的上下文；还没有准备过时为空
     */
    public ContextPrepared published() {
        synchronized (publishLock) {
            return published;
        }
    }

    /**
     * 判断是否有一次摘要调用正在进行、尚未发布新上下文。
     *
     * @return 正在等待摘要时为真
     */
    public boolean compressing() {
        synchronized (publishLock) {
            return compressing;
        }
    }

    /**
     * 渲染上下文。未到压缩线时直接发布；到了则摘要最近几条之前的非工具消息，完成前不替换已发布内容。执行标识为空或预算小于 1 时失败。
     *
     * @param input 本轮要准备的上下文
     * @return 完成后发布的消息和摘要
     */
    public CompletionStage<ContextPrepared> prepare(ContextInput input) {
        if (input == null || input.executionId() == null || input.executionId().isBlank()) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "executionId"));
        }
        if (input.budgetTokens() < 1) {
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "budgetTokens"));
        }
        List<ChatMessage> turns = new ArrayList<>(input.turns());
        List<SkillText> skills = new ArrayList<>(input.skills());
        int estimated = estimate(render(input, turns, skills, ""));
        if (!reached(estimated, input.budgetTokens())) {
            ContextPrepared ready = new ContextPrepared(render(input, turns, skills, ""), "");
            publish(ready);
            return CompletableFuture.completedFuture(ready);
        }
        int keep = Math.min(RECENT_TURNS, turns.size());
        List<ChatMessage> older = new ArrayList<>();
        List<ChatMessage> keptTools = new ArrayList<>();
        for (int index = 0; index < turns.size() - keep; index++) {
            ChatMessage message = turns.get(index);
            if (message != null && "tool".equals(message.role())) {
                keptTools.add(message);
            } else if (message != null) {
                older.add(message);
            }
        }
        List<ChatMessage> recent = new ArrayList<>(turns.subList(turns.size() - keep, turns.size()));
        if (older.isEmpty()) {
            ContextPrepared reduced = reduce(input, keptTools, recent, skills, "", input.budgetTokens());
            publish(reduced);
            return CompletableFuture.completedFuture(reduced);
        }
        ChatRequest request = new ChatRequest(LlmTask.COMPRESS, List.copyOf(older), List.of(), null, input.executionId());
        markCompressing();
        CompletionStage<ChatResponse> stage = model.complete(request);
        if (stage == null) {
            clearCompressing();
            return CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "compress", input.executionId(), null));
        }
        return stage.thenApply(response -> {
            ContextPrepared done = reduce(
                    input,
                    keptTools,
                    recent,
                    new ArrayList<>(input.skills()),
                    summaryText(response),
                    input.budgetTokens());
            publish(done);
            return done;
        }).whenComplete((done, error) -> {
            if (error != null) {
                clearCompressing();
            }
        });
    }

    /**
     * 判断估算令牌是否已经达到预算乘以压缩线。
     *
     * @param estimated 当前渲染的估算令牌
     * @param budget 本轮预算
     * @return 达到或超过压缩线时为真
     */
    private boolean reached(int estimated, int budget) {
        return estimated >= budget * compressAt;
    }

    /**
     * 发布新的上下文并清掉正在压缩的标记。副作用是替换对外可见的已发布结果。
     *
     * @param prepared 这次准备完成的消息和摘要
     */
    private void publish(ContextPrepared prepared) {
        synchronized (publishLock) {
            published = prepared;
            compressing = false;
        }
    }

    /**
     * 标成正在等待摘要。副作用是让查询看到压缩尚未完成，已发布内容保持不变。
     */
    private void markCompressing() {
        synchronized (publishLock) {
            compressing = true;
        }
    }

    /**
     * 清掉正在压缩的标记。副作用是压缩失败后不再显示为进行中，已发布内容也不变。
     */
    private void clearCompressing() {
        synchronized (publishLock) {
            compressing = false;
        }
    }

    /**
     * 用保留的工具消息、最近轮次和摘要重新渲染；仍超过预算时先把旧工具结果收成短摘要，没有要点就删掉，再不行就去掉技能正文。
     *
     * @param input 提供角色、规则、任务板、订正、当前用户和执行标识
     * @param keptTools 最近轮次之前留下来的工具消息
     * @param recent 原样保留的最近消息
     * @param skills 初始仍带正文的技能
     * @param summary 摘要文本，没有时为空
     * @param budget 本轮令牌上限
     * @return 尽量放进预算后的消息和实际留下的摘要
     */
    private static ContextPrepared reduce(
            ContextInput input,
            List<ChatMessage> keptTools,
            List<ChatMessage> recent,
            List<SkillText> skills,
            String summary,
            int budget) {
        List<ChatMessage> conversation = new ArrayList<>(keptTools.size() + recent.size());
        conversation.addAll(keptTools);
        conversation.addAll(recent);
        if (estimate(render(input, conversation, skills, summary)) > budget) {
            conversation = digestOldTools(input, conversation, keptTools.size(), skills, summary, budget);
        }
        if (estimate(render(input, conversation, skills, summary)) > budget) {
            List<SkillText> shrunk = new ArrayList<>();
            for (SkillText skill : skills) {
                shrunk.add(new SkillText(skill.name(), skill.description(), ""));
            }
            skills = shrunk;
        }
        String kept = summary == null ? "" : summary;
        return new ContextPrepared(render(input, conversation, skills, kept), kept);
    }

    /**
     * 旧工具结果放不进预算时，逐条换成短摘要；摘要再放不下或没有要点就删掉这条。
     *
     * @param input 提供系统提示和订正
     * @param conversation 旧工具在前、最近轮次在后
     * @param oldCount 开头有多少条来自旧工具
     * @param skills 当前技能
     * @param summary 已有摘要
     * @param budget 令牌上限
     * @return 去掉或换过摘要的对话
     */
    private static List<ChatMessage> digestOldTools(
            ContextInput input,
            List<ChatMessage> conversation,
            int oldCount,
            List<SkillText> skills,
            String summary,
            int budget) {
        List<ChatMessage> oldTools = new ArrayList<>();
        List<ChatMessage> rest = new ArrayList<>();
        for (int index = 0; index < conversation.size(); index++) {
            ChatMessage message = conversation.get(index);
            if (index < oldCount && message != null && "tool".equals(message.role())) {
                oldTools.add(message);
            } else if (message != null) {
                rest.add(message);
            }
        }
        List<ChatMessage> kept = new ArrayList<>();
        for (ChatMessage tool : oldTools) {
            String digest = fitDigest(toolDigest(tool.content()), budget);
            if (!digest.isEmpty()) {
                kept.add(ChatMessage.text("tool", digest));
            }
        }
        if (estimate(render(input, rest, skills, summary)) > budget) {
            return join(kept, rest);
        }
        List<ChatMessage> combined = join(kept, rest);
        while (!kept.isEmpty() && !fits(input, combined, skills, summary, budget)) {
            kept.remove(0);
            combined = join(kept, rest);
        }
        return combined;
    }

    /**
     * 把工具结果收成「工具名：做了什么。结果：关键结果」。没有可读要点时返回空串。
     *
     * @param content 工具结果原文
     * @return 短摘要；空白或只剩重复字符时为空
     */
    static String toolDigest(String content) {
        if (content == null) {
            return "";
        }
        String raw = content.trim();
        if (raw.isEmpty()) {
            return "";
        }
        Matcher count = FILE_COUNT.matcher(raw);
        if (count.find()) {
            String number = count.group(1) != null ? count.group(1) : count.group(2);
            return "list_directory：列出目录。结果：文件总数 " + number;
        }
        String compact = raw.replaceAll("\\s+", " ").replaceAll("(.)\\1{3,}", "").trim();
        if (compact.isEmpty()) {
            return "";
        }
        if (compact.length() > DIGEST_RESULT_CAP) {
            compact = compact.substring(0, DIGEST_RESULT_CAP);
        }
        return "工具：保留要点。结果：" + compact;
    }

    /**
     * 把摘要裁到剩余令牌能放下的长度。剩下的位置不够写一句摘要时返回空串。
     *
     * @param digest 已经收好的摘要
     * @param roomTokens 预算里还剩的令牌
     * @return 能放进剩余预算的摘要；放不下时为空
     */
    private static String fitDigest(String digest, int roomTokens) {
        if (digest == null || digest.isBlank() || roomTokens < 1) {
            return "";
        }
        int maxChars = roomTokens * 4;
        if (maxChars < 8) {
            return "";
        }
        if (digest.length() <= maxChars) {
            return digest;
        }
        return digest.substring(0, maxChars);
    }

    /**
     * 判断这组对话渲染后是否不超过预算。
     *
     * @param input 系统提示和订正
     * @param turns 对话
     * @param skills 技能
     * @param summary 摘要
     * @param budget 令牌上限
     * @return 不超过预算时为真
     */
    private static boolean fits(ContextInput input, List<ChatMessage> turns, List<SkillText> skills, String summary, int budget) {
        return estimate(render(input, turns, skills, summary)) <= budget;
    }

    /**
     * 在已保留的摘要后面再放一条，并接上最近轮次。
     *
     * @param kept 已经留下的工具摘要
     * @param digest 这一条摘要
     * @param rest 最近轮次
     * @return 新的对话列表
     */
    private static List<ChatMessage> append(List<ChatMessage> kept, String digest, List<ChatMessage> rest) {
        List<ChatMessage> trial = new ArrayList<>(kept);
        trial.add(ChatMessage.text("tool", digest));
        trial.addAll(rest);
        return trial;
    }

    /**
     * 把两组消息接成一个新列表。
     *
     * @param head 前面的消息
     * @param tail 后面的消息
     * @return 新列表
     */
    private static List<ChatMessage> join(List<ChatMessage> head, List<ChatMessage> tail) {
        List<ChatMessage> combined = new ArrayList<>(head);
        combined.addAll(tail);
        return combined;
    }

    /**
     * 按系统提示、摘要、对话轮次、订正和当前用户的顺序排好消息。
     *
     * @param input 提供系统提示素材、订正和当前用户
     * @param turns 要保留的对话，包含工具消息
     * @param skills 写进系统提示的技能
     * @param summary 非空时插在系统提示之后
     * @return 排好序的消息
     */
    private static List<ChatMessage> render(ContextInput input, List<ChatMessage> turns, List<SkillText> skills, String summary) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.text("system", system(input, skills)));
        if (summary != null && !summary.isBlank()) {
            messages.add(ChatMessage.text("summary", summary));
        }
        for (ChatMessage turn : turns) {
            if (turn != null) {
                messages.add(turn);
            }
        }
        for (CorrectionNote note : input.notes()) {
            messages.add(ChatMessage.text("correction", note.text() == null ? "" : note.text()));
        }
        messages.add(ChatMessage.text("user", input.currentUser()));
        return messages;
    }

    /**
     * 拼出含角色、规则、未完成任务、执行标识和技能的系统提示。
     *
     * @param input 角色、规则、任务板和执行标识的来源
     * @param skills 要写进提示的技能；空技能会被跳过
     * @return 系统提示全文
     */
    private static String system(ContextInput input, List<SkillText> skills) {
        StringBuilder builder = new StringBuilder();
        builder.append("角色：").append(input.role()).append('\n');
        builder.append("规则：").append(input.rules()).append('\n');
        builder.append("未完成任务：").append(input.taskBoard()).append('\n');
        builder.append("executionId：").append(input.executionId()).append('\n');
        for (SkillText skill : skills) {
            if (skill == null) {
                continue;
            }
            builder.append("技能：");
            builder.append(skill.name() == null ? "" : skill.name());
            builder.append(' ');
            builder.append(skill.description() == null ? "" : skill.description());
            if (skill.body() != null && !skill.body().isBlank()) {
                builder.append(' ').append(skill.body());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 从摘要回复里取出正文。
     *
     * @param response 模型的摘要回复
     * @return 回复正文；没有消息时为空串
     */
    private static String summaryText(ChatResponse response) {
        if (response == null || response.message() == null || response.message().content() == null) {
            return "";
        }
        return response.message().content();
    }

    /**
     * 把每条消息的字符数除以 4 后向上至少算 1 个令牌，用来和预算比较。
     *
     * @param messages 已经排好的消息
     * @return 估算令牌数
     */
    private static int estimate(List<ChatMessage> messages) {
        int tokens = 0;
        for (ChatMessage message : messages) {
            String content = message == null || message.content() == null ? "" : message.content();
            tokens += Math.max(1, content.length() / 4);
        }
        return tokens;
    }
}
