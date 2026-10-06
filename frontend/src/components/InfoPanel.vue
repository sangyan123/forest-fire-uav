<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import FireIncidentList from './FireIncidentList.vue'
import type { FireIncident, PatrolClosurePayload, PatrolScheduleView, TrackedCommand, UavState } from '../types'
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
  suppliesRemaining: number | null
}>()

const emit = defineEmits<{
  (e: 'quick', commandType: 'TAKEOFF' | 'LAND' | 'RETURN_HOME' | 'LOUDSPEAKER_BROADCAST' | 'DROP_SUPPLIES'): void
  (e: 'select-incident', id: string): void
  (e: 'toggle-patrol', on: boolean): void
  (e: 'toggle-closure', payload: PatrolClosurePayload): void
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

/** 开关 = 是否有班次在飞（手动/计划/禁期计划任一）；关闭即返航 */
const patrolOn = computed(() => props.patrolSchedule?.shift != null)

function patrolChecked(e: Event): boolean {
  return (e.target as HTMLInputElement).checked
}

/* ---------------- 禁期巡逻（封山期加强窗口） ---------------- */

const cEnabled = computed(() => props.patrolSchedule?.closure?.enabled ?? false)
const cStart = ref('')
const cEnd = ref('')
const cTime = ref('06:00')
const cHours = ref(14)
/** 用户手动改过表单后，10s 轮询不再回填覆盖 */
const cDirty = ref(false)

watch(
  () => props.patrolSchedule?.closure,
  (c) => {
    if (!c || cDirty.value) return
    cStart.value = c.startDate ?? ''
    cEnd.value = c.endDate ?? ''
    cTime.value = c.startTime || '06:00'
    cHours.value = c.durationHours || 14
  },
  { immediate: true },
)

const closureStatusText = computed<string>(() => {
  const c = props.patrolSchedule?.closure
  if (!c) return '—'
  const range = c.startDate && c.endDate ? `${c.startDate} ~ ${c.endDate}` : '未配置日期'
  const startText = (c.startTime || '').slice(0, 5)
  if (c.enabled) {
    const active = props.patrolSchedule?.mode === 'CLOSURE'
    return `${range} · 每天 ${startText} 起飞巡逻 ${c.durationHours} 小时${active ? '（禁期生效中）' : '（未到禁期日期）'}`
  }
  return '未启用 · 按正常巡逻计划运行'
})

function closurePayload(enabled: boolean): PatrolClosurePayload {
  return {
    enabled,
    startDate: cStart.value || undefined,
    endDate: cEnd.value || undefined,
    startTime: cTime.value || undefined,
    durationHours: Number(cHours.value) || undefined,
  }
}

function onClosureToggle(e: Event): void {
  cDirty.value = false
  emit('toggle-closure', closurePayload(patrolChecked(e)))
}

function applyClosure(): void {
  cDirty.value = false
  emit('toggle-closure', closurePayload(true))
}

const patrolStatusText = computed<string>(() => {
  const ps = props.patrolSchedule
  if (!ps) return '—'
  const planLabel = ps.mode === 'CLOSURE' ? '禁期计划' : '正常计划'
  const s = ps.shift
  if (s) {
    const endText = new Date(s.endAt).toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })
    if (s.returning) return `🛩️ 班次结束返航中 · 到家后自动降落充电`
    const originLabel = s.origin === 'MANUAL'
      ? '手动班次'
      : (ps.mode === 'CLOSURE' ? '禁期计划班次' : '计划班次')
    return `🛩️ 巡逻中（${originLabel}）· ${endText} 自动返航`
  }
  return `⏱️ 待命 · ${planLabel}：每天 ${ps.startTime} 起飞巡逻 ${ps.durationHours} 小时`
})

/* ---------------- 投放物资（空中一键空投） ---------------- */

/** 视为"不在空中"的设备状态（含离线/故障）；设备侧仍有兜底校验，拒绝原因经 toast 展示 */
const GROUNDED_STATUSES = new Set(['IDLE', 'STANDBY', 'LANDED', 'GROUNDED', 'CHARGING', 'OFFLINE', 'ERROR'])

const supplyDisabled = computed(() => {
  if (props.suppliesRemaining === 0) return true
  if (props.state === null) return true
  return GROUNDED_STATUSES.has(String(props.state.status ?? '').toUpperCase())
})

const supplyLabel = computed(() =>
  props.suppliesRemaining === null ? '📦 投放物资' : `📦 投放物资（剩 ${props.suppliesRemaining} 件）`,
)

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
        <button
          class="cmd-btn"
          :disabled="supplyDisabled"
          :title="supplyDisabled ? '需无人机在空中且有余量方可空投' : '向无人机当前位置空投应急物资包'"
          @click="$emit('quick', 'DROP_SUPPLIES')"
        >{{ supplyLabel }}</button>
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
      <div class="patrol-row">
        <div class="patrol-text">
          <b>禁期巡逻</b>
          <small class="patrol-status">{{ closureStatusText }}</small>
        </div>
        <label class="patrol-switch">
          <input
            type="checkbox"
            :checked="cEnabled"
            @change="onClosureToggle($event)"
          />
          <span class="patrol-slider"></span>
        </label>
      </div>
      <div v-if="cEnabled" class="closure-form">
        <input class="closure-input" type="date" v-model="cStart" @input="cDirty = true" title="禁期开始日期" />
        <span class="closure-sep">~</span>
        <input class="closure-input" type="date" v-model="cEnd" @input="cDirty = true" title="禁期结束日期" />
        <input class="closure-input closure-narrow" type="time" v-model="cTime" @input="cDirty = true" title="禁期每日起飞时刻" />
        <input class="closure-input closure-narrow" type="number" min="1" max="23" v-model="cHours" @input="cDirty = true" title="禁期每日巡逻时长（小时）" />
        <button class="cmd-btn closure-apply" @click="applyClosure">应用</button>
      </div>
      <p class="hint">提示：在左侧地图上点击任意位置可下发 GOTO 指令；巡检发现可疑人员可随时喊话警告，发现受伤/受困人员可空投应急物资。</p>
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
