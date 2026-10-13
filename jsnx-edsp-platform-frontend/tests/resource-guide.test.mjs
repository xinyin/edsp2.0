import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { ref, computed, nextTick, watch } from 'vue'

const guideSource = readFileSync(new URL('../src/resource-guide.ts', import.meta.url), 'utf8')
const guideJS = ts.transpileModule(guideSource, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
const guide = await import('data:text/javascript;base64,' + Buffer.from(guideJS).toString('base64'))
const appText = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const apiText = readFileSync(new URL('../src/api.ts', import.meta.url), 'utf8')
const consoleThemeText = readFileSync(new URL('../src/console-theme.css', import.meta.url), 'utf8')
const stylesText = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const script = parse(appText).descriptor.scriptSetup.content
const sourceFile = ts.createSourceFile('App.ts', script, ts.ScriptTarget.Latest, true)
const imports = sourceFile.statements.filter(ts.isImportDeclaration)
let standalone = script
for (const declaration of [...imports].reverse()) standalone = standalone.slice(0, declaration.getStart()) + standalone.slice(declaration.end)
const appJS = ts.transpileModule(standalone, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText

const mapping = direction => ({ direction, sourcePath: 'name', targetPath: 'name', transformType: 'DIRECT', transformConfig: {}, defaultValue: '', required: true, sortOrder: 1 })
const codes = [{ vendorCode: '0000', standardCode: 'EXT-SUCCESS', resultStatus: 'HIT', billable: true, retryable: false }]
const baseGuide = { upstreamUrl: 'http://localhost/query', servicePath: '/api/query', mappings: [mapping('REQUEST'), mapping('RESPONSE')], responseCodes: codes, securityEnabled: false, status: 'DRAFT', currentVersion: 2, publishedVersion: 1, orchestrationVerified: false }
const result = () => ({ upstreamRequest: {}, upstreamResponse: {}, standardResponse: { resultStatus: 'HIT' }, steps: [], traceNodes: ['ESB_QUEUE', 'ESB_ADAPTER', 'GATEWAY', 'DATA_APPLICATION', 'SUPPLIER', 'STANDARD_RESPONSE'].map(key => ({ key, status: 'SUCCESS' })) })

function harness(overrides = {}, confirm = async () => true) {
  const messages = []
  const deps = {}
  for (const entry of imports) {
    for (const element of entry.importClause?.namedBindings?.elements ?? []) deps[element.name.text] = {}
  }
  Object.assign(deps, guide, { ref, computed, nextTick, watch, useDisplayPreference: (_key, initial = false) => ref(initial), URL, AbortController, structuredClone,
    setInterval: () => 1, clearInterval: () => {}, setTimeout: callback => { callback(); return 1 },
    document: { querySelector: () => null }, navigator: {},
    ElMessage: Object.fromEntries(['error', 'warning', 'success', 'info'].map(kind => [kind, message => messages.push({ kind, message })])),
    ElMessageBox: { confirm }, isDemoMode: () => false,
    api: new Proxy({ securityAlgorithms: async () => [],
      validationStatus: async () => ({ upstreamVerified: false, orchestrationVerified: false }),
      resourceValidationStatus: async () => ({ upstreamVerified: false }), ...overrides },
      { get: (target, name) => target[name] ?? (() => { throw new Error('Unexpected API call: ' + String(name)) }) })
  })
  const state = vm.runInNewContext('(() => { ' + appJS + ' return { navigate, sourceForm, sourceStep, sourceError, sourceSaving, advanceSourceWizard, createSource, authorizationSearch, authorizationClient, filteredAuthorizations, pagedAuthorizations, activeMenu, activeTab, detail, selectedInterfaceId, selectedSourceId, sources, providers, clients, authorizations, allInterfaces, connectionForm, savedConnectionBaseline, savedUpstreamRequestBaseline, savedUpstreamResponseBaseline, savedMappingsBaseline, savedRequestBaseline, savedResponseBaseline, savedResponseCodesBaseline, savedSecurityBaseline, standardServiceForm, savedStandardServiceBaseline, securityPolicy, requestText, responseText, upstreamRequestText, sourceSearch, providerExpansion, sourceExpansion, isProviderExpanded, isSourceExpanded, sourceInterfaces, filteredProviderGroups, filteredSources, setAllSourcesExpanded, locateSelectedApi, timeoutExpanded, hasUnsavedChanges, resourceEditorState, resourcePrimaryActionDisabled, markDraftSaved, guideError, guideBusy, apiGuideSteps, lastUpstreamValidation, lastOrchestrationValidation, lastE2ETest, currentUpstreamVerified, currentOrchestrationVerified, currentPreReleaseVerified, currentE2EVerified, serviceDevelopmentStage, selectServiceDevelopmentStage, continueGuide, openGuideStep, runCurrentPrimaryAction, runTest, testMode, loadDetail, bringVendorApiToPlatform, vendorImportingKey, apiForm, apiDialog, mappingDirection, mappingIsPassThrough, mappingModeLabel, startMappingConfiguration, deleteMapping, mappingRowKey, enumDrawer, enumPairs, enumDefaultValue, openEnumDrawer, removeEnumPair, applyEnumConfig, upgradeLegacyGeneratedUpstreamExample }; })()', deps)
  state.selectedSourceId.value = 1
  state.activeMenu.value = 'resource'
  state.selectedInterfaceId.value = 11
  state.providers.value = [{ id: 10, providerCode: 'QX', providerName: '企信资源提供商', status: 'ACTIVE' }]
  state.sources.value = [{ id: 1, providerId: 10, providerName: '企信资源提供商', sourceName: '企业工商数据源', sourceCode: 'ENTERPRISE', endpointUrl: 'http://localhost', defaultTimeoutMs: 5000 }]
  state.detail.value = { summary: { id: 11, dataSourceId: 1, interfaceCode: 'ENTERPRISE_QUERY', currentVersion: 2, publishedVersion: 1, status: 'DRAFT', upstreamUrl: 'http://localhost/query', servicePath: '/api/query' }, mappings: [mapping('REQUEST'), mapping('RESPONSE')], responseCodes: codes, vendorCodePath: 'result.code', requestExample: { name: '测试企业' }, upstreamRequestExample: { name: '测试企业' }, upstreamResponseExample: { name: '测试企业' }, standardResponseExample: { name: '测试企业' } }
  state.connectionForm.value = { configScope: 'INHERIT', upstreamPath: '/query', upstreamUrl: 'http://localhost/query', timeoutMode: 'INHERIT', timeoutMs: 5000 }
  state.savedConnectionBaseline.value = JSON.stringify(state.connectionForm.value)
  state.upstreamRequestText.value = state.savedUpstreamRequestBaseline.value = '{"name":"测试企业"}'
  state.savedMappingsBaseline.value = [mapping('REQUEST'), mapping('RESPONSE')]
  state.savedResponseCodesBaseline.value = JSON.stringify(codes)
  state.savedSecurityBaseline.value = JSON.stringify(state.securityPolicy.value)
  state.requestText.value = state.savedRequestBaseline.value = '{"name":"测试企业"}'
  state.responseText.value = state.savedResponseBaseline.value = '{"name":"测试企业"}'
  state.savedUpstreamResponseBaseline.value = state.responseText.value
  state.standardServiceForm.value = { servicePath: '/api/query', httpMethod: 'POST' }
  state.savedStandardServiceBaseline.value = JSON.stringify(state.standardServiceForm.value)
  return { ...state, messages }
}

test('跨模块导航取消时保留未保存报文，确认放弃后才能离开', async () => {
  const blocked = harness({}, async () => { throw 'cancel' })
  blocked.responseText.value = '{"edited":true}'
  await blocked.navigate('billing')
  assert.equal(blocked.activeMenu.value, 'resource')
  assert.equal(blocked.responseText.value, '{"edited":true}')
  const accepted = harness()
  accepted.responseText.value = '{"edited":true}'
  await accepted.navigate('billing')
  assert.equal(accepted.activeMenu.value, 'billing')
  assert.equal(accepted.responseText.value, accepted.savedUpstreamResponseBaseline.value)
})

test('接入向导阻止空基础信息和无效地址，不发起创建请求', async () => {
  let calls = 0
  const s = harness({ createDataSource: async () => { calls++; throw new Error('不应调用') } })
  s.advanceSourceWizard()
  assert.equal(s.sourceStep.value, 0)
  assert.match(s.sourceError.value, /资源提供商/)
  Object.assign(s.sourceForm.value, { providerId: 1, sourceCode: 'NEW_SOURCE', sourceName: '新数据源' })
  s.advanceSourceWizard()
  assert.equal(s.sourceStep.value, 1)
  s.sourceForm.value.endpoint = 'not-an-address'
  await s.createSource()
  assert.equal(calls, 0)
  assert.match(s.sourceError.value, /有效的资源提供商基础地址/)
  assert.equal(s.sourceSaving.value, false)
})

test('授权清单同时按调用系统与接口关键词过滤，单页最多20条', () => {
  const s = harness()
  s.authorizations.value = Array.from({ length: 45 }, (_, i) => ({ id: i, clientId: i < 30 ? 1 : 2, clientName: '系统'+i, interfaceName: '企业查询', interfaceCode: 'ENTERPRISE_'+i }))
  assert.equal(s.pagedAuthorizations.value.length, 20)
  s.authorizationClient.value = 2
  assert.equal(s.filteredAuthorizations.value.length, 15)
  s.authorizationSearch.value = 'enterprise_44'
  assert.equal(s.filteredAuthorizations.value.length, 1)
  assert.equal(s.filteredAuthorizations.value[0].id, 44)
})

test('请求和响应可以独立选择映射或原报文透传；关闭安全策略是可跳过项', () => {
  const steps = guide.buildResourceGuide({ ...baseGuide, mappings: [mapping('REQUEST')] })
  assert.equal(steps[1].done, true)
  assert.match(steps[1].hint, /响应 透传/)
  assert.equal(steps[3].done, true)
  assert.match(steps[3].hint, /可选/)
})

test('空映射被视为已完成的自动透传，无需生成一对一配置数据', async () => {
  const steps = guide.buildResourceGuide({ ...baseGuide, mappings: [] })
  assert.equal(steps[1].done, true)
  assert.match(steps[1].hint, /请求、响应均透传/)
  const s = harness()
  s.activeTab.value = 'request'
  s.detail.value.mappings = []
  s.savedMappingsBaseline.value = []
  assert.equal(s.mappingIsPassThrough.value, true)
  assert.equal(s.mappingModeLabel.value, '自动透传')
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'codes')
  assert.equal(s.detail.value.mappings.length, 0)
})

test('登录页使用纯色背景和居中卡片，不再渲染左右分栏品牌面板', () => {
  const styles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
  const loginPageRule = styles.match(/\.login-page\s*\{[^}]*\}/s)?.[0] ?? ''
  assert.match(appText, /class="login-page"[\s\S]*class="login-form-panel"[\s\S]*class="login-box"/)
  assert.doesNotMatch(appText, /class="login-brand-panel"/)
  assert.match(loginPageRule, /place-items:\s*center[^}]*background:\s*#[0-9a-f]{6}/is)
  assert.doesNotMatch(loginPageRule, /login-platform-bg|background-image|url\(/)
  assert.match(styles, /\.login-form-panel\s*\{[^}]*width:\s*min\(420px,\s*100%\)/s)
})

test('已接入数据源可修改归属、名称、协议和连接配置', () => {
  assert.match(appText, />编辑数据源<\/el-button>/)
  assert.match(appText, /数据源基础地址[\s\S]*@click="openSourceSettingsEditor">修改<\/el-button>/)
  assert.match(appText, /sourceSettingsForm\.providerId[\s\S]*sourceSettingsForm\.sourceName[\s\S]*sourceSettingsForm\.sourceCode[\s\S]*disabled/)
  assert.match(appText, /sourceSettingsForm\.protocolType[\s\S]*HTTP_JSON[\s\S]*SOAP[\s\S]*LOCAL_SQL/)
  assert.match(appText, /api\.saveDataSourceSettings\(selectedSource\.value\.id/)
  assert.match(appText, /providerId: sourceSettingsForm\.value\.providerId[\s\S]*sourceName: sourceSettingsForm\.value\.sourceName\.trim\(\)[\s\S]*protocolType: sourceSettingsForm\.value\.protocolType/)
  assert.match(appText, /activeMenu==='resource' && selectedSource[\s\S]*openSourceSettingsEditor[\s\S]*新增资源 API/)
})

test('只有用户主动配置字段转换时才创建第一条映射，删除最后一条后恢复透传', () => {
  const s = harness()
  s.activeTab.value = 'request'
  s.detail.value.mappings = []
  s.savedMappingsBaseline.value = []
  s.mappingDirection.value = 'REQUEST'
  s.startMappingConfiguration()
  assert.equal(s.detail.value.mappings.length, 1)
  assert.equal(s.detail.value.mappings[0].direction, 'REQUEST')
  assert.equal(s.mappingIsPassThrough.value, false)
  s.deleteMapping(s.detail.value.mappings[0])
  assert.equal(s.mappingIsPassThrough.value, true)
  assert.match(appText, /平台请求将按原路径一对一发送给上游，无需逐条配置映射/)
})

test('请求转换和响应标准化已有映射时仍可继续新增行', () => {
  const s = harness()
  const requestCount = s.detail.value.mappings.filter(item => item.direction === 'REQUEST').length
  s.mappingDirection.value = 'REQUEST'
  s.startMappingConfiguration()
  s.startMappingConfiguration()
  assert.equal(s.detail.value.mappings.filter(item => item.direction === 'REQUEST').length, requestCount + 2)

  const responseCount = s.detail.value.mappings.filter(item => item.direction === 'RESPONSE').length
  s.mappingDirection.value = 'RESPONSE'
  s.startMappingConfiguration()
  assert.equal(s.detail.value.mappings.filter(item => item.direction === 'RESPONSE').length, responseCount + 1)
  assert.equal(s.detail.value.mappings.at(-1).direction, 'RESPONSE')
})

test('已进入字段转换模式但存在空映射行时不能继续', async () => {
  const s = harness()
  s.activeTab.value = 'request'
  s.detail.value.mappings = []
  s.savedMappingsBaseline.value = []
  s.startMappingConfiguration()
  const incomplete = guide.buildResourceGuide({ ...baseGuide, mappings: s.detail.value.mappings })
  assert.equal(incomplete[1].done, false)
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'request')
  assert.match(s.guideError.value, /补全已添加的映射行/)
})

test('每条映射行具有稳定且唯一的界面标识，下拉框不会被 Vue 错误复用', () => {
  const { mappingRowKey } = harness()
  const first = { ...mapping('REQUEST'), id: 0 }
  const second = { ...mapping('REQUEST'), id: 0 }
  assert.equal(mappingRowKey(first), mappingRowKey(first))
  assert.notEqual(mappingRowKey(first), mappingRowKey(second))
  assert.match(appText, /:teleported="false"\s+:persistent="true"\s+placement="bottom-start"\s+popper-class="mapping-rule-dropdown"/)
  assert.match(appText, /<el-option label="直接映射" value="DIRECT"\/>/)
})

test('旧版自动生成的上游样例升级为供应商字段，用户自定义样例保持不变', () => {
  const { upgradeLegacyGeneratedUpstreamExample } = harness()
  assert.deepEqual(JSON.parse(JSON.stringify(upgradeLegacyGeneratedUpstreamExample({
    queryKey: 'DEMO-QUERY-001', queryReason: '业务准入'
  }))), { query_key: 'DEMO-QUERY-001', query_reason: '业务准入' })
  assert.deepEqual(JSON.parse(JSON.stringify(upgradeLegacyGeneratedUpstreamExample({
    queryKey: 'CUSTOM', queryReason: '供应商确实使用驼峰'
  }))), { queryKey: 'CUSTOM', queryReason: '供应商确实使用驼峰' })
})

test('枚举配置使用独立草稿，清空或删除枚举项不会被旧值回填', () => {
  const state = harness()
  const row = state.detail.value.mappings[0]
  row.transformType = 'ENUM'
  row.transformConfig = { '1': 'MALE', '2': 'FEMALE' }
  row.defaultValue = 'UNKNOWN'
  state.openEnumDrawer(row)
  assert.equal(state.enumPairs.value.length, 2)
  state.enumPairs.value[0].sourceValue = ''
  state.enumPairs.value[0].targetValue = ''
  state.enumDefaultValue.value = ''
  state.applyEnumConfig()
  assert.equal(JSON.stringify(row.transformConfig), JSON.stringify({ '2': 'FEMALE' }))
  assert.equal(row.defaultValue, '')
  assert.equal(state.enumDrawer.value, false)
})
test('审批中、旧已发布版本都不能标记当前版本上线', () => {
  assert.equal(guide.buildResourceGuide({ ...baseGuide, status: 'PENDING_APPROVAL' })[5].done, false)
  assert.equal(guide.buildResourceGuide({ ...baseGuide, status: 'PUBLISHED' })[5].done, false)
  assert.equal(guide.buildResourceGuide({ ...baseGuide, status: 'PUBLISHED', publishedVersion: 2 })[5].done, true)
})
test('空返回码不能标记已配置；URL 必须是 HTTP/HTTPS', () => {
  assert.equal(guide.buildResourceGuide({ ...baseGuide, responseCodes: [{ ...codes[0], vendorCode: '' }] })[2].done, false)
  for (const invalid of ['', '/query', 'file:///tmp/a', 'javascript:alert(1)']) assert.equal(guide.isHttpEndpoint(invalid), false)
  assert.equal(guide.isHttpEndpoint('https://provider.example/query'), true)
})
test('只有完整链路且关键节点成功才可记为 E2E 通过', () => {
  assert.equal(guide.isSuccessfulE2E(result(), 'E2E'), true)
  assert.equal(guide.isSuccessfulE2E(result(), 'UPSTREAM'), false)
  assert.equal(guide.isSuccessfulE2E(result(), 'PLATFORM'), false)
  const failed = result(); failed.traceNodes[1].status = 'ERROR'
  assert.equal(guide.isSuccessfulE2E(failed, 'E2E'), false)
  assert.equal(guide.isSuccessfulE2E({ ...result(), traceNodes: [] }, 'E2E'), false)
  assert.equal(guide.isSuccessfulE2E({ ...result(), standardResponse: { resultStatus: 'ERROR' } }, 'E2E'), false)
})
test('浏览器联调截止时间包含上游超时和平台处理余量', () => {
  assert.equal(guide.requestDeadlineMs(3000), 10000)
  assert.equal(guide.requestDeadlineMs(15000), 20000)
  assert.equal(guide.requestDeadlineMs(120000), 125000)
  assert.equal(guide.requestDeadlineMs(Number.NaN), 10000)
})
test('已保存配置下一步不重复保存，不增加版本', async () => {
  const s = harness()
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'request')
  assert.equal(s.detail.value.summary.currentVersion, 2)
})
test('无效连接保存失败时停留当前步并显示原因', async () => {
  const s = harness()
  s.connectionForm.value.configScope = 'OVERRIDE'; s.connectionForm.value.upstreamUrl = 'bad-url'
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'overview')
  assert.match(s.guideError.value, /HTTP/)
  assert.equal(s.guideBusy.value, false)
})
test('映射样例 JSON 错误不会跳步', async () => {
  const s = harness(); s.activeTab.value = 'request'; s.requestText.value = '{'
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'request')
  assert.match(s.guideError.value, /JSON/)
})
test('保存请求失败保留编辑内容且不跳步', async () => {
  const s = harness({ saveMappings: async () => { throw new Error('数据库暂不可用') } })
  s.activeTab.value = 'request'; s.detail.value.mappings[0].targetPath = 'companyName'
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'request')
  assert.equal(s.detail.value.mappings[0].targetPath, 'companyName')
  assert.equal(s.hasUnsavedChanges.value, true)
  assert.ok(s.guideError.value)
})
test('保存成功后继续下一步并清除脏状态', async () => {
  const s = harness({ saveMappings: async (_id, mappings) => JSON.parse(JSON.stringify(mappings)) })
  s.activeTab.value = 'request'; s.detail.value.mappings[0].targetPath = 'companyName'
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'codes')
  assert.equal(s.detail.value.summary.currentVersion, 2)
  assert.equal(s.hasUnsavedChanges.value, false)
})
test('确认放弃后真正还原映射；取消离开则保留编辑', async () => {
  const s = harness(); s.activeTab.value = 'request'; s.detail.value.mappings[0].targetPath = 'changed'
  await s.openGuideStep('codes')
  assert.equal(s.detail.value.mappings[0].targetPath, 'name')
  assert.equal(s.activeTab.value, 'codes')
  const keep = harness({}, async () => { throw 'cancel' }); keep.activeTab.value = 'request'; keep.detail.value.mappings[0].targetPath = 'changed'
  await keep.openGuideStep('codes')
  assert.equal(keep.activeTab.value, 'request')
  assert.equal(keep.detail.value.mappings[0].targetPath, 'changed')
})
test('安全策略默认关闭可以直接继续联调，不触发保存', async () => {
  const s = harness(); s.activeTab.value = 'security'
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'test')
})
test('草稿链路测试为可选项，没有测试凭证也能进入发布', async () => {
  const s = harness(); s.activeTab.value = 'test'
  await s.continueGuide()
  assert.equal(s.activeMenu.value, 'releases')
  assert.equal(s.activeTab.value, 'test')
  assert.equal(s.currentPreReleaseVerified.value, false)
})
test('搜索自动展开所有匹配项，清除搜索后恢复折叠偏好', () => {
  const s = harness(); s.setAllSourcesExpanded(false)
  assert.equal(s.isProviderExpanded(10), false)
  assert.equal(s.isSourceExpanded(1), false)
  s.sourceSearch.value = '企业'
  assert.equal(s.isProviderExpanded(10), true)
  assert.equal(s.isSourceExpanded(1), true)
  assert.equal(s.filteredSources.value.length, 1)
  s.sourceSearch.value = ''
  assert.equal(s.isProviderExpanded(10), false)
  assert.equal(s.isSourceExpanded(1), false)
})
test('目录按资源提供商、数据源、API 三级稳定分组', () => {
  const s = harness()
  s.activeMenu.value = 'service-development'
  s.providers.value.push({ id: 20, providerCode: 'SF', providerName: '司法资源提供商', status: 'ACTIVE' })
  s.sources.value.push(
    { id: 2, providerId: 10, providerName: '企信资源提供商', sourceName: '企业风险数据源', sourceCode: 'RISK' },
    { id: 3, providerId: 20, providerName: '司法资源提供商', sourceName: '司法涉诉数据源', sourceCode: 'JUDICIAL' }
  )
  s.allInterfaces.value = [
    { id: 11, dataSourceId: 1, interfaceName: '企业查询', interfaceCode: 'ENT_QUERY', servicePath: '/enterprise' },
    { id: 12, dataSourceId: 2, interfaceName: '风险查询', interfaceCode: 'RISK_QUERY', servicePath: '/risk' },
    { id: 13, dataSourceId: 3, interfaceName: '涉诉查询', interfaceCode: 'CASE_QUERY', servicePath: '/case' }
  ]
  assert.equal(s.filteredProviderGroups.value.length, 2)
  assert.deepEqual(Array.from(s.filteredProviderGroups.value[0].sources, item => item.id), [1, 2])
  assert.deepEqual(Array.from(s.sourceInterfaces(2), item => item.id), [12])
  s.sourceSearch.value = 'QX'
  assert.equal(s.filteredProviderGroups.value.length, 1)
  assert.deepEqual(Array.from(s.filteredProviderGroups.value[0].sources, item => item.id), [1, 2])
  assert.deepEqual(Array.from(s.sourceInterfaces(2), item => item.id), [12])
  s.sourceSearch.value = 'CASE_QUERY'
  assert.deepEqual(Array.from(s.filteredProviderGroups.value[0].sources, item => item.id), [3])
  assert.deepEqual(Array.from(s.sourceInterfaces(3), item => item.id), [13])
})
test('加载已有接口保留独立超时；折叠状态不改变配置', async () => {
  let loaded
  const s = harness({ detail: async () => loaded })
  loaded = JSON.parse(JSON.stringify(s.detail.value))
  loaded.configuredTimeoutMs = 8500; loaded.timeoutMs = 8500
  s.activeMenu.value = 'service-development'
  await s.loadDetail()
  assert.equal(s.connectionForm.value.timeoutMode, 'OVERRIDE')
  assert.equal(s.connectionForm.value.timeoutMs, 8500)
  s.timeoutExpanded.value = true; s.timeoutExpanded.value = false
  assert.equal(s.connectionForm.value.timeoutMs, 8500)
  assert.equal(s.hasUnsavedChanges.value, false)
})
test('目录样式取消固定高度裁切，长名称允许换行', () => {
  const css = readFileSync(new URL('../src/resource-workbench.css', import.meta.url), 'utf8')
  assert.match(css, /\.resource-workbench \.resource-tree \{ max-height: none; min-height: 0; overflow: visible/)
  assert.match(css, /white-space: normal; overflow: visible; text-overflow: clip; overflow-wrap: anywhere/)
})

test('只配置请求映射时响应自动透传并允许进入下一步', async () => {
  const s = harness(); s.activeTab.value = 'request'
  s.detail.value.mappings = [mapping('REQUEST')]; s.savedMappingsBaseline.value = [mapping('REQUEST')]
  await s.continueGuide()
  assert.equal(s.activeTab.value, 'codes')
})
test('完整链路测试会记录版本，后续失败会清除已通过记录', async () => {
  let failed = false
  let latest
  const s = harness({ detail: async () => latest, esbDiagnostic: async () => { if (failed) throw new Error('上游连接失败'); return { ...result(), apiCode: 'ENTERPRISE_QUERY', servicePath: '/api/query', traceId: 'T001', parsedRequest: {}, xmlRequest: '<edspRequest/>', nodes: result().traceNodes } } })
  latest = s.detail.value
  s.detail.value.summary.status = 'PUBLISHED'
  s.detail.value.summary.publishedVersion = 2
  s.clients.value = [{ id: 7, clientCode: 'TEST_CLIENT', status: 'ACTIVE' }]
  s.authorizations.value = [{ id: 8, clientId: 7, interfaceId: 11, environment: 'TEST', status: 'ACTIVE', validFrom: '2020-01-01', validTo: '2099-12-31' }]
  s.activeTab.value = 'test'; s.testMode.value = 'E2E'
  assert.equal(await s.runTest(), true)
  assert.equal(s.currentE2EVerified.value, true)
  failed = true
  assert.equal(await s.runTest(), false)
  assert.equal(s.currentE2EVerified.value, false)
})
test('存在新草稿时仍可独立验收当前线上版本', async () => {
  let latest
  const s = harness({ detail: async () => latest, esbDiagnostic: async () => ({ ...result(), apiCode: 'ENTERPRISE_QUERY', servicePath: '/api/query', traceId: 'T002', parsedRequest: {}, xmlRequest: '<edspRequest/>', nodes: result().traceNodes }) })
  s.detail.value.summary.status = 'DRAFT'
  s.detail.value.summary.currentVersion = 3
  s.detail.value.summary.publishedVersion = 2
  latest = JSON.parse(JSON.stringify(s.detail.value))
  s.clients.value = [{ id: 7, clientCode: 'TEST_CLIENT', status: 'ACTIVE' }]
  s.authorizations.value = [{ id: 8, clientId: 7, interfaceId: 11, environment: 'TEST', status: 'ACTIVE', validFrom: '2020-01-01', validTo: '2099-12-31' }]
  s.activeTab.value = 'test'; s.testMode.value = 'E2E'
  assert.equal(await s.runTest(), true)
  assert.equal(s.currentE2EVerified.value, true)
  assert.equal(s.lastE2ETest.value.version, 2)
})
test('上游测试成功不会被误判为完整链路通过', async () => {
  const s = harness({ test: async () => result() })
  s.activeTab.value = 'test'; s.testMode.value = 'UPSTREAM'
  assert.equal(await s.runTest(), true)
  assert.equal(s.currentE2EVerified.value, false)
})
test('可选草稿链路测试由后端记录结果，不要求已发布和授权', async () => {
  let submitted
  const s = harness({ test: async (_id, body) => { submitted = body; return result() },
    validationStatus: async () => ({ upstreamVerified: true, orchestrationVerified: true }) })
  s.activeTab.value = 'test'; s.testMode.value = 'UPSTREAM'
  assert.equal(await s.runTest(), true)
  assert.equal(s.currentUpstreamVerified.value, true)
  assert.equal(s.currentPreReleaseVerified.value, false)
  s.testMode.value = 'PLATFORM'
  assert.equal(await s.runTest(), true)
  assert.equal(s.currentOrchestrationVerified.value, true)
  assert.equal(s.currentPreReleaseVerified.value, true)
  assert.equal(submitted.callUpstream, true)
  assert.equal(submitted.upstreamResponse, undefined)
  s.requestText.value = '{"name":"未保存的请求"}'
  assert.equal(s.currentPreReleaseVerified.value, false)
  assert.equal(await s.runTest(), false)
})

test('响应看似成功但后端未签发凭证时不能进入发布', async () => {
  const s = harness({ test: async () => result() })
  s.activeTab.value = 'test'; s.testMode.value = 'PLATFORM'
  assert.equal(await s.runTest(), false)
  assert.equal(s.currentPreReleaseVerified.value, false)
})

test('同一草稿反复保存不跳版本，保存后必须重新验证', async () => {
  const s = harness({ saveMappings: async (_id, mappings) => JSON.parse(JSON.stringify(mappings)) })
  for (const name of ['companyName', 'enterpriseName']) {
    s.lastOrchestrationValidation.value = { interfaceId: 11, version: 2 }
    s.activeTab.value = 'request'
    s.detail.value.mappings[0].targetPath = name
    await s.continueGuide()
    assert.equal(s.detail.value.summary.currentVersion, 2)
    assert.equal(s.currentPreReleaseVerified.value, false)
  }
})
test('服务开发三阶段可以从上游接入切换到标准编排，发布阶段交由服务发布', async () => {
  const s = harness()
  s.serviceDevelopmentStage.value = 'UPSTREAM'
  await s.selectServiceDevelopmentStage('ORCHESTRATION')
  assert.equal(s.serviceDevelopmentStage.value, 'ORCHESTRATION')
  assert.equal(s.activeTab.value, 'request')
})
test('主页面使用新的三阶段产品名称和运行管理名称', () => {
  assert.match(appText, /数据资源接入/)
  assert.match(appText, /标准服务开发/)
  assert.match(appText, /上游接口验证/)
  assert.match(appText, /草稿链路测试/)
  assert.match(appText, /链路追踪/)
  assert.match(appText, /运行监控/)
  assert.match(appText, /@click\.stop="sidebarCollapsed=!sidebarCollapsed"/)
  assert.match(appText, /:aria-label="sidebarCollapsed\?'展开菜单':'收起菜单'"/)
  assert.doesNotMatch(appText, /<span>数据资源服务<\/span>/)
})
test('ESB 联调使用生产 Service XML 且最终响应仍为 XML', () => {
  assert.match(appText, /<Service_Header>/)
  assert.match(appText, /<service_sn>/)
  assert.match(appText, /<service_id>/)
  assert.match(appText, /<requester_id>/)
  assert.match(appText, /<Service_Body>/)
  assert.match(appText, /ESB XML 响应/)
  assert.match(appText, /平台内部和 Kafka 使用结构化 JSON/)
  assert.doesNotMatch(appText, /<edspRequest>/)
  assert.doesNotMatch(appText, /不将 XML 返回给行内系统/)
})
test('生效配置和日志检索在窄工作区使用自适应布局', () => {
  assert.match(consoleThemeText, /\.api-preview-panel dl \{[^}]*minmax\(280px,\.38fr\)/)
  assert.match(consoleThemeText, /@container \(max-width: 740px\)[\s\S]*?\.api-preview-panel dl \{ grid-template-columns: minmax\(0,1fr\); \}/)
  assert.match(consoleThemeText, /@media \(max-width: 1100px\)[\s\S]*?\.log-query-grid \{ grid-template-columns: minmax\(0,1fr\) minmax\(0,1fr\); \}/)
})
test('标准服务链路测试模式使用完整内容列并在窄编辑区纵向排列', () => {
  assert.match(consoleThemeText, /\.test-mode-selector\.orchestration-modes button \{[^}]*grid-template-columns: minmax\(0,1fr\)/)
  assert.match(consoleThemeText, /\.test-mode-selector\.orchestration-modes button > div \{ width: 100%; min-width: 0; \}/)
  assert.match(consoleThemeText, /@container \(max-width: 740px\)[\s\S]*?\.test-mode-selector\.orchestration-modes \{ grid-template-columns: minmax\(0,1fr\); \}/)
})
test('状态标签文字继承语义前景色且调用链标题样式不污染标签内容', () => {
  assert.match(consoleThemeText, /\.el-tag \.el-tag__content \{ color: inherit; \}/)
  assert.match(consoleThemeText, /--el-color-success: #658575;/)
  assert.match(consoleThemeText, /--el-color-warning: #987744;/)
  assert.match(consoleThemeText, /--el-color-danger: #a45d63;/)
  assert.match(consoleThemeText, /--el-color-info: #667989;/)
  assert.match(stylesText, /\.drawer-trace-title>div:first-child>span/)
  assert.doesNotMatch(stylesText, /\.drawer-trace-title span[,\{]/)
  assert.match(stylesText, /\.persisted-node-detail header>div:first-child>span/)
  assert.doesNotMatch(stylesText, /\.persisted-node-detail header span[,\{]/)
})
test('标题栏说明文字样式不覆盖主按钮文字和图标', () => {
  assert.match(consoleThemeText, /\.el-button > span, \.el-button \.el-icon \{ color: inherit; \}/)
  assert.match(consoleThemeText, /\.panel-heading > div > span, \.monitor-panel > header small/)
  assert.doesNotMatch(consoleThemeText, /\.panel-heading span, \.monitor-panel > header small/)
  assert.match(stylesText, /\.section-toolbar > div > span, \.test-toolbar > div > span/)
  assert.match(stylesText, /\.panel-heading > div > span \{ color: var\(--muted\);/)
})
test('全平台使用统一的淡色导航区、白色工作区和收敛字号体系', () => {
  assert.match(consoleThemeText, /--workspace-aside: #f4f7fa;/)
  assert.match(consoleThemeText, /--workspace-chrome: #f7f9fb;/)
  assert.match(consoleThemeText, /--selection: #e6eef6;/)
  assert.match(consoleThemeText, /\.provider-list,[\s\S]*?\.release-api-list,[\s\S]*?\.resource-workbench \.resource-tree-panel \{ background: var\(--workspace-aside\); \}/)
  assert.match(consoleThemeText, /\.vendor-app-banner,[\s\S]*?\.resource-workbench \.editor-panel,[\s\S]*?\.version-history-panel \{ background: #fff; \}/)
  assert.match(consoleThemeText, /Unified type scale: 18 page title, 14 section title, 13 body, 11-12 support/)
  assert.match(consoleThemeText, /\.main-content \{ font-size: 13px; \}/)
  assert.match(consoleThemeText, /\.page-heading h1,[\s\S]*?\.compact-page-heading h1 \{ font-size: 18px; \}/)
  assert.match(consoleThemeText, /\.billing-metrics \.amount \{ color: var\(--text-primary\); background: #fff; \}/)
})
test('折叠侧边栏的展开按钮作为内嵌控制条而非悬浮卡片', () => {
  assert.match(consoleThemeText, /\.sidebar\.collapsed \.sidebar-collapse-button \{[^}]*width: 68px;[^}]*height: 36px;[^}]*background: var\(--nav\);[^}]*box-shadow: none;/)
  assert.match(consoleThemeText, /\.sidebar\.collapsed \.sidebar-collapse-button:hover \{ color: var\(--brand-dark\); background: var\(--nav-hover\); \}/)
  assert.match(consoleThemeText, /\.sidebar\.collapsed \.side-menu \{ padding-top: 42px; \}/)
})
test('已下线接口提供恢复最近线上版本入口并保留更高版本草稿', () => {
  assert.match(apiText, /interfaces\/\$\{id\}\/online/)
  assert.match(appText, /status==='OFFLINE'.*恢复线上 V\{\{ releaseDetail\.summary\.publishedVersion \}\}/s)
  assert.match(appText, /草稿会继续保留，不会进入线上流量/)
})
test('已下线接口仍可编辑保存，并可选择上线新草稿或恢复旧线上版本', () => {
  const s = harness()
  s.activeMenu.value = 'service-development'
  s.detail.value.summary.status = 'OFFLINE'
  s.detail.value.summary.currentVersion = 2
  s.detail.value.summary.publishedVersion = 2
  s.activeTab.value = 'request'
  assert.equal(s.resourcePrimaryActionDisabled.value, false)
  s.markDraftSaved()
  assert.equal(s.detail.value.summary.status, 'OFFLINE')
  assert.equal(s.detail.value.summary.currentVersion, 3)
  assert.equal(s.resourceEditorState.value.label, '线上已下线')
  assert.match(appText, /上线草稿 V\$\{releaseDetail\.summary\.currentVersion\}/)
  assert.match(appText, /恢复线上 V\{\{ releaseDetail\.summary\.publishedVersion \}\}/)
})
test('标准服务只展示上游已保存接口，菜单支持横向和分组折叠，审批不占主菜单', () => {
  assert.match(appText, /item\.upstreamStatus === 'READY'/)
  assert.match(appText, /sidebarCollapsed/)
  assert.match(appText, /menuGroups\.service/)
  assert.doesNotMatch(appText, /<el-menu-item index="approvals"/)
})
test('没有接口级超时的草稿继续继承数据源默认配置', async () => {
  let loaded
  const s = harness({ detail: async () => loaded })
  loaded = JSON.parse(JSON.stringify(s.detail.value)); loaded.configuredTimeoutMs = null; loaded.timeoutMs = 5000
  s.activeMenu.value = 'service-development'
  await s.loadDetail()
  assert.equal(s.connectionForm.value.timeoutMode, 'INHERIT')
  assert.equal(s.connectionForm.value.timeoutMs, 5000)
  assert.equal(s.timeoutExpanded.value, false)
})
test('忙碌状态禁止重复保存和跳步', async () => {
  const s = harness(); s.guideBusy.value = true
  await s.continueGuide(); await s.openGuideStep('release')
  assert.equal(s.activeTab.value, 'overview')
  assert.equal(await s.runCurrentPrimaryAction(), false)
})

test('供应商目录接入不依赖固定主键，空库时先创建提供商再创建数据源', async () => {
  const calls = []
  const provider = { id: 37, providerCode: 'VENDOR_DEMO', providerName: '上游演示资源提供商', status: 'ACTIVE' }
  const source = { id: 52, providerId: 37, sourceCode: 'ENTERPRISE', sourceName: '企业数据源', endpointUrl: 'http://127.0.0.1:8090/enterprise', defaultTimeoutMs: 3000, interfaceCount: 0 }
  const s = harness({
    createProvider: async body => { calls.push(['provider', body]); return provider },
    createDataSource: async body => { calls.push(['source', body]); return source },
    dashboard: async () => ({ providerCount: 1, dataSourceCount: 1, interfaceCount: 0, publishedCount: 0, draftCount: 0, activePublishedPlans: 0 }),
    providers: async () => [provider], providerAccounts: async () => [], dataSources: async () => [source], resourceApis: async () => [], interfaces: async () => [],
    clients: async () => [], authorizations: async () => [], billingRules: async () => [],
    billingSummary: async () => ({ totalCalls: 0, billableCalls: 0, totalAmount: 0, hitCalls: 0, missCalls: 0, errorCalls: 0 }),
    usageRecords: async () => [], monitorSummary: async () => ({ totalCalls: 0, successCalls: 0, errorCalls: 0, successRate: 100, avgDurationMs: 0, p95DurationMs: 0, avgUpstreamDurationMs: 0, activeAlerts: 0 }),
    monitorPoints: async () => [], monitorAlerts: async () => [], approvals: async () => []
  })
  s.providers.value = []
  s.sources.value = []
  await s.bringVendorApiToPlatform(
    { apiCode: 'COMPANY_QUERY', apiName: '企业查询', httpMethod: 'POST', servicePath: '/enterprise/query', requestExample: { name: '测试企业' }, responseBody: { code: '0000' } },
    { sourceCode: 'ENTERPRISE', sourceName: '企业数据源', basePath: '/enterprise' }
  )
  assert.equal(calls[0][0], 'provider')
  assert.equal(calls[1][0], 'source')
  assert.equal(calls[1][1].providerId, 37)
  assert.equal(s.apiForm.value.dataSourceId, 52)
  assert.equal(s.apiDialog.value, true)
  assert.equal(s.vendorImportingKey.value, '')
})
