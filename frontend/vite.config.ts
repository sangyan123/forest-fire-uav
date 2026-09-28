import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端默认同源请求 /api 与 /simulator，本地 dev/preview 由此转发到对应容器端口
const proxy = {
  '/api': { target: 'http://localhost:8080', changeOrigin: true },
  '/simulator': { target: 'http://localhost:8002', changeOrigin: true },
}

export default defineConfig({
  plugins: [vue()],
  server: { proxy },
  preview: { proxy },
})
