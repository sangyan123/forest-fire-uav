# 开发规范（Phase 0）

## 分支与合并

权威策略：`docs/00-doc/branch-policy.yaml`（机器执行来源：GitHub Branch Ruleset）

- **main 禁止直接 Push**；合并必须 Pull Request + 至少 1 人 Review + CI 全绿
- 分支模型：`main`（生产可发布）/ `develop`（集成）/ `feature/*`、`fix/*`、`release/*`
- CI 流水线：Push → Lint → Unit Test → Build → Docker Build → Integration Test → Artifact

## Commit

遵循 11 号文档 V2.1 的 Commit 规范（见 `docs/11-development/`）。

## 配置与Schema变更纪律（基线第71/75/76章）

1. **枚举**只存在于 `docs/00-doc/enums.yaml`；**参数**只存在于 `docs/00-doc/constants.yaml`；算法运行时配置（`config/algorithm/`）只是其镜像，禁止另创数值
2. 任何字段/枚举/API/Schema/参数变更：先改主定义文档 → 更新机器来源 → 同步引用文档 → 更新代码与测试 → 在 `docs/00-doc/change-log.md` 登记
3. 数据库变更：先改 04 号文档，再新增迁移文件（禁止修改已应用的迁移）

## 待办提醒

- 推送 GitHub 后：按 branch-policy.yaml 配置 Branch Ruleset（main 保护 + required status checks）
- ISSUE-001（F07权重）、ISSUE-002（工期人力）、ISSUE-003（运维指标）、ISSUE-004（命令状态映射）见 `docs/00-doc/change-log.md`
