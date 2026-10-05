package com.forestfire.uav.command;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** 命令表 Repository（id 主键查询由 JpaRepository 自带） */
public interface UavCommandRepository extends JpaRepository<UavCommandEntity, UUID> {

    /**
     * 行锁读取（SELECT ... FOR UPDATE）：设备 EXECUTING 与终态回执可能在毫秒级先后到达，
     * 两个并发 PATCH 事务不加锁时最后写者赢，可能把终态覆盖回 EXECUTING（命令永久卡死）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from UavCommandEntity c where c.id = :id")
    Optional<UavCommandEntity> findByIdForUpdate(@Param("id") UUID id);
}
