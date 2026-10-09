import type { CommandRecord, CommandType, Envelope, MissionRecord, PatrolClosurePayload, PatrolScheduleView, UavState } from './types'

// 默认同源相对路径，由 nginx（容器）或 vite proxy（本地 dev/preview）转发；
// 不能写死 localhost，否则只有浏览器与 Docker 宿主同机时才能连上
const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? ''
const SIM_BASE = (import.meta.env.VITE_SIMULATOR_BASE as string | undefined) ?? ''

/** 统一请求：解析 {code,message,data} 包裹；code 兼容数字 0 与字符串 "0"；
 * 兼容 204/空 body；非 0 抛出 message。 */
async function requestBase<T>(base: string, path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${base}${path}`, init)
  } catch {
    throw new Error('网络错误，无法连接后端')
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }
  const text = await res.text()
  if (!text.trim()) {
    return undefined as T
  }
  let body: Envelope<T>
  try {
    body = JSON.parse(text) as Envelope<T>
  } catch {
    throw new Error('后端响应格式错误')
  }
  const code: unknown = (body as Partial<Envelope<T>>).code
  if (code !== 0 && String(code) !== '0') {
    const msg = (body as Partial<Envelope<T>>).message
    throw new Error(msg || `后端错误 code=${String(code)}`)
  }
  return body.data
}

function request<T>(path: string, init?: RequestInit): Promise<T> {
  return requestBase<T>(API_BASE, path, init)
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/* ---------------- 无人机监控（原有） ---------------- */

export function getUavState(deviceId: string): Promise<UavState> {
  return request<UavState>(`/api/v1/uavs/${deviceId}/state`)
}

export function postCommand(
  deviceId: string,
  payload: { commandType: CommandType; params?: Record<string, unknown> },
): Promise<CommandRecord> {
  return request<CommandRecord>(`/api/v1/uavs/${deviceId}/commands`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(payload),
  })
}

export function getCommand(commandId: string): Promise<CommandRecord> {
  return request<CommandRecord>(`/api/v1/commands/${encodeURIComponent(commandId)}`)
}

/* ---------------- 火情模拟场景（simulator, :8002） ---------------- */

/** 一键注入火情场景；body 可选坐标与结论走向（缺省 CONFIRMED） */
export function startFireScenario(
  payload?: { latitude?: number; longitude?: number; verdict?: 'CONFIRMED' | 'FALSE_ALARM' },
): Promise<unknown> {
  return requestBase<unknown>(SIM_BASE, '/simulator/scenarios/fire/start', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(payload ?? {}),
  })
}

export function stopFireScenario(): Promise<unknown> {
  return requestBase<unknown>(SIM_BASE, '/simulator/scenarios/fire/stop', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: '{}',
  })
}

/* ---------------- 场景注册表（simulator, :8002） ---------------- */

/** 场景注册表启动响应（mock-uav /simulator/scenarios/{id}/start） */
export interface SimulatorScenarioStart {
  scenarioId?: string
  verdict?: string
  silenceSeconds?: number
  commsSilent?: boolean
  resumeInSeconds?: number
  fireScenario?: {
    active?: boolean
    latitude?: number | null
    longitude?: number | null
    capturing?: boolean
    verdict?: string
  }
}

/** 启动注册表场景：scenario-01 正常巡检 / scenario-02 火情发现 / scenario-03 编排 / scenario-04 误报 / scenario-05 编排 / scenario-06 断联；未知号返回 40001 */
export function startScenario(
  scenarioId: string,
  body?: Record<string, unknown>,
): Promise<SimulatorScenarioStart> {
  return requestBase<SimulatorScenarioStart>(
    SIM_BASE,
    `/simulator/scenarios/${encodeURIComponent(scenarioId)}/start`,
    {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(body ?? {}),
    },
  )
}

/** 模拟器状态（含 currentScenarioId），字段宽松解析见 fire.ts parseSimulatorStatus */
export function getSimulatorStatus(): Promise<unknown> {
  return requestBase<unknown>(SIM_BASE, '/simulator/status')
}

/* ---------------- 火情事件 ---------------- */

/** 事件列表原始数据，结构宽松，用 fire.ts 的 parseIncidents 解析 */
export function getFireIncidents(): Promise<unknown> {
  return request<unknown>('/api/v1/fire/incidents')
}

export function getFireIncident(id: string): Promise<unknown> {
  return request<unknown>(`/api/v1/fire/incidents/${encodeURIComponent(id)}`)
}

export function patchIncidentStatus(id: string, status: string): Promise<unknown> {
  return request<unknown>(`/api/v1/fire/incidents/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: JSON_HEADERS,
    body: JSON.stringify({ status }),
  })
}

/** 触发 AI 核验，后端返回 decision */
export function postIncidentVerification(id: string): Promise<unknown> {
  return request<unknown>(`/api/v1/fire/incidents/${encodeURIComponent(id)}/verification`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({}),
  })
}

/* ---------------- 火场分析（F05/F06） ---------------- */

/** 触发一轮火场分析（仅 CONFIRMED/TRACKING 可用），返回新多边形与趋势 */
export function postIncidentAnalysis(id: string): Promise<unknown> {
  return request<unknown>(`/api/v1/fire/incidents/${encodeURIComponent(id)}/analysis`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({}),
  })
}

/** 历史多边形（按轮次升序），用于绘制扩散年轮 */
export function getIncidentPolygons(id: string): Promise<unknown> {
  return request<unknown>(`/api/v1/fire/incidents/${encodeURIComponent(id)}/polygons`)
}

/** 最新蔓延趋势 */
export function getIncidentTracking(id: string): Promise<unknown> {
  return request<unknown>(`/api/v1/fire/incidents/${encodeURIComponent(id)}/tracking`)
}

/* ---------------- 任务派单 ---------------- */

export interface MissionCreatePayload {
  missionType: string
  incidentId: string
  target: {
    latitude: number
    longitude: number
    altitude: number
    altitudeMode: string
  }
  priority: number
  requiredCapabilities: string[]
}

export function createMission(payload: MissionCreatePayload): Promise<MissionRecord> {
  return request<MissionRecord>('/api/v1/missions', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(payload),
  })
}

export function startMission(missionId: string): Promise<unknown> {
  return request<unknown>(`/api/v1/missions/${encodeURIComponent(missionId)}/start`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: '{}',
  })
}

/* ---------------- 定时巡逻计划 ---------------- */

export function getPatrolSchedule(): Promise<PatrolScheduleView> {
  return request<PatrolScheduleView>('/api/v1/patrol-schedule')
}

/** 快捷指令开关：true=立即起飞开手动班次（飞到窗口结束时刻自动返航），false=结束手动班次 */
export function updatePatrolSchedule(manual: boolean): Promise<PatrolScheduleView> {
  return request<PatrolScheduleView>('/api/v1/patrol-schedule', {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify({ manual }),
  })
}

/** 禁期（封山期）配置：日期范围内按独立窗口巡逻，到期自动切回正常计划 */
export function updatePatrolClosure(payload: PatrolClosurePayload): Promise<PatrolScheduleView> {
  return request<PatrolScheduleView>('/api/v1/patrol-schedule/closure', {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify(payload),
  })
}

/** 任务 ID 宽松取值 */
export function missionIdOf(record: MissionRecord | null | undefined): string | null {
  if (!record) return null
  const raw = record as Record<string, unknown>
  const v =
    raw.id ??
    raw.missionId ??
    raw.missionUUID ??
    (typeof raw.data === 'object' && raw.data !== null
      ? (raw.data as Record<string, unknown>).id
      : undefined)
  if (v === undefined || v === null) return null
  const s = String(v)
  return s || null
}

/* ---------------- 火情风险检测页（F07+F11+F08，03号第91章） ---------------- */

/** 风险区域（risk_area 行） */
export interface RiskArea {
  areaId: string
  areaCode: string
  areaName: string
  /** [lat,lon] 闭合环（GeoJSON 外环，Leaflet 直接可用） */
  geometry: [number, number][] | null
  riskScore: number | null
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW' | null
  evaluationTime: string | null
}

/** 五因子分项（risk_feature 行） */
export interface RiskFactor {
  featureType: string
  featureValue: number | null
  weight: number | null
  contribution: number | null
  source: string | null
}

/** 区域详情 = 区域字段 + factors */
export type RiskAreaDetail = RiskArea & { factors: RiskFactor[] }

/** 评估摘要（POST /risk/assess） */
export interface RiskAssessSummary {
  assessedCount: number
  highCount: number
  mediumCount: number
  lowCount: number
  assessedAt: string
}

/** 火势预测（fire_prediction 行） */
export interface FirePrediction {
  predictionId: string
  incidentId: string
  baseTime: string
  forecastMinutes: number
  predictedGeometry: [number, number][] | null
  predictedAreaSquareMeter: number | null
  confidence: number | null
  environmentalInput: Record<string, unknown> | null
  predictionResult: Record<string, unknown> | null
  createdAt: string
}

/** 巡检建议航点 */
export interface SuggestionWaypoint {
  sequenceNo: number
  latitude: number
  longitude: number
  altitude: number
}

/** 巡检建议（patrol_area 行 + 区域摘要） */
export interface PatrolSuggestion {
  suggestionId: string
  riskAreaId: string
  areaCode: string | null
  riskScore: number | null
  geometry: [number, number][] | null
  waypoints: SuggestionWaypoint[]
  priority: number | null
  estimatedDurationMin: number | null
  status: 'SUGGESTED' | 'DISPATCHED' | 'DISMISSED' | 'EXPIRED'
  missionId: string | null
  generatedAt: string
}

export function getRiskAreas(level?: string): Promise<RiskArea[]> {
  const q = level ? `?level=${encodeURIComponent(level)}` : ''
  return request<RiskArea[]>(`/api/v1/risk/areas${q}`)
}

export function getRiskAreaDetail(areaId: string): Promise<RiskAreaDetail> {
  return request<RiskAreaDetail>(`/api/v1/risk/areas/${encodeURIComponent(areaId)}`)
}

export function postRiskAssess(): Promise<RiskAssessSummary> {
  return request<RiskAssessSummary>('/api/v1/risk/assess', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: '{}',
  })
}

export function getLatestPredictions(incidentId?: string): Promise<FirePrediction[]> {
  const q = incidentId ? `?incidentId=${encodeURIComponent(incidentId)}` : ''
  return request<FirePrediction[]>(`/api/v1/predictions/latest${q}`)
}

export function postPredictionRun(incidentId?: string): Promise<FirePrediction[]> {
  return request<FirePrediction[]>('/api/v1/predictions/run', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(incidentId ? { incidentId } : {}),
  })
}

export function getPatrolSuggestions(status?: string): Promise<PatrolSuggestion[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : ''
  return request<PatrolSuggestion[]>(`/api/v1/patrol-suggestions${q}`)
}

export function postPatrolSuggestionsGenerate(areaIds?: string[]): Promise<PatrolSuggestion[]> {
  return request<PatrolSuggestion[]>('/api/v1/patrol-suggestions/generate', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(areaIds && areaIds.length ? { areaIds } : {}),
  })
}

export function postPatrolSuggestionDispatch(suggestionId: string): Promise<unknown> {
  return request<unknown>(
    `/api/v1/patrol-suggestions/${encodeURIComponent(suggestionId)}/dispatch`,
    { method: 'POST', headers: JSON_HEADERS, body: '{}' },
  )
}

export function postPatrolSuggestionDismiss(suggestionId: string): Promise<PatrolSuggestion> {
  return request<PatrolSuggestion>(
    `/api/v1/patrol-suggestions/${encodeURIComponent(suggestionId)}/dismiss`,
    { method: 'POST', headers: JSON_HEADERS, body: '{}' },
  )
}
