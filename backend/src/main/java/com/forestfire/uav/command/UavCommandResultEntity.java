package com.forestfire.uav.command;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 命令结果表实体 — 对应 db/migration/V1__baseline_schema.sql 的 uav_command_result（9 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                        → id（应用侧 UUID.randomUUID()）</li>
 *   <li>command_id UUID NOT NULL          → command_id（uav_command.id）</li>
 *   <li>execution_status VARCHAR(32)      → execution_status（DeviceCommandStatus 终态值）</li>
 *   <li>device_response JSONB             → device_response（Map&lt;String,Object&gt; 回调原文）</li>
 *   <li>execution_start_at TIMESTAMPTZ    → execution_start_at（有 executionTimeMs 时
 *       = execution_end_at - executionTimeMs 推算）</li>
 *   <li>execution_end_at TIMESTAMPTZ      → execution_end_at（回调到达时刻）</li>
 *   <li>error_code VARCHAR(64)            → error_code（MVP 不区分细码，可空）</li>
 *   <li>error_message TEXT                → error_message（回调 message）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL   → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/command_id/created_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "uav_command_result")
public class UavCommandResultEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: command_id UUID NOT NULL — 关联 uav_command.id（逻辑外键） */
    @Column(name = "command_id", nullable = false)
    private UUID commandId;

    /** DDL: execution_status VARCHAR(32) — DeviceCommandStatus 终态值 */
    @Column(name = "execution_status")
    private String executionStatus;

    /** DDL: device_response JSONB — gateway 回调原文 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "device_response")
    private Map<String, Object> deviceResponse;

    /** DDL: execution_start_at TIMESTAMPTZ */
    @Column(name = "execution_start_at")
    private Instant executionStartAt;

    /** DDL: execution_end_at TIMESTAMPTZ */
    @Column(name = "execution_end_at")
    private Instant executionEndAt;

    /** DDL: error_code VARCHAR(64) */
    @Column(name = "error_code")
    private String errorCode;

    /** DDL: error_message TEXT */
    @Column(name = "error_message")
    private String errorMessage;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCommandId() { return commandId; }
    public void setCommandId(UUID commandId) { this.commandId = commandId; }
    public String getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(String executionStatus) { this.executionStatus = executionStatus; }
    public Map<String, Object> getDeviceResponse() { return deviceResponse; }
    public void setDeviceResponse(Map<String, Object> deviceResponse) { this.deviceResponse = deviceResponse; }
    public Instant getExecutionStartAt() { return executionStartAt; }
    public void setExecutionStartAt(Instant executionStartAt) { this.executionStartAt = executionStartAt; }
    public Instant getExecutionEndAt() { return executionEndAt; }
    public void setExecutionEndAt(Instant executionEndAt) { this.executionEndAt = executionEndAt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
