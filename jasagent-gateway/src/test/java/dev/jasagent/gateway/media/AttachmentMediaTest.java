package dev.jasagent.gateway.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型只看文件头。图片、语音和视频的上限互不相混。
 */
class AttachmentMediaTest {
    /**
     * 允许名单里的图片、语音和视频都能认出来。
     */
    @Test
    void sniffAcceptsTheAllowList() {
        assertEquals("image/png", accept(AttachmentSamples.png()));
        assertEquals("image/gif", accept(AttachmentSamples.gif()));
        assertEquals("image/jpeg", accept(AttachmentSamples.jpeg()));
        assertEquals("image/webp", accept(AttachmentSamples.riff("WEBP")));
        assertEquals("audio/wav", accept(AttachmentSamples.wav()));
        assertEquals("audio/mpeg", accept(AttachmentSamples.mpeg()));
        assertEquals("audio/mpeg", accept(AttachmentSamples.mpegFrame()));
        assertEquals("audio/aac", accept(AttachmentSamples.aac()));
        assertEquals("audio/ogg", accept(AttachmentSamples.ogg("OpusHead")));
        assertEquals("audio/mp4", accept(AttachmentSamples.audioMp4()));
        assertEquals("audio/webm", accept(AttachmentSamples.webm("A_OPUS")));
        assertEquals("video/mp4", accept(AttachmentSamples.videoMp4()));
        assertEquals("video/mp4", accept(AttachmentSamples.ftyp("isom", "")));
        assertEquals("video/webm", accept(AttachmentSamples.webm("V_VP8")));
        assertEquals("video/mp4", accept(AttachmentSamples.ftyp("M4A ", "avc1")));
    }

    /**
     * SVG、HTML、XML、AVI、Theora 和 3GPP 都不在名单里。
     */
    @Test
    void sniffRejectsMarkupAndBroadVideo() {
        assertRejected(AttachmentSamples.text("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        assertRejected(AttachmentSamples.text("<!DOCTYPE html><html></html>"));
        assertRejected(AttachmentSamples.text("<html xmlns=\"http://www.w3.org/1999/xhtml\"></html>"));
        assertRejected(AttachmentSamples.text("<?xml version=\"1.0\"?><a/>"));
        assertRejected(AttachmentSamples.text("<xml></xml>"));
        assertRejected(AttachmentSamples.riff("AVI "));
        assertRejected(AttachmentSamples.ogg("theora"));
        assertRejected(AttachmentSamples.ftyp("3gp4", "isom"));
        assertRejected(AttachmentSamples.ftyp("qt  ", ""));
        assertRejected(AttachmentSamples.webm(""));
        assertRejected(new byte[0]);
        assertRejected(null);
    }

    /**
     * 图片超 10 MiB 拒绝，同样大小的视频仍可接受。超过 50 MiB 的一律拒绝。
     */
    @Test
    void limitsFollowTheDetectedType() {
        assertTrue(AttachmentMedia.decide(AttachmentSamples.pad(AttachmentSamples.png(), AttachmentMedia.IMAGE_LIMIT)).accepted());
        AttachmentMedia.Decision image = AttachmentMedia.decide(
                AttachmentSamples.pad(AttachmentSamples.png(), AttachmentMedia.IMAGE_LIMIT + 1));
        assertTrue(image.tooLarge());
        assertFalse(image.accepted());
        AttachmentMedia.Decision audio = AttachmentMedia.decide(
                AttachmentSamples.pad(AttachmentSamples.wav(), AttachmentMedia.AUDIO_LIMIT + 1));
        assertTrue(audio.tooLarge());
        byte[] video = AttachmentSamples.pad(AttachmentSamples.videoMp4(), 11 * 1024 * 1024);
        AttachmentMedia.Decision videoOk = AttachmentMedia.decide(video);
        assertTrue(videoOk.accepted());
        assertEquals("video/mp4", videoOk.mediaType());
        assertTrue(AttachmentMedia.decide(new byte[AttachmentMedia.VIDEO_LIMIT + 1]).tooLarge());
    }

    /**
     * 探测结果在名单里时返回类型。
     *
     * @param content 样本
     * @return 类型
     */
    private static String accept(byte[] content) {
        AttachmentMedia.Decision decision = AttachmentMedia.decide(content);
        assertTrue(decision.accepted(), decision.toString());
        return decision.mediaType();
    }

    /**
     * 认不出或被排除时既不接受也不算超限。
     *
     * @param content 样本，可以是 null
     */
    private static void assertRejected(byte[] content) {
        AttachmentMedia.Decision decision = AttachmentMedia.decide(content);
        assertFalse(decision.accepted());
        assertFalse(decision.tooLarge());
        assertNull(decision.mediaType());
    }
}
