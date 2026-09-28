package com.forestfire.uav.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UavDeviceRepository extends JpaRepository<UavDeviceEntity, UUID> {

    /** 按设备编码（uavId，如 "UAV-001"）查询设备 */
    Optional<UavDeviceEntity> findByDeviceCode(String deviceCode);

    /** F09 调度候选：device_status 属于给定集合且未删除（ONLINE/IDLE/AIRBORNE） */
    List<UavDeviceEntity> findByDeviceStatusInAndDeletedAtIsNull(Collection<String> deviceStatuses);

    /**
     * D5 心跳超时离线标记（一条带子查询的批量 UPDATE）：
     * device_status != 'OFFLINE' 且未删除（deleted_at IS NULL）的设备，若其最新一条
     * uav_telemetry.event_time 早于 threshold（即心跳距今超过超时阈值）→
     * device_status 置 'OFFLINE'、updated_at 置 now。返回受影响行数。
     *
     * <p>子查询取 MAX(event_time)：无任何遥测的设备（MAX 为 NULL）不满足
     * {@code < threshold}，保持原状态；恢复路径零改动——telemetry-ingest 每次按
     * flight.status 重映射 device_status，消息恢复下一跳即自动离开 OFFLINE。</p>
     */
    @Modifying
    @Query(value = """
            UPDATE uav_device d
            SET device_status = 'OFFLINE',
                updated_at = :now
            WHERE d.device_status <> 'OFFLINE'
              AND d.deleted_at IS NULL
              AND (SELECT MAX(t.event_time) FROM uav_telemetry t WHERE t.uav_id = d.id) < :threshold
            """, nativeQuery = true)
    int markDevicesOffline(@Param("now") Instant now, @Param("threshold") Instant threshold);
}
