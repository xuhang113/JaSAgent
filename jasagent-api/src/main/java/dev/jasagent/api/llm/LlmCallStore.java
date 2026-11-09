package dev.jasagent.api.llm;

import java.util.concurrent.CompletionStage;

/**
 * 模型调用账本。路由在每次对话或嵌入结束后把用量、耗时和供应方存进去。
 */
public interface LlmCallStore {
    /**
     * 保存一次模型调用的账目，包括任务、供应方、模型、耗时、token 用量和是否走了备用供应方。
     *
     * @param call 待保存的调用账目
     * @return 保存完成后结束的阶段；正常完成表示账目已经写下，没有业务返回值
     */
    CompletionStage<Void> save(LlmCall call);
}
