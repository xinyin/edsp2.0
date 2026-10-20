<script setup lang="ts">
import { DocumentCopy } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const props = defineProps<{ value: string }>()

async function copy() {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(props.value)
    } else {
      const input = document.createElement('textarea')
      input.value = props.value
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      input.select()
      try {
        if (!document.execCommand('copy')) throw new Error('Copy command failed')
      } finally {
        input.remove()
      }
    }
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请选择文本后手动复制')
  }
}
</script>

<template>
  <div class="copyable-block">
    <slot />
    <button class="copyable-block-button" type="button" title="复制内容" aria-label="复制内容" @click="copy">
      <DocumentCopy />
    </button>
  </div>
</template>

<style scoped>
.copyable-block { position: relative; min-width: 0; width: 100%; }
.copyable-block :deep(.el-textarea__inner),
.copyable-block :deep(pre) { padding-right: 44px !important; }
.copyable-block-button {
  position: absolute; top: 8px; right: 8px; z-index: 1;
  display: grid; place-items: center; width: 27px; height: 27px;
  border: 1px solid #cbd9e6; border-radius: 3px;
  color: #426987; background: #fff; cursor: pointer;
}
.copyable-block-button:hover { border-color: #8db3d2; color: #104d80; background: #eef6fc; }
.copyable-block-button:focus-visible { outline: 2px solid #1766a6; outline-offset: 2px; }
.copyable-block-button svg { width: 14px; height: 14px; }
</style>
