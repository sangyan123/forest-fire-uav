package com.forestfire.uav.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 火势预测表 Repository */
public interface FirePredictionRepository extends JpaRepository<FirePredictionEntity, UUID> {

    /** 某火情预测历史（新→旧；latest 取首行 baseTime 再取同批） */
    List<FirePredictionEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
