package com.forestfire.uav.fire;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 火情核验表 Repository */
public interface FireVerificationRepository extends JpaRepository<FireVerificationEntity, UUID> {

    /** 取 incident 最新一次核验（详情页用） */
    Optional<FireVerificationEntity> findFirstByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
