package com.forestfire.uav.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一包裹为 {@link ApiResponse}，错误码 40001/40002/40401。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        return ApiResponse.of(e.getErrorCode(), e.getMessage());
    }

    /** 参数错误 → 40001 */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpRequestMethodNotSupportedException.class,
            IllegalArgumentException.class
    })
    public ApiResponse<Void> handleBadRequest(Exception e) {
        return ApiResponse.of(ErrorCode.BAD_REQUEST,
                ErrorCode.BAD_REQUEST.getDefaultMessage() + ": " + e.getMessage());
    }

    /** 路径资源不存在 → 40401 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        return ApiResponse.of(ErrorCode.PATH_NOT_FOUND, ErrorCode.PATH_NOT_FOUND.getDefaultMessage());
    }

    /** 兜底内部错误 → 50000（约定外补充码） */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ApiResponse.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }
}
