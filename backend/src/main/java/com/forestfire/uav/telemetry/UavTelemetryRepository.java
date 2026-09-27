package com.forestfire.uav.telemetry;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UavTelemetryRepository extends JpaRepository<UavTelemetryEntity, Long> {

    /** 按 uav_id 取最新遥测（利用 idx_uav_telemetry_uav_time (uav_id, event_time DESC)） */
    List<UavTelemetryEntity> findByUavIdOrderByEventTimeDesc(UUID uavId, PageRequest pageRequest);

    default Optional<UavTelemetryEntity> findLatestByUavId(UUID uavId) {
        return findByUavIdOrderByEventTimeDesc(uavId, PageRequest.of(0, 1))
                .stream().findFirst();
    }
}
