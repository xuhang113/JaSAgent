<!-- 对话两侧的图形。可见内容只有图标，不写角色汉字。 -->
<template>
  <span class="role-icon" :data-role="role" role="img" :aria-label="accessibleName">
    <svg v-if="role === 'user'" viewBox="0 0 16 16" aria-hidden="true">
      <circle cx="8" cy="5" r="2.2" fill="none" stroke="currentColor" stroke-width="1.4" />
      <path d="M3.2 13.2c.8-2.4 2.4-3.4 4.8-3.4s4 1 4.8 3.4" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
    </svg>
    <svg v-else viewBox="0 0 16 16" aria-hidden="true">
      <rect x="2.2" y="2.2" width="11.6" height="11.6" rx="3" fill="none" stroke="currentColor" stroke-width="1.4" />
      <path d="M8 5.2v5.6M5.2 8h5.6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
    </svg>
  </span>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 用图形区分发话的人和模型回复。
 * role 只接受 user 或 assistant。其它值按 assistant 的图形画，避免回落到汉字。
 */
const props = defineProps({
  /**
   * 哪一侧。user 是人的头像轮廓，assistant 是方框加十字。
   */
  role: {
    type: String,
    required: true
  }
})

/**
 * 读屏用的名字，不用页面上禁止的角色汉字。
 * user 读成 user，其余读成 assistant。
 */
const accessibleName = computed(() => (props.role === 'user' ? 'user' : 'assistant'))
</script>
