package com.gmscpt.server.api;

import com.gmscpt.server.kernel.input.HotkeyConflictException;
import com.gmscpt.server.service.ServiceException;
import com.gmscpt.server.store.RevConflictException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 统一错误体：{@code {code,message,details,traceId}}。
 *
 * <p>旧版的失败路径大量只写 {@code System.err}，打包成没有控制台的 exe 之后
 * 用户看到的就是「脚本在跑但什么都不发生」。这里保证任何失败都有可读原因。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, Object>> handleService(ServiceException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), null);
    }

    @ExceptionHandler(RevConflictException.class)
    public ResponseEntity<Map<String, Object>> handleRev(RevConflictException e) {
        return body(409, "REV_CONFLICT", e.getMessage(),
                Map.of("expected", e.getExpected(), "actual", e.getActual()));
    }

    @ExceptionHandler(HotkeyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleHotkey(HotkeyConflictException e) {
        return body(409, "ACCEL_CONFLICT", e.getMessage(), Map.of("accelerator", e.getAccelerator()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException e) {
        return body(400, "VALIDATION_FAILED", e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("参数不合法");
        return body(400, "VALIDATION_FAILED", msg, null);
    }

    /**
     * SSE 连接断开后的收尾异常——**必须单独吞掉**。
     *
     * <p>为什么需要这个分支：{@code text/event-stream} 的响应已经带着 SSE 的 Content-Type，
     * 客户端断开后响应不可再写。如果让它落到 {@link #handleOther}，
     * 会尝试把一个 JSON 错误体写进 SSE 响应，抛
     * {@code HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap]
     * with preset Content-Type 'text/event-stream'}——
     * 于是每次浏览器关闭标签页都会在控制台刷两条 ERROR，把真正的错误淹掉。
     *
     * <p>这类异常是**正常生命周期**（客户端断开），不是故障，所以只记 debug。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("SSE 连接已断开，忽略收尾异常: {}", e.getMessage());
    }

    /**
     * 兜底：即使异常发生在 SSE 请求上，也不能尝试写 JSON。
     * 通过检查响应的 Content-Type 判断，避免污染 SSE 流。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e, HttpServletResponse response) {
        if (isEventStream(response)) {
            log.debug("SSE 请求上的异常，不再写响应体: {}", e.toString());
            return null;
        }
        String traceId = UUID.randomUUID().toString();
        log.error("未处理异常 traceId={}", traceId, e);
        return body(500, "INTERNAL", "服务内部错误，详见后端日志（traceId=" + traceId + "）", null);
    }

    private static boolean isEventStream(HttpServletResponse response) {
        String contentType = response == null ? null : response.getContentType();
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private ResponseEntity<Map<String, Object>> body(int status, String code, String message, Object details) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message == null ? code : message);
        if (details != null) out.put("details", details);
        return ResponseEntity.status(status).body(out);
    }
}
