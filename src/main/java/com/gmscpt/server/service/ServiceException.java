package com.gmscpt.server.service;

/** 服务层可读错误：带机器可读 code 与 HTTP 状态码，由 API 层统一翻译成错误体。 */
public class ServiceException extends RuntimeException {

    private final String code;
    private final int status;

    public ServiceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ServiceException(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return status;
    }

    public static ServiceException notFound(String message) {
        return new ServiceException("NOT_FOUND", 404, message);
    }

    public static ServiceException badRequest(String message) {
        return new ServiceException("VALIDATION_FAILED", 400, message);
    }

    public static ServiceException conflict(String code, String message) {
        return new ServiceException(code, 409, message);
    }

    public static ServiceException unavailable(String code, String message) {
        return new ServiceException(code, 503, message);
    }
}
