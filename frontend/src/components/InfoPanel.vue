<script setup lang="ts">
import { computed } from 'vue'
import FireIncidentList from './FireIncidentList.vue'
import type { FireIncident, PatrolScheduleView, TrackedCommand, UavState } from '../types'
import { zhCmdStatus, zhGpsStatus, zhUavStatus } from '../labels'

const props = defineProps<{
  state: UavState | null
  connectionLost: boolean
  lastUpdate: number | null
  now: number
  commands: TrackedCommand[]
  incidents: FireIncident[]
  selectedIncidentId: string | null
  patrolSchedule: PatrolScheduleView | null
}>()

defineEmits<{
  (e: 'quick', commandType: 'TAKEOFF' | 'LAND' | 'RETURN_HOME' | 'LOUDSPEAKER_BROADCAST'): void
  (e: 'select-incident', id: string): void
  (e: 'toggle-patrol', on: boolean): void
}>()

const battery = computed<number | null>(() => {
  const b = Number(props.state?.battery)
  return Number.isFinite(b) ? b : null
})

/** 心跳超时（backend 置 OFFLINE）或断联演示中 */
const uavOffline = computed(() => String(props.state?.status ?? '').toUpperCase() === 'OFFLINE')

const batteryLevel = computed<'ok' | 'warn' | 'mid' | 'crit' | null>(() => {
  const b = battery.value
  if (b === null) return null
  if (b > 30) return 'ok'
  if (b > 25) return 'warn'
  if (b > 20) return 'mid'
  return 'crit'
})

function num(v: unknown, digits = 1): string {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(digits) : '—'
}

const relTime = computed<string>(() => {
  if (props.lastUpdate === null) return '—'
  const sec = Math.max(0, Math.round((props.now - props.lastUpdate) / 1000))
  return `${sec} 秒前`
})

/* ---------------- 定时巡逻（快捷指令内开关） ---------------- */

const patrolOn = computed(() => props.patrolSchedule?.shift?.origin === 'MANUAL')

function patrolChecked(e: Event): boolean {
  return (e.target as HTMLInputElement).checked
}

const patrolStatusText = computed<string>(() => {
  const ps = props.patrolSchedule
  if (!ps) return '—'
  const s = ps.shift
  if (s) {
    const endText = new Date(s.endAt).toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })
    if (s.returning) return `🛩️ 班次结束返航中 · 到家后自动降落充电`
    return `🛩️ 巡逻中（${s.origin === 'MANUAL' ? '手动' : '计划'}班次）· ${endText} 自动返航`
  }
  return `⏱️ 待命 · 每天 ${ps.startTime} 自动起飞巡逻 ${ps.durationHours} 小时`
})

function chipClass(status: string): string {
  const s = status.toUpperCase()
  if (s === 'SUCCESS' || s === 'SUCCEEDED') return 's-success'
  if (s === 'FAILED' || s === 'REJECTED' || s === 'TIMEOUT' || s === 'CANCELLED' || s === 'EXPIRED') return 's-failed'
  if (s === 'EXECUTING' || s === 'IN_PROGRESS' || s === 'SENT' || s === 'ACK' || s === 'ACKED') return 's-running'
  if (s === 'CREATED' || s === 'PENDING' || s === 'QUEUED' || s === 'ACCEPTED') return 's-created'
  return 's-other'
}

function timeText(ts: number): string {
  return new Date(ts).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <aside class="panel">
    <section class="card">
      <h3>
        设备状态
        <span v-if="uavOffline" class="uav-offline-badge">离线</span>
      </h3>
      <div v-if="!state" class="placeholder">{{ connectionLost ? '后端连接断开，等待恢复…' : '正在获取无人机状态…' }}</div>
      <template v-else>
        <div class="kv"><span>设备 ID</span><b>{{ state.deviceId }}</b></div>
        <div class="kv"><span>设备状态</span><b>{{ zhUavStatus(state.status) }}<small v-if="state.status" style="color: var(--muted); font-weight: 400"> ({{ state.status }})</small></b></div>
        <div class="battery">
          <div class="battery-head">
            <span>电量</span>
            <b :class="batteryLevel ? `txt-${batteryLevel}` : ''">{{ battery === null ? '—' : `${battery}%` }}</b>
          </div>
          <div class="battery-track">
            <div v-if="batteryLevel" class="battery-fill" :class="`lvl-${batteryLevel}`" :style="{ width: `${Math.min(100, Math.max(0, battery ?? 0))}%` }"></div>
          </div>
        </div>
        <div class="grid2">
          <div class="kv"><span>速度</span><b>{{ num(state.speed) }} m/s</b></div>
          <div class="kv"><span>航向</span><b>{{ num(state.heading, 0) }}°</b></div>
          <div class="kv"><span>高度</span><b>{{ num(state.altitude) }} m</b></div>
          <div class="kv"><span>最后更新</span><b>{{ relTime }}</b></div>
          <div class="kv"><span>GPS</span><b>{{ zhGpsStatus(state.gpsStatus) }}</b></div>
          <div class="kv"><span>RTK</span><b>{{ zhGpsStatus(state.rtkStatus) }}</b></div>
        </div>
        <p v-if="uavOffline" class="hint">心跳超时，地图与面板展示最后已知位置</p>
      </template>
    </section>

    <FireIncidentList
      :incidents="incidents"
      :now="now"
      :selected-id="selectedIncidentId"
      @select="$emit('select-incident', $event)"
    />

    <section class="card">
      <h3>快捷指令</h3>
      <div class="btn-row">
        <button class="cmd-btn" @click="$emit('quick', 'TAKEOFF')">一键起飞</button>
        <button class="cmd-btn" @click="$emit('quick', 'LAND')">降落</button>
        <button class="cmd-btn" @click="$emit('quick', 'RETURN_HOME')">一键返航</button>
      </div>
      <div class="btn-row btn-row-stack">
        <button class="cmd-btn cmd-btn-broadcast" @click="$emit('quick', 'LOUDSPEAKER_BROADCAST')">📢 防护喊话</button>
      </div>
      <div class="patrol-row btn-row-stack">
        <div class="patrol-text">
          <b>定时巡逻</b>
          <small class="patrol-status">{{ patrolStatusText }}</small>
        </div>
        <label class="patrol-switch">
          <input
            type="checkbox"
            :checked="patrolOn"
            @change="$emit('toggle-patrol', patrolChecked($event))"
          />
          <span class="patrol-slider"></span>
        </label>
      </div>
      <p class="hint">提示：在左侧地图上点击任意位置可下发 GOTO 指令；巡检发现可疑人员可随时喊话警告。</p>
    </section>

    <section class="card">
      <h3>指令跟踪</h3>
      <div v-if="commands.length === 0" class="placeholder">暂无指令</div>
      <ul v-else class="cmd-list">
        <li v-for="c in commands" :key="c.key">
          <div class="cmd-line">
            <span class="cmd-label">{{ c.label }}</span>
            <span class="chip" :class="chipClass(c.status)">{{ zhCmdStatus(c.status) }}</span>
          </div>
          <div class="cmd-sub">指令 ID: {{ c.commandId }} · {{ timeText(c.createdAt) }}</div>
        </li>
      </ul>
    </section>
  </aside>
</template>
