<template>
  <div class="console-layout" :class="{ 'is-resizing': resizing }" data-layout="three-column" data-panels-open="true" :style="layoutStyle">
    <aside class="session-column">
      <div class="sidebar-top chrome-bar">
        <div class="sidebar-user">
          <span class="brand">JaSAgent</span>
          <span class="who">{{ loginName }}</span>
          <el-button size="small" class="cursor-btn-ghost" @click="logout">退出</el-button>
        </div>
      </div>
      <div class="workspace-block">
        <span class="field-label">工作区</span>
        <el-select v-model="workspace" class="workspace-select" filterable allow-create default-first-option placeholder="工作区">
          <el-option v-for="item in workspaceOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <div class="row">
          <el-button type="primary" size="small" class="cursor-btn-primary" @click="saveWorkspace">选定工作区</el-button>
          <span v-if="workspaceReady" class="who">已选定</span>
        </div>
        <p v-if="workspace" class="workspace-path">{{ workspace }}</p>
      </div>
      <div class="session-head">
        <h2>会话</h2>
        <el-button size="small" type="primary" class="cursor-btn-primary" @click="createSession">新建</el-button>
      </div>
      <div class="session-list">
        <p v-if="sessions.length === 0" class="muted">无会话。</p>
        <div v-for="item in sessions" :key="item.sessionId" class="session-row" :class="{ active: item.sessionId === activeSessionId }">
          <div v-if="editingSessionId === item.sessionId" class="session-edit">
            <input
              v-model="editingTitle"
              class="session-title-input"
              :data-session-title-input="item.sessionId"
              aria-label="会话标题"
              maxlength="80"
              @click.stop
              @keydown.enter.prevent="commitRename(item)"
              @keydown.esc.prevent="cancelRename"
              @blur="onRenameBlur(item)"
            />
            <small class="mono-num">{{ item.sessionId.slice(0, 8) }}</small>
          </div>
          <button v-else type="button" class="session-switch" @click="switchSession(item.sessionId)">
            <strong class="session-title" @click.stop="beginRename(item)">{{ item.title }}</strong>
            <small class="mono-num">{{ item.sessionId.slice(0, 8) }}</small>
          </button>
          <div class="session-tools">
            <button type="button" class="session-rename" @click.stop="beginRename(item)">改名</button>
            <el-button size="small" text type="danger" class="cursor-session-delete" @click="removeSession(item.sessionId)">删除</el-button>
          </div>
        </div>
      </div>
      <div class="session-meta">
        <span class="field-label">纠正</span>
        <p v-if="!activeSessionId" class="muted">先选择会话。</p>
        <p v-for="note in corrections" :key="note.noteId" class="note-line">{{ note.text }}</p>
        <div class="correction-compose">
          <el-input v-model="noteText" placeholder="写下纠正" :disabled="!activeSessionId" />
          <el-button size="small" class="cursor-btn-ghost" @click="addNote">追加纠正</el-button>
        </div>
      </div>
    </aside>
    <div class="split-handle" :class="{ 'is-active': resizing === 'session' }" data-resize="session" @mousedown.prevent="startResize($event, 'session')"></div>

    <section class="session-main">
      <header class="chat-head chrome-bar">
        <span class="eyebrow">对话</span>
        <h2>{{ activeTitle || '会话' }}</h2>
        <span v-if="activeSessionId" class="session-model" data-session-model>{{ currentModelLabel }}</span>
        <div class="drawer-launcher">
          <button type="button" class="rail-tool" data-drawer-menu aria-haspopup="menu" aria-label="抽屉" :aria-expanded="drawerMenu ? 'true' : 'false'" @click="toggleDrawerMenu">···</button>
          <div v-if="drawerMenu" class="drawer-menu" role="menu" aria-label="抽屉">
            <button
              v-for="item in railDrawers"
              :key="item.id"
              type="button"
              role="menuitem"
              :data-open="item.id"
              @click="openDrawer(item.id)"
            >{{ item.label }}</button>
          </div>
        </div>
      </header>
      <div v-if="activeSessionId" class="agent-dock">
        <div class="agent-live">
          <p v-if="liveAgents.length === 0" class="muted empty-line">无正在执行。</p>
          <button
            v-for="agent in liveAgents"
            :key="agent.agentId + agent.executionId"
            type="button"
            class="agent-row"
            data-agent-live
            @click="openAgent(agent)"
          >
            <strong>{{ agent.agentId }}</strong>
            <span>{{ resolvedStatus(agent.status, agent.executionId) }}</span>
          </button>
        </div>
        <button
          type="button"
          class="rail-tool"
          data-agent-list
          aria-haspopup="listbox"
          :aria-expanded="agentListOpen ? 'true' : 'false'"
          aria-label="全部子 agent"
          @click="toggleAgentList"
        >全部</button>
        <div v-if="agentListOpen" class="agent-listbox" role="listbox" aria-label="子 agent">
          <p v-if="agents.length === 0" class="muted empty-line">无子 agent。</p>
          <button
            v-for="agent in agents"
            :key="'all-' + agent.agentId + agent.executionId"
            type="button"
            class="agent-row"
            role="option"
            @click="pickAgent(agent)"
          >
            <strong>{{ agent.agentId }}</strong>
            <span>{{ resolvedStatus(agent.status, agent.executionId) }}</span>
          </button>
        </div>
      </div>
      <div class="messages">
        <div
          class="thread"
          :class="{ 'is-empty': !activeSessionId || messages.length === 0 }"
          :key="activeSessionId || 'none'"
          :data-thread="activeSessionId || 'none'"
        >
          <p v-if="!activeSessionId" class="empty-thread">从左侧选择一个会话。</p>
          <p v-else-if="messages.length === 0" class="empty-thread">在下面输入，开始这一会话。</p>
          <article v-for="(message, index) in messages" :key="message.executionId || index" class="turn">
            <div class="bubble user">
              <strong>用户</strong>
              <div>{{ message.text }}</div>
            </div>
            <details v-if="turnTools(message).length" class="turn-trace">
              <summary @click="foldDetails">▶ Trace / 执行过程</summary>
              <div v-for="tool in turnTools(message)" :key="tool.tool_call_id || tool.name" class="tool-result">
                <strong>{{ tool.name }}</strong>
                <pre>{{ tool.content }}</pre>
              </div>
            </details>
            <div v-if="turnReply(message) || turnThinking(message) || message.status || message.errorCode" class="bubble assistant">
              <details v-if="turnThinking(message)" class="thinking">
                <summary @click="foldDetails">思考</summary>
                <div class="thinking-body">{{ turnThinking(message) }}</div>
              </details>
              <strong>执行输出</strong>
              <div v-if="turnReply(message)">{{ turnReply(message) }}</div>
              <small v-if="message.executionId" class="muted mono-num">{{ message.executionId }} {{ message.status }}</small>
              <small v-if="message.errorCode" class="execution-error">{{ message.errorCode }}</small>
            </div>
          </article>
        </div>
      </div>
      <div class="composer-dock">
        <div class="composer-inner">
          <div v-if="slashQuery !== null" class="skill-pop">
            <p class="muted">skill</p>
            <p v-if="filteredSkills.length === 0" class="muted">无 skill。</p>
            <button v-for="skill in filteredSkills" :key="skill.name" type="button" @mousedown.prevent="insertSkill(skill)">
              /skill:{{ skill.name }}
              <small>{{ skill.description }}</small>
            </button>
          </div>
          <el-input v-model="draft" type="textarea" :rows="2" placeholder="输入 / 选择 skill" @keydown.enter.exact.prevent="sendMessage" />
          <div class="row composer-actions">
            <el-select
              v-model="sessionModel"
              class="model-select"
              data-model-select
              popper-class="model-popper"
              aria-label="模型"
              :disabled="!activeSessionId"
              placeholder="模型"
              @change="saveModel"
            >
              <el-option
                v-for="item in models"
                :key="item.provider + '|' + item.id"
                :label="item.label"
                :value="item.provider + '|' + item.id"
              />
            </el-select>
            <el-button type="primary" size="small" class="cursor-btn-primary" @click="sendMessage">发送</el-button>
            <el-button size="small" class="cursor-composer-stop" @click="interruptExecution">中止</el-button>
            <el-button size="small" class="cursor-btn-ghost" @click="resumeExecution">恢复</el-button>
          </div>
        </div>
      </div>
    </section>
    <div class="split-handle" :class="{ 'is-active': resizing === 'rail' }" data-resize="rail" @mousedown.prevent="startResize($event, 'rail')"></div>

    <aside class="right-rail">
      <div class="rail-chrome">
        <div class="rail-tabs chrome-bar" role="tablist" aria-label="执行">
          <button
            v-for="tab in railTabs"
            :key="tab.id"
            type="button"
            class="rail-tab"
            role="tab"
            :data-tab="tab.id"
            :aria-selected="railTab === tab.id ? 'true' : 'false'"
            @click="selectRailTab(tab.id)"
          >{{ tab.label }}</button>
        </div>
      </div>
      <div class="rail-stage">
        <div v-if="railTab === 'tasks'" class="rail-pane" role="tabpanel" data-pane="tasks">
          <p v-if="!activeSessionId" class="muted empty-line">未选会话。</p>
          <p v-else-if="taskGroups.length === 0" class="muted empty-line">无任务。</p>
          <section v-for="group in taskGroups" :key="group.id" class="task-group" :data-task-group="group.id">
            <p class="task-group-label">{{ group.label }}</p>
            <div v-for="row in group.rows" :key="row.id" class="task-item">
              <button
                type="button"
                class="task-row"
                :aria-expanded="openTaskId === row.id ? 'true' : 'false'"
                @click="toggleTask(row)"
              >
                <span class="task-title">{{ row.title }}</span>
              </button>
              <p v-if="openTaskId === row.id" class="task-summary">{{ taskSummary(row) }}</p>
            </div>
          </section>
        </div>
        <div v-else-if="railTab === 'trace'" class="rail-pane" role="tabpanel" data-pane="trace">
          <p v-if="timeline.length === 0" class="muted empty-line">无 Trace。</p>
          <ol v-else class="lg-timeline" :key="executionId || 'none'" data-timeline="langgraph">
            <li v-for="(message, index) in timeline" :key="index" :data-type="message.type">
              <div class="lg-type">{{ message.type }}</div>
              <div v-if="message.type === 'system'" class="lg-meta">
                <p v-for="rule in message.rules || []" :key="rule.id">rule {{ rule.id }} {{ rule.description }}</p>
                <p v-for="skill in message.skills || []" :key="skill.name">skill {{ skill.name }} {{ skill.description }}</p>
              </div>
              <pre class="lg-content">{{ message.content }}</pre>
              <p v-for="call in message.tool_calls || []" :key="call.id || call.name" class="muted">{{ call.name }} {{ call.args || call.arguments }}</p>
              <small v-if="message.name" class="muted mono-num">{{ message.name }} {{ message.tool_call_id }}</small>
            </li>
          </ol>
        </div>
        <div v-else-if="railTab === 'tools'" class="rail-pane" role="tabpanel" data-pane="tools">
          <div class="pane-block">
            <p v-if="!(detail.tools || []).length" class="muted empty-line">无工具。</p>
            <div v-else class="tags">
              <el-tag v-for="name in detail.tools" :key="name">{{ name }}</el-tag>
            </div>
          </div>
          <div class="pane-block">
            <p v-if="!(detail.plugins || []).length" class="muted empty-line">无插件。</p>
            <div v-else class="tags">
              <el-tag v-for="name in detail.plugins" :key="name">{{ name }}</el-tag>
            </div>
          </div>
        </div>
        <div v-else-if="railTab === 'context'" class="rail-pane" role="tabpanel" data-pane="context">
          <p class="context-metrics">
            <span>预算</span>
            <span class="mono-num">{{ context.budgetTokens || 8000 }}</span>
            <span>阈值</span>
            <span class="mono-num">{{ context.compressAt ?? 0.85 }}</span>
          </p>
          <p v-if="!(context.rules || []).length && !(context.skills || []).length" class="muted empty-line">无规则。</p>
          <p v-for="rule in context.rules || []" :key="rule.id">{{ rule.id }} {{ rule.description }}</p>
          <p v-for="skill in context.skills || []" :key="skill.name">{{ skill.name }} {{ skill.description }}</p>
        </div>
        <div v-else class="rail-pane" role="tabpanel" data-pane="logs">
          <pre class="log-box">{{ logs || '无日志。' }}</pre>
          <div class="pane-block">
            <pre class="log-box" data-stream="sse" aria-label="SSE">{{ sseText || '无事件。' }}</pre>
          </div>
        </div>
        <aside v-if="drawer" class="console-drawer" :data-drawer="drawer" role="dialog" :aria-label="drawerTitle">
          <header class="drawer-head">
            <h2>{{ drawerTitle }}</h2>
            <button type="button" class="rail-tool" data-close @click="closeDrawer">关闭</button>
          </header>
          <div class="drawer-body">
            <template v-if="drawer === 'retrieval'">
              <p v-if="retrievalRows.length === 0" class="muted empty-line">无溯源。</p>
              <article v-for="(row, index) in retrievalRows" :key="index" class="trace-card">
                <p>{{ row.sourceScope }} {{ row.uri }}</p>
                <p class="muted mono-num">{{ row.docId }} {{ row.chunkId }} {{ row.contentHash }}</p>
                <p class="muted">fusion <span class="mono-num">{{ row.fusionScore }}</span> rerank <span class="mono-num">{{ row.rerankScore }}</span></p>
              </article>
            </template>
            <template v-else-if="drawer === 'evaluation'">
              <p v-if="!(detail.evaluations || []).length" class="muted empty-line">无评估。</p>
              <div class="tags">
                <el-tag v-for="item in detail.evaluations || []" :key="item">{{ item }}</el-tag>
              </div>
            </template>
            <div v-else-if="drawer === 'passes'" class="pass-list">
              <p v-if="!(detail.passes || []).length" class="muted empty-line">无通行证。</p>
              <article v-for="row in detail.passes || []" :key="row.passId" class="pass-card">
                <div class="pass-card-head">
                  <strong>{{ row.name }}</strong>
                  <span>{{ row.kind }}</span>
                  <span class="mono-num">{{ row.decision }}</span>
                </div>
                <p class="muted">{{ row.reason }}</p>
                <div class="pass-actions">
                  <el-button size="small" type="primary" class="cursor-btn-primary" @click="decide(row, 'ALLOW')">ALLOW</el-button>
                  <el-button size="small" type="danger" class="cursor-btn-danger" @click="decide(row, 'DENY')">DENY</el-button>
                </div>
              </article>
            </div>
            <template v-else>
              <div class="row">
                <el-select v-model="workflowId" class="grow" placeholder="workflow">
                  <el-option v-for="item in workflows" :key="item.id" :label="item.id" :value="item.id" />
                </el-select>
                <el-button size="small" class="cursor-btn-ghost" @click="loadWorkflows">刷新列表</el-button>
              </div>
              <el-input v-model="cliInput" type="textarea" :rows="2" placeholder="执行输入" />
              <div class="row">
                <el-button type="primary" size="small" class="cursor-btn-primary" @click="submitExecution">提交执行</el-button>
                <el-button size="small" class="cursor-btn-ghost" @click="queryExecution">查询</el-button>
              </div>
              <el-input v-model="executionId" placeholder="executionId" />
              <p class="muted mono-num">{{ detail.status || '未选择' }}</p>
              <p v-if="detail.errorCode" class="execution-error">{{ detail.errorCode }}</p>
            </template>
          </div>
        </aside>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const railTabs = [
  { id: 'tasks', label: '任务' },
  { id: 'trace', label: 'Trace' },
  { id: 'tools', label: '工具' },
  { id: 'context', label: '上下文' },
  { id: 'logs', label: '日志' }
]
const LIVE_STATUS = new Set(['RUNNING', 'QUEUED', 'PENDING', 'ACCEPTED'])
const REVIEW_STATUS = new Set(['SUSPENDED', 'FAILED'])
const DONE_STATUS = new Set(['SUCCEEDED', 'CANCELLED'])
const railDrawers = [
  { id: 'retrieval', label: '溯源' },
  { id: 'evaluation', label: '评估' },
  { id: 'passes', label: '通行证' },
  { id: 'cli', label: 'CLI' }
]
const router = useRouter()
const route = useRoute()
const loginName = ref('')
const workspace = ref('')
const workspaceOptions = ref([])
const sessions = ref([])
const activeSessionId = ref('')
const activeTitle = ref('')
const editingSessionId = ref('')
const editingTitle = ref('')
const skipRenameBlur = ref(false)
const agents = ref([])
const corrections = ref([])
const workflows = ref([])
const workflowId = ref('answer-with-tool')
const skills = ref([])
const cliInput = ref('请回答')
const executionId = ref('')
const logs = ref('')
const sseLines = ref([])
const draft = ref('')
const models = ref([])
const sessionModel = ref('deepseek|deepseek-flash')
const noteText = ref('')
const messages = ref([])
const detail = ref({})
const context = ref({})
const timeline = ref([])
const timelines = ref({})
const workspaceReady = ref(false)
const sessionWidth = ref(260)
const railWidth = ref(320)
const resizing = ref('')
const railTab = ref('tasks')
const drawer = ref('')
const drawerMenu = ref(false)
const agentListOpen = ref(false)
const openTaskId = ref('')
const detailExecutionId = ref('')
const foldTimers = []
let source = null
let poll = 0

const layoutStyle = computed(() => ({
  gridTemplateColumns: sessionWidth.value + 'px 8px minmax(0, 1fr) 8px ' + railWidth.value + 'px'
}))
const currentModelLabel = computed(() => {
  const match = models.value.find((item) => item.provider + '|' + item.id === sessionModel.value)
  if (match) {
    return match.label
  }
  return splitModel(sessionModel.value).id
})
const sseText = computed(() => sseLines.value.join('\n'))
const slashQuery = computed(() => {
  const matched = draft.value.match(/(?:^|\s)\/([^\s]*)$/)
  return matched ? matched[1] : null
})
const filteredSkills = computed(() => {
  if (slashQuery.value === null) {
    return []
  }
  const query = slashQuery.value.toLowerCase()
  return skills.value.filter((skill) => {
    const name = String(skill.name || '').toLowerCase()
    const description = String(skill.description || '').toLowerCase()
    return name.includes(query) || description.includes(query)
  })
})
const drawerTitle = computed(() => {
  const found = railDrawers.find((item) => item.id === drawer.value)
  return found ? found.label : ''
})
const retrievalRows = computed(() => {
  const rows = []
  for (const retrieval of detail.value.retrievals || []) {
    for (const hit of retrieval.hits || []) {
      rows.push(hit)
    }
    if (!(retrieval.hits || []).length) {
      rows.push({
        sourceScope: '',
        uri: retrieval.reason || 'quality-empty',
        docId: '',
        chunkId: '',
        start: '',
        end: '',
        contentHash: '',
        fusionScore: '',
        rerankScore: ''
      })
    }
  }
  return rows
})
const liveAgents = computed(() => agents.value.filter((agent) => agentIsLive(agent)))
const taskGroups = computed(() => {
  const rows = []
  const covered = new Set()
  for (const agent of agents.value) {
    const executionId = agent.executionId || ''
    const message = messages.value.find((item) => executionId && item.executionId === executionId)
    rows.push({
      id: 'agent:' + (agent.agentId || '') + ':' + executionId,
      kind: 'agent',
      title: shortTitle(message && message.text ? message.text : agent.agentId),
      status: resolvedStatus(agent.status, executionId),
      agent,
      message,
      executionId
    })
    if (executionId) {
      covered.add(executionId)
    }
  }
  for (const message of messages.value) {
    if (!message.executionId || covered.has(message.executionId)) {
      continue
    }
    rows.push({
      id: 'exec:' + message.executionId,
      kind: 'execution',
      title: shortTitle(message.text),
      status: resolvedStatus(message.status, message.executionId),
      agent: null,
      message,
      executionId: message.executionId
    })
  }
  const groups = [
    { id: 'active', label: '进行中', rows: [] },
    { id: 'review', label: '待看', rows: [] },
    { id: 'done', label: '完成', rows: [] }
  ]
  for (const row of rows) {
    const group = groups.find((item) => item.id === taskBucket(row.status))
    if (group) {
      group.rows.push(row)
    }
  }
  return groups.filter((group) => group.rows.length)
})

onMounted(async () => {
  await loadProfile()
  await loadModels()
  await Promise.all([loadWorkflows(), loadSkills(), loadSessions()])
  if (route.params.id) {
    executionId.value = String(route.params.id)
    await queryExecution()
  }
})

onBeforeUnmount(() => {
  closeStream()
  window.clearInterval(poll)
  for (const timer of foldTimers) {
    window.clearTimeout(timer)
  }
})

function selectRailTab(id) {
  railTab.value = id
  drawerMenu.value = false
}

function normalizeStatus(status) {
  return String(status || '').toUpperCase()
}

function knownExecutionStatus(id) {
  const message = messages.value.find((item) => item.executionId === id)
  if (message && message.status) {
    return normalizeStatus(message.status)
  }
  if (id && id === detailExecutionId.value && detail.value && detail.value.status) {
    return normalizeStatus(detail.value.status)
  }
  return ''
}

function resolvedStatus(fallback, id) {
  return knownExecutionStatus(id) || normalizeStatus(fallback)
}

function taskBucket(status) {
  const value = normalizeStatus(status)
  if (LIVE_STATUS.has(value)) {
    return 'active'
  }
  if (DONE_STATUS.has(value)) {
    return 'done'
  }
  if (REVIEW_STATUS.has(value) || !value) {
    return 'review'
  }
  return 'review'
}

function agentIsLive(agent) {
  return taskBucket(resolvedStatus(agent.status, agent.executionId)) === 'active'
}

function shortTitle(value) {
  const text = String(value || '').replace(/\s+/g, ' ').trim()
  if (!text) {
    return '未命名'
  }
  if (text.length <= 28) {
    return text
  }
  return text.slice(0, 28) + '…'
}

function taskSummary(row) {
  const lines = []
  if (row.agent) {
    lines.push([row.agent.agentId, row.agent.role, row.status].filter(Boolean).join(' '))
  } else if (row.status) {
    lines.push(row.status)
  }
  if (row.message && row.message.errorCode) {
    lines.push(row.message.errorCode)
  }
  const output = row.message && row.message.output ? String(row.message.output).replace(/\s+/g, ' ').trim() : ''
  if (output) {
    lines.push(output.length > 80 ? output.slice(0, 80) + '…' : output)
  }
  if (row.executionId) {
    lines.push(row.executionId)
  }
  return lines.join('\n')
}

function toggleAgentList() {
  agentListOpen.value = !agentListOpen.value
}

function pickAgent(agent) {
  agentListOpen.value = false
  openAgent(agent)
}

function toggleTask(row) {
  openTaskId.value = openTaskId.value === row.id ? '' : row.id
  if (!row.executionId) {
    return
  }
  executionId.value = row.executionId
  queryExecution().catch(() => {})
  loadTimeline(row.executionId).catch(() => {})
}

function toggleDrawerMenu() {
  drawerMenu.value = !drawerMenu.value
}

function openDrawer(name) {
  drawer.value = name
  drawerMenu.value = false
}

function closeDrawer() {
  drawer.value = ''
}

function foldDetails(event) {
  const details = event.currentTarget && event.currentTarget.parentElement
  if (!details) {
    return
  }
  if (details.classList.contains('is-closing')) {
    event.preventDefault()
    return
  }
  if (!details.open) {
    return
  }
  event.preventDefault()
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    details.open = false
    return
  }
  details.classList.add('is-closing')
  foldTimers.push(window.setTimeout(() => {
    details.open = false
    details.classList.remove('is-closing')
  }, 200))
}

function startResize(event, key) {
  resizing.value = key
  const startX = event.clientX
  const start = key === 'session' ? sessionWidth.value : railWidth.value
  const direction = key === 'session' ? 1 : -1
  function move(ev) {
    const next = Math.min(560, Math.max(200, start + direction * (ev.clientX - startX)))
    if (key === 'session') {
      sessionWidth.value = next
    } else {
      railWidth.value = next
    }
  }
  function up() {
    resizing.value = ''
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

function turnRows(message) {
  if (!message || !message.executionId) {
    return []
  }
  return timelines.value[message.executionId] || []
}

function turnTools(message) {
  return turnRows(message).filter((row) => row.type === 'tool')
}

function turnThinking(message) {
  const rows = turnRows(message)
  let fromRows = ''
  for (const row of rows) {
    if (row && row.type === 'ai') {
      const text = cleanThinking(row.thinking)
      if (text) {
        fromRows = text
      }
    }
  }
  return fromRows || cleanThinking(message && message.thinking)
}

function cleanThinking(value) {
  if (value == null) {
    return ''
  }
  return String(value).trim()
}

function turnReply(message) {
  if (message.output) {
    return message.output
  }
  const rows = turnRows(message)
  for (let index = rows.length - 1; index >= 0; index -= 1) {
    if (rows[index].type === 'ai' && rows[index].content) {
      return rows[index].content
    }
  }
  return ''
}

async function loadProfile() {
  const me = await api('/api/me')
  loginName.value = me.login
  const listed = await api('/api/workspaces')
  workspaceOptions.value = listed.workspaces || []
  workspace.value = me.workspace || listed.workspace || workspaceOptions.value[0] || ''
}

async function saveWorkspace() {
  try {
    const saved = await api('/api/workspace', { method: 'POST', body: { path: workspace.value } })
    workspace.value = saved.workspace
    workspaceReady.value = true
    await loadSkills()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function loadWorkflows() {
  workflows.value = await api('/api/workflows')
  if (!workflows.value.some((item) => item.id === workflowId.value) && workflows.value.length) {
    workflowId.value = workflows.value[0].id
  }
}

async function loadSkills() {
  skills.value = await api('/api/skills')
}

async function loadSessions() {
  sessions.value = await api('/api/sessions')
}

async function loadModels() {
  try {
    const body = await api('/api/models')
    models.value = Array.isArray(body.models) ? body.models : []
  } catch {
    models.value = []
  }
}

function splitModel(value) {
  const text = String(value || '')
  const cut = text.indexOf('|')
  if (cut < 0) {
    return { provider: 'deepseek', id: text || 'deepseek-flash' }
  }
  return { provider: text.slice(0, cut) || 'deepseek', id: text.slice(cut + 1) || 'deepseek-flash' }
}

function defaultModelKey() {
  const flash = models.value.find((item) => item.provider === 'deepseek' && item.id === 'deepseek-flash')
  if (flash) {
    return 'deepseek|deepseek-flash'
  }
  if (models.value.length) {
    return models.value[0].provider + '|' + models.value[0].id
  }
  return 'deepseek|deepseek-flash'
}

function rememberModel(stored) {
  const key = (stored.modelProvider || 'deepseek') + '|' + (stored.model || 'deepseek-flash')
  const known = models.value.some((item) => item.provider + '|' + item.id === key)
  sessionModel.value = known ? key : defaultModelKey()
}

async function saveModel(value) {
  if (!activeSessionId.value) {
    return
  }
  const chosen = splitModel(value)
  try {
    const saved = await api('/api/sessions/' + activeSessionId.value, {
      method: 'PATCH',
      body: { model: chosen.id, modelProvider: chosen.provider }
    })
    rememberModel(saved)
  } catch (error) {
    ElMessage.error(error.message)
    try {
      rememberModel(await api('/api/sessions/' + activeSessionId.value))
    } catch {
      rememberModel({ model: 'deepseek-flash', modelProvider: 'deepseek' })
    }
  }
}

async function createSession() {
  const created = await api('/api/sessions', { method: 'POST', body: { title: '新会话' } })
  await loadSessions()
  await switchSession(created.sessionId)
}

function beginRename(item) {
  editingSessionId.value = item.sessionId
  editingTitle.value = item.title || ''
  skipRenameBlur.value = false
  nextTick(() => {
    const input = document.querySelector('[data-session-title-input="' + item.sessionId + '"]')
    if (input) {
      input.focus()
      input.select()
    }
  })
}

function cancelRename() {
  skipRenameBlur.value = true
  editingSessionId.value = ''
  editingTitle.value = ''
}

function onRenameBlur(item) {
  if (skipRenameBlur.value) {
    skipRenameBlur.value = false
    return
  }
  commitRename(item)
}

async function commitRename(item) {
  if (editingSessionId.value !== item.sessionId) {
    return
  }
  const title = editingTitle.value.trim()
  if (!title || title === String(item.title || '').trim()) {
    cancelRename()
    return
  }
  editingSessionId.value = ''
  try {
    const saved = await api('/api/sessions/' + item.sessionId, { method: 'PATCH', body: { title } })
    item.title = saved.title || title
    if (activeSessionId.value === item.sessionId) {
      activeTitle.value = item.title
    }
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function switchSession(sessionId) {
  activeSessionId.value = sessionId
  agentListOpen.value = false
  openTaskId.value = ''
  const stored = await api('/api/sessions/' + sessionId)
  activeTitle.value = stored.title || ''
  rememberModel(stored)
  messages.value = stored.messages || []
  agents.value = stored.agents || []
  corrections.value = stored.corrections || []
  timelines.value = {}
  const last = [...messages.value].reverse().find((message) => message.executionId)
  executionId.value = last ? last.executionId : ''
  await refreshMessageOutputs()
  try {
    await queryExecution()
  } catch {
    detail.value = {}
    detailExecutionId.value = ''
  }
  await loadTurnTimelines()
  await loadContext()
}

async function removeSession(sessionId) {
  await api('/api/sessions/' + sessionId, { method: 'DELETE' })
  if (activeSessionId.value === sessionId) {
    activeSessionId.value = ''
    activeTitle.value = ''
    sessionModel.value = defaultModelKey()
    messages.value = []
    agents.value = []
    corrections.value = []
    timeline.value = []
    timelines.value = {}
    detail.value = {}
    detailExecutionId.value = ''
    executionId.value = ''
    logs.value = ''
    agentListOpen.value = false
    openTaskId.value = ''
  }
  await loadSessions()
}

function insertSkill(skill) {
  draft.value = draft.value.replace(/(?:^|\s)\/([^\s]*)$/, (all) => {
    const lead = all.startsWith('/') ? '' : all[0]
    return lead + '/skill:' + skill.name + ' '
  })
}

async function sendMessage() {
  if (!draft.value.trim()) {
    return
  }
  if (!activeSessionId.value) {
    await createSession()
  }
  const text = draft.value
  draft.value = ''
  try {
    const chosen = splitModel(sessionModel.value)
    const message = await api('/api/sessions/' + activeSessionId.value + '/messages', {
      method: 'POST',
      body: { text, workflowId: workflowId.value, model: chosen.id, modelProvider: chosen.provider }
    })
    messages.value.push(message)
    executionId.value = message.executionId
    cliInput.value = text
    await loadSessions()
    await switchSession(activeSessionId.value)
    watchExecution(message.executionId)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function addNote() {
  if (!activeSessionId.value || !noteText.value.trim()) {
    ElMessage.warning('先选择会话，再写纠正')
    return
  }
  await api('/api/sessions/' + activeSessionId.value + '/corrections', {
    method: 'POST',
    body: { text: noteText.value, executionId: executionId.value }
  })
  noteText.value = ''
  await switchSession(activeSessionId.value)
  ElMessage.success('已追加纠正')
}

async function loadContext() {
  if (!activeSessionId.value) {
    return
  }
  try {
    context.value = await api('/api/sessions/' + activeSessionId.value + '/context')
  } catch {
    context.value = context.value || {}
  }
}

async function openAgent(agent) {
  executionId.value = agent.executionId || executionId.value
  try {
    await queryExecution()
  } catch {
    detail.value = detail.value || {}
  }
  await loadTimeline(executionId.value)
}

async function loadTimeline(id) {
  if (!activeSessionId.value) {
    timeline.value = []
    return
  }
  const query = id ? '?executionId=' + encodeURIComponent(id) : ''
  try {
    const body = await api('/api/sessions/' + activeSessionId.value + '/timeline' + query)
    const key = String((body && body.executionId) || id || '')
    const rows = (body && body.messages) || []
    if (key) {
      timelines.value = { ...timelines.value, [key]: rows }
    }
    if (!id || key === executionId.value) {
      timeline.value = rows
    }
  } catch {
    if (!id || id === executionId.value) {
      timeline.value = []
    }
  }
}

async function loadTurnTimelines() {
  const ids = []
  for (const message of messages.value) {
    if (message.executionId && !ids.includes(message.executionId)) {
      ids.push(message.executionId)
    }
  }
  if (!ids.length && executionId.value) {
    ids.push(executionId.value)
  }
  for (const id of ids) {
    await loadTimeline(id)
  }
}

async function submitExecution() {
  try {
    const created = await api('/api/executions', {
      method: 'POST',
      body: { workflowId: workflowId.value, input: cliInput.value }
    })
    executionId.value = created.executionId
    watchExecution(created.executionId)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function queryExecution() {
  if (!executionId.value) {
    return
  }
  const id = executionId.value
  detail.value = await api('/api/executions/' + id)
  detailExecutionId.value = id
  const logBody = await api('/api/executions/' + executionId.value + '/logs')
  logs.value = logBody.logs || detail.value.logs || ''
  await refreshMessageOutputs()
}

async function interruptExecution() {
  if (!executionId.value) {
    return
  }
  detail.value = await api('/api/executions/' + executionId.value + '/interrupt', { method: 'POST', body: {} })
  logs.value = detail.value.logs || logs.value
}

async function resumeExecution() {
  if (!executionId.value) {
    return
  }
  try {
    detail.value = await api('/api/executions/' + executionId.value + '/resume', { method: 'POST', body: {} })
    if (detail.value && detail.value.errorCode) {
      ElMessage.error(String(detail.value.errorCode))
    }
    watchExecution(executionId.value)
  } catch (error) {
    ElMessage.error(error.message || '恢复失败')
    await queryExecution().catch(() => {})
  }
}

async function decide(row, decision) {
  detail.value = await api('/api/executions/' + executionId.value + '/passes/' + row.passId, {
    method: 'POST',
    body: { decision }
  })
}

function watchExecution(id) {
  closeStream()
  sseLines.value = []
  source = new EventSource('/api/executions/' + id + '/events')
  source.addEventListener('status', (event) => {
    sseLines.value.push(event.data)
    queryExecution().catch(() => {})
    loadTurnTimelines().catch(() => {})
  })
  source.onerror = () => closeStream()
  window.clearInterval(poll)
  poll = window.setInterval(() => {
    queryExecution().catch(() => {})
    loadTurnTimelines().catch(() => {})
  }, 1000)
}

function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

async function refreshMessageOutputs() {
  for (const message of messages.value) {
    if (!message.executionId) {
      continue
    }
    try {
      const row = await api('/api/executions/' + message.executionId)
      message.status = row.status
      message.errorCode = row.errorCode || ''
      message.output = row.output
      message.thinking = row.thinking || ''
    } catch {
      // 执行可能还不属于这次登录。
    }
  }
}

async function logout() {
  await api('/logout', { method: 'POST' })
  await router.push('/login')
}
</script>
