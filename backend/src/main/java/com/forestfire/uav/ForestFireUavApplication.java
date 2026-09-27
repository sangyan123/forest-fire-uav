package com.forestfire.uav;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 森林防火无人机智能系统 — backend MVP 走线骨架。
 *
 * <p>范围：设备（uav_device）/ 遥测接入（uav_telemetry）/ 命令下发与回调
 * （uav_command / uav_command_result）。</p>
 */
@SpringBootApplication
public class ForestFireUavApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForestFireUavApplication.class, args);
    }
}
