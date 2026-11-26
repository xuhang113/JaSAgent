package dev.jasagent.gateway.media;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传正文按字节切开。PNG 里的换行不能被当成 multipart 分隔。
 */
class AttachmentBodyTest {
    /**
     * 原始正文整段就是文件。客户端类型不在这里解释。
     */
    @Test
    void rawBodyIsTheFile() {
        byte[] png = AttachmentSamples.png();
        AttachmentBody.Parsed parsed = AttachmentBody.parse("image/svg+xml", png, false);
        assertFalse(parsed.bad());
        assertFalse(parsed.tooLarge());
        assertArrayEquals(png, parsed.content());
        assertEquals("", parsed.filename());
    }

    /**
     * 唯一的 file 段被原样取出，文件名留给后面的展示名处理。
     */
    @Test
    void oneFilePartKeepsPngBytes() {
        byte[] png = AttachmentSamples.png();
        byte[] body = form("boundary026", "../../holiday.png", "image/svg+xml", png);
        AttachmentBody.Parsed parsed = AttachmentBody.parse("multipart/form-data; boundary=boundary026", body, false);
        assertFalse(parsed.bad());
        assertArrayEquals(png, parsed.content());
        assertEquals("../../holiday.png", parsed.filename());
        assertEquals("holiday.png", dev.jasagent.persist.SessionAttachmentLedger.displayName(parsed.filename()));
    }

    /**
     * 两段文件或截断的正文都不能继续落库。
     */
    @Test
    void twoFilesOrTruncationFailClosed() {
        byte[] both = twoFiles("boundary026", AttachmentSamples.png(), AttachmentSamples.gif());
        AttachmentBody.Parsed parsed = AttachmentBody.parse("multipart/form-data; boundary=\"boundary026\"", both, false);
        assertTrue(parsed.bad());
        assertEquals(0, parsed.content().length);
        AttachmentBody.Parsed truncated = AttachmentBody.parse("application/octet-stream", AttachmentSamples.png(), true);
        assertTrue(truncated.tooLarge());
        assertFalse(truncated.bad());
    }

    /**
     * 拼一段只含一个文件的 multipart。分隔符使用 CRLF。
     *
     * @param boundary 分隔符
     * @param filename 客户端文件名
     * @param type 客户端申报的类型
     * @param file 文件字节
     * @return 请求体
     */
    private static byte[] form(String boundary, String filename, String type, byte[] file) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n";
        byte[] prefix = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[prefix.length + file.length + suffix.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(file, 0, out, prefix.length, file.length);
        System.arraycopy(suffix, 0, out, prefix.length + file.length, suffix.length);
        return out;
    }

    /**
     * 同一分隔符里放两段 file。这不是两份各自闭合的请求。
     *
     * @param boundary 分隔符
     * @param first 第一段字节
     * @param second 第二段字节
     * @return 请求体
     */
    private static byte[] twoFiles(String boundary, byte[] first, byte[] second) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(first);
            out.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"b.png\"\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(second);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("样本无法拼装");
        }
    }
}
