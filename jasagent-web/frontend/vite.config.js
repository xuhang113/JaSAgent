/** 前端构建和测试配置。产物写进 Java 静态资源目录，开发时代理到 18721。 */
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom'
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:18721' },
      '/login': { target: 'http://127.0.0.1:18721' },
      '/logout': { target: 'http://127.0.0.1:18721' },
      '/health': { target: 'http://127.0.0.1:18721' }
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
})
