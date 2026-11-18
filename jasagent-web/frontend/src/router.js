/** 网关管理控制台路由。登录和注册页直接放行。其它页面先确认 /api/me，未登录回到 /login。普通用户不能进入集群页。 */
import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import ConsoleView from './views/ConsoleView.vue'
import ClusterView from './views/ClusterView.vue'
import { api } from './api'

/**
 * 页面表。history 的 base 来自 Vite 的 /admin/，浏览器地址是 /admin/login、/admin/、/admin/cluster。
 * 接口仍在网关根路径，不放在 /admin 下面。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/register', component: RegisterView },
    { path: '/', component: ConsoleView },
    { path: '/cluster', component: ClusterView },
    { path: '/run', component: ConsoleView },
    { path: '/executions/:id', component: ConsoleView }
  ]
})

/**
 * 进入页面前检查登录。登录页和注册页直接放行。
 * 集群页还要角色是 admin，否则回到会话壳。
 *
 * @param to 目标路由
 * @return true 表示放行，字符串表示改去别的页
 */
router.beforeEach(async (to) => {
  if (to.path === '/login' || to.path === '/register') {
    return true
  }
  try {
    const me = await api('/api/me')
    if (to.path === '/cluster' && (!me || me.role !== 'admin')) {
      return '/'
    }
    return true
  } catch {
    return '/login'
  }
})

export default router
