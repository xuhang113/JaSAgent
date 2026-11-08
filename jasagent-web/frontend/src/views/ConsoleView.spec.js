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
        'el-button': {
          props: ['type', 'size', 'text', 'loading', 'nativeType'],
          template: '<button type="button" class="el-button" :class="[type && `el-button--${type}`, text && \'is-text\']"><slot /></button>'
        },
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

function buttonByText(wrapper, text) {
  const found = wrapper.findAll('.el-button').find((node) => node.text() === text)
  expect(found, text).toBeTruthy()
  return found
}

describe('会话页按钮对齐 llgraph', () => {
  it('主操作使用 cursor-btn-primary 的颜色、字号、圆角和内边距', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    for (const label of ['选定工作区', '新建', '发送', '提交执行', 'ALLOW']) {
      const button = buttonByText(wrapper, label)
      expect(button.classes()).toContain('cursor-btn-primary')
      expect(button.classes()).toContain('el-button')
    }
    const primary = rule(css, '.console-layout .el-button.cursor-btn-primary')
    expect(primary).toMatch(/background:\s*#1b1b18/)
    expect(primary).toMatch(/color:\s*#fff/)
    expect(primary).toMatch(/border:\s*none/)
    expect(primary).toMatch(/border-radius:\s*8px/)
    expect(primary).toMatch(/padding:\s*7px 10px/)
    expect(primary).toMatch(/font-size:\s*12px/)
    expect(primary).toMatch(/font-weight:\s*500/)
    expect(rule(css, '.console-layout .el-button.cursor-btn-primary:hover')).toMatch(/background:\s*#333\b/)
    wrapper.unmount()
  })

  it('次操作使用 cursor-btn-ghost', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    for (const label of ['退出', '追加纠正', '恢复', '刷新列表', '查询']) {
      expect(buttonByText(wrapper, label).classes()).toContain('cursor-btn-ghost')
    }
    const ghost = rule(css, '.console-layout .el-button.cursor-btn-ghost')
    expect(ghost).toMatch(/background:\s*transparent/)
    expect(ghost).toMatch(/border:\s*1px solid #c8c8c3/)
    expect(ghost).toMatch(/color:\s*#1b1b18/)
    expect(ghost).toMatch(/border-radius:\s*8px/)
    expect(ghost).toMatch(/padding:\s*7px 10px/)
    expect(ghost).toMatch(/font-size:\s*12px/)
    expect(rule(css, '.console-layout .el-button.cursor-btn-ghost:hover')).toMatch(/background:\s*#e3e3de/)
    wrapper.unmount()
  })

  it('危险操作使用 cursor-btn-danger，中止使用 composer stop', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    expect(buttonByText(wrapper, 'DENY').classes()).toContain('cursor-btn-danger')
    expect(buttonByText(wrapper, '中止').classes()).toContain('cursor-composer-stop')
    const danger = rule(css, '.console-layout .el-button.cursor-btn-danger')
    expect(danger).toMatch(/background:\s*#b42318/)
    expect(danger).toMatch(/color:\s*#fff/)
    expect(danger).toMatch(/border:\s*none/)
    expect(danger).toMatch(/border-radius:\s*8px/)
    expect(danger).toMatch(/padding:\s*7px 12px/)
    expect(danger).toMatch(/font-size:\s*12px/)
    expect(danger).toMatch(/font-weight:\s*500/)
    expect(rule(css, '.console-layout .el-button.cursor-btn-danger:hover')).toMatch(/background:\s*#912018/)
    expect(rule(css, '.console-layout .el-button.cursor-btn-danger:disabled')).toMatch(/opacity:\s*0\.5/)
    const stop = rule(css, '.console-layout .el-button.cursor-composer-stop')
    expect(stop).toMatch(/min-width:\s*52px/)
    expect(stop).toMatch(/height:\s*28px/)
    expect(stop).toMatch(/padding:\s*0 10px/)
    expect(stop).toMatch(/border-radius:\s*8px/)
    expect(stop).toMatch(/border:\s*1px solid #d4a5a5/)
    expect(stop).toMatch(/background:\s*#fff5f5/)
    expect(stop).toMatch(/color:\s*#b42318/)
    expect(stop).toMatch(/font-size:\s*12px/)
    expect(stop).toMatch(/font-weight:\s*500/)
    expect(rule(css, '.console-layout .el-button.cursor-composer-stop:hover')).toMatch(/background:\s*#ffe8e8/)
    wrapper.unmount()
  })

  it('会话删除按钮沿用列表删除的颜色和圆角，并保持可见', async () => {
    const wrapper = await mountConsole()
    const button = buttonByText(wrapper, '删除')
    expect(button.classes()).toContain('cursor-session-delete')
    const del = rule(css, '.console-layout .el-button.cursor-session-delete')
    expect(del).toMatch(/border:\s*none/)
    expect(del).toMatch(/border-radius:\s*4px/)
    expect(del).toMatch(/background:\s*transparent/)
    expect(del).toMatch(/color:\s*#8a8a84/)
    expect(del).toMatch(/height:\s*24px/)
    expect(del).not.toMatch(/opacity:\s*0\b/)
    expect(rule(css, '.console-layout .el-button.cursor-session-delete:hover')).toMatch(/background:\s*#e8d4d4/)
    expect(rule(css, '.console-layout .el-button.cursor-session-delete:hover')).toMatch(/color:\s*#b42318/)
    wrapper.unmount()
  })

  it('按钮间距去掉 Element Plus 的相邻外边距', () => {
    expect(rule(css, '.console-layout .el-button + .el-button')).toMatch(/margin-left:\s*0/)
    expect(rule(css, '.console-layout .row')).toMatch(/gap:\s*6px/)
    expect(rule(css, '.console-layout .pass-actions')).toMatch(/gap:\s*6px/)
  })

  it('会话行、子 agent 和 skill 按钮只改颜色、字号、圆角', () => {
    expect(rule(css, '.console-layout .session-switch')).toMatch(/font-size:\s*13px/)
    expect(rule(css, '.console-layout .session-switch')).toMatch(/color:\s*#1b1b18/)
    expect(rule(css, '.console-layout .session-switch')).toMatch(/padding:\s*6px 12px/)
    expect(rule(css, '.console-layout .session-row.active')).toMatch(/background:\s*#dcdcd7/)
    expect(rule(css, '.console-layout .session-row:hover')).toMatch(/background:\s*#e3e3de/)
    const agent = rule(css, '.console-layout .agent-row')
    expect(agent).toMatch(/border:\s*1px solid #c8c8c3/)
    expect(agent).toMatch(/border-radius:\s*8px/)
    expect(agent).toMatch(/padding:\s*7px 10px/)
    expect(agent).toMatch(/font-size:\s*12px/)
    expect(agent).toMatch(/color:\s*#1b1b18/)
    expect(rule(css, '.console-layout .agent-row:hover')).toMatch(/background:\s*#e3e3de/)
    const skill = rule(css, '.console-layout .skill-pop button')
    expect(skill).toMatch(/padding:\s*8px 12px/)
    expect(skill).toMatch(/font-size:\s*12px/)
    expect(skill).toMatch(/border-radius:\s*6px/)
    expect(skill).toMatch(/color:\s*#1b1b18/)
    expect(rule(css, '.console-layout .skill-pop button:hover')).toMatch(/background:\s*#f0f0ec/)
  })

  it('按钮样式不加入过渡或关键帧', () => {
    const at = css.indexOf('/* llgraph-buttons */')
    expect(at).toBeGreaterThanOrEqual(0)
    const block = css.slice(at)
    expect(block).not.toMatch(/transition\s*:/)
    expect(block).not.toMatch(/@keyframes/)
    expect(block).not.toMatch(/animation\s*:/)
  })

  it('通行证决定和中止仍走原来的接口', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    await buttonByText(wrapper, 'DENY').trigger('click')
    await buttonByText(wrapper, '中止').trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/api/executions/exec-1/passes/p1', {
      method: 'POST',
      body: { decision: 'DENY' }
    })
    expect(api).toHaveBeenCalledWith('/api/executions/exec-1/interrupt', {
      method: 'POST',
      body: {}
    })
    wrapper.unmount()
  })
})
