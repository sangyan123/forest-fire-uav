package com.forestfire.uav.common;

/**
 * 业务异常：携带 {@link ErrorCode} 与可读信息。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
