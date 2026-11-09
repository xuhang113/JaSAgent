package dev.jasagent.api.eval;

/**
 * 一次评估的结论。
 * 说明里写下通过或未通过的原因，例如空文本、工具成功率或评判给出的理由。
 *
 * @param evaluator 这次使用的评估器标识
 * @param passed 是否通过
 * @param detail 通过或未通过的说明
 */
public record EvalResult(String evaluator, boolean passed, String detail) {
}
