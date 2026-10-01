#!/usr/bin/env bash
# =====================================================================
# 演示数据重置（reset-demo）—— 一键恢复干净演示底座
# 用法: bash scripts/reset-demo.sh        （Git Bash / 任何有 docker 的终端）
# 行为: 清空演示数据 → 重启 mock-uav（电量回满）→ 打印核对清单
# =====================================================================
set -e
cd "$(dirname "$0")/.."

echo "== 1/3 清空演示数据 =="
docker exec -i forest-fire-postgres psql -U forest_fire -d forest_fire -v ON_ERROR_STOP=1 < scripts/reset-demo.sql

echo "== 2/3 重启 mock-uav（电量回满100%）=="
docker restart forest-fire-mock-uav > /dev/null
sleep 5

echo "== 3/3 核对 =="
docker exec forest-fire-postgres psql -U forest_fire -d forest_fire -tAc "
SELECT 'fire_incident: ' || count(*) FROM fire_incident
UNION ALL SELECT 'media_file: ' || count(*) FROM media_file
UNION ALL SELECT 'uav_telemetry: ' || count(*) FROM uav_telemetry
UNION ALL SELECT 'uav_device: ' || count(*) FROM uav_device;"

echo ""
echo "✅ 重置完成。等约 15 秒 mock-uav 重连 MQTT 后，打开 http://localhost:8181 开始演示。"
echo "   （数据库数据卷未动，如需彻底重来用 docker compose down -v 后 up -d --build）"
