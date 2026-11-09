package dev.jasagent.mcp;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 连接 MCP 服务器的方式。
 * 要么向一个 HTTP 端点发请求，要么拉起一个用标准输入输出通信的子进程。
 */
public sealed interface McpTransport permits McpTransport.Http, McpTransport.Stdio {
    /**
     * 通过 HTTP 端点访问 MCP 服务器。
     *
     * @param endpoint 服务器的 JSON-RPC 地址
     * @param headers 每次请求都附带的请求头
     */
    record Http(URI endpoint, Map<String, String> headers) implements McpTransport {
        /**
         * 拒绝空地址，并把请求头收成不可变映射。
         * 副作用：请求头为空引用时改成空映射，否则复制一份，调用方再改原映射不会影响这里。
         *
         * @param endpoint 服务器地址，不能为空
         * @param headers 额外请求头，可以为空
         */
        public Http {
            if (endpoint == null) {
                throw new IllegalArgumentException("地址不能为空");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * 通过子进程的标准输入输出访问 MCP 服务器。
     *
     * @param command 启动命令，第一个元素是程序，后面是参数
     * @param environment 额外放进子进程的环境变量
     */
    record Stdio(List<String> command, Map<String, String> environment) implements McpTransport {
        /**
         * 把命令和环境变量收成不可变副本。
         * 副作用：空引用改成空列表或空映射，非空则复制，调用方再改原集合不会影响这里。
         *
         * @param command 启动命令，可以为空；空命令会在真正启动时被拒绝
         * @param environment 额外环境变量，可以为空
         */
        public Stdio {
            command = command == null ? List.of() : List.copyOf(command);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }
}
