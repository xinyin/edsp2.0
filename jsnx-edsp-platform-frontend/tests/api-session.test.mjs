import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

const source = readFileSync(new URL('../src/api.ts', import.meta.url), 'utf8')
  .replace(/^import .*$/gm, '')
  .replace('import.meta.env.VITE_DEMO_MODE', 'undefined')
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText

function apiWithStatus(status) {
  const events = []
  const context = {
    exports: {}, mock: {}, Headers, AbortController, URLSearchParams, setTimeout, clearTimeout,
    Event: class { constructor(type) { this.type = type } },
    window: { dispatchEvent: event => events.push(event.type) },
    fetch: async () => ({ ok: status >= 200 && status < 300, status,
      text: async () => JSON.stringify({ timestamp: '2026-10-23T03:23:10Z', status, error: 'Unauthorized' }) })
  }
  vm.runInNewContext(code, context)
  return { api: context.exports.api, events }
}

test('资源目录会话失效时发出重新登录事件，不展示原始 401 响应', async () => {
  const { api, events } = apiWithStatus(401)
  await assert.rejects(api.resourceDirectory({ keyword: '', providerId: 0, dataSourceId: 0,
    status: '', protocolType: '', page: 1, size: 20 }), error =>
    error.name === 'ApiRequestError' && error.status === 401
      && error.message === '登录会话已失效，请重新登录')
  assert.deepEqual(events, ['edsp:session-expired'])
})

test('登录接口本身返回 401 时不触发会话失效事件', async () => {
  const { api, events } = apiWithStatus(401)
  await assert.rejects(api.login('admin', 'wrong'))
  assert.deepEqual(events, [])
})
