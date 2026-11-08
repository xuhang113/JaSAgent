package dev.jasagent.exception;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

public final class HttpErrors {
    private HttpErrors() {
    }

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
