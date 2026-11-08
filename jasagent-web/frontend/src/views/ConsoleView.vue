<template>
  <div class="console-layout" data-layout="three-column" data-panels-open="true" :style="layoutStyle">
    <aside class="session-column">
      <div class="sidebar-top">
        <div class="sidebar-user">
          <span class="brand">JaSAgent</span>
          <span class="who">{{ loginName }}</span>
          <el-button size="small" @click="logout">退出</el-button>
        </div>
      </div>
      <div class="workspace-block">
        <span class="field-label">工作区</span>
        <el-select v-model="workspace" class="workspace-select" filterable allow-create default-first-option placeholder="工作区">
          <el-option v-for="item in workspaceOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <div class="row">
          <el-button type="primary" size="small" @click="saveWorkspace">选定工作区</el-button>
          <span v-if="workspaceReady" class="who">已选定</span>
        </div>
        <p v-if="workspace" class="workspace-path">{{ workspace }}</p>
      </div>
      <div class="session-head">
        <h2>会话</h2>
        <el-button size="small" type="primary" @click="createSession">新建</el-button>
      </div>
      <div class="session-list">
        <p v-if="sessions.length === 0" class="muted">还没有会话。</p>
        <div v-for="item in sessions" :key="item.sessionId" class="session-row" :class="{ active: item.sessionId === activeSessionId }">
          <button type="button" class="session-switch" @click="switchSession(item.sessionId)">
            <strong>{{ item.title }}</strong>
            <small>{{ item.sessionId.slice(0, 8) }}</small>
          </button>
          <el-button size="small" text type="danger" @click="removeSession(item.sessionId)">删除</el-button>
        </div>
      </div>
      <div class="session-meta">
        <h3>子 agent</h3>
        <p v-if="!activeSessionId" class="muted">选择会话后显示。</p>
        <p v-else-if="agents.length === 0" class="muted">这条会话还没有子 agent。</p>
        <button v-for="agent in agents" :key="agent.agentId + agent.executionId" type="button" class="agent-row" @click="openAgent(agent)">
          <strong>{{ agent.agentId }}</strong>
          <span>{{ agent.role }}</span>
          <small>{{ agent.status }}</small>
        </button>
        <h3>纠正 note <small>harness note</small></h3>
        <p v-if="!activeSessionId" class="muted">选择会话后显示。</p>
        <p v-else-if="corrections.length === 0" class="muted">这条会话还没有纠正 note。</p>
        <p v-for="note in corrections" :key="note.noteId" class="note-line">{{ note.source }} {{ note.text }}</p>
        <el-input v-model="noteText" placeholder="纠正 note" />
        <el-button size="small" @click="addNote">追加纠正</el-button>
      </div>
    </aside>
    <div class="split-handle" data-resize="session" @mousedown.prevent="startResize($event, 'session')"></div>

    <section class="session-main">
      <header class="chat-head">
        <div>
          <p class="eyebrow">对话</p>
          <h2>{{ activeTitle || '会话' }}</h2>
        </div>
      </header>
      <div class="messages">
        <div class="thread">
          <p v-if="!activeSessionId" class="muted">从左侧选择会话。</p>
          <p v-else-if="messages.length === 0" class="muted">输入 / 引用当前用户可见的 skill。</p>
          <article v-for="(message, index) in messages" :key="message.executionId || index" class="turn">
            <div class="bubble user">
              <strong>用户</strong>
              <div>{{ message.text }}</div>
            </div>
            <details v-if="turnTools(message).length" class="turn-trace">
              <summary>▶ Trace / 执行过程</summary>
              <div v-for="tool in turnTools(message)" :key="tool.tool_call_id || tool.name" class="tool-result">
                <strong>{{ tool.name }}</strong>
                <pre>{{ tool.content }}</pre>
              </div>
            </details>
            <div v-if="turnReply(message) || message.status" class="bubble assistant">
              <strong>执行输出</strong>
              <div v-if="turnReply(message)">{{ turnReply(message) }}</div>
              <small v-if="message.executionId" class="muted">{{ message.executionId }} {{ message.status }}</small>
            </div>
          </article>
        </div>
      </div>
      <div class="composer-dock">
        <div class="composer-inner">
          <div v-if="slashQuery !== null" class="skill-pop">
            <p class="muted">当前用户可见的 skill</p>
            <p v-if="filteredSkills.length === 0" class="muted">当前没有可见的 skill</p>
            <button v-for="skill in filteredSkills" :key="skill.name" type="button" @mousedown.prevent="insertSkill(skill)">
              /skill:{{ skill.name }}
              <small>{{ skill.description }}</small>
            </button>
          </div>
          <el-input v-model="draft" type="textarea" :rows="2" placeholder="输入 / 选择 skill" @keydown.enter.exact.prevent="sendMessage" />
          <div class="row">
            <el-button type="primary" size="small" @click="sendMessage">发送</el-button>
            <el-button size="small" @click="interruptExecution">中止</el-button>
            <el-button size="small" @click="resumeExecution">恢复</el-button>
          </div>
        </div>
      </div>
    </section>
    <div class="split-handle" data-resize="rail" @mousedown.prevent="startResize($event, 'rail')"></div>

    <aside class="right-rail">
      <section class="rail-block rail-trace">
        <h2>Trace</h2>
        <div class="rail-body">
          <p class="lg-legend">
            <span v-for="kind in langGraphTypes" :key="kind">{{ kind }}</span>
          </p>
          <p v-if="timeline.length === 0" class="muted">还没有这次执行的消息。</p>
          <ol class="lg-timeline" data-timeline="langgraph">
            <li v-for="(message, index) in timeline" :key="index" :data-type="message.type">
              <div class="lg-type">{{ message.type }}</div>
              <div v-if="message.type === 'system'" class="lg-meta">
                <p v-for="rule in message.rules || []" :key="rule.id">rule {{ rule.id }} {{ rule.description }}</p>
                <p v-for="skill in message.skills || []" :key="skill.name">skill {{ skill.name }} {{ skill.description }}</p>
              </div>
              <pre class="lg-content">{{ message.content }}</pre>
              <p v-for="call in message.tool_calls || []" :key="call.id || call.name" class="muted">{{ call.name }} {{ call.args || call.arguments }}</p>
              <small v-if="message.name" class="muted">{{ message.name }} {{ message.tool_call_id }}</small>
            </li>
          </ol>
          <h3>溯源</h3>
          <p v-if="retrievalRows.length === 0" class="muted">还没有溯源</p>
          <article v-for="(row, index) in retrievalRows" :key="index" class="trace-card">
            <p>{{ row.sourceScope }} {{ row.uri }}</p>
            <p class="muted">{{ row.docId }} {{ row.chunkId }} {{ row.contentHash }}</p>
            <p class="muted">fusion {{ row.fusionScore }} rerank {{ row.rerankScore }}</p>
          </article>
          <h3>评估</h3>
          <p v-if="!(detail.evaluations || []).length" class="muted">还没有评估</p>
          <div class="tags">
            <el-tag v-for="item in detail.evaluations || []" :key="item">{{ item }}</el-tag>
          </div>
        </div>
      </section>
      <section class="rail-block rail-passes">
        <h2>通行证</h2>
        <div class="rail-body pass-list">
          <p v-if="!(detail.passes || []).length" class="muted">还没有通行证</p>
          <article v-for="row in detail.passes || []" :key="row.passId" class="pass-card">
            <div class="pass-card-head">
              <strong>{{ row.name }}</strong>
              <span>{{ row.kind }}</span>
              <span>{{ row.decision }}</span>
            </div>
            <p class="muted">{{ row.reason }}</p>
            <div class="pass-actions">
              <el-button size="small" type="success" @click="decide(row, 'ALLOW')">ALLOW</el-button>
              <el-button size="small" type="danger" @click="decide(row, 'DENY')">DENY</el-button>
            </div>
          </article>
        </div>
      </section>
      <section class="rail-block rail-tools">
        <h2>工具</h2>
        <div class="rail-body">
          <p class="muted">可用工具</p>
          <div v-if="(detail.tools || []).length" class="tags">
            <el-tag v-for="name in detail.tools" :key="name">{{ name }}</el-tag>
          </div>
          <p v-else class="muted">这次执行还没有工具</p>
          <p class="muted">插件</p>
          <div v-if="(detail.plugins || []).length" class="tags">
            <el-tag v-for="name in detail.plugins" :key="name">{{ name }}</el-tag>
          </div>
          <p v-else class="muted">这次执行还没有插件</p>
        </div>
      </section>
      <section class="rail-block rail-context">
        <h2>上下文</h2>
        <div class="rail-body">
          <p class="muted">预算 {{ context.budgetTokens || 8000 }}，压缩阈值 {{ context.compressAt ?? 0.85 }}</p>
          <p v-for="rule in context.rules || []" :key="rule.id">{{ rule.id }} {{ rule.description }}</p>
          <p v-for="skill in context.skills || []" :key="skill.name">{{ skill.name }} {{ skill.description }}</p>
        </div>
      </section>
      <section class="rail-block rail-logs">
        <h2>日志</h2>
        <div class="rail-body">
          <pre class="log-box">{{ logs || '还没有日志' }}</pre>
          <h3>SSE</h3>
          <pre class="log-box">{{ sseText || '等待事件' }}</pre>
          <h3>CLI</h3>
          <div class="row">
            <el-select v-model="workflowId" class="grow" placeholder="workflow">
              <el-option v-for="item in workflows" :key="item.id" :label="item.id" :value="item.id" />
            </el-select>
            <el-button size="small" @click="loadWorkflows">刷新列表</el-button>
          </div>
          <el-input v-model="cliInput" type="textarea" :rows="2" placeholder="执行输入" />
          <div class="row">
            <el-button type="primary" size="small" @click="submitExecution">提交执行</el-button>
            <el-button size="small" @click="queryExecution">查询</el-button>
          </div>
          <el-input v-model="executionId" placeholder="executionId" />
          <p class="muted">{{ detail.status || '未选择' }}</p>
        </div>
      </section>
    </aside>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const langGraphTypes = ['system', 'human', 'ai', 'tool']
const router = useRouter()
const route = useRoute()
const loginName = ref('')
const workspace = ref('')
const workspaceOptions = ref([])
const sessions = ref([])
const activeSessionId = ref('')
const activeTitle = ref('')
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
const noteText = ref('')
const messages = ref([])
const detail = ref({})
const context = ref({})
const timeline = ref([])
const timelines = ref({})
const workspaceReady = ref(false)
const sessionWidth = ref(260)
const railWidth = ref(320)
let source = null
let poll = 0

const layoutStyle = computed(() => ({
  gridTemplateColumns: sessionWidth.value + 'px 8px minmax(0, 1fr) 8px ' + railWidth.value + 'px'
}))
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

onMounted(async () => {
  await loadProfile()
  await Promise.all([loadWorkflows(), loadSkills(), loadSessions()])
  if (route.params.id) {
    executionId.value = String(route.params.id)
    await queryExecution()
  }
})

onBeforeUnmount(() => {
  closeStream()
  window.clearInterval(poll)
})

function startResize(event, key) {
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

async function createSession() {
  const created = await api('/api/sessions', { method: 'POST', body: { title: '新会话 ' + (sessions.value.length + 1) } })
  await loadSessions()
  await switchSession(created.sessionId)
}

async function switchSession(sessionId) {
  activeSessionId.value = sessionId
  const stored = await api('/api/sessions/' + sessionId)
  activeTitle.value = stored.title || ''
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
  }
  await loadTurnTimelines()
  await loadContext()
}

async function removeSession(sessionId) {
  await api('/api/sessions/' + sessionId, { method: 'DELETE' })
  if (activeSessionId.value === sessionId) {
    activeSessionId.value = ''
    activeTitle.value = ''
    messages.value = []
    agents.value = []
    corrections.value = []
    timeline.value = []
    timelines.value = {}
    detail.value = {}
    executionId.value = ''
    logs.value = ''
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
    const message = await api('/api/sessions/' + activeSessionId.value + '/messages', {
      method: 'POST',
      body: { text, workflowId: workflowId.value }
    })
    messages.value.push(message)
    executionId.value = message.executionId
    cliInput.value = text
    await switchSession(activeSessionId.value)
    watchExecution(message.executionId)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function addNote() {
  if (!activeSessionId.value || !noteText.value.trim()) {
    ElMessage.warning('先选择会话并写下纠正 note')
    return
  }
  await api('/api/sessions/' + activeSessionId.value + '/corrections', {
    method: 'POST',
    body: { text: noteText.value, executionId: executionId.value }
  })
  noteText.value = ''
  await switchSession(activeSessionId.value)
  ElMessage.success('已追加纠正 note')
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
  detail.value = await api('/api/executions/' + executionId.value)
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
  detail.value = await api('/api/executions/' + executionId.value + '/resume', { method: 'POST', body: {} })
  watchExecution(executionId.value)
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
      message.output = row.output
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
