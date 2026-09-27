# 森林防火无人机智能感知与闭环决策系统

> 核心链路：**感知 → 分析 → 决策 → 执行 → 反馈**（无人机多源数据 → 统一协议 → 算法分析 → 业务事件 → 任务决策 → 无人机执行 → 结果回流）

## 当前状态

- 设计基线：**V1.3 已冻结**（`docs/00-doc/active/`），01~11 号文档已按基线第 73 章完成回写同步
- 机器可执行来源：enums / constants / baseline-metrics / openapi / UAV JSON Schema / DB Migration / Algorithm & Auth Config **全部建立并校验通过**
- 阶段：**Phase 0 项目初始化**（基线第 23 章阶段表）；下一步 Phase 1 基础设施

## 目录速览

```
docs/00-doc/        基线与治理（enums/constants/metrics/branch-policy/change-log，active+archive）
docs/api/           OpenAPI（API机器来源，owner=07号）
docs/protocol/      UAV统一数据JSON Schema（协议机器来源，owner=05号）
db/migration/       数据库迁移（Schema机器来源，owner=04号，PostgreSQL16+PostGIS）
config/             算法/认证 运行时配置（镜像constants.yaml，禁止另创数值）
backend|ai-service|uav-gateway|mock-uav|frontend   五大工程组件（Phase 1起填充）
deploy/             DEV中间件栈（PostGIS/Redis/Kafka/MQTT/MinIO）
.github/workflows/  CI（含基线配置校验job）
```

## 快速开始（DEV）

```bash
cd deploy
docker compose up -d          # 五中间件全UP；首次启动自动执行 db/migration（V1建表+V2权限种子）
docker compose ps             # 验收：全部 healthy/running
```

- PostgreSQL: localhost:5432（库 forest_fire；PostGIS 已启用）
- MinIO Console: http://localhost:9001
- MQTT: localhost:1883（**仅DEV匿名**；生产必须 TLS+ACL，见10号）

## 文档索引

| 内容 | 位置 |
| --- | --- |
| 设计基线（唯一生效） | docs/00-doc/active/00_跨文档设计基线_V1.3.md |
| 变更与Issue登记 | docs/00-doc/change-log.md |
| API 规范 | docs/api/openapi.yaml |
| UAV 协议 Schema | docs/protocol/uav-json-schema/README.md |
| 部署与运维 | docs/10-ops/ |

## 开发规范

见 [CONTRIBUTING.md](CONTRIBUTING.md) 与 `docs/00-doc/branch-policy.yaml`（main 禁止直接 Push；PR + Review + CI 通过方可合并）。
