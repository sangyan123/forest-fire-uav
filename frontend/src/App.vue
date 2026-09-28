<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import InfoPanel from './components/InfoPanel.vue'
import MapView from './components/MapView.vue'
import Toasts from './components/Toasts.vue'
import {
  createMission,
  getCommand,
  getFireIncidents,
  getIncidentPolygons,
  getIncidentTracking,
  getUavState,
  missionIdOf,
  patchIncidentStatus,
  postCommand,
  postIncidentAnalysis,
  postIncidentVerification,
  startFireScenario,
  startMission,
  stopFireScenario,
} from './api'
import {
  appendTimelinePoint,
  canAnalyze,
  parseAnalysis,
  parseIncidents,
  parsePolygonHistory,
  parseStatusHistory,
  parseVerification,
  sortIncidentsForDisplay,
  zhDecision,
  zhFireStatus,
} from './fire'
import { TERMINAL_CMD_STATUSES, zhCmdStatus, zhCmdType } from './labels'
import { pushToast } from './toast'
import type {
  CommandType,
  FireAnalysis,
  FireIncident,
  FirePolygonShape,
  GotoPayload,
  StatusPoint,
  TrackedCommand,
  UavState,
  VerificationResult,
} from './types'

const DEVICE_ID = 'UAV-001'
const STATE_POLL_MS = 1000
const COMMAND_POLL_MS = 1000
const INCIDENT_POLL_MS = 2000
const SCENARIO_WAIT_TIMEOUT_MS = 20000
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
let incidentTimer: ReturnType<typeof setInterval> | undefined

/* ---------------- 火情事件 ---------------- */

const incidents = ref<FireIncident[]>([])
const selectedIncidentId = ref<string | null>(null)
const focus = ref<{ id: string; seq: number } | null>(null)
let focusSeq = 0
const verifications = ref<Record<string, VerificationResult>>({})
const dispatched = ref<Record<string, string>>({})
const busy = ref<{ incidentId: string; action: 'verify' | 'dispatch' | 'analyze' } | null>(null)
/** 状态时间线（事件 id -> 已知状态变化序列；后端无历史字段时为会话内记录） */
const timelines = ref<Record<string, StatusPoint[]>>({})

/* 火场分析（F05/F06）：仅作用于当前选中事件 */
const polygons = ref<FirePolygonShape[]>([])
const latestAnalysis = ref<FireAnalysis | null>(null)
const analysisPulse = ref(0)
const analysisRounds = ref<Record<string, number>>({})
let polygonLoadToken = 0

const knownIncidentIds = new Set<string>()

/** 依据轮询结果维护状态时间线：后端历史优先，否则本地记录 */
function recordTimelines(list: FireIncident[]): void {
  for (const inc of list) {
    const backend = parseStatusHistory(inc.raw)
    const prev = timelines.value[inc.id]
    if (backend !== null) {
      // 后端透出历史：整段采用；若末点之后本地还记录过更晚状态则续接
      let merged = backend
      const lastLocal = prev?.[prev.length - 1]
      if (lastLocal && backend[backend.length - 1]?.status !== lastLocal.status) {
        merged = appendTimelinePoint(merged, lastLocal.status, lastLocal.at ?? Date.now(), 'local')
      }
      timelines.value[inc.id] = merged
      continue
    }
    if (!prev || prev.length === 0) {
      timelines.value[inc.id] = [{ status: inc.status, at: Date.now(), source: 'local' }]
      continue
    }
    const next = appendTimelinePoint(prev, inc.status, Date.now(), 'local')
    if (next !== prev) timelines.value[inc.id] = next
  }
}

async function pollIncidents(): Promise<void> {
  try {
    const raw = await getFireIncidents()
    const list = parseIncidents(raw)
    recordTimelines(list)
    incidents.value = sortIncidentsForDisplay(list)
    for (const inc of list) knownIncidentIds.add(inc.id)
    // 注入场景后首次发现新事件 -> 解除按钮 loading
    if (scenarioLoading.value && scenarioSnapshot !== null) {
      const snapshot = scenarioSnapshot
      if (list.some((i) => !snapshot.has(i.id))) resolveScenarioWait(true)
    }
  } catch {
    // 事件列表拉取失败静默重试（连接角标由无人机状态轮询负责）
  }
}

/* ---------------- 注入/停止演示场景 ---------------- */

type ScenarioVerdict = 'CONFIRMED' | 'FALSE_ALARM'

const scenarioActive = ref(false)
const scenarioLoading = ref(false)
/** 正在等待事件生成的场景走向（决定哪个按钮转 loading） */
const scenarioPendingVerdict = ref<ScenarioVerdict | null>(null)
let scenarioSnapshot: Set<string> | null = null
let scenarioWaitToken = 0

function resolveScenarioWait(found: boolean): void {
  scenarioLoading.value = false
  scenarioPendingVerdict.value = null
  scenarioSnapshot = null
  scenarioWaitToken++
  if (found) {
    pushToast('success', '火情事件已生成，无人机前往核查')
  }
}

async function onStartScenario(verdict: ScenarioVerdict): Promise<void> {
  if (scenarioLoading.value || scenarioActive.value) return
  try {
    await startFireScenario({ verdict })
  } catch (e) {
    pushToast(
      'error',
      `注入${verdict === 'FALSE_ALARM' ? '误报演示' : '火情'}场景失败：${e instanceof Error ? e.message : '未知错误'}`,
    )
    return
  }
  scenarioActive.value = true
  pushToast(
    'info',
    verdict === 'FALSE_ALARM' ? '已注入误报演示场景，无人机前往核查' : '已注入火情场景，无人机前往核查',
  )
  // loading 直到事件列表出现新事件，或超时兜底
  scenarioLoading.value = true
  scenarioPendingVerdict.value = verdict
  scenarioSnapshot = new Set(knownIncidentIds)
  const token = ++scenarioWaitToken
  window.setTimeout(() => {
    if (scenarioLoading.value && token === scenarioWaitToken) {
      resolveScenarioWait(false)
      pushToast('info', '暂未在事件列表中看到新事件，可稍后继续观察')
    }
  }, SCENARIO_WAIT_TIMEOUT_MS)
  void pollIncidents()
}

async function onStopScenario(): Promise<void> {
  if (!scenarioActive.value) return
  try {
    await stopFireScenario()
  } catch (e) {
    pushToast('error', `停止场景失败：${e instanceof Error ? e.message : '未知错误'}`)
    return
  }
  scenarioActive.value = false
  pushToast('info', '演示场景已停止')
}

/* ---------------- 事件卡片动作 ---------------- */

/** 拉取选中事件的历史多边形（扩散年轮数据源）；后端未就绪时静默置空 */
async function loadPolygons(id: string): Promise<void> {
  const token = ++polygonLoadToken
  try {
    const raw = await getIncidentPolygons(id)
    if (token !== polygonLoadToken) return
    polygons.value = parsePolygonHistory(raw)
  } catch {
    if (token !== polygonLoadToken) return
    polygons.value = []
  }
}

/** 拉取最新趋势，作为趋势面板种子（后端未就绪时静默跳过） */
async function refreshTracking(id: string): Promise<void> {
  try {
    const raw = await getIncidentTracking(id)
    const parsed = parseAnalysis(raw)
    if (parsed.tracking === null && parsed.growthStep === null) return
    latestAnalysis.value = {
      polygon: parsed.polygon ?? latestAnalysis.value?.polygon ?? null,
      tracking: parsed.tracking ?? latestAnalysis.value?.tracking ?? null,
      growthStep: parsed.growthStep ?? latestAnalysis.value?.growthStep ?? null,
      at: Date.now(),
    }
  } catch {
    // 趋势接口失败不影响卡片其余功能
  }
}

function onSelectFromMap(id: string): void {
  selectedIncidentId.value = id
  void loadPolygons(id)
  void refreshTracking(id)
}

function onSelectFromList(id: string): void {
  selectedIncidentId.value = id
  focus.value = { id, seq: ++focusSeq }
  void loadPolygons(id)
  void refreshTracking(id)
}

function onCloseCard(): void {
  selectedIncidentId.value = null
  // 切换/关闭事件时清除旧多边形与趋势
  polygonLoadToken++
  polygons.value = []
  latestAnalysis.value = null
}

async function onVerify(id: string): Promise<void> {
  if (busy.value) return
  busy.value = { incidentId: id, action: 'verify' }
  try {
    const raw = await postIncidentVerification(id)
    const v = parseVerification(raw)
    verifications.value = { ...verifications.value, [id]: v }
    const conf = v.confidence !== null ? `（置信度 ${v.confidence}%）` : ''
    if (v.decision === 'FALSE_ALARM') {
      pushToast('success', `已排除误报，无需出动${conf}`)
    } else if (v.decision === 'CONFIRMED' || v.decision === 'FIRE_CONFIRMED' || v.decision === 'TRUE_ALARM') {
      pushToast('success', `已确认火情，保持跟踪处置${conf}`)
    } else if (v.decision) {
      pushToast('success', `AI 核验完成：${zhDecision(v.decision)}${conf}`)
    } else {
      pushToast('info', '核验已触发，后端未返回明确结论')
    }
    void pollIncidents()
  } catch (e) {
    pushToast('error', `触发核验失败：${e instanceof Error ? e.message : '未知错误'}`)
  } finally {
    busy.value = null
  }
}

async function onDispatch(id: string): Promise<void> {
  if (busy.value) return
  const inc = incidents.value.find((i) => i.id === id)
  if (!inc || inc.latitude === null || inc.longitude === null) {
    pushToast('error', '该事件缺少火点坐标，无法派单')
    return
  }
  busy.value = { incidentId: id, action: 'dispatch' }
  try {
    const mission = await createMission({
      missionType: 'FIRE_VERIFICATION',
      incidentId: id,
      target: {
        latitude: inc.latitude,
        longitude: inc.longitude,
        altitude: 120,
        altitudeMode: 'RELATIVE_TO_TAKEOFF',
      },
      priority: 90,
      requiredCapabilities: ['RGB', 'THERMAL'],
    })
    const mid = missionIdOf(mission)
    if (mid === null) throw new Error('后端未返回任务 ID')
    await startMission(mid)
    dispatched.value = { ...dispatched.value, [id]: mid }
    pushToast('success', `核验任务 ${mid} 已派单并启动，无人机前往核查`)
    void pollIncidents()
  } catch (e) {
    pushToast('error', `派单核验失败：${e instanceof Error ? e.message : '未知错误'}`)
  } finally {
    busy.value = null
  }
}

async function onAnalyze(id: string): Promise<void> {
  if (busy.value) return
  const inc = incidents.value.find((i) => i.id === id)
  if (!inc) return
  if (!canAnalyze(inc.status)) {
    pushToast('info', '该事件尚未确认，确认后可进行火场分析')
    return
  }
  busy.value = { incidentId: id, action: 'analyze' }
  try {
    const raw = await postIncidentAnalysis(id)
    const a = parseAnalysis(raw)
    const prevRound = analysisRounds.value[id] ?? 0
    if (a.growthStep === null) a.growthStep = prevRound + 1
    analysisRounds.value = { ...analysisRounds.value, [id]: a.growthStep }
    if (a.polygon) polygons.value = [...polygons.value, a.polygon]
    latestAnalysis.value = a
    analysisPulse.value++ // 触发最新一代多边形扩散动画
    const areaText =
      a.polygon?.areaSquareMeters != null
        ? `，面积约 ${Math.round(a.polygon.areaSquareMeters).toLocaleString('zh-CN')} m²`
        : ''
    pushToast('success', `火场分析完成：第 ${a.growthStep} 轮${areaText}`)
  } catch (e) {
    pushToast('error', `火场分析失败：${e instanceof Error ? e.message : '未知错误'}`)
  } finally {
    busy.value = null
  }
}

async function onStatusChange(id: string, status: string): Promise<void> {
  try {
    await patchIncidentStatus(id, status)
    pushToast('success', `事件状态已流转：${zhFireStatus(status)}`)
    void pollIncidents()
  } catch (e) {
    pushToast('error', `状态流转失败：${e instanceof Error ? e.message : '未知错误'}`)
  }
}

/* ---------------- 无人机监控（原有） ---------------- */

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
  void pollIncidents()
  stateTimer = setInterval(() => {
    void pollState()
  }, STATE_POLL_MS)
  incidentTimer = setInterval(() => {
    void pollIncidents()
  }, INCIDENT_POLL_MS)
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 500)
})

onUnmounted(() => {
  if (stateTimer !== undefined) clearInterval(stateTimer)
  if (clockTimer !== undefined) clearInterval(clockTimer)
  if (incidentTimer !== undefined) clearInterval(incidentTimer)
  for (const id of [...pollTimers.keys()]) stopPolling(id)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark"></span>
        森林防火无人机智能系统 <small>Demo · Vue 3 + Leaflet</small>
      </div>
      <div class="topbar-right">
        <template v-if="!scenarioActive">
          <button
            class="fire-btn"
            :class="{ loading: scenarioLoading }"
            :disabled="scenarioLoading"
            @click="onStartScenario('CONFIRMED')"
          >
            <span v-if="scenarioLoading && scenarioPendingVerdict === 'CONFIRMED'" class="spinner"></span>
            <template v-if="scenarioLoading && scenarioPendingVerdict === 'CONFIRMED'">等待火情事件生成…</template>
            <template v-else>🔥 火情场景</template>
          </button>
          <button
            class="fire-btn warn"
            :class="{ loading: scenarioLoading }"
            :disabled="scenarioLoading"
            @click="onStartScenario('FALSE_ALARM')"
          >
            <span v-if="scenarioLoading && scenarioPendingVerdict === 'FALSE_ALARM'" class="spinner"></span>
            <template v-if="scenarioLoading && scenarioPendingVerdict === 'FALSE_ALARM'">等待误报事件生成…</template>
            <template v-else>⚠️ 误报场景</template>
          </button>
        </template>
        <button v-else class="fire-btn stop" :disabled="scenarioLoading" @click="onStopScenario">
          <span v-if="scenarioLoading" class="spinner"></span>
          ■ 停止场景
        </button>
        <div class="conn" :class="connectionLost ? 'bad' : 'ok'">
          <span class="dot"></span>
          {{ connectionLost ? '后端连接断开' : '后端已连接' }}
        </div>
      </div>
    </header>
    <main class="content">
      <MapView
        :state="state"
        :incidents="incidents"
        :now="now"
        :selected-id="selectedIncidentId"
        :focus="focus"
        :verifications="verifications"
        :dispatched="dispatched"
        :busy="busy"
        :timelines="timelines"
        :polygons="polygons"
        :analysis="latestAnalysis"
        :analysis-pulse="analysisPulse"
        :analysis-rounds="analysisRounds"
        @goto="onGoto"
        @select-incident="onSelectFromMap"
        @verify="onVerify"
        @dispatch="onDispatch"
        @analyze="onAnalyze"
        @status-change="onStatusChange"
        @close-card="onCloseCard"
      />
      <InfoPanel
        :state="state"
        :connection-lost="connectionLost"
        :last-update="lastUpdateAt"
        :now="now"
        :commands="tracked"
        :incidents="incidents"
        :selected-incident-id="selectedIncidentId"
        @quick="onQuick"
        @select-incident="onSelectFromList"
      />
    </main>
    <Toasts />
  </div>
</template>
