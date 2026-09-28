# =====================================================================
# AI Service — MVP 算法 provider（D1：Phase 3/4 闭环用）
# =====================================================================
# 双轨设计（11号V2.2第89章 MVP简化决策）:
#   provider = mock（默认）: 场景驱动的确定性模拟输出，前端标注"算法模拟模式"
#   provider = real        : 预留——放置真实模型后切换（后续打磨项）
# 参数纪律: 阈值（0.60/0.80 等）唯一来源 config/algorithm/*.yaml 与基线，
#           本文件不内嵌第二套业务阈值；mock 输出的随机抖动仅限展示层。
# 输出结构对齐 07号第21~23章。
# =====================================================================
import os
import random

from fastapi import FastAPI

app = FastAPI(title="Forest Fire AI Service", version="0.2.0")

PROVIDER = os.getenv("AI_PROVIDER", "mock")  # mock | real


@app.get("/health")
def health():
    return {"status": "UP", "service": "ai-service", "provider": PROVIDER}


def _wrap(data: dict) -> dict:
    return {"code": 0, "message": "success", "data": data}


@app.post("/ai/v1/detection")
def detection(req: dict):
    """F01 火情检测（07号第21章）。入参 {taskId, mediaId, ...}。
    mock: 每张 RGB 图像确定性输出 smoke 检测，置信度 0.86~0.97（必过 T_alert=0.60）。"""
    if PROVIDER == "real":
        return _real_pending("detection")
    conf = round(random.uniform(0.86, 0.97), 2)
    return _wrap({
        "taskId": req.get("taskId"),
        "mediaId": req.get("mediaId"),
        "status": "SUCCEEDED",
        "provider": "mock",
        "detections": [
            {"class": "smoke", "confidence": conf, "bbox": [520, 300, 660, 430]}
        ],
        "processingTimeMs": random.randint(60, 140),
    })


@app.post("/ai/v1/localization")
def localization(req: dict):
    """F03 火点定位（07号第22章）。入参 {taskId, media:{position:{latitude,longitude}}}。
    mock: 以拍摄点为基准加固定偏移（模拟视场中心射线交点），误差 12~18m。"""
    if PROVIDER == "real":
        return _real_pending("localization")
    pos = (req.get("media") or {}).get("position") or {}
    lat = float(pos.get("latitude", 30.12))
    lon = float(pos.get("longitude", 114.12))
    return _wrap({
        "taskId": req.get("taskId"),
        "status": "SUCCEEDED",
        "provider": "mock",
        "latitude": round(lat + 0.00045, 6),
        "longitude": round(lon + 0.00055, 6),
        "accuracy": round(random.uniform(12.0, 18.0), 1),
        "method": "RAY_GROUND_INTERSECTION",
    })


@app.post("/ai/v1/verification")
def verification(req: dict):
    """F04 二次核验（07号第23章）。入参 {taskId, incidentId, evidence:{rgb,thermal,temporal,spatial}}。
    mock: 按 0.35/0.35/0.15/0.15 加权，阈值判定（≥0.80 CONFIRMED / <0.50 FALSE_ALARM / 其余 UNCERTAIN）。"""
    if PROVIDER == "real":
        return _real_pending("verification")
    ev = req.get("evidence") or {}
    rgb = float(ev.get("rgb", 0.9))
    thermal = float(ev.get("thermal", 0.92))
    temporal = float(ev.get("temporal", 0.88))
    spatial = float(ev.get("spatial", 0.9))
    score = round(0.35 * rgb + 0.35 * thermal + 0.15 * temporal + 0.15 * spatial, 3)
    if score >= 0.80:
        decision = "CONFIRMED"
    elif score < 0.50:
        decision = "FALSE_ALARM"
    else:
        decision = "UNCERTAIN"
    return _wrap({
        "taskId": req.get("taskId"),
        "incidentId": req.get("incidentId"),
        "status": "SUCCEEDED",
        "provider": "mock",
        "decision": decision,
        "confidence": score,
        "evidence": {"rgb": rgb, "thermal": thermal, "temporal": temporal, "spatial": spatial},
    })


@app.post("/ai/v1/thermal/anomaly")
def thermal_anomaly(req: dict):
    """F02 热异常检测（规则版 mock）。"""
    if PROVIDER == "real":
        return _real_pending("thermal/anomaly")
    return _wrap({
        "taskId": req.get("taskId"),
        "status": "SUCCEEDED",
        "provider": "mock",
        "anomalies": [{
            "maxTemperature": 87.4,
            "backgroundTemperature": 34.2,
            "deltaTemperature": 53.2,
            "confidence": 0.93,
        }],
    })


@app.post("/ai/v1/segmentation")
def segmentation(req: dict):
    """F05 火场分割（mock）：以火点为中心 ≈150m 半径示例多边形（D4 用）。"""
    if PROVIDER == "real":
        return _real_pending("segmentation")
    center = req.get("center") or {}
    lat = float(center.get("latitude", 30.12))
    lon = float(center.get("longitude", 114.12))
    r = 0.00135
    ring = [
        [round(lat + r, 6), round(lon, 6)],
        [round(lat + r * 0.6, 6), round(lon + r, 6)],
        [round(lat - r * 0.5, 6), round(lon + r * 0.8, 6)],
        [round(lat - r, 6), round(lon, 6)],
        [round(lat - r * 0.4, 6), round(lon - r * 0.9, 6)],
        [round(lat + r * 0.7, 6), round(lon - r * 0.7, 6)],
        [round(lat + r, 6), round(lon, 6)],
    ]
    return _wrap({
        "taskId": req.get("taskId"),
        "status": "SUCCEEDED",
        "provider": "mock",
        "polygon": ring,
        "areaSquareMeters": 70686,
    })


@app.post("/ai/v1/tracking")
def tracking(req: dict):
    """F06 火势跟踪（mock，D4 用）。"""
    if PROVIDER == "real":
        return _real_pending("tracking")
    return _wrap({
        "taskId": req.get("taskId"),
        "status": "SUCCEEDED",
        "provider": "mock",
        "direction": 63.2,
        "speed": 1.8,
        "areaGrowthRate": 0.16,
        "trend": "EXPANDING",
    })


def _real_pending(name: str) -> dict:
    return {
        "code": 0,
        "message": "real provider not installed",
        "data": {"endpoint": name, "status": "PENDING_MODEL",
                 "note": "放置真实模型并实现 provider 后设置 AI_PROVIDER=real"},
    }
