const UAV_STATUS_ZH: Record<string, string> = {
  IDLE: '空闲',
  STANDBY: '待命',
  INIT: '初始化',
  INITIALIZING: '初始化中',
  READY: '就绪',
  ARMED: '已解锁',
  TAKEOFF: '起飞中',
  AIRBORNE: '空中',
  HOVER: '悬停',
  GOTO: '前往目标点',
  RETURNING: '返航中',
  RTL: '返航中',
  LANDING: '降落中',
  LANDED: '已降落',
  GROUNDED: '地面',
  CHARGING: '充电中',
  ONLINE: '在线',
  OFFLINE: '离线',
  ERROR: '故障',
  FAULT: '故障',
  UNKNOWN: '未知',
}

export function zhUavStatus(s?: string | null): string {
  if (!s) return '未知'
  return UAV_STATUS_ZH[s.toUpperCase()] ?? s
}

const GPS_STATUS_ZH: Record<string, string> = {
  NO_GPS: '无 GPS',
  NO_FIX: '无定位',
  FIX_2D: '2D 定位',
  FIX_3D: '3D 定位',
  GPS_2D: 'GPS 2D',
  GPS_3D: 'GPS 3D',
  '3D_FIX': '3D 定位',
  SINGLE: '单点定位',
  DGPS: '差分定位',
  RTK: 'RTK',
  RTK_FLOAT: 'RTK 浮点解',
  RTK_FIXED: 'RTK 固定解',
}

export function zhGpsStatus(s?: string | null): string {
  if (!s) return '—'
  return GPS_STATUS_ZH[s.toUpperCase()] ?? s
}

const CMD_STATUS_ZH: Record<string, string> = {
  CREATED: '已创建',
  PENDING: '等待中',
  QUEUED: '排队中',
  ACCEPTED: '已接受',
  SENT: '已下发',
  ACK: '已确认',
  ACKED: '已确认',
  EXECUTING: '执行中',
  IN_PROGRESS: '执行中',
  SUCCESS: '成功',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  REJECTED: '已拒绝',
  TIMEOUT: '超时',
  EXPIRED: '已过期',
}

export function zhCmdStatus(s?: string | null): string {
  if (!s) return '未知'
  return CMD_STATUS_ZH[s.toUpperCase()] ?? s
}

export const TERMINAL_CMD_STATUSES = new Set([
  'SUCCESS',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'REJECTED',
  'TIMEOUT',
  'EXPIRED',
])

export const CMD_TYPE_ZH: Record<string, string> = {
  TAKEOFF: '一键起飞',
  LAND: '降落',
  GOTO: '飞往目标点',
  GIMBAL_LOOK_AT: '云台对准',
  CAPTURE_RGB: '可见光拍照',
  CAPTURE_THERMAL: '热成像拍照',
  RETURN_HOME: '一键返航',
  PAUSE: '暂停任务',
  RESUME: '恢复任务',
  DROP_EXTINGUISHING_BALL: '投放灭火弹',
  LOUDSPEAKER_BROADCAST: '森林防护喊话',
}

export function zhCmdType(s?: string | null): string {
  if (!s) return '未知指令'
  return CMD_TYPE_ZH[s.toUpperCase()] ?? s
}
