<template>
  <div class="login-wrap" data-register-page>
    <el-card class="login-card">
      <h1>注册</h1>
      <p class="muted">注册后得到自己的租户，不能加入已有租户。</p>
      <el-form @submit.prevent="submit">
        <el-form-item label="登录名">
          <el-input v-model="user" name="user" autocomplete="username" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input v-model="password" name="password" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="pending">注册</el-button>
        <p class="muted"><router-link to="/login">已有账号，去登录</router-link></p>
        <p v-if="error" class="muted">{{ error }}</p>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
/** 注册页。成功后用同一份登录名和口令登录，再进入会话壳。失败不回显口令。 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

/** 注册成功后进入会话壳。 */
const router = useRouter()
/** 登录名草稿。 */
const user = ref('')
/** 口令草稿。只放在这次提交里，不写进页面错误。 */
const password = ref('')
/** 正在提交时禁用重复点击。 */
const pending = ref(false)
/** 失败提示。空字符串表示没有错误。 */
const error = ref('')

/**
 * 先注册再登录。口令只放在表单正文里。正在提交时忽略重复点击。
 * 成功后清空草稿并进入首页；失败时提示「注册失败」，不回显口令。
 */
async function submit() {
  if (pending.value) {
    return
  }
  pending.value = true
  error.value = ''
  const login = user.value
  const secret = password.value
  try {
    const body = new URLSearchParams()
    body.set('user', login)
    body.set('password', secret)
    await api('/register', { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
    await api('/login', { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
    password.value = ''
    await router.push('/')
  } catch {
    error.value = '注册失败'
  } finally {
    pending.value = false
  }
}
</script>
