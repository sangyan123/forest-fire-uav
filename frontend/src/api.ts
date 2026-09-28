import type { CommandRecord, CommandType, Envelope, MissionRecord, UavState } from './types'

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080'
const SIM_BASE =
  (import.meta.env.VITE_SIMULATOR_BASE as string | undefined) ?? 'http://localhost:8002'

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
