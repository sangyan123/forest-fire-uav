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
  | 'DROP_EXTINGUISHING_BALL'
  | 'LOUDSPEAKER_BROADCAST'

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
  /** 演示场景类型（CONFIRMED 火情线 / FALSE_ALARM 误报线） */
  scenarioType: string | null
  raw: Record<string, unknown>
}

/** 状态时间线上的一点 */
export interface StatusPoint {
  status: string
  at: number | null
  /** backend=接口透出的历史；local=前端轮询记录 */
  source: 'backend' | 'local'
}

/** 一代火场多边形（宽松解析后的统一形状） */
export interface FirePolygonShape {
  /** [[lat, lng], ...] */
  points: [number, number][]
  radiusMeters: number | null
  areaSquareMeters: number | null
  timeMs: number | null
  /** 分析轮次（第几代） */
  step: number | null
}

/** 蔓延趋势（宽松解析） */
export interface FireTracking {
  direction: number | null
  speed: number | null
  areaGrowthRate: number | null
  trend: string | null
}

/** POST /analysis 响应（宽松解析） */
export interface FireAnalysis {
  polygon: FirePolygonShape | null
  tracking: FireTracking | null
  growthStep: number | null
  at: number
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

/** 定时巡逻视图：每日窗口（startTime~startTime+durationHours）+ 当前班次（内存态） */
export interface PatrolScheduleView {
  startTime: string
  durationHours: number
  zone: string
  shift: {
    origin: 'MANUAL' | 'SCHEDULED'
    startedAt: string
    endAt: string
    returning: boolean
  } | null
}
