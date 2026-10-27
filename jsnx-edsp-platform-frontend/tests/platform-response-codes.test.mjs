import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')

test('平台返回码支持分类和关键词组合筛选', () => {
  assert.match(app, /const filteredPlatformResponseCodes = computed\(\(\) => \{[\s\S]*?item\.category === platformCodeCategory\.value[\s\S]*?keyword[\s\S]*?\}\)/)
  assert.match(app, /<el-select v-model="platformCodeCategory"[^>]*aria-label="返回码分类"/)
  assert.match(app, /<el-table-column label="分类"/)
})
