<script setup lang="ts">
import * as L from 'leaflet'
import { onMounted, onUnmounted, ref, watch } from 'vue'
import type { GotoPayload, UavState } from '../types'

const props = defineProps<{ state: UavState | null }>()

const emit = defineEmits<{ (e: 'goto', payload: GotoPayload): void }>()

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

let map: L.Map | null = null
let marker: L.Marker | null = null
let lastHeading = 0

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

function clampPoint(p: L.Point): L.Point {
  if (!map) return p
  const size = map.getSize()
  const x = Math.min(Math.max(p.x, 8), Math.max(8, size.x - 236))
  const y = Math.min(Math.max(p.y, 8), Math.max(8, size.y - 236))
  return L.point(x, y)
}

function repositionDraft(): void {
  const draft = gotoDraft.value
  if (!draft || !map) return
  const p = map.latLngToContainerPoint([draft.lat, draft.lng])
  const c = clampPoint(p)
  draft.x = c.x
  draft.y = c.y
}

function onMapClick(e: L.LeafletMouseEvent): void {
  if (!map) return
  const c = clampPoint(e.containerPoint)
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
  map.on('move', repositionDraft)
  map.on('dragstart', () => {
    follow.value = false
  })
})

onUnmounted(() => {
  map?.remove()
  map = null
  marker = null
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
  </div>
</template>
