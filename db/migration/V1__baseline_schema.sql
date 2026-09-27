-- =====================================================================
-- 森林防火无人机智能系统 — 基线数据库 Schema（V1）
-- =====================================================================
-- 语义来源:  04号《数据库设计文档》V1.2（00基线V1.3第4章：Schema owner=04，
--            机器来源=DB Migration，基线第75章）
-- 版本:      migration V1（对应 04号 V1.2，2026-09-27）
--
-- 环境要求:
--   PostgreSQL 16+ （04号第1章）
--   PostGIS 扩展   （geometry 列依赖；本文件头自动创建）
--
-- 设计要点（忠实于04号，不在迁移中擅自增强）:
--   1. 全部为逻辑外键（无 REFERENCES 约束），关联完整性由应用层保证；
--   2. 主键 id UUID 无数据库 DEFAULT —— UUID 由应用侧生成；
--   3. uav_telemetry 按月 Range 分区或 TimescaleDB hypertable 为04号既定
--      设计方向但未定稿，本版为普通表，分区迁移待运维评审后另行版本（V3+）；
--   4. 时间列统一 TIMESTAMPTZ（UTC），空间列统一 SRID 4326；
--   5. 本文件由04号文档DDL程序化提取组装，禁止手工增删字段；
--      任何 Schema 变更先改04号文档，再出新版本迁移（基线第71/75章）。
-- =====================================================================

-- PostGIS 扩展（geometry 列依赖）
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE uav_device (
    id UUID PRIMARY KEY,
    device_code VARCHAR(64) NOT NULL UNIQUE,
    device_name VARCHAR(128) NOT NULL,
    manufacturer VARCHAR(64),
    model VARCHAR(128),
    serial_number VARCHAR(128),
    firmware_version VARCHAR(128),

    adapter_type VARCHAR(32) NOT NULL,
    device_status VARCHAR(32) NOT NULL,

    home_latitude DOUBLE PRECISION,
    home_longitude DOUBLE PRECISION,
    home_geometry geometry(Point, 4326),

    current_latitude DOUBLE PRECISION,
    current_longitude DOUBLE PRECISION,
    current_height DOUBLE PRECISION,
    current_geometry geometry(Point, 4326),

    battery_percent NUMERIC(5,2),
    remaining_flight_time INTEGER,

    last_online_at TIMESTAMPTZ,
    last_telemetry_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE uav_capability (
    id UUID PRIMARY KEY,
    uav_id UUID NOT NULL,
    capability_code VARCHAR(64) NOT NULL,
    supported BOOLEAN NOT NULL DEFAULT FALSE,
    capability_config JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    UNIQUE (uav_id, capability_code)
);

CREATE TABLE uav_payload (
    id UUID PRIMARY KEY,
    uav_id UUID NOT NULL,
    payload_type VARCHAR(64) NOT NULL,
    payload_name VARCHAR(128),
    manufacturer VARCHAR(64),
    model VARCHAR(128),
    serial_number VARCHAR(128),
    status VARCHAR(32),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE uav_telemetry (
    id BIGSERIAL,
    uav_id UUID NOT NULL,

    event_time TIMESTAMPTZ NOT NULL,

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    geometry geometry(Point,4326),

    height DOUBLE PRECISION,
    relative_height DOUBLE PRECISION,

    horizontal_speed DOUBLE PRECISION,
    vertical_speed DOUBLE PRECISION,

    heading DOUBLE PRECISION,
    pitch DOUBLE PRECISION,
    roll DOUBLE PRECISION,

    gps_satellites INTEGER,
    rtk_satellites INTEGER,
    rtk_status VARCHAR(32),

    battery_percent NUMERIC(5,2),
    remaining_flight_time INTEGER,

    gimbal_pitch DOUBLE PRECISION,
    gimbal_roll DOUBLE PRECISION,
    gimbal_yaw DOUBLE PRECISION,

    flight_mode VARCHAR(64),
    mission_id UUID,

    communication_status VARCHAR(32),

    raw_data_id UUID,

    created_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (id, event_time)
);

CREATE TABLE raw_uav_data (
    id UUID PRIMARY KEY,

    source_type VARCHAR(32) NOT NULL,
    source_device_id VARCHAR(128),

    topic VARCHAR(512),

    event_time TIMESTAMPTZ,

    payload JSONB NOT NULL,

    payload_hash VARCHAR(128),

    received_at TIMESTAMPTZ NOT NULL,

    processed_at TIMESTAMPTZ,

    process_status VARCHAR(32),

    error_message TEXT
);

CREATE TABLE media_file (
    id UUID PRIMARY KEY,

    uav_id UUID,
    mission_id UUID,

    media_type VARCHAR(32) NOT NULL,
    mime_type VARCHAR(128),

    storage_provider VARCHAR(32),
    bucket_name VARCHAR(128),
    object_key TEXT NOT NULL,

    file_size BIGINT,

    checksum VARCHAR(128),

    captured_at TIMESTAMPTZ,

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    geometry geometry(Point,4326),

    width INTEGER,
    height INTEGER,

    frame_rate DOUBLE PRECISION,

    metadata JSONB,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE algorithm_model (
    id UUID PRIMARY KEY,

    model_code VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    model_type VARCHAR(64) NOT NULL,

    version VARCHAR(64) NOT NULL,

    framework VARCHAR(64),

    input_schema_version VARCHAR(64),
    output_schema_version VARCHAR(64),

    model_path TEXT,

    status VARCHAR(32) NOT NULL,

    metrics JSONB,

    description TEXT,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    UNIQUE (model_code, version)
);

CREATE TABLE algorithm_task (
    id UUID PRIMARY KEY,

    algorithm_model_id UUID NOT NULL,

    algorithm_config_version VARCHAR(64),

    dataset_version VARCHAR(64),

    task_type VARCHAR(64) NOT NULL,

    input_ref JSONB,

    status VARCHAR(32) NOT NULL,

    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    inference_time_ms INTEGER,

    error_message TEXT,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE algorithm_result (
    id UUID PRIMARY KEY,

    task_id UUID NOT NULL,
    model_id UUID NOT NULL,

    algorithm_config_version VARCHAR(64),

    dataset_version VARCHAR(64),

    result_type VARCHAR(64) NOT NULL,

    confidence NUMERIC(6,5),

    result JSONB NOT NULL,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fire_detection (
    id UUID PRIMARY KEY,

    algorithm_result_id UUID,

    uav_id UUID,
    media_id UUID,

    detection_type VARCHAR(32) NOT NULL,

    confidence NUMERIC(6,5),

    bbox JSONB,

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    geometry geometry(Point,4326),

    detection_time TIMESTAMPTZ NOT NULL,

    model_id UUID,

    temporal_confirmed BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE thermal_anomaly (
    id UUID PRIMARY KEY,

    algorithm_result_id UUID,

    uav_id UUID,
    media_id UUID,

    max_temperature DOUBLE PRECISION,
    mean_temperature DOUBLE PRECISION,
    background_temperature DOUBLE PRECISION,

    temperature_delta DOUBLE PRECISION,

    area DOUBLE PRECISION,

    confidence NUMERIC(6,5),

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    geometry geometry(Point,4326),

    anomaly_time TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fire_point (
    id UUID PRIMARY KEY,

    incident_id UUID,

    detection_id UUID,

    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,

    altitude DOUBLE PRECISION,

    geometry geometry(Point,4326) NOT NULL,

    position_error_radius DOUBLE PRECISION,

    location_method VARCHAR(32) NOT NULL,

    confidence NUMERIC(6,5),

    source_uav_id UUID,

    detected_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fire_verification (
    id UUID PRIMARY KEY,

    incident_id UUID NOT NULL,

    mission_id UUID,

    verification_type VARCHAR(32),

    rgb_score NUMERIC(6,5),
    thermal_score NUMERIC(6,5),
    temporal_score NUMERIC(6,5),
    spatial_score NUMERIC(6,5),

    final_score NUMERIC(6,5),

    result VARCHAR(32),

    verifier_type VARCHAR(32),

    verified_at TIMESTAMPTZ,

    remark TEXT,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fire_polygon (
    id UUID PRIMARY KEY,

    incident_id UUID NOT NULL,

    polygon geometry(MultiPolygon,4326) NOT NULL,

    area_square_meter DOUBLE PRECISION,

    perimeter_meter DOUBLE PRECISION,

    confidence NUMERIC(6,5),

    source_media_id UUID,

    detected_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fire_track (
    id UUID PRIMARY KEY,

    incident_id UUID NOT NULL,

    event_time TIMESTAMPTZ NOT NULL,

    center geometry(Point,4326),

    direction DOUBLE PRECISION,

    speed DOUBLE PRECISION,

    area_square_meter DOUBLE PRECISION,

    area_growth_rate DOUBLE PRECISION,

    trend VARCHAR(32),

    confidence NUMERIC(6,5),

    source_uav_id UUID,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE risk_area (
    id UUID PRIMARY KEY,

    area_code VARCHAR(64),
    area_name VARCHAR(128),

    geometry geometry(MultiPolygon,4326) NOT NULL,

    risk_score DOUBLE PRECISION,

    risk_level VARCHAR(32),

    model_id UUID,

    evaluation_time TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE risk_area_assessment (
    id UUID PRIMARY KEY,

    area_id UUID NOT NULL,

    assessed_at TIMESTAMPTZ NOT NULL,

    risk_score DOUBLE PRECISION,

    risk_level VARCHAR(32),

    model_version VARCHAR(64),

    algorithm_config_version VARCHAR(64),

    dataset_version VARCHAR(64),

    factors JSONB,

    created_at TIMESTAMPTZ NOT NULL,

    UNIQUE (area_id, assessed_at)
);

CREATE TABLE risk_feature (
    id UUID PRIMARY KEY,

    risk_area_id UUID NOT NULL,

    feature_type VARCHAR(64) NOT NULL,

    feature_value DOUBLE PRECISION,

    weight DOUBLE PRECISION,

    contribution DOUBLE PRECISION,

    source VARCHAR(128),

    evaluation_time TIMESTAMPTZ NOT NULL
);

CREATE TABLE patrol_area (
    id UUID PRIMARY KEY,

    risk_area_id UUID,

    geometry geometry(Polygon,4326),

    priority INTEGER,

    reason JSONB,

    generated_at TIMESTAMPTZ NOT NULL,

    status VARCHAR(32)
);

CREATE TABLE mission (
    id UUID PRIMARY KEY,

    mission_no VARCHAR(64) NOT NULL UNIQUE,

    mission_type VARCHAR(64) NOT NULL,

    incident_id UUID,
    risk_area_id UUID,
    patrol_area_id UUID,

    assigned_uav_id UUID,

    priority VARCHAR(32),

    status VARCHAR(32) NOT NULL,

    planned_start_at TIMESTAMPTZ,
    planned_end_at TIMESTAMPTZ,

    actual_start_at TIMESTAMPTZ,
    actual_end_at TIMESTAMPTZ,

    origin geometry(Point,4326),

    target geometry(Point,4326),

    created_by UUID,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE mission_waypoint (
    id UUID PRIMARY KEY,

    mission_id UUID NOT NULL,

    sequence_no INTEGER NOT NULL,

    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,

    geometry geometry(Point,4326),

    altitude DOUBLE PRECISION,

    speed DOUBLE PRECISION,

    heading DOUBLE PRECISION,

    action JSONB,

    created_at TIMESTAMPTZ NOT NULL,

    UNIQUE (mission_id, sequence_no)
);

CREATE TABLE dispatch_record (
    id UUID PRIMARY KEY,

    mission_id UUID NOT NULL,

    incident_id UUID,

    selected_uav_id UUID,

    algorithm_type VARCHAR(64),

    score DOUBLE PRECISION,

    factors JSONB,

    estimated_distance DOUBLE PRECISION,

    estimated_arrival_seconds INTEGER,

    decision_time TIMESTAMPTZ NOT NULL,

    result VARCHAR(32),

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE assessment_report (
    id UUID PRIMARY KEY,

    incident_id UUID,

    report_no VARCHAR(64) NOT NULL UNIQUE,

    report_type VARCHAR(64),

    before_media_id UUID,
    after_media_id UUID,

    burned_area_square_meter DOUBLE PRECISION,

    affected_forest_area_square_meter DOUBLE PRECISION,

    affected_road_length_meter DOUBLE PRECISION,

    affected_facility_area_square_meter DOUBLE PRECISION,

    result JSONB,

    created_by UUID,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE assessment_area (
    id UUID PRIMARY KEY,

    report_id UUID NOT NULL,

    area_type VARCHAR(64),

    geometry geometry(MultiPolygon,4326),

    area_square_meter DOUBLE PRECISION,

    confidence NUMERIC(6,5),

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fire_prediction (
    id UUID PRIMARY KEY,

    incident_id UUID NOT NULL,

    model_id UUID NOT NULL,

    base_time TIMESTAMPTZ NOT NULL,

    forecast_minutes INTEGER NOT NULL,

    predicted_geometry geometry(MultiPolygon,4326),

    predicted_area_square_meter DOUBLE PRECISION,

    confidence NUMERIC(6,5),

    environmental_input JSONB,

    prediction_result JSONB,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fleet_assignment (
    id UUID PRIMARY KEY,

    incident_id UUID NOT NULL,

    mission_id UUID NOT NULL,

    uav_id UUID NOT NULL,

    role VARCHAR(64),

    priority INTEGER,

    assignment_score DOUBLE PRECISION,

    assignment_reason JSONB,

    status VARCHAR(32),

    assigned_at TIMESTAMPTZ NOT NULL,

    completed_at TIMESTAMPTZ
);

CREATE TABLE fire_incident (
    id UUID PRIMARY KEY,

    incident_no VARCHAR(64) NOT NULL UNIQUE,

    title VARCHAR(255),

    status VARCHAR(32) NOT NULL,

    level VARCHAR(32),

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,

    geometry geometry(Point,4326),

    first_detected_at TIMESTAMPTZ,

    confirmed_at TIMESTAMPTZ,

    resolved_at TIMESTAMPTZ,

    current_area_square_meter DOUBLE PRECISION,

    max_area_square_meter DOUBLE PRECISION,

    current_temperature DOUBLE PRECISION,

    source_uav_id UUID,

    source_detection_id UUID,

    verification_status VARCHAR(32),

    false_alarm BOOLEAN DEFAULT FALSE,

    description TEXT,

    extra JSONB,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE alert (
    id UUID PRIMARY KEY,

    alert_type VARCHAR(64) NOT NULL,

    level VARCHAR(32) NOT NULL,

    source_type VARCHAR(64),

    source_id UUID,

    incident_id UUID,

    title VARCHAR(255),

    content TEXT,

    status VARCHAR(32) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    acknowledged_at TIMESTAMPTZ,

    resolved_at TIMESTAMPTZ,

    acknowledged_by UUID
);

CREATE TABLE uav_command (
    id UUID PRIMARY KEY,

    command_no VARCHAR(64) NOT NULL UNIQUE,

    uav_id UUID NOT NULL,

    mission_id UUID,

    command_type VARCHAR(64) NOT NULL,

    priority VARCHAR(32),

    payload JSONB,

    status VARCHAR(32) NOT NULL,

    created_by UUID,

    created_at TIMESTAMPTZ NOT NULL,

    sent_at TIMESTAMPTZ,

    ack_at TIMESTAMPTZ,

    completed_at TIMESTAMPTZ,

    error_code VARCHAR(64),

    error_message TEXT
);

CREATE TABLE uav_command_result (
    id UUID PRIMARY KEY,

    command_id UUID NOT NULL,

    execution_status VARCHAR(32),

    device_response JSONB,

    execution_start_at TIMESTAMPTZ,

    execution_end_at TIMESTAMPTZ,

    error_code VARCHAR(64),

    error_message TEXT,

    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE audit_log (
    id BIGSERIAL,

    user_id UUID,

    operation_type VARCHAR(64),

    resource_type VARCHAR(64),

    resource_id UUID,

    request_data JSONB,

    response_data JSONB,

    ip_address INET,

    user_agent TEXT,

    created_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (id, created_at)
);

CREATE TABLE sys_user (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    real_name VARCHAR(128),
    status VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE sys_role (
    id UUID PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL UNIQUE,
    role_name VARCHAR(128) NOT NULL
);

CREATE TABLE sys_permission (
    id UUID PRIMARY KEY,
    permission_code VARCHAR(128) NOT NULL UNIQUE,
    permission_name VARCHAR(128)
);

-- =====================================================================
-- 索引（04号：遥测复合索引 + GIST空间索引；同名索引已去重）
-- =====================================================================

CREATE INDEX idx_uav_telemetry_uav_time
ON uav_telemetry (uav_id, event_time DESC);

CREATE INDEX idx_uav_telemetry_geometry
ON uav_telemetry
USING GIST (geometry);

CREATE INDEX idx_fire_polygon_geometry
ON fire_polygon
USING GIST (polygon);

CREATE INDEX idx_uav_device_geometry
ON uav_device USING GIST(current_geometry);

CREATE INDEX idx_fire_incident_geometry
ON fire_incident USING GIST(geometry);

CREATE INDEX idx_fire_point_geometry
ON fire_point USING GIST(geometry);

CREATE INDEX idx_fire_polygon_polygon
ON fire_polygon USING GIST(polygon);

CREATE INDEX idx_risk_area_geometry
ON risk_area USING GIST(geometry);
