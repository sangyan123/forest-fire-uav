/**
 * 火情域逻辑：状态中文标签 / 颜色 / 状态机 / 字段宽松解析。
 *
 * 后端字段命名可能不统一（firePoint.latitude / latitude / incidentLatitude 等），
 * 所有取值都走 pickPath 宽松回退，保证演示时不因字段差异丢数据。
 */
import type { FireAnalysis, FireIncident, FirePolygonShape, StatusPoint, VerificationResult } from './types'

/* ---------------- 状态标签与颜色 ---------------- */

const FIRE_STATUS_ZH: Record<string, string> = {
  SUSPECTED: '疑似火情',
  VERIFYING: '核验中',
  CONFIRMED: '确认火情',
  FALSE_ALARM: '误报',
  TRACKING: '持续跟踪',
  PROCESSING: '处置中',
  RESOLVED: '已处置',
  CLOSED: '已关闭',
}

export function zhFireStatus(s?: string | null): string {
  if (!s) return '未知'
  return FIRE_STATUS_ZH[s.toUpperCase()] ?? s
}

/** 各状态主色（marker / chip 共用） */
export function fireStatusColor(status?: string | null): string {
  switch ((status ?? '').toUpperCase()) {
    case 'SUSPECTED':
      return '#fb923c' // 橙
    case 'VERIFYING':
      return '#facc15' // 黄
    case 'CONFIRMED':
      return '#f43f5e' // 红
    case 'FALSE_ALARM':
      return '#94a3b8' // 灰
    case 'TRACKING':
      return '#e859f0' // 红紫
    case 'PROCESSING':
      return '#a855f7' // 渐暗（紫）
    case 'RESOLVED':
      return '#64748b' // 更暗
    case 'CLOSED':
      return '#475569' // 最暗
    default:
      return '#8fa3c4'
  }
}

/** 是否播放脉冲动画：终态/误报/处置后渐暗不闪 */
export function fireStatusPulse(status?: string | null): boolean {
  const s = (status ?? '').toUpperCase()
  return s === 'SUSPECTED' || s === 'VERIFYING' || s === 'CONFIRMED' || s === 'TRACKING'
}

/* ---------------- 状态机：当前状态可达的下一态 ---------------- */

const FIRE_NEXT: Record<string, string[]> = {
  SUSPECTED: ['VERIFYING', 'FALSE_ALARM'],
  VERIFYING: ['CONFIRMED', 'FALSE_ALARM'],
  CONFIRMED: ['TRACKING', 'FALSE_ALARM'],
  FALSE_ALARM: ['CLOSED'],
  TRACKING: ['PROCESSING', 'RESOLVED'],
  PROCESSING: ['RESOLVED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
}

export function nextFireStatuses(status?: string | null): string[] {
  const s = (status ?? '').toUpperCase()
  return FIRE_NEXT[s] ?? []
}

/* ---------------- AI 核验 decision 标签 ---------------- */

const DECISION_ZH: Record<string, string> = {
  CONFIRMED: '确认火情',
  FIRE_CONFIRMED: '确认火情',
  TRUE_ALARM: '真实火情',
  FALSE_ALARM: '误报',
  NO_FIRE: '无火情',
  NEED_MORE_DATA: '需补充信息',
  INCONCLUSIVE: '结论不明',
  PENDING: '待定',
}

export function zhDecision(d?: string | null): string {
  if (!d) return '—'
  return DECISION_ZH[d.toUpperCase()] ?? d
}

/* ---------------- 宽松字段解析 ---------------- */

function asRecord(v: unknown): Record<string, unknown> | null {
  return v !== null && typeof v === 'object' && !Array.isArray(v)
    ? (v as Record<string, unknown>)
    : null
}

/** 按 'a.b.c' 路径逐层取第一个非空值；数组路径段会尝试逐个元素查找 */
function pickPath(obj: unknown, path: string): unknown {
  const segs = path.split('.')
  let cur: unknown = obj
  for (const seg of segs) {
    if (cur === null || cur === undefined) return undefined
    if (Array.isArray(cur)) {
      let found: unknown
      let hit = false
      for (const item of cur) {
        const rec = asRecord(item)
        const v = rec?.[seg]
        if (v !== undefined && v !== null && v !== '') {
          found = v
          hit = true
          break
        }
      }
      if (!hit) return undefined
      cur = found
      continue
    }
    const rec = asRecord(cur)
    if (!rec) return undefined
    cur = rec[seg]
  }
  return cur !== '' ? cur : undefined
}

/** 依次尝试多个路径，返回第一个非空值 */
export function pickFirst(obj: unknown, ...paths: string[]): unknown {
  for (const p of paths) {
    const v = pickPath(obj, p)
    if (v !== undefined && v !== null) return v
  }
  return undefined
}

function toNum(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string') {
    const n = Number(v.trim())
    if (Number.isFinite(n)) return n
  }
  return null
}

/** id：id / incidentId / uuid / incidentUUID / eventID … */
function idOf(raw: Record<string, unknown>): string | null {
  const v = pickFirst(
    raw,
    'id',
    'incidentId',
    'incidentUUID',
    'uuid',
    'incidentNo',
    'incidentCode',
    'no',
    'code',
  )
  if (v === undefined) return null
  const s = String(v)
  return s ? s : null
}

function incidentNoOf(raw: Record<string, unknown>, fallbackId: string): string {
  const v = pickFirst(raw, 'incidentNo', 'incidentCode', 'code', 'no', 'name', 'title', 'id')
  if (v === undefined) return fallbackId
  const s = String(v)
  return s || fallbackId
}

function statusOf(raw: Record<string, unknown>): string {
  const v = pickFirst(raw, 'status', 'incidentStatus', 'state', 'currentStatus')
  return v === undefined ? '' : String(v).trim().toUpperCase()
}

/** 坐标：优先 firePoint.latitude，回退 latitude / incidentLatitude / location 等 */
function coordsOf(raw: Record<string, unknown>): { latitude: number; longitude: number } | null {
  const lat = toNum(
    pickFirst(
      raw,
      'firePoint.latitude',
      'fireLocation.latitude',
      'location.latitude',
      'position.latitude',
      'latitude',
      'lat',
      'incidentLatitude',
    ),
  )
  const lng = toNum(
    pickFirst(
      raw,
      'firePoint.longitude',
      'firePoint.lng',
      'firePoint.lon',
      'fireLocation.longitude',
      'location.longitude',
      'position.longitude',
      'longitude',
      'lng',
      'lon',
      'incidentLongitude',
    ),
  )
  // firePoint / location 可能是 [lat, lng] 或 GeoJSON [lng, lat] 数组
  if (lat === null || lng === null) {
    for (const p of ['firePoint', 'fireLocation', 'location', 'position', 'point']) {
      const arr = pickPath(raw, p)
      if (Array.isArray(arr) && arr.length >= 2) {
        const a = toNum(arr[0])
        const b = toNum(arr[1])
        if (a !== null && b !== null) {
          // 经度绝对值通常 > 纬度；这里按常见范围启发式纠正顺序
          return Math.abs(a) > 90 ? { latitude: b, longitude: a } : { latitude: a, longitude: b }
        }
      }
    }
  }
  if (lat === null || lng === null) return null
  return { latitude: lat, longitude: lng }
}

/** 置信度：0-1 小数或 0-100 数值或带 % 字符串，统一归一化到 0-100 */
function confidenceOf(raw: Record<string, unknown>): number | null {
  const v = pickFirst(
    raw,
    'confidence',
    'confidenceScore',
    'confidence_rate',
    'aiConfidence',
    'detectionConfidence',
    'score',
  )
  if (v === undefined) return null
  if (typeof v === 'string') {
    const s = v.trim().replace('%', '')
    const n = toNum(s)
    if (n === null) return null
    return n <= 1 ? Math.round(n * 100) : Math.min(100, Math.round(n))
  }
  const n = toNum(v)
  if (n === null) return null
  return n <= 1 ? Math.round(n * 100) : Math.min(100, Math.round(n))
}

/** 时间：ISO 字符串 / epoch 秒或毫秒 / 'yyyy-MM-dd HH:mm:ss'，统一毫秒 */
function timeMsOf(raw: Record<string, unknown>): number | null {
  const v = pickFirst(
    raw,
    'createdAt',
    'firstDetectionAt',
    'detectedAt',
    'createTime',
    'createdTime',
    'timestamp',
    'time',
    'updateTime',
  )
  if (v === undefined) return null
  if (typeof v === 'number' && Number.isFinite(v)) {
    return v < 1e12 ? v * 1000 : v
  }
  if (typeof v === 'string') {
    const s = v.trim()
    if (/^\d{10}$/.test(s)) return Number(s) * 1000
    if (/^\d{13}$/.test(s)) return Number(s)
    if (/^\d{16}$/.test(s)) return Number(s) / 1000
    const normalized = s.includes('T') ? s : s.replace(' ', 'T')
    const ms = Date.parse(normalized)
    if (Number.isFinite(ms)) return ms
  }
  return null
}

/** 解析单个事件对象；非对象返回 null */
export function parseIncident(item: unknown): FireIncident | null {
  const raw = asRecord(item)
  if (!raw) return null
  const id = idOf(raw) ?? ''
  const coords = coordsOf(raw)
  const scenarioRaw = pickFirst(raw, 'scenarioType', 'scenario_type', 'demoScenarioType', 'scenario')
  return {
    id,
    incidentNo: incidentNoOf(raw, id || '未知事件'),
    status: statusOf(raw),
    latitude: coords?.latitude ?? null,
    longitude: coords?.longitude ?? null,
    confidence: confidenceOf(raw),
    timeMs: timeMsOf(raw),
    scenarioType: scenarioRaw === undefined ? null : String(scenarioRaw).trim().toUpperCase(),
    raw,
  }
}

/** 解析事件列表：data 直接是数组，或包在 items/list/content/records 里 */
export function parseIncidents(data: unknown): FireIncident[] {
  let arr: unknown[] | null = null
  if (Array.isArray(data)) {
    arr = data
  } else {
    const rec = asRecord(data)
    if (rec) {
      for (const key of ['items', 'list', 'content', 'records', 'data', 'incidents']) {
        const v = rec[key]
        if (Array.isArray(v)) {
          arr = v
          break
        }
      }
    }
  }
  if (!arr) return []
  return arr
    .map((item) => parseIncident(item))
    .filter((x): x is FireIncident => x !== null)
}

/** POST /verification 响应宽松解析 decision / confidence */
export function parseVerification(data: unknown): VerificationResult {
  const raw = asRecord(data)
  const at = Date.now()
  if (!raw) return { decision: null, confidence: null, at }
  // decision 可能直接在 data 上，也可能包在 result/verification/aiResult 里
  const decisionRaw = pickFirst(
    raw,
    'decision',
    'aiDecision',
    'verificationDecision',
    'result.decision',
    'verification.decision',
    'aiResult.decision',
    'verificationResult.decision',
  )
  const decision = decisionRaw === undefined ? null : String(decisionRaw).trim().toUpperCase()
  const confRaw = pickFirst(
    raw,
    'confidence',
    'aiConfidence',
    'confidenceScore',
    'result.confidence',
    'verification.confidence',
    'verificationResult.confidence',
  )
  let confidence: number | null = null
  if (confRaw !== undefined) {
    const rec = asRecord(data)
    confidence = rec ? confidenceOf(rec) : null
    if (confidence === null) {
      const n = toNum(confRaw)
      if (n !== null) confidence = n <= 1 ? Math.round(n * 100) : Math.min(100, Math.round(n))
    }
  }
  return { decision, confidence, at }
}

/* ---------------- 相对时间 ---------------- */

export function relTimeText(timeMs: number | null, now: number): string {
  if (timeMs === null) return '—'
  const diff = Math.max(0, now - timeMs)
  const sec = Math.floor(diff / 1000)
  if (sec < 10) return '刚刚'
  if (sec < 60) return `${sec} 秒前`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  return new Date(timeMs).toLocaleDateString('zh-CN')
}

export function formatCoords(lat: number | null, lng: number | null): string {
  if (lat === null || lng === null) return '—'
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}`
}

/* ---------------- 展示排序 / 误报演示 / 状态时间线 ---------------- */

/** 唯一终态（其余视为活跃态排在前面） */
export function isTerminalFireStatus(status?: string | null): boolean {
  return (status ?? '').toUpperCase() === 'CLOSED'
}

/** 误报演示线事件 */
export function isFalseAlarmDemo(inc: FireIncident | null | undefined): boolean {
  return inc?.scenarioType === 'FALSE_ALARM'
}

/** 列表展示排序：活跃态（非终态）在前，组内按创建时间倒序（无时间视为最旧） */
export function sortIncidentsForDisplay(list: FireIncident[]): FireIncident[] {
  const byNewest = (a: FireIncident, b: FireIncident): number => (b.timeMs ?? 0) - (a.timeMs ?? 0)
  const active = list.filter((i) => !isTerminalFireStatus(i.status)).sort(byNewest)
  const done = list.filter((i) => isTerminalFireStatus(i.status)).sort(byNewest)
  return [...active, ...done]
}

/** 事件原始数据中的状态历史（若后端透出）。找不到返回 null */
export function parseStatusHistory(raw: unknown): StatusPoint[] | null {
  const rec = asRecord(raw)
  if (!rec) return null
  for (const key of ['statusHistory', 'statusTimeline', 'statusHistoryList', 'statusLog', 'statusRecords', 'history']) {
    const arr = rec[key]
    if (!Array.isArray(arr) || arr.length === 0) continue
    const points: StatusPoint[] = []
    for (const entry of arr) {
      if (typeof entry === 'string' || typeof entry === 'number') {
        points.push({ status: String(entry).trim().toUpperCase(), at: null, source: 'backend' })
        continue
      }
      const e = asRecord(entry)
      if (!e) continue
      const st = pickFirst(e, 'status', 'toStatus', 'newStatus', 'state', 'targetStatus')
      if (st === undefined) continue
      const tRaw = pickFirst(e, 'at', 'time', 'changedAt', 'timestamp', 'createdAt', 'updatedAt')
      let at: number | null = null
      if (typeof tRaw === 'number' && Number.isFinite(tRaw)) {
        at = tRaw < 1e12 ? tRaw * 1000 : tRaw
      } else if (typeof tRaw === 'string' && tRaw) {
        const ms = Date.parse(tRaw.includes('T') ? tRaw : tRaw.replace(' ', 'T'))
        if (Number.isFinite(ms)) at = ms
      }
      points.push({ status: String(st).trim().toUpperCase(), at, source: 'backend' })
    }
    if (points.length > 0) return points
  }
  return null
}

/**
 * 记录状态变化（纯函数）：末点状态相同返回原数组，否则追加点。
 * 后端历史优先采用（整段替换由调用方完成），本地轮询追加 source='local' 的点。
 */
export function appendTimelinePoint(
  points: StatusPoint[],
  status: string,
  at: number,
  source: 'backend' | 'local',
): StatusPoint[] {
  const last = points[points.length - 1]
  if (last && last.status === status) return points
  return [...points, { status, at, source }]
}

/* ---------------- 火场分析：多边形 / 趋势（F05/F06） ---------------- */

/** 解析多边形坐标点集合：[[lat,lng]] / [[lng,lat]]（启发式纠正）/ [{latitude,longitude}] */
function parsePolygonPoints(v: unknown): [number, number][] {
  const arr = Array.isArray(v) ? v : []
  const pts: [number, number][] = []
  for (const entry of arr) {
    if (Array.isArray(entry) && entry.length >= 2) {
      const a = toNum(entry[0])
      const b = toNum(entry[1])
      if (a === null || b === null) continue
      // 经度绝对值通常大于纬度；按数值范围启发式纠正顺序
      pts.push(Math.abs(a) > 90 ? [b, a] : [a, b])
      continue
    }
    const rec = asRecord(entry)
    if (!rec) continue
    const lat = toNum(pickFirst(rec, 'latitude', 'lat'))
    const lng = toNum(pickFirst(rec, 'longitude', 'lng', 'lon'))
    if (lat !== null && lng !== null) pts.push([lat, lng])
  }
  return pts
}

/**
 * 宽松解析单代多边形。兼容：
 * - {polygon:{polygon:[[..]], radiusMeters, areaSquareMeters, createdAt}}
 * - {coordinates:[[..]], radiusMeters, ...}
 * - [[lat,lng],...]（纯坐标数组）
 */
export function parsePolygonShape(raw: unknown): FirePolygonShape | null {
  let coordsRaw: unknown
  let meta: Record<string, unknown> = {}
  if (Array.isArray(raw)) {
    coordsRaw = raw
  } else {
    const rec = asRecord(raw)
    if (!rec) return null
    meta = rec
    const container = pickFirst(rec, 'polygon', 'geometry', 'shape', 'firePolygon', 'areaPolygon')
    const containerRec = asRecord(container)
    coordsRaw = Array.isArray(container)
      ? container
      : containerRec
        ? pickFirst(containerRec, 'polygon', 'coordinates', 'coords', 'points', 'ring', 'boundary')
        : pickFirst(rec, 'coordinates', 'coords', 'points', 'vertices')
  }
  const points = parsePolygonPoints(coordsRaw)
  if (points.length < 3) return null
  return {
    points,
    radiusMeters: toNum(pickFirst(meta, 'radiusMeters', 'radius_m', 'radius')),
    areaSquareMeters: toNum(pickFirst(meta, 'areaSquareMeters', 'areaMeters', 'areaSqM', 'area')),
    timeMs: timeMsOf(meta),
    step: toNum(pickFirst(meta, 'growthStep', 'generation', 'round', 'seq', 'step')),
  }
}

/** 历史多边形（升序）。兼容数组直返或 {polygons/items/list/...} 包裹；无 step 时按序号补齐 */
export function parsePolygonHistory(data: unknown): FirePolygonShape[] {
  let arr: unknown[] | null = null
  if (Array.isArray(data)) {
    arr = data
  } else {
    const rec = asRecord(data)
    if (rec) {
      for (const key of ['polygons', 'items', 'list', 'content', 'records', 'history', 'generations']) {
        const v = rec[key]
        if (Array.isArray(v)) {
          arr = v
          break
        }
      }
    }
  }
  if (!arr) return []
  const out: FirePolygonShape[] = []
  arr.forEach((entry, idx) => {
    const shape = parsePolygonShape(entry)
    if (shape) {
      if (shape.step === null) shape.step = idx + 1
      out.push(shape)
    }
  })
  return out
}

/** POST /analysis 响应宽松解析 */
export function parseAnalysis(data: unknown): FireAnalysis {
  const rec = asRecord(data)
  const at = Date.now()
  if (!rec) return { polygon: null, tracking: null, growthStep: null, at }
  let polygon = parsePolygonShape(rec.polygon)
  const growthStep = toNum(pickFirst(rec, 'growthStep', 'generation', 'round', 'step'))
  if (polygon !== null && polygon.step === null) {
    polygon = { ...polygon, step: growthStep }
  }
  // tracking 可能是子对象，也可能平铺在根上
  const trRaw = asRecord(rec.tracking) ?? rec
  const direction = toNum(pickFirst(trRaw, 'direction', 'spreadDirection', 'directionDeg', 'bearing'))
  const speed = toNum(pickFirst(trRaw, 'speed', 'spreadSpeed', 'speedMps'))
  const areaGrowthRate = toNum(
    pickFirst(trRaw, 'areaGrowthRate', 'areaGrowth', 'growthRate', 'areaGrowthRatePercent'),
  )
  const trendRaw = pickFirst(trRaw, 'trend', 'spreadTrend', 'tendency')
  const hasTracking =
    direction !== null || speed !== null || areaGrowthRate !== null || trendRaw !== undefined
  const tracking = hasTracking
    ? {
        direction,
        speed,
        areaGrowthRate,
        trend: trendRaw === undefined ? null : String(trendRaw).trim().toUpperCase(),
      }
    : null
  return { polygon, tracking, growthStep, at }
}

/* ---------------- 火场分析展示辅助 ---------------- */

const TREND_ZH: Record<string, string> = {
  EXPANDING: '扩散中',
  EXPANSION: '扩散中',
  GROWING: '扩散中',
  SPREADING: '扩散中',
  STABLE: '趋于稳定',
  STEADY: '趋于稳定',
  SHRINKING: '收缩中',
  DECREASING: '收缩中',
  CONTAINED: '已控制',
}

export function zhTrend(t?: string | null): string {
  if (!t) return '—'
  return TREND_ZH[t.toUpperCase()] ?? t
}

const COMPASS = ['北', '东北', '东', '东南', '南', '西南', '西', '西北']

/** 蔓延方向：度数 + 罗盘方位 */
export function compassText(deg: number | null | undefined): string {
  const n = toNum(deg)
  if (n === null) return '—'
  const normalized = ((n % 360) + 360) % 360
  const idx = Math.round(normalized / 45) % 8
  return `${Math.round(normalized)}° · ${COMPASS[idx]}`
}

/** 面积展示：m²，≥1 公顷时附公顷 */
export function formatAreaText(area: number | null | undefined): string {
  const n = toNum(area)
  if (n === null) return '—'
  const a = Math.round(n)
  const base = `${a.toLocaleString('zh-CN')} m²`
  return a >= 10000 ? `${base}（${(a / 10000).toFixed(2)} 公顷）` : base
}

/** 数值展示：一位小数，空值 — */
export function num1(v: number | null | undefined): string {
  const n = toNum(v)
  return n === null ? '—' : n.toFixed(1)
}

/** 面积增长率展示：0-1 小数视为比例 ×100，其余按百分数原样 */
export function formatGrowthRate(v: number | null | undefined): string {
  const n = toNum(v)
  if (n === null) return '—'
  const scaled = n > 0 && n <= 1 ? n * 100 : n
  return scaled.toFixed(1)
}

/** 仅 CONFIRMED/TRACKING 可做火场分析 */
export function canAnalyze(status?: string | null): boolean {
  const s = (status ?? '').toUpperCase()
  return s === 'CONFIRMED' || s === 'TRACKING'
}

/** 扩散年轮填充透明度：第 i 代（0 基），最新一代 0.32，其余 0.08 起每代 +0.08（上限 0.24） */
export function polygonFillOpacity(index: number, total: number): number {
  if (index === total - 1) return 0.32
  return Math.min(0.08 + index * 0.08, 0.24)
}
