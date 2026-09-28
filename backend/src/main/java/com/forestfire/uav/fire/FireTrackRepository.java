package com.forestfire.uav.fire;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 火势跟踪表 Repository */
public interface FireTrackRepository extends JpaRepository<FireTrackEntity, UUID> {

    /** 最新一条跟踪（event_time 倒序取一） */
    Optional<FireTrackEntity> findFirstByIncidentIdOrderByEventTimeDesc(UUID incidentId);
}
