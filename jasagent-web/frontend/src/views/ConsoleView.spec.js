import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ConsoleView from './ConsoleView.vue'

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

const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../styles.css'), 'utf8')

const state = {
  sessions: [],
  session: {},
  timeline: { messages: [] },
  execution: {},
  context: {}
}

function rule(source, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matched = source.match(new RegExp(escaped + '\\s*\\{([^}]*)\\}'))
  expect(matched, selector).toBeTruthy()
  return matched[1]
}

function resetState() {
  state.sessions = [{ sessionId: 's1', title: '检查会话' }]
  state.session = {
    title: '检查会话',
    messages: [{ role: 'user', text: '请给出时间', executionId: 'exec-1', status: 'SUSPENDED', output: '' }],
    agents: [{ agentId: 'sub-1', role: 'SUB', status: 'QUEUED', executionId: 'exec-1' }],
    corrections: [{ noteId: 'n1', source: 'USER', text: '不要编造数字' }]
  }
  state.timeline = {
    executionId: 'exec-1',
    messages: [
      { type: 'system', content: 'rules', rules: [], skills: [] },
      { type: 'human', content: '请给出时间' },
      { type: 'tool', name: 'clock', tool_call_id: 'clock-1', content: '2026-11-08T00:00:00Z' },
      { type: 'ai', content: '模型未配置', tool_calls: [] }
    ]
  }
  state.execution = {
    status: 'SUSPENDED',
    output: '',
    logs: 'pass rag.search PENDING',
    tools: ['clock', 'rag.search'],
    plugins: ['grounding.default'],
    passes: [{ passId: 'p1', name: 'rag.search', kind: 'tool', decision: 'PENDING', reason: 'approval-required' }],
    retrievals: [],
    evaluations: []
  }
  state.context = {
    budgetTokens: 8000,
    compressAt: 0.85,
    rules: [],
    skills: []
  }
}

beforeEach(() => {
  resetState()
  api.mockReset()
  api.mockImplementation(async (path, options = {}) => {
    const method = options.method || 'GET'
    if (path === '/api/me') {
      return { login: 'admin', workspace: '/ws' }
    }
    if (path === '/api/workspaces') {
      return { workspaces: ['/ws'], workspace: '/ws' }
    }
    if (path === '/api/workflows') {
      return [{ id: 'answer-with-tool' }]
    }
    if (path === '/api/skills') {
      return [{ name: 'research', description: '查找公开资料' }]
    }
    if (path === '/api/workspace') {
      return { workspace: '/ws' }
    }
    if (String(path).includes('/timeline')) {
      return state.timeline
    }
    if (String(path).endsWith('/context')) {
      return state.context
    }
    if (String(path).includes('/corrections')) {
      return { noteId: 'n1', source: 'USER', text: 'ok' }
    }
    if (String(path).includes('/messages')) {
      return { role: 'user', text: '请给出时间', executionId: 'exec-1' }
    }
    if (path === '/api/sessions' && method === 'POST') {
      return { sessionId: 's-new', title: '新会话 1' }
    }
    if (path === '/api/sessions') {
      return state.sessions
    }
    if (String(path).startsWith('/api/sessions/')) {
      return state.session
    }
    if (String(path).endsWith('/logs')) {
      return { logs: state.execution.logs || '' }
    }
    if (String(path).includes('/passes/') || String(path).endsWith('/interrupt') || String(path).endsWith('/resume')) {
      return state.execution
    }
    if (path === '/api/executions' && method === 'POST') {
      return { executionId: 'exec-1' }
    }
    if (String(path).startsWith('/api/executions/')) {
      return state.execution
    }
    return {}
  })
})

async function mountConsole() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: ConsoleView }]
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
        'el-select': { template: '<div class="el-select workspace-select"><slot /></div>' },
        'el-option': { template: '<div class="el-option" />' },
        'el-tag': { template: '<span class="el-tag"><slot /></span>' }
      }
    }
  })
  await flushPromises()
  return wrapper
}

async function openSession(wrapper) {
  await wrapper.get('.session-switch').trigger('click')
  await flushPromises()
}

describe('会话页三列布局', () => {
  it('左栏放工作区和会话，中间是消息，右栏默认展开', async () => {
    const wrapper = await mountConsole()
    const root = wrapper.get('.console-layout')
    expect(root.attributes('data-panels-open')).toBe('true')
    expect(root.attributes('data-default-panels')).not.toBe('hidden')
    expect(wrapper.find('.topbar').exists()).toBe(false)

    const side = wrapper.get('.session-column')
    expect(side.text()).toContain('工作区')
    expect(side.text()).toContain('选定工作区')
    expect(side.text()).toContain('会话')
    expect(side.find('.session-switch').exists()).toBe(true)

    expect(wrapper.get('.session-main .messages').exists()).toBe(true)
    expect(wrapper.get('.session-main .composer-dock').exists()).toBe(true)

    const rail = wrapper.get('.right-rail')
    for (const label of ['Trace', '工具', '上下文', '日志', '通行证']) {
      expect(rail.text()).toContain(label)
    }
    wrapper.unmount()
  })

  it('输入框排在消息区之后，并钉在中间列', async () => {
    const wrapper = await mountConsole()
    const main = wrapper.get('.session-main')
    const html = main.element.innerHTML
    expect(html.indexOf('class="messages"')).toBeGreaterThanOrEqual(0)
    expect(html.indexOf('class="messages"')).toBeLessThan(html.indexOf('class="composer-dock"'))
    expect(rule(css, '.session-main')).toMatch(/flex-direction:\s*column/)
    expect(rule(css, '.messages')).toMatch(/flex:\s*1/)
    expect(rule(css, '.messages')).not.toMatch(/max-height/)
    expect(rule(css, '.messages')).not.toMatch(/180px/)
    expect(rule(css, '.composer-dock')).toMatch(/flex:\s*none/)
    wrapper.unmount()
  })

  it('三列都占满视口高度', () => {
    expect(rule(css, '.console-layout')).toMatch(/height:\s*100vh/)
    expect(rule(css, '.console-layout')).toMatch(/grid-template-columns:[^;]*260px/)
    expect(rule(css, '.console-layout')).toMatch(/320px/)
    expect(rule(css, '.session-column')).toMatch(/height:\s*100%/)
    expect(rule(css, '.session-main')).toMatch(/height:\s*100%/)
    expect(rule(css, '.right-rail')).toMatch(/height:\s*100%/)
    expect(rule(css, '.right-rail')).toMatch(/overflow-x:\s*hidden/)
  })

  it('工具结果折进这一轮，右侧 Trace 再展示一次', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const turn = wrapper.get('.session-main .turn')
    const html = turn.element.innerHTML
    const userAt = html.indexOf('请给出时间')
    const toolAt = html.indexOf('2026-11-08T00:00:00Z')
    const replyAt = html.indexOf('模型未配置')
    expect(userAt).toBeGreaterThanOrEqual(0)
    expect(toolAt).toBeGreaterThan(userAt)
    expect(replyAt).toBeGreaterThan(toolAt)
    expect(turn.get('.turn-trace summary').text()).toContain('Trace')
    expect(turn.get('.turn-trace').text()).toContain('clock')
    expect(wrapper.get('.right-rail [data-timeline="langgraph"]').text()).toContain('2026-11-08T00:00:00Z')
    wrapper.unmount()
  })

  it('通行证的允许和拒绝在右栏里，不靠横向表格', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const card = wrapper.get('.right-rail .pass-card')
    expect(card.text()).toContain('rag.search')
    expect(card.text()).toContain('ALLOW')
    expect(card.text()).toContain('DENY')
    expect(wrapper.find('.pass-list table').exists()).toBe(false)
    expect(wrapper.find('.pass-list .el-table').exists()).toBe(false)
    expect(rule(css, '.pass-actions')).toMatch(/flex-wrap:\s*wrap/)
    wrapper.unmount()
  })

  it('子 agent 和纠正 note 留在左栏，不进消息区', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const meta = wrapper.get('.session-column .session-meta')
    expect(meta.text()).toContain('子 agent')
    expect(meta.text()).toContain('sub-1')
    expect(meta.text()).toContain('纠正 note')
    expect(meta.text()).toContain('harness note')
    expect(meta.text()).toContain('不要编造数字')
    const messages = wrapper.get('.messages')
    expect(messages.text()).not.toContain('sub-1')
    expect(messages.text()).not.toContain('不要编造数字')
    expect(messages.text()).not.toContain('harness note')
    expect(rule(css, '.session-meta')).not.toMatch(/position:\s*(absolute|fixed)/)
    wrapper.unmount()
  })
})
