<script setup lang="ts">
import * as L from 'leaflet'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  canAnalyze,
  compassText,
  fireStatusColor,
  fireStatusPulse,
  formatAreaText,
  formatGrowthRate,
  formatCoords,
  isFalseAlarmDemo,
  nextFireStatuses,
  num1,
  polygonFillOpacity,
  relTimeText,
  zhDecision,
  zhFireStatus,
  zhTrend,
} from '../fire'
import type {
  FireAnalysis,
  FireIncident,
  FirePolygonShape,
  GotoPayload,
  StatusPoint,
  UavState,
  VerificationResult,
} from '../types'

const props = defineProps<{
  state: UavState | null
  incidents: FireIncident[]
  now: number
  selectedId: string | null
  /** 列表点击触发 {id, seq}，seq 变化即重新定位 */
  focus: { id: string; seq: number } | null
  /** AI 核验结果（incidentId -> 结果） */
  verifications: Record<string, VerificationResult>
  /** 已派单事件（incidentId -> missionId） */
  dispatched: Record<string, string>
  /** 当前进行中的卡片动作 */
  busy: { incidentId: string; action: 'verify' | 'dispatch' | 'analyze' } | null
  /** 状态时间线（incidentId -> 状态变化序列） */
  timelines: Record<string, StatusPoint[]>
  /** 选中事件的多边形历史（升序，扩散年轮） */
  polygons: FirePolygonShape[]
  /** 选中事件的最新分析（趋势面板） */
  analysis: FireAnalysis | null
  /** 每次分析成功自增，触发新多边形扩散动画 */
  analysisPulse: number
  /** 已完成分析轮次（incidentId -> 轮次） */
  analysisRounds: Record<string, number>
}>()

const emit = defineEmits<{
  (e: 'goto', payload: GotoPayload): void
  (e: 'select-incident', id: string): void
  (e: 'verify', id: string): void
  (e: 'dispatch', id: string): void
  (e: 'analyze', id: string): void
  (e: 'status-change', id: string, status: string): void
  (e: 'close-card'): void
}>()

const DEFAULT_ALTITUDE = 120
/** 演示区中心（火场常用区域） */
const DEFAULT_CENTER: L.LatLngExpression = [30.12, 114.13]
const DEFAULT_ZOOM = 15
/** 拖拽边界 = z13 瓦片覆盖范围（29.85~30.45N, 113.85~114.45E），此范围内任意缩放级别都有瓦片 */
const DEMO_BOUNDS = L.latLngBounds([29.85, 113.85], [30.45, 114.45])
/** 1x1 透明 png：缺失瓦片不显示破图 */
const TRANSPARENT_TILE =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='

const mapEl = ref<HTMLDivElement | null>(null)
const follow = ref(true)

interface GotoDraft {
  lat: number
  lng: number
  x: number
  y: number
  altitude: number
}

const gotoDraft = ref<GotoDraft | null>(null)

/* ---------------- 火情事件卡片 ---------------- */

const openCardId = ref<string | null>(null)
const cardPos = ref<{ x: number; y: number } | null>(null)
const statusPick = ref('')

const cardIncident = computed<FireIncident | null>(() => {
  if (openCardId.value === null) return null
  return props.incidents.find((i) => i.id === openCardId.value) ?? null
})

const cardStatus = computed(() => cardIncident.value?.status ?? '')
const cardNexts = computed(() => nextFireStatuses(cardStatus.value))
const cardVerification = computed<VerificationResult | null>(() => {
  const inc = cardIncident.value
  if (!inc) return null
  return props.verifications[inc.id] ?? null
})
const cardDispatched = computed(() => {
  const inc = cardIncident.value
  if (!inc) return false
  return props.dispatched[inc.id] !== undefined
})
const cardBusyVerify = computed(
  () => props.busy?.action === 'verify' && props.busy.incidentId === openCardId.value,
)
const cardBusyDispatch = computed(
  () => props.busy?.action === 'dispatch' && props.busy.incidentId === openCardId.value,
)
/** 核验仅限 SUSPECTED/VERIFYING（backend 规则）；终态/已确认事件不可再核验 */
const cardCanVerify = computed(
  () => cardStatus.value === 'SUSPECTED' || cardStatus.value === 'VERIFYING',
)
/** 派单仅对未关闭事件有意义（FALSE_ALARM/RESOLVED/CLOSED 不可派单） */
const cardCanDispatch = computed(
  () => !['FALSE_ALARM', 'RESOLVED', 'CLOSED'].includes(cardStatus.value),
)
const cardIsFalseAlarm = computed(() => isFalseAlarmDemo(cardIncident.value))
const cardTimeline = computed<StatusPoint[]>(() => {
  const inc = cardIncident.value
  if (!inc) return []
  return props.timelines[inc.id] ?? []
})
const cardTimelineLocalOnly = computed(
  () => cardTimeline.value.length > 0 && cardTimeline.value.every((p) => p.source === 'local'),
)

/* ---------------- 火场分析（卡片） ---------------- */

const cardAnalysis = computed(() => (cardIncident.value ? props.analysis : null))
const cardCanAnalyze = computed(() => canAnalyze(cardStatus.value))
const cardBusyAnalyze = computed(
  () => props.busy?.action === 'analyze' && props.busy.incidentId === openCardId.value,
)
const cardRound = computed(() => {
  const inc = cardIncident.value
  if (!inc) return 0
  return props.analysis?.growthStep ?? props.analysisRounds[inc.id] ?? 0
})
const cardLatestShape = computed<FirePolygonShape | null>(() => {
  if (props.analysis?.polygon) return props.analysis.polygon
  return props.polygons.length > 0 ? props.polygons[props.polygons.length - 1] : null
})
const analysisHint = computed(() => {
  if (cardCanAnalyze.value) return ''
  if (cardStatus.value === 'FALSE_ALARM') return '误报事件无需分析'
  if (cardStatus.value === 'CLOSED' || cardStatus.value === 'RESOLVED') return '事件已结束'
  return '确认后可分析'
})

let map: L.Map | null = null
let marker: L.Marker | null = null
let lastHeading = 0
let lastOffline: boolean | null = null
const fireMarkers = new Map<string, L.Marker>()
let polygonLayer: L.LayerGroup | null = null

function uavIcon(heading: number, offline: boolean): L.DivIcon {
  const deg = ((heading % 360) + 360) % 360
  const color = offline ? '#94a3b8' : '#00e5a0'
  const glow = offline ? 'rgba(148, 163, 184, 0.55)' : 'rgba(0, 229, 160, 0.7)'
  const fill = offline ? 'rgba(148, 163, 184, 0.14)' : 'rgba(0, 229, 160, 0.14)'
  return L.divIcon({
    className: 'uav-div-icon',
    html:
      `<div class="uav-icon" style="transform: rotate(${deg}deg); filter: drop-shadow(0 0 5px ${glow})">` +
      '<svg viewBox="0 0 40 40" width="40" height="40">' +
      `<circle cx="20" cy="20" r="13" fill="${fill}" stroke="${color}" stroke-width="1.5"/>` +
      `<path d="M20 5 L26 25 L20 21 L14 25 Z" fill="${color}" stroke="#003b2a" stroke-width="0.5"/>` +
      '</svg></div>',
    iconSize: [40, 40],
    iconAnchor: [20, 20],
  })
}

/** 火点 divIcon：火焰圆点 + 状态色 + CSS 脉冲环 */
function fireIcon(incident: FireIncident): L.DivIcon {
  const color = fireStatusColor(incident.status)
  const pulse = fireStatusPulse(incident.status)
  const html =
    `<div class="fire-marker" style="--fire-color:${color}">` +
    (pulse ? '<span class="fire-pulse"></span>' : '') +
    '<span class="fire-core">🔥</span>' +
    '</div>'
  return L.divIcon({
    className: 'fire-div-icon',
    html,
    iconSize: [26, 26],
    iconAnchor: [13, 13],
  })
}

function clampPoint(p: L.Point, marginRight: number, marginBottom: number): L.Point {
  if (!map) return p
  const size = map.getSize()
  const x = Math.min(Math.max(p.x, 8), Math.max(8, size.x - marginRight))
  const y = Math.min(Math.max(p.y, 8), Math.max(8, size.y - marginBottom))
  return L.point(x, y)
}

function repositionDraft(): void {
  const draft = gotoDraft.value
  if (!draft || !map) return
  const p = map.latLngToContainerPoint([draft.lat, draft.lng])
  const c = clampPoint(p, 236, 236)
  draft.x = c.x
  draft.y = c.y
}

function repositionCard(): void {
  if (!map) return
  const inc = cardIncident.value
  if (!inc) {
    cardPos.value = null
    return
  }
  if (inc.latitude !== null && inc.longitude !== null) {
    const p = map.latLngToContainerPoint([inc.latitude, inc.longitude])
    const c = clampPoint(p, 306, 370)
    cardPos.value = { x: c.x + 16, y: c.y - 16 }
  } else {
    const size = map.getSize()
    cardPos.value = { x: Math.max(8, size.x / 2 - 130), y: Math.max(8, size.y / 2 - 160) }
  }
}

function openIncidentCard(id: string, fly = false): void {
  const inc = props.incidents.find((i) => i.id === id)
  if (!inc) return
  openCardId.value = id
  statusPick.value = ''
  repositionCard()
  if (fly && map && inc.latitude !== null && inc.longitude !== null) {
    follow.value = false
    map.flyTo([inc.latitude, inc.longitude], Math.max(map.getZoom(), 16), { duration: 0.8 })
  }
}

function onFireMarkerClick(incident: FireIncident): void {
  openIncidentCard(incident.id)
  emit('select-incident', incident.id)
}

function closeCard(): void {
  openCardId.value = null
  cardPos.value = null
  emit('close-card')
}

function onStatusSelect(): void {
  const inc = cardIncident.value
  const target = statusPick.value
  statusPick.value = ''
  if (!inc || !target) return
  emit('status-change', inc.id, target)
}

/** 状态变化时的一次性闪烁/放大反馈（1.2s 动画，结束后自动还原，不常驻） */
function triggerFlash(m: L.Marker): void {
  const el = m.getElement()?.querySelector<HTMLElement>('.fire-marker')
  if (!el) return
  el.classList.remove('fire-flash')
  void el.offsetWidth // 强制 reflow，保证重复触发
  el.classList.add('fire-flash')
  window.setTimeout(() => el.classList.remove('fire-flash'), 1600)
}

/** 事件列表变化：增/改/删 marker */
function syncFireMarkers(): void {
  if (!map) return
  const seen = new Set<string>()
  for (const inc of props.incidents) {
    if (inc.latitude === null || inc.longitude === null) continue
    seen.add(inc.id)
    const existing = fireMarkers.get(inc.id)
    if (existing) {
      existing.setLatLng([inc.latitude, inc.longitude])
      const el = existing.getElement()?.querySelector<HTMLElement>('.fire-marker')
      if (!el || el.dataset.status !== inc.status) {
        // 状态变化时重建 icon（颜色/脉冲随之变化）并闪烁一次
        existing.setIcon(fireIcon(inc))
        const el2 = existing.getElement()?.querySelector<HTMLElement>('.fire-marker')
        if (el2) el2.dataset.status = inc.status
        triggerFlash(existing)
      }
    } else {
      const m = L.marker([inc.latitude, inc.longitude], {
        icon: fireIcon(inc),
        zIndexOffset: 500,
      }).addTo(map)
      m.on('click', () => onFireMarkerClick(inc))
      const el = m.getElement()?.querySelector<HTMLElement>('.fire-marker')
      if (el) el.dataset.status = inc.status
      triggerFlash(m)
      fireMarkers.set(inc.id, m)
    }
  }
  for (const [id, m] of fireMarkers) {
    if (!seen.has(id)) {
      m.remove()
      fireMarkers.delete(id)
    }
  }
  // 打开的卡片对应事件消失则关闭
  if (openCardId.value !== null && !props.incidents.some((i) => i.id === openCardId.value)) {
    openCardId.value = null
    cardPos.value = null
  }
  repositionCard()
}

watch(
  () => props.incidents,
  () => {
    syncFireMarkers()
  },
  { deep: false },
)

watch(
  () => props.focus,
  (f) => {
    if (f) openIncidentCard(f.id, true)
  },
)

/* ---------------- 火场多边形（扩散年轮） ---------------- */

/** 全量重绘：越早的越淡，最新一代红描边；切换事件/更新历史时调用（自动清除旧多边形） */
function redrawPolygons(): void {
  if (!polygonLayer) return
  polygonLayer.clearLayers()
  const total = props.polygons.length
  props.polygons.forEach((shape, i) => {
    if (!polygonLayer) return
    const isLast = i === total - 1
    const poly = L.polygon(shape.points as L.LatLngExpression[], {
      color: '#f43f5e',
      weight: isLast ? 2 : 1,
      opacity: isLast ? 0.9 : 0.3,
      fillColor: '#f43f5e',
      fillOpacity: polygonFillOpacity(i, total),
      smoothFactor: 1,
    })
    const round = shape.step ?? i + 1
    const label =
      `第 ${round} 轮` +
      (shape.areaSquareMeters !== null ? ` · ${formatAreaText(shape.areaSquareMeters)}` : '')
    poly.bindTooltip(label, { sticky: true, direction: 'top' })
    poly.addTo(polygonLayer)
  })
}

watch([() => props.selectedId, () => props.polygons], () => {
  redrawPolygons()
  // 外部选中（Scenario-05 编排/列表点击）同步打开事件卡：
  // 否则趋势面板与火场分析按钮只在 marker 点击路径出现（D6 彩排发现）
  if (props.selectedId && props.selectedId !== openCardId.value) {
    openIncidentCard(props.selectedId)
  }
})

/** 分析成功后：最新一代多边形播放一次扩散动画（渐入+轻微缩放，约 1s） */
watch(
  () => props.analysisPulse,
  () => {
    window.setTimeout(() => {
      const layers = polygonLayer?.getLayers() ?? []
      const last = layers[layers.length - 1] as L.Path | undefined
      const el = last?.getElement()
      if (!el) return
      el.classList.remove('poly-new')
      void el.getBoundingClientRect() // SVG 无 offsetWidth，用 getBoundingClientRect 强制 reflow
      el.classList.add('poly-new')
      window.setTimeout(() => el.classList.remove('poly-new'), 1400)
    }, 60)
  },
)

function onMapClick(e: L.LeafletMouseEvent): void {
  if (!map) return
  const c = clampPoint(e.containerPoint, 236, 236)
  gotoDraft.value = {
    lat: e.latlng.lat,
    lng: e.latlng.lng,
    x: c.x,
    y: c.y,
    altitude: gotoDraft.value?.altitude ?? DEFAULT_ALTITUDE,
  }
}

function confirmGoto(): void {
  const draft = gotoDraft.value
  if (!draft) return
  const altitude = Number(draft.altitude)
  emit('goto', {
    latitude: draft.lat,
    longitude: draft.lng,
    altitude: Number.isFinite(altitude) ? Math.min(1000, Math.max(20, altitude)) : DEFAULT_ALTITUDE,
  })
  gotoDraft.value = null
}

function cancelGoto(): void {
  gotoDraft.value = null
}

watch(
  () => props.state,
  (s) => {
    if (!s || !map) return
    const lat = Number(s.latitude)
    const lng = Number(s.longitude)
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return
    const heading = Number.isFinite(Number(s.heading)) ? Number(s.heading) : lastHeading
    lastHeading = heading
    // OFFLINE（心跳超时/断联演示）时标记变灰，保留最后已知位置
    const offline = String(s.status ?? '').toUpperCase() === 'OFFLINE'

    if (!marker) {
      marker = L.marker([lat, lng], { icon: uavIcon(heading, offline), zIndexOffset: 1000 }).addTo(map)
      lastOffline = offline
    } else {
      marker.setLatLng([lat, lng])
      if (lastOffline !== offline) {
        marker.setIcon(uavIcon(heading, offline))
        lastOffline = offline
      } else {
        const rot = marker.getElement()?.querySelector<HTMLElement>('.uav-icon')
        if (rot) {
          rot.style.transform = `rotate(${((heading % 360) + 360) % 360}deg)`
        } else {
          marker.setIcon(uavIcon(heading, offline))
        }
      }
    }
    if (follow.value) {
      map.panTo([lat, lng], { animate: true, duration: 0.5 })
    }
  },
)

onMounted(() => {
  if (!mapEl.value) return
  map = L.map(mapEl.value, {
    center: DEFAULT_CENTER,
    zoom: DEFAULT_ZOOM,
    // 视野钳制在离线瓦片演示区（外扩），防止拖出无瓦片区
    maxBounds: DEMO_BOUNDS,
    maxBoundsViscosity: 0.8, // 软性边界：边缘有回弹，不是死墙
  })
  // 本地离线瓦片（frontend/public/tiles/{z}/{x}/{y}.jpg，build 后随 dist/ 发布）
  // 源: Esri World_Imagery 卫星影像（scripts/fetch-tiles.mjs 一次性下载；断网可演示）
  L.tileLayer('tiles/{z}/{x}/{y}.jpg', {
    minZoom: 13,
    maxZoom: 17,
    noWrap: true,
    errorTileUrl: TRANSPARENT_TILE,
    className: 'map-tiles',
    attribution:
      'Tiles &copy; <a href="https://www.esri.com/">Esri</a> — Earthstar Geographics',
  }).addTo(map)
  map.on('click', onMapClick)
  map.on('move', () => {
    repositionDraft()
    repositionCard()
  })
  map.on('dragstart', () => {
    follow.value = false
  })
  polygonLayer = L.layerGroup().addTo(map)
  redrawPolygons()
  syncFireMarkers()
})

onUnmounted(() => {
  map?.remove()
  map = null
  marker = null
  fireMarkers.clear()
})
</script>

<template>
  <div class="map-wrap">
    <div ref="mapEl" class="map"></div>
    <button class="follow-btn" :class="{ active: follow }" @click="follow = !follow">
      跟随：{{ follow ? '开' : '关' }}
    </button>
    <div v-if="gotoDraft" class="goto-panel" :style="{ left: `${gotoDraft.x}px`, top: `${gotoDraft.y}px` }">
      <div class="goto-title">GOTO 目标点</div>
      <div class="kv"><span>纬度</span><b>{{ gotoDraft.lat.toFixed(6) }}</b></div>
      <div class="kv"><span>经度</span><b>{{ gotoDraft.lng.toFixed(6) }}</b></div>
      <div class="kv">
        <span>相对高度 (m)</span>
        <input v-model.number="gotoDraft.altitude" type="number" min="20" max="1000" step="10" />
      </div>
      <div class="kv"><span>altitudeMode</span><b>RELATIVE_TO_TAKEOFF</b></div>
      <div class="goto-actions">
        <button class="btn primary" @click="confirmGoto">确认下发</button>
        <button class="btn" @click="cancelGoto">取消</button>
      </div>
    </div>

    <!-- 火情事件卡片 -->
    <div
      v-if="cardIncident"
      class="incident-card"
      :style="cardPos ? { left: `${cardPos.x}px`, top: `${cardPos.y}px` } : { display: 'none' }"
    >
      <div class="ic-head">
        <div class="ic-chips">
          <span
            class="chip fire-chip"
            :class="`fs-${cardStatus || 'OTHER'}`"
            :style="{ '--fire-color': fireStatusColor(cardStatus) }"
          >
            {{ cardIsFalseAlarm ? '⚠️' : '🔥' }} {{ zhFireStatus(cardStatus) }}
          </span>
          <span v-if="cardIsFalseAlarm" class="ic-demo-badge">误报演示</span>
        </div>
        <button class="ic-close" title="关闭" @click="closeCard">✕</button>
      </div>
      <div class="ic-no">{{ cardIncident.incidentNo }}</div>
      <div class="kv"><span>坐标</span><b>{{ formatCoords(cardIncident.latitude, cardIncident.longitude) }}</b></div>
      <div class="kv">
        <span>置信度</span>
        <b>{{ cardIncident.confidence === null ? '—' : `${cardIncident.confidence}%` }}</b>
      </div>
      <div class="kv"><span>检测时间</span><b>{{ relTimeText(cardIncident.timeMs, now) }}</b></div>
      <div class="ic-verdict" :class="{ false: cardVerification.decision === 'FALSE_ALARM' }" v-if="cardVerification">
        AI 核验：<b>{{ zhDecision(cardVerification.decision) }}</b>
        <small v-if="cardVerification.confidence !== null">（置信度 {{ cardVerification.confidence }}%）</small>
      </div>
      <div class="ic-actions">
        <button
          class="btn ic-btn"
          :disabled="!cardCanVerify || cardBusyVerify"
          @click="emit('verify', cardIncident.id)"
        >
          {{ cardBusyVerify ? '核验中…' : cardCanVerify ? '触发核验' : '当前状态不可核验' }}
        </button>
        <button
          class="btn ic-btn"
          :disabled="!cardCanDispatch || cardBusyDispatch || cardDispatched || cardIncident.latitude === null"
          @click="emit('dispatch', cardIncident.id)"
        >
          {{ cardDispatched ? '已派单' : cardBusyDispatch ? '派单中…' : cardCanDispatch ? '派单核验' : '不可派单' }}
        </button>
      </div>
      <div class="ic-analysis-row">
        <button
          class="btn ic-btn ic-analyze-btn"
          :disabled="!cardCanAnalyze || cardBusyAnalyze"
          @click="emit('analyze', cardIncident.id)"
        >
          {{
            cardBusyAnalyze
              ? '分析中…'
              : cardRound > 0
                ? `🔥 火场分析 · 第 ${cardRound} 轮`
                : '🔥 火场分析'
          }}
        </button>
        <span v-if="analysisHint" class="ic-analysis-hint">{{ analysisHint }}</span>
      </div>
      <div class="ic-status-row">
        <select v-model="statusPick" class="ic-select" :disabled="cardNexts.length === 0" @change="onStatusSelect">
          <option value="" disabled>
            {{ cardNexts.length === 0 ? '当前为终态' : '状态流转…' }}
          </option>
          <option v-for="s in cardNexts" :key="s" :value="s">{{ zhFireStatus(s) }}</option>
        </select>
      </div>
      <div v-if="cardTimeline.length" class="ic-timeline">
        <div class="ic-tl-head">
          状态时间线
          <small v-if="cardTimelineLocalOnly">（会话内记录）</small>
        </div>
        <div class="ic-tl-chips">
          <span
            v-for="(p, i) in cardTimeline"
            :key="`${p.status}-${i}`"
            class="tl-chip"
            :class="{ latest: i === cardTimeline.length - 1 }"
            :style="{ '--fire-color': fireStatusColor(p.status) }"
            :title="p.at !== null ? new Date(p.at).toLocaleTimeString('zh-CN', { hour12: false }) : ''"
          >
            {{ zhFireStatus(p.status) }}
          </span>
        </div>
      </div>

      <!-- 火场趋势面板（F06） -->
      <div v-if="cardAnalysis || cardLatestShape" class="ic-trend">
        <div class="ic-tl-head">
          火场趋势
          <small v-if="cardRound > 0">· 第 {{ cardRound }} 轮分析</small>
        </div>
        <div class="kv">
          <span>趋势</span>
          <b>
            <span class="trend-chip" :class="`trend-${props.analysis?.tracking?.trend ?? 'OTHER'}`">
              {{ zhTrend(props.analysis?.tracking?.trend ?? null) }}
            </span>
          </b>
        </div>
        <div class="kv">
          <span>蔓延方向</span>
          <b>{{ compassText(props.analysis?.tracking?.direction ?? null) }}</b>
        </div>
        <div class="kv">
          <span>蔓延速度</span>
          <b>{{ num1(props.analysis?.tracking?.speed ?? null) }} m/s</b>
        </div>
        <div class="kv">
          <span>面积增长率</span>
          <b>{{ formatGrowthRate(props.analysis?.tracking?.areaGrowthRate ?? null) }} %/轮</b>
        </div>
        <div class="kv">
          <span>最新面积</span>
          <b>{{ formatAreaText(cardLatestShape?.areaSquareMeters ?? null) }}</b>
        </div>
        <div class="kv">
          <span>火场半径</span>
          <b>{{ cardLatestShape?.radiusMeters !== null && cardLatestShape?.radiusMeters !== undefined ? `${Math.round(cardLatestShape.radiusMeters)} m` : '—' }}</b>
        </div>
      </div>
    </div>
  </div>
</template>
