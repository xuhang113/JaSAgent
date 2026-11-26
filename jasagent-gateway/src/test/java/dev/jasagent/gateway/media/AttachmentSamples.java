package dev.jasagent.gateway.media;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 附件测试用的最小文件头。类型判断只看这些魔数，不靠扩展名。
 */
public final class AttachmentSamples {
    /**
     * 禁止实例化。样本只通过静态方法给出。
     */
    private AttachmentSamples() {
    }

    /**
     * 一像素 PNG。文件头里含有换行，用来确认 multipart 不会按字符切开。
     *
     * @return PNG 字节
     */
    public static byte[] png() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    /**
     * 一像素 GIF。
     *
     * @return GIF 字节
     */
    public static byte[] gif() {
        return Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
    }

    /**
     * JPEG 文件头。探测只要求 FF D8 FF。
     *
     * @return JPEG 字节
     */
    public static byte[] jpeg() {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        };
    }

    /**
     * 一小段 8 位 PCM。浏览器可以把这段当成语音播放。
     *
     * @return WAV 字节
     */
    public static byte[] wav() {
        byte[] data = new byte[] {(byte) 128, (byte) 200, (byte) 40};
        byte[] header = new byte[44];
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        write32(header, 4, 36 + data.length);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        write32(header, 16, 16);
        write16(header, 20, 1);
        write16(header, 22, 1);
        write32(header, 24, 8000);
        write32(header, 28, 8000);
        write16(header, 32, 1);
        write16(header, 34, 8);
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        write32(header, 40, data.length);
        byte[] out = new byte[header.length + data.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(data, 0, out, header.length, data.length);
        return out;
    }

    /**
     * RIFF 容器。form 为 WEBP、WAVE 或 AVI。
     *
     * @param form 四字符形式
     * @return 12 字节的文件头
     */
    public static byte[] riff(String form) {
        byte[] out = new byte[12];
        out[0] = 'R';
        out[1] = 'I';
        out[2] = 'F';
        out[3] = 'F';
        byte[] kind = form.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(kind, 0, out, 8, 4);
        return out;
    }

    /**
     * 带 ID3 头的 MPEG 音频。
     *
     * @return 音频字节
     */
    public static byte[] mpeg() {
        return "ID3\0\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 一帧 MPEG 音频，没有 ID3。
     *
     * @return 音频帧
     */
    public static byte[] mpegFrame() {
        return new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00};
    }

    /**
     * ADTS AAC 帧头。
     *
     * @return AAC 字节
     */
    public static byte[] aac() {
        return new byte[] {(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0x00, 0x1F, (byte) 0xFC};
    }

    /**
     * Ogg 容器。codec 用来区分 Opus 和 Theora。
     *
     * @param codec 紧跟在 OggS 后面的标记
     * @return Ogg 字节
     */
    public static byte[] ogg(String codec) {
        return ("OggS" + codec).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 只含 ftyp 的 MP4。compatible 为空时没有兼容品牌。minor version 留空。
     *
     * @param major 主品牌，四个字符
     * @param compatible 兼容品牌；没有时为空
     * @return MP4 盒子
     */
    public static byte[] ftyp(String major, String compatible) {
        boolean extra = compatible != null && !compatible.isEmpty();
        int size = extra ? 20 : 16;
        byte[] box = new byte[size];
        box[0] = (byte) ((size >>> 24) & 0xFF);
        box[1] = (byte) ((size >>> 16) & 0xFF);
        box[2] = (byte) ((size >>> 8) & 0xFF);
        box[3] = (byte) (size & 0xFF);
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, box, 4, 4);
        System.arraycopy(major.getBytes(StandardCharsets.US_ASCII), 0, box, 8, 4);
        if (extra) {
            System.arraycopy(compatible.getBytes(StandardCharsets.US_ASCII), 0, box, 16, 4);
        }
        return box;
    }

    /**
     * 带 avc1 的 MP4，应按视频收下。
     *
     * @return video/mp4 字节
     */
    public static byte[] videoMp4() {
        return ftyp("isom", "avc1");
    }

    /**
     * 音频 MP4 品牌。
     *
     * @return audio/mp4 字节
     */
    public static byte[] audioMp4() {
        return ftyp("M4A ", "");
    }

    /**
     * EBML 头加上 webm 和编码名。
     *
     * @param codec 视频用 V_VP8，语音用 A_OPUS
     * @return WebM 字节
     */
    public static byte[] webm(String codec) {
        byte[] magic = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
        byte[] text = ("webm" + codec).getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[magic.length + text.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(text, 0, out, magic.length, text.length);
        return out;
    }

    /**
     * 文本样本，用来表示 SVG、HTML 和 XML。
     *
     * @param value 文本
     * @return UTF-8 字节
     */
    public static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 把文件头铺到指定长度，后面补零。用来试大小上限。
     *
     * @param head 文件头
     * @param size 目标长度
     * @return 指定长度的字节
     */
    public static byte[] pad(byte[] head, int size) {
        byte[] out = new byte[size];
        System.arraycopy(head, 0, out, 0, Math.min(head.length, size));
        return out;
    }

    /**
     * 写小端 32 位整数。
     *
     * @param target 目标数组
     * @param offset 起点
     * @param value 数值
     */
    private static void write32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    /**
     * 写小端 16 位整数。
     *
     * @param target 目标数组
     * @param offset 起点
     * @param value 数值
     */
    private static void write16(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
