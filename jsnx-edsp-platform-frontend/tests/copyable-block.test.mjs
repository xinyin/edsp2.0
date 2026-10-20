import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'

const component = parse(readFileSync(new URL('../src/components/CopyableBlock.vue', import.meta.url), 'utf8')).descriptor
const app = parse(readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')).descriptor
const script = component.scriptSetup.content.replace(/^import .*$/gm, '')
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText

function copyHarness(value, clipboard, document = {}) {
  const messages = []
  const props = { value }
  const copy = vm.runInNewContext(`(() => { ${js} return copy })()`, {
    defineProps: () => props,
    navigator: { clipboard },
    document,
    ElMessage: { success: message => messages.push(['success', message]), warning: message => messages.push(['warning', message]) }
  })
  return { copy, props, messages }
}

test('copies the latest editable text through the clipboard API', async () => {
  const copied = []
  const h = copyHarness('old', { writeText: async text => copied.push(text) })
  h.props.value = '{"query_key":"new"}'
  await h.copy()
  assert.deepEqual(copied, ['{"query_key":"new"}'])
  assert.deepEqual(h.messages, [['success', '已复制']])
})

test('falls back to selection-based copying when clipboard API is unavailable', async () => {
  let input
  const h = copyHarness('<request />', undefined, {
    createElement: () => input = { style: {}, select() { assert.equal(this.value, '<request />') }, remove() { this.removed = true } },
    body: { appendChild: () => {} },
    execCommand: command => command === 'copy'
  })
  await h.copy()
  assert.equal(input.removed, true)
  assert.deepEqual(h.messages, [['success', '已复制']])
})

test('shows failure feedback and cleans up the temporary input', async () => {
  let input
  const h = copyHarness('response', undefined, {
    createElement: () => input = { style: {}, select() {}, remove() { this.removed = true } },
    body: { appendChild: () => {} },
    execCommand: () => false
  })
  await h.copy()
  assert.equal(input.removed, true)
  assert.equal(h.messages[0][0], 'warning')
})

test('every multiline input and preformatted message is inside a copyable block', () => {
  const uncovered = []
  function visit(node, parents = []) {
    if (node.type === 1) {
      const multiline = node.tag === 'el-input' && node.props.some(prop => prop.type === 6 && prop.name === 'type' && prop.value?.content === 'textarea')
      if ((multiline || node.tag === 'pre') && !parents.includes('CopyableBlock')) uncovered.push(node.loc.start.line)
      parents = [...parents, node.tag]
    }
    for (const child of node.children ?? []) visit(child, parents)
  }
  visit(app.template.ast)
  assert.deepEqual(uncovered, [])
})
