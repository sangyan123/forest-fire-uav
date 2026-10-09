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
import hashlib
import math
import os
import random
import time

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
    """F05 火场分割（mock）：以火点为中心的多边形。
    growthStep（可选，默认0）控制火场轮次半径：≈150m → 240m → 330m…（每轮+90m），
    供后端多次火场分析叠加出"扩散"效果；面积按 πr² 计。"""
    if PROVIDER == "real":
        return _real_pending("segmentation")
    center = req.get("center") or {}
    lat = float(center.get("latitude", 30.12))
    lon = float(center.get("longitude", 114.12))
    growth_step = int(req.get("growthStep", 0) or 0)
    if growth_step < 0:
        growth_step = 0
    r = 0.00135 + 0.00081 * growth_step
    ring = [
        [round(lat + r, 6), round(lon, 6)],
        [round(lat + r * 0.6, 6), round(lon + r, 6)],
        [round(lat - r * 0.5, 6), round(lon + r * 0.8, 6)],
        [round(lat - r, 6), round(lon, 6)],
        [round(lat - r * 0.4, 6), round(lon - r * 0.9, 6)],
        [round(lat + r * 0.7, 6), round(lon - r * 0.7, 6)],
        [round(lat + r, 6), round(lon, 6)],
    ]
    import math
    radius_m = round(r * 111320.0)
    area = round(math.pi * radius_m * radius_m)
    return _wrap({
        "taskId": req.get("taskId"),
        "status": "SUCCEEDED",
        "provider": "mock",
        "polygon": ring,
        "radiusMeters": radius_m,
        "areaSquareMeters": area,
        "growthStep": growth_step,
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


# =====================================================================
# 火情风险检测页 mock（F07 风险因子 / F11 火势预测 / F08 航点，03号第91章）
# 纯函数 provider：模型参数由 backend 请求体传入（唯一来源 application.yml
# risk.*，镜像 constants.yaml v1.6），本文件不内嵌第二套业务参数。
# 因子数值基于 cellCode 哈希确定性生成（同一格同值，可复现演示）。
# =====================================================================

def _deterministic_percent(seed: str, lo: float, hi: float) -> float:
    """cellCode 哈希 → [lo, hi] 内确定性取值（同一格同一天恒定）。"""
    digest = hashlib.md5(seed.encode("utf-8")).hexdigest()
    unit = int(digest[:8], 16) / 0xFFFFFFFF
    return round(lo + unit * (hi - lo), 1)


@app.get("/ai/v1/weather/current")
def weather_current():
    """气象 mock 数据源（与 DJI 遥测 wind_speed 分离，03号第19.3节硬约束）。
    按 UTC 小时播种确定性生成，同一小时内恒定、逐小时缓变。"""
    seed = time.strftime("%Y%m%d%H", time.gmtime())
    temp_c = _deterministic_percent("temp-" + seed, 18.0, 34.0)
    humidity = _deterministic_percent("humi-" + seed, 25.0, 75.0)
    wind_speed = _deterministic_percent("wind-" + seed, 1.0, 7.0)
    wind_dir = _deterministic_percent("dir-" + seed, 0.0, 359.0)
    return _wrap({
        "source": "mock",
        "tempC": temp_c,
        "humidityPct": humidity,
        "windSpeedMps": wind_speed,
        "windDirectionDeg": wind_dir,
    })


@app.post("/ai/v1/risk/factors")
def risk_factors(req: dict):
    """F07 五因子分值 mock（0~100，03号第91.5节）。
    入参 {cells:[{cellCode}], weather:{tempC,humidityPct,windSpeedMps}}；
    historical/vegetation/terrain/human_activity 按格哈希确定性生成，
    weather 因子由气象 mock 实值归一（高温/低湿/大风 → 高分）。"""
    if PROVIDER == "real":
        return _real_pending("risk/factors")
    weather = req.get("weather") or {}
    temp_c = float(weather.get("tempC", 25.0))
    humidity = float(weather.get("humidityPct", 50.0))
    wind = float(weather.get("windSpeedMps", 2.0))
    # 气象因子归一：温度(18~34)+湿度反向(25~75)+风速(1~7) 各 1/3
    weather_factor = round(
        (max(0.0, min(1.0, (temp_c - 18.0) / 16.0))
         + max(0.0, min(1.0, (75.0 - humidity) / 50.0))
         + max(0.0, min(1.0, (wind - 1.0) / 6.0))) / 3.0 * 100.0, 1)
    today = time.strftime("%Y%m%d", time.gmtime())
    items = []
    for cell in req.get("cells") or []:
        code = str(cell.get("cellCode", ""))
        # HISTORICAL 双峰分化（mock 展示层）：约 20% 格子为历史高火险格——
        # HISTORICAL 92~99、VEGETATION 75~95（干燥密集植被），确保总分稳定越过 70（HIGH）；
        # 其余格子 HISTORICAL 15~70，总分落在 MEDIUM/LOW——三档分级均有演示覆盖
        hot_cell = int(hashlib.md5(f"hot-{code}".encode()).hexdigest()[:2], 16) % 5 == 0
        vegetation = _deterministic_percent(f"veg-{code}", 75.0, 95.0) if hot_cell \
            else _deterministic_percent(f"veg-{code}", 40.0, 90.0)
        if hot_cell:
            historical = _deterministic_percent(f"his-{code}-{today}", 92.0, 99.0)
        else:
            historical = _deterministic_percent(f"his-{code}-{today}", 15.0, 70.0)
        items.append({
            "cellCode": code,
            "HISTORICAL": historical,
            "WEATHER": weather_factor,
            "VEGETATION": vegetation,
            "TERRAIN": _deterministic_percent(f"ter-{code}", 35.0, 85.0),
            "HUMAN_ACTIVITY": _deterministic_percent(f"hum-{code}", 20.0, 80.0),
        })
    return _wrap({"provider": "mock", "factors": items})


@app.post("/ai/v1/fire/predict")
def fire_predict(req: dict):
    """F11 Level 1 规则椭圆扩散 mock（03号第91.5节）。
    入参 {center, baseRadiusM, horizons:[30,60], weather, params(模型参数，
    唯一来源 backend application.yml risk.prediction.*)}；
    出 data.predictions[]：{forecastMinutes, ring:[[lat,lon]...闭合环],
    areaSquareMeters, semiMajorM, semiMinorM, fireHeadDirectionDeg,
    spreadRateMPerMin, lengthWidthRatio, confidence}。"""
    if PROVIDER == "real":
        return _real_pending("fire/predict")
    center = req.get("center") or {}
    lat = float(center.get("latitude", 30.12))
    lon = float(center.get("longitude", 114.12))
    base_radius = float(req.get("baseRadiusM", 150.0))
    weather = req.get("weather") or {}
    wind_speed = float(weather.get("windSpeedMps", 0.0))
    wind_dir = float(weather.get("windDirectionDeg", 0.0))
    p = req.get("params") or {}
    base_rate = float(p.get("baseSpreadRateMPerMin", 1.5))
    wind_factor = float(p.get("windSpeedFactorPerMps", 0.20))
    ratio_base = float(p.get("lengthWidthRatioBase", 1.5))
    ratio_per_mps = float(p.get("lengthWidthRatioPerMps", 0.25))
    ratio_max = float(p.get("lengthWidthRatioMax", 3.0))
    confidence = float(p.get("confidence", 0.78))

    spread_rate = base_rate * (1.0 + wind_factor * wind_speed)
    ratio = min(ratio_base + ratio_per_mps * wind_speed, ratio_max)
    wind_rad = math.radians(wind_dir)
    # 沿风向单位向量（lat,lon 分量；风向 0°=正北）与垂直方向单位向量
    u_lat, u_lon = math.cos(wind_rad), math.sin(wind_rad)
    p_lat, p_lon = -math.sin(wind_rad), math.cos(wind_rad)
    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * math.cos(math.radians(lat))

    predictions = []
    for minutes in req.get("horizons") or [30, 60]:
        minutes = int(minutes)
        a = base_radius + spread_rate * minutes          # 半长轴（沿风向）
        b = base_radius + spread_rate * minutes / ratio  # 半短轴（垂直风向）
        ring = []
        steps = 24
        for i in range(steps + 1):
            theta = 2.0 * math.pi * i / steps
            d_lat = (a * math.cos(theta) * u_lat + b * math.sin(theta) * p_lat) / m_per_deg_lat
            d_lon = (a * math.cos(theta) * u_lon + b * math.sin(theta) * p_lon) / m_per_deg_lon
            ring.append([round(lat + d_lat, 6), round(lon + d_lon, 6)])
        predictions.append({
            "forecastMinutes": minutes,
            "ring": ring,
            "areaSquareMeters": round(math.pi * a * b),
            "semiMajorM": round(a, 1),
            "semiMinorM": round(b, 1),
            "fireHeadDirectionDeg": wind_dir,
            "spreadRateMPerMin": round(spread_rate, 2),
            "lengthWidthRatio": round(ratio, 2),
            "confidence": confidence,
        })
    return _wrap({
        "provider": "mock",
        "baseRadiusM": round(base_radius, 1),
        "weather": {"windSpeedMps": wind_speed, "windDirectionDeg": wind_dir},
        "predictions": predictions,
    })


@app.post("/ai/v1/patrol/waypoints")
def patrol_waypoints(req: dict):
    """F08 网格内蛇形覆盖航点 mock（03号第91.5节）。
    入参 {bounds:{minLat,minLon,maxLat,maxLon}, laneSpacingM, altitudeM, speedMps}；
    出 data{waypoints:[{sequenceNo,latitude,longitude,altitude}], routeLengthM,
    estimatedDurationMin}。"""
    if PROVIDER == "real":
        return _real_pending("patrol/waypoints")
    bounds = req.get("bounds") or {}
    min_lat = float(bounds.get("minLat", 30.12))
    min_lon = float(bounds.get("minLon", 114.12))
    max_lat = float(bounds.get("maxLat", 30.13))
    max_lon = float(bounds.get("maxLon", 114.13))
    lane_spacing = float(req.get("laneSpacingM", 80.0))
    altitude = float(req.get("altitudeM", 100.0))
    speed = float(req.get("speedMps", 9.0))

    mid_lat = (min_lat + max_lat) / 2.0
    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * math.cos(math.radians(mid_lat))
    height_m = (max_lat - min_lat) * m_per_deg_lat
    width_m = (max_lon - min_lon) * m_per_deg_lon

    # 蛇形：每间隔 laneSpacing 一条横航线，奇偶行反向（首尾均落在边界内）
    lane_ys = []
    y = lane_spacing / 2.0
    while y < height_m:
        lane_ys.append(y)
        y += lane_spacing
    if not lane_ys:
        lane_ys.append(height_m / 2.0)
    if height_m - lane_ys[-1] > lane_spacing / 2.0:
        lane_ys.append(height_m)

    waypoints = []
    route_m = 0.0
    prev = None
    for idx, y_m in enumerate(lane_ys):
        lat_i = min_lat + y_m / m_per_deg_lat
        xs = [min_lon, max_lon] if idx % 2 == 0 else [max_lon, min_lon]
        for lon_i in xs:
            waypoints.append({
                "sequenceNo": len(waypoints) + 1,
                "latitude": round(lat_i, 6),
                "longitude": round(lon_i, 6),
                "altitude": altitude,
            })
            if prev is not None:
                d_lat = (lat_i - prev[0]) * m_per_deg_lat
                d_lon = (lon_i - prev[1]) * m_per_deg_lon
                route_m += math.hypot(d_lat, d_lon)
            prev = (lat_i, lon_i)
    duration_min = round(route_m / speed / 60.0, 1) if speed > 0 else None
    return _wrap({
        "provider": "mock",
        "laneCount": len(lane_ys),
        "routeLengthM": round(route_m, 1),
        "estimatedDurationMin": duration_min,
        "waypoints": waypoints,
    })
