package com.gmscpt.server.api;

import com.gmscpt.server.kernel.input.HotkeyConflictException;
import com.gmscpt.server.service.ServiceException;
import com.gmscpt.server.store.RevConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        String traceId = UUID.randomUUID().toString();
        log.error("未处理异常 traceId={}", traceId, e);
        return body(500, "INTERNAL", "服务内部错误，详见后端日志（traceId=" + traceId + "）", null);
    }

    private ResponseEntity<Map<String, Object>> body(int status, String code, String message, Object details) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message == null ? code : message);
        if (details != null) out.put("details", details);
        return ResponseEntity.status(status).body(out);
    }
}
