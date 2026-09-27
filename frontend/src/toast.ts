import { reactive } from 'vue'

export type ToastType = 'success' | 'error' | 'info'

export interface ToastItem {
  id: number
  type: ToastType
  text: string
}

export const toasts = reactive<ToastItem[]>([])

let seq = 0

export function pushToast(type: ToastType, text: string, durationMs = 3200): void {
  const item: ToastItem = { id: ++seq, type, text }
  toasts.push(item)
  window.setTimeout(() => {
    const idx = toasts.indexOf(item)
    if (idx >= 0) toasts.splice(idx, 1)
  }, durationMs)
}
