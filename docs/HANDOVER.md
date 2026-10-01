# 项目交接文档（开发者版）

> 读者：接手本系统开发的工程师
> 配套：《README.md》（快速启动）、《docs/demo/客户演示讲稿.md》（现场演示）、《docs/00-doc/change-log.md》（全部变更与验收记录，**遇到"为什么是这样"先查它**）
> 更新：2026-09-28（对应提交 18d31ab）

------

## 1. 这是什么项目

森林防火无人机智能感知与闭环决策系统。设计文档 12 份（`docs/00-doc/active/` 为治理基线，`docs/01~11/` 为专项设计），核心链路：**感知 → 分析 → 决策 → 执行 → 反馈**。

## 2. 当前进度一句话

**MVP 演示版完成（D1~D5 全验收），整个项目约完成四分之一**：火情闭环主链路已通（mock 模式），Phase 6/7 高级功能、真实模型、真机适配、工程化（认证/WS/监控/测试体系）**均未开发**。偏差明细在各文档附录（03 第90章/06 第57章/07 第36章/10 第83章/11 第90章），open issues 见 `docs/00-doc/change-log.md`（当前 OPEN：ISSUE-001 F07权重、ISSUE-003 运维指标）。

## 3. 仓库地图

```
forest-fire-uav/
├── backend/          Spring Boot 3.3 + Java 21（57个Java文件）
│    └── com.forestfire.uav: device/ telemetry/ command/ media/ fire/ mission/ dispatch/ + common/config
├── uav-gateway/      Spring Boot 3.3（MQTT↔REST 桥，Paho客户端）
├── mock-uav/         Python FastAPI（无人机模拟器：1Hz状态/矩形航线/GOTO执行/火情场景）
├── ai-service/       Python FastAPI（算法 mock provider，AI_PROVIDER=mock|real 双轨）
├── frontend/         Vue3 + TS + Leaflet（监控页；public/tiles 898块离线卫星瓦片）
├── db/migration/     V1（34表+PostGIS）V2（RBAC种子）——postgres容器首启自动执行
├── deploy/           docker-compose.yml（DEV九服务栈）
├── scripts/          reset-demo.sh / validate-uav-schemas.js / fetch-tiles.mjs
└── docs/00-doc/      治理：enums.yaml / constants.yaml / baseline-metrics.yaml / branch-policy.yaml / change-log.md
```

## 4. 三条核心链路（读代码从这三条入手）

1. **遥测上行**：`mock-uav`（1Hz UAV_STATE/TELEMETRY）→ MQTT `uav/+/state` → `uav-gateway`（原样转发）→ `backend POST /api/v1/uavs/{id}/telemetry-ingest` → PostgreSQL
2. **命令下行**：`backend POST /api/v1/uavs/{id}/commands`（落库）→ gateway `POST /internal/commands` → MQTT `uav/{id}/command` → mock-uav 执行（GOTO 真实飞行）→ `uav/{id}/command/result` → gateway → `backend PATCH /api/v1/commands/{id}/status`
3. **火情闭环**：媒体采集（`uav/+/media`）→ gateway → `media-ingest` → ai-service detection（mock）→ 建 fire_detection/fire_point/fire_incident（100m/120s去重）→ 人工/API 触发 verification → CONFIRMED/FALSE_ALARM → mission+dispatch → 命令链路复飞

## 5. 哪些是真的，哪些是 mock（最关键的一张表）

| 组件 | 状态 |
|---|---|
| 后端业务/状态机/调度/去重/火情分析 | ✅ 真实实现 |
| 数据库 34 表 + PostGIS 空间查询 | ✅ 真实 |
| mock-uav 飞行/电池/场景/断联 | ✅ 真实（本来就是模拟器） |
| **ai-service 全部算法** | ⚠️ **mock provider**（确定性假输出，见 06 号第57章） |
| **媒体文件** | ⚠️ 只有元数据，无真实图片 |
| **认证鉴权** | ❌ 未接（auth-v1.yaml 已冻结待启用） |
| **WebSocket** | ❌ 前端 1~2s 轮询替代 |
| **Kafka** | ⚠️ 容器运行，业务链路未用 |
| **真机适配（DJI/MAVLink）** | ❌ 未做（Phase 9A） |

## 6. 本地跑起来与验证

```powershell
cd deploy && docker compose up -d --build   # 九服务；改代码后必须 --build（曾因漏build线上跑旧镜像）
bash scripts/reset-demo.sh                   # 重置演示数据+无人机满电
docker compose ps                            # 九容器全Up；postgres/redis/minio 应 healthy
```

- 页面 http://localhost:8181；后端宿主18080（容器内8080，经前端nginx同源代理，演示页访问不受影响）；模拟器 :8002；AI :8000；MinIO :9001
（注：本机8080/8081被其他项目占用，故改用8181/18080；uav-gateway无宿主端口，纯内部桥接）
- 自动化校验：`node scripts/validate-uav-schemas.js`（协议 schema）、`npx js-yaml docs/00-doc/*.yaml`（机器来源）
- 演示剧本：`docs/demo/客户演示讲稿.md`（五场景按钮）
- git 纪律：`docs/00-doc/branch-policy.yaml`（main 禁直推、PR+Review+CI）

## 7. 新人第一个任务建议（按批次选）

| 批次 | 任务 | 入口 |
|---|---|---|
| 第一批·不依赖决策 | WebSocket 接入（07 号第29章端点已定义） | backend 加 STOMP 端点 + frontend 替换轮询 |
| 第一批·不依赖决策 | 认证鉴权激活（auth-v1.yaml 已冻结） | backend 加 JWT 过滤器 + 前端登录页 |
| 第一批·不依赖决策 | Kafka 事件流（03 号 17 个核心事件已定义） | backend 事件发布 + gateway 消费示例 |
| 第一批·需先定数据源 | F01 真实模型训练 | 06 号第57章“真实化路径”列 |
| 第二批 | L1~L8 测试体系（08 号） | 从 backend 单元测试起 |
| 第二批 | 监控栈（10 号第63章已冻结选型） | deploy 加 prometheus/grafana/loki |
| 第三批 | DJI 适配（09 号第4章接口已冻结） | uav-gateway 加 DJI adapter |

**纪律红线**（基线治理，违反必返工）：枚举只改 `docs/00-doc/enums.yaml`；参数只改 `constants.yaml`；算法指标只改 `baseline-metrics.yaml`；DB 变更先改 04 号文档再出 migration；任何改动登记 `change-log.md`。

## 8. 已知坑（血的教训，提前知道）

1. **改代码后必须 `docker compose up -d --build`**——本地 build ≠ 镜像更新（D5 踩过）
2. **瓦片下载必须校验大小**—— Carto 返回 2049 字节"api key required"占位图但 HTTP 200（曾导致 363 块假瓦片）；现用 Esri 源，脚本已拒 <1KB
3. **mock 静默期(25s)必须大于平台离线阈值(15s)+扫描周期(5s)**——否则断联演示时平台来不及置 OFFLINE
4. **Git 勿在网页端直接编辑**——格式退化且与本地历史分叉，先 `git pull --rebase`
5. **ai-service 无测试时 pytest 退出码 5**——CI 已放行，但本地跑 pytest 别被退出码迷惑
