package com.forestfire.uav.common;

/**
 * 业务错误码（基线约定）：
 * <ul>
 *   <li>40001 参数错误（HTTP 400）</li>
 *   <li>40002 业务数据不存在（HTTP 404）</li>
 *   <li>40003 业务状态冲突/非法状态迁移（HTTP 400，火情状态机/任务状态机用）</li>
 *   <li>40401 路径资源不存在（HTTP 404）</li>
 *   <li>50000 内部错误（HTTP 500，约定外补充）</li>
 * </ul>
 */
public enum ErrorCode {

    BAD_REQUEST(40001, 400, "参数错误"),
    BUSINESS_NOT_FOUND(40002, 404, "业务数据不存在"),
    STATE_CONFLICT(40003, 400, "业务状态冲突"),
    PATH_NOT_FOUND(40401, 404, "路径资源不存在"),
    INTERNAL_ERROR(50000, 500, "内部错误");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
