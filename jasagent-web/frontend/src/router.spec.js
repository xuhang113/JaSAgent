import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./api', () => ({
  api: vi.fn()
}))

import router from './router'
import { api } from './api'

beforeEach(async () => {
  api.mockReset()
  await router.push('/login')
})

describe('会话壳路由', () => {
  it('注册页不要求已经登录', async () => {
    await router.push('/register')
    expect(api).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/register')
  })

  it('普通用户打开集群页时回到会话壳', async () => {
    api.mockResolvedValue({ login: 'ada', role: 'user', tenantId: 't1' })
    await router.push('/cluster')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('admin 可以打开集群页', async () => {
    api.mockResolvedValue({ login: 'ada', role: 'admin', tenantId: 't1' })
    await router.push('/cluster')
    expect(router.currentRoute.value.path).toBe('/cluster')
  })

  it('未登录访问会话壳时回到登录页', async () => {
    api.mockRejectedValue(Object.assign(new Error('AUTH'), { status: 401 }))
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('没有把 Jaeger 注册成页面路由', () => {
    expect(router.getRoutes().some((route) => String(route.path).includes('jaeger'))).toBe(false)
  })
})
