/**
 * 火情域逻辑：状态中文标签 / 颜色 / 状态机 / 字段宽松解析。
 *
 * 后端字段命名可能不统一（firePoint.latitude / latitude / incidentLatitude 等），
 * 所有取值都走 pickPath 宽松回退，保证演示时不因字段差异丢数据。
 */
import type { FireIncident, StatusPoint, VerificationResult } from './types'

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
