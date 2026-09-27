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
 * 命令表实体 — 对应 db/migration/V1__baseline_schema.sql 的 uav_command（15 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                       → id（应用侧 UUID.randomUUID()）</li>
 *   <li>command_no VARCHAR(64) NOT NULL UNIQUE → command_no（"CMD-"+8位随机）</li>
 *   <li>uav_id UUID NOT NULL             → uav_id（device.id，按 device_code 查得）</li>
 *   <li>mission_id UUID                  → mission_id（MVP 不关联，可空不赋值）</li>
 *   <li>command_type VARCHAR(64) NOT NULL → command_type</li>
 *   <li>priority VARCHAR(32)             → priority（默认 "NORMAL"，可空）</li>
 *   <li>payload JSONB                    → payload（Map&lt;String,Object&gt;，
 *       @JdbcTypeCode(SqlTypes.JSON)，存 params 对象）</li>
 *   <li>status VARCHAR(32) NOT NULL      → status（创建时 "CREATED"；gateway 回调后为
 *       DeviceCommandStatus 值；gateway 不可达时 "FAILED"）</li>
 *   <li>created_by UUID                  → created_by（MVP 无鉴权，可空不赋值）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL  → created_at（应用赋值，NOT NULL 覆盖）</li>
 *   <li>sent_at TIMESTAMPTZ              → sent_at（gateway 下发成功时赋值）</li>
 *   <li>ack_at TIMESTAMPTZ               → ack_at（回调 RECEIVED 时赋值）</li>
 *   <li>completed_at TIMESTAMPTZ         → completed_at（终态时赋值）</li>
 *   <li>error_code VARCHAR(64)           → error_code（gateway 不可达时 "GATEWAY_UNREACHABLE"）</li>
 *   <li>error_message TEXT               → error_message</li>
 * </ol>
 * <p>NOT NULL 列（id/command_no/uav_id/command_type/status/created_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "uav_command")
public class UavCommandEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: command_no VARCHAR(64) NOT NULL UNIQUE */
    @Column(name = "command_no", nullable = false)
    private String commandNo;

    /** DDL: uav_id UUID NOT NULL — 关联 uav_device.id（逻辑外键） */
    @Column(name = "uav_id", nullable = false)
    private UUID uavId;

    /** DDL: mission_id UUID */
    @Column(name = "mission_id")
    private UUID missionId;

    /** DDL: command_type VARCHAR(64) NOT NULL — CommandType 枚举值（enums.yaml 第2项） */
    @Column(name = "command_type", nullable = false)
    private String commandType;

    /** DDL: priority VARCHAR(32) */
    @Column(name = "priority")
    private String priority;

    /** DDL: payload JSONB — 命令参数对象 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private Map<String, Object> payload;

    /** DDL: status VARCHAR(32) NOT NULL — 命令记录状态 */
    @Column(name = "status", nullable = false)
    private String status;

    /** DDL: created_by UUID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** DDL: sent_at TIMESTAMPTZ */
    @Column(name = "sent_at")
    private Instant sentAt;

    /** DDL: ack_at TIMESTAMPTZ */
    @Column(name = "ack_at")
    private Instant ackAt;

    /** DDL: completed_at TIMESTAMPTZ */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** DDL: error_code VARCHAR(64) */
    @Column(name = "error_code")
    private String errorCode;

    /** DDL: error_message TEXT */
    @Column(name = "error_message")
    private String errorMessage;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCommandNo() { return commandNo; }
    public void setCommandNo(String commandNo) { this.commandNo = commandNo; }
    public UUID getUavId() { return uavId; }
    public void setUavId(UUID uavId) { this.uavId = uavId; }
    public UUID getMissionId() { return missionId; }
    public void setMissionId(UUID missionId) { this.missionId = missionId; }
    public String getCommandType() { return commandType; }
    public void setCommandType(String commandType) { this.commandType = commandType; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getAckAt() { return ackAt; }
    public void setAckAt(Instant ackAt) { this.ackAt = ackAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
