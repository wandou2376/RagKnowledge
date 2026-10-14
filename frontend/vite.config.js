import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端默认跑在 8080；本地换端口调试时用环境变量覆盖，不去改后端配置
const backend = process.env.VITE_BACKEND ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 后端没有配 CORS，跨域统一由开发服务器的代理解决
      '/api': {
        target: backend,
        changeOrigin: true
      }
    }
  }
})
