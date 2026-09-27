<script setup lang="ts">
import { toasts } from '../toast'
</script>

<template>
  <div class="toast-host">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.type">{{ t.text }}</div>
    </TransitionGroup>
  </div>
</template>
