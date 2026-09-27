import type { CommandRecord, CommandType, Envelope, UavState } from './types'

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${API_BASE}${path}`, init)
  } catch {
    throw new Error('网络错误，无法连接后端')
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`)
  }
  const body = (await res.json()) as Envelope<T>
  if (body.code !== 0) {
    throw new Error(body.message || `后端错误 code=${body.code}`)
  }
  return body.data
}

export function getUavState(deviceId: string): Promise<UavState> {
  return request<UavState>(`/api/v1/uavs/${deviceId}/state`)
}

export function postCommand(
  deviceId: string,
  payload: { commandType: CommandType; params?: Record<string, unknown> },
): Promise<CommandRecord> {
  return request<CommandRecord>(`/api/v1/uavs/${deviceId}/commands`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function getCommand(commandId: string): Promise<CommandRecord> {
  return request<CommandRecord>(`/api/v1/commands/${encodeURIComponent(commandId)}`)
}
