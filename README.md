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

**前置条件**：Docker Desktop 已安装并启动（无需本机 Java/Python/Node，全部容器化）；磁盘预留 ≥10GB（构建镜像）；内存 ≥8GB 推荐。

```bash
git clone <仓库地址>
cd forest-fire-uav/deploy

# 1. （仅国内网络）预拉基础镜像走加速器，避免直连 docker.io 超时
docker pull docker.m.daocloud.io/postgis/postgis:16-3.4 && docker tag docker.m.daocloud.io/postgis/postgis:16-3.4 postgis/postgis:16-3.4
docker pull dockerpull.cn/bitnami/kafka:3.7        && docker tag dockerpull.cn/bitnami/kafka:3.7 bitnami/kafka:3.7
docker pull docker.m.daocloud.io/library/redis:7-alpine && docker tag docker.m.daocloud.io/library/redis:7-alpine redis:7-alpine
docker pull docker.m.daocloud.io/library/eclipse-mosquitto:2 && docker tag docker.m.daocloud.io/library/eclipse-mosquitto:2 eclipse-mosquitto:2
docker pull docker.m.daocloud.io/minio/minio:latest  && docker tag docker.m.daocloud.io/minio/minio:latest minio/minio:latest

# 2. 启动（首次构建 backend/uav-gateway 两个 Maven 镜像约 5~15 分钟）
docker compose up -d --build

# 3. 验收：九容器全部 Up；数据库首次初始化自动执行 db/migration（34表+权限种子）
docker compose ps
```

启动后访问：

| 入口 | 地址 |
| --- | --- |
| **Demo 监控页** | http://localhost:8181 |
| 后端 API（经前端同源代理）| http://localhost:8181/api/v1/uavs ；直连 http://localhost:18080 |
| Mock 模拟器状态 | http://localhost:8002/simulator/status |
| MinIO 控制台 | http://localhost:9001（forest_fire / forest_fire_dev） |

**常见问题**：端口冲突（8080/8081/5432/1883/9000 等被占用）→ `docker compose down` 后修改 compose 端口映射或停掉占用进程；`port is already allocated` 同理。完整运维手册见 `docs/10-ops/`。

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
