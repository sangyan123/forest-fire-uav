# DB Migration — 数据库 Schema 机器可执行来源

**语义 owner：** 04号《数据库设计文档》V1.2（基线V1.3第4章：Schema owner=04；基线第75章：Migration 是 Schema 唯一真相来源）
**版本约定：** Flyway 命名 `V<版本>__<描述>.sql`
**日期：** 2026-09-28

## 文件清单

| 文件 | 内容 |
| --- | --- |
| V1__baseline_schema.sql | 基线全量 Schema：CREATE EXTENSION postgis + 34 张表（按04号文档顺序）+ 8 个索引（遥测复合索引 + GIST 空间索引，同名已去重） |
| V2__seed_rbac_permissions.sql | RBAC 权限种子：11 项权限（uav:read ~ audit:read），ON CONFLICT 幂等 |

## 运行方式

```bash
# psql 直跑（PG16+，需管理员权限创建扩展）
psql -h <host> -U <user> -d forest_fire -f db/migration/V1__baseline_schema.sql
psql -h <host> -U <user> -d forest_fire -f db/migration/V2__seed_rbac_permissions.sql

# 或 Flyway / Spring Boot Flyway 自动迁移（Phase 1 Backend 接入）
```

## 设计要点（忠实于04号，不在迁移中擅自增强）

1. **逻辑外键**：全部表间关系无 `REFERENCES` 约束，关联完整性由应用层（Backend）保证——04号既定设计；
2. **UUID 主键无数据库 DEFAULT**：`id UUID PRIMARY KEY`，UUID 由应用侧生成；种子数据用 `gen_random_uuid()`（PG13+ 内置）；
3. **分区未定稿**：uav_telemetry 按月 Range 分区或 TimescaleDB hypertable 是04号既定方向但未最终决策，V1 为普通表，分区迁移待运维评审后以 V3+ 版本追加；
4. **规范统一**：时间列 TIMESTAMPTZ（UTC）、空间列 SRID 4326、UUID 主键+业务号（incident_no/mission_no/command_no）、关键表 deleted_at 软删；
5. **错误码/枚举不入库**：枚举唯一来源 `docs/00-doc/enums.yaml`，参数唯一来源 `docs/00-doc/constants.yaml`，应用层引用。

## 变更纪律（基线第71/75章）

任何 Schema 变更：**先改 04号文档 → 再出新版本迁移（禁止修改已应用的 V1/V2）→ 同步引用文档与代码**。代码不得自行增加同名字段而不更新 Migration。
