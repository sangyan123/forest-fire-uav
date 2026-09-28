<script setup lang="ts">
import { fireStatusColor, relTimeText, zhFireStatus } from '../fire'
import type { FireIncident } from '../types'

defineProps<{
  incidents: FireIncident[]
  now: number
  selectedId: string | null
}>()

const emit = defineEmits<{ (e: 'select', id: string): void }>()

function chipStyle(inc: FireIncident): Record<string, string> {
  return { '--fire-color': fireStatusColor(inc.status) }
}
</script>

<template>
  <section class="card fire-card">
    <h3>
      火情事件
      <small v-if="incidents.length" class="fire-count">{{ incidents.length }}</small>
    </h3>
    <div v-if="incidents.length === 0" class="placeholder fire-empty">
      暂无火情事件——点击顶部按钮注入演示场景
    </div>
    <ul v-else class="fire-list">
      <li
        v-for="inc in incidents"
        :key="inc.id"
        :class="{ active: inc.id === selectedId }"
        @click="emit('select', inc.id)"
      >
        <div class="fire-line">
          <span class="chip fire-chip" :class="`fs-${inc.status || 'OTHER'}`" :style="chipStyle(inc)">
            <i class="fire-dot"></i>{{ zhFireStatus(inc.status) }}
          </span>
          <span class="fire-no">{{ inc.incidentNo }}</span>
        </div>
        <div class="fire-sub">
          置信度 {{ inc.confidence === null ? '—' : `${inc.confidence}%` }} · {{ relTimeText(inc.timeMs, now) }}
        </div>
      </li>
    </ul>
  </section>
</template>
