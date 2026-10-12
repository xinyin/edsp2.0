<script setup lang="ts">
defineProps<{ current: string; busy?: boolean }>()
defineEmits<{ navigate: [menu: string] }>()
const stages = [
  { key: 'resource', title: '接入资源', description: '连接地址 · 原始报文' },
  { key: 'service-development', title: '开发服务', description: '标准契约 · 转换规则' },
  { key: 'releases', title: '发布服务', description: '版本管理 · 上下线' },
  { key: 'authorization', title: '授权使用', description: '调用系统 · 访问权限' }
]
</script>

<template>
  <nav class="workspace-journey" aria-label="服务交付流程">
    <button v-for="(stage, index) in stages" :key="stage.key" type="button"
      :class="{ active: current === stage.key }" :aria-current="current === stage.key ? 'step' : undefined"
      :disabled="busy" @click="$emit('navigate', stage.key)">
      <span class="journey-index">{{ index + 1 }}</span>
      <span><strong>{{ stage.title }}</strong><small>{{ stage.description }}</small></span>
      <span v-if="index < stages.length - 1" class="journey-arrow" aria-hidden="true">›</span>
    </button>
  </nav>
</template>
