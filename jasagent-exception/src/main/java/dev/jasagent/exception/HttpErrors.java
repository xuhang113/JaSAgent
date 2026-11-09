package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

/**
 * 把异常收成可返回的错误正文，并把完整堆栈单独交给追踪出口。
 */
public final class HttpErrors {
    /**
     * 禁止实例化，错误正文只通过静态方法生成。
     */
    private HttpErrors() {
    }

    /**
     * 打印异常堆栈并交给追踪出口，再从因果链里抽出执行标识和错误码组成返回正文。
     *
     * @param error 本次要报告的异常，堆栈会完整打印
     * @param traceStack 接收完整堆栈文本的出口，由调用方决定写到哪里
     * @return 只含执行标识和错误码名称的正文；找不到业务异常时错误码为内部错误
     */
    public static HttpErrorBody body(Throwable error, Consumer<String> traceStack) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        traceStack.accept(writer.toString());
        AgentException agent = find(error);
        if (agent == null) {
            return new HttpErrorBody(null, ErrorCode.INTERNAL.name());
        }
        return new HttpErrorBody(agent.executionId(), agent.code().name());
    }

    /**
     * 沿 cause 链查找第一个业务异常。
     *
     * @param error 起始异常
     * @return 链上的业务异常；没有时为空
     */
    private static AgentException find(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AgentException agent) {
                return agent;
            }
            current = current.getCause();
        }
        return null;
    }
}
