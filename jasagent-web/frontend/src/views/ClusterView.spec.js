import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClusterView from './ClusterView.vue'

vi.mock('../api', () => ({
  api: vi.fn()
}))

import { api } from '../api'

beforeEach(() => {
  api.mockReset()
})

/**
 * 挂上集群页。按钮用替身，避免拉整套 Element Plus。
 *
 * @return 已挂到文档上的包装器
 */
async function mountCluster() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>home</div>' } },
      { path: '/cluster', component: ClusterView }
    ]
  })
  await router.push('/cluster')
  await router.isReady()
  const wrapper = mount(ClusterView, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        'el-button': {
          props: ['loading'],
          template: '<button type="button" class="el-button"><slot /></button>'
        }
      }
    }
  })
  await flushPromises()
  return wrapper
}

describe('集群页', () => {
  it('列出实例、地址、在线、长连接、粘着登录名和心跳', async () => {
    api.mockResolvedValue({
      agents: [
        {
          instanceId: 'agent-1',
          address: '10.0.0.1:19001',
          online: true,
          connectionAlive: true,
          stickyUsers: ['ada'],
          lastHeartbeat: '2026-11-12T13:00:00+08:00'
        }
      ]
    })
    const wrapper = await mountCluster()
    expect(api).toHaveBeenCalledWith('/api/cluster')
    const row = wrapper.get('[data-cluster-row]')
    expect(row.get('[data-instance]').text()).toBe('agent-1')
    expect(row.get('[data-address]').text()).toBe('10.0.0.1:19001')
    expect(row.get('[data-sticky]').text()).toBe('ada')
    expect(row.get('[data-heartbeat]').text()).toBe('2026-11-12T13:00:00+08:00')
    const marks = row.findAll('.cluster-mark')
    expect(marks[0].attributes('data-on')).toBe('true')
    expect(marks[1].attributes('data-on')).toBe('true')
    expect(row.text()).not.toContain('完成')
    wrapper.unmount()
  })

  it('长连接断开时第二个标记为关，不写完成', async () => {
    api.mockResolvedValue({
      agents: [
        {
          instanceId: 'agent-2',
          address: '10.0.0.2:19001',
          online: true,
          connectionAlive: false,
          stickyUsers: [],
          lastHeartbeat: ''
        }
      ]
    })
    const wrapper = await mountCluster()
    const marks = wrapper.get('[data-cluster-row]').findAll('.cluster-mark')
    expect(marks[0].attributes('data-on')).toBe('true')
    expect(marks[1].attributes('data-on')).toBe('false')
    expect(wrapper.get('[data-sticky]').text()).toBe('无')
    expect(wrapper.get('[data-heartbeat]').text()).toBe('无')
    expect(wrapper.text()).not.toContain('完成')
    wrapper.unmount()
  })

  it('接口失败时不填假 agent', async () => {
    api.mockRejectedValue(new Error('请求失败'))
    const wrapper = await mountCluster()
    expect(wrapper.get('.empty-line').text()).toBe('集群接口不可用')
    expect(wrapper.find('[data-cluster-row]').exists()).toBe(false)
    wrapper.unmount()
  })
})
