package dev.jasagent.gateway.media;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 从上传请求里取出唯一一份附件字节。支持原始正文，也支持只含一个文件段的 multipart。
 * 按字节切分，不按字符切，避免 PNG 等二进制里的换行被当成分隔。
 */
public final class AttachmentBody {
    /**
     * multipart 里文件段的字段名。
     */
    public static final String FILE_FIELD = "file";

    /**
     * 禁止实例化。解析只通过静态方法使用。
     */
    private AttachmentBody() {
    }

    /**
     * 取出这一次上传的原始字节和展示名。空正文、多个文件段或读到上限都失败，调用方不得落库。
     *
     * @param contentType 请求的 Content-Type，可以是 multipart
     * @param body 已经读完的请求体；超限时由调用方传空并带上截断标记
     * @param truncated 读请求体时是否已经碰到读取上限
     * @return 解析结果
     */
    public static Parsed parse(String contentType, byte[] body, boolean truncated) {
        if (truncated) {
            return Parsed.overLimit();
        }
        byte[] content = body == null ? new byte[0] : body;
        if (multipart(contentType)) {
            return multipart(contentType, content);
        }
        return Parsed.file(content, "");
    }

    /**
     * Content-Type 是不是 multipart。比较时忽略大小写和参数前后的空白。
     *
     * @param contentType 请求头
     * @return 是 multipart 时为真
     */
    public static boolean multipart(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("multipart/");
    }

    /**
     * 按 boundary 切开 multipart，只收下名为 file 的那一段。没有、多段或 boundary 不合法都失败。
     *
     * @param contentType 含 boundary 的 Content-Type
     * @param body 完整请求体
     * @return 唯一文件段；否则是格式错误
     */
    private static Parsed multipart(String contentType, byte[] body) {
        String boundary = boundary(contentType);
        if (boundary == null || boundary.isBlank() || boundary.length() > 70) {
            return Parsed.malformed();
        }
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] separator = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int cursor = indexOf(body, marker, 0);
        if (cursor < 0) {
            return Parsed.malformed();
        }
        cursor += marker.length;
        byte[] found = null;
        String name = "";
        int files = 0;
        while (cursor < body.length) {
            if (startsWith(body, cursor, "--".getBytes(StandardCharsets.US_ASCII))) {
                break;
            }
            if (body[cursor] == '\r') {
                cursor++;
            }
            if (cursor < body.length && body[cursor] == '\n') {
                cursor++;
            }
            int next = indexOf(body, separator, cursor);
            int partEnd = next >= 0 ? next : body.length;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), cursor);
            if (headerEnd < 0 || headerEnd > partEnd) {
                return Parsed.malformed();
            }
            String headers = new String(body, cursor, headerEnd - cursor, StandardCharsets.ISO_8859_1);
            if (filePart(headers)) {
                files++;
                int dataStart = headerEnd + 4;
                int dataEnd = partEnd;
                if (dataEnd < dataStart) {
                    return Parsed.malformed();
                }
                found = copy(body, dataStart, dataEnd);
                name = filename(headers);
            }
            if (next < 0) {
                break;
            }
            cursor = next + separator.length;
        }
        if (files != 1 || found == null) {
            return Parsed.malformed();
        }
        return Parsed.file(found, name);
    }

    /**
     * 从 Content-Type 取出 boundary。带引号时去掉引号。没有 boundary 时为空。
     *
     * @param contentType 请求头
     * @return boundary；没有时为空
     */
    private static String boundary(String contentType) {
        String[] parts = contentType.split(";");
        for (String part : parts) {
            String item = part.trim();
            if (item.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String value = item.substring("boundary=".length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    /**
     * 这一段是不是名为 file 的文件段。
     *
     * @param headers 段头原文
     * @return Content-Disposition 里 name 是 file 时为真
     */
    private static boolean filePart(String headers) {
        return FILE_FIELD.equals(parameter(headers, "name"));
    }

    /**
     * 取出文件段里的 filename。没有时是空串，不影响对象路径。
     *
     * @param headers 段头原文
     * @return 客户端给出的文件名
     */
    private static String filename(String headers) {
        String value = parameter(headers, "filename");
        return value == null ? "" : value;
    }

    /**
     * 从 Content-Disposition 里取一个参数。支持带引号的值。
     *
     * @param headers 段头原文
     * @param name 参数名
     * @return 参数值；没有时为空
     */
    private static String parameter(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String header = line.substring(0, colon).trim();
            if (!"content-disposition".equalsIgnoreCase(header)) {
                continue;
            }
            String value = line.substring(colon + 1);
            for (String piece : value.split(";")) {
                String item = piece.trim();
                int equals = item.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = item.substring(0, equals).trim();
                if (!name.equalsIgnoreCase(key)) {
                    continue;
                }
                String raw = item.substring(equals + 1).trim();
                if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
                    raw = raw.substring(1, raw.length() - 1);
                }
                return raw;
            }
        }
        return null;
    }

    /**
     * 从指定位置查找字节串。
     *
     * @param data 原始字节
     * @param needle 要找的字节
     * @param from 起点
     * @return 起点；没有时为 -1
     */
    private static int indexOf(byte[] data, byte[] needle, int from) {
        for (int i = Math.max(0, from); i + needle.length <= data.length; i++) {
            if (startsWith(data, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从指定位置比较一段字节。
     *
     * @param data 原始字节
     * @param offset 起点
     * @param prefix 要比较的字节
     * @return 一致时为真
     */
    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > data.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 复制一段字节。结束位置不包含。
     *
     * @param data 原始字节
     * @param start 起点
     * @param end 终点
     * @return 这一段的副本
     */
    private static byte[] copy(byte[] data, int start, int end) {
        byte[] out = new byte[end - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }

    /**
     * 一次上传解析的结果。
     *
     * @param content 文件字节；失败时是空数组
     * @param filename 客户端文件名；没有时是空串
     * @param bad 格式不对
     * @param tooLarge 请求体超过读取上限
     */
    public record Parsed(byte[] content, String filename, boolean bad, boolean tooLarge) {
        /**
         * 解析出唯一一份文件。
         *
         * @param content 文件字节
         * @param filename 客户端文件名
         * @return 可继续做类型判断的结果
         */
        public static Parsed file(byte[] content, String filename) {
            return new Parsed(content, filename == null ? "" : filename, false, false);
        }

        /**
         * multipart 格式不对，或文件段不是恰好一段。
         *
         * @return 格式错误
         */
        public static Parsed malformed() {
            return new Parsed(new byte[0], "", true, false);
        }

        /**
         * 请求体超过读取上限。
         *
         * @return 过大
         */
        public static Parsed overLimit() {
            return new Parsed(new byte[0], "", false, true);
        }
    }
}
