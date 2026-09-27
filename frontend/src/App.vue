<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import InfoPanel from './components/InfoPanel.vue'
import MapView from './components/MapView.vue'
import Toasts from './components/Toasts.vue'
import { getCommand, getUavState, postCommand } from './api'
import { TERMINAL_CMD_STATUSES, zhCmdStatus, zhCmdType } from './labels'
import { pushToast } from './toast'
import type { CommandType, GotoPayload, TrackedCommand, UavState } from './types'

const DEVICE_ID = 'UAV-001'
const STATE_POLL_MS = 1000
const COMMAND_POLL_MS = 1000
const MAX_TRACKED = 12

const state = ref<UavState | null>(null)
const connectionLost = ref(false)
const lastUpdateAt = ref<number | null>(null)
const now = ref(Date.now())

const tracked = ref<TrackedCommand[]>([])
let trackedSeq = 0
const pollTimers = new Map<string, ReturnType<typeof setInterval>>()

let stateTimer: ReturnType<typeof setInterval> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined

async function pollState(): Promise<void> {
  try {
    const s = await getUavState(DEVICE_ID)
    state.value = s
    lastUpdateAt.value = Date.now()
    connectionLost.value = false
  } catch {
    connectionLost.value = true
  }
}

function stopPolling(commandId: string): void {
  const t = pollTimers.get(commandId)
  if (t !== undefined) {
    clearInterval(t)
    pollTimers.delete(commandId)
  }
}

function startPolling(item: TrackedCommand): void {
  stopPolling(item.commandId)
  const timer = setInterval(async () => {
    try {
      const rec = await getCommand(item.commandId)
      const st = String(rec.status ?? '').trim()
      if (st) item.status = st.toUpperCase()
      if (TERMINAL_CMD_STATUSES.has(item.status)) {
        item.terminal = true
        stopPolling(item.commandId)
        if (item.status === 'SUCCESS' || item.status === 'SUCCEEDED') {
          pushToast('success', `指令执行成功：${item.label}`)
        } else {
          pushToast('error', `指令执行失败：${item.label}（${zhCmdStatus(item.status)}）`)
        }
      }
    } catch {
      // 轮询失败时保持当前状态继续轮询；连接断开由状态轮询角标提示
    }
  }, COMMAND_POLL_MS)
  pollTimers.set(item.commandId, timer)
}

function track(commandId: string, label: string, initialStatus: string): void {
  const item: TrackedCommand = {
    key: ++trackedSeq,
    commandId,
    label,
    status: initialStatus,
    createdAt: Date.now(),
    terminal: TERMINAL_CMD_STATUSES.has(initialStatus),
  }
  tracked.value = [item, ...tracked.value].slice(0, MAX_TRACKED)
  if (!item.terminal) startPolling(item)
}

async function sendCommand(
  commandType: CommandType,
  params?: Record<string, unknown>,
  label?: string,
): Promise<void> {
  const text = label ?? zhCmdType(commandType)
  try {
    const rec = await postCommand(DEVICE_ID, { commandType, params })
    const cid = rec.commandId ?? rec.id
    if (cid === undefined || cid === null || String(cid) === '') {
      pushToast('error', `指令下发成功但后端未返回命令 ID，无法跟踪：${text}`)
      return
    }
    track(String(cid), text, String(rec.status ?? 'CREATED').toUpperCase())
    pushToast('info', `指令已下发：${text}`)
  } catch (e) {
    pushToast('error', `指令下发失败：${text}（${e instanceof Error ? e.message : '未知错误'}）`)
  }
}

function onQuick(commandType: 'TAKEOFF' | 'LAND' | 'RETURN_HOME'): void {
  void sendCommand(commandType)
}

function onGoto(payload: GotoPayload): void {
  void sendCommand(
    'GOTO',
    {
      latitude: payload.latitude,
      longitude: payload.longitude,
      altitude: payload.altitude,
      altitudeMode: 'RELATIVE_TO_TAKEOFF',
    },
    `GOTO (${payload.latitude.toFixed(5)}, ${payload.longitude.toFixed(5)}) @${payload.altitude}m`,
  )
}

onMounted(() => {
  void pollState()
  stateTimer = setInterval(() => {
    void pollState()
  }, STATE_POLL_MS)
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 500)
})

onUnmounted(() => {
  if (stateTimer !== undefined) clearInterval(stateTimer)
  if (clockTimer !== undefined) clearInterval(clockTimer)
  for (const id of [...pollTimers.keys()]) stopPolling(id)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark"></span>
        森林防火无人机监控 <small>Demo · Vue 3 + Leaflet</small>
      </div>
      <div class="conn" :class="connectionLost ? 'bad' : 'ok'">
        <span class="dot"></span>
        {{ connectionLost ? '后端连接断开' : '后端已连接' }}
      </div>
    </header>
    <main class="content">
      <MapView :state="state" @goto="onGoto" />
      <InfoPanel
        :state="state"
        :connection-lost="connectionLost"
        :last-update="lastUpdateAt"
        :now="now"
        :commands="tracked"
        @quick="onQuick"
      />
    </main>
    <Toasts />
  </div>
</template>
