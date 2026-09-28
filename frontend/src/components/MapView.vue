<script setup lang="ts">
import * as L from 'leaflet'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  fireStatusColor,
  fireStatusPulse,
  formatCoords,
  nextFireStatuses,
  relTimeText,
  zhDecision,
  zhFireStatus,
} from '../fire'
import type { FireIncident, GotoPayload, UavState, VerificationResult } from '../types'

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
  busy: { incidentId: string; action: 'verify' | 'dispatch' } | null
}>()

const emit = defineEmits<{
  (e: 'goto', payload: GotoPayload): void
  (e: 'select-incident', id: string): void
  (e: 'verify', id: string): void
  (e: 'dispatch', id: string): void
  (e: 'status-change', id: string, status: string): void
  (e: 'close-card'): void
}>()

const DEFAULT_ALTITUDE = 120
const DEFAULT_CENTER: L.LatLngExpression = [30.12, 114.12]
const DEFAULT_ZOOM = 15

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
const cardTerminated = computed(() => cardStatus.value === 'CLOSED')

let map: L.Map | null = null
let marker: L.Marker | null = null
let lastHeading = 0
const fireMarkers = new Map<string, L.Marker>()

function uavIcon(heading: number): L.DivIcon {
  const deg = ((heading % 360) + 360) % 360
  return L.divIcon({
    className: 'uav-div-icon',
    html:
      `<div class="uav-icon" style="transform: rotate(${deg}deg)">` +
      '<svg viewBox="0 0 40 40" width="40" height="40">' +
      '<circle cx="20" cy="20" r="13" fill="rgba(0,229,160,0.14)" stroke="#00e5a0" stroke-width="1.5"/>' +
      '<path d="M20 5 L26 25 L20 21 L14 25 Z" fill="#00e5a0" stroke="#003b2a" stroke-width="0.5"/>' +
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
        // 状态变化时重建 icon（颜色/脉冲随之变化）
        existing.setIcon(fireIcon(inc))
        const el2 = existing.getElement()?.querySelector<HTMLElement>('.fire-marker')
        if (el2) el2.dataset.status = inc.status
      }
    } else {
      const m = L.marker([inc.latitude, inc.longitude], {
        icon: fireIcon(inc),
        zIndexOffset: 500,
      }).addTo(map)
      m.on('click', () => onFireMarkerClick(inc))
      const el = m.getElement()?.querySelector<HTMLElement>('.fire-marker')
      if (el) el.dataset.status = inc.status
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

    if (!marker) {
      marker = L.marker([lat, lng], { icon: uavIcon(heading), zIndexOffset: 1000 }).addTo(map)
    } else {
      marker.setLatLng([lat, lng])
      const rot = marker.getElement()?.querySelector<HTMLElement>('.uav-icon')
      if (rot) {
        rot.style.transform = `rotate(${((heading % 360) + 360) % 360}deg)`
      } else {
        marker.setIcon(uavIcon(heading))
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
  })
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    className: 'map-tiles',
    attribution:
      '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  }).addTo(map)
  map.on('click', onMapClick)
  map.on('move', () => {
    repositionDraft()
    repositionCard()
  })
  map.on('dragstart', () => {
    follow.value = false
  })
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
        <span
          class="chip fire-chip"
          :class="`fs-${cardStatus || 'OTHER'}`"
          :style="{ '--fire-color': fireStatusColor(cardStatus) }"
        >
          🔥 {{ zhFireStatus(cardStatus) }}
        </span>
        <button class="ic-close" title="关闭" @click="closeCard">✕</button>
      </div>
      <div class="ic-no">{{ cardIncident.incidentNo }}</div>
      <div class="kv"><span>坐标</span><b>{{ formatCoords(cardIncident.latitude, cardIncident.longitude) }}</b></div>
      <div class="kv">
        <span>置信度</span>
        <b>{{ cardIncident.confidence === null ? '—' : `${cardIncident.confidence}%` }}</b>
      </div>
      <div class="kv"><span>检测时间</span><b>{{ relTimeText(cardIncident.timeMs, now) }}</b></div>
      <div v-if="cardVerification" class="ic-verdict">
        AI 核验：<b>{{ zhDecision(cardVerification.decision) }}</b>
        <small v-if="cardVerification.confidence !== null">（置信度 {{ cardVerification.confidence }}%）</small>
      </div>
      <div class="ic-actions">
        <button
          class="btn ic-btn"
          :disabled="cardTerminated || cardBusyVerify"
          @click="emit('verify', cardIncident.id)"
        >
          {{ cardBusyVerify ? '核验中…' : '触发核验' }}
        </button>
        <button
          class="btn ic-btn"
          :disabled="cardTerminated || cardBusyDispatch || cardDispatched || cardIncident.latitude === null"
          @click="emit('dispatch', cardIncident.id)"
        >
          {{ cardDispatched ? '已派单' : cardBusyDispatch ? '派单中…' : '派单核验' }}
        </button>
      </div>
      <div class="ic-status-row">
        <select v-model="statusPick" class="ic-select" :disabled="cardNexts.length === 0" @change="onStatusSelect">
          <option value="" disabled>
            {{ cardNexts.length === 0 ? '当前为终态' : '状态流转…' }}
          </option>
          <option v-for="s in cardNexts" :key="s" :value="s">{{ zhFireStatus(s) }}</option>
        </select>
      </div>
    </div>
  </div>
</template>
