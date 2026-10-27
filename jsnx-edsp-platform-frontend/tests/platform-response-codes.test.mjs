import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { computed, ref, watch, nextTick } from 'vue'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')

test('平台返回码支持分类和关键词组合筛选', () => {
  assert.match(app, /const filteredPlatformResponseCodes = computed\(\(\) => \{[\s\S]*?item\.category === platformCodeCategory\.value[\s\S]*?keyword[\s\S]*?\}\)/)
  assert.match(app, /<el-select v-model="platformCodeCategory"[^>]*aria-label="返回码分类"/)
  assert.match(app, /<el-table-column label="分类"/)
})

test('返回码分页支持切换条数，筛选后自动回到有效页', async () => {
  const start = app.indexOf('const filteredPlatformResponseCodes = computed(')
  const end = app.indexOf('const codeEditorDialog = ref(', start)
  const logic = app.slice(start, end)
  const state = vm.runInNewContext(`(() => { ${logic}; return {
    platformCodeSearch, platformCodeCategory, platformCodePage, platformCodePageSize,
    platformResponseCodes, filteredPlatformResponseCodes, pagedPlatformResponseCodes
  } })()`, {
    computed, ref, watch,
    platformCodeSearch: ref(''), platformCodeCategory: ref(''),
    platformCodePage: ref(1), platformCodePageSize: ref(20),
    platformResponseCodes: ref(Array.from({ length: 37 }, (_, index) => ({
      code: String(index), name: `返回码 ${index}`, category: index < 25 ? 'AUTH' : 'FILE', description: ''
    }))),
    platformCodeCategoryLabel: value => value
  })
  assert.equal(state.pagedPlatformResponseCodes.value.length, 20)
  state.platformCodePage.value = 2
  assert.equal(state.pagedPlatformResponseCodes.value.length, 17)
  state.platformCodePageSize.value = 10
  await nextTick()
  assert.equal(state.platformCodePage.value, 1)
  assert.equal(state.pagedPlatformResponseCodes.value.length, 10)
  state.platformCodePage.value = 4
  state.platformCodeCategory.value = 'FILE'
  await nextTick()
  assert.equal(state.platformCodePage.value, 1)
  assert.equal(state.filteredPlatformResponseCodes.value.length, 12)
  state.platformCodePage.value = 2
  assert.equal(state.pagedPlatformResponseCodes.value.length, 2)
})
