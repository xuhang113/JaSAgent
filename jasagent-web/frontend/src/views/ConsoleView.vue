<template>
  <div class="console" data-default-panels="hidden" data-panels-open="false">
    <header class="topbar">
      <span class="brand">JaSAgent</span>
      <el-select v-model="workspace" class="workspace-select" filterable allow-create default-first-option placeholder="工作区">
        <el-option v-for="item in workspaceOptions" :key="item" :label="item" :value="item" />
      </el-select>
      <el-button type="primary" size="small" @click="saveWorkspace">选定工作区</el-button>
      <span v-if="workspaceReady" class="who">已选定</span>
      <span class="spacer"></span>
      <span class="who">{{ loginName }}</span>
      <el-button size="small" @click="logout">退出</el-button>
    </header>

    <div class="console-body">
      <aside class="session-column" :style="{ width: sessionWidth + 'px' }">
        <div class="session-head">
          <h2>会话</h2>
          <el-button size="small" type="primary" @click="createSession">新建</el-button>
        </div>
        <p v-if="sessions.length === 0" class="muted">还没有会话。</p>
        <div v-for="item in sessions" :key="item.sessionId" class="session-row" :class="{ active: item.sessionId === activeSessionId }">
          <button type="button" class="session-switch" @click="switchSession(item.sessionId)">
            <strong>{{ item.title }}</strong>
            <small>{{ item.sessionId.slice(0, 8) }}</small>
          </button>
          <el-button size="small" text type="danger" @click="removeSession(item.sessionId)">删除</el-button>
        </div>
      </aside>
      <div class="split-handle" data-resize="session" @mousedown.prevent="startResize($event, 'session')"></div>

      <section class="session-main">
        <div class="panel-head">
          <h2>{{ activeTitle || '会话' }}</h2>
          <div class="row">
            <el-button size="small" @click="togglePanel('cli')">CLI</el-button>
            <el-button size="small" @click="togglePanel('sse')">SSE</el-button>
            <el-button size="small" @click="togglePanel('passes')">通行证</el-button>
            <el-button size="small" @click="togglePanel('retrieval')">溯源</el-button>
            <el-button size="small" @click="togglePanel('evaluation')">评估</el-button>
            <el-button size="small" @click="openContext">上下文</el-button>
          </div>
        </div>
        <p v-if="!activeSessionId" class="muted">从左侧选择会话。子 agent 和纠正 note 会出现在这里。</p>
        <template v-else>
          <div class="split-read">
            <div>
              <h3>子 agent</h3>
              <p v-if="agents.length === 0" class="muted">这条会话还没有子 agent。</p>
              <button v-for="agent in agents" :key="agent.agentId + agent.executionId" type="button" class="agent-row" @click="openAgent(agent)">
                <strong>{{ agent.agentId }}</strong>
                <span>{{ agent.role }}</span>
                <small>{{ agent.status }}</small>
              </button>
            </div>
            <div>
              <h3>纠正 note <small>harness note</small></h3>
              <p v-if="corrections.length === 0" class="muted">这条会话还没有纠正 note。</p>
              <p v-for="note in corrections" :key="note.noteId" class="note-line">{{ note.source }} {{ note.text }}</p>
            </div>
          </div>
          <h3>对话</h3>
          <div class="messages">
            <p v-if="messages.length === 0" class="muted">输入 / 引用当前用户可见的 skill。</p>
            <div v-for="(message, index) in messages" :key="index" class="bubble" :class="message.role">
              <strong>{{ message.role === 'assistant' ? '执行输出' : '用户' }}</strong>
              <div>{{ message.text }}</div>
              <small v-if="message.executionId" class="muted">{{ message.executionId }} {{ message.status }}</small>
              <small v-if="message.errorCode" class="execution-error">{{ message.errorCode }}</small>
              <div v-if="message.output">{{ message.output }}</div>
            </div>
          </div>
          <div class="composer">
            <el-input v-model="draft" type="textarea" :rows="2" placeholder="输入 / 选择 skill" @keydown.enter.exact.prevent="sendMessage" />
            <div v-if="slashQuery !== null" class="skill-pop">
              <p class="muted">当前用户可见的 skill</p>
              <p v-if="filteredSkills.length === 0" class="muted">当前没有可见的 skill</p>
              <button v-for="skill in filteredSkills" :key="skill.name" type="button" @mousedown.prevent="insertSkill(skill)">
                /skill:{{ skill.name }}
                <small>{{ skill.description }}</small>
              </button>
            </div>
          </div>
          <div class="row">
            <el-button type="primary" size="small" @click="sendMessage">发送</el-button>
            <el-button size="small" @click="interruptExecution">中止</el-button>
            <el-button size="small" @click="resumeExecution">恢复</el-button>
            <el-input v-model="noteText" class="grow" placeholder="纠正 note" />
            <el-button size="small" @click="addNote">追加纠正</el-button>
          </div>
        </template>
      </section>

      <template v-if="openPanel">
        <div class="split-handle" data-resize="dock" @mousedown.prevent="startResize($event, 'dock')"></div>
        <section class="dock" :style="{ width: dockWidth + 'px' }">
          <div v-if="openPanel === 'cli'" class="panel-body">
            <div class="panel-head">
              <h2>CLI 面板</h2>
              <el-button size="small" text @click="closePanels">关闭</el-button>
            </div>
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
              <el-button size="small" @click="interruptExecution">中止</el-button>
              <el-button size="small" @click="resumeExecution">恢复</el-button>
            </div>
            <el-input v-model="executionId" placeholder="executionId" />
            <p class="muted">{{ detail.status || '未选择' }}</p>
            <p v-if="detail.errorCode" class="execution-error">{{ detail.errorCode }}</p>
            <h3>日志</h3>
            <pre class="log-box">{{ logs || '还没有日志' }}</pre>
            <h3>工具</h3>
            <p class="muted">{{ (detail.tools || []).join('、') || '这次执行还没有工具' }}</p>
            <h3>插件</h3>
            <p class="muted">{{ (detail.plugins || []).join('、') || '这次执行还没有插件' }}</p>
          </div>
          <div v-else-if="openPanel === 'sse'" class="panel-body">
            <div class="panel-head">
              <h2>SSE 回显</h2>
              <el-button size="small" text @click="closePanels">关闭</el-button>
            </div>
            <pre class="log-box">{{ sseText || '等待事件' }}</pre>
          </div>
          <div v-else-if="openPanel === 'passes'" class="panel-body">
            <div class="panel-head">
              <h2>通行证</h2>
              <el-button size="small" text @click="closePanels">关闭</el-button>
            </div>
            <el-table :data="detail.passes || []" size="small" empty-text="还没有通行证">
              <el-table-column prop="name" label="名称" />
              <el-table-column prop="kind" label="类型" width="72" />
              <el-table-column prop="decision" label="决定" width="90" />
              <el-table-column prop="reason" label="原因" />
              <el-table-column label="审批" width="150">
                <template #default="{ row }">
                  <el-button size="small" type="success" @click="decide(row, 'ALLOW')">ALLOW</el-button>
                  <el-button size="small" type="danger" @click="decide(row, 'DENY')">DENY</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div v-else-if="openPanel === 'retrieval'" class="panel-body">
            <div class="panel-head">
              <h2>溯源</h2>
              <el-button size="small" text @click="closePanels">关闭</el-button>
            </div>
            <el-table :data="retrievalRows" size="small" empty-text="还没有溯源">
              <el-table-column prop="sourceScope" label="scope" width="90" />
              <el-table-column prop="uri" label="uri" />
              <el-table-column prop="docId" label="docId" />
              <el-table-column prop="chunkId" label="chunkId" />
              <el-table-column prop="contentHash" label="contentHash" />
              <el-table-column prop="fusionScore" label="fusion" width="80" />
              <el-table-column prop="rerankScore" label="rerank" width="80" />
            </el-table>
          </div>
          <div v-else-if="openPanel === 'evaluation'" class="panel-body">
            <div class="panel-head">
              <h2>评估</h2>
              <el-button size="small" text @click="closePanels">关闭</el-button>
            </div>
            <p v-if="!(detail.evaluations || []).length" class="muted">还没有评估</p>
            <el-tag v-for="item in detail.evaluations || []" :key="item">{{ item }}</el-tag>
          </div>
          <div v-else class="panel-body">
            <div class="panel-head">
              <h2>上下文</h2>
              <el-button size="small" text @click="closePanels">关闭</el-button>
            </div>
            <p class="muted">预算 {{ context.budgetTokens || 8000 }}，压缩阈值 {{ context.compressAt ?? 0.85 }}</p>
            <p v-for="rule in context.rules || []" :key="rule.id">{{ rule.id }} {{ rule.description }}</p>
            <p v-for="skill in context.skills || []" :key="skill.name">{{ skill.name }} {{ skill.description }}</p>
          </div>
        </section>
      </template>

      <template v-if="timelineOpen">
        <div class="split-handle" data-resize="timeline" @mousedown.prevent="startResize($event, 'timeline')"></div>
        <aside class="timeline-pane" :style="{ width: timelineWidth + 'px' }">
          <div class="panel-head">
            <h2>支持内容</h2>
            <el-button size="small" text @click="timelineOpen = false">关闭</el-button>
          </div>
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
        </aside>
      </template>
    </div>
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
const openPanel = ref('')
const timelineOpen = ref(false)
const timeline = ref([])
const workspaceReady = ref(false)
const sessionWidth = ref(220)
const dockWidth = ref(300)
const timelineWidth = ref(340)
let source = null
let poll = 0

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
  const start = key === 'session' ? sessionWidth.value : key === 'dock' ? dockWidth.value : timelineWidth.value
  const direction = key === 'session' ? 1 : -1
  function move(ev) {
    const next = Math.min(560, Math.max(160, start + direction * (ev.clientX - startX)))
    if (key === 'session') {
      sessionWidth.value = next
    } else if (key === 'dock') {
      dockWidth.value = next
    } else {
      timelineWidth.value = next
    }
  }
  function up() {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

function togglePanel(name) {
  if (openPanel.value === name) {
    closePanels()
    return
  }
  openPanel.value = name
  timelineOpen.value = true
  loadTimeline()
}

function closePanels() {
  openPanel.value = ''
  timelineOpen.value = false
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
  const last = [...messages.value].reverse().find((message) => message.executionId)
  if (last) {
    executionId.value = last.executionId
  }
  await refreshMessageOutputs()
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
    timelineOpen.value = false
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

async function openContext() {
  if (!activeSessionId.value) {
    ElMessage.warning('先选择会话')
    return
  }
  context.value = await api('/api/sessions/' + activeSessionId.value + '/context')
  openPanel.value = 'context'
  timelineOpen.value = true
  await loadTimeline()
}

async function openAgent(agent) {
  executionId.value = agent.executionId || executionId.value
  if (!openPanel.value) {
    openPanel.value = 'cli'
  }
  timelineOpen.value = true
  await queryExecution()
  await loadTimeline()
}

async function loadTimeline() {
  if (!activeSessionId.value) {
    timeline.value = []
    return
  }
  const query = executionId.value ? '?executionId=' + encodeURIComponent(executionId.value) : ''
  try {
    const body = await api('/api/sessions/' + activeSessionId.value + '/timeline' + query)
    timeline.value = body.messages || []
  } catch {
    timeline.value = []
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
    if (timelineOpen.value) {
      loadTimeline()
    }
  })
  source.onerror = () => closeStream()
  window.clearInterval(poll)
  poll = window.setInterval(() => {
    queryExecution().catch(() => {})
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
