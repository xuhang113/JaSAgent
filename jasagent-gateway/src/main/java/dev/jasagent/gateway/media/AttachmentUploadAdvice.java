package dev.jasagent.gateway.media;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 容器先于网关拒绝过大的上传时，仍回固定的 413。正文不带异常文本、口令或附件字节。
 */
@RestControllerAdvice
public class AttachmentUploadAdvice {
    /**
     * 把容器的大小限制换成和网关相同的错误码。不把异常说明写进响应。
     *
     * @param ignored 容器抛出的大小异常，这里不读取它的说明
     * @return 413 和固定 JSON
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException ignored) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", null);
        body.put("errorCode", "PAYLOAD_TOO_LARGE");
        return ResponseEntity.status(413).body(body);
    }
}
