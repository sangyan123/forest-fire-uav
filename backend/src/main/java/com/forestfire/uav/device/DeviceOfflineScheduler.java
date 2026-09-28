package com.forestfire.uav.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * D5 设备心跳超时 → 离线检测定时任务。
 *
 * <p>每 {@value #SCAN_INTERVAL_MS} 毫秒执行一次：device_status != 'OFFLINE' 且未删除的
 * 设备，若其最新 uav_telemetry.event_time 距今超过
 * {@code device.offline-timeout-seconds}（application.yml 新增，默认 15 秒）→
 * device_status 置 'OFFLINE'、updated_at 置 now。落库为一条带子查询的批量 UPDATE，
 * 见 {@link UavDeviceRepository#markDevicesOffline}。</p>
 *
 * <p>恢复路径零改动：{@link com.forestfire.uav.telemetry.TelemetryIngestService} 每次
 * 按 flight.status 经 {@link FlightStatusMapper} 重映射 device_status，消息恢复的下一跳
 * 即自动离开 OFFLINE，本任务不做任何反向回写。</p>
 */
@Component
public class DeviceOfflineScheduler {

    /** 扫描周期（毫秒）：固定 5 秒 */
    private static final long SCAN_INTERVAL_MS = 5000L;

    private static final Logger log = LoggerFactory.getLogger(DeviceOfflineScheduler.class);

    private final UavDeviceRepository deviceRepository;
    private final long offlineTimeoutSeconds;

    public DeviceOfflineScheduler(
            UavDeviceRepository deviceRepository,
            @Value("${device.offline-timeout-seconds:15}") long offlineTimeoutSeconds) {
        this.deviceRepository = deviceRepository;
        this.offlineTimeoutSeconds = offlineTimeoutSeconds;
    }

    @Scheduled(fixedRate = SCAN_INTERVAL_MS)
    @Transactional
    public void markStaleDevicesOffline() {
        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(offlineTimeoutSeconds);
        int marked = deviceRepository.markDevicesOffline(now, threshold);
        if (marked > 0) {
            log.info("Heartbeat timeout: {} device(s) marked OFFLINE (timeout={}s, threshold={})",
                    marked, offlineTimeoutSeconds, threshold);
        } else {
            log.debug("Heartbeat check done: no device crossed the offline threshold (threshold={})",
                    threshold);
        }
    }
}
