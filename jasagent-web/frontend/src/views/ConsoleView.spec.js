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
  context: {},
  models: []
}

function rule(source, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matched = source.match(new RegExp(escaped + '\\s*\\{([^}]*)\\}'))
  expect(matched, selector).toBeTruthy()
  return matched[1]
}

function blocks(source, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return [...source.matchAll(new RegExp(escaped + '\\s*\\{([^}]*)\\}', 'g'))].map((match) => match[1])
}

function declaresVisualChrome(body) {
  return /(?:^|;)(?:color|background(?:-color)?|font-size|border-radius|padding|margin|gap)\s*:/.test(body)
}

function resetState() {
  state.sessions = [{ sessionId: 's1', title: '检查会话', model: 'deepseek-flash', modelProvider: 'deepseek' }]
  state.models = [
    { id: 'deepseek-flash', provider: 'deepseek', label: 'deepseek-flash' },
    { id: 'deepseek-v4-pro', provider: 'deepseek', label: 'deepseek-v4-pro' }
  ]
  state.session = {
    title: '检查会话',
    model: 'deepseek-flash',
    modelProvider: 'deepseek',
    messages: [{ role: 'user', text: '请给出时间', executionId: 'exec-1', status: 'SUSPENDED', output: '' }],
    agents: [
      { agentId: 'sub-1', role: 'SUB', status: 'RUNNING', executionId: 'exec-live' },
      { agentId: 'sub-old', role: 'SUB', status: 'QUEUED', executionId: 'exec-1' },
      { agentId: 'sub-done', role: 'SUB', status: 'SUCCEEDED', executionId: 'exec-done' }
    ],
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
    if (path === '/api/models') {
      return { defaultModel: 'deepseek-flash', models: state.models }
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
      return { sessionId: 's-new', title: '新会话' }
    }
    if (path === '/api/sessions') {
      return state.sessions
    }
    if (method === 'PATCH' && String(path).startsWith('/api/sessions/')) {
      const sessionId = String(path).split('/').pop()
      if (options.body && options.body.model) {
        state.session = {
          ...state.session,
          model: options.body.model,
          modelProvider: options.body.modelProvider || 'deepseek'
        }
        state.sessions = state.sessions.map((item) => item.sessionId === sessionId ? { ...item, ...state.session } : item)
        return { sessionId, title: state.session.title, model: state.session.model, modelProvider: state.session.modelProvider }
      }
      const title = options.body && options.body.title
      state.sessions = state.sessions.map((item) => item.sessionId === sessionId ? { ...item, title } : item)
      state.session = { ...state.session, title }
      return { sessionId, title }
    }
    if (String(path).startsWith('/api/sessions/')) {
      const sessionId = String(path).split('/').pop()
      if (sessionId === 's2') {
        return {
          title: '另一会话',
          model: 'deepseek-v4-pro',
          modelProvider: 'deepseek',
          messages: [],
          agents: [],
          corrections: []
        }
      }
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
        'el-input': {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<textarea class="el-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        'el-select': {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue', 'change'],
          template: '<select class="el-select" :disabled="disabled" :value="modelValue" @change="onChange($event)"><slot /></select>',
          methods: {
            onChange(event) {
              this.$emit('update:modelValue', event.target.value)
              this.$emit('change', event.target.value)
            }
          }
        },
        'el-option': {
          props: ['label', 'value'],
          template: '<option :value="value">{{ label }}</option>'
        },
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

async function openDrawer(wrapper, name) {
  await wrapper.get('[data-drawer-menu]').trigger('click')
  await wrapper.get(`[role="menu"] [data-open="${name}"]`).trigger('click')
  await flushPromises()
}

describe('会话页三列布局', () => {
  it('左栏放工作区和会话，中间是消息，右栏默认是任务', async () => {
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
    expect(wrapper.get('.chat-head').classes()).toContain('chrome-bar')
    expect(wrapper.get('.sidebar-top').classes()).toContain('chrome-bar')
    expect(wrapper.find('.agent-dock').exists()).toBe(false)
    expect(rail.findAll('[role="tab"]').map((tab) => tab.text())).toEqual(['任务', 'Trace', '工具', '上下文', '日志'])
    expect(rail.get('[role="tab"][aria-selected="true"]').text()).toBe('任务')
    expect(rail.get('[role="tab"][aria-selected="true"]').attributes('data-tab')).toBe('tasks')
    expect(rail.get('[data-pane="tasks"] .empty-line').text()).toBe('未选会话。')
    expect(rail.find('[data-pane="trace"]').exists()).toBe(false)
    expect(rail.find('[data-pane="logs"]').exists()).toBe(false)
    expect(rail.find('[data-pane="tools"]').exists()).toBe(false)
    expect(rail.find('[data-pane="context"]').exists()).toBe(false)
    expect(rule(css, '.rail-tab[aria-selected="true"]')).toMatch(/background:\s*#1b1b18/)
    expect(rule(css, '.rail-tab[aria-selected="true"]')).toMatch(/color:\s*#fff/)
    expect(rail.find('.rail-summary').exists()).toBe(false)
    expect(rail.find('details.rail-block').exists()).toBe(false)
    expect(rail.find('.rail-toolbar').exists()).toBe(false)
    expect(rail.find('[data-open]').exists()).toBe(false)
    expect(rail.find('h2').exists()).toBe(false)
    expect(rail.find('.console-drawer').exists()).toBe(false)
    for (const label of ['溯源', '评估', '通行证', 'CLI', 'SSE']) {
      expect(rail.text()).not.toContain(label)
    }
    wrapper.unmount()
  })

  it('右栏 tab 一次只显示一栏，抽屉从菜单打开', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const rail = wrapper.get('.right-rail')
    expect(rail.get('[role="tabpanel"]').attributes('data-pane')).toBe('tasks')
    expect(rail.find('[data-pane="trace"]').exists()).toBe(false)
    expect(rail.find('[data-pane="logs"]').exists()).toBe(false)
    expect(rail.findAll('[data-task-group]').map((group) => group.attributes('data-task-group'))).toEqual(['active', 'review', 'done'])
    expect(rail.get('[data-task-group="active"] .task-group-label').text()).toBe('进行中')
    expect(rail.get('[data-task-group="review"] .task-group-label').text()).toBe('待看')
    expect(rail.get('[data-task-group="done"] .task-group-label').text()).toBe('完成')
    expect(rail.get('[data-task-group="active"] .task-title').text()).toBe('sub-1')
    expect(rail.get('[data-task-group="review"] .task-title').text()).toBe('请给出时间')
    expect(rail.get('[data-task-group="done"] .task-title').text()).toBe('sub-done')
    expect(rail.find('.task-summary').exists()).toBe(false)
    await rail.get('[data-task-group="review"] .task-row').trigger('click')
    const summary = rail.get('.task-summary')
    expect(summary.text()).toContain('sub-old')
    expect(summary.text()).toContain('SUSPENDED')
    expect(summary.text()).toContain('exec-1')
    expect(rail.get('[data-pane="tasks"]').text()).not.toContain('2026-11-08T00:00:00Z')
    expect(rail.get('[data-task-group="review"] .task-row').attributes('aria-expanded')).toBe('true')

    await rail.get('[data-tab="trace"]').trigger('click')
    expect(rail.get('[role="tab"][aria-selected="true"]').text()).toBe('Trace')
    expect(rail.find('[data-pane="tasks"]').exists()).toBe(false)
    expect(rail.get('[data-pane="trace"]').text()).toContain('2026-11-08T00:00:00Z')
    expect(rail.get('[data-pane="trace"]').text()).not.toContain('溯源')
    expect(rail.get('[data-pane="trace"]').text()).not.toContain('评估')

    await rail.get('[data-tab="tools"]').trigger('click')
    expect(rail.get('[role="tab"][aria-selected="true"]').text()).toBe('工具')
    expect(rail.find('[data-pane="trace"]').exists()).toBe(false)
    const tools = rail.get('[data-pane="tools"]')
    expect(tools.text()).toContain('clock')
    expect(tools.text()).toContain('grounding.default')
    expect(tools.text()).not.toContain('提交执行')
    expect(tools.find('.pane-kicker').exists()).toBe(false)
    expect(tools.find('h2').exists()).toBe(false)

    await rail.get('[data-tab="context"]').trigger('click')
    const context = rail.get('[data-pane="context"]')
    expect(context.get('.mono-num').text()).toContain('8000')
    expect(context.text()).toContain('0.85')

    await rail.get('[data-tab="logs"]').trigger('click')
    const logs = rail.get('[data-pane="logs"]')
    expect(logs.text()).not.toContain('SSE')
    expect(logs.get('[data-stream="sse"]').attributes('aria-label')).toBe('SSE')
    expect(logs.text()).toContain('pass rag.search PENDING')
    expect(logs.text()).not.toContain('CLI')
    expect(logs.text()).not.toContain('提交执行')
    expect(logs.find('.pane-kicker').exists()).toBe(false)

    await openDrawer(wrapper, 'retrieval')
    expect(wrapper.get('.console-drawer[data-drawer="retrieval"]').text()).toContain('无溯源')
    await openDrawer(wrapper, 'evaluation')
    expect(wrapper.get('.console-drawer[data-drawer="evaluation"]').text()).toContain('无评估')
    expect(wrapper.find('.console-drawer[data-drawer="retrieval"]').exists()).toBe(false)
    await openDrawer(wrapper, 'cli')
    expect(wrapper.get('.console-drawer[data-drawer="cli"]').text()).toContain('提交执行')
    expect(wrapper.get('.right-rail').text()).toContain('CLI')
    await wrapper.get('.console-drawer [data-close]').trigger('click')
    expect(wrapper.find('.console-drawer').exists()).toBe(false)
    expect(wrapper.get('.right-rail').text()).not.toContain('CLI')
    wrapper.unmount()
  })

  it('空状态是短句', async () => {
    state.timeline = { executionId: 'exec-1', messages: [] }
    state.execution = {
      status: '',
      output: '',
      logs: '',
      tools: [],
      plugins: [],
      passes: [],
      retrievals: [],
      evaluations: []
    }
    state.session = { title: '检查会话', messages: [], agents: [], corrections: [] }
    const wrapper = await mountConsole()
    await openSession(wrapper)
    expect(wrapper.get('[data-pane="tasks"] .empty-line').text()).toBe('无任务。')
    expect(wrapper.get('.agent-live .empty-line').text()).toBe('无正在执行。')
    await wrapper.get('[data-agent-list]').trigger('click')
    expect(wrapper.get('[role="listbox"] .empty-line').text()).toBe('无子 agent。')
    await wrapper.get('[data-tab="trace"]').trigger('click')
    expect(wrapper.get('[data-pane="trace"] .empty-line').text()).toBe('无 Trace。')
    await wrapper.get('[data-tab="tools"]').trigger('click')
    expect(wrapper.findAll('[data-pane="tools"] .empty-line').map((node) => node.text())).toEqual(['无工具。', '无插件。'])
    await openDrawer(wrapper, 'passes')
    expect(wrapper.get('.console-drawer .empty-line').text()).toBe('无通行证。')
    expect(wrapper.text()).not.toMatch(/还没有/)
    wrapper.unmount()
  })

  it('页面空状态不写还没有', () => {
    const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), './ConsoleView.vue'), 'utf8')
    expect(source).not.toMatch(/还没有/)
    expect(source).not.toContain('harness note')
    expect(source).not.toContain('纠正 note')
    expect(source).not.toContain('无消息。')
  })

  it('空会话在中部用一句说明', async () => {
    state.timeline = { executionId: 'exec-1', messages: [] }
    state.session = {
      title: '检查会话',
      model: 'deepseek-flash',
      modelProvider: 'deepseek',
      messages: [],
      agents: [],
      corrections: []
    }
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const thread = wrapper.get('.thread.is-empty')
    expect(thread.get('.empty-thread').text()).toBe('在下面输入，开始这一会话。')
    expect(thread.text()).not.toContain('无消息')
    expect(rule(css, '.messages')).toMatch(/display:\s*flex/)
    expect(rule(css, '.thread.is-empty')).toMatch(/justify-content:\s*center/)
    expect(rule(css, '.thread.is-empty')).toMatch(/align-items:\s*center/)
    expect(rule(css, '.empty-thread')).toMatch(/text-align:\s*center/)
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

  it('工具结果折进这一轮，点开 Trace 后再展示一次', async () => {
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
    expect(wrapper.find('.right-rail [data-timeline="langgraph"]').exists()).toBe(false)
    await wrapper.get('.right-rail [data-tab="trace"]').trigger('click')
    expect(wrapper.get('.right-rail [data-timeline="langgraph"]').text()).toContain('2026-11-08T00:00:00Z')
    wrapper.unmount()
  })

  it('通行证的允许和拒绝在抽屉里，不靠横向表格', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    expect(wrapper.find('.right-rail .pass-card').exists()).toBe(false)
    await openDrawer(wrapper, 'passes')
    const card = wrapper.get('.console-drawer[data-drawer="passes"] .pass-card')
    expect(card.text()).toContain('rag.search')
    expect(card.text()).toContain('ALLOW')
    expect(card.text()).toContain('DENY')
    expect(wrapper.find('.pass-list table').exists()).toBe(false)
    expect(wrapper.find('.pass-list .el-table').exists()).toBe(false)
    expect(rule(css, '.pass-actions')).toMatch(/flex-wrap:\s*wrap/)
    wrapper.unmount()
  })

  it('正在执行的子 agent 挂在对话上方，历史的要打开列表才看见', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const main = wrapper.get('.session-main')
    const html = main.element.innerHTML
    expect(html.indexOf('agent-dock')).toBeGreaterThanOrEqual(0)
    expect(html.indexOf('class="messages"')).toBeGreaterThan(html.indexOf('agent-dock'))
    const live = wrapper.get('.agent-live')
    expect(live.text()).toContain('sub-1')
    expect(live.text()).not.toContain('sub-old')
    expect(live.text()).not.toContain('sub-done')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    const meta = wrapper.get('.session-column .session-meta')
    expect(meta.text()).not.toContain('sub-1')
    expect(meta.text()).not.toContain('sub-old')
    expect(meta.text()).toContain('纠正')
    expect(meta.text()).toContain('追加纠正')
    expect(meta.text()).toContain('不要编造数字')
    expect(meta.text()).not.toContain('harness')
    expect(meta.text()).not.toContain('纠正 note')
    const messages = wrapper.get('.messages')
    expect(messages.text()).not.toContain('sub-1')
    expect(messages.text()).not.toContain('sub-old')
    expect(messages.text()).not.toContain('不要编造数字')
    expect(messages.text()).not.toContain('harness note')
    expect(rule(css, '.session-meta')).not.toMatch(/position:\s*(absolute|fixed)/)
    expect(rule(css, '.agent-dock')).not.toMatch(/position:\s*(absolute|fixed)/)
    await wrapper.get('[data-agent-list]').trigger('click')
    const list = wrapper.get('[role="listbox"]')
    expect(list.attributes('aria-label')).toBe('子 agent')
    expect(wrapper.get('[data-agent-list]').attributes('aria-expanded')).toBe('true')
    for (const id of ['sub-1', 'sub-old', 'sub-done']) {
      expect(list.text()).toContain(id)
    }
    wrapper.unmount()
  })

  it('任务行和子 agent 列表保持浅色和 8px 圆角', () => {
    expect(rule(css, '.task-row')).toMatch(/border-radius:\s*8px/)
    expect(rule(css, '.task-row')).toMatch(/color:\s*#1b1b18/)
    expect(rule(css, '.task-title')).toMatch(/text-overflow:\s*ellipsis/)
    expect(rule(css, '.task-title')).toMatch(/white-space:\s*nowrap/)
    expect(rule(css, '.agent-listbox')).toMatch(/border-radius:\s*8px/)
    expect(rule(css, '.agent-listbox')).toMatch(/background:\s*#fff/)
    expect(rule(css, '.right-rail')).not.toMatch(/background:\s*#000/)
    expect(css).not.toMatch(/neon|scanline|#00ffff|#39ff14/)
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
    for (const label of ['选定工作区', '新建', '发送']) {
      const button = buttonByText(wrapper, label)
      expect(button.classes()).toContain('cursor-btn-primary')
      expect(button.classes()).toContain('el-button')
    }
    await openDrawer(wrapper, 'cli')
    expect(buttonByText(wrapper, '提交执行').classes()).toContain('cursor-btn-primary')
    await openDrawer(wrapper, 'passes')
    expect(buttonByText(wrapper, 'ALLOW').classes()).toContain('cursor-btn-primary')
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
    for (const label of ['退出', '追加纠正', '恢复']) {
      expect(buttonByText(wrapper, label).classes()).toContain('cursor-btn-ghost')
    }
    await openDrawer(wrapper, 'cli')
    for (const label of ['刷新列表', '查询']) {
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
    await openDrawer(wrapper, 'passes')
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
    expect(rule(css, '.console-layout .session-switch')).toMatch(/padding:\s*0 8px/)
    expect(rule(css, '.console-layout .session-switch')).toMatch(/display:\s*flex/)
    expect(rule(css, '.console-layout .session-title')).toMatch(/white-space:\s*nowrap/)
    expect(rule(css, '.console-layout .session-title')).toMatch(/text-overflow:\s*ellipsis/)
    expect(rule(css, '.console-layout .session-row')).toMatch(/height:\s*28px/)
    expect(rule(css, '.console-layout .session-tools')).toMatch(/height:\s*28px/)
    expect(rule(css, '.console-layout .session-rename')).toMatch(/height:\s*22px/)
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
    await openDrawer(wrapper, 'passes')
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

describe('会话页交互动效', () => {
  it('悬停 0.15s，按压 0.1s，不改按钮颜色字号圆角和间距', () => {
    const row = blocks(css, '.session-row').join('\n')
    expect(row).toMatch(/background-color 0\.15s ease/)
    expect(row).toMatch(/border-color 0\.15s ease/)
    expect(blocks(css, '.session-row:hover:not(.active)').join('\n')).toMatch(/background-color:\s*#f2f4f7/)
    expect(blocks(css, '.split-handle').join('\n')).toMatch(/background-color 0\.15s ease/)
    expect(blocks(css, '.split-handle.is-active').join('\n')).toMatch(/background:\s*#c8c8c3/)

    for (const selector of ['.session-switch', '.agent-row', '.skill-pop button']) {
      const body = blocks(css, selector).join('\n')
      expect(body, selector).toMatch(/0\.15s ease/)
      expect(body, selector).toMatch(/transform 0\.1s ease/)
      expect(blocks(css, selector + ':hover').join('\n'), selector).toMatch(/transform:\s*translateY\(-1px\)/)
      expect(blocks(css, selector + ':active').join('\n'), selector).toMatch(/transform:\s*translateY\(0\)/)
    }

    const buttonMotion = blocks(css, '.console-layout .el-button').join('\n')
    expect(buttonMotion).toMatch(/color 0\.15s ease/)
    expect(buttonMotion).toMatch(/background-color 0\.15s ease/)
    expect(buttonMotion).toMatch(/border-color 0\.15s ease/)
    expect(buttonMotion).not.toMatch(/transform/)
    for (const selector of [
      '.console-layout .el-button',
      '.console-layout .el-button:hover:not(.is-disabled):not(:disabled)',
      '.console-layout .el-button:active:not(.is-disabled):not(:disabled)'
    ]) {
      expect(declaresVisualChrome(blocks(css, selector).join('\n')), selector).toBe(false)
    }
    expect(rule(css, '.skill-pop button:hover')).toMatch(/background:\s*#f2f4f7/)
  })

  it('列宽展开是 0.2s，拖动时立刻跟上', () => {
    expect(blocks(css, '.console-layout').join('\n')).toMatch(/grid-template-columns 0\.2s ease/)
    expect(blocks(css, '.console-layout.is-resizing').join('\n')).toMatch(/transition:\s*none/)
  })

  it('折叠的 Trace 用 0.2s 展开，右栏改用 tab 指示', () => {
    const fold = blocks(css, '.turn-trace::details-content').join('\n')
    expect(fold).toMatch(/height 0\.2s ease/)
    expect(fold).toMatch(/opacity 0\.2s ease/)
    expect(blocks(css, '.turn-trace[open]::details-content').join('\n')).toMatch(/height:\s*auto/)
    expect(blocks(css, '.turn-trace[open]::details-content').join('\n')).toMatch(/opacity:\s*1/)
    expect(css).not.toMatch(/\.rail-block::details-content/)
    expect(blocks(css, '.rail-tab').join('\n')).toMatch(/0\.15s ease/)
    expect(blocks(css, '.rail-tab[aria-selected="true"]').join('\n')).toMatch(/#1b1b18/)
  })

  it('紧凑顶栏、细分割线、等宽数字，浅色上仍能读', () => {
    const bar = rule(css, '.chrome-bar')
    expect(bar).toMatch(/height:\s*36px/)
    expect(bar).toMatch(/border-bottom:\s*1px solid #e4e4df/)
    const selected = rule(css, '.rail-tab[aria-selected="true"]')
    expect(selected).toMatch(/background:\s*#1b1b18/)
    expect(selected).toMatch(/color:\s*#fff/)
    expect(selected).not.toMatch(/inset 0 -2px/)
    const mono = rule(css, '.mono-num')
    expect(mono).toMatch(/monospace/)
    expect(mono).toMatch(/tabular-nums/)
    expect(rule(css, '.log-box')).toMatch(/background:\s*#f4f4f0/)
    expect(rule(css, '.log-box')).not.toMatch(/#000\b|#0f1720/)
    expect(rule(css, '.right-rail')).not.toMatch(/background:\s*#000/)
    expect(css).not.toMatch(/scanline|particle/)
  })

  it('切换会话和消息进入只做 0.15s 透明度', () => {
    const frame = css.match(/@keyframes jas-msg-in\s*\{([\s\S]*?)\n\}/)
    expect(frame, 'jas-msg-in').toBeTruthy()
    expect(frame[1]).toMatch(/opacity:\s*0/)
    expect(frame[1]).toMatch(/opacity:\s*1/)
    expect(frame[1]).not.toMatch(/transform/)
    expect(blocks(css, '.turn').join('\n')).toMatch(/jas-msg-in 0\.15s ease/)
    expect(blocks(css, '.lg-timeline').join('\n')).toMatch(/jas-msg-in 0\.15s ease/)
    expect(css).toMatch(/@media \(prefers-reduced-motion:\s*reduce\)/)
  })

  it('右栏默认停在任务，抽屉收起，拖动分隔时标记按压', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    expect(wrapper.findAll('.right-rail details.rail-block')).toHaveLength(0)
    expect(wrapper.get('.right-rail [role="tab"][aria-selected="true"]').attributes('data-tab')).toBe('tasks')
    expect(wrapper.find('.right-rail [data-pane="trace"]').exists()).toBe(false)
    expect(wrapper.find('.right-rail [data-pane="logs"]').exists()).toBe(false)
    expect(wrapper.get('.agent-live').text()).toContain('sub-1')
    expect(wrapper.find('.console-drawer').exists()).toBe(false)
    expect(wrapper.get('.turn-trace').element.tagName).toBe('DETAILS')
    expect(wrapper.get('.turn-trace').element.hasAttribute('open')).toBe(false)
    expect(wrapper.get('.thread').attributes('data-thread')).toBe('s1')

    const handle = wrapper.get('[data-resize="session"]')
    await handle.trigger('mousedown', { clientX: 260 })
    expect(wrapper.get('.console-layout').classes()).toContain('is-resizing')
    expect(handle.classes()).toContain('is-active')
    window.dispatchEvent(new MouseEvent('mouseup'))
    await flushPromises()
    expect(wrapper.get('.console-layout').classes()).not.toContain('is-resizing')
    expect(handle.classes()).not.toContain('is-active')
    wrapper.unmount()
  })
})

describe('助手回复上的思考', () => {
  function paint() {
    const style = document.createElement('style')
    style.textContent = css
    document.head.appendChild(style)
    return style
  }

  it('有思考内容时在气泡里显示可折叠的灰色小字，默认收起', async () => {
    state.execution.thinking = '先核对时钟再回答'
    state.timeline.messages = [
      { type: 'system', content: 'rules', rules: [], skills: [] },
      { type: 'human', content: '请给出时间' },
      { type: 'tool', name: 'clock', tool_call_id: 'clock-1', content: '2026-11-08T00:00:00Z' },
      { type: 'ai', content: '模型未配置', thinking: '先核对时钟再回答', tool_calls: [] }
    ]
    const style = paint()
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const bubble = wrapper.get('.bubble.assistant')
    const block = bubble.get('.thinking')
    expect(block.element.tagName).toBe('DETAILS')
    expect(block.element.hasAttribute('open')).toBe(false)
    expect(block.get('summary').text()).toBe('思考')
    expect(block.get('.thinking-body').text()).toBe('先核对时钟再回答')
    const html = bubble.element.innerHTML
    expect(html.indexOf('thinking')).toBeGreaterThanOrEqual(0)
    expect(html.indexOf('思考')).toBeLessThan(html.indexOf('模型未配置'))
    expect(html.indexOf('thinking')).toBeLessThan(html.indexOf('模型未配置'))
    expect(bubble.text()).not.toContain('执行输出')
    expect(bubble.text()).not.toContain('exec-1')
    expect(bubble.text()).not.toContain('SUSPENDED')
    expect(wrapper.find('.right-rail .thinking').exists()).toBe(false)
    await wrapper.get('[data-tab="trace"]').trigger('click')
    expect(wrapper.get('.right-rail').text()).not.toContain('先核对时钟再回答')

    const thinkingStyle = getComputedStyle(block.element)
    const bubbleStyle = getComputedStyle(bubble.element)
    expect(thinkingStyle.color === 'rgb(152, 162, 179)' || thinkingStyle.color === '#98a2b3').toBe(true)
    expect(thinkingStyle.fontSize).toBe('12px')
    expect(parseFloat(thinkingStyle.fontSize)).toBeLessThan(parseFloat(bubbleStyle.fontSize))
    expect(rule(css, '.thinking')).toMatch(/color:\s*#98a2b3/)
    expect(rule(css, '.thinking')).toMatch(/font-size:\s*12px/)

    block.get('summary').element.click()
    await flushPromises()
    expect(block.element.open).toBe(true)
    expect(block.get('.thinking-body').text()).toBe('先核对时钟再回答')
    block.get('summary').element.click()
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 250))
    expect(block.element.open).toBe(false)
    style.remove()
    wrapper.unmount()
  })

  it('没有思考内容时不渲染思考块', async () => {
    state.execution.thinking = '   '
    state.timeline.messages = [
      { type: 'human', content: '请给出时间' },
      { type: 'ai', content: '模型未配置', thinking: '  ', tool_calls: [] }
    ]
    const wrapper = await mountConsole()
    await openSession(wrapper)
    expect(wrapper.find('.bubble.assistant').exists()).toBe(true)
    expect(wrapper.find('.thinking').exists()).toBe(false)
    expect(wrapper.get('.bubble.assistant').text()).not.toContain('思考')
    wrapper.unmount()
  })
})

describe('会话标题', () => {
  it('新建会话使用新会话，而不是带序号的会话 1', async () => {
    const wrapper = await mountConsole()
    await buttonByText(wrapper, '新建').trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/api/sessions', { method: 'POST', body: { title: '新会话' } })
    const bodies = api.mock.calls.map((call) => JSON.stringify(call[1] && call[1].body))
    expect(bodies.some((body) => body && body.includes('会话 1'))).toBe(false)
    wrapper.unmount()
  })

  it('单击标题后回车保存，Esc 取消', async () => {
    const wrapper = await mountConsole()
    await wrapper.get('.session-title').trigger('click')
    await flushPromises()
    const input = wrapper.get('.session-title-input')
    expect(input.element.value).toBe('检查会话')
    await input.setValue('  改过的标题  ')
    await input.trigger('keydown.enter')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/api/sessions/s1', { method: 'PATCH', body: { title: '改过的标题' } })
    expect(wrapper.get('.session-title').text()).toBe('改过的标题')

    api.mockClear()
    await wrapper.get('.session-title').trigger('click')
    await flushPromises()
    const again = wrapper.get('.session-title-input')
    await again.setValue('不会保存')
    await again.trigger('keydown.esc')
    await flushPromises()
    expect(api.mock.calls.filter((call) => call[1] && call[1].method === 'PATCH')).toEqual([])
    expect(wrapper.get('.session-title').text()).toBe('改过的标题')
    wrapper.unmount()
  })

  it('改名入口和空标题都不发请求', async () => {
    const wrapper = await mountConsole()
    await wrapper.get('.session-rename').trigger('click')
    await flushPromises()
    const input = wrapper.get('.session-title-input')
    await input.setValue('   ')
    await input.trigger('keydown.enter')
    await flushPromises()
    expect(api.mock.calls.filter((call) => call[1] && call[1].method === 'PATCH')).toEqual([])
    expect(wrapper.get('.session-title').text()).toBe('检查会话')
    expect(rule(css, '.console-layout .session-title-input')).toMatch(/border-radius:\s*8px/)
    expect(rule(css, '.console-layout .session-title-input')).toMatch(/color:\s*#1b1b18/)
    expect(rule(css, '.console-layout .session-rename')).toMatch(/border-radius:\s*8px/)
    expect(rule(css, '.console-layout .session-rename')).toMatch(/color:\s*#1b1b18/)
    wrapper.unmount()
  })
})

describe('会话模型', () => {
  it('输入区能看到当前模型，列表只有接口返回的模型', async () => {
    const wrapper = await mountConsole()
    expect(wrapper.get('.composer-dock [data-model-select]').attributes('disabled')).toBeDefined()
    await openSession(wrapper)
    const select = wrapper.get('.composer-dock [data-model-select]')
    expect(select.attributes('disabled')).toBeUndefined()
    expect(select.element.value).toBe('deepseek|deepseek-flash')
    expect(wrapper.get('[data-session-model]').text()).toBe('deepseek-flash')
    expect(select.findAll('option').map((item) => item.text())).toEqual(['deepseek-flash', 'deepseek-v4-pro'])
    expect(select.text()).not.toContain('ai-gateway')
    const actions = wrapper.get('.composer-actions')
    const actionHtml = actions.element.innerHTML
    expect(actionHtml.indexOf('data-model-select')).toBeGreaterThanOrEqual(0)
    expect(actionHtml.indexOf('data-model-select')).toBeLessThan(actionHtml.indexOf('发送'))
    expect(actionHtml.indexOf('发送')).toBeLessThan(actionHtml.indexOf('中止'))
    expect(actionHtml.indexOf('中止')).toBeLessThan(actionHtml.indexOf('恢复'))
    expect(rule(css, '.console-layout .composer-actions')).toMatch(/flex-wrap:\s*nowrap/)
    expect(rule(css, '.console-layout .composer-actions')).toMatch(/align-items:\s*center/)
    const chrome = rule(css, '.console-layout .model-select .el-select__wrapper')
    expect(chrome).toMatch(/height:\s*28px/)
    expect(chrome).toMatch(/min-height:\s*28px/)
    expect(chrome).toMatch(/font-size:\s*12px/)
    expect(chrome).toMatch(/border-radius:\s*8px/)
    expect(chrome).toMatch(/color:\s*#1b1b18/)
    const shown = rule(css, '.console-layout .model-select .el-select__placeholder')
    expect(shown).toMatch(/position:\s*static/)
    expect(shown).toMatch(/transform:\s*none/)
    expect(shown).toMatch(/color:\s*#1b1b18/)
    expect(rule(css, '.console-layout .model-select .el-select__selection')).toMatch(/height:\s*26px/)
    const send = rule(css, '.console-layout .composer-actions .el-button.cursor-btn-primary')
    expect(send).toMatch(/height:\s*28px/)
    expect(send).toMatch(/font-size:\s*12px/)
    expect(send).toMatch(/border-radius:\s*8px/)
    expect(send).toMatch(/padding:\s*0 10px/)
    expect(rule(css, '.console-layout .el-button.cursor-btn-primary')).toMatch(/background:\s*#1b1b18/)
    expect(rule(css, '.chat-head .session-model')).toMatch(/border-radius:\s*8px/)
    expect(rule(css, '.chat-head .session-model')).toMatch(/color:\s*#1b1b18/)
    wrapper.unmount()
  })

  it('换模型只改这一会话，再打开仍然是保存的模型', async () => {
    state.sessions = [
      { sessionId: 's1', title: '检查会话', model: 'deepseek-flash', modelProvider: 'deepseek' },
      { sessionId: 's2', title: '另一会话', model: 'deepseek-v4-pro', modelProvider: 'deepseek' }
    ]
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const select = wrapper.get('[data-model-select]')
    await select.setValue('deepseek|deepseek-v4-pro')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/api/sessions/s1', {
      method: 'PATCH',
      body: { model: 'deepseek-v4-pro', modelProvider: 'deepseek' }
    })
    expect(wrapper.get('[data-session-model]').text()).toBe('deepseek-v4-pro')

    await wrapper.findAll('.session-switch')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-session-model]').text()).toBe('deepseek-v4-pro')
    expect(wrapper.get('[data-model-select]').element.value).toBe('deepseek|deepseek-v4-pro')

    await wrapper.findAll('.session-switch')[0].trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-model-select]').element.value).toBe('deepseek|deepseek-v4-pro')
    expect(wrapper.get('[data-session-model]').text()).toBe('deepseek-v4-pro')
    wrapper.unmount()
  })

  it('发送时带上这一会话当前的模型', async () => {
    const wrapper = await mountConsole()
    await openSession(wrapper)
    const input = wrapper.get('.composer-dock textarea')
    await input.setValue('现在几点')
    await buttonByText(wrapper, '发送').trigger('click')
    await flushPromises()
    expect(api).toHaveBeenCalledWith('/api/sessions/s1/messages', {
      method: 'POST',
      body: {
        text: '现在几点',
        workflowId: 'answer-with-tool',
        model: 'deepseek-flash',
        modelProvider: 'deepseek'
      }
    })
    wrapper.unmount()
  })
})
