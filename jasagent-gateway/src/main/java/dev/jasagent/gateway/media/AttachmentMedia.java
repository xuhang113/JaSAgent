package dev.jasagent.gateway.media;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 按文件头判断附件的真实类型，并给出这一类型的原始字节上限。
 * 不看扩展名，也不看客户端申报的 Content-Type。探测结果不在允许名单里就拒绝。
 */
public final class AttachmentMedia {
    /**
     * 图片原始字节上限，10 MiB。
     */
    public static final int IMAGE_LIMIT = 10 * 1024 * 1024;
    /**
     * 语音原始字节上限，20 MiB。
     */
    public static final int AUDIO_LIMIT = 20 * 1024 * 1024;
    /**
     * 视频原始字节上限，50 MiB。这也是任何附件允许的最大原始字节数。
     */
    public static final int VIDEO_LIMIT = 50 * 1024 * 1024;
    /**
     * 连 multipart 头一起读取的上限。比视频上限多 1 MiB，超出则整请求按过大拒绝。
     */
    public static final int READ_CAP = VIDEO_LIMIT + 1024 * 1024;
    /**
     * 允许的图片类型。不含 SVG。
     */
    public static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    /**
     * 允许的语音类型。
     */
    public static final Set<String> AUDIO = Set.of(
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/wav", "audio/ogg", "audio/webm");
    /**
     * 允许的视频类型。只此两种，不含宽泛的 video/*。
     */
    public static final Set<String> VIDEO = Set.of("video/mp4", "video/webm");
    /**
     * JPEG 文件头。
     */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    /**
     * PNG 文件头。
     */
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    /**
     * GIF 87a 文件头。
     */
    private static final byte[] GIF87 = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    /**
     * GIF 89a 文件头。
     */
    private static final byte[] GIF89 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    /**
     * EBML 文件头。WebM 以此开头。
     */
    private static final byte[] EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    /**
     * 禁止实例化。类型判断只通过静态方法使用。
     */
    private AttachmentMedia() {
    }

    /**
     * 这个类型是否在允许名单里。
     *
     * @param mediaType 探测到的类型
     * @return 图片、语音或视频名单中有它时为真
     */
    public static boolean allowed(String mediaType) {
        return mediaType != null && (IMAGES.contains(mediaType) || AUDIO.contains(mediaType) || VIDEO.contains(mediaType));
    }

    /**
     * 这一类型的原始字节上限。不在名单里时是 0。
     *
     * @param mediaType 探测到的类型
     * @return 上限字节数
     */
    public static int limit(String mediaType) {
        if (mediaType == null) {
            return 0;
        }
        if (IMAGES.contains(mediaType)) {
            return IMAGE_LIMIT;
        }
        if (AUDIO.contains(mediaType)) {
            return AUDIO_LIMIT;
        }
        if (VIDEO.contains(mediaType)) {
            return VIDEO_LIMIT;
        }
        return 0;
    }

    /**
     * 判断这段原始字节能不能收下。空内容、无法识别的类型和超限都拒绝。
     * 超过视频上限时不再继续探测，调用方不得落库。
     *
     * @param content 附件原始字节
     * @return 接受、类型拒绝或大小拒绝
     */
    public static Decision decide(byte[] content) {
        if (content == null || content.length == 0) {
            return Decision.typeRejected();
        }
        if (content.length > VIDEO_LIMIT) {
            return Decision.overLimit();
        }
        String mediaType = sniff(content);
        if (!allowed(mediaType)) {
            return Decision.typeRejected();
        }
        if (content.length > limit(mediaType)) {
            return Decision.overLimit();
        }
        return Decision.accepted(mediaType);
    }

    /**
     * 只看文件头和容器标记，得出允许名单中的类型。识别不了时为空。
     *
     * @param content 非空的原始字节，长度不超过视频上限
     * @return 允许的类型；不是图片、语音或视频时为空
     */
    static String sniff(byte[] content) {
        if (startsWith(content, PNG)) {
            return "image/png";
        }
        if (startsWith(content, GIF87) || startsWith(content, GIF89)) {
            return "image/gif";
        }
        if (startsWith(content, JPEG)) {
            return "image/jpeg";
        }
        String riff = riff(content);
        if (riff != null) {
            return riff;
        }
        String ogg = ogg(content);
        if (ogg != null) {
            return ogg;
        }
        if (startsWith(content, "ID3".getBytes(StandardCharsets.US_ASCII))) {
            return "audio/mpeg";
        }
        String mpeg = mpeg(content);
        if (mpeg != null) {
            return mpeg;
        }
        String webm = webm(content);
        if (webm != null) {
            return webm;
        }
        return mp4(content);
    }

    /**
     * RIFF 容器只接受 WEBP 和 WAVE。AVI 等其它形式不在名单里。
     *
     * @param content 原始字节
     * @return 图片或语音类型；不是这两种时为空
     */
    private static String riff(byte[] content) {
        if (!startsWith(content, "RIFF".getBytes(StandardCharsets.US_ASCII)) || content.length < 12) {
            return null;
        }
        String form = new String(content, 8, 4, StandardCharsets.US_ASCII);
        if ("WEBP".equals(form)) {
            return "image/webp";
        }
        if ("WAVE".equals(form)) {
            return "audio/wav";
        }
        return null;
    }

    /**
     * Ogg 只接受 Opus、Vorbis、Speex 或 FLAC。带 Theora 等视频标记的不收。
     *
     * @param content 原始字节
     * @return audio/ogg；不是允许的语音流时为空
     */
    private static String ogg(byte[] content) {
        if (!startsWith(content, "OggS".getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        String lower = window(content, 64 * 1024).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("theora") || lower.contains("daala") || lower.contains("dirac")) {
            return null;
        }
        if (lower.contains("opushead") || lower.contains("vorbis") || lower.contains("speex") || lower.contains("flac")) {
            return "audio/ogg";
        }
        return null;
    }

    /**
     * 区分 ADTS AAC 和 MPEG 音频帧。保留位、空比特率或非法采样率都不算。
     *
     * @param content 原始字节
     * @return audio/aac 或 audio/mpeg；不像音频帧时为空
     */
    private static String mpeg(byte[] content) {
        if (content.length < 4) {
            return null;
        }
        int first = content[0] & 0xFF;
        int second = content[1] & 0xFF;
        int third = content[2] & 0xFF;
        if (first != 0xFF || (second & 0xE0) != 0xE0) {
            return null;
        }
        int version = (second >> 3) & 0x3;
        if (version == 0x1) {
            return null;
        }
        int layer = (second >> 1) & 0x3;
        int bitrate = (third >> 4) & 0xF;
        int sample = (third >> 2) & 0x3;
        if (bitrate == 0 || bitrate == 0xF || sample == 0x3) {
            return null;
        }
        if (layer == 0) {
            return "audio/aac";
        }
        return "audio/mpeg";
    }

    /**
     * EBML 只接受 DocType 为 webm 的文件。有视频编码时是 video/webm，只有音频编码时是 audio/webm。
     *
     * @param content 原始字节
     * @return 视频或语音 webm；不是 webm 时为空
     */
    private static String webm(byte[] content) {
        if (!startsWith(content, EBML)) {
            return null;
        }
        String text = window(content, 256 * 1024);
        if (!text.contains("webm")) {
            return null;
        }
        boolean video = text.contains("V_VP8") || text.contains("V_VP9") || text.contains("V_AV1")
                || text.contains("V_MPEG4") || text.contains("V_THEORA") || text.contains("V_MPEGH");
        boolean audio = text.contains("A_OPUS") || text.contains("A_VORBIS") || text.contains("A_AAC")
                || text.contains("A_FLAC") || text.contains("A_PCM");
        if (video) {
            return "video/webm";
        }
        if (audio) {
            return "audio/webm";
        }
        return null;
    }

    /**
     * 看 ftyp 的主品牌和兼容品牌。音频品牌且没有视频编码品牌时是 audio/mp4，常见 MP4 品牌是 video/mp4。
     * 3GPP 不在允许名单里。
     *
     * @param content 原始字节
     * @return video/mp4 或 audio/mp4；品牌不能确定时为空
     */
    private static String mp4(byte[] content) {
        int ftyp = indexOf(content, "ftyp".getBytes(StandardCharsets.US_ASCII), 64);
        if (ftyp < 4) {
            return null;
        }
        int boxStart = ftyp - 4;
        int size = readU32(content, boxStart);
        if (size < 16) {
            return null;
        }
        int end = Math.min(content.length, boxStart + size);
        if (end < ftyp + 8) {
            return null;
        }
        String major = brand(content, ftyp + 4);
        if (major.startsWith("3gp") || major.startsWith("3g2")) {
            return null;
        }
        boolean video = videoBrand(major);
        boolean audio = audioBrand(major);
        for (int offset = ftyp + 12; offset + 4 <= end; offset += 4) {
            String compatible = brand(content, offset);
            video = video || videoBrand(compatible);
            audio = audio || audioBrand(compatible);
        }
        if (video) {
            return "video/mp4";
        }
        if (audio) {
            return "audio/mp4";
        }
        if (genericMp4(major)) {
            return "video/mp4";
        }
        return null;
    }

    /**
     * 读四字节品牌。不够四字节时是空串。
     *
     * @param content 原始字节
     * @param offset 品牌起点
     * @return 四个字符的品牌
     */
    private static String brand(byte[] content, int offset) {
        if (offset < 0 || offset + 4 > content.length) {
            return "";
        }
        return new String(content, offset, 4, StandardCharsets.US_ASCII);
    }

    /**
     * 这个品牌是不是视频编码。isom 这类通用品牌不算。
     *
     * @param brand 四字符品牌
     * @return 是视频编码品牌时为真
     */
    private static boolean videoBrand(String brand) {
        return switch (brand) {
            case "avc1", "avc3", "hvc1", "hev1", "vp08", "vp09", "av01", "mp4v", "M4V ", "dvh1", "dva1" -> true;
            default -> false;
        };
    }

    /**
     * 这个品牌是不是音频 MP4。比较时保留末尾空格。
     *
     * @param brand 四字符品牌
     * @return 是音频品牌时为真
     */
    private static boolean audioBrand(String brand) {
        return switch (brand) {
            case "M4A ", "M4B ", "M4P ", "F4A ", "F4B " -> true;
            default -> false;
        };
    }

    /**
     * 没有更具体的音视频品牌时，这些主品牌按 video/mp4 收下。
     *
     * @param brand 主品牌
     * @return 可视为 MP4 视频时为真
     */
    private static boolean genericMp4(String brand) {
        return switch (brand) {
            case "isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "dash", "MSNV", "ndas" -> true;
            default -> false;
        };
    }

    /**
     * 读大端 32 位无符号整数。越界或最高位为 1 时返回 -1，调用方会把它当成无效盒子。
     *
     * @param content 原始字节
     * @param offset 起点
     * @return 盒子大小；无效时为 -1
     */
    private static int readU32(byte[] content, int offset) {
        if (offset < 0 || offset + 4 > content.length || (content[offset] & 0x80) != 0) {
            return -1;
        }
        return ((content[offset] & 0xFF) << 24)
                | ((content[offset + 1] & 0xFF) << 16)
                | ((content[offset + 2] & 0xFF) << 8)
                | (content[offset + 3] & 0xFF);
    }

    /**
     * 在限定范围内查找字节串。
     *
     * @param data 原始字节
     * @param needle 要找的字节
     * @param limit 只看到这个下标为止
     * @return 起点；没有时为 -1
     */
    private static int indexOf(byte[] data, byte[] needle, int limit) {
        int end = Math.min(data.length, limit);
        for (int i = 0; i + needle.length <= end; i++) {
            if (matches(data, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 内容是否以这几个字节开头。
     *
     * @param content 原始字节
     * @param prefix 文件头
     * @return 开头一致时为真
     */
    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && matches(content, 0, prefix);
    }

    /**
     * 从指定位置比较一段字节。
     *
     * @param data 原始字节
     * @param offset 起点
     * @param needle 要比较的字节
     * @return 一致时为真
     */
    private static boolean matches(byte[] data, int offset, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (data[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把前面一段按 ISO-8859-1 看成文本，用来找容器里的 ASCII 标记。
     *
     * @param content 原始字节
     * @param max 最多看这么多字节
     * @return 这段文本
     */
    private static String window(byte[] content, int max) {
        int length = Math.min(content.length, max);
        return new String(content, 0, length, StandardCharsets.ISO_8859_1);
    }

    /**
     * 一次探测的结论。接受时带类型；类型不对或太大时不带可落库的类型。
     *
     * @param mediaType 允许的类型；拒绝时为空
     * @param tooLarge 因原始字节超过上限而拒绝
     */
    public record Decision(String mediaType, boolean tooLarge) {
        /**
         * 类型在名单里且没有超限。
         *
         * @return 可以落库时为真
         */
        public boolean accepted() {
            return mediaType != null && !tooLarge;
        }

        /**
         * 收下这个类型。
         *
         * @param mediaType 允许名单中的类型
         * @return 接受结论
         */
        public static Decision accepted(String mediaType) {
            return new Decision(mediaType, false);
        }

        /**
         * 类型不在允许名单，或根本认不出来。
         *
         * @return 类型拒绝
         */
        public static Decision typeRejected() {
            return new Decision(null, false);
        }

        /**
         * 原始字节超过这一类型或视频总上限。
         *
         * @return 大小拒绝
         */
        public static Decision overLimit() {
            return new Decision(null, true);
        }
    }
}
