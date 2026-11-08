package dev.jasagent.mcp;

import java.net.URI;
import java.util.List;
import java.util.Map;

public sealed interface McpTransport permits McpTransport.Http, McpTransport.Stdio {
    record Http(URI endpoint, Map<String, String> headers) implements McpTransport {
        public Http {
            if (endpoint == null) {
                throw new IllegalArgumentException("地址不能为空");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    record Stdio(List<String> command, Map<String, String> environment) implements McpTransport {
        public Stdio {
            command = command == null ? List.of() : List.copyOf(command);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }
}
