import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RegisterView from './RegisterView.vue'

vi.mock('../api', () => ({
  api: vi.fn()
}))

import { api } from '../api'

beforeEach(() => {
  api.mockReset()
})

/**
 * 挂上注册页和登录页的内存路由。
 *
 * @return 已挂载的包装器
 */
async function mountRegister() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/register', component: RegisterView },
      { path: '/login', component: { template: '<div>login</div>' } },
      { path: '/', component: { template: '<div data-shell>shell</div>' } }
    ]
  })
  await router.push('/register')
  await router.isReady()
  return mount(RegisterView, {
    global: {
      plugins: [router],
      stubs: {
        'el-card': { template: '<div><slot /></div>' },
        'el-form': { template: '<form @submit.prevent="$attrs.onSubmit"><slot /></form>' },
        'el-form-item': { template: '<label><slot /></label>' },
        'el-input': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        'el-button': { template: '<button type="submit"><slot /></button>' }
      }
    }
  })
}

describe('注册页', () => {
  it('注册成功后登录并进入会话壳，口令不出现在错误里', async () => {
    api.mockResolvedValue({})
    const wrapper = await mountRegister()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('ada')
    await inputs[1].setValue('secret-phrase')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api).toHaveBeenNthCalledWith(1, '/register', expect.objectContaining({ method: 'POST' }))
    expect(api).toHaveBeenNthCalledWith(2, '/login', expect.objectContaining({ method: 'POST' }))
    const registerBody = api.mock.calls[0][1].body
    expect(registerBody.get('user')).toBe('ada')
    expect(registerBody.get('password')).toBe('secret-phrase')
    expect(registerBody.has('tenant')).toBe(false)
    expect(wrapper.text()).not.toContain('secret-phrase')
    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/')
    wrapper.unmount()
  })

  it('失败时不回显口令', async () => {
    api.mockRejectedValue(Object.assign(new Error('secret-phrase'), { status: 409 }))
    const wrapper = await mountRegister()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('ada')
    await inputs[1].setValue('secret-phrase')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).not.toContain('secret-phrase')
    expect(wrapper.text()).toContain('注册失败')
    wrapper.unmount()
  })
})
