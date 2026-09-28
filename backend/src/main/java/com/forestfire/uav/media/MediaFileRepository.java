package com.forestfire.uav.media;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 媒体文件表 Repository（id 主键查询由 JpaRepository 自带） */
public interface MediaFileRepository extends JpaRepository<MediaFileEntity, UUID> {
}
