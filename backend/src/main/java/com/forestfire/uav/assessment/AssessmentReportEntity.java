package com.forestfire.uav.assessment;

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
 * 灾后评估报告表实体 — 对应 db/migration/V1__baseline_schema.sql 的 assessment_report（13 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                          → id（应用侧 UUID.randomUUID()）</li>
 *   <li>incident_id UUID                     → incident_id（关联 fire_incident.id，逻辑外键）</li>
 *   <li>report_no VARCHAR(64) NOT NULL UNIQUE → report_no（"RPT-INC-"+incidentNo 后4位，按事件稳定，幂等覆盖时不变）</li>
 *   <li>report_type VARCHAR(64)              → report_type（AssessmentReportType，一期 POST_FIRE_ASSESSMENT）</li>
 *   <li>before_media_id UUID                 → before_media_id（灾前影像，一期 mock 不赋值，可空）</li>
 *   <li>after_media_id UUID                  → after_media_id（灾后影像，一期 mock 不赋值，可空）</li>
 *   <li>burned_area_square_meter DOUBLE PRECISION → burned_area_square_meter（过火总面积）</li>
 *   <li>affected_forest_area_square_meter DOUBLE PRECISION → affected_forest_area_square_meter（受影响林地）</li>
 *   <li>affected_road_length_meter DOUBLE PRECISION → affected_road_length_meter（受影响道路长度米）</li>
 *   <li>affected_facility_area_square_meter DOUBLE PRECISION → affected_facility_area_square_meter（受影响设施面积）</li>
 *   <li>result JSONB                         → result（{model, shrinkRate, severityRatios, seed}）</li>
 *   <li>created_by UUID                      → created_by（一期不赋值，可空）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL      → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>报告版本策略（constants.yaml assessment.report_version_strategy=idempotent_overwrite）：
 * 同一 incident_id 调用评估时先删旧报告+旧分带再插新，一事件一份现行报告，report_no 按事件稳定。</p>
 */
@Entity
@Table(name = "assessment_report")
public class AssessmentReportEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "report_no", nullable = false)
    private String reportNo;

    @Column(name = "report_type")
    private String reportType;

    @Column(name = "before_media_id")
    private UUID beforeMediaId;

    @Column(name = "after_media_id")
    private UUID afterMediaId;

    @Column(name = "burned_area_square_meter")
    private Double burnedAreaSquareMeter;

    @Column(name = "affected_forest_area_square_meter")
    private Double affectedForestAreaSquareMeter;

    @Column(name = "affected_road_length_meter")
    private Double affectedRoadLengthMeter;

    @Column(name = "affected_facility_area_square_meter")
    private Double affectedFacilityAreaSquareMeter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result")
    private Map<String, Object> result;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public UUID getBeforeMediaId() { return beforeMediaId; }
    public void setBeforeMediaId(UUID beforeMediaId) { this.beforeMediaId = beforeMediaId; }
    public UUID getAfterMediaId() { return afterMediaId; }
    public void setAfterMediaId(UUID afterMediaId) { this.afterMediaId = afterMediaId; }
    public Double getBurnedAreaSquareMeter() { return burnedAreaSquareMeter; }
    public void setBurnedAreaSquareMeter(Double burnedAreaSquareMeter) { this.burnedAreaSquareMeter = burnedAreaSquareMeter; }
    public Double getAffectedForestAreaSquareMeter() { return affectedForestAreaSquareMeter; }
    public void setAffectedForestAreaSquareMeter(Double affectedForestAreaSquareMeter) { this.affectedForestAreaSquareMeter = affectedForestAreaSquareMeter; }
    public Double getAffectedRoadLengthMeter() { return affectedRoadLengthMeter; }
    public void setAffectedRoadLengthMeter(Double affectedRoadLengthMeter) { this.affectedRoadLengthMeter = affectedRoadLengthMeter; }
    public Double getAffectedFacilityAreaSquareMeter() { return affectedFacilityAreaSquareMeter; }
    public void setAffectedFacilityAreaSquareMeter(Double affectedFacilityAreaSquareMeter) { this.affectedFacilityAreaSquareMeter = affectedFacilityAreaSquareMeter; }
    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
