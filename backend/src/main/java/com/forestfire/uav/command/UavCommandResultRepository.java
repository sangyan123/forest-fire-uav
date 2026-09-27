package com.forestfire.uav.command;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UavCommandResultRepository extends JpaRepository<UavCommandResultEntity, UUID> {

    /** 取命令最新一条结果 */
    Optional<UavCommandResultEntity> findFirstByCommandIdOrderByCreatedAtDesc(UUID commandId);
}
