package com.forestfire.uav.fire;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 火情检测表 Repository（id 主键查询由 JpaRepository 自带） */
public interface FireDetectionRepository extends JpaRepository<FireDetectionEntity, UUID> {
}
