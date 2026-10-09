<script setup lang="ts">
import * as L from 'leaflet'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  getFireIncidents,
  getLatestPredictions,
  getPatrolSuggestions,
  getRiskAreaDetail,
  getRiskAreas,
  postPatrolSuggestionDismiss,
  postPatrolSuggestionDispatch,
  postPatrolSuggestionsGenerate,
  postPredictionRun,
  postRiskAssess,
  type FirePrediction,
  type PatrolSuggestion,
  type RiskArea,
  type RiskAreaDetail,
} from '../api'
import { parseIncidents } from '../fire'
import { pushToast } from '../toast'

/** 切回森林防火页查看下发的任务 */
const emit = defineEmits<{ (e: 'jump-fire'): void }>()

/* 轮询间隔（constants.yaml#patrol_suggestion#poll_intervals_ms；事件徽标 30s） */
const AREAS_POLL_MS = 60000
const SUGGESTION_POLL_MS = 30000
const PREDICTION_POLL_MS = 120000
const INCIDENT_POLL_MS = 30000

/** 演示区中心（与网格 5×5 对齐：30.12~30.165 / 114.12~114.172） */
const DEFAULT_CENTER: L.LatLngExpression = [30.142, 114.146]
const DEFAULT_ZOOM = 13
/** 视野钳制在本地瓦片覆盖区（与 MapView 同规则，z13 边界最宽） */
const MAP_MAX_BOUNDS = L.latLngBounds([29.85, 113.85], [30.45, 114.45])
/** 1x1 透明 png：缺失瓦片不显示破图 */
const TRANSPARENT_TILE =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='

const LEVEL_COLOR: Record<string, string> = {
  HIGH: '#ef4444',
  MEDIUM: '#f59e0b',
  LOW: '#22c55e',
}
const ZH_LEVEL: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const ZH_STATUS: Record<string, string> = {
  SUGGESTED: '待处理',
  DISPATCHED: '已下发',
  DISMISSED: '已驳回',
  EXPIRED: '已过期',
}
const ZH_FACTOR: Record<string, string> = {
  HISTORICAL: '历史火点',
  WEATHER: '气象',
  VEGETATION: '植被',
  TERRAIN: '地形',
  HUMAN_ACTIVITY: '人类活动',
}

/* ---------------- 数据 ---------------- */

const areas = ref<RiskArea[]>([])
const suggestions = ref<PatrolSuggestion[]>([])
const predictions = ref<FirePrediction[]>([])
const weather = ref<Record<string, unknown> | null>(null)
const incidents = ref<ReturnType<typeof parseIncidents>>([])
const detail = ref<RiskAreaDetail | null>(null)
const selectedAreaId = ref<string | null>(null)
const showPredictionLayer = ref(true)
const assessing = ref(false)
const generating = ref(false)
const rerunning = ref(false)
/** 下发确认弹窗目标 */
const confirmTarget = ref<PatrolSuggestion | null>(null)
const dispatching = ref(false)

const activeIncidents = computed(() =>
  incidents.value.filter((i) => !['FALSE_ALARM', 'RESOLVED', 'CLOSED'].includes(i.status)),
)
const pendingSuggestions = computed(() =>
  suggestions.value.filter((s) => s.status === 'SUGGESTED'),
)
const fireState = computed(() => activeIncidents.value.length > 0)

/* ---------------- 地图（独立实例：v-if 挂载初始化 / 卸载 destroy） ---------------- */

const mapEl = ref<HTMLDivElement | null>(null)
let map: L.Map | null = null
let riskLayer: L.LayerGroup | null = null
let predictionLayer: L.LayerGroup | null = null
let routeLayer: L.LayerGroup | null = null
const areaShapes = new Map<string, L.Polygon>()
const selectedSuggestionId = ref<string | null>(null)

function levelColor(level: string | null): string {
  return LEVEL_COLOR[level ?? ''] ?? '#94a3b8'
}

function redrawRiskLayer(): void {
  if (!riskLayer) return
  riskLayer.clearLayers()
  areaShapes.clear()
  for (const area of areas.value) {
    if (!area.geometry || area.geometry.length < 3) continue
    const color = levelColor(area.riskLevel)
    const poly = L.polygon(area.geometry as L.LatLngExpression[], {
      color,
      weight: area.areaId === selectedAreaId.value ? 3 : 1,
      opacity: 0.9,
      fillColor: color,
      fillOpacity: 0.32,
    })
    poly.bindTooltip(
      `${area.areaName} · ${ZH_LEVEL[area.riskLevel ?? ''] ?? '—'}风险 ${area.riskScore ?? '—'}`,
      { sticky: true, direction: 'top' },
    )
    poly.on('click', () => selectArea(area.areaId))
    poly.addTo(riskLayer)
    areaShapes.set(area.areaId, poly)
  }
}

function redrawPredictionLayer(): void {
  if (!predictionLayer) return
  predictionLayer.clearLayers()
  if (!showPredictionLayer.value) return
  for (const p of predictions.value) {
    if (!p.predictedGeometry || p.predictedGeometry.length < 3) continue
    const poly = L.polygon(p.predictedGeometry as L.LatLngExpression[], {
      color: '#f43f5e',
      weight: 2,
      dashArray: '6 5',
      fillColor: '#f43f5e',
      fillOpacity: 0.12,
    })
    poly.bindTooltip(
      `+${p.forecastMinutes}min 预测扩散 · 置信度 ${p.confidence ?? '—'}`,
      { sticky: true, direction: 'top' },
    )
    poly.addTo(predictionLayer)
  }
}

function redrawRouteLayer(): void {
  if (!routeLayer) return
  routeLayer.clearLayers()
  for (const s of suggestions.value) {
    if (s.status === 'DISMISSED' || s.status === 'EXPIRED') continue
    const pts = s.waypoints.map((w) => [w.latitude, w.longitude] as L.LatLngExpression)
    if (pts.length < 2) continue
    const selected = s.suggestionId === selectedSuggestionId.value
    const line = L.polyline(pts, {
      color: selected ? '#22d3ee' : '#0ea5e9',
      weight: selected ? 4 : 2.5,
      dashArray: '4 6',
      opacity: s.status === 'DISPATCHED' ? 0.45 : 0.95,
    })
    line.bindTooltip(
      `巡检航线 · ${s.areaCode ?? '—'} · ${ZH_STATUS[s.status]}（${s.waypoints.length} 航点）`,
      { sticky: true, direction: 'top' },
    )
    line.on('click', () => {
      selectedSuggestionId.value = s.suggestionId
    })
    line.addTo(routeLayer)
    for (const w of s.waypoints) {
      L.circleMarker([w.latitude, w.longitude], {
        radius: 3,
        color: '#22d3ee',
        weight: 1,
        fillOpacity: 0.9,
      }).addTo(routeLayer)
    }
  }
}

/* ---------------- 交互 ---------------- */

async function selectArea(areaId: string): Promise<void> {
  selectedAreaId.value = areaId
  redrawRiskLayer()
  const area = areas.value.find((a) => a.areaId === areaId)
  if (area && map) {
    const shape = areaShapes.get(areaId)
    if (shape) map.flyToBounds(shape.getBounds().pad(0.25), { duration: 0.6 })
  }
  try {
    detail.value = await getRiskAreaDetail(areaId)
  } catch (e) {
    pushToast('error', `区域详情加载失败：${(e as Error).message}`)
  }
}

function closeDetail(): void {
  selectedAreaId.value = null
  detail.value = null
  redrawRiskLayer()
}

function focusArea(area: RiskArea): void {
  selectArea(area.areaId)
}

async function onAssess(): Promise<void> {
  assessing.value = true
  try {
    const summary = await postRiskAssess()
    pushToast(
      'success',
      `评估完成：${summary.assessedCount} 格（高 ${summary.highCount} / 中 ${summary.mediumCount} / 低 ${summary.lowCount}）`,
    )
    await loadAreas()
  } catch (e) {
    pushToast('error', `评估失败：${(e as Error).message}`)
  } finally {
    assessing.value = false
  }
}

async function onGenerate(areaIds?: string[]): Promise<void> {
  generating.value = true
  try {
    const created = await postPatrolSuggestionsGenerate(areaIds)
    if (created.length) {
      pushToast('success', `已生成 ${created.length} 条巡检建议`)
    } else {
      pushToast('warning', '没有可生成的新建议（已有待处理或无高分区）')
    }
    await loadSuggestions()
  } catch (e) {
    pushToast('error', `生成失败：${(e as Error).message}`)
  } finally {
    generating.value = false
  }
}

async function onRerunPrediction(): Promise<void> {
  rerunning.value = true
  try {
    const rows = await postPredictionRun()
    if (rows.length) {
      pushToast('success', `预测完成：${rows.map((r) => `+${r.forecastMinutes}min`).join(' / ')}`)
    } else {
      pushToast('warning', '当前无活跃火情，无法预测')
    }
    await loadPredictions()
  } catch (e) {
    pushToast('error', `预测失败：${(e as Error).message}`)
  } finally {
    rerunning.value = false
  }
}

function askDispatch(s: PatrolSuggestion): void {
  confirmTarget.value = s
}

async function onDispatchConfirmed(): Promise<void> {
  const target = confirmTarget.value
  if (!target) return
  dispatching.value = true
  try {
    await postPatrolSuggestionDispatch(target.suggestionId)
    pushToast('success', `已下发任务（${target.areaCode ?? '区域'}）`)
    confirmTarget.value = null
    await loadSuggestions()
  } catch (e) {
    pushToast('error', `下发失败：${(e as Error).message}`)
  } finally {
    dispatching.value = false
  }
}

async function onDismiss(s: PatrolSuggestion): Promise<void> {
  try {
    await postPatrolSuggestionDismiss(s.suggestionId)
    pushToast('success', '建议已驳回')
    await loadSuggestions()
  } catch (e) {
    pushToast('error', `驳回失败：${(e as Error).message}`)
  }
}

/* ---------------- 加载与轮询 ---------------- */

async function loadAreas(): Promise<void> {
  try {
    areas.value = await getRiskAreas()
    redrawRiskLayer()
  } catch {
    /* 轮询容错：失败保留旧数据 */
  }
}

async function loadSuggestions(): Promise<void> {
  try {
    suggestions.value = await getPatrolSuggestions()
    redrawRouteLayer()
  } catch {
    /* 轮询容错 */
  }
}

async function loadPredictions(): Promise<void> {
  try {
    predictions.value = await getLatestPredictions()
    if (predictions.value.length > 0) {
      const env = predictions.value[0].environmentalInput
      if (env) weather.value = env
    }
    redrawPredictionLayer()
  } catch {
    /* 轮询容错 */
  }
}

async function loadIncidents(): Promise<void> {
  try {
    incidents.value = parseIncidents(await getFireIncidents())
  } catch {
    /* 轮询容错 */
  }
}

let areasTimer: ReturnType<typeof setInterval> | undefined
let suggestionTimer: ReturnType<typeof setInterval> | undefined
let predictionTimer: ReturnType<typeof setInterval> | undefined
let incidentTimer: ReturnType<typeof setInterval> | undefined

onMounted(async () => {
  if (!mapEl.value) return
  map = L.map(mapEl.value, {
    center: DEFAULT_CENTER,
    zoom: DEFAULT_ZOOM,
    maxBounds: MAP_MAX_BOUNDS,
    maxBoundsViscosity: 0.8,
  })
  L.tileLayer('tiles/{z}/{x}/{y}.jpg', {
    minZoom: 13,
    maxZoom: 17,
    noWrap: true,
    errorTileUrl: TRANSPARENT_TILE,
    className: 'map-tiles',
    attribution: 'Tiles &copy; <a href="https://www.esri.com/">Esri</a> — Earthstar Geographics',
  }).addTo(map)
  riskLayer = L.layerGroup().addTo(map)
  predictionLayer = L.layerGroup().addTo(map)
  routeLayer = L.layerGroup().addTo(map)

  await Promise.all([loadAreas(), loadSuggestions(), loadPredictions(), loadIncidents()])
  // 命中高分区时视野铺满网格
  const high = areas.value.find((a) => a.riskLevel === 'HIGH')
  if (high?.geometry && map) {
    map.fitBounds(L.polygon(high.geometry as L.LatLngExpression[]).getBounds().pad(2.2))
  }

  areasTimer = setInterval(loadAreas, AREAS_POLL_MS)
  suggestionTimer = setInterval(loadSuggestions, SUGGESTION_POLL_MS)
  predictionTimer = setInterval(loadPredictions, PREDICTION_POLL_MS)
  incidentTimer = setInterval(loadIncidents, INCIDENT_POLL_MS)
})

onUnmounted(() => {
  clearInterval(areasTimer)
  clearInterval(suggestionTimer)
  clearInterval(predictionTimer)
  clearInterval(incidentTimer)
  map?.remove()
  map = null
  areaShapes.clear()
})

function fmtWeather(w: Record<string, unknown> | null): string {
  if (!w) return '—'
  const t = w.tempC
  const h = w.humidityPct
  const s = w.windSpeedMps
  const d = w.windDirectionDeg
  const dir =
    typeof d === 'number'
      ? ['北', '东北', '东', '东南', '南', '西南', '西', '西北'][Math.round(d / 45) % 8]
      : '—'
  return `${typeof t === 'number' ? t.toFixed(0) : '—'}℃ · 湿度 ${typeof h === 'number' ? h.toFixed(0) : '—'}% · ${dir}风 ${typeof s === 'number' ? s.toFixed(1) : '—'}m/s`
}
</script>

<template>
  <div class="risk-layout">
    <!-- ① 地图 + ③ 顶部状态条 -->
    <section class="risk-map-wrap">
      <div class="risk-toolbar">
        <button class="risk-btn primary" :disabled="assessing" @click="onAssess">
          {{ assessing ? '评估中…' : '重新评估' }}
        </button>
        <button class="risk-btn" :disabled="generating" @click="onGenerate()">
          {{ generating ? '生成中…' : '生成巡检建议' }}
        </button>
        <template v-if="fireState">
          <span class="risk-fire-badge">🔥 {{ activeIncidents.length }} 起活跃火情</span>
          <button class="risk-btn" :disabled="rerunning" @click="onRerunPrediction">
            {{ rerunning ? '预测中…' : '重跑预测' }}
          </button>
          <label class="risk-toggle">
            <input v-model="showPredictionLayer" type="checkbox" @change="redrawPredictionLayer" />
            预测图层
          </label>
        </template>
        <span class="risk-weather">气象：{{ fmtWeather(weather) }}</span>
      </div>
      <div ref="mapEl" class="risk-map"></div>
      <div class="risk-legend">
        <span v-for="(color, level) in LEVEL_COLOR" :key="level" class="risk-legend-item">
          <i :style="{ background: color }"></i>{{ ZH_LEVEL[level] }}风险
        </span>
        <span class="risk-legend-item"><i class="dash red"></i>预测扩散</span>
        <span class="risk-legend-item"><i class="dash cyan"></i>巡检航线</span>
      </div>
    </section>

    <!-- 右侧：② 风险区域列表 + ④ 建议任务面板 -->
    <aside class="risk-side">
      <div class="risk-panel">
        <div class="risk-panel-head">
          风险区域（{{ areas.length }}）
          <small>按分数降序</small>
        </div>
        <div class="risk-panel-body">
          <p v-if="areas.length === 0" class="risk-empty">暂无评估结果，点击「重新评估」生成 25 个网格。</p>
          <div
            v-for="a in areas"
            :key="a.areaId"
            class="risk-card"
            :class="{ selected: a.areaId === selectedAreaId }"
            @click="focusArea(a)"
          >
            <div class="risk-card-top">
              <b>{{ a.areaName }}</b>
              <span class="risk-level-chip" :style="{ '--c': levelColor(a.riskLevel) }">
                {{ ZH_LEVEL[a.riskLevel ?? ''] ?? '—' }}风险 {{ a.riskScore ?? '—' }}
              </span>
            </div>
            <div class="risk-card-actions">
              <button
                class="risk-btn small"
                :disabled="generating"
                @click.stop="onGenerate([a.areaId])"
              >
                生成巡检
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="risk-panel">
        <div class="risk-panel-head">
          巡检建议（{{ pendingSuggestions.length }} 待处理）
          <small>30s 自动刷新</small>
        </div>
        <div class="risk-panel-body">
          <p v-if="suggestions.length === 0" class="risk-empty">暂无建议；在风险区域卡片或地图上生成。</p>
          <div v-for="s in suggestions" :key="s.suggestionId" class="risk-card flat">
            <div class="risk-card-top">
              <b>{{ s.areaCode ?? '区域' }}</b>
              <span class="risk-status-chip" :class="`st-${s.status}`">{{ ZH_STATUS[s.status] }}</span>
            </div>
            <div class="risk-card-meta">
              优先级 {{ s.priority ?? '—' }} · {{ s.waypoints.length }} 航点 ·
              约 {{ s.estimatedDurationMin ?? '—' }} min
            </div>
            <div class="risk-card-actions">
              <template v-if="s.status === 'SUGGESTED'">
                <button class="risk-btn small primary" @click="askDispatch(s)">下发任务</button>
                <button class="risk-btn small" @click="onDismiss(s)">驳回</button>
              </template>
              <template v-else-if="s.status === 'DISPATCHED'">
                <button class="risk-btn small" @click="emit('jump-fire')">查看任务 →</button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </aside>

    <!-- 五因子抽屉 -->
    <div v-if="detail" class="risk-drawer">
      <div class="risk-drawer-head">
        <b>{{ detail.areaName }} · 五因子</b>
        <button class="risk-drawer-close" @click="closeDetail">✕</button>
      </div>
      <div class="risk-drawer-score">
        综合
        <span class="risk-level-chip big" :style="{ '--c': levelColor(detail.riskLevel) }">
          {{ detail.riskScore ?? '—' }} · {{ ZH_LEVEL[detail.riskLevel ?? ''] ?? '—' }}风险
        </span>
      </div>
      <div v-for="f in detail.factors" :key="f.featureType" class="risk-factor-row">
        <span class="risk-factor-name">{{ ZH_FACTOR[f.featureType] ?? f.featureType }}</span>
        <div class="risk-factor-bar">
          <i
            :style="{
              width: `${Math.min(100, f.contribution != null ? f.contribution * (100 / 30) : 0)}%`,
              background: levelColor(detail.riskLevel),
            }"
          ></i>
        </div>
        <span class="risk-factor-val">
          {{ f.featureValue ?? '—' }} 分 × {{ f.weight ?? '—' }} = {{ f.contribution ?? '—' }}
        </span>
      </div>
    </div>

    <!-- 下发确认弹窗 -->
    <div v-if="confirmTarget" class="risk-modal-mask" @click.self="confirmTarget = null">
      <div class="risk-modal">
        <div class="risk-modal-title">确认下发巡检任务</div>
        <p>
          将为 <b>{{ confirmTarget.areaCode }}</b> 生成
          <b>RISK_PATROL</b> 任务（{{ confirmTarget.waypoints.length }} 航点 · 航高 100m ·
          约 {{ confirmTarget.estimatedDurationMin ?? '—' }} min），经调度评分自动选机并下发首航点。
        </p>
        <div class="risk-modal-actions">
          <button class="risk-btn primary" :disabled="dispatching" @click="onDispatchConfirmed">
            {{ dispatching ? '下发中…' : '确认下发' }}
          </button>
          <button class="risk-btn" @click="confirmTarget = null">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.risk-layout {
  display: flex;
  gap: 12px;
  height: 100%;
  min-height: 0;
}
.risk-map-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.risk-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.risk-weather {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-dim, #94a3b8);
}
.risk-fire-badge {
  font-size: 12px;
  font-weight: 600;
  color: #fecaca;
  background: rgba(239, 68, 68, 0.18);
  border: 1px solid rgba(239, 68, 68, 0.55);
  border-radius: 999px;
  padding: 3px 10px;
  animation: risk-pulse 2s infinite;
}
@keyframes risk-pulse {
  50% {
    box-shadow: 0 0 0 4px rgba(239, 68, 68, 0.12);
  }
}
.risk-map {
  flex: 1;
  min-height: 0;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.25);
}
.risk-legend {
  position: absolute;
  left: 12px;
  bottom: 14px;
  z-index: 500;
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #cbd5e1;
  background: rgba(15, 23, 42, 0.72);
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 8px;
  padding: 6px 10px;
  pointer-events: none;
}
.risk-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.risk-legend-item i {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  display: inline-block;
}
.risk-legend-item i.dash {
  background: transparent;
  border-top: 2px dashed;
  height: 0;
  width: 14px;
}
.risk-legend-item i.red {
  border-color: #f43f5e;
}
.risk-legend-item i.cyan {
  border-color: #22d3ee;
}

.risk-btn {
  border: 1px solid rgba(148, 163, 184, 0.4);
  background: rgba(30, 41, 59, 0.8);
  color: #e2e8f0;
  font-size: 12px;
  border-radius: 8px;
  padding: 5px 12px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.risk-btn:hover:not(:disabled) {
  background: rgba(51, 65, 85, 0.9);
}
.risk-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.risk-btn.primary {
  background: rgba(16, 185, 129, 0.22);
  border-color: rgba(16, 185, 129, 0.6);
  color: #6ee7b7;
}
.risk-btn.small {
  font-size: 11px;
  padding: 3px 9px;
}
.risk-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #cbd5e1;
  cursor: pointer;
}

.risk-side {
  width: 330px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}
.risk-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 12px;
  overflow: hidden;
}
.risk-panel-head {
  padding: 9px 12px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}
.risk-panel-head small {
  font-weight: 400;
  color: #94a3b8;
  margin-left: 6px;
}
.risk-panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.risk-empty {
  font-size: 12px;
  color: #94a3b8;
  text-align: center;
  padding: 16px 0;
}
.risk-card {
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 10px;
  padding: 8px 10px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.risk-card:hover {
  background: rgba(51, 65, 85, 0.4);
}
.risk-card.selected {
  border-color: rgba(16, 185, 129, 0.65);
}
.risk-card.flat {
  cursor: default;
}
.risk-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
}
.risk-level-chip {
  font-size: 11px;
  font-weight: 600;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 18%, transparent);
  border: 1px solid var(--c);
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.risk-level-chip.big {
  font-size: 13px;
  padding: 3px 12px;
}
.risk-status-chip {
  font-size: 11px;
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.risk-status-chip.st-SUGGESTED {
  color: #7dd3fc;
  border: 1px solid rgba(125, 211, 252, 0.6);
  background: rgba(125, 211, 252, 0.12);
}
.risk-status-chip.st-DISPATCHED {
  color: #6ee7b7;
  border: 1px solid rgba(110, 231, 183, 0.6);
  background: rgba(110, 231, 183, 0.12);
}
.risk-status-chip.st-DISMISSED,
.risk-status-chip.st-EXPIRED {
  color: #94a3b8;
  border: 1px solid rgba(148, 163, 184, 0.4);
}
.risk-card-meta {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 3px;
}
.risk-card-actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}

.risk-drawer {
  position: absolute;
  top: 52px;
  left: 12px;
  z-index: 600;
  width: 340px;
  background: rgba(15, 23, 42, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 12px;
  padding: 12px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.45);
}
.risk-drawer-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  margin-bottom: 8px;
}
.risk-drawer-close {
  background: none;
  border: none;
  color: #94a3b8;
  font-size: 14px;
  cursor: pointer;
}
.risk-drawer-score {
  font-size: 12px;
  color: #cbd5e1;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.risk-factor-row {
  display: grid;
  grid-template-columns: 62px 1fr auto;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  margin-bottom: 7px;
}
.risk-factor-name {
  color: #cbd5e1;
}
.risk-factor-bar {
  height: 8px;
  background: rgba(148, 163, 184, 0.18);
  border-radius: 4px;
  overflow: hidden;
}
.risk-factor-bar i {
  display: block;
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s;
}
.risk-factor-val {
  color: #94a3b8;
  white-space: nowrap;
}

.risk-modal-mask {
  position: absolute;
  inset: 0;
  z-index: 700;
  background: rgba(2, 6, 23, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
}
.risk-modal {
  width: 380px;
  background: #0f172a;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  padding: 16px;
  font-size: 13px;
  color: #cbd5e1;
}
.risk-modal-title {
  font-size: 14px;
  font-weight: 600;
  color: #f1f5f9;
  margin-bottom: 8px;
}
.risk-modal-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
