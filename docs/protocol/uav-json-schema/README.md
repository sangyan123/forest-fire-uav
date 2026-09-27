# UAV Unified Data — JSON Schema（机器可执行协议规范）

**语义 owner：** 05号《UAV模拟与统一数据协议文档》V1.2（基线V1.3第4章：UAV协议语义 owner=05，机器来源=本目录）
**版本：** uav-json-schema-v1.0（对应协议 schemaVersion 1.0）
**日期：** 2026-09-27

## 文件清单

| 文件 | 消息 | Topic | 方向 |
| --- | --- | --- | --- |
| defs.schema.json | 公共定义（Header/13子模型/枚举/载荷） | — | — |
| uav-state.schema.json | UAV_STATE 全量状态（13子模型） | uav/{deviceId}/state | 上行 |
| uav-heartbeat.schema.json | UAV_HEARTBEAT 心跳（5s/次，3次丢失判OFFLINE） | uav/{deviceId}/state | 上行 |
| uav-telemetry.schema.json | UAV_TELEMETRY 高频轻量遥测（6子模型） | uav/{deviceId}/telemetry | 上行 |
| uav-media.schema.json | UAV_MEDIA 媒体元数据 | uav/{deviceId}/media | 上行 |
| uav-capability.schema.json | UAV_CAPABILITY 能力位 | uav/{deviceId}/state | 上行 |
| uav-mission-state.schema.json | UAV_MISSION_STATE 任务状态 | uav/{deviceId}/mission | 上行 |
| uav-environment.schema.json | UAV_ENVIRONMENT 环境信息 | uav/{deviceId}/event | 上行 |
| uav-event.schema.json | UAV_EVENT 事件（17类型） | uav/{deviceId}/event | 上行 |
| uav-command.schema.json | UAV_COMMAND 命令（9核心命令） | uav/{deviceId}/command | 下行 |
| uav-command-result.schema.json | UAV_COMMAND_RESULT 命令结果 | uav/{deviceId}/command/result | 上行 |
| examples/ | 取自05号文档的示例实例（回归测试基准） | — | — |

## 约定

- **JSON Schema draft-07**（工具链兼容性最优）；公共定义集中在 `defs.schema.json#/definitions`，各消息通过 `allOf + $ref` 组合，并用 `messageType: const` 锁定消息类型。
- 枚举值与 `docs/00-doc/enums.yaml` 逐字一致；数值参数（电量四级阈值等）唯一来源 `docs/00-doc/constants.yaml`，本目录不重复。
- **两套命令状态明确分层**：`DeviceCommandStatus`（设备侧，05号第42章9值，用于UAV_COMMAND_RESULT.status）与平台侧 `CommandStatus`（07/09号8值，用于REST命令记录）是两个层面，对应关系归一见 change-log ISSUE-004。
- 高度纪律：`TargetPoint.altitude` 必须与 `altitudeMode`（ELLIPSOID/RELATIVE_TO_TAKEOFF/AGL）同时出现，禁止裸高度值（基线第39章）。

## 校验方法

```bash
# 单条实例校验（ajv-cli，draft-07）
npx ajv-cli validate -s uav-state.schema.json -r defs.schema.json -d examples/uav-state.json

# 全量校验（每个example对同名schema）
for m in state heartbeat telemetry media capability mission-state environment event command command-result; do
  npx ajv-cli validate -s uav-$m.schema.json -r defs.schema.json -d examples/uav-$m.json
done
```

## 修改纪律

字段/枚举变更必须走基线第71章流程：修改05号主定义 → 更新本目录 → 同步 enums.yaml/openapi.yaml/引用文档 → 更新代码与测试 → 关闭Issue。
