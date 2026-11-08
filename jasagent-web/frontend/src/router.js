import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import ConsoleView from './views/ConsoleView.vue'
import { api } from './api'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: ConsoleView },
    { path: '/run', component: ConsoleView },
    { path: '/executions/:id', component: ConsoleView }
  ]
})

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
