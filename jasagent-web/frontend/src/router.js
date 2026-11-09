/** 控制台路由。除登录页外，先确认 /api/me，未登录就回到 /login。 */
import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import ConsoleView from './views/ConsoleView.vue'
import { api } from './api'

/** 页面表。登录页单独放，其余都进会话控制台。 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: ConsoleView },
    { path: '/run', component: ConsoleView },
    { path: '/executions/:id', component: ConsoleView }
  ]
})

/**
 * 进入页面前检查登录。登录页直接放行。
 *
 * @param to 目标路由
 * @return true 表示放行，字符串表示改去登录页
 */
router.beforeEach(async (to) => {
  if (to.path === '/login') {
    return true
  }
  try {
    await api('/api/me')
    return true
  } catch {
    return '/login'
  }
})

export default router
