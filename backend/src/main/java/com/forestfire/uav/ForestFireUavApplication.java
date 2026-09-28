package com.forestfire.uav;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 森林防火无人机智能系统 — backend MVP 走线骨架。
 *
 * <p>范围：设备（uav_device）/ 遥测接入（uav_telemetry）/ 命令下发与回调
 * （uav_command / uav_command_result）。</p>
 *
 * <p>D5：{@code @EnableScheduling} 启用设备心跳超时离线检测定时任务
 * （{@link com.forestfire.uav.device.DeviceOfflineScheduler}，每 5 秒扫描，
 * 超阈值设备置 OFFLINE）。</p>
 */
@SpringBootApplication
@EnableScheduling
public class ForestFireUavApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForestFireUavApplication.class, args);
    }
}
