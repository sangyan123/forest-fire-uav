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
