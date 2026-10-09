<script setup lang="ts">
import * as L from 'leaflet'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  getAssessmentReportDetail,
  getAssessmentReports,
  getFireIncidents,
  postBurnedArea,
  type AssessmentReport,
  type AssessmentReportDetail,
} from '../api'
import { parseIncidents, pickFirst } from '../fire'
import { pushToast } from '../toast'

/* 轮询间隔（评估报告 30s / 已处置事件 60s） */
const REPORTS_POLL_MS = 30000
const INCIDENTS_POLL_MS = 60000

/** 演示区中心（与 RiskPage 一致） */
const DEFAULT_CENTER: L.LatLngExpression = [30.142, 114.146]
const DEFAULT_ZOOM = 13
/** 视野钳制在本地瓦片覆盖区（z13 边界最宽） */
const MAP_MAX_BOUNDS = L.latLngBounds([29.85, 113.85], [30.45, 114.45])
/** 1x1 透明 png：缺失瓦片不显示破图 */
const TRANSPARENT_TILE =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='

const SEVERITY_COLOR: Record<string, string> = {
  SEVERE: '#7f1d1d',
  MODERATE: '#dc2626',
  LIGHT: '#f97316',
}
const ZH_SEVERITY: Record<string, string> = { SEVERE: '重度', MODERATE: '中度', LIGHT: '轻度' }
const ZH_STATUS: Record<string, string> = { RESOLVED: '已处置', CLOSED: '已关闭' }

/* ---------------- 数据 ---------------- */

const incidents = ref<ReturnType<typeof parseIncidents>>([])
const reports = ref<AssessmentReport[]>([])
const detail = ref<AssessmentReportDetail | null>(null)
const assessing = ref<string | null>(null)
const showReferenceLayer = ref(true)
const selectedReportId = ref<string | null>(null)

const resolvedIncidents = computed(() =>
  incidents.value.filter((i) => ['RESOLVED', 'CLOSED'].includes(i.status)),
)

/** 累计过火面积（所有报告 burnedAreaSquareMeter 求和 → ha） */
const totalBurnedHa = computed(() => {
  const sum = reports.value.reduce((acc, r) => acc + (r.burnedAreaSquareMeter ?? 0), 0)
  return fmtHa(sum)
})

/** incidentId → reportId 映射，用于判断事件是否已评估 */
const reportIncidentMap = computed(() => {
  const m = new Map<string, string>()
  for (const r of reports.value) m.set(r.incidentId, r.reportId)
  return m
})

function hasReport(incidentId: string): boolean {
  return reportIncidentMap.value.has(incidentId)
}

/* ---------------- 地图（独立实例：v-if 挂载初始化 / 卸载 destroy） ---------------- */

const mapEl = ref<HTMLDivElement | null>(null)
let map: L.Map | null = null
let bandLayer: L.LayerGroup | null = null
let referenceLayer: L.LayerGroup | null = null

function setupMap(): void {
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
  bandLayer = L.layerGroup().addTo(map)
  referenceLayer = L.layerGroup().addTo(map)
}

/** 过火分带填色层：遍历 detail.severityBands，用 areaType 取颜色画 L.polygon */
function redrawBands(): void {
  if (!bandLayer) return
  bandLayer.clearLayers()
  const d = detail.value
  if (!d) return
  for (const band of d.severityBands) {
    if (!band.geometry || band.geometry.length < 3) continue
    const color = SEVERITY_COLOR[band.areaType] ?? '#94a3b8'
    const poly = L.polygon(band.geometry as L.LatLngExpression[], {
      color,
      weight: 1,
      opacity: 0.9,
      fillColor: color,
      fillOpacity: 0.4,
    })
    poly.bindTooltip(
      `${ZH_SEVERITY[band.areaType] ?? band.areaType} · ${fmtHa(band.areaSquareMeter)} ha`,
      { sticky: true, direction: 'top' },
    )
    poly.addTo(bandLayer)
  }
}

/** 对比参考层：当前报告 LIGHT 外环白虚线（fill false，dashArray '4 4'） */
function redrawReference(): void {
  if (!referenceLayer) return
  referenceLayer.clearLayers()
  if (!showReferenceLayer.value) return
  const d = detail.value
  if (!d) return
  const light = d.severityBands.find((b) => b.areaType === 'LIGHT')
  if (!light?.geometry || light.geometry.length < 3) return
  L.polygon(light.geometry as L.LatLngExpression[], {
    color: '#ffffff',
    weight: 1.5,
    opacity: 0.9,
    dashArray: '4 4',
    fill: false,
  }).addTo(referenceLayer)
}

/* ---------------- 交互 ---------------- */

/** 点击报告卡片：选中 + 加载详情抽屉 + 飞到 LIGHT 外环 */
async function selectReport(report: AssessmentReport): Promise<void> {
  selectedReportId.value = report.reportId
  try {
    detail.value = await getAssessmentReportDetail(report.reportId)
    redrawBands()
    redrawReference()
    const light = detail.value?.severityBands.find((b) => b.areaType === 'LIGHT')
    if (map && light?.geometry && light.geometry.length >= 3) {
      const bounds = L.polygon(light.geometry as L.LatLngExpression[]).getBounds().pad(0.25)
      map.flyToBounds(bounds, { duration: 0.6 })
    }
  } catch (e) {
    pushToast('error', `报告详情加载失败：${(e as Error).message}`)
  }
}

function closeDetail(): void {
  selectedReportId.value = null
  detail.value = null
  redrawBands()
  redrawReference()
}

/** 生成评估报告：set assessing + postBurnedArea → toast → reloadReports */
async function onAssess(incident: { id: string; incidentNo: string }): Promise<void> {
  if (assessing.value !== null) return
  assessing.value = incident.id
  try {
    const summary = await postBurnedArea(incident.id)
    pushToast(
      'success',
      `评估完成：${summary.reportNo} · 过火 ${fmtHa(summary.burnedAreaSquareMeter)} ha`,
    )
    await loadReports()
  } catch (e) {
    pushToast('error', `评估失败：${(e as Error).message}`)
  } finally {
    assessing.value = null
  }
}

/* ---------------- 加载与轮询 ---------------- */

async function loadIncidents(): Promise<void> {
  try {
    incidents.value = parseIncidents(await getFireIncidents())
  } catch {
    /* 轮询容错：失败保留旧数据 */
  }
}

async function loadReports(): Promise<void> {
  try {
    reports.value = await getAssessmentReports()
  } catch {
    /* 轮询容错 */
  }
}

let reportsTimer: ReturnType<typeof setInterval> | undefined
let incidentsTimer: ReturnType<typeof setInterval> | undefined

function clearAllTimers(): void {
  if (reportsTimer) clearInterval(reportsTimer)
  if (incidentsTimer) clearInterval(incidentsTimer)
  reportsTimer = undefined
  incidentsTimer = undefined
}

onMounted(async () => {
  setupMap()
  await loadIncidents()
  await loadReports()
  reportsTimer = setInterval(loadReports, REPORTS_POLL_MS)
  incidentsTimer = setInterval(loadIncidents, INCIDENTS_POLL_MS)
})

onUnmounted(() => {
  clearAllTimers()
  map?.remove()
  map = null
})

/* ---------------- 单位换算 ---------------- */

/** m² → ha，保留 1 位小数 */
function fmtHa(m2: number | null | undefined): string {
  if (m2 == null) return '—'
  return (m2 / 10000).toFixed(1)
}

/** m 直接显示，整数 */
function fmtM(m: number | null | undefined): string {
  if (m == null) return '—'
  return m.toFixed(0)
}

/** 面积换算（同 fmtHa，语义化别名） */
function fmtArea(m2: number | null | undefined): string {
  return fmtHa(m2)
}

/** 严重度占比（单段面积 / 总面积） */
function severityPct(bandArea: number | null | undefined, total: number): number {
  if (!bandArea || total <= 0) return 0
  return Math.round((bandArea / total) * 100)
}

/** 严重度总面积 */
function severityTotal(): number {
  const d = detail.value
  if (!d) return 0
  return d.severityBands.reduce((acc, b) => acc + (b.areaSquareMeter ?? 0), 0)
}

/** ISO 时间 → 本地字符串 */
function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const t = Date.parse(iso.includes('T') ? iso : iso.replace(' ', 'T'))
  if (!Number.isFinite(t)) return '—'
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

/** 宽松读取事件等级（后端字段名不一，从 raw 取 level/fireLevel/severity 等） */
function levelOf(incident: { raw: Record<string, unknown> }): string {
  const v = pickFirst(incident.raw, 'level', 'fireLevel', 'severity', 'fireSeverity', 'riskLevel')
  if (v === undefined || v === null) return '—'
  return String(v)
}
</script>

<template>
  <div class="risk-layout">
    <!-- ① 地图 + 顶部状态条 -->
    <section class="risk-map-wrap">
      <div class="risk-toolbar">
        <span class="risk-summary">累计过火：{{ totalBurnedHa }} ha · {{ reports.length }} 份报告</span>
        <label class="risk-toggle">
          <input v-model="showReferenceLayer" type="checkbox" @change="redrawReference" />
          对比参考层
        </label>
      </div>
      <div ref="mapEl" class="risk-map"></div>
      <div class="risk-legend">
        <span v-for="(color, level) in SEVERITY_COLOR" :key="level" class="risk-legend-item">
          <i :style="{ background: color }"></i>{{ ZH_SEVERITY[level] }}
        </span>
        <span class="risk-legend-item"><i class="dash white"></i>LIGHT 外环</span>
      </div>
    </section>

    <!-- 右侧：③ 已处置事件 + ④ 评估报告 -->
    <aside class="risk-side">
      <div class="risk-panel">
        <div class="risk-panel-head">已处置完毕事件（{{ resolvedIncidents.length }}）</div>
        <div class="risk-panel-body">
          <p v-if="resolvedIncidents.length === 0" class="risk-empty">暂无已处置完毕的火情事件</p>
          <div v-for="inc in resolvedIncidents" :key="inc.id" class="risk-card">
            <div class="risk-card-top">
              <b>{{ inc.incidentNo }}</b>
              <span class="risk-status-chip" :class="`st-${inc.status}`">
                {{ ZH_STATUS[inc.status] ?? inc.status }}
              </span>
            </div>
            <div class="risk-card-meta">等级 {{ levelOf(inc) }}</div>
            <div class="risk-card-actions">
              <button
                v-if="!hasReport(inc.id)"
                class="risk-btn small primary"
                :disabled="assessing === inc.id"
                @click="onAssess(inc)"
              >
                {{ assessing === inc.id ? '评估中…' : '生成评估报告' }}
              </button>
              <span v-else class="risk-card-meta">已评估</span>
            </div>
          </div>
        </div>
      </div>

      <div class="risk-panel">
        <div class="risk-panel-head">
          评估报告（{{ reports.length }}）
          <small>30s 自动刷新</small>
        </div>
        <div class="risk-panel-body">
          <p v-if="reports.length === 0" class="risk-empty">暂无评估报告</p>
          <div
            v-for="r in reports"
            :key="r.reportId"
            class="risk-card flat"
            :class="{ selected: r.reportId === selectedReportId }"
            @click="selectReport(r)"
          >
            <div class="risk-card-top">
              <b>{{ r.reportNo }}</b>
              <span class="risk-level-chip" :style="{ '--c': '#f97316' }">
                {{ r.reportType ?? '—' }}
              </span>
            </div>
            <div class="risk-card-meta">
              过火 {{ fmtHa(r.burnedAreaSquareMeter) }}ha · 林地
              {{ fmtHa(r.affectedForestAreaSquareMeter) }}ha · 道路
              {{ fmtM(r.affectedRoadLengthMeter) }}m · 设施
              {{ fmtArea(r.affectedFacilityAreaSquareMeter) }}ha
            </div>
            <div class="risk-card-meta">{{ fmtTime(r.createdAt) }}</div>
          </div>
        </div>
      </div>
    </aside>

    <!-- 评估报告详情抽屉 -->
    <div v-if="detail" class="risk-drawer">
      <div class="risk-drawer-head">
        <b>{{ detail.reportNo }}</b>
        <button class="risk-drawer-close" @click="closeDetail">✕</button>
      </div>
      <div class="disaster-stat-grid">
        <div class="disaster-stat">
          <span class="num">{{ fmtHa(detail.burnedAreaSquareMeter) }}</span>
          <span class="lbl">过火面积 (ha)</span>
        </div>
        <div class="disaster-stat">
          <span class="num">{{ fmtHa(detail.affectedForestAreaSquareMeter) }}</span>
          <span class="lbl">受影响林地 (ha)</span>
        </div>
        <div class="disaster-stat">
          <span class="num">{{ fmtM(detail.affectedRoadLengthMeter) }}</span>
          <span class="lbl">受影响道路 (m)</span>
        </div>
        <div class="disaster-stat">
          <span class="num">{{ fmtArea(detail.affectedFacilityAreaSquareMeter) }}</span>
          <span class="lbl">受影响设施 (ha)</span>
        </div>
      </div>
      <div class="disaster-severity-bar">
        <div
          v-for="band in detail.severityBands"
          :key="band.areaType"
          class="disaster-severity-seg"
          :class="`severity-${band.areaType}`"
          :style="{ flex: Math.max(0, band.areaSquareMeter ?? 0) }"
          :title="`${ZH_SEVERITY[band.areaType] ?? band.areaType} · ${fmtHa(band.areaSquareMeter)} ha`"
        >
          <span v-if="severityPct(band.areaSquareMeter, severityTotal()) > 0">
            {{ severityPct(band.areaSquareMeter, severityTotal()) }}%
          </span>
        </div>
      </div>
      <div class="disaster-severity-legend">
        <span
          v-for="band in detail.severityBands"
          :key="band.areaType"
          class="risk-legend-item"
        >
          <i :class="`severity-${band.areaType}`"></i>
          {{ ZH_SEVERITY[band.areaType] ?? band.areaType }}
          {{ fmtHa(band.areaSquareMeter) }}ha
          ({{ severityPct(band.areaSquareMeter, severityTotal()) }}%)
        </span>
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
.risk-summary {
  font-size: 13px;
  color: #cbd5e1;
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
.risk-legend-item i.dash.white {
  border-color: #ffffff;
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
  margin-left: auto;
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
  cursor: pointer;
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
.risk-status-chip {
  font-size: 11px;
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.risk-status-chip.st-RESOLVED {
  color: #6ee7b7;
  border: 1px solid rgba(110, 231, 183, 0.6);
  background: rgba(110, 231, 183, 0.12);
}
.risk-status-chip.st-CLOSED {
  color: #94a3b8;
  border: 1px solid rgba(148, 163, 184, 0.4);
  background: rgba(148, 163, 184, 0.12);
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
  align-items: center;
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
  margin-bottom: 10px;
}
.risk-drawer-close {
  background: none;
  border: none;
  color: #94a3b8;
  font-size: 14px;
  cursor: pointer;
}

/* 灾后页新增样式：四项大数字 + 严重度条形图 */
.disaster-stat-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 12px;
}
.disaster-stat {
  display: flex;
  flex-direction: column;
  background: rgba(30, 41, 59, 0.6);
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  padding: 8px 10px;
}
.disaster-stat .num {
  font-size: 18px;
  font-weight: 600;
  color: #f1f5f9;
}
.disaster-stat .lbl {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}
.disaster-severity-bar {
  display: flex;
  height: 18px;
  border-radius: 6px;
  overflow: hidden;
  background: rgba(148, 163, 184, 0.15);
  margin-bottom: 8px;
}
.disaster-severity-seg {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #f1f5f9;
  font-weight: 600;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.disaster-severity-seg.severity-SEVERE {
  background: #7f1d1d;
}
.disaster-severity-seg.severity-MODERATE {
  background: #dc2626;
}
.disaster-severity-seg.severity-LIGHT {
  background: #f97316;
}
.disaster-severity-legend {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 11px;
  color: #cbd5e1;
}
.disaster-severity-legend i {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  display: inline-block;
}
.disaster-severity-legend i.severity-SEVERE {
  background: #7f1d1d;
}
.disaster-severity-legend i.severity-MODERATE {
  background: #dc2626;
}
.disaster-severity-legend i.severity-LIGHT {
  background: #f97316;
}
</style>
