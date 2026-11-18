/** 网关管理控制台的构建和测试。产物只进 jasagent-web，由该进程作为上游提供。开发时代理到 18721。 */
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/** 页面挂在网关的 /admin/ 下。接口仍走网关根路径，由下面的 proxy 转到 18721。 */
const ADMIN_BASE = '/admin/'

/** 开发时页面接口要打到的网关地址。网关尚未单独监听时，这就是本机 18721。 */
const GATEWAY_ORIGIN = 'http://127.0.0.1:18721'

export default defineConfig({
  base: ADMIN_BASE,
  plugins: [vue()],
  test: {
    environment: 'happy-dom'
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': { target: GATEWAY_ORIGIN },
      '/login': { target: GATEWAY_ORIGIN },
      '/register': { target: GATEWAY_ORIGIN },
      '/logout': { target: GATEWAY_ORIGIN },
      '/health': { target: GATEWAY_ORIGIN }
    }
  },
  build: {
    outDir: '../target/classes/admin',
    emptyOutDir: true
  }
})
