<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h1>登录</h1>
      <p class="muted">JaSAgent 管理控制台</p>
      <el-form @submit.prevent="submit">
        <el-form-item label="用户">
          <el-input v-model="user" name="user" autocomplete="username" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input v-model="password" name="password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="pending">登录</el-button>
        <p v-if="error" class="muted">{{ error }}</p>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
/** 登录页。成功后进入会话控制台，失败只显示「登录失败」。 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

/** 用来在登录成功后跳进会话页。 */
const router = useRouter()
/** 登录名草稿。 */
const user = ref('')
/** 口令草稿，只放在这次提交里。 */
const password = ref('')
/** 正在提交时禁用重复点击。 */
const pending = ref(false)
/** 失败提示。空字符串表示没有错误。 */
const error = ref('')

/**
 * 用表单提交登录。口令不写进 JSON。
 * 成功后进入首页；失败时留下「登录失败」，不抛出页面错误。
 */
async function submit() {
  pending.value = true
  error.value = ''
  try {
    const body = new URLSearchParams()
    body.set('user', user.value)
    body.set('password', password.value)
    await api('/login', { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
    await router.push('/')
  } catch {
    error.value = '登录失败'
  } finally {
    pending.value = false
  }
}
</script>
