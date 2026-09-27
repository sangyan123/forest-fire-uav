package com.forestfire.uav.command;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 命令表 Repository（id 主键查询由 JpaRepository 自带） */
public interface UavCommandRepository extends JpaRepository<UavCommandEntity, UUID> {
}
