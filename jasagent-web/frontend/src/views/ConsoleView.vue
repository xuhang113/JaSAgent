<template>
  <div class="console-layout" :class="{ 'is-resizing': resizing }" data-layout="three-column" :data-panels-open="railOpen ? 'true' : 'false'" :style="layoutStyle">
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
        <router-link class="rail-tool" to="/cluster">集群</router-link>
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
        <button v-if="focusedExecutionId" type="button" class="rail-tool" data-back-main @click="showMainSession">返回主会话</button>
        <span class="eyebrow">{{ focusedExecutionId ? '子会话' : '对话' }}</span>
        <h2>{{ focusedTitle || activeTitle || '会话' }}</h2>
        <span v-if="activeSessionId" class="session-model" data-session-model>{{ currentModelLabel }}</span>
        <span v-if="activeSessionId" class="routed-agent" data-routed-agent>
          <span class="routed-label">实例</span>
          <span class="mono-num" data-routed-instance>{{ routedAgent.instanceId || '未分配' }}</span>
          <span class="routed-label">地址</span>
          <span class="mono-num" data-routed-address>{{ routedAgent.address || '无地址' }}</span>
        </span>
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
            @click="openSubSession(agent)"
          >
            <strong>{{ agent.agentId }}</strong>
            <span class="task-icon" :data-status="statusLabel(resolvedStatus(agent.status, agent.executionId))" aria-hidden="true"></span>
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
            <span class="task-icon" :data-status="statusLabel(resolvedStatus(agent.status, agent.executionId))" aria-hidden="true"></span>
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
          <article v-for="(message, index) in visibleMessages" :key="message.executionId || index" class="turn">
            <div class="bubble user">
              <RoleIcon role="user" />
              <div class="user-text">{{ message.text }}</div>
            </div>
            <!-- 最后一轮思考默认收起，只显示一行「思考」。 -->
            <details v-if="showThinking(message, index)" class="thinking">
              <summary @click="foldDetails">思考</summary>
              <div class="thinking-body">{{ turnThinking(message) }}</div>
            </details>
            <div v-if="turnReply(message) || message.errorCode" class="bubble assistant">
              <RoleIcon role="assistant" />
              <div class="assistant-body">
                <div v-if="turnReply(message)" class="assistant-text">{{ turnReply(message) }}</div>
                <small v-if="message.errorCode" class="execution-error">{{ message.errorCode }}</small>
              </div>
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

    <aside class="right-rail" :data-rail-open="railOpen ? 'true' : 'false'">
      <div class="rail-chrome">
        <div class="rail-tabs chrome-bar" role="tablist" aria-label="执行">
          <button
            type="button"
            class="rail-tool rail-fold"
            data-rail-fold
            :aria-expanded="railOpen ? 'true' : 'false'"
            :aria-label="railOpen ? '收起右栏' : '展开右栏'"
            @click="toggleRail"
          ></button>
          <template v-if="railOpen">
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
          </template>
        </div>
      </div>
      <div v-if="railOpen" class="rail-stage">
        <div v-if="railTab === 'tasks'" class="rail-pane" role="tabpanel" data-pane="tasks">
          <p v-if="!activeSessionId" class="muted empty-line">未选会话。</p>
          <div v-else class="task-tree" data-task-tree>
            <details
              v-for="group in taskSections"
              :key="group.id"
              class="task-section"
              :data-task-group="group.id"
              :open="sectionIsOpen(group.id, group.count)"
              @toggle="rememberSection(group.id, $event)"
            >
              <summary class="task-section-head">
                <span class="task-section-label">{{ group.label }}</span>
                <span class="task-section-count">{{ group.count }}</span>
              </summary>
              <div v-for="session in group.sessions" :key="session.id" class="task-branch">
                <button type="button" class="task-row task-main" data-task-main @click="showMainSession">
                  <span class="task-icon" :data-status="session.status" aria-hidden="true"></span>
                  <span class="task-title">{{ session.title }}</span>
                </button>
                <div v-if="session.children.length" class="task-children">
                  <button
                    v-for="agent in session.children"
                    :key="agent.agentId + agent.executionId"
                    type="button"
                    class="task-row task-child"
                    data-task-child
                    @click="openSubSession(agent)"
                  >
                    <span class="task-icon" :data-status="statusLabel(resolvedStatus(agent.status, agent.executionId))" aria-hidden="true"></span>
                    <span class="task-title">{{ subTitle(agent) }}</span>
                  </button>
                </div>
              </div>
            </details>
          </div>
        </div>
        <div v-else-if="railTab === 'trace'" class="rail-pane" role="tabpanel" data-pane="trace">
          <p v-if="traceTurns.length === 0" class="muted empty-line">无 Trace。</p>
          <details v-for="turn in traceTurns" :key="turn.id" class="trace-turn">
            <summary @click="foldDetails">{{ turn.title }}</summary>
            <ol class="lg-timeline" data-timeline="langgraph">
              <li v-for="(step, index) in turn.steps" :key="turn.id + ':' + index">
                <details class="trace-step" :data-kind="step.kind">
                  <summary @click="foldDetails">
                    <span class="trace-step-name">{{ step.name }}</span>
                    <span v-if="step.elapsed" class="trace-step-meta">{{ step.elapsed }}</span>
                    <span class="trace-step-summary">{{ step.summary }}</span>
                  </summary>
                  <pre class="trace-step-body">{{ step.body }}</pre>
                </details>
              </li>
            </ol>
          </details>
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
/** 会话页：中间是图标、最后一轮思考和回复正文，Trace 在可折叠的右栏。 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import RoleIcon from '../components/RoleIcon.vue'
import { api } from '../api'
import { consoleModels, readRoutedAgent } from '../gateway'

/** 右栏页签：任务、Trace、工具、上下文、日志。id 用来切换面板，label 是按钮文字。 */
const railTabs = [
  { id: 'tasks', label: '任务' },
  { id: 'trace', label: 'Trace' },
  { id: 'tools', label: '工具' },
  { id: 'context', label: '上下文' },
  { id: 'logs', label: '日志' }
]
/** 仍在排队或执行的状态，归到任务列表的「进行中」。 */
const LIVE_STATUS = new Set(['RUNNING', 'QUEUED', 'PENDING', 'ACCEPTED'])
/** 停住或失败的状态，归到任务列表的「待看」。 */
const REVIEW_STATUS = new Set(['SUSPENDED', 'FAILED'])
/** 已经结束的状态，归到任务列表的「完成」。 */
const DONE_STATUS = new Set(['SUCCEEDED', 'CANCELLED'])
/** 顶栏抽屉菜单。id 决定打开哪一块，label 是菜单文字。 */
const railDrawers = [
  { id: 'retrieval', label: '溯源' },
  { id: 'evaluation', label: '评估' },
  { id: 'passes', label: '通行证' },
  { id: 'cli', label: 'CLI' }
]
/** 路由实例，退出时跳回登录页。 */
const router = useRouter()
/** 当前路由。地址里带执行编号时，进来就查询那一次执行。 */
const route = useRoute()
/** 当前登录名，显示在左上角。 */
const loginName = ref('')
/** 正在编辑的工作区路径。 */
const workspace = ref('')
/** 可选工作区路径。 */
const workspaceOptions = ref([])
/** 左侧会话列表。 */
const sessions = ref([])
/** 中间栏正在看的会话编号。空字符串表示还没选。 */
const activeSessionId = ref('')
/** 当前会话标题，显示在对话顶栏。 */
const activeTitle = ref('')
/** 正在改名的会话编号。空字符串表示没有进入改名。 */
const editingSessionId = ref('')
/** 改名输入框里的草稿。 */
const editingTitle = ref('')
/** 按 Esc 取消改名时，挡住紧接着的 blur，避免又把标题存回去。 */
const skipRenameBlur = ref(false)
/** 当前会话的子 agent。 */
const agents = ref([])
/** 当前会话的纠正记录。 */
const corrections = ref([])
/** 可提交的工作流。 */
const workflows = ref([])
/** 发送和 CLI 提交使用的工作流编号。 */
const workflowId = ref('answer-with-tool')
/** 斜杠菜单里的 skill。 */
const skills = ref([])
/** CLI 抽屉的执行输入。 */
const cliInput = ref('请回答')
/** 当前关注的执行编号，供中止、恢复和右栏查询使用。不显示在对话气泡里。 */
const executionId = ref('')
/** 当前执行的日志文本。 */
const logs = ref('')
/** 这一轮 SSE status 事件的原文，显示在日志页。 */
const sseLines = ref([])
/** 输入框草稿。 */
const draft = ref('')
/** 模型目录。每一项含 provider、id、label。 */
const models = ref([])
/** 当前会话模型，形如 provider|id。 */
const sessionModel = ref('deepseek|deepseek-flash')
/** 纠正输入框草稿。 */
const noteText = ref('')
/** 当前会话的用户消息。每一项对应一轮。 */
const messages = ref([])
/** 当前执行的详情：状态、工具、通行证、评估。 */
const detail = ref({})
/** 当前会话的上下文预算、规则和 skill。 */
const context = ref({})
/** 当前关注的执行的时间线消息。 */
const timeline = ref([])
/** 按执行编号缓存的时间线，供每一轮的 Trace 使用。 */
const timelines = ref({})
/** 这次页面里是否已经选定过工作区。 */
const workspaceReady = ref(false)
/** 左栏宽度，单位像素。 */
const sessionWidth = ref(260)
/** 右栏展开时的宽度，单位像素。 */
const railWidth = ref(320)
/** 右栏是否展开。收起后 Trace 和任务都不占中间的宽度。 */
const railOpen = ref(true)
/** 正在拖的分隔条。session 是左栏，rail 是右栏，空字符串表示没在拖。 */
const resizing = ref('')
/** 右栏当前页签。选中会话后回到 tasks。 */
const railTab = ref('tasks')
/** 打开的抽屉编号。空字符串表示抽屉关着。 */
const drawer = ref('')
/** 顶栏抽屉菜单是否展开。 */
const drawerMenu = ref(false)
/** 「全部」子 agent 列表是否展开。 */
const agentListOpen = ref(false)
/** 展开摘要的任务行编号。空字符串表示都收着。 */
const openTaskId = ref('')
/** 正在看的子执行。空字符串表示主会话。 */
const focusedExecutionId = ref('')
/** 用户手动展开或收起的任务组。没动过的组：有内容就展开。 */
const sectionChoice = ref({})
/** detail 对应的执行编号，避免把别的执行状态套到当前行上。 */
const detailExecutionId = ref('')
/** 收起 details 的定时器，离开页面时清掉。 */
const foldTimers = []
/** 当前执行的 SSE 连接。换执行或离开页面时关掉。 */
let source = null
/** 执行进行中时每秒补拉详情和时间线的定时器编号。 */
let poll = 0

/** 三列宽度。右栏收起时只留折叠按钮那一列，中间吃掉剩余空间。 */
const layoutStyle = computed(() => ({
  gridTemplateColumns: sessionWidth.value + 'px 8px minmax(0, 1fr) 8px ' + (railOpen.value ? railWidth.value : 36) + 'px'
}))
/** 顶栏上的模型名。目录里有就用标签，否则用模型 id。 */
const currentModelLabel = computed(() => {
  const match = models.value.find((item) => item.provider + '|' + item.id === sessionModel.value)
  if (match) {
    return match.label
  }
  return splitModel(sessionModel.value).id
})
/** 日志页里的 SSE 原文，一行一个事件。 */
const sseText = computed(() => sseLines.value.join('\n'))
/** 输入框末尾的 / 查询。没有斜杠时是 null，用来决定要不要弹出 skill。 */
const slashQuery = computed(() => {
  const matched = draft.value.match(/(?:^|\s)\/([^\s]*)$/)
  return matched ? matched[1] : null
})
/** 按斜杠后面的文字过滤 skill。 */
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
/** 当前抽屉标题。没有打开时是空字符串。 */
const drawerTitle = computed(() => {
  const found = railDrawers.find((item) => item.id === drawer.value)
  return found ? found.label : ''
})
/** 把检索结果摊成一行行溯源。没有命中时用原因占一行。 */
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
/** 仍在执行的子 agent，挂在对话上方。 */
const liveAgents = computed(() => agents.value.filter((agent) => agentIsLive(agent)))
/**
 * 当前请求送到的 agent。先看正在查询的执行详情，再从最近一条带地址的消息往回找。
 * 网关没给实例或地址时，两个字段都是空串，页面显示未分配。
 */
const routedAgent = computed(() => {
  const fromDetail = readRoutedAgent(detail.value)
  if (fromDetail.instanceId || fromDetail.address) {
    return fromDetail
  }
  const list = messages.value
  for (let index = list.length - 1; index >= 0; index -= 1) {
    const found = readRoutedAgent(list[index])
    if (found.instanceId || found.address) {
      return found
    }
  }
  return { instanceId: '', address: '' }
})
/** 主会话显示全部消息；点进子任务后只留那一次执行。 */
const visibleMessages = computed(() => {
  if (!focusedExecutionId.value) {
    return messages.value
  }
  return messages.value.filter((message) => message.executionId === focusedExecutionId.value)
})
/** 子会话顶栏标题，用这一轮的用户原话。 */
const focusedTitle = computed(() => {
  const message = messages.value.find((item) => item.executionId === focusedExecutionId.value)
  return message && message.text ? shortTitle(message.text) : ''
})
/** 主会话状态：有子任务在跑就是进行中，有失败且没有在跑就是失败，否则完成。 */
const mainStatusLabel = computed(() => {
  const labels = agents.value.map((agent) => statusLabel(resolvedStatus(agent.status, agent.executionId)))
  if (labels.includes('进行中')) {
    return '进行中'
  }
  if (labels.includes('失败')) {
    return '失败'
  }
  if (!messages.value.length && !agents.value.length) {
    return ''
  }
  return '完成'
})
/** 右栏 Trace。每一轮默认收起，里面是思考、工具和回复步骤。 */
const traceTurns = computed(() => {
  const turns = []
  messages.value.forEach((message, index) => {
    const steps = absorbLive(traceSteps(turnRows(message)), message && message.thinking)
    if (!steps.length) {
      return
    }
    turns.push({
      id: message.executionId || 'turn-' + index,
      title: shortTitle(message.text),
      steps
    })
  })
  return turns
})
/**
 * 右栏任务按状态分成三组。主会话算一组里的一行，子任务缩在它下面，不另占一组。
 * 数量是这一组里能看见的行数。
 */
const taskSections = computed(() => {
  const status = mainStatusLabel.value || '待看'
  const groupId = status === '进行中' ? 'active' : status === '完成' ? 'done' : 'review'
  const children = agents.value.slice()
  const sessions = activeSessionId.value
    ? [{
        id: activeSessionId.value,
        title: activeTitle.value || '主会话',
        status: mainStatusLabel.value,
        children
      }]
    : []
  const sections = [
    { id: 'active', label: '进行中', sessions: [] },
    { id: 'review', label: '待看', sessions: [] },
    { id: 'done', label: '完成', sessions: [] }
  ]
  const section = sections.find((item) => item.id === groupId)
  if (section) {
    section.sessions = sessions
  }
  return sections.map((item) => ({
    ...item,
    count: item.sessions.reduce((sum, session) => sum + 1 + session.children.length, 0)
  }))
})

/** 进入页面时读取登录信息、模型、工作流、skill 和会话。地址上有执行编号就接着查它。 */
onMounted(async () => {
  await loadProfile()
  await loadModels()
  await Promise.all([loadWorkflows(), loadSkills(), loadSessions()])
  if (route.params.id) {
    executionId.value = String(route.params.id)
    await queryExecution()
  }
})

/** 离开页面时关掉 SSE、停止轮询，并清掉折叠动画的定时器。 */
onBeforeUnmount(() => {
  closeStream()
  window.clearInterval(poll)
  for (const timer of foldTimers) {
    window.clearTimeout(timer)
  }
})

/**
 * 展开或收起右栏。收起后 Trace 不占位，再点按原来的页签打开。
 * 副作用：只改 railOpen。
 */
function toggleRail() {
  railOpen.value = !railOpen.value
}

/**
 * 切换右栏页签，并顺手关上抽屉菜单。
 *
 * @param id 页签编号：tasks、trace、tools、context、logs
 */
function selectRailTab(id) {
  railTab.value = id
  drawerMenu.value = false
}

/**
 * 任务组默认：有行就展开。用户点过之后记住选择。
 *
 * @param id 组编号 active、review、done
 * @param count 这一组的行数
 * @return 是否展开
 */
function sectionIsOpen(id, count) {
  if (Object.prototype.hasOwnProperty.call(sectionChoice.value, id)) {
    return sectionChoice.value[id]
  }
  return count > 0
}

/**
 * 记下用户把这一组展开还是收起。
 *
 * @param id 组编号
 * @param event details 的 toggle 事件
 */
function rememberSection(id, event) {
  const open = !!(event.currentTarget && event.currentTarget.open)
  if (sectionChoice.value[id] === open) {
    return
  }
  sectionChoice.value = { ...sectionChoice.value, [id]: open }
}

/**
 * 把状态收成大写，空值当成空字符串。
 *
 * @param status 执行或子 agent 的原始状态
 * @return 大写状态
 */
function normalizeStatus(status) {
  return String(status || '').toUpperCase()
}

/**
 * 优先用这条执行已经拉到的状态，而不是子 agent 身上的旧状态。
 *
 * @param id 执行编号
 * @return 已知状态；查不到时是空字符串
 */
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

/**
 * 展示用状态。执行详情里有就用详情，否则用调用方给的兜底值。
 *
 * @param fallback 子 agent 或消息上的状态
 * @param id 执行编号
 * @return 大写状态
 */
function resolvedStatus(fallback, id) {
  return knownExecutionStatus(id) || normalizeStatus(fallback)
}

/**
 * 把状态分进任务组。
 *
 * @param status 原始或已解析的状态
 * @return active、review 或 done
 */
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

/**
 * 这个子 agent 是否还在执行。
 *
 * @param agent 子 agent
 * @return 进行中时为 true
 */
function agentIsLive(agent) {
  return taskBucket(resolvedStatus(agent.status, agent.executionId)) === 'active'
}

/**
 * 把标题收成一行，超过 28 字加省略号。
 *
 * @param value 原始标题或用户原文
 * @return 展示用短标题
 */
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

/**
 * 任务行展开后的摘要：角色、错误、输出摘录和执行编号。
 *
 * @param row 任务行
 * @return 多行文本
 */
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

/** 展开或收起全部子 agent 列表。 */
function toggleAgentList() {
  agentListOpen.value = !agentListOpen.value
}

/**
 * 从全部列表里选一个子 agent，并让右栏去看它的执行。
 *
 * @param agent 子 agent
 */
function pickAgent(agent) {
  agentListOpen.value = false
  openSubSession(agent)
}

/**
 * 展开或收起一条任务，并加载它的执行详情和时间线。
 *
 * @param row 任务行
 */
function toggleTask(row) {
  openTaskId.value = openTaskId.value === row.id ? '' : row.id
  if (!row.executionId) {
    return
  }
  executionId.value = row.executionId
  queryExecution().catch(() => {})
  loadTimeline(row.executionId).catch(() => {})
}

/** 展开或收起顶栏抽屉菜单。 */
function toggleDrawerMenu() {
  drawerMenu.value = !drawerMenu.value
}

/**
 * 打开一块抽屉。
 *
 * @param name 抽屉编号：retrieval、evaluation、passes、cli
 */
function openDrawer(name) {
  drawer.value = name
  drawerMenu.value = false
}

/** 关掉抽屉。 */
function closeDrawer() {
  drawer.value = ''
}

/**
 * 收起 details。第一次点击仍交给浏览器展开；已经打开时用 0.2 秒收回。
 * 思考和 Trace 步骤都走这里，所以默认不带 open。
 *
 * @param event summary 的点击事件
 */
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

/**
 * 开始拖分隔条。
 *
 * @param event 按下时的鼠标事件，用 clientX 当起点
 * @param key session 拖左栏，rail 拖右栏
 */
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

/**
 * 取出这一轮缓存的时间线。
 *
 * @param message 会话消息
 * @return 时间线行；缺少执行编号时是空数组
 */
function turnRows(message) {
  if (!message || !message.executionId) {
    return []
  }
  return timelines.value[message.executionId] || []
}

/**
 * 只有最后一轮、并且真有思考文字时，才在正文上面放思考块。
 *
 * @param message 会话消息
 * @param index 它在消息列表里的位置
 * @return 要不要渲染思考
 */
function showThinking(message, index) {
  return index === visibleMessages.value.length - 1 && !!turnThinking(message)
}

/**
 * 把执行状态收成图标用的 data-status。页面不再把这三个词写进任务行。
 *
 * @param status 已解析的大写状态
 * @return 进行中、完成或失败，只交给图标
 */
function statusLabel(status) {
  const value = normalizeStatus(status)
  if (value === 'FAILED' || value === 'CANCELLED') {
    return '失败'
  }
  if (taskBucket(value) === 'done') {
    return '完成'
  }
  return '进行中'
}

/**
 * 子任务标题。优先用这一轮的用户原话，否则用子 agent 编号。
 *
 * @param agent 子 agent
 * @return 一行标题
 */
function subTitle(agent) {
  const titled = agent && agent.title ? String(agent.title) : ''
  if (titled.trim()) {
    return shortTitle(titled)
  }
  const message = messages.value.find((item) => agent && item.executionId && item.executionId === agent.executionId)
  if (message && message.text) {
    return shortTitle(message.text)
  }
  return agent && agent.agentId ? agent.agentId : '子任务'
}

/** 回到主会话，中间栏重新显示全部轮次。 */
function showMainSession() {
  focusedExecutionId.value = ''
}

/**
 * 进入子 agent 的这一次执行，中间栏只留它的对话和结果。
 *
 * @param agent 子 agent
 */
function openSubSession(agent) {
  focusedExecutionId.value = agent && agent.executionId ? agent.executionId : ''
  if (agent) {
    openAgent(agent)
  }
}

/**
 * 把工具结果或思考收成一行摘要，避免 Trace 里铺开全文。
 *
 * @param text 原文
 * @param limit 最多保留的字数，默认 72
 * @return 单行摘要；空文本返回空字符串
 */
function brief(text, limit = 72) {
  const flat = String(text || '').replace(/\s+/g, ' ').trim()
  if (!flat) {
    return ''
  }
  if (flat.length <= limit) {
    return flat
  }
  return flat.slice(0, limit) + '…'
}

/**
 * 把耗时收成「14 ms」或「1.2 s」。
 *
 * @param value 毫秒；缺失或不是数字时不显示
 * @return 展示文字；不能显示时是空字符串
 */
function formatElapsed(value) {
  if (value == null || value === '') {
    return ''
  }
  const ms = Number(value)
  if (!Number.isFinite(ms) || ms < 0) {
    return ''
  }
  if (ms < 1000) {
    return Math.round(ms) + ' ms'
  }
  const seconds = ms / 1000
  if (seconds < 10) {
    return seconds.toFixed(1) + ' s'
  }
  return Math.round(seconds) + ' s'
}

/**
 * 工具步骤点开后的全文：有参数时先写参数，再写结果。
 *
 * @param row 类型为 tool 的时间线行
 * @return 折叠体内的文本
 */
function toolBody(row) {
  const args = String((row && row.arguments) || '').trim()
  const content = String((row && row.content) || '')
  if (!args) {
    return content
  }
  return '参数\n' + args + '\n\n结果\n' + content
}

/**
 * 做一条思考步骤。摘要只有一行，全文留在 body。
 *
 * @param text 思考全文
 * @param elapsed 已经格式化的耗时，没有时传空字符串
 * @return Trace 步骤
 */
function thinkingStep(text, elapsed) {
  return {
    kind: 'thinking',
    name: '思考',
    elapsed: elapsed || '',
    summary: brief(text),
    body: text
  }
}

/**
 * 把一条时间线收成 Trace 步骤。系统提示和用户原文不单列，文件全文只进 body。
 *
 * @param rows 时间线行
 * @return 步骤数组
 */
function traceSteps(rows) {
  const steps = []
  let sawThinking = false
  for (const row of rows || []) {
    if (!row || !row.type) {
      continue
    }
    if (row.type === 'thinking') {
      const body = cleanThinking(row.content)
      if (!body) {
        continue
      }
      sawThinking = true
      steps.push(thinkingStep(body, formatElapsed(row.elapsedMs)))
    } else if (row.type === 'tool') {
      steps.push({
        kind: 'tool',
        name: row.name || 'tool',
        elapsed: formatElapsed(row.elapsedMs),
        summary: brief(row.content) || '无结果',
        body: toolBody(row)
      })
    } else if (row.type === 'ai') {
      const thought = cleanThinking(row.thinking)
      if (thought && !sawThinking) {
        sawThinking = true
        steps.push(thinkingStep(thought, ''))
      }
      const reply = String(row.content || '').trim()
      if (reply) {
        steps.push({
          kind: 'reply',
          name: '回复',
          elapsed: '',
          summary: brief(reply),
          body: String(row.content || '')
        })
      }
    }
  }
  return steps
}

/**
 * 把 SSE 里还在长的思考补进步骤。已经记进时间线的不再重复。
 *
 * @param steps 已经从时间线做出的步骤
 * @param live 这条消息上最新的思考全文
 * @return 补过实时思考的步骤
 */
function absorbLive(steps, live) {
  const text = cleanThinking(live)
  if (!text) {
    return steps
  }
  const thoughts = []
  for (const step of steps) {
    if (step.kind === 'thinking' && step.body) {
      thoughts.push(step.body)
    }
  }
  const joined = thoughts.join('\n\n')
  if (!joined) {
    return steps.concat([thinkingStep(text, '')])
  }
  if (text === joined || joined.startsWith(text)) {
    return steps
  }
  if (text.startsWith(joined)) {
    const extra = text.slice(joined.length).replace(/^\n+/, '')
    if (!extra) {
      return steps
    }
    return steps.concat([thinkingStep(extra, '')])
  }
  if (thoughts.includes(text)) {
    return steps
  }
  return steps.concat([thinkingStep(text, '')])
}

/**
 * 按执行编号找会话消息，找不到就用最后一条。
 *
 * @param id 执行编号
 * @return 消息；列表是空的时是 null
 */
function messageForExecution(id) {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (id && messages.value[index].executionId === id) {
      return messages.value[index]
    }
  }
  return messages.value[messages.value.length - 1] || null
}

/**
 * 把 SSE thinking 事件写进对应消息。空白事件不覆盖已有思考。
 *
 * @param raw 事件数据，是到目前为止的思考全文
 */
function applyThinking(raw) {
  const text = raw == null ? '' : String(raw)
  if (!text.trim()) {
    return
  }
  const message = messageForExecution(executionId.value)
  if (!message) {
    return
  }
  message.thinking = text
}

/**
 * 这一轮要显示的思考。时间线上的模型思考优先，否则用消息上的实时思考。
 * 思考和正文完全一样时不显示，避免两块重复。
 *
 * @param message 会话消息
 * @return 思考全文；不该显示时是空字符串
 */
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
  const thinking = fromRows || cleanThinking(message && message.thinking)
  const reply = turnReply(message)
  if (!thinking || thinking === reply) {
    return ''
  }
  return thinking
}

/**
 * 去掉思考两侧空白。
 *
 * @param value 原始思考
 * @return 去掉空白后的文字；空值返回空字符串
 */
function cleanThinking(value) {
  if (value == null) {
    return ''
  }
  return String(value).trim()
}

/**
 * 这一轮的助手正文。先用执行输出，否则用时间线上最后一条模型回复。
 *
 * @param message 会话消息
 * @return 正文；暂时没有时是空字符串
 */
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

/** 读取登录名和可选工作区，并填上当前工作区。 */
async function loadProfile() {
  const me = await api('/api/me')
  loginName.value = me.login
  const listed = await api('/api/workspaces')
  workspaceOptions.value = listed.workspaces || []
  workspace.value = me.workspace || listed.workspace || workspaceOptions.value[0] || ''
}

/** 提交工作区路径。成功后刷新 skill，失败时弹出错误。 */
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

/** 读取工作流列表。当前选中的不在列表里时改选第一项。 */
async function loadWorkflows() {
  workflows.value = await api('/api/workflows')
  if (!workflows.value.some((item) => item.id === workflowId.value) && workflows.value.length) {
    workflowId.value = workflows.value[0].id
  }
}

/** 读取斜杠菜单用的 skill。 */
async function loadSkills() {
  skills.value = await api('/api/skills')
}

/** 重新读取左侧会话列表。 */
async function loadSessions() {
  sessions.value = await api('/api/sessions')
}

/**
 * 读取模型目录。保留接口返回的 DeepSeek，并加上 grok-4.7。
 * 接口失败或给了空列表时，仍保留 deepseek-flash 和 grok-4.7。
 * 副作用：改 models。
 */
async function loadModels() {
  try {
    models.value = consoleModels(await api('/api/models'))
  } catch {
    models.value = consoleModels(null)
  }
}

/**
 * 把 provider|id 拆开。没有竖线时供应方按 deepseek。
 *
 * @param value 选择框的值
 * @return provider 和 id
 */
function splitModel(value) {
  const text = String(value || '')
  const cut = text.indexOf('|')
  if (cut < 0) {
    return { provider: 'deepseek', id: text || 'deepseek-flash' }
  }
  return { provider: text.slice(0, cut) || 'deepseek', id: text.slice(cut + 1) || 'deepseek-flash' }
}

/**
 * 默认模型键。目录里有 deepseek-flash 就用它，否则用第一项。
 *
 * @return provider|id
 */
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

/**
 * 把会话记录里的模型写进选择框。目录里没有这项时退回默认模型。
 *
 * @param stored 会话对象，读 model 和 modelProvider
 */
function rememberModel(stored) {
  const key = (stored.modelProvider || 'deepseek') + '|' + (stored.model || 'deepseek-flash')
  const known = models.value.some((item) => item.provider + '|' + item.id === key)
  sessionModel.value = known ? key : defaultModelKey()
}

/**
 * 把选择框里的模型存到当前会话。失败时重新读回服务器上的值。
 *
 * @param value provider|id
 */
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

/** 新建会话并立刻打开它。标题由服务器写成「新会话」。 */
async function createSession() {
  const created = await api('/api/sessions', { method: 'POST', body: { title: '新会话' } })
  await loadSessions()
  await switchSession(created.sessionId)
}

/**
 * 进入改名，并选中输入框里的原文。
 *
 * @param item 会话列表项
 */
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

/** 取消改名，并让随后的 blur 不要再保存。 */
function cancelRename() {
  skipRenameBlur.value = true
  editingSessionId.value = ''
  editingTitle.value = ''
}

/**
 * 输入框失焦时保存。如果刚刚按了 Esc，这次 blur 不保存。
 *
 * @param item 会话列表项
 */
function onRenameBlur(item) {
  if (skipRenameBlur.value) {
    skipRenameBlur.value = false
    return
  }
  commitRename(item)
}

/**
 * 把标题写到服务器。空白或没改过的标题只退出编辑。
 *
 * @param item 会话列表项
 */
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

/**
 * 打开一条会话。右栏回到任务，并加载消息、时间线和上下文。
 *
 * @param sessionId 会话编号
 */
async function switchSession(sessionId) {
  activeSessionId.value = sessionId
  focusedExecutionId.value = ''
  railTab.value = 'tasks'
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

/**
 * 删除一条会话。删的是当前这条时，清空中间栏和右栏。
 *
 * @param sessionId 会话编号
 */
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

/**
 * 把输入框末尾的斜杠换成选中的 skill。
 *
 * @param skill skill 对象，使用它的 name
 */
function insertSkill(skill) {
  draft.value = draft.value.replace(/(?:^|\s)\/([^\s]*)$/, (all) => {
    const lead = all.startsWith('/') ? '' : all[0]
    return lead + '/skill:' + skill.name + ' '
  })
}

/** 发送输入框里的文字，并订阅这一轮的 SSE。空白输入不发送。 */
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

/** 给当前会话追加一条纠正。没选会话或没写文字时只提示。 */
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

/** 读取当前会话的上下文。失败时保留原来的值。 */
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

/**
 * 让右栏查看这个子 agent 的执行和时间线。
 *
 * @param agent 子 agent，使用它的 executionId
 */
async function openAgent(agent) {
  executionId.value = agent.executionId || executionId.value
  try {
    await queryExecution()
  } catch {
    detail.value = detail.value || {}
  }
  await loadTimeline(executionId.value)
}

/**
 * 读取一条执行的时间线，并放进按编号缓存的表。
 *
 * @param id 执行编号；空字符串表示让服务器选最近一条
 */
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

/** 把当前会话每一轮的时间线都读进缓存。 */
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

/** 从 CLI 抽屉提交一次执行，并订阅它的 SSE。 */
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

/** 读取当前执行的详情和日志，并刷新消息上的输出。 */
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

/** 中止当前执行。没有执行编号时什么也不做。 */
async function interruptExecution() {
  if (!executionId.value) {
    return
  }
  detail.value = await api('/api/executions/' + executionId.value + '/interrupt', { method: 'POST', body: {} })
  logs.value = detail.value.logs || logs.value
}

/** 恢复当前执行。失败时弹出错误并重新查询状态。 */
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

/**
 * 对一张通行证做出允许或拒绝。
 *
 * @param row 通行证，使用它的 passId
 * @param decision ALLOW 或 DENY
 */
async function decide(row, decision) {
  detail.value = await api('/api/executions/' + executionId.value + '/passes/' + row.passId, {
    method: 'POST',
    body: { decision }
  })
}

/**
 * 订阅一次执行的 SSE。thinking 事件立刻写到最后一轮，status 事件再拉详情和时间线。
 *
 * @param id 执行编号
 */
function watchExecution(id) {
  closeStream()
  sseLines.value = []
  source = new EventSource('/api/executions/' + id + '/events')
  source.addEventListener('thinking', (event) => {
    applyThinking(event.data)
  })
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

/** 关掉当前的 SSE 连接。 */
function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

/** 按执行详情刷新每条消息的状态、错误、正文，以及网关写上的实例和地址。空白思考不覆盖正在流式增长的思考。 */
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
      const routed = readRoutedAgent(row)
      if (routed.instanceId) {
        message.agentInstanceId = routed.instanceId
      }
      if (routed.address) {
        message.agentAddress = routed.address
      }
      if (row.thinking) {
        message.thinking = row.thinking
      }
    } catch {
      // 执行可能还不属于这次登录。
    }
  }
}

/** 退出登录并回到登录页。 */
async function logout() {
  await api('/logout', { method: 'POST' })
  await router.push('/login')
}
</script>
