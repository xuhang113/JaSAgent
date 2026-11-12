<!-- 集群页：每台 agent 的在线、长连接、粘着登录名和最近心跳。 -->
<template>
  <div class="cluster-page">
    <header class="chrome-bar cluster-head">
      <router-link class="rail-tool" to="/">会话</router-link>
      <h1>集群</h1>
      <el-button size="small" class="cursor-btn-ghost" :loading="pending" @click="load">刷新</el-button>
    </header>
    <p v-if="error" class="muted empty-line">{{ error }}</p>
    <p v-else-if="!pending && rows.length === 0" class="muted empty-line">没有 agent。</p>
    <table v-else class="cluster-table">
      <thead>
        <tr>
          <th>实例</th>
          <th>地址</th>
          <th>在线</th>
          <th>长连接</th>
          <th>粘着用户</th>
          <th>最近心跳</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="row.instanceId || index" data-cluster-row>
          <td class="mono-num" data-instance>{{ row.instanceId || '无' }}</td>
          <td class="mono-num" data-address>{{ row.address || '无' }}</td>
          <td>
            <span class="cluster-mark" :data-on="row.online ? 'true' : 'false'" role="img" :aria-label="row.online ? 'online' : 'offline'" />
          </td>
          <td>
            <span class="cluster-mark" :data-on="row.connectionAlive ? 'true' : 'false'" role="img" :aria-label="row.connectionAlive ? 'connected' : 'disconnected'" />
          </td>
          <td data-sticky>{{ stickyLine(row.stickyUsers) }}</td>
          <td class="mono-num" data-heartbeat>{{ heartbeatLine(row.lastHeartbeat) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
/** 集群页。数据只来自网关的 /api/cluster，失败时不填假的 agent。 */
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { CLUSTER_PATH, clusterRows } from '../gateway'

/** 表格行。每一项是 clusterRows 整理后的一台 agent。 */
const rows = ref([])
/** 刷新进行中时，按钮进入等待，避免连点打两次。 */
const pending = ref(false)
/** 接口失败时的一句说明。空字符串表示当前没有错误。 */
const error = ref('')

/**
 * 把粘着的登录名拼成一行。没有人时显示「无」。
 *
 * @param names 登录名数组，来自这一行的 stickyUsers
 * @return 用顿号连起来的登录名，或「无」
 */
function stickyLine(names) {
  if (!names || names.length === 0) {
    return '无'
  }
  return names.join('、')
}

/**
 * 心跳原文。空白时显示「无」，不把空串伪装成时间。
 *
 * @param value 这一行的 lastHeartbeat
 * @return 接口原文，或「无」
 */
function heartbeatLine(value) {
  return value ? value : '无'
}

/**
 * 向网关拉取集群。成功后换掉表格；失败时清空行并留下「集群接口不可用」。
 * 副作用：改 rows、error、pending。
 */
async function load() {
  pending.value = true
  error.value = ''
  try {
    rows.value = clusterRows(await api(CLUSTER_PATH))
  } catch {
    rows.value = []
    error.value = '集群接口不可用'
  } finally {
    pending.value = false
  }
}

/** 进入页面就拉一次集群。 */
onMounted(() => {
  load()
})
</script>
