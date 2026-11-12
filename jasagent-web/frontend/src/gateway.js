/**
 * 网关管理控制台认的模型目录和集群字段。
 * 页面只读这里写明的字段；网关没返回时留空，不在浏览器里编造某台 agent。
 */

/** DeepSeek 在会话里使用的供应商标识，和现有 /api/models 的 provider 一致。 */
export const DEEPSEEK_PROVIDER = 'deepseek'

/** 现有默认 DeepSeek 模型 id。接口没有给出任何模型时，选择框仍保留这一项。 */
export const DEEPSEEK_FLASH = 'deepseek-flash'

/** 这次加进选择框的模型 id。 */
export const GROK_MODEL_ID = 'grok-4.7'

/** grok-4.7 写入会话时的供应商标识。保存模型时 modelProvider 用这个字符串。 */
export const GROK_PROVIDER = 'grok'

/**
 * 固定追加的 grok-4.7。
 * id、provider、label 都指向这一项，不替换接口里已有的 DeepSeek。
 */
export const GROK_MODEL = Object.freeze({
  id: GROK_MODEL_ID,
  provider: GROK_PROVIDER,
  label: GROK_MODEL_ID
})

/**
 * 接口没有模型时放回选择框的 DeepSeek。
 * 只保留现有的 deepseek-flash，不另造一份供应商模型。
 */
export const DEEPSEEK_FALLBACK = Object.freeze({
  id: DEEPSEEK_FLASH,
  provider: DEEPSEEK_PROVIDER,
  label: DEEPSEEK_FLASH
})

/** 集群列表路径。网关在这个路径返回 agents 数组。 */
export const CLUSTER_PATH = '/api/cluster'

/**
 * 把任意值收成去掉空白的字符串。null 和 undefined 变成空串。
 *
 * @param value 接口里的原始字段
 * @return 去掉首尾空白后的文本；没有内容时是空串
 */
function text(value) {
  if (value == null) {
    return ''
  }
  return String(value).trim()
}

/**
 * 复制成选择框使用的一项。缺 label 时用 id。没有 id 或 provider 的项丢掉。
 *
 * @param item 接口里的一条模型
 * @return 含 id、provider、label 的对象；不合格时是 null
 */
function modelChoice(item) {
  const id = text(item && item.id)
  const provider = text(item && item.provider)
  if (!id || !provider) {
    return null
  }
  return {
    id,
    provider,
    label: text(item.label) || id
  }
}

/**
 * 判断这一项是不是要追加的 grok-4.7，避免目录里已经有它时再加一次。
 *
 * @param item 已整理过的模型项，含 provider 和 id
 * @return provider 与 id 都等于 grok-4.7 这一项时为 true
 */
function isGrok(item) {
  return item.provider === GROK_MODEL.provider && item.id === GROK_MODEL.id
}

/**
 * 保留接口给出的模型，并在末尾加上 grok-4.7。
 * 入参里的 DeepSeek 保持原来的顺序。已经有同 provider、同 id 的 grok-4.7 时不重复追加。
 *
 * @param listed 接口 models 数组；不是数组时按空数组处理
 * @return 新数组，不修改入参
 */
export function withConsoleModels(listed) {
  const source = Array.isArray(listed) ? listed : []
  const models = []
  for (const item of source) {
    const choice = modelChoice(item)
    if (choice) {
      models.push(choice)
    }
  }
  if (!models.some(isGrok)) {
    models.push({
      id: GROK_MODEL.id,
      provider: GROK_MODEL.provider,
      label: GROK_MODEL.label
    })
  }
  return models
}

/**
 * 从 /api/models 的响应得到选择框列表。
 * models 有内容时全部保留，再追加 grok-4.7。
 * models 缺失或是空数组时保留 deepseek-flash，再追加 grok-4.7。
 *
 * @param body 接口 JSON，失败或空响应时可以是 null
 * @return 选择框使用的模型数组
 */
export function consoleModels(body) {
  const listed = body && Array.isArray(body.models) ? body.models : []
  if (!listed.length) {
    return withConsoleModels([DEEPSEEK_FALLBACK])
  }
  return withConsoleModels(listed)
}

/**
 * 从一条网关响应里取出当前请求落到的 agent。
 * 实例认 agentInstanceId，没有时再认 instanceId。
 * 地址认 agentAddress，没有时再认 address。
 *
 * @param source 执行详情、消息或会话对象，可以缺字段，也可以是 null
 * @return instanceId 与 address；没有时对应字段是空串
 */
export function readRoutedAgent(source) {
  const row = source && typeof source === 'object' ? source : {}
  return {
    instanceId: text(row.agentInstanceId) || text(row.instanceId),
    address: text(row.agentAddress) || text(row.address)
  }
}

/**
 * 把 /api/cluster 的响应收成表格行。
 * 每一行有 instanceId、address、online、connectionAlive、stickyUsers、lastHeartbeat。
 * online 表示这台 agent 是否在线。connectionAlive 表示到它的长连接是否还在。
 * stickyUsers 是粘在这台上的登录名。lastHeartbeat 是最近一次心跳的原文。
 * 缺字段时用空串或 false，不把缺省当成在线。
 *
 * @param body 集群接口 JSON，可以是 null
 * @return 表格行；没有 agents 数组时是空数组
 */
export function clusterRows(body) {
  const list = body && Array.isArray(body.agents) ? body.agents : []
  return list.map((item) => {
    const row = item && typeof item === 'object' ? item : {}
    const sticky = Array.isArray(row.stickyUsers) ? row.stickyUsers : []
    return {
      instanceId: text(row.instanceId),
      address: text(row.address),
      online: row.online === true,
      connectionAlive: row.connectionAlive === true,
      stickyUsers: sticky.map((name) => text(name)).filter((name) => name.length > 0),
      lastHeartbeat: text(row.lastHeartbeat)
    }
  })
}
