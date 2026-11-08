import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
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
