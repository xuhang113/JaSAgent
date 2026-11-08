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
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

const router = useRouter()
const user = ref('')
const password = ref('')
const pending = ref(false)
const error = ref('')

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
