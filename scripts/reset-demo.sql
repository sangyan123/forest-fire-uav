-- =====================================================================
-- 演示数据重置（reset-demo）
-- 用法: scripts/reset-demo.sh（或 PowerShell 内执行其中的 psql 命令）
-- 目标: 一键恢复干净演示底座 —— 清空演示数据、无人机恢复满电巡航
-- 依据: 00基线第62章（reset-demo 重新加载 UAV/Fire/Mission/Telemetry/Media）
-- =====================================================================

-- 火情域（子表在前，虽无外键约束仍按逻辑顺序）
TRUNCATE fire_polygon;
TRUNCATE fire_track;
TRUNCATE fire_verification;
TRUNCATE fire_detection;
TRUNCATE fire_point;
TRUNCATE fire_incident;

-- 任务与调度域
TRUNCATE dispatch_record;
TRUNCATE mission_waypoint;
TRUNCATE mission;

-- 媒体与命令域
TRUNCATE media_file;
TRUNCATE uav_command_result;
TRUNCATE uav_command;

-- 遥测与原始数据（历史轨迹清空，让演示从"新巡检"开始）
TRUNCATE uav_telemetry;
TRUNCATE raw_uav_data;

-- 设备恢复在线（注册关系保留，免重新注册）
UPDATE uav_device SET device_status = 'ONLINE', updated_at = now();
