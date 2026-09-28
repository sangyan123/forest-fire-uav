export interface UavState {
  deviceId: string
  status: string
  latitude: number
  longitude: number
  altitude: number
  heading: number
  speed: number
  battery: number
  gpsStatus: string
  rtkStatus: string
}

export type CommandType =
  | 'TAKEOFF'
  | 'LAND'
  | 'GOTO'
  | 'GIMBAL_LOOK_AT'
  | 'CAPTURE_RGB'
  | 'CAPTURE_THERMAL'
  | 'RETURN_HOME'
  | 'PAUSE'
  | 'RESUME'

export interface CommandRecord {
  id?: string | number
  commandId?: string | number
  commandType?: string
  status?: string
  params?: Record<string, unknown>
  [key: string]: unknown
}

export interface Envelope<T> {
  code: number
  message: string
  data: T
  requestId?: string
}

export interface TrackedCommand {
  key: number
  commandId: string
  label: string
  status: string
  createdAt: number
  terminal: boolean
}

export interface GotoPayload {
  latitude: number
  longitude: number
  altitude: number
}

/** 火情事件（后端字段命名可能不一致，统一在此宽松归一化） */
export interface FireIncident {
  id: string
  incidentNo: string
  status: string
  latitude: number | null
  longitude: number | null
  /** 归一化到 0-100 的置信度 */
  confidence: number | null
  /** 首次检测/创建时间的毫秒时间戳 */
  timeMs: number | null
  raw: Record<string, unknown>
}

/** AI 核验结果（宽松解析自 POST /verification 响应） */
export interface VerificationResult {
  decision: string | null
  confidence: number | null
  at: number
}

export interface MissionRecord {
  id?: string | number
  missionId?: string | number
  status?: string
  [key: string]: unknown
}
