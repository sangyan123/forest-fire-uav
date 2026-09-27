# =====================================================================
# AI Service — MVP stub（Phase 1 骨架）
# 说明:  /ai/v1/* 六接口为占位实现（Phase 4/5 AI 闭环时填充真实模型）；
#        算法参数唯一来源 config/algorithm/，此处不得内嵌数值。
# =====================================================================
from fastapi import FastAPI

app = FastAPI(title="Forest Fire AI Service", version="0.1.0")

STUBS = {
    "detection": "F01 火情检测：Phase 4 实现（YOLO/RT-DETR，参数见 config/algorithm/fire-detection-v1.yaml）",
    "thermal/anomaly": "F02 热异常检测：Phase 4 实现（规则版）",
    "localization": "F03 火点定位：Phase 4 实现（射线-地面交会）",
    "verification": "F04 二次核验：Phase 4 实现（四证据加权）",
    "segmentation": "F05 火场分割：Phase 5 实现",
    "tracking": "F06 火势跟踪：Phase 5 实现",
}


@app.get("/health")
def health():
    return {"status": "UP", "service": "ai-service", "mode": "stub"}


@app.post("/ai/v1/{endpoint:path}")
def stub(endpoint: str):
    note = STUBS.get(endpoint, "未定义的AI端点")
    return {"code": 0, "message": "stub", "data": {"endpoint": endpoint, "status": "PENDING_IMPLEMENTATION", "note": note}}
