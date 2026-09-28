package com.forestfire.uav.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UavDeviceRepository extends JpaRepository<UavDeviceEntity, UUID> {

    /** 按设备编码（uavId，如 "UAV-001"）查询设备 */
    Optional<UavDeviceEntity> findByDeviceCode(String deviceCode);

    /** F09 调度候选：device_status 属于给定集合且未删除（ONLINE/IDLE/AIRBORNE） */
    List<UavDeviceEntity> findByDeviceStatusInAndDeletedAtIsNull(Collection<String> deviceStatuses);
}
