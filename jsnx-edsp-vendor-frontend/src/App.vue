<script setup lang="ts">
import { computed, ref } from 'vue'
import { Connection, DataLine, Edit, Files, Lock, Plus, Refresh, SwitchButton, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { vendorApi, type EnterpriseRecord, type VendorApi, type VendorDataSource } from './api'

const loggedIn = ref(false)
const loginForm = ref({ username: 'vendor-admin', password: 'admin123' })
const loading = ref(false)
const sources = ref<VendorDataSource[]>([])
const activeSection = ref<'capabilities'|'enterprise'>('capabilities')
const enterpriseRecords = ref<EnterpriseRecord[]>([])
const enterpriseDialog = ref(false)
const enterpriseForm = ref({ enterpriseCode: '', creditCode: '', enterpriseName: '', legalPerson: '', sexCode: '1', riskLevel: 'A', registrationStatus: 'ACTIVE' })
const selectedId = ref(0)
const sourceDialog = ref(false)
const sourceEditingId = ref<number | null>(null)
const sourceForm = ref({ sourceCode: '', sourceName: '', description: '', basePath: '/vendor/data', status: 'ACTIVE' })
const apiDialog = ref(false)
const apiEditingId = ref<number | null>(null)
const apiForm = ref({ dataSourceId: 0, apiCode: '', apiName: '', servicePath: '', httpMethod: 'POST', responseCode: '0000', delayMs: 100, status: 'ACTIVE' })
const apiRelativePath = ref('')
const requestText = ref('{}')
const responseText = ref('{}')
const selectedSource = computed(() => sources.value.find(item => item.id === selectedId.value) ?? sources.value[0])
const apiSelectedSource = computed(() => sources.value.find(item => item.id === apiForm.value.dataSourceId))
const apiServicePrefix = computed(() => `http://127.0.0.1:8090${normalizePath(apiSelectedSource.value?.basePath ?? '/vendor')}`)
const apiServiceAddress = computed(() => `${apiServicePrefix.value}${normalizeRelativePath(apiRelativePath.value)}`)
const apiCount = computed(() => sources.value.reduce((sum, item) => sum + item.apis.length, 0))
const activeApiCount = computed(() => sources.value.flatMap(item => item.apis).filter(item => item.status === 'ACTIVE').length)

function normalizePath(value: string) {
  const path = `/${value.trim()}`.replace(/\/{2,}/g, '/').replace(/\/$/, '')
  return path || '/'
}

function normalizeRelativePath(value: string) {
  const path = normalizePath(value)
  return path === '/' ? '' : path
}

function relativePathOf(servicePath: string, basePath: string) {
  const service = normalizePath(servicePath)
  const base = normalizePath(basePath)
  if (service.startsWith(`${base}/`)) return service.slice(base.length)
  const segments = service.split('/').filter(Boolean)
  const leaf = segments[segments.length - 1]
  return leaf ? `/${leaf}` : ''
}

async function login() {
  if (loginForm.value.username !== 'vendor-admin' || loginForm.value.password !== 'admin123') return ElMessage.error('用户名或密码错误')
  loggedIn.value = true
  await loadCatalog()
}

async function loadCatalog() {
  loading.value = true
  try {
    ;[sources.value, enterpriseRecords.value] = await Promise.all([vendorApi.dataSources(), vendorApi.enterpriseRecords()])
    if (!sources.value.some(item => item.id === selectedId.value)) selectedId.value = sources.value[0]?.id ?? 0
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '供应商服务不可用') }
  finally { loading.value = false }
}

function openEnterpriseDialog() {
  const suffix = String(enterpriseRecords.value.length + 1).padStart(3, '0')
  enterpriseForm.value = { enterpriseCode: suffix, creditCode: `91320583MA1DEMO${suffix}`, enterpriseName: '', legalPerson: '', sexCode: '1', riskLevel: 'A', registrationStatus: 'ACTIVE' }
  enterpriseDialog.value = true
}

async function saveEnterprise() {
  await vendorApi.saveEnterpriseRecord(enterpriseForm.value)
  enterpriseRecords.value = await vendorApi.enterpriseRecords()
  enterpriseDialog.value = false
  ElMessage.success('企业业务数据已保存，查询接口立即可命中')
}

function openSource(item?: VendorDataSource) {
  sourceEditingId.value = item?.id ?? null
  const suffix = String(sources.value.length + 1).padStart(2, '0')
  sourceForm.value = item
    ? { sourceCode: item.sourceCode, sourceName: item.sourceName, description: item.description, basePath: item.basePath, status: item.status }
    : { sourceCode: `SOURCE_${suffix}`, sourceName: `新增外部数据源${suffix}`, description: '供应商新增的模拟数据能力', basePath: `/vendor/source-${suffix}`, status: 'ACTIVE' }
  sourceDialog.value = true
}

async function saveSource() {
  try {
    const result = sourceEditingId.value
      ? await vendorApi.updateDataSource(sourceEditingId.value, sourceForm.value)
      : await vendorApi.createDataSource(sourceForm.value)
    sourceDialog.value = false
    selectedId.value = result.id
    await loadCatalog()
    ElMessage.success(sourceEditingId.value ? '数据源修改已实时生效' : '数据源已实时发布')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') }
}

function openApi(item?: VendorApi) {
  const source = selectedSource.value
  apiEditingId.value = item?.id ?? null
  const suffix = String((source?.apis.length ?? 0) + 1).padStart(2, '0')
  apiForm.value = item
    ? { dataSourceId: item.dataSourceId, apiCode: item.apiCode, apiName: item.apiName, servicePath: item.servicePath, httpMethod: item.httpMethod, responseCode: item.responseCode, delayMs: item.delayMs, status: item.status }
    : { dataSourceId: source?.id ?? 0, apiCode: `${source?.sourceCode ?? 'DATA'}_QUERY_${suffix}`, apiName: `${source?.sourceName ?? '数据'}查询接口${suffix}`, servicePath: '', httpMethod: 'POST', responseCode: '0000', delayMs: 100, status: 'ACTIVE' }
  apiRelativePath.value = item
    ? relativePathOf(item.servicePath, sources.value.find(sourceItem => sourceItem.id === item.dataSourceId)?.basePath ?? '/vendor')
    : `/${(source?.sourceCode ?? 'data').toLowerCase().replace(/_/g, '-')}-query-${suffix}`
  requestText.value = JSON.stringify(item?.requestExample ?? { query_key: 'DEMO-001', query_reason: '业务查询' }, null, 2)
  responseText.value = JSON.stringify(item?.responseBody ?? { result: { code: '0000', message: 'success' }, data: { query_key: '${request.query_key}', name: '实时模拟数据', status_code: '1' } }, null, 2)
  apiDialog.value = true
}

async function saveApi() {
  try {
    if (!apiSelectedSource.value) return ElMessage.error('请选择所属数据源')
    if (!normalizeRelativePath(apiRelativePath.value)) return ElMessage.error('请填写 API 相对路径')
    const body = { ...apiForm.value, servicePath: `${normalizePath(apiSelectedSource.value.basePath)}${normalizeRelativePath(apiRelativePath.value)}`, requestExample: JSON.parse(requestText.value), responseBody: JSON.parse(responseText.value) }
    if (apiEditingId.value) await vendorApi.updateApi(apiEditingId.value, body)
    else await vendorApi.createApi(body)
    apiDialog.value = false
    await loadCatalog()
    ElMessage.success(apiEditingId.value ? 'API 新版本已实时生效' : 'API 已发布，无需重启应用')
  } catch (error) { ElMessage.error(error instanceof SyntaxError ? 'JSON 格式不正确' : error instanceof Error ? error.message : '保存失败') }
}

function formatTime(value: string) { return value ? value.replace('T', ' ').slice(0, 19) : '-' }
</script>

<template>
  <div v-if="!loggedIn" class="login-page">
    <section class="login-brand"><div class="mark">VX</div><p>企信数据服务商</p><h1>数据能力开放平台</h1><span>独立维护数据源与 API，配置保存后实时对外生效</span></section>
    <form class="login-form" @submit.prevent="login"><small>VENDOR CONSOLE</small><h2>供应商管理登录</h2><p>进入数据能力配置控制台</p><label>用户名</label><el-input v-model="loginForm.username" :prefix-icon="User" size="large"/><label>密码</label><el-input v-model="loginForm.password" :prefix-icon="Lock" type="password" size="large"/><el-button type="primary" size="large" native-type="submit">登录</el-button><span>演示账号：vendor-admin / admin123</span></form>
  </div>
  <div v-else class="app" v-loading="loading">
    <aside><div class="brand"><b>VX</b><div><strong>企信开放平台</strong><span>Vendor Open Platform</span></div></div><nav><button :class="{active:activeSection==='capabilities'}" @click="activeSection='capabilities'"><Files/>数据能力管理</button><button :class="{active:activeSection==='enterprise'}" @click="activeSection='enterprise'"><DataLine/>企业业务数据</button></nav><div class="runtime"><i></i><div><strong>开放服务正常</strong><span>API Gateway · 8090</span></div></div></aside>
    <main>
      <header><div><span>供应商管理控制台</span><b>/</b><span>数据能力管理</span></div><div><el-tag type="success" effect="plain">实时配置模式</el-tag><el-button :icon="SwitchButton" circle @click="loggedIn=false"/></div></header>
      <div v-if="activeSection==='capabilities'" class="content">
        <section class="heading"><div><h1>数据源与 API</h1><p>所有变更直接写入运行时配置，保存后无需重启供应商应用</p></div><div><el-button :icon="Refresh" @click="loadCatalog">刷新</el-button><el-button type="primary" :icon="Plus" @click="openSource()">新增数据源</el-button></div></section>
        <section class="metrics"><div><span>数据源</span><strong>{{ sources.length }}</strong><small>独立能力域</small></div><div><span>API 总数</span><strong>{{ apiCount }}</strong><small>动态路由</small></div><div><span>在线 API</span><strong class="green">{{ activeApiCount }}</strong><small>立即可调用</small></div><div><span>配置生效方式</span><strong class="mode">实时</strong><small>无需重启</small></div></section>
        <section class="workspace">
          <div class="source-list"><div class="source-title"><strong>数据源目录</strong><span>{{ sources.length }} 项</span></div><button v-for="source in sources" :key="source.id" :class="{active:source.id===selectedSource?.id}" @click="selectedId=source.id"><span><Files/></span><div><strong>{{ source.sourceName }}</strong><small>{{ source.sourceCode }} · {{ source.apis.length }} 个 API</small></div><i :class="source.status.toLowerCase()"></i></button></div>
          <div v-if="selectedSource" class="source-detail">
            <div class="source-head"><div><span>数据源</span><h2>{{ selectedSource.sourceName }}</h2><p>{{ selectedSource.description }}</p><code>{{ selectedSource.basePath }}</code></div><div><el-tag :type="selectedSource.status==='ACTIVE'?'success':'info'">{{ selectedSource.status==='ACTIVE'?'已上线':'已停用' }}</el-tag><el-button :icon="Edit" @click="openSource(selectedSource)">编辑数据源</el-button><el-button type="primary" :icon="Plus" @click="openApi()">新增 API</el-button></div></div>
            <div class="realtime-note"><Connection/><div><strong>运行时动态目录</strong><span>新增、修改或停用 API 后，动态路由立即读取最新配置，不产生服务重启和调用中断。</span></div><b>LIVE</b></div>
            <el-table :data="selectedSource.apis" class="api-table"><el-table-column label="API" min-width="190"><template #default="{row}"><div class="api-cell"><span>{{ row.httpMethod }}</span><div><strong>{{ row.apiName }}</strong><small>{{ row.apiCode }}</small></div></div></template></el-table-column><el-table-column label="实时服务地址" min-width="220"><template #default="{row}"><code>http://127.0.0.1:8090{{ row.servicePath }}</code></template></el-table-column><el-table-column label="版本" width="64"><template #default="{row}"><span :title="`更新于 ${formatTime(row.updatedAt)}`">V{{ row.version }}</span></template></el-table-column><el-table-column label="耗时" width="72"><template #default="{row}">{{ row.delayMs }}ms</template></el-table-column><el-table-column label="状态" width="72"><template #default="{row}"><el-tag :type="row.status==='ACTIVE'?'success':'info'" size="small">{{ row.status==='ACTIVE'?'在线':'停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="66"><template #default="{row}"><el-button text type="primary" @click="openApi(row)">编辑</el-button></template></el-table-column></el-table>
          </div>
        </section>
      </div>
      <div v-else class="content"><section class="heading"><div><h1>企业业务数据</h1><p>企业查询类 API 先校验请求契约，再按企业编码、统一信用代码或企业名称查询本表</p></div><div><el-button :icon="Refresh" @click="loadCatalog">刷新</el-button><el-button type="primary" :icon="Plus" @click="openEnterpriseDialog">新增企业</el-button></div></section><section class="realtime-note"><Connection/><div><strong>真实命中规则</strong><span>存在且查询条件一致时返回企业数据；不存在或编码与名称不一致时返回供应商码 1001。</span></div><b>DB QUERY</b></section><section class="source-detail"><el-table :data="enterpriseRecords" border><el-table-column prop="enterpriseCode" label="企业编码" width="110"/><el-table-column prop="creditCode" label="统一社会信用代码" width="210"/><el-table-column prop="enterpriseName" label="企业名称" min-width="260"/><el-table-column prop="legalPerson" label="法定代表人" width="130"/><el-table-column prop="riskLevel" label="风险等级" width="100"><template #default="{row}"><el-tag :type="row.riskLevel==='A'?'success':row.riskLevel==='B'?'warning':'danger'">{{ row.riskLevel }}</el-tag></template></el-table-column><el-table-column label="登记状态" width="110"><template #default="{row}"><el-tag :type="row.registrationStatus==='ACTIVE'?'success':'info'">{{ row.registrationStatus }}</el-tag></template></el-table-column></el-table></section></div>
    </main>
  </div>

  <el-dialog v-model="sourceDialog" :title="sourceEditingId?'编辑数据源':'新增数据源'" width="620px"><el-form label-position="top"><div class="grid"><el-form-item label="数据源名称"><el-input v-model="sourceForm.sourceName"/></el-form-item><el-form-item label="数据源编码"><el-input v-model="sourceForm.sourceCode"/></el-form-item></div><el-form-item label="能力说明"><el-input v-model="sourceForm.description" type="textarea" :rows="3"/></el-form-item><div class="grid"><el-form-item label="基础路径"><el-input v-model="sourceForm.basePath"/></el-form-item><el-form-item label="状态"><el-radio-group v-model="sourceForm.status"><el-radio-button value="ACTIVE">上线</el-radio-button><el-radio-button value="DISABLED">停用</el-radio-button></el-radio-group></el-form-item></div></el-form><template #footer><el-button @click="sourceDialog=false">取消</el-button><el-button type="primary" @click="saveSource">保存并实时生效</el-button></template></el-dialog>
  <el-dialog v-model="apiDialog" :title="apiEditingId?'编辑 API 并发布新版本':'新增 API'" width="820px"><el-form label-position="top"><div class="grid"><el-form-item label="所属数据源"><el-select v-model="apiForm.dataSourceId"><el-option v-for="item in sources" :key="item.id" :label="item.sourceName" :value="item.id"/></el-select></el-form-item><el-form-item label="HTTP 方法"><el-select v-model="apiForm.httpMethod"><el-option label="POST" value="POST"/><el-option label="GET" value="GET"/></el-select></el-form-item><el-form-item label="API 名称"><el-input v-model="apiForm.apiName"/></el-form-item><el-form-item label="API 编码"><el-input v-model="apiForm.apiCode"/></el-form-item></div><el-form-item label="API 相对路径"><el-input v-model="apiRelativePath"><template #prepend>{{ apiServicePrefix }}</template></el-input><div class="route-preview"><span>实时服务地址</span><code>{{ apiServiceAddress }}</code></div></el-form-item><div class="grid"><el-form-item label="供应商返回码"><el-input v-model="apiForm.responseCode"/></el-form-item><el-form-item label="模拟耗时"><el-input-number v-model="apiForm.delayMs" :min="0" :max="2000"/> ms</el-form-item></div><div class="contract-note"><Connection/><div><strong>请求样例即运行时校验契约</strong><span>字段、嵌套层级、类型和非空值都会校验；响应可使用 ${request.字段路径} 引用本次请求值。</span></div></div><div class="json-grid"><div><span>请求契约样例</span><el-input v-model="requestText" type="textarea" :autosize="{minRows:10,maxRows:16}"/></div><div><span>成功响应模板</span><el-input v-model="responseText" type="textarea" :autosize="{minRows:10,maxRows:16}"/></div></div><div class="status-row"><span>接口状态</span><el-radio-group v-model="apiForm.status"><el-radio-button value="ACTIVE">上线</el-radio-button><el-radio-button value="DISABLED">停用</el-radio-button></el-radio-group></div></el-form><template #footer><el-button @click="apiDialog=false">取消</el-button><el-button type="primary" @click="saveApi">保存并实时发布</el-button></template></el-dialog>
  <el-dialog v-model="enterpriseDialog" title="新增企业业务数据" width="680px"><el-form label-position="top"><div class="grid"><el-form-item label="企业编码"><el-input v-model="enterpriseForm.enterpriseCode"/></el-form-item><el-form-item label="统一社会信用代码"><el-input v-model="enterpriseForm.creditCode"/></el-form-item><el-form-item label="企业名称"><el-input v-model="enterpriseForm.enterpriseName"/></el-form-item><el-form-item label="法定代表人"><el-input v-model="enterpriseForm.legalPerson"/></el-form-item><el-form-item label="法人性别代码"><el-select v-model="enterpriseForm.sexCode"><el-option label="1 · 男" value="1"/><el-option label="2 · 女" value="2"/></el-select></el-form-item><el-form-item label="风险等级"><el-select v-model="enterpriseForm.riskLevel"><el-option label="A · 低风险" value="A"/><el-option label="B · 关注" value="B"/><el-option label="C · 高风险" value="C"/></el-select></el-form-item></div><el-form-item label="登记状态"><el-radio-group v-model="enterpriseForm.registrationStatus"><el-radio-button value="ACTIVE">正常</el-radio-button><el-radio-button value="CANCELLED">注销</el-radio-button></el-radio-group></el-form-item></el-form><template #footer><el-button @click="enterpriseDialog=false">取消</el-button><el-button type="primary" @click="saveEnterprise">保存并立即可查询</el-button></template></el-dialog>
</template>
