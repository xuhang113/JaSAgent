import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ConsoleView from './ConsoleView.vue'
import { MEDIA_ACCEPT } from '../sessionMedia'

vi.mock('../api', () => ({
  api: vi.fn()
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn()
  }
}))

import { api } from '../api'
import { ElMessage } from 'element-plus'

/** 接口替身里的附件。打开会话时由附件路径返回。 */
const state = {
  attachments: []
}

beforeEach(() => {
  state.attachments = [
    { attachmentId: 'img1', sessionId: 's1', mediaType: 'image/png', byteSize: 8, name: 'a.png' },
    { attachmentId: 'aud1', sessionId: 's1', mediaType: 'audio/wav', byteSize: 44, name: 'a.wav' },
    { attachmentId: 'vid1', sessionId: 's1', mediaType: 'video/mp4', byteSize: 20, name: 'a.mp4' },
    { attachmentId: 'bad1', sessionId: 's1', mediaType: 'image/svg+xml', byteSize: 8, name: 'a.svg' }
  ]
  api.mockReset()
  api.mockImplementation(async (path) => {
    if (path === '/api/me') {
      return { login: 'ada', workspace: '/ws', role: 'user', tenantId: 't-ada' }
    }
    if (path === '/api/workspaces') {
      return { workspaces: ['/ws'], workspace: '/ws' }
    }
    if (path === '/api/models') {
      return { defaultModel: 'deepseek-flash', models: [{ id: 'deepseek-flash', provider: 'deepseek', label: 'deepseek-flash' }] }
    }
    if (path === '/api/workflows') {
      return [{ id: 'answer-with-tool' }]
    }
    if (path === '/api/skills') {
      return []
    }
    if (path === '/api/sessions') {
      return [{ sessionId: 's1', title: '自己的会话' }]
    }
    if (String(path).endsWith('/attachments')) {
      return state.attachments
    }
    if (String(path).includes('/timeline')) {
      return { messages: [] }
    }
    if (String(path).endsWith('/context')) {
      return { rules: [], skills: [] }
    }
    if (String(path).startsWith('/api/sessions/')) {
      return { title: '自己的会话', messages: [], agents: [], corrections: [] }
    }
    return {}
  })
})

/**
 * 挂上会话页。控件替身只保留按钮和输入框。
 *
 * @return 包装器
 */
async function mountConsole() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: ConsoleView },
      { path: '/login', component: { template: '<div>login</div>' } }
    ]
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(ConsoleView, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        'el-button': { template: '<button type="button" class="el-button"><slot /></button>' },
        'el-input': { template: '<textarea class="el-input" />' },
        'el-select': { template: '<select class="el-select"><slot /></select>' },
        'el-option': { template: '<option><slot /></option>' },
        'el-tag': { template: '<span><slot /></span>' }
      }
    }
  })
  await flushPromises()
  return wrapper
}

describe('会话里的图片、语音和视频', () => {
  it('用网关路径展示图片并播放语音、视频', async () => {
    const wrapper = await mountConsole()
    await wrapper.get('.session-switch').trigger('click')
    await flushPromises()
    const image = wrapper.get('[data-kind="image"] img')
    const audio = wrapper.get('[data-kind="audio"] audio')
    const video = wrapper.get('[data-kind="video"] video')
    expect(image.attributes('src')).toBe('/api/sessions/s1/attachments/img1')
    expect(audio.attributes('src')).toBe('/api/sessions/s1/attachments/aud1')
    expect(video.attributes('src')).toBe('/api/sessions/s1/attachments/vid1')
    expect(audio.attributes('controls')).toBeDefined()
    expect(video.attributes('controls')).toBeDefined()
    expect(video.attributes('playsinline')).toBeDefined()
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.find('svg').exists()).toBe(false)
    for (const src of [image.attributes('src'), audio.attributes('src'), video.attributes('src')]) {
      expect(src.startsWith('/api/sessions/')).toBe(true)
      expect(src).not.toMatch(/\?|token|http:|https:/)
      expect(src).not.toContain('ts.' + 'net')
    }
    expect(wrapper.get('.composer-actions').find('[data-media-upload]').exists()).toBe(false)
    expect(wrapper.get('[data-media-upload]').text()).toBe('上传图片、语音或视频')
    expect(wrapper.get('[data-media-file]').attributes('accept')).toBe(MEDIA_ACCEPT)
    wrapper.unmount()
  })

  it('上传走登录 Cookie，地址上没有令牌', async () => {
    const wrapper = await mountConsole()
    await wrapper.get('.session-switch').trigger('click')
    await flushPromises()
    const fetchMock = vi.fn(async () => ({
      ok: true,
      text: async () => JSON.stringify({ attachmentId: 'new1', mediaType: 'image/png', byteSize: 3, name: 'photo.png' })
    }))
    vi.stubGlobal('fetch', fetchMock)
    const input = wrapper.get('[data-media-file]')
    const file = new File([new Uint8Array([1, 2, 3])], 'photo.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/sessions/s1/attachments')
    expect(options.method).toBe('POST')
    expect(options.credentials).toBe('same-origin')
    expect(options.body).toBeInstanceOf(FormData)
    expect(String(url)).not.toMatch(/\?|token|http:|https:/)
    expect(String(url)).not.toContain('ts.' + 'net')
    expect(ElMessage.error).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
    wrapper.unmount()
  })

  it('页面和样式不写历史入口', () => {
    const here = dirname(fileURLToPath(import.meta.url))
    const vue = readFileSync(resolve(here, './ConsoleView.vue'), 'utf8')
    const css = readFileSync(resolve(here, '../styles.css'), 'utf8')
    const banned = 'ts.' + 'net'
    expect(vue).not.toContain(banned)
    expect(css).not.toContain(banned)
    const block = css.slice(css.indexOf('/* llgraph-buttons */'))
    expect(block).not.toMatch(/transition\s*:/)
  })
})
