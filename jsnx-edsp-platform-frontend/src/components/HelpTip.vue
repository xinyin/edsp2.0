<script setup lang="ts">
withDefaults(defineProps<{ title?: string; content: string; width?: number }>(), {
  title: '配置说明', width: 300
})
</script>

<template>
  <el-popover trigger="click" :width="width" :title="title" :content="content" placement="bottom-start" popper-class="console-help-popover">
    <template #reference>
      <button class="help-tip" type="button" :aria-label="`查看${title}`" @click.stop><span aria-hidden="true">!</span></button>
    </template>
  </el-popover>
</template>

<style>
.help-tip { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 24px; width: 24px; height: 24px; margin-left: 4px; padding: 0; color: #65758a; background: transparent; border: 0; border-radius: 4px; vertical-align: middle; cursor: pointer; }
.help-tip > span { display: inline-flex; align-items: center; justify-content: center; width: 15px; height: 15px; border: 1.3px solid currentColor; border-radius: 50%; font: 600 11px/1 Arial, sans-serif; }
.help-tip:hover, .help-tip:focus-visible { color: var(--brand); background: var(--brand-soft); }
.console-help-popover { color: var(--text-regular); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.console-help-popover .el-popover__title { color: var(--text-primary); font-size: 14px; font-weight: 600; }
</style>
