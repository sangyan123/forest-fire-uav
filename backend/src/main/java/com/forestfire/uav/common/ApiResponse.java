package com.forestfire.uav.common;

import java.util.UUID;

/**
 * 统一响应包裹：{"code":0,"message":"success","data":...,"requestId":"..."}
 *
 * @param code      0=成功；业务错误码见 {@link ErrorCode}
 * @param message   success 或错误说明
 * @param data      业务数据
 * @param requestId 本次请求追踪 ID（每响应生成）
 */
public record ApiResponse<T>(int code, String message, T data, String requestId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, UUID.randomUUID().toString());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> of(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, UUID.randomUUID().toString());
    }
}
