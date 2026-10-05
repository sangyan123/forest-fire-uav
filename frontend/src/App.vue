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
  getSimulatorStatus,
  getUavState,
  missionIdOf,
  patchIncidentStatus,
  postCommand,
  postIncidentAnalysis,
  postIncidentVerification,
  startMission,
  startScenario,
} from './api'
import {
  appendTimelinePoint,
  canAnalyze,
  parseAnalysis,
  parseIncidents,
  parsePolygonHistory,
  parseSimulatorStatus,
  parseStatusHistory,
  parseVerification,
  sortIncidentsForDisplay,
  zhDecision,
  zhFireStatus,
} from './fire'
import { TERMINAL_CMD_STATUSES, zhCmdStatus, zhCmdType } from './labels'
import { pushToast } from './toast'
import type {
  CommandRecord,
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
const SIMULATOR_POLL_MS = 2000
// 转场飞行约60~75秒（900m@15m/s）+采集/检测/入库，事件生成最长可达2分钟
const SCENARIO_WAIT_TIMEOUT_MS = 120000
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
let simulatorTimer: ReturnType<typeof setInterval> | undefined

/* ---------------- 火情事件 ---------------- */

const incidents = ref<FireIncident[]>([])
const selectedIncidentId = ref<string | null>(null)
const focus = ref<{ id: string; seq: number } | null>(null)
let focusSeq = 0
const verifications = ref<Record<string, VerificationResult>>({})
const dispatched = ref<Record<string, string>>({})
const busy = ref<{ incidentId: string; action: 'verify' | 'dispatch' | 'analyze' | 'bomb' } | null>(null)
/** 状态时间线（事件 id -> 已知状态变化序列；后端无历史字段时为会话内记录） */
const timelines = ref<Record<string, StatusPoint[]>>({})

/* 投放灭火弹（DROP_EXTINGUISHING_BALL）：弹药余量来自模拟器 2s 轮询；投弹成功脉冲驱动地图特效 */
const ballsRemaining = ref<number | null>(null)
const bombBlast = ref<{ incidentId: string; seq: number } | null>(null)
let bombSeq = 0

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
      // 后端透出历史：整段采用；仅当本地曾记录过"超出后端末点"的状态时才续接
      let merged = backend
      const lastPrev = prev?.[prev.length - 1]
      if (lastPrev?.source === 'local' && backend[backend.length - 1]?.status !== lastPrev.status) {
        merged = appendTimelinePoint(merged, lastPrev.status, lastPrev.at ?? Date.now(), 'local')
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
    // 启动场景后首次发现"自己的"新事件 -> 解除按钮 loading（按 scenarioType 匹配）
    if (scenarioPending.value !== null && scenarioSnapshot !== null) {
      const snapshot = scenarioSnapshot
      const candidates = list.filter((i) => !snapshot.has(i.id))
      const meta = SCENARIOS.find((s) => s.id === scenarioPending.value)
      const matched =
        candidates.find(
          (i) => meta?.waitType == null || i.scenarioType === null || i.scenarioType === meta.waitType,
        ) ?? candidates[0]
      if (matched) resolveScenarioWait(true)
    }
  } catch {
    // 事件列表拉取失败静默重试（连接角标由无人机状态轮询负责）
  }
}

/* ---------------- 场景注册表（scenario-01/02/04） ---------------- */

interface ScenarioMeta {
  id: string
  label: string
  /** 附加配色类 */
  css: string
  /** 期望的新事件 scenarioType；null=无需等待新事件 */
  waitType: 'CONFIRMED' | 'FALSE_ALARM' | null
  /** 按钮 loading 中的文案 */
  pendingLabel: string
  startToast: string
  foundToast: string
}

const SCENARIOS: ScenarioMeta[] = [
  {
    id: 'scenario-01',
    label: '▶ Scenario-01 正常巡检',
    css: 'patrol',
    waitType: null,
    pendingLabel: '巡检启动中…',
    startToast: '已恢复正常巡检',
    foundToast: '',
  },
  {
    id: 'scenario-02',
    label: '🔥 Scenario-02 火情发现',
    css: '',
    waitType: 'CONFIRMED',
    pendingLabel: '等待事件生成…',
    startToast: '已注入火情场景，无人机前往核查',
    foundToast: '火情事件已生成，无人机前往核查',
  },
  {
    id: 'scenario-03',
    label: '🔬 Scenario-03 二次核验',
    css: '',
    waitType: null,
    pendingLabel: '核验演示进行中…',
    startToast: '二次核验演示开始（无疑似事件时自动注入火情）',
    foundToast: '',
  },
  {
    id: 'scenario-04',
    label: '⚠️ Scenario-04 误报',
    css: 'warn',
    waitType: 'FALSE_ALARM',
    pendingLabel: '等待事件生成…',
    startToast: '已注入误报演示场景，无人机前往核查',
    foundToast: '误报事件已生成，无人机前往核查',
  },
  {
    id: 'scenario-05',
    label: '📈 Scenario-05 火势扩大',
    css: 'expand',
    waitType: null,
    pendingLabel: '演示进行中…',
    startToast: '火势扩大演示开始',
    foundToast: '',
  },
  {
    id: 'scenario-06',
    label: '📡 Scenario-06 UAV断联',
    css: 'linkloss',
    waitType: null,
    pendingLabel: '断联演示中…',
    startToast: '断联演示开始，等待无人机进入盲区',
    foundToast: '',
  },
]

/** 当前激活场景：点击后本地置位，随后由 GET /simulator/status 2s 轮询校正 */
const currentScenarioId = ref<string | null>(null)
/** 正在等待新事件的场景（仅 scenario-02/04），决定哪个按钮转 loading */
const scenarioPending = ref<string | null>(null)
let scenarioSnapshot: Set<string> | null = null
let scenarioWaitToken = 0

function resolveScenarioWait(found: boolean): void {
  const meta = SCENARIOS.find((s) => s.id === scenarioPending.value)
  scenarioPending.value = null
  scenarioSnapshot = null
  scenarioWaitToken++
  if (found) {
    pushToast('success', meta?.foundToast ?? '场景事件已生成，无人机前往核查')
  }
}

async function onStartScenario(id: string): Promise<void> {
  // Scenario-01 在等待期间也可点击：取消当前等待并停止场景，恢复巡检
  if (scenarioPending.value !== null && id !== 'scenario-01') return
  const meta = SCENARIOS.find((s) => s.id === id)
  if (!meta) return
  if (id === 'scenario-01') {
    if (scenarioPending.value !== null) resolveScenarioWait(false) // 取消等待
    try {
      await startScenario(id)
    } catch (e) {
      pushToast('error', `停止场景失败：${e instanceof Error ? e.message : '未知错误'}`)
      return
    }
    currentScenarioId.value = id
    pushToast('info', meta.startToast)
    return
  }
  // Scenario-05 是前端编排（无 mock 场景端点）：自动注入火情→等确认→两轮分析
  if (id === 'scenario-05') {
    currentScenarioId.value = id
    pushToast('info', meta.startToast)
    await runFireSpreadDemo()
    return
  }
  // Scenario-03 是前端编排：自动注入火情（如无疑似事件）→ 自动触发核验
  if (id === 'scenario-03') {
    currentScenarioId.value = id
    pushToast('info', meta.startToast)
    await runVerificationDemo()
    return
  }
  try {
    const started = await startScenario(id)
    currentScenarioId.value = id // 本地置位，高亮即时反馈；轮询随后校正
    const dst = started?.fireScenario
    // 无人机正在飞往的起火点坐标（模拟器按预设点位轮换返回，见 mock-uav FIRE_PRESET_POINTS）
    if (dst?.active && dst.latitude != null && dst.longitude != null) {
      pushToast('info', `${meta.startToast}，飞往 (${dst.latitude.toFixed(4)}, ${dst.longitude.toFixed(4)})`)
    } else {
      pushToast('info', meta.startToast)
    }
  } catch (e) {
    pushToast('error', `启动场景失败：${e instanceof Error ? e.message : '未知错误'}`)
    return
  }
  if (id === 'scenario-06') {
    await runCommsLossDemo()
    return
  }
  if (meta.waitType === null) return // scenario-01 无需等待新事件
  scenarioPending.value = id
  // 先等首次轮询把现有事件入册，再做快照——否则空快照会把旧事件误判为"新事件"，
  // 等待状态被瞬间清掉（D7 实测：页面刚加载时点场景按钮必现）
  await pollIncidents()
  scenarioSnapshot = new Set(knownIncidentIds)
  const token = ++scenarioWaitToken
  window.setTimeout(() => {
    if (scenarioPending.value === id && token === scenarioWaitToken) {
      resolveScenarioWait(false)
      pushToast('info', '暂未生成新事件（转场飞行约需1分钟），可继续等待或稍后观察列表')
    }
  }, SCENARIO_WAIT_TIMEOUT_MS)
  void pollIncidents()
}

/**
 * Scenario-03 二次核验编排：确保存在疑似/核验中事件（无则自动注入火情），
 * 自动触发核验并展示结论。
 */
async function runVerificationDemo(): Promise<void> {
  scenarioPending.value = 'scenario-03'
  const token = ++scenarioWaitToken
  const cancelled = () => scenarioPending.value !== 'scenario-03' || token !== scenarioWaitToken
  try {
    let incident = incidents.value.find((i) => i.status === 'SUSPECTED' || i.status === 'VERIFYING')
    if (!incident) {
      try {
        await startScenario('scenario-02')
      } catch (e) {
        pushToast('error', `火情注入失败：${e instanceof Error ? e.message : '未知错误'}`)
        return
      }
      const deadline = Date.now() + 120000
      while (Date.now() < deadline && !cancelled()) {
        await pollIncidents()
        incident = incidents.value.find((i) => i.status === 'SUSPECTED' || i.status === 'VERIFYING')
        if (incident) break
        await new Promise((r) => setTimeout(r, 3000))
      }
      if (!incident) {
        if (!cancelled()) pushToast('info', '未在时限内等到疑似事件')
        return
      }
    }
    if (cancelled()) return
    onSelectFromList(incident.id) // 打开事件卡，画面聚焦
    await onVerify(incident.id)   // 自动触发核验（内部有结论 toast）
  } finally {
    if (scenarioPending.value === 'scenario-03' && token === scenarioWaitToken) {
      scenarioPending.value = null
      scenarioSnapshot = null
    }
  }
}

/**
 * Scenario-05 火势扩大：编排一场"自动两轮火场分析"。
 * 无 CONFIRMED/TRACKING 事件则先自动走 scenario-02 流程（等待事件+核验确认），
 * 然后间隔 3 秒连续两次 analysis，让扩散年轮与趋势面板自动上演。
 */
/** Scenario-05 上一次分析的事件：连续点击在同一事件上叠加轮次（半径持续扩大） */
const lastSpreadIncidentId = ref<string | null>(null)

async function runFireSpreadDemo(): Promise<void> {
  scenarioPending.value = 'scenario-05'
  const token = ++scenarioWaitToken
  const cancelled = () => scenarioPending.value !== 'scenario-05' || token !== scenarioWaitToken
  try {
    const active = (i: FireIncident) => i.status === 'CONFIRMED' || i.status === 'TRACKING'
    // 优先延续上一次分析的事件（其年轮/轮次继续增长）；否则选最新确认事件
    let incident: FireIncident | undefined =
      incidents.value.find((i) => i.id === lastSpreadIncidentId.value && active(i)) ??
      incidents.value.find(active)
    if (!incident) {
      pushToast('info', '先注入火情场景，等待事件确认…')
      try {
        await startScenario('scenario-02')
      } catch (e) {
        pushToast('error', `火情注入失败：${e instanceof Error ? e.message : '未知错误'}`)
        return
      }
      incident = await waitForVerifiedIncident(120000, cancelled)
      if (!incident) {
        if (!cancelled()) pushToast('info', '未在时限内等到确认事件，请先注入火情再试')
        return
      }
    }
    if (cancelled()) return
    lastSpreadIncidentId.value = incident.id
    // 提前选中：飞向火点、开卡，分析轮次在其上叠加
    onSelectFromMap(incident.id)
    const r1 = (await postIncidentAnalysis(incident.id)) as {
      growthStep?: number
      polygon?: { radiusMeters?: number }
    } | undefined
    if (cancelled()) return
    // 后端 growthStep 已是 1 基（本轮序号），无需 +1 补偿（D8 修复：与手动路径口径统一）
    pushToast('info', `火势扩大演示：第 ${r1?.growthStep ?? 1} 轮分析（半径 ${Math.round(r1?.polygon?.radiusMeters ?? 0)} 米）`)
    void loadPolygons(incident.id)
    void refreshTracking(incident.id)
    window.setTimeout(() => {
      if (cancelled()) return
      void (async () => {
        try {
          const r2 = (await postIncidentAnalysis(incident.id)) as {
            growthStep?: number
            polygon?: { radiusMeters?: number }
          } | undefined
          pushToast('info', `火势扩大演示：第 ${r2?.growthStep ?? 2} 轮分析（半径 ${Math.round(r2?.polygon?.radiusMeters ?? 0)} 米）`)
          void loadPolygons(incident.id)
          void refreshTracking(incident.id)
        } catch (e) {
          pushToast('error', `分析失败：${e instanceof Error ? e.message : '未知错误'}`)
        }
      })()
    }, 3000)
  } finally {
    if (scenarioPending.value === 'scenario-05' && token === scenarioWaitToken) {
      scenarioPending.value = null
      scenarioSnapshot = null
    }
  }
}

/** 等待出现 CONFIRMED/TRACKING 事件（轮询列表；取消回调命中返回 undefined） */
async function waitForVerifiedIncident(timeoutMs: number, cancelled: () => boolean): Promise<FireIncident | undefined> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline && !cancelled()) {
    await pollIncidents()
    const found = incidents.value.find((i) => i.status === 'CONFIRMED' || i.status === 'TRACKING')
    if (found) return found
    await new Promise((r) => setTimeout(r, 3000))
  }
  return undefined
}

/**
 * Scenario-06 UAV断联：监听 1Hz 状态轮询，
 * 状态变 OFFLINE → toast 警告；恢复 AIRBORNE → toast 通信恢复。最长 40s 兜底。
 */
async function runCommsLossDemo(): Promise<void> {
  scenarioPending.value = 'scenario-06'
  const token = ++scenarioWaitToken
  const cancelled = () => scenarioPending.value !== 'scenario-06' || token !== scenarioWaitToken
  const deadline = Date.now() + 40000
  let announcedLoss = false
  let announcedRecovery = false
  while (Date.now() < deadline && !cancelled()) {
    try {
      const state = await getUavState('UAV-001')
      const status = String((state as unknown as { status?: string })?.status ?? '')
      if (status === 'OFFLINE' && !announcedLoss) {
        announcedLoss = true
        pushToast('warning', '⚠️ 无人机进入盲区，连接丢失——等待通信恢复')
      }
      if (announcedLoss && status !== 'OFFLINE' && !announcedRecovery) {
        announcedRecovery = true
        pushToast('success', '✅ 通信恢复，遥测数据续传')
      }
      if (announcedRecovery) break
    } catch {
      // 单次失败静默重试
    }
    await new Promise((r) => setTimeout(r, 1000))
  }
  if (scenarioPending.value === 'scenario-06' && token === scenarioWaitToken) {
    scenarioPending.value = null
    scenarioSnapshot = null
    if (!announcedLoss) pushToast('info', '断联演示结束，未观测到离线状态（可重试）')
  }
}

/** 2s 轮询模拟器状态，用 currentScenarioId 校正高亮；等待期间以本地置位为准 */
async function pollSimulatorStatus(): Promise<void> {
  try {
    const raw = await getSimulatorStatus()
    ballsRemaining.value = parseBallRemaining(raw, ballsRemaining.value)
    if (scenarioPending.value !== null) return
    currentScenarioId.value = parseSimulatorStatus(raw)
  } catch {
    // 模拟器未就绪时保留本地状态
  }
}

/** 宽松解析模拟器状态里的灭火弹余量（payload.extinguishingBall.remaining） */
function parseBallRemaining(raw: unknown, fallback: number | null): number | null {
  if (typeof raw !== 'object' || raw === null) return fallback
  const payload = (raw as Record<string, unknown>).payload
  if (typeof payload !== 'object' || payload === null) return fallback
  const ball = (payload as Record<string, unknown>).extinguishingBall
  if (typeof ball !== 'object' || ball === null) return fallback
  const n = Number((ball as Record<string, unknown>).remaining)
  return Number.isFinite(n) ? n : fallback
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

/* ---------------- 投放灭火弹（DROP_EXTINGUISHING_BALL） ---------------- */

/** 命令记录里宽松取 result.deviceResponse（mock-uav 投弹回执所在） */
function bombResultOf(rec: CommandRecord): Record<string, unknown> {
  const raw = rec as Record<string, unknown>
  const result = raw.result
  if (typeof result !== 'object' || result === null) return {}
  const r = result as Record<string, unknown>
  const dr = r.deviceResponse
  return typeof dr === 'object' && dr !== null ? (dr as Record<string, unknown>) : r
}

/** 投弹失败原因：优先设备回执 message，其次后端 errorMessage */
function bombFailMessage(rec: CommandRecord): string {
  const raw = rec as Record<string, unknown>
  const candidates: unknown[] = [bombResultOf(rec).message, raw.errorMessage, raw.message]
  for (const c of candidates) {
    if (typeof c === 'string' && c.trim()) return c
  }
  return '无人机未接受投放指令'
}

async function onDropBomb(id: string): Promise<void> {
  if (busy.value) return
  const inc = incidents.value.find((i) => i.id === id)
  if (!inc || inc.latitude === null || inc.longitude === null) {
    pushToast('error', '该事件缺少火点坐标，无法投放')
    return
  }
  busy.value = { incidentId: id, action: 'bomb' }
  try {
    const rec = await postCommand(DEVICE_ID, {
      commandType: 'DROP_EXTINGUISHING_BALL',
      params: { latitude: inc.latitude, longitude: inc.longitude, incidentId: id },
    })
    const cid = rec.commandId ?? rec.id
    if (cid === undefined || cid === null || String(cid) === '') {
      pushToast('error', '指令下发成功但后端未返回命令 ID，无法跟踪投放结果')
      return
    }
    pushToast('info', '投放指令已下发，等待无人机执行投放')
    // silent：成功/失败提示由 onTerminal 按设备回执定制（含安全校验拒绝原因）
    track(String(cid), `投放灭火弹 → ${inc.incidentNo}`, String(rec.status ?? 'CREATED').toUpperCase(), {
      silent: true,
      onTerminal: (final) => onBombTerminal(id, final),
    })
  } catch (e) {
    pushToast('error', `投放失败：${e instanceof Error ? e.message : '未知错误'}`)
  } finally {
    busy.value = null
  }
}

/** 投弹命令终态：成功→爆炸特效+事件状态推进（首发 PROCESSING / 打完 RESOLVED）；失败→设备回执原因 */
async function onBombTerminal(id: string, rec: CommandRecord): Promise<void> {
  void pollIncidents()
  const status = String(rec.status ?? '').toUpperCase()
  if (status !== 'SUCCESS' && status !== 'SUCCEEDED') {
    pushToast('error', `投放未执行：${bombFailMessage(rec)}`)
    return
  }
  bombBlast.value = { incidentId: id, seq: ++bombSeq }
  // 余量以模拟器为准（网关命令回执只透传 status/message，设备自定义字段不落地）
  const remaining = await refreshBallsRemaining()
  const inc = incidents.value.find((i) => i.id === id)
  const needProcessing = !!inc && (inc.status === 'CONFIRMED' || inc.status === 'TRACKING')
  const exhausted = remaining === 0
  if (!needProcessing && !exhausted) {
    pushToast('success', `💥 灭火弹命中目标${remaining !== null ? `（剩余 ${remaining} 发）` : ''}`)
    return
  }
  try {
    // 旁路迁移见 enums.yaml#IncidentStatus.transitions.bypass：CONFIRMED/TRACKING → PROCESSING
    if (needProcessing) {
      await patchIncidentStatus(id, 'PROCESSING')
      pushToast('success', `💥 灭火弹命中，事件进入处置中${remaining !== null ? `（剩余 ${remaining} 发）` : ''}`)
    }
    if (exhausted) {
      await patchIncidentStatus(id, 'RESOLVED')
      pushToast('success', '🔥 灭火弹打完，火势已扑灭：事件标记为已处置，无人机自动归队恢复巡检')
    }
  } catch (e) {
    pushToast('error', `状态流转失败：${e instanceof Error ? e.message : '未知错误'}`)
  }
  void pollIncidents()
}

/** 主动刷新一次灭火弹余量（模拟器 /simulator/status），返回当前余量 */
async function refreshBallsRemaining(): Promise<number | null> {
  try {
    const raw = await getSimulatorStatus()
    ballsRemaining.value = parseBallRemaining(raw, ballsRemaining.value)
  } catch {
    // 拉取失败时保留上次值，2s 轮询会继续校正
  }
  return ballsRemaining.value
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

interface TrackOptions {
  /** 终态时不发默认成功/失败 toast（由 onTerminal 自定义提示） */
  silent?: boolean
  /** 命令到达终态时回调（拿到含 result 的完整记录与终态状态） */
  onTerminal?: (rec: CommandRecord, status: string) => void
}

function startPolling(item: TrackedCommand, opts?: TrackOptions): void {
  stopPolling(item.commandId)
  const timer = setInterval(async () => {
    try {
      const rec = await getCommand(item.commandId)
      const st = String(rec.status ?? '').trim()
      if (st) item.status = st.toUpperCase()
      if (TERMINAL_CMD_STATUSES.has(item.status)) {
        item.terminal = true
        stopPolling(item.commandId)
        if (opts?.onTerminal) {
          opts.onTerminal(rec, item.status)
        } else if (!opts?.silent) {
          if (item.status === 'SUCCESS' || item.status === 'SUCCEEDED') {
            pushToast('success', `指令执行成功：${item.label}`)
          } else {
            pushToast('error', `指令执行失败：${item.label}（${zhCmdStatus(item.status)}）`)
          }
        }
      }
    } catch {
      // 轮询失败时保持当前状态继续轮询；连接断开由状态轮询角标提示
    }
  }, COMMAND_POLL_MS)
  pollTimers.set(item.commandId, timer)
}

function track(commandId: string, label: string, initialStatus: string, opts?: TrackOptions): void {
  const item: TrackedCommand = {
    key: ++trackedSeq,
    commandId,
    label,
    status: initialStatus,
    createdAt: Date.now(),
    terminal: TERMINAL_CMD_STATUSES.has(initialStatus),
  }
  tracked.value = [item, ...tracked.value].slice(0, MAX_TRACKED)
  if (!item.terminal) {
    startPolling(item, opts)
  } else {
    // 下发响应即终态（罕见）：补一次回调，传最小记录
    opts?.onTerminal?.({ commandId, status: initialStatus }, item.status)
    if (!opts?.onTerminal && !opts?.silent) {
      if (item.status === 'SUCCESS' || item.status === 'SUCCEEDED') {
        pushToast('success', `指令执行成功：${label}`)
      } else {
        pushToast('error', `指令执行失败：${label}（${zhCmdStatus(item.status)}）`)
      }
    }
  }
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
  simulatorTimer = setInterval(() => {
    void pollSimulatorStatus()
  }, SIMULATOR_POLL_MS)
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 500)
})

onUnmounted(() => {
  if (stateTimer !== undefined) clearInterval(stateTimer)
  if (clockTimer !== undefined) clearInterval(clockTimer)
  if (incidentTimer !== undefined) clearInterval(incidentTimer)
  if (simulatorTimer !== undefined) clearInterval(simulatorTimer)
  for (const id of [...pollTimers.keys()]) stopPolling(id)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark"></span>
        森林保护无人机智能系统
      </div>
      <div class="topbar-right">
        <button
          v-for="s in SCENARIOS"
          :key="s.id"
          class="fire-btn"
          :class="[s.css, { active: currentScenarioId === s.id }]"
          :disabled="scenarioPending !== null && s.id !== 'scenario-01'"
          @click="onStartScenario(s.id)"
        >
          <span v-if="scenarioPending === s.id" class="spinner"></span>
          <template v-if="scenarioPending === s.id">等待事件生成…（点 01 取消）</template>
          <template v-else>{{ s.label }}</template>
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
        :balls-remaining="ballsRemaining"
        :bomb-blast="bombBlast"
        @goto="onGoto"
        @select-incident="onSelectFromMap"
        @verify="onVerify"
        @dispatch="onDispatch"
        @analyze="onAnalyze"
        @drop-bomb="onDropBomb"
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
