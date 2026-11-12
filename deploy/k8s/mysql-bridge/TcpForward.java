import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 把本机 127.0.0.1 上的一个端口转到集群里的 MySQL。
 * 网关代码写死了 127.0.0.1:3306，所以网关 Pod 里需要这一跳。日志只打主机和端口。
 */
public final class TcpForward {
    private TcpForward() {
    }

    /**
     * 参数依次是目标主机、目标端口、本机监听端口。一直接受连接，直到进程退出。
     *
     * @param args 目标主机、目标端口、监听端口
     * @throws Exception 绑定监听端口失败时抛出
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: TcpForward host port listen");
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int listen = Integer.parseInt(args[2]);
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), listen), 64);
        System.out.println("mysql-bridge 127.0.0.1:" + listen + " -> " + host + ":" + port);
        while (true) {
            Socket inbound = server.accept();
            Thread worker = new Thread(() -> bridge(inbound, host, port), "mysql-bridge");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * 为一条已接受的连接打开到 MySQL 的出站，并在两个方向上搬运字节。
     *
     * @param inbound 已经接受的本机连接
     * @param host MySQL 主机
     * @param port MySQL 端口
     */
    private static void bridge(Socket inbound, String host, int port) {
        Socket outbound = new Socket();
        try {
            outbound.connect(new InetSocketAddress(host, port), 8000);
            Thread upstream = new Thread(() -> pump(inbound, outbound), "mysql-bridge-up");
            upstream.setDaemon(true);
            upstream.start();
            pump(outbound, inbound);
            upstream.join(2000);
        } catch (Exception ignored) {
            close(inbound);
            close(outbound);
        }
    }

    /**
     * 把一端读到的字节写到另一端。任一端关闭后两边都关掉。
     *
     * @param from 读取的一侧
     * @param to 写入的一侧
     */
    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (Exception ignored) {
            // 对端关闭是正常结束。
        } finally {
            close(to);
            close(from);
        }
    }

    /**
     * 关掉套接字，已经关掉时不再抛出。
     *
     * @param socket 要关闭的套接字
     */
    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
            // 重复关闭不用处理。
        }
    }
}
