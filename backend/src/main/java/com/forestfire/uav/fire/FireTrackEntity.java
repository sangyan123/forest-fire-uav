package com.forestfire.uav.fire;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 火势跟踪表实体 — 对应 db/migration/V1__baseline_schema.sql 的 fire_track（12 列）。
 *
 * <p>【DDL 逐列核对记录】（列名 @Column(name=...) 与 DDL 逐字一致）：</p>
 * <ol>
 *   <li>id UUID PK                        → id（应用侧 UUID.randomUUID()）</li>
 *   <li>incident_id UUID NOT NULL         → incident_id（关联 fire_incident.id）</li>
 *   <li>event_time TIMESTAMPTZ NOT NULL   → event_time（跟踪时刻，应用赋值 now，NOT NULL 覆盖）</li>
 *   <li>center geometry(Point,4326)       → center（分析所用火点中心，hibernate-spatial 映射）</li>
 *   <li>direction DOUBLE PRECISION        → direction（F06 扩展方向，度）</li>
 *   <li>speed DOUBLE PRECISION            → speed（F06 扩展速度）</li>
 *   <li>area_square_meter DOUBLE PRECISION → area_square_meter（取同轮 F05 面积，F06 mock 不单独返回）</li>
 *   <li>area_growth_rate DOUBLE PRECISION → area_growth_rate（F06 面积增长率）</li>
 *   <li>trend VARCHAR(32)                 → trend（F06 趋势，如 EXPANDING）</li>
 *   <li>confidence NUMERIC(6,5)           → confidence（F06 mock 不返回，可空不赋值）</li>
 *   <li>source_uav_id UUID                → source_uav_id（透传 incident.source_uav_id，可空）</li>
 *   <li>created_at TIMESTAMPTZ NOT NULL   → created_at（应用赋值，NOT NULL 覆盖）</li>
 * </ol>
 * <p>NOT NULL 列（id/incident_id/event_time/created_at）全部由应用赋值。</p>
 */
@Entity
@Table(name = "fire_track")
public class FireTrackEntity {

    /** DDL: id UUID PRIMARY KEY（应用生成） */
    @Id
    @Column(name = "id")
    private UUID id;

    /** DDL: incident_id UUID NOT NULL — 关联 fire_incident.id（逻辑外键） */
    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    /** DDL: event_time TIMESTAMPTZ NOT NULL — 跟踪时刻 */
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    /** DDL: center geometry(Point,4326) — hibernate-spatial 映射（可空） */
    @Column(name = "center")
    private Point center;

    /** DDL: direction DOUBLE PRECISION — 扩展方向（度） */
    @Column(name = "direction")
    private Double direction;

    /** DDL: speed DOUBLE PRECISION — 扩展速度 */
    @Column(name = "speed")
    private Double speed;

    /** DDL: area_square_meter DOUBLE PRECISION — 面积（同轮 F05 结果） */
    @Column(name = "area_square_meter")
    private Double areaSquareMeter;

    /** DDL: area_growth_rate DOUBLE PRECISION — 面积增长率 */
    @Column(name = "area_growth_rate")
    private Double areaGrowthRate;

    /** DDL: trend VARCHAR(32) — 趋势（EXPANDING 等） */
    @Column(name = "trend")
    private String trend;

    /** DDL: confidence NUMERIC(6,5)（F06 mock 不返回，可空） */
    @Column(name = "confidence")
    private BigDecimal confidence;

    /** DDL: source_uav_id UUID — 透传 incident.source_uav_id（可空） */
    @Column(name = "source_uav_id")
    private UUID sourceUavId;

    /** DDL: created_at TIMESTAMPTZ NOT NULL */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public Point getCenter() { return center; }
    public void setCenter(Point center) { this.center = center; }
    public Double getDirection() { return direction; }
    public void setDirection(Double direction) { this.direction = direction; }
    public Double getSpeed() { return speed; }
    public void setSpeed(Double speed) { this.speed = speed; }
    public Double getAreaSquareMeter() { return areaSquareMeter; }
    public void setAreaSquareMeter(Double areaSquareMeter) { this.areaSquareMeter = areaSquareMeter; }
    public Double getAreaGrowthRate() { return areaGrowthRate; }
    public void setAreaGrowthRate(Double areaGrowthRate) { this.areaGrowthRate = areaGrowthRate; }
    public String getTrend() { return trend; }
    public void setTrend(String trend) { this.trend = trend; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public UUID getSourceUavId() { return sourceUavId; }
    public void setSourceUavId(UUID sourceUavId) { this.sourceUavId = sourceUavId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
