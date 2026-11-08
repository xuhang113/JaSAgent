<template>
  <div class="console">
    <header class="topbar">
      <span class="brand">JaSAgent 管理控制台</span>
      <el-select v-model="workspace" style="width: 360px" filterable allow-create default-first-option placeholder="工作区">
        <el-option v-for="item in workspaceOptions" :key="item" :label="item" :value="item" />
      </el-select>
      <el-button type="primary" @click="saveWorkspace">选定工作区</el-button>
      <span v-if="workspaceReady" class="who">已选定</span>
      <span class="spacer"></span>
      <span class="who">{{ loginName }}</span>
      <el-button @click="logout">退出</el-button>
    </header>

    <div class="workspace">
      <aside class="session-column">
        <div class="session-head">
          <h2>会话</h2>
          <el-button size="small" type="primary" @click="createSession">新建</el-button>
        </div>
        <p v-if="sessions.length === 0" class="muted">还没有会话。新建后只保存在当前登录用户目录。</p>
        <div v-for="item in sessions" :key="item.sessionId" class="session-row" :class="{ active: item.sessionId === activeSessionId }">
          <button type="button" class="session-switch" @click="switchSession(item.sessionId)">
            <strong>{{ item.title }}</strong>
            <small>{{ item.sessionId.slice(0, 8) }}</small>
          </button>
          <el-button size="small" text type="danger" @click="removeSession(item.sessionId)">删除</el-button>
        </div>
      </aside>

      <section class="panel">
        <div class="panel-body">
          <div class="panel-head">
            <h2>CLI 面板</h2>
          </div>
          <div class="row">
            <el-select v-model="workflowId" class="grow" placeholder="workflow">
              <el-option v-for="item in workflows" :key="item.id" :label="item.id" :value="item.id" />
            </el-select>
            <el-button @click="loadWorkflows">刷新列表</el-button>
          </div>
          <el-input v-model="cliInput" type="textarea" :rows="3" placeholder="执行输入" />
          <div class="row" style="margin-top: 8px">
            <el-button type="primary" @click="submitExecution">提交执行</el-button>
            <el-button @click="queryExecution">查询</el-button>
            <el-button @click="interruptExecution">中止</el-button>
            <el-button @click="resumeExecution">恢复</el-button>
          </div>
          <el-input v-model="executionId" placeholder="executionId" />
          <h3>日志</h3>
          <pre class="log-box">{{ logs || '还没有日志' }}</pre>
          <h3>SSE 回显</h3>
          <pre class="log-box">{{ sseText || '等待事件' }}</pre>
        </div>
      </section>

      <section class="panel">
        <div class="panel-body">
          <div class="chat-head">
            <h2>对话</h2>
            <el-button @click="openContext">上下文</el-button>
          </div>
          <div class="messages">
            <p v-if="messages.length === 0" class="muted">输入 / 引用当前用户可见的 skill。</p>
            <div v-for="(message, index) in messages" :key="index" class="bubble" :class="message.role">
              <strong>{{ message.role === 'assistant' ? '执行输出' : '用户' }}</strong>
              <div>{{ message.text }}</div>
              <small v-if="message.executionId" class="muted">{{ message.executionId }} {{ message.status }}</small>
              <div v-if="message.output">{{ message.output }}</div>
            </div>
          </div>
          <div class="composer">
            <el-input v-model="draft" type="textarea" :rows="3" placeholder="输入 / 选择 skill" @keydown.enter.exact.prevent="sendMessage" />
            <div v-if="slashQuery !== null" class="skill-pop">
              <p class="muted">当前用户可见的 skill</p>
              <p v-if="filteredSkills.length === 0" class="muted">当前没有可见的 skill</p>
              <button v-for="skill in filteredSkills" :key="skill.name" type="button" @mousedown.prevent="insertSkill(skill)">
                /skill:{{ skill.name }}
                <small>{{ skill.description }}</small>
              </button>
            </div>
          </div>
          <div class="row" style="margin-top: 8px">
            <el-button type="primary" @click="sendMessage">发送</el-button>
            <el-input v-model="noteText" class="grow" placeholder="纠正 note" />
            <el-button @click="addNote">追加纠正</el-button>
          </div>
        </div>
      </section>
    </div>

    <section class="detail">
      <div class="panel-head">
        <h2>当前执行</h2>
        <span class="muted">{{ detail.status || '未选择' }} {{ executionId }}</span>
      </div>
      <h3>工具</h3>
      <p>{{ (detail.tools || []).join('、') || '这次执行还没有工具' }}</p>
      <div class="tags">
        <el-tag v-for="tool in detail.tools || []" :key="tool">{{ tool }}</el-tag>
      </div>
      <h3>插件</h3>
      <p>{{ (detail.plugins || []).join('、') || '这次执行还没有插件' }}</p>
      <div class="tags">
        <el-tag v-for="plugin in detail.plugins || []" :key="plugin" type="warning">{{ plugin }}</el-tag>
      </div>
      <h3>通行证</h3>
      <el-table :data="detail.passes || []" size="small" empty-text="还没有通行证">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="kind" label="类型" width="90" />
        <el-table-column prop="decision" label="决定" width="110" />
        <el-table-column prop="reason" label="原因" />
        <el-table-column label="审批" width="180">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="decide(row, 'ALLOW')">ALLOW</el-button>
            <el-button size="small" type="danger" @click="decide(row, 'DENY')">DENY</el-button>
          </template>
        </el-table-column>
      </el-table>
      <h3>检索溯源</h3>
      <el-table :data="retrievalRows" size="small" empty-text="还没有溯源">
        <el-table-column prop="sourceScope" label="scope" width="110" />
        <el-table-column prop="uri" label="uri" />
        <el-table-column prop="docId" label="docId" />
        <el-table-column prop="chunkId" label="chunkId" />
        <el-table-column prop="start" label="start" width="80" />
        <el-table-column prop="end" label="end" width="80" />
        <el-table-column prop="contentHash" label="contentHash" />
        <el-table-column prop="fusionScore" label="fusion" width="90" />
        <el-table-column prop="rerankScore" label="rerank" width="90" />
      </el-table>
      <h3>评估</h3>
      <p v-if="!(detail.evaluations || []).length" class="muted">还没有评估</p>
      <el-tag v-for="item in detail.evaluations || []" :key="item" style="margin-right: 6px">{{ item }}</el-tag>
    </section>

    <el-drawer v-model="contextOpen" title="上下文" size="420px">
      <p>预算 {{ context.budgetTokens || 8000 }}</p>
      <h3>规则</h3>
      <p v-if="!(context.rules || []).length" class="muted">没有规则</p>
      <p v-for="rule in context.rules || []" :key="rule.id">{{ rule.id }} {{ rule.description }}</p>
      <h3>技能</h3>
      <p v-if="!(context.skills || []).length" class="muted">没有技能</p>
      <p v-for="skill in context.skills || []" :key="skill.name">{{ skill.name }} {{ skill.description }}</p>
      <h3>纠正 note</h3>
      <p v-if="!(context.corrections || []).length" class="muted">没有纠正 note</p>
      <p v-for="note in context.corrections || []" :key="note.noteId">{{ note.source }} {{ note.text }}</p>
      <h3>最近消息</h3>
      <p v-if="!(context.messages || []).length" class="muted">没有消息</p>
      <p v-for="(message, index) in context.messages || []" :key="index">{{ message.text }}</p>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const route = useRoute()
const loginName = ref('')
const workspace = ref('')
const workspaceOptions = ref([])
const sessions = ref([])
const activeSessionId = ref('')
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
const contextOpen = ref(false)
const context = ref({})
const workspaceReady = ref(false)
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
      rows.push({ sourceScope: '', uri: retrieval.reason || 'quality-empty', docId: '', chunkId: '', start: '', end: '', contentHash: '', fusionScore: '', rerankScore: '' })
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
  messages.value = stored.messages || []
  await refreshMessageOutputs()
}

async function removeSession(sessionId) {
  await api('/api/sessions/' + sessionId, { method: 'DELETE' })
  if (activeSessionId.value === sessionId) {
    activeSessionId.value = ''
    messages.value = []
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
  ElMessage.success('已追加纠正 note')
}

async function openContext() {
  if (!activeSessionId.value) {
    ElMessage.warning('先选择会话')
    return
  }
  context.value = await api('/api/sessions/' + activeSessionId.value + '/context')
  contextOpen.value = true
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
