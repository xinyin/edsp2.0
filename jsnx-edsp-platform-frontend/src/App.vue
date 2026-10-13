<script setup lang="ts">
import './resource-workbench.css'
import { computed, nextTick, ref, watch } from 'vue'
import { useDisplayPreference } from './composables/useDisplayPreference'
import {
  Bell, Check, CircleCheck, Connection, DataAnalysis, DataBoard, Delete,
  DocumentChecked, DocumentCopy, Files, Key, Lock, Monitor, Plus, Promotion, Refresh,
  Search, Setting, SwitchButton, Tickets, User as UserIcon, Wallet, OfficeBuilding, Warning,
  Fold, Expand,
  Guide, ArrowRight
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiRequestError, isDemoMode, vendorApi } from './api'
import { buildResourceGuide, guideAdvice, isHttpEndpoint, isSuccessfulE2E, requestDeadlineMs } from './resource-guide'
import type {
  AuthorizationRecord, BillingRule, BillingSummary, CallTraceDetail, ClientApp, DashboardSummary,
  DataSourceSummary, FieldMapping, InterfaceDetail, InterfaceSummary, InterfaceVersionHistory, LoginResult,
  MonitorAlert, MonitorPoint, MonitorSummary, ProviderAccount, ProviderSummary, ReleaseApproval, TestResult,
  ResourceApiDetail, ResourceApiSummary, UsageRecord, ValidationResult, VendorApiDefinition, VendorDataSourceDefinition,
  SecurityAlgorithmDescriptor, SecurityPolicy
} from './types'

const loggedIn = ref(false)
const loginLoading = ref(false)
const loginForm = ref({ username: 'admin', password: 'admin123' })
const user = ref<LoginResult | null>(null)
const activeMenu = ref('overview')
const sidebarCollapsed = useDisplayPreference('sidebar-collapsed')
const resourceCatalogCollapsed = useDisplayPreference('resource-catalog-collapsed')
const releaseCatalogCollapsed = useDisplayPreference('release-catalog-collapsed')
const menuGroups = ref({ global: true, service: true, operations: true })
const loading = ref(false)
const demo = ref(false)
const dashboard = ref<DashboardSummary>({ providerCount: 0, dataSourceCount: 0, interfaceCount: 0, publishedCount: 0, draftCount: 0, activePublishedPlans: 0 })
const providers = ref<ProviderSummary[]>([])
const selectedProviderId = ref(0)
const providerAccounts = ref<ProviderAccount[]>([])
const providerDialog = ref(false)
const providerSaving = ref(false)
const providerForm = ref<ProviderSummary>({ id: 0, providerCode: '', providerName: '', status: 'ACTIVE' })
const accountDialog = ref(false)
const accountForm = ref({ username: '', displayName: '', mobile: '', email: '', roleCode: 'PROVIDER_ADMIN', permissions: ['DATASOURCE_VIEW', 'API_VIEW'], status: 'ACTIVE' })
const approvals = ref<ReleaseApproval[]>([])
const approvalDialog = ref(false)
const selectedApproval = ref<ReleaseApproval | null>(null)
const approvalComment = ref('')
const sources = ref<DataSourceSummary[]>([])
const interfaces = ref<InterfaceSummary[]>([])
const allInterfaces = ref<InterfaceSummary[]>([])
const allResourceApis = ref<ResourceApiSummary[]>([])
const clients = ref<ClientApp[]>([])
const authorizations = ref<AuthorizationRecord[]>([])
const authorizationSearch = ref('')
const authorizationClient = ref(0)
const authorizationPage = ref(1)
const filteredAuthorizations = computed(() => {
  const keyword = authorizationSearch.value.trim().toLowerCase()
  return authorizations.value.filter(item => (!authorizationClient.value || item.clientId === authorizationClient.value)
    && (!keyword || `${item.clientName} ${item.interfaceName} ${item.interfaceCode}`.toLowerCase().includes(keyword)))
})
const pagedAuthorizations = computed(() => filteredAuthorizations.value.slice((authorizationPage.value - 1) * 20, authorizationPage.value * 20))
watch([authorizationSearch, authorizationClient, authorizations], () => { authorizationPage.value = 1 })
const billingRules = ref<BillingRule[]>([])
const billingSummary = ref<BillingSummary>({ totalCalls: 0, billableCalls: 0, totalAmount: 0, hitCalls: 0, missCalls: 0, errorCalls: 0 })
const usageRecords = ref<UsageRecord[]>([])
const monitorSummary = ref<MonitorSummary>({ totalCalls: 0, successCalls: 0, errorCalls: 0, successRate: 100, avgDurationMs: 0, p95DurationMs: 0, avgUpstreamDurationMs: 0, activeAlerts: 0 })
const monitorPoints = ref<MonitorPoint[]>([])
const monitorAlerts = ref<MonitorAlert[]>([])
const callLogs = ref<UsageRecord[]>([])
const callLogFilters = ref({ status: 'ALL', interfaceCode: '', keyword: '' })
const callTrace = ref<CallTraceDetail | null>(null)
const callTraceDrawer = ref(false)
const callTraceLoading = ref(false)
const callTraceError = ref('')
const activeCallTraceRecord = ref<UsageRecord | null>(null)
const selectedLogNodeKey = ref('')
const vendorCatalog = ref<VendorDataSourceDefinition[]>([])
const vendorOnline = ref(false)
const vendorImportingKey = ref('')
const securityAlgorithms = ref<SecurityAlgorithmDescriptor[]>([])
const securityAlgorithmLoadError = ref('')
const algorithmDrawer = ref(false)
const editingSecurityMapping = ref<FieldMapping | null>(null)
type EnumPairDraft = { id: number; sourceValue: string; targetValue: string }
const enumDrawer = ref(false)
const editingEnumMapping = ref<FieldMapping | null>(null)
const enumPairs = ref<EnumPairDraft[]>([])
const enumDefaultValue = ref('')
let enumPairSequence = 0
const activeMappingRuleRow = ref<FieldMapping | null>(null)
const mappingRowKeys = new WeakMap<FieldMapping, string>()
let mappingRowKeySequence = 0

const selectedSourceId = ref(0)
const selectedInterfaceId = ref(0)
const detail = ref<InterfaceDetail | null>(null)
const securityPolicy = ref<SecurityPolicy>({ interfaceId: 0, enabled: false, encryptMode: 'NONE', signatureMode: 'NONE', requestFields: '', encryptionKeyReference: '', signingKeyReference: '', signatureField: 'signature', description: '' })
const savedMappingsBaseline = ref<FieldMapping[]>([])
const detailLoading = ref(false)
const sourceSwitching = ref(false)
const interfaceCache = new Map<number, InterfaceSummary[]>()
const detailCache = new Map<number, InterfaceDetail>()
const selectedInterfaceBySource = new Map<number, number>()
const selectedResourceApiBySource = new Map<number, number>()
let sourceRequestSequence = 0
const activeTab = ref('overview')
const upstreamSection = ref(0)
const upstreamSections = [
  { title: '连接与认证' },
  { title: '请求与响应' },
  { title: '接口联调' }
]
watch(selectedInterfaceId, () => { upstreamSection.value = 0 })
type ServiceDevelopmentStage = 'UPSTREAM' | 'ORCHESTRATION'
const serviceDevelopmentStage = ref<ServiceDevelopmentStage>('UPSTREAM')
// The platform's primary product output is the standardized response returned
// to internal consumers, so open this side first. Request adaptation remains
// available as a separate, explicitly named direction.
const mappingDirection = ref<'REQUEST' | 'RESPONSE'>('RESPONSE')
const sourceSearch = ref('')
const providerExpansion = ref<Record<number, boolean>>({})
const sourceExpansion = ref<Record<number, boolean>>({})
const timeoutExpanded = ref(false)
const guideBusy = ref(false)
const guideError = ref('')
const guideHelpExpanded = ref(false)
const lastUpstreamValidation = ref<{ interfaceId: number; connectionFingerprint: string } | null>(null)
const lastOrchestrationValidation = ref<{ interfaceId: number; version: number } | null>(null)
const lastE2ETest = ref<{ interfaceId: number; version: number } | null>(null)
const validation = ref<ValidationResult | null>(null)
const testResult = ref<TestResult | null>(null)
const testRunning = ref(false)
const testAbortController = ref<AbortController | null>(null)
const testElapsedSeconds = ref(0)
const testSucceeded = ref(false)
const testError = ref('')
const chainPhase = ref(0)
const selectedTraceKey = ref('DOWNSTREAM')
const publishResult = ref<ReleaseApproval | null>(null)
const interfaceVersions = ref<InterfaceVersionHistory[]>([])
const versionDialog = ref(false)
const selectedVersion = ref<InterfaceVersionHistory | null>(null)
const publishedDetailDrawer = ref(false)
const publishedDetailLoading = ref(false)
const publishedInterfaceDetail = ref<InterfaceDetail | null>(null)
const highlightedVersion = ref<number | null>(null)
const releaseInterfaceId = ref(0)
const releaseDetail = ref<InterfaceDetail | null>(null)
const releaseVersions = ref<InterfaceVersionHistory[]>([])
const releaseLoading = ref(false)
const releaseNoteManagement = ref('完成接口配置并通过联调验证')
const releaseSearch = ref('')
const releaseSourceExpansion = ref<Record<number, boolean>>({})
let releaseRequestSequence = 0
const releaseNote = ref('完成接口字段标准化配置并通过联调验证')
const requestText = ref('{}')
const responseText = ref('{}')
const upstreamRequestText = ref('{}')
const testMode = ref<'UPSTREAM' | 'PLATFORM' | 'E2E'>('PLATFORM')
const savedRequestBaseline = ref('{}')
const savedResponseBaseline = ref('{}')
const savedConnectionBaseline = ref('')
const savedUpstreamRequestBaseline = ref('{}')
const savedUpstreamResponseBaseline = ref('{}')
const savedResponseCodesBaseline = ref('')
const savedVendorCodePathBaseline = ref('result.code')
const savedSecurityBaseline = ref('')
const standardServiceForm = ref({ servicePath: '', httpMethod: 'POST' })
const savedStandardServiceBaseline = ref('')

const sourceDialog = ref(false)
const sourceSettingsDialog = ref(false)
const sourceSettingsSaving = ref(false)
const sourceSettingsError = ref('')
const sourceRuntimeDialog = ref(false)
const sourceRuntimeSaving = ref(false)
const sourceStep = ref(0)
const sourceSaving = ref(false)
const sourceError = ref('')
// Deployment environment and instance count are intentionally not part of the
// onboarding form until deployment orchestration is connected to the platform.
const sourceForm = ref({ providerId: 0, sourceCode: '', sourceName: '', applicationName: '', runtimeUrl: '', protocolType: 'HTTP_JSON', endpoint: '', defaultTimeoutMs: 3000 })
const sourceSettingsForm = ref({ providerId: 0, sourceCode: '', sourceName: '', protocolType: 'HTTP_JSON', endpoint: '', defaultTimeoutMs: 3000 })
const sourceRuntimeForm = ref({ applicationName: '', runtimeUrl: '' })
const apiDialog = ref(false)
const apiCreating = ref(false)
const deletingApi = ref(false)
const connectionSaving = ref(false)
const previewUpstreamUrl = computed(() => {
  if (connectionForm.value.configScope === 'OVERRIDE') return connectionForm.value.upstreamUrl.trim()
  const base = (selectedSource.value?.endpointUrl ?? '').replace(/\/+$/, '')
  const path = (connectionForm.value.upstreamPath ?? '').replace(/^\/+/, '')
  return base ? (path ? base + '/' + path : base) : ''
})
const previewTimeoutMs = computed(() => connectionForm.value.timeoutMode === 'INHERIT'
  ? selectedSource.value?.defaultTimeoutMs ?? 3000 : connectionForm.value.timeoutMs)
const canDeleteApi = computed(() => detail.value?.summary.status === 'DRAFT'
  && detail.value.summary.publishedVersion == null)
const deleteApiRestriction = computed(() => {
  if (canDeleteApi.value) return ''
  if (detail.value?.summary.status === 'PENDING_APPROVAL') return '接口正在审批中，不能删除；请进入服务发布查看状态，或到变更审批处理。'
  if (detail.value?.summary.publishedVersion != null) {
    const published = interfaceVersions.value.find(item => item.version === detail.value?.summary.publishedVersion)
    if (published && !published.historyComplete) return `该 API 已发布过 V${published.version}，但旧审批或发布档案不完整；已在服务发布中显示修复提示，不能物理删除。`
    return `该 API 已发布过 V${detail.value.summary.publishedVersion}，可在服务发布的版本历史中追溯；为保留授权、链路日志和版本记录不能物理删除。`
  }
  return '仅支持删除从未发布、且未进入审批的草稿 API。'
})
const apiForm = ref({ dataSourceId: 1, interfaceCode: '', interfaceName: '', servicePath: '', httpMethod: 'POST', configScope: 'INHERIT' as 'INHERIT'|'OVERRIDE', upstreamPath: '', upstreamUrl: '', timeoutMode: 'INHERIT' as 'INHERIT'|'OVERRIDE', timeoutMs: 3000, generateMock: false, upstreamRequestExample: null as Record<string, unknown> | null })
const platformServiceDialog = ref(false)
const platformServiceCreating = ref(false)
const platformServiceForm = ref({ resourceApiId: 0, serviceCode: '', serviceName: '', servicePath: '', httpMethod: 'POST' })
const connectionForm = ref({ configScope: 'INHERIT' as 'INHERIT'|'OVERRIDE', upstreamPath: '', upstreamUrl: '', timeoutMode: 'INHERIT' as 'INHERIT'|'OVERRIDE', timeoutMs: 3000 })
const vendorRequestExample = ref<Record<string, unknown> | null>(null)
const vendorResponseExample = ref<Record<string, unknown> | null>(null)
const clientDialog = ref(false)
const clientForm = ref({ clientCode: 'LOAN_APPROVAL', clientName: '贷款审批系统', institutionCode: '320583001', institutionName: '昆山支行', ownerName: '陈晨' })
const returnToAuthAfterClient = ref(false)
const authDialog = ref(false)
function authorizationDefaultDates() {
  const from = new Date()
  const to = new Date(from)
  to.setFullYear(to.getFullYear() + 1)
  const format = (date: Date) => date.toISOString().slice(0, 10)
  return { validFrom: format(from), validTo: format(to) }
}
const defaultAuthorizationDates = authorizationDefaultDates()
const authForm = ref({ clientId: 1, interfaceId: 1, environment: 'TEST', dailyQuota: 50000, ...defaultAuthorizationDates, approvedBy: '平台管理员' })
const batchAuthDialog = ref(false)
const batchAuthForm = ref({ clientId: 0, interfaceIds: [] as number[], environment: 'TEST', dailyQuota: 50000, ...defaultAuthorizationDates, approvedBy: '平台管理员' })
const batchAuthDateRange = ref<[string, string]>([defaultAuthorizationDates.validFrom, defaultAuthorizationDates.validTo])
const batchAuthFilters = ref({ sourceId: 0, keyword: '', grantStatus: 'UNAUTHORIZED' as 'ALL'|'UNAUTHORIZED'|'AUTHORIZED' })
const batchAuthPage = ref(1)
const batchAuthPageSize = ref(20)
const batchAuthSubmitting = ref(false)
const billingDialog = ref(false)
const billingForm = ref({ interfaceId: 1, billingMode: 'SUCCESS_COUNT', unitPrice: 0.35, hitBillable: true, missBillable: false, effectiveDate: '2026-07-24' })

const traceNodeDefinitions = [
  { key: 'DOWNSTREAM', label: '下游系统', short: 'CLIENT', icon: UserIcon },
  { key: 'GATEWAY', label: 'Gateway', short: 'ACCESS', icon: Connection },
  { key: 'SCHEMA', label: 'Schema', short: 'VALIDATE', icon: DocumentChecked },
  { key: 'REQUEST_MAPPING', label: '请求转换', short: 'MAPPING', icon: Setting },
  { key: 'SECURITY', label: '安全处理', short: 'SECURITY', icon: Lock },
  { key: 'DATA_SOURCE', label: '数据源应用', short: 'ADAPTER', icon: Files },
  { key: 'VENDOR', label: '上游服务', short: 'UPSTREAM', icon: OfficeBuilding },
  { key: 'MOCK_RESPONSE', label: '响应样例', short: 'MOCK', icon: DocumentCopy },
  { key: 'RESPONSE_MAPPING', label: '响应标准化', short: 'NORMALIZE', icon: DataAnalysis },
  { key: 'BILLING', label: '计量计费', short: 'BILLING', icon: Wallet }
]
const esbTraceNodeDefinitions = [
  { key: 'ESB_QUEUE', label: 'ESB 队列', short: 'XML QUEUE', icon: Tickets },
  { key: 'ESB_ADAPTER', label: 'ESB Adapter', short: 'PARSE', icon: Connection },
  { key: 'GATEWAY', label: '统一 Gateway', short: 'ROUTE', icon: DataBoard },
  { key: 'DATA_APPLICATION', label: '数据源应用', short: 'ADAPTER', icon: Files },
  { key: 'SUPPLIER', label: '上游服务', short: 'UPSTREAM', icon: OfficeBuilding },
  { key: 'STANDARD_RESPONSE', label: 'ESB XML 响应', short: 'XML REPLY', icon: DataAnalysis }
]

const testModes = [
  { value: 'UPSTREAM' as const, index: '01', label: '上游接口验证', path: '数据源应用 → 上游服务', description: '发送真实请求，验证连接、认证和原始响应' },
  { value: 'PLATFORM' as const, index: '02', label: '草稿链路测试（可选）', path: '标准请求 → 转换与安全 → 数据源应用 → 上游 → 标准响应', description: '真实调用上游，辅助检查当前草稿，不影响上线' },
  { value: 'E2E' as const, index: '03', label: '线上入口验收（可选）', path: 'XML → ESB → Gateway → 上游服务 → XML', description: '检查当前线上版本的 ESB 请求与响应、动态路由和调用方授权' }
]
const orchestrationTestModes = computed(() => testModes.filter(mode => mode.value !== 'UPSTREAM'))

const activeTraceDefinitions = computed(() => (testMode.value === 'E2E' ? esbTraceNodeDefinitions : traceNodeDefinitions).filter(node => {
  if (testMode.value === 'UPSTREAM') return ['DATA_SOURCE', 'VENDOR'].includes(node.key)
  if (testMode.value === 'PLATFORM') return !['GATEWAY', 'MOCK_RESPONSE'].includes(node.key)
  return node.key !== 'MOCK_RESPONSE'
}))

const activeTestMode = computed(() => testModes.find(mode => mode.value === testMode.value) ?? testModes[2])
const testActionLabel = computed(() => testMode.value === 'UPSTREAM'
  ? '验证上游接口' : testMode.value === 'PLATFORM' ? '执行草稿链路测试' : '执行线上入口验收')

function selectTestMode(mode: 'UPSTREAM' | 'PLATFORM' | 'E2E') {
  if (testRunning.value || guideBusy.value) return
  if (testMode.value === mode) return
  testMode.value = mode
  testResult.value = null
  testError.value = ''
  testSucceeded.value = false
  chainPhase.value = 0
  selectedTraceKey.value = mode === 'UPSTREAM' ? 'DATA_SOURCE' : 'DOWNSTREAM'
}

async function openGuideStep(key: string) {
  if (key === activeTab.value) return
  if (guideBusy.value || testRunning.value || connectionSaving.value) return
  if (!await confirmDiscardUnsaved()) return
  activeTab.value = key
  guideError.value = ''
  await nextTick()
  document.querySelector('.resource-workflow')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

const selectedTraceNode = computed(() => testResult.value?.traceNodes.find(node => node.key === selectedTraceKey.value)
  ?? testResult.value?.traceNodes[0] ?? null)

function readObjectPath(source: Record<string, unknown>, path: string) {
  return path.split('.').reduce<unknown>((current, key) => current && typeof current === 'object'
    ? (current as Record<string, unknown>)[key] : undefined, source)
}

function writeObjectPath(target: Record<string, unknown>, path: string, value: unknown) {
  const parts = path.split('.')
  let cursor = target
  parts.slice(0, -1).forEach(part => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part] as Record<string, unknown>
  })
  cursor[parts[parts.length - 1]] = value
}

function deleteObjectPath(target: Record<string, unknown>, path: string) {
  const parts = path.split('.')
  let cursor: Record<string, unknown> = target
  for (const part of parts.slice(0, -1)) {
    const next = cursor[part]
    if (!next || typeof next !== 'object') return
    cursor = next as Record<string, unknown>
  }
  delete cursor[parts[parts.length - 1]]
}

function upgradeLegacyGeneratedUpstreamExample(example: Record<string, unknown>) {
  const keys = Object.keys(example).sort()
  const isLegacyGenerated = keys.length === 2
    && keys[0] === 'queryKey' && keys[1] === 'queryReason'
    && example.queryKey === 'DEMO-QUERY-001' && example.queryReason === '业务准入'
  if (!isLegacyGenerated) return example
  return { query_key: example.queryKey, query_reason: example.queryReason }
}

function syncRequestExampleForMappingEdits(example: Record<string, unknown>, mappings: FieldMapping[]) {
  const synchronized = structuredClone(example)
  mappings.filter(mapping => mapping.direction === 'REQUEST' && mapping.id != null).forEach(mapping => {
    const previous = savedMappingsBaseline.value.find(item => item.id === mapping.id && item.direction === 'REQUEST')
    if (!previous || previous.sourcePath === mapping.sourcePath) return
    const previousValue = readObjectPath(synchronized, previous.sourcePath)
    if (previousValue === undefined || readObjectPath(synchronized, mapping.sourcePath) !== undefined) return
    writeObjectPath(synchronized, mapping.sourcePath, previousValue)
    deleteObjectPath(synchronized, previous.sourcePath)
  })
  return synchronized
}

function mapRequestPreview(request: Record<string, unknown>, mappings: FieldMapping[]) {
  if (!mappings.some(mapping => mapping.direction === 'REQUEST')) return structuredClone(request)
  const output: Record<string, unknown> = {}
  mappings.filter(mapping => mapping.direction === 'REQUEST').forEach(mapping => {
    const value = readObjectPath(request, mapping.sourcePath) ?? mapping.defaultValue
    if (value !== undefined && value !== null && value !== '') writeObjectPath(output, mapping.targetPath, value)
  })
  return output
}

const mappedRequestPreview = computed(() => {
  if (!detail.value) return {}
  try { return mapRequestPreview(JSON.parse(requestText.value), detail.value.mappings) }
  catch { return {} }
})

function xmlEscape(value: unknown) {
  return String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;')
}
function xmlNode(name: string, value: unknown): string {
  const safeName = name.replace(/[^A-Za-z0-9_.-]/g, '_') || 'field'
  if (Array.isArray(value)) return value.map(item => xmlNode(safeName, item)).join('')
  if (value !== null && typeof value === 'object') {
    return `<${safeName}>${Object.entries(value as Record<string, unknown>).map(([key, child]) => xmlNode(key, child)).join('')}</${safeName}>`
  }
  return `<${safeName}>${xmlEscape(value)}</${safeName}>`
}
function esbServiceTime() {
  const now = new Date()
  const part = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}${part(now.getMonth() + 1)}${part(now.getDate())}${part(now.getHours())}${part(now.getMinutes())}${part(now.getSeconds())}`
}
function buildEsbXml(serviceId: string, serviceSn: string, requesterId: string, body: Record<string, unknown>) {
  const request = Object.entries(body).map(([key, value]) => xmlNode(key, value)).join('')
  return `<?xml version="1.0" encoding="UTF-8"?>\n<Service>\n  <Service_Header>\n    <service_sn>${xmlEscape(serviceSn)}</service_sn>\n    <service_id>${xmlEscape(serviceId)}</service_id>\n    <requester_id>${xmlEscape(requesterId)}</requester_id>\n    <service_time>${esbServiceTime()}</service_time>\n    <version_id>01</version_id>\n  </Service_Header>\n  <Service_Body>\n    <request>${request}</request>\n  </Service_Body>\n</Service>`
}
const esbXmlPreview = computed(() => {
  if (!detail.value) return ''
  try { return buildEsbXml(detail.value.summary.interfaceCode, 'TRACE_PREVIEW', activeClients.value[0]?.clientCode ?? 'REQUESTER_ID', JSON.parse(requestText.value)) }
  catch { return '请输入有效 JSON 后生成 XML 入队报文' }
})
const esbXmlResponsePreview = computed(() => {
  const node = testResult.value?.traceNodes.find(item => item.key === 'STANDARD_RESPONSE')
  const output = node?.output
  return output && typeof output === 'object' && 'xml' in output
    ? String((output as Record<string, unknown>).xml ?? '') : ''
})
const standardRequestPreview = computed(() => {
  try { return JSON.parse(requestText.value) }
  catch { return {} }
})

const pageMeta = computed(() => ({
  overview: ['运营总览', '统一掌握服务运行、调用质量、资源健康与经营计量'],
  discovery: ['资源目录', '发现资源方开放能力并创建接入草稿'],
  resource: ['数据资源接入', '配置上游请求、响应和连接验证'],
  'service-development': ['标准服务开发', '定义平台标准请求、响应与安全编排'],
  authorization: ['API 访问授权', '按机构和调用系统授予平台 API 访问权限'],
  billing: ['计量管理', '按统一结果码生成调用计量明细并汇总费用'],
  monitor: ['运行监控', '监控 Gateway、数据源应用和上游服务的全链路耗时'],
  logs: ['链路追踪', '统一检索全行 API 调用，定位失败节点并查看节点级输入输出'],
  providers: ['资源方管理', '维护资源提供方资料、平台账号、角色与数据权限'],
  approvals: ['变更审批', '统一处理服务发布申请，审批通过后才会激活发布计划'],
  releases: ['服务发布', '集中查看版本档案、上线状态、下线和归档操作']
}[activeMenu.value] ?? ['外部数据平台', '统一管理']))
const selectedSource = computed(() => sources.value.find(item => item.id === selectedSourceId.value))
const selectedInterface = computed(() => allInterfaces.value.find(item => item.id === selectedInterfaceId.value))
const selectedResourceApi = computed(() => allResourceApis.value.find(item => item.id === selectedInterfaceId.value))
const selectedApiSource = computed(() => sources.value.find(item => item.id === apiForm.value.dataSourceId))
function resourceApiProjection(item: ResourceApiSummary): InterfaceSummary {
  return {
    id: item.id, dataSourceId: item.dataSourceId, interfaceCode: item.resourceApiCode,
    interfaceName: item.resourceApiName, servicePath: item.upstreamPath || item.upstreamUrl,
    httpMethod: item.httpMethod, upstreamUrl: item.upstreamUrl, upstreamPath: item.upstreamPath,
    configScope: item.configScope, effectiveTimeoutMs: item.effectiveTimeoutMs,
    upstreamStatus: item.status, status: item.status, currentVersion: item.currentVersion,
    publishedVersion: null, updatedAt: item.updatedAt, resourceApiId: item.id,
    resourceApiCode: item.resourceApiCode, resourceApiName: item.resourceApiName
  }
}
function adaptResourceDetail(item: ResourceApiDetail): InterfaceDetail {
  return {
    summary: resourceApiProjection(item.summary), requestSchema: {}, responseSchema: {},
    requestExample: structuredClone(item.requestExample), upstreamRequestExample: structuredClone(item.requestExample),
    upstreamResponseExample: structuredClone(item.responseExample), standardResponseExample: structuredClone(item.responseExample),
    timeoutMs: item.summary.effectiveTimeoutMs, configuredTimeoutMs: item.configuredTimeoutMs,
    timeoutSource: item.timeoutSource === 'RESOURCE_API' ? 'INTERFACE' : 'DATA_SOURCE', retryCount: 0,
    vendorCodePath: 'result.code', mappings: [], responseCodes: [],
    securityPolicy: { interfaceId: item.summary.id, enabled: false, encryptMode: 'NONE', signatureMode: 'NONE', requestFields: '', encryptionKeyReference: '', signingKeyReference: '', signatureField: 'signature', description: '' }
  }
}
const workspaceInterfaceRows = computed(() => activeMenu.value === 'resource'
  ? allResourceApis.value.map(resourceApiProjection) : allInterfaces.value)
const availableSources = computed(() => sources.value.filter(source => activeMenu.value !== 'service-development'
  || workspaceInterfaceRows.value.some(apiItem => apiItem.dataSourceId === source.id)))
type ProviderSourceGroup = { providerId: number; providerName: string; providerCode: string; sources: DataSourceSummary[] }
function resolvedProviderId(source: DataSourceSummary) {
  return providers.value.find(item => item.id === source.providerId)?.id
    ?? providers.value.find(item => item.providerName === source.providerName)?.id
    ?? source.providerId
    ?? -source.id
}
const filteredProviderGroups = computed<ProviderSourceGroup[]>(() => {
  const keyword = sourceSearch.value.trim().toLowerCase()
  const grouped = new Map<number, ProviderSourceGroup>()
  availableSources.value.forEach(source => {
    const provider = providers.value.find(item => item.id === source.providerId)
      ?? providers.value.find(item => item.providerName === source.providerName)
    const providerId = resolvedProviderId(source)
    const providerName = provider?.providerName ?? source.providerName ?? '未关联资源提供商'
    const providerCode = provider?.providerCode ?? ''
    const providerMatched = `${providerName}${providerCode}`.toLowerCase().includes(keyword)
    const sourceMatched = `${source.sourceName}${source.sourceCode}`.toLowerCase().includes(keyword)
    const apiMatched = workspaceInterfaceRows.value.some(apiItem => apiItem.dataSourceId === source.id
      && `${apiItem.interfaceName}${apiItem.interfaceCode}${apiItem.servicePath}`.toLowerCase().includes(keyword))
    if (keyword && !providerMatched && !sourceMatched && !apiMatched) return
    if (!grouped.has(providerId)) grouped.set(providerId, { providerId, providerName, providerCode, sources: [] })
    grouped.get(providerId)!.sources.push(source)
  })
  return [...grouped.values()]
})
const filteredSources = computed(() => filteredProviderGroups.value.flatMap(group => group.sources))
function sourceInterfaces(sourceId: number) {
  const keyword = sourceSearch.value.trim().toLowerCase()
  const rows = workspaceInterfaceRows.value.filter(item => item.dataSourceId === sourceId)
  if (!keyword) return rows
  const source = sources.value.find(item => item.id === sourceId)
  const provider = providers.value.find(item => item.id === source?.providerId)
    ?? providers.value.find(item => item.providerName === source?.providerName)
  const sourceMatched = source && `${source.sourceName}${source.sourceCode}${source.providerName}${provider?.providerCode ?? ''}`.toLowerCase().includes(keyword)
  return sourceMatched ? rows : rows.filter(item =>
    `${item.interfaceName}${item.interfaceCode}${item.servicePath}`.toLowerCase().includes(keyword))
}
function isProviderExpanded(providerId: number) {
  const selectedSourceItem = sources.value.find(item => item.id === selectedSourceId.value)
  const selectedProviderId = selectedSourceItem ? resolvedProviderId(selectedSourceItem) : undefined
  return !!sourceSearch.value.trim() || (providerExpansion.value[providerId] ?? providerId === selectedProviderId)
}
function isSourceExpanded(sourceId: number) {
  return !!sourceSearch.value.trim() || (sourceExpansion.value[sourceId] ?? sourceId === selectedSourceId.value)
}
function setAllSourcesExpanded(expanded: boolean) {
  providerExpansion.value = Object.fromEntries(filteredProviderGroups.value.map(group => [group.providerId, expanded]))
  sourceExpansion.value = Object.fromEntries(sources.value.map(source => [source.id, expanded]))
}
async function locateSelectedApi() {
  sourceSearch.value = ''
  const source = sources.value.find(item => item.id === selectedSourceId.value)
  const providerId = source ? resolvedProviderId(source) : undefined
  if (providerId != null) providerExpansion.value[providerId] = true
  sourceExpansion.value[selectedSourceId.value] = true
  await nextTick()
  document.querySelector('.resource-api-item.active')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}
const filteredMappings = computed(() => detail.value?.mappings.filter(item => item.direction === mappingDirection.value) ?? [])
const mappingIsPassThrough = computed(() => filteredMappings.value.length === 0)
const mappingModeLabel = computed(() => mappingIsPassThrough.value ? '自动透传' : '字段转换')
const hasUnpublishedChanges = computed(() => !!detail.value && detail.value.summary.publishedVersion !== detail.value.summary.currentVersion)
const authorizableInterfaces = computed(() => allInterfaces.value.filter(item =>
  item.publishedVersion != null && !['OFFLINE', 'DELETED'].includes(item.status)))
const activeClients = computed(() => clients.value.filter(item => item.status === 'ACTIVE'))
const today = computed(() => new Date().toISOString().slice(0, 10))
const validAuthorizations = computed(() => authorizations.value.filter(item => item.status === 'ACTIVE'
  && item.validFrom <= today.value && item.validTo >= today.value))
const validTestAuthorizations = computed(() => validAuthorizations.value.filter(item => item.environment.toUpperCase() === 'TEST'))
const releaseHasAuthorization = computed(() => !!releaseDetail.value
  && validAuthorizations.value.some(item => item.interfaceId === releaseDetail.value?.summary.id))
function authorizationStatus(item: AuthorizationRecord) {
  if (item.status !== 'ACTIVE') return { text: '已停用', type: 'info' as const }
  if (item.validFrom > today.value) return { text: '待生效', type: 'warning' as const }
  if (item.validTo < today.value) return { text: '已过期', type: 'danger' as const }
  return { text: '生效', type: 'success' as const }
}
function hasAuthorization(interfaceId: number) {
  return authorizations.value.some(item => item.clientId === batchAuthForm.value.clientId
    && item.interfaceId === interfaceId && item.environment === batchAuthForm.value.environment
    && item.status === 'ACTIVE' && item.validFrom <= today.value && item.validTo >= today.value)
}
const filteredBatchAuthorizationInterfaces = computed(() => {
  const keyword = batchAuthFilters.value.keyword.trim().toLowerCase()
  return authorizableInterfaces.value.filter(item => {
    if (batchAuthFilters.value.sourceId && item.dataSourceId !== batchAuthFilters.value.sourceId) return false
    if (keyword && !`${item.interfaceName}${item.interfaceCode}${item.servicePath}`.toLowerCase().includes(keyword)) return false
    const granted = hasAuthorization(item.id)
    if (batchAuthFilters.value.grantStatus === 'AUTHORIZED' && !granted) return false
    if (batchAuthFilters.value.grantStatus === 'UNAUTHORIZED' && granted) return false
    return true
  })
})
const pagedBatchAuthorizationInterfaces = computed(() => {
  const start = (batchAuthPage.value - 1) * batchAuthPageSize.value
  return filteredBatchAuthorizationInterfaces.value.slice(start, start + batchAuthPageSize.value)
})
const batchPageSelectedCount = computed(() => pagedBatchAuthorizationInterfaces.value.filter(
  item => batchAuthForm.value.interfaceIds.includes(item.id)).length)
const selectedBatchAuthorizationInterfaces = computed(() => authorizableInterfaces.value.filter(
  item => batchAuthForm.value.interfaceIds.includes(item.id)))
function toggleBatchInterface(interfaceId: number, selected: unknown) {
  const ids = new Set(batchAuthForm.value.interfaceIds)
  Boolean(selected) ? ids.add(interfaceId) : ids.delete(interfaceId)
  batchAuthForm.value.interfaceIds = [...ids]
}
function selectBatchPage(selected: unknown) {
  pagedBatchAuthorizationInterfaces.value.forEach(item => toggleBatchInterface(item.id, Boolean(selected)))
}
function selectAllBatchResults() {
  batchAuthForm.value.interfaceIds = [...new Set([
    ...batchAuthForm.value.interfaceIds,
    ...filteredBatchAuthorizationInterfaces.value.map(item => item.id)
  ])]
}
function clearBatchSelection() { batchAuthForm.value.interfaceIds = [] }
function removeBatchSelection(interfaceId: number) { toggleBatchInterface(interfaceId, false) }
function resetBatchPage() { batchAuthPage.value = 1 }
const selectedProvider = computed(() => providers.value.find(item => item.id === selectedProviderId.value))
const pendingApprovals = computed(() => approvals.value.filter(item => item.status === 'PENDING'))
const logSelectedNode = computed(() => callTrace.value?.nodes.find(node => node.key === selectedLogNodeKey.value) ?? callTrace.value?.nodes[0])
const failedCallCount = computed(() => callLogs.value.filter(item => item.requestStatus !== 'SUCCESS' || item.resultStatus === 'ERROR').length)
const overviewSuccessRate = computed(() => monitorSummary.value.totalCalls > 0 ? monitorSummary.value.successRate : 100)
const overviewMaxCalls = computed(() => Math.max(1, ...monitorPoints.value.map(item => item.calls)))
const overviewHealthySources = computed(() => sources.value.filter(item => !['INACTIVE', 'OFFLINE', 'DISABLED'].includes(item.status)).length)
const overviewTotalInstances = computed(() => sources.value.reduce((sum, item) => sum + item.instanceCount, 0))
const overviewResultTotal = computed(() => Math.max(1, billingSummary.value.hitCalls + billingSummary.value.missCalls + billingSummary.value.errorCalls))
const overviewResultMix = computed(() => [
  { key: 'hit', label: '成功命中', value: billingSummary.value.hitCalls, color: '#718d7e' },
  { key: 'miss', label: '未查得', value: billingSummary.value.missCalls, color: '#a48a5b' },
  { key: 'error', label: '异常', value: billingSummary.value.errorCalls, color: '#a9676c' }
].map(item => ({ ...item, percentage: Math.round(item.value * 1000 / overviewResultTotal.value) / 10 })))
const activeTrendIndex = ref<number | null>(null)
const activeResultKey = ref<string | null>(null)
const activeTrendPoint = computed(() => activeTrendIndex.value == null ? null : monitorPoints.value[activeTrendIndex.value])
const activeResultItem = computed(() => overviewResultMix.value.find(item => item.key === activeResultKey.value) ?? null)
const overviewResultSegments = computed(() => {
  let offset = 0
  return overviewResultMix.value.map(item => {
    const segment = { ...item, offset }
    offset += item.percentage
    return segment
  })
})
const overviewTopInterfaces = computed(() => {
  const groups = new Map<string, { name: string; code: string; calls: number; errors: number; totalDuration: number }>()
  usageRecords.value.forEach(item => {
    const current = groups.get(item.interfaceCode) ?? { name: item.interfaceName, code: item.interfaceCode, calls: 0, errors: 0, totalDuration: 0 }
    current.calls += 1
    current.totalDuration += item.durationMs
    if (item.resultStatus === 'ERROR' || item.requestStatus !== 'SUCCESS') current.errors += 1
    groups.set(item.interfaceCode, current)
  })
  return [...groups.values()].sort((a, b) => b.calls - a.calls).slice(0, 5).map(item => ({
    ...item,
    avgDuration: Math.round(item.totalDuration / item.calls),
    successRate: Math.round((item.calls - item.errors) * 1000 / item.calls) / 10
  }))
})
const overviewRecentFailures = computed(() => usageRecords.value
  .filter(item => item.resultStatus === 'ERROR' || item.requestStatus !== 'SUCCESS')
  .slice(0, 4))
const activeMonitorPointIndex = ref<number | null>(null)
const activeMonitorNodeKey = ref('VENDOR')
const selectedMonitorAlertIndex = ref(0)
const monitorRecordFilter = ref<'ALL' | 'ERROR' | 'SLOW'>('ALL')
const monitorTimeline = computed(() => [...monitorPoints.value].reverse())
const activeMonitorPoint = computed(() => activeMonitorPointIndex.value == null ? null : monitorTimeline.value[activeMonitorPointIndex.value])
const monitorPlatformDuration = computed(() => Math.max(2, monitorSummary.value.avgDurationMs - monitorSummary.value.avgUpstreamDurationMs))
const monitorTopologyNodes = computed(() => {
  const gatewayDuration = Math.max(1, Math.round(monitorPlatformDuration.value * .42))
  const adapterDuration = Math.max(1, monitorPlatformDuration.value - gatewayDuration)
  return [
    { key: 'GATEWAY', name: '统一 Gateway', code: 'ACCESS', duration: gatewayDuration, description: '认证、授权、配额与动态路由', status: gatewayDuration > 200 ? 'WARNING' : 'HEALTHY', icon: Connection },
    { key: 'ADAPTER', name: '数据源应用', code: 'ADAPTER', duration: adapterDuration, description: 'Schema、字段映射与响应标准化', status: adapterDuration > 300 ? 'WARNING' : 'HEALTHY', icon: Files },
    { key: 'VENDOR', name: '上游服务', code: 'UPSTREAM', duration: monitorSummary.value.avgUpstreamDurationMs, description: '上游网络与业务处理耗时', status: monitorSummary.value.avgUpstreamDurationMs > 800 ? 'WARNING' : 'HEALTHY', icon: OfficeBuilding }
  ].map(item => ({ ...item, share: Math.round(item.duration * 1000 / Math.max(1, monitorSummary.value.avgDurationMs)) / 10 }))
})
const activeMonitorNode = computed(() => monitorTopologyNodes.value.find(item => item.key === activeMonitorNodeKey.value) ?? monitorTopologyNodes.value[0])
const selectedMonitorAlert = computed(() => monitorAlerts.value[selectedMonitorAlertIndex.value] ?? null)
const monitorErrorRecordCount = computed(() => usageRecords.value.filter(item => item.resultStatus === 'ERROR' || item.requestStatus !== 'SUCCESS').length)
const monitorSlowRecordCount = computed(() => usageRecords.value.filter(item => item.durationMs >= monitorSummary.value.p95DurationMs).length)
const filteredMonitorRecords = computed(() => usageRecords.value.filter(item => {
  if (monitorRecordFilter.value === 'ERROR') return item.resultStatus === 'ERROR' || item.requestStatus !== 'SUCCESS'
  if (monitorRecordFilter.value === 'SLOW') return item.durationMs >= monitorSummary.value.p95DurationMs
  return true
}))
const currentE2EVerified = computed(() => !!detail.value && lastE2ETest.value?.interfaceId === detail.value.summary.id
  && lastE2ETest.value?.version === detail.value.summary.publishedVersion)
const upstreamValidationFingerprint = computed(() => [
  savedConnectionBaseline.value, savedUpstreamRequestBaseline.value, savedUpstreamResponseBaseline.value
].join('|'))
const currentUpstreamVerified = computed(() => !!detail.value
  && lastUpstreamValidation.value?.interfaceId === detail.value.summary.id
  && lastUpstreamValidation.value.connectionFingerprint === upstreamValidationFingerprint.value)
const currentOrchestrationVerified = computed(() => !!detail.value
  && lastOrchestrationValidation.value?.interfaceId === detail.value.summary.id
  && lastOrchestrationValidation.value.version === detail.value.summary.currentVersion
  && draftEditorMatchesSaved())
const resourceContractChanged = ref(false)
function draftEditorMatchesSaved() {
  return !!detail.value && JSON.stringify(detail.value.mappings) === JSON.stringify(savedMappingsBaseline.value)
    && requestText.value === savedRequestBaseline.value
    && responseText.value === savedResponseBaseline.value
    && JSON.stringify(securityPolicy.value) === savedSecurityBaseline.value
    && JSON.stringify(detail.value.responseCodes) === savedResponseCodesBaseline.value
    && (detail.value.vendorCodePath || 'result.code') === (savedVendorCodePathBaseline.value || 'result.code')
    && JSON.stringify(standardServiceForm.value) === savedStandardServiceBaseline.value
}
const currentPreReleaseVerified = computed(() => currentOrchestrationVerified.value)
const apiGuideSteps = computed(() => buildResourceGuide({
  upstreamUrl: detail.value?.summary.upstreamUrl ?? '', servicePath: detail.value?.summary.servicePath ?? '',
  mappings: savedMappingsBaseline.value, responseCodes: JSON.parse(savedResponseCodesBaseline.value || '[]'),
  securityEnabled: JSON.parse(savedSecurityBaseline.value || '{}').enabled === true,
  status: detail.value?.summary.status ?? 'DRAFT', currentVersion: detail.value?.summary.currentVersion ?? 0,
  publishedVersion: detail.value?.summary.publishedVersion, orchestrationVerified: currentOrchestrationVerified.value
}))
const activeGuideIndex = computed(() => Math.max(0, apiGuideSteps.value.findIndex(item => item.key === activeTab.value)))
const activeGuideStep = computed(() => apiGuideSteps.value[activeGuideIndex.value])
const orchestrationGuideSteps = computed(() => apiGuideSteps.value
  .filter(item => ['request', 'codes', 'security', 'test'].includes(item.key))
  .map((item, index) => ({ ...item, index: String(index + 1).padStart(2, '0') })))
const orchestrationStepIndex = computed(() => Math.max(0, orchestrationGuideSteps.value.findIndex(item => item.key === activeTab.value)))
const activeGuideAdvice = computed(() => guideAdvice[activeTab.value] ?? guideAdvice.overview)
const nextGuideStep = computed(() => apiGuideSteps.value[activeGuideIndex.value + 1])
const nextGuideLabel = computed(() => activeTab.value === 'security' && !securityPolicy.value.enabled && !hasUnsavedChanges.value
  ? '无需安全策略，去联调' : `${hasUnsavedChanges.value ? '保存并继续' : '下一步'}：${nextGuideStep.value?.title ?? ''}`)
const hasUnsavedChanges = computed(() => {
  if (!detail.value) return false
  if (activeTab.value === 'overview') return JSON.stringify(connectionForm.value) !== savedConnectionBaseline.value
    || upstreamRequestText.value !== savedUpstreamRequestBaseline.value
    || responseText.value !== savedUpstreamResponseBaseline.value
  if (activeTab.value === 'request') return JSON.stringify(detail.value.mappings) !== JSON.stringify(savedMappingsBaseline.value)
    || requestText.value !== savedRequestBaseline.value || responseText.value !== savedResponseBaseline.value
    || JSON.stringify(standardServiceForm.value) !== savedStandardServiceBaseline.value
  if (activeTab.value === 'codes') return JSON.stringify(detail.value.responseCodes) !== savedResponseCodesBaseline.value
    || detail.value.vendorCodePath !== savedVendorCodePathBaseline.value
  if (activeTab.value === 'security') return JSON.stringify(securityPolicy.value) !== savedSecurityBaseline.value
  return false
})
const upstreamSaveRequired = computed(() => hasUnsavedChanges.value
  || (activeMenu.value === 'resource' && detail.value?.summary.upstreamStatus !== 'READY'))
const resourceEditorState = computed(() => {
  if (hasUnsavedChanges.value) return { key: 'unsaved', label: '未保存修改', hint: '仅保留在当前浏览器页面' }
  if (activeMenu.value === 'resource') return detail.value?.summary.upstreamStatus === 'READY'
    ? { key: 'published', label: '上游配置已保存', hint: '可基于此资源创建标准服务' }
    : { key: 'draft', label: '接入草稿', hint: '保存后可用于标准服务开发' }
  if (detail.value?.summary.status === 'OFFLINE') return { key: 'offline', label: '线上已下线', hint: `草稿 V${detail.value.summary.currentVersion} 可继续编辑` }
  if (detail.value?.summary.status === 'PENDING_APPROVAL') return { key: 'pending', label: '审批中', hint: `草稿 V${detail.value.summary.currentVersion} 已提交审批` }
  if (detail.value && detail.value.summary.publishedVersion === detail.value.summary.currentVersion) return { key: 'published', label: '已发布', hint: `线上生效 V${detail.value.summary.currentVersion}` }
  return { key: 'draft', label: '草稿版本', hint: `已持久化 V${detail.value?.summary.currentVersion ?? '-'}` }
})
const resourcePrimaryActionLabel = computed(() => ({
  overview: '保存连接配置', request: '保存字段标准化', codes: '保存返回码配置',
  security: '保存安全策略', test: testActionLabel.value, release: '提交发布审批'
}[activeTab.value] ?? '保存当前配置'))
const resourcePrimaryActionDisabled = computed(() => activeTab.value === 'release'
  && (!hasUnpublishedChanges.value || detail.value?.summary.status === 'PENDING_APPROVAL'))
const slowestLogNode = computed(() => callTrace.value?.nodes.reduce((slowest, node) => !slowest || node.durationMs > slowest.durationMs ? node : slowest, callTrace.value.nodes[0]))

async function login() {
  loginLoading.value = true
  try {
    user.value = await api.login(loginForm.value.username, loginForm.value.password)
    loggedIn.value = true
    await loadAll()
    ElMessage.success('登录成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  } finally { loginLoading.value = false }
}

async function loadAll() {
  loading.value = true
  try {
    const [dashboardData, providerData, sourceData, resourceApiData, interfaceData, clientData, authData, rules, summary, usage, monitor, points, alerts, approvalData, algorithmData] = await Promise.all([
      api.dashboard(), api.providers(), api.dataSources(), api.resourceApis(), api.interfaces(), api.clients(), api.authorizations(), api.billingRules(), api.billingSummary(), api.usageRecords(),
      api.monitorSummary(), api.monitorPoints(), api.monitorAlerts(), api.approvals(), loadSecurityAlgorithms()
    ])
    dashboard.value = dashboardData
    providers.value = providerData
    sources.value = sourceData
    allResourceApis.value = resourceApiData
    allInterfaces.value = interfaceData
    interfaceCache.clear()
    sourceData.forEach(source => {
      interfaceCache.set(source.id, interfaceData.filter(item => item.dataSourceId === source.id))
    })
    clients.value = clientData
    authorizations.value = authData
    billingRules.value = rules
    billingSummary.value = summary
    usageRecords.value = usage
    monitorSummary.value = monitor
    monitorPoints.value = points
    monitorAlerts.value = alerts
    approvals.value = approvalData
    securityAlgorithms.value = algorithmData
    demo.value = isDemoMode()
    if (!selectedSourceId.value && sourceData.length) selectedSourceId.value = sourceData[0].id
    if (!selectedProviderId.value && providerData.length) { selectedProviderId.value = providerData[0].id; await loadProviderAccounts() }
    await loadInterfaces(false)
  } finally { loading.value = false }
}

async function loadSecurityAlgorithms() {
  try {
    const algorithms = await api.securityAlgorithms()
    securityAlgorithmLoadError.value = ''
    return algorithms
  } catch (error) {
    securityAlgorithmLoadError.value = error instanceof Error ? error.message : '安全算法目录加载失败'
    return []
  }
}

async function loadInterfaces(reset = true) {
  if (!selectedSourceId.value) return
  const sourceId = selectedSourceId.value
  const requestSequence = ++sourceRequestSequence
  sourceSwitching.value = true
  try {
    let sourceInterfaces: InterfaceSummary[]
    if (activeMenu.value === 'resource') {
      sourceInterfaces = allResourceApis.value.filter(item => item.dataSourceId === sourceId).map(resourceApiProjection)
    } else {
      sourceInterfaces = interfaceCache.get(sourceId) ?? await api.interfaces(sourceId)
      interfaceCache.set(sourceId, sourceInterfaces)
    }
    if (requestSequence !== sourceRequestSequence || sourceId !== selectedSourceId.value) return
    const visibleInterfaces = sourceInterfaces
    interfaces.value = visibleInterfaces
    const selectionMemory = activeMenu.value === 'resource' ? selectedResourceApiBySource : selectedInterfaceBySource
    const rememberedInterface = selectionMemory.get(sourceId)
    if (reset || !visibleInterfaces.some(item => item.id === selectedInterfaceId.value)) {
      selectedInterfaceId.value = rememberedInterface && visibleInterfaces.some(item => item.id === rememberedInterface)
        ? rememberedInterface : visibleInterfaces[0]?.id ?? 0
    }
    selectionMemory.set(sourceId, selectedInterfaceId.value)
    await loadDetail()
  } finally {
    if (requestSequence === sourceRequestSequence) sourceSwitching.value = false
  }
}

async function loadDetail() {
  if (!selectedInterfaceId.value) { detail.value = null; interfaceVersions.value = []; return }
  const requestedInterfaceId = selectedInterfaceId.value
  if (detail.value?.summary.id !== requestedInterfaceId) timeoutExpanded.value = false
  detailLoading.value = true
  const resourceWorkspace = activeMenu.value === 'resource'
  const cachedDetail = resourceWorkspace ? undefined : detailCache.get(requestedInterfaceId)
  if (cachedDetail) {
    detail.value = structuredClone(cachedDetail)
    detailLoading.value = false
    sourceSwitching.value = false
  }
  testResult.value = null
  testRunning.value = false
  testSucceeded.value = false
  testError.value = ''
  chainPhase.value = 0
  selectedTraceKey.value = 'DOWNSTREAM'
  try {
    const loadedDetail = resourceWorkspace
      ? adaptResourceDetail(await api.resourceApi(requestedInterfaceId))
      : await api.detail(requestedInterfaceId)
    const loadedVerification = resourceWorkspace
      ? { ...await api.resourceValidationStatus(requestedInterfaceId), orchestrationVerified: false, resourceContractChanged: false }
      : await api.validationStatus(requestedInterfaceId)
    // Version history is additive. Keep the editor usable while an older
    // management service is being rolled out and expose an empty archive.
    let loadedVersions: InterfaceVersionHistory[] = []
    if (!resourceWorkspace) {
      try { loadedVersions = await api.interfaceVersions(requestedInterfaceId) } catch { /* older backend */ }
    }
    if (requestedInterfaceId !== selectedInterfaceId.value) return
    loadedDetail.vendorCodePath = loadedDetail.vendorCodePath || 'result.code'
    if (!resourceWorkspace) detailCache.set(requestedInterfaceId, structuredClone(loadedDetail))
    detail.value = loadedDetail
    interfaceVersions.value = loadedVersions
    guideError.value = ''
    connectionForm.value = {
      configScope: loadedDetail.summary.configScope ?? 'OVERRIDE',
      upstreamPath: loadedDetail.summary.upstreamPath ?? '',
      upstreamUrl: loadedDetail.summary.upstreamUrl,
      timeoutMode: loadedDetail.configuredTimeoutMs == null ? 'INHERIT' : 'OVERRIDE',
      timeoutMs: loadedDetail.configuredTimeoutMs ?? loadedDetail.timeoutMs
    }
    securityPolicy.value = loadedDetail.securityPolicy ?? { interfaceId: loadedDetail.summary.id, enabled: false, encryptMode: 'NONE', signatureMode: 'NONE', requestFields: '', encryptionKeyReference: '', signingKeyReference: '', signatureField: 'signature', description: '' }
    savedMappingsBaseline.value = structuredClone(loadedDetail.mappings)
    requestText.value = JSON.stringify(loadedDetail.requestExample, null, 2)
    responseText.value = JSON.stringify(loadedDetail.upstreamResponseExample, null, 2)
    const persistedUpstreamRequestExample = Object.keys(loadedDetail.upstreamRequestExample ?? {}).length
      ? loadedDetail.upstreamRequestExample : mapRequestPreview(loadedDetail.requestExample, loadedDetail.mappings)
    const upstreamRequestExample = upgradeLegacyGeneratedUpstreamExample(persistedUpstreamRequestExample)
    upstreamRequestText.value = JSON.stringify(upstreamRequestExample, null, 2)
    savedConnectionBaseline.value = JSON.stringify(connectionForm.value)
    savedUpstreamRequestBaseline.value = JSON.stringify(persistedUpstreamRequestExample, null, 2)
    savedUpstreamResponseBaseline.value = responseText.value
    savedSecurityBaseline.value = JSON.stringify(securityPolicy.value)
    savedRequestBaseline.value = requestText.value
    savedResponseBaseline.value = responseText.value
    savedResponseCodesBaseline.value = JSON.stringify(loadedDetail.responseCodes)
    savedVendorCodePathBaseline.value = loadedDetail.vendorCodePath || 'result.code'
    standardServiceForm.value = { servicePath: loadedDetail.summary.servicePath, httpMethod: loadedDetail.summary.httpMethod }
    savedStandardServiceBaseline.value = JSON.stringify(standardServiceForm.value)
    lastUpstreamValidation.value = loadedVerification.upstreamVerified
      ? { interfaceId: requestedInterfaceId, connectionFingerprint: upstreamValidationFingerprint.value } : null
    lastOrchestrationValidation.value = loadedVerification.orchestrationVerified
      ? { interfaceId: requestedInterfaceId, version: loadedDetail.summary.currentVersion } : null
    resourceContractChanged.value = loadedVerification.resourceContractChanged === true
    validation.value = null
    publishResult.value = null
  } catch (error) {
    if (requestedInterfaceId === selectedInterfaceId.value) {
      ElMessage.error(error instanceof Error ? error.message : '接口配置加载失败')
    }
  } finally {
    if (requestedInterfaceId === selectedInterfaceId.value) detailLoading.value = false
  }
}

async function syncResourceContract() {
  if (!detail.value || !await confirmDiscardUnsaved()) return
  const id = detail.value.summary.id
  try {
    await ElMessageBox.confirm('更新草稿的上游地址、超时及请求/响应契约。已有映射保留，未单独修改的透传样例随之更新；线上版本不变，更新后需重新验证。', '同步资源契约', { confirmButtonText: '同步到草稿', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  try {
    guideBusy.value = true
    await api.syncResourceContract(id)
    detailCache.delete(id)
    if (selectedInterfaceId.value === id) await loadDetail()
    ElMessage.success('资源契约已同步，请核对映射；可按需执行草稿链路测试')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '同步失败')
  } finally { guideBusy.value = false }
}

function markDraftSaved() {
  if (!detail.value) return
  detail.value.summary.currentVersion = (detail.value.summary.publishedVersion ?? 0) + 1
  if (detail.value.summary.status !== 'OFFLINE') detail.value.summary.status = 'DRAFT'
  lastOrchestrationValidation.value = null
  lastE2ETest.value = null
}

async function saveSecurityPolicy() {
  if (!detail.value) return false
  securityPolicy.value = await api.saveSecurityPolicy(detail.value.summary.id, {
    enabled: securityPolicy.value.enabled,
    encryptMode: securityPolicy.value.encryptMode,
    signatureMode: securityPolicy.value.signatureMode,
    requestFields: securityPolicy.value.requestFields,
    encryptionKeyReference: securityPolicy.value.encryptionKeyReference,
    signingKeyReference: securityPolicy.value.signingKeyReference,
    signatureField: securityPolicy.value.signatureField,
    description: securityPolicy.value.description
  })
  detailCache.delete(detail.value.summary.id)
  markDraftSaved()
  savedSecurityBaseline.value = JSON.stringify(securityPolicy.value)
  validation.value = null
  ElMessage.success('安全策略已保存，请重新验证当前草稿')
  return true
}

async function confirmDiscardUnsaved() {
  if (!hasUnsavedChanges.value) return true
  try {
    await ElMessageBox.confirm('当前页面存在未保存修改，离开后修改将丢失。', '未保存修改', {
      confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning'
    })
    // Step navigation keeps the same detail object, so explicitly restore the
    // saved draft when the user chooses to discard (not just hide the form).
    if (activeTab.value === 'overview') {
      connectionForm.value = JSON.parse(savedConnectionBaseline.value)
      upstreamRequestText.value = savedUpstreamRequestBaseline.value
      responseText.value = savedUpstreamResponseBaseline.value
    }
    if (activeTab.value === 'request' && detail.value) {
      detail.value.mappings = JSON.parse(JSON.stringify(savedMappingsBaseline.value))
      requestText.value = savedRequestBaseline.value
      responseText.value = savedResponseBaseline.value
      standardServiceForm.value = JSON.parse(savedStandardServiceBaseline.value)
    }
    if (activeTab.value === 'codes' && detail.value) {
      detail.value.responseCodes = JSON.parse(savedResponseCodesBaseline.value)
      detail.value.vendorCodePath = savedVendorCodePathBaseline.value
    }
    if (activeTab.value === 'security') securityPolicy.value = JSON.parse(savedSecurityBaseline.value)
    return true
  } catch { return false }
}

async function selectSource(id: number) {
  if (guideBusy.value || testRunning.value || connectionSaving.value) return
  if (id === selectedSourceId.value && !sourceSwitching.value) return
  if (!await confirmDiscardUnsaved()) return
  selectedSourceId.value = id
  const source = sources.value.find(item => item.id === id)
  const providerId = source ? resolvedProviderId(source) : undefined
  if (providerId != null) providerExpansion.value[providerId] = true
  sourceExpansion.value[id] = true
  await loadInterfaces()
}

async function selectResourceInterface(sourceId: number, interfaceId: number) {
  if (guideBusy.value || testRunning.value || connectionSaving.value) return
  if (sourceId === selectedSourceId.value && interfaceId === selectedInterfaceId.value) return
  if (!await confirmDiscardUnsaved()) return
  sourceSwitching.value = true
  selectedSourceId.value = sourceId
  const source = sources.value.find(item => item.id === sourceId)
  const providerId = source ? resolvedProviderId(source) : undefined
  if (providerId != null) providerExpansion.value[providerId] = true
  sourceExpansion.value[sourceId] = true
  interfaces.value = activeMenu.value === 'resource'
    ? allResourceApis.value.filter(item => item.dataSourceId === sourceId).map(resourceApiProjection)
    : interfaceCache.get(sourceId) ?? allInterfaces.value.filter(item => item.dataSourceId === sourceId)
  selectedInterfaceId.value = interfaceId
  ;(activeMenu.value === 'resource' ? selectedResourceApiBySource : selectedInterfaceBySource).set(sourceId, interfaceId)
  try { await loadDetail() }
  finally { sourceSwitching.value = false }
}
async function selectServiceDevelopmentStage(stage: 'UPSTREAM' | 'ORCHESTRATION' | 'RELEASE') {
  if (guideBusy.value || testRunning.value || connectionSaving.value) return
  if (stage !== 'RELEASE' && serviceDevelopmentStage.value === stage) return
  if (!await confirmDiscardUnsaved()) return
  if (stage === 'RELEASE') {
    releaseInterfaceId.value = selectedInterfaceId.value
    navigate('releases')
    return
  }
  serviceDevelopmentStage.value = stage
  validation.value = null
  testResult.value = null
  testError.value = ''
  if (stage === 'UPSTREAM') {
    activeTab.value = 'overview'
    testMode.value = 'UPSTREAM'
    activeMenu.value = 'resource'
  } else {
    activeTab.value = 'request'
    if (testMode.value === 'UPSTREAM') testMode.value = 'PLATFORM'
    activeMenu.value = 'service-development'
    const ready = allInterfaces.value.find(item => item.id === selectedInterfaceId.value && item.upstreamStatus === 'READY')
      ?? allInterfaces.value.find(item => item.upstreamStatus === 'READY')
    if (ready) { selectedSourceId.value = ready.dataSourceId; selectedInterfaceId.value = ready.id; void loadInterfaces(false) }
  }
}
async function navigate(menu: string) {
  if (menu === activeMenu.value) return
  if (guideBusy.value || testRunning.value || connectionSaving.value) return
  if (['resource', 'service-development'].includes(activeMenu.value) && !await confirmDiscardUnsaved()) return
  const originatingResourceApiId = activeMenu.value === 'resource' ? selectedInterfaceId.value : undefined
  activeMenu.value = menu
  if (menu === 'resource') {
    upstreamSection.value = 0
    serviceDevelopmentStage.value = 'UPSTREAM'
    activeTab.value = 'overview'
    testMode.value = 'UPSTREAM'
    const selected = allResourceApis.value.find(item => item.id === selectedResourceApiBySource.get(selectedSourceId.value))
      ?? allResourceApis.value.find(item => item.dataSourceId === selectedSourceId.value) ?? allResourceApis.value[0]
    if (selected) { selectedSourceId.value = selected.dataSourceId; selectedInterfaceId.value = selected.id; void loadInterfaces(false) }
    else { selectedInterfaceId.value = 0; detail.value = null }
  }
  if (menu === 'service-development') {
    serviceDevelopmentStage.value = 'ORCHESTRATION'
    activeTab.value = 'request'
    if (testMode.value === 'UPSTREAM') testMode.value = 'PLATFORM'
    const ready = (originatingResourceApiId ? allInterfaces.value.find(item => item.resourceApiId === originatingResourceApiId) : undefined)
      ?? allInterfaces.value.find(item => item.id === selectedInterfaceBySource.get(selectedSourceId.value))
      ?? allInterfaces.value[0]
    if (ready) { selectedSourceId.value = ready.dataSourceId; selectedInterfaceId.value = ready.id; void loadInterfaces(false) }
    else { selectedInterfaceId.value = 0; detail.value = null }
  }
  if (menu === 'discovery') void loadVendorCatalog()
  if (menu === 'monitor') void refreshMonitor()
  if (menu === 'logs') void searchCallLogs()
  if (menu === 'providers' && selectedProviderId.value) void loadProviderAccounts()
  if (menu === 'approvals') void refreshApprovals()
  if (menu === 'releases') void loadReleaseManagement(releaseInterfaceId.value || selectedInterfaceId.value || allInterfaces.value[0]?.id)
  await nextTick()
  document.querySelector('.page-heading')?.scrollIntoView({ block: 'start' })
}
async function refreshCurrentWorkspace() {
  if (guideBusy.value || testRunning.value || connectionSaving.value) return
  if (['resource', 'service-development'].includes(activeMenu.value) && !await confirmDiscardUnsaved()) return
  try {
    if (activeMenu.value === 'discovery') await loadVendorCatalog()
    else if (activeMenu.value === 'monitor') await refreshMonitor()
    else if (activeMenu.value === 'logs') await searchCallLogs()
    else if (activeMenu.value === 'releases') await loadReleaseManagement()
    else await loadAll()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '刷新失败，请重试') }
}
function editReleaseRuntime() {
  if (!releaseDetail.value) return
  selectedSourceId.value = releaseDetail.value.summary.dataSourceId
  openSourceRuntimeEditor()
}
const releaseFilteredSources = computed(() => {
  const keyword = releaseSearch.value.trim().toLowerCase()
  if (!keyword) return sources.value
  return sources.value.filter(source => {
    const sourceText = `${source.sourceName}${source.sourceCode}${source.providerName}`.toLowerCase()
    const apiMatched = allInterfaces.value.some(item => item.dataSourceId === source.id
      && `${item.interfaceName}${item.interfaceCode}${item.servicePath}`.toLowerCase().includes(keyword))
    return sourceText.includes(keyword) || apiMatched
  })
})
function releaseSourceInterfaces(sourceId: number) {
  const rows = allInterfaces.value.filter(item => item.dataSourceId === sourceId)
  const keyword = releaseSearch.value.trim().toLowerCase()
  if (!keyword) return rows
  const source = sources.value.find(item => item.id === sourceId)
  const sourceMatched = source && `${source.sourceName}${source.sourceCode}${source.providerName}`.toLowerCase().includes(keyword)
  return sourceMatched ? rows : rows.filter(item => `${item.interfaceName}${item.interfaceCode}${item.servicePath}`.toLowerCase().includes(keyword))
}
function isReleaseSourceExpanded(sourceId: number) {
  return !!releaseSearch.value.trim() || (releaseSourceExpansion.value[sourceId] ?? sourceId === releaseInterfaceSourceId.value)
}
const releaseInterfaceSourceId = computed(() => allInterfaces.value.find(item => item.id === releaseInterfaceId.value)?.dataSourceId ?? 0)
function setAllReleaseSourcesExpanded(expanded: boolean) {
  releaseSourceExpansion.value = Object.fromEntries(sources.value.map(source => [source.id, expanded]))
}
async function loadProviderAccounts() { if (selectedProviderId.value) providerAccounts.value = await api.providerAccounts(selectedProviderId.value) }
async function refreshApprovals() { approvals.value = await api.approvals() }
function versionStatusText(item: InterfaceVersionHistory) {
  return ({ ACTIVE: '当前线上', OFFLINE: '已下线', SUPERSEDED: '历史已发布', PUBLISHED_LEGACY: '历史已发布 · 记录待补全', PENDING: '待审批', APPROVED: '已审批', REJECTED: '已驳回', DRAFT: '当前草稿' } as Record<string, string>)[item.status] ?? item.status
}
function versionTagType(item: InterfaceVersionHistory) {
  if (item.status === 'ACTIVE') return 'success'
  if (item.status === 'OFFLINE') return 'danger'
  if (item.status === 'PENDING' || item.status === 'PUBLISHED_LEGACY') return 'warning'
  if (item.status === 'REJECTED') return 'danger'
  return 'info'
}
function showVersionDetail(item: InterfaceVersionHistory) { selectedVersion.value = item; versionDialog.value = true }
async function openPublishedDetail(interfaceId: number) {
  publishedDetailDrawer.value = true
  publishedDetailLoading.value = true
  try { publishedInterfaceDetail.value = await api.detail(interfaceId) }
  catch (error) { publishedInterfaceDetail.value = null; ElMessage.error(error instanceof Error ? error.message : '接口详情加载失败') }
  finally { publishedDetailLoading.value = false }
}
function versionRowClass(row: InterfaceVersionHistory) { return row.version === highlightedVersion.value ? 'version-row-highlight' : '' }
async function openVersionHistory(version?: number) {
  releaseInterfaceId.value = selectedInterfaceId.value
  highlightedVersion.value = version ?? null
  activeMenu.value = 'releases'
  await loadReleaseManagement(releaseInterfaceId.value)
  highlightedVersion.value = version ?? null
}
async function loadReleaseManagement(interfaceId = releaseInterfaceId.value) {
  if (!interfaceId) return
  const requestSequence = ++releaseRequestSequence
  releaseInterfaceId.value = interfaceId
  releaseLoading.value = true
  try {
    const [nextDetail, nextVersions] = await Promise.all([api.detail(interfaceId), api.interfaceVersions(interfaceId)])
    if (requestSequence !== releaseRequestSequence || interfaceId !== releaseInterfaceId.value) return
    releaseDetail.value = nextDetail
    releaseVersions.value = nextVersions
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '发布档案加载失败') }
  finally { if (requestSequence === releaseRequestSequence) releaseLoading.value = false }
}
async function selectReleaseInterface(interfaceId: number) {
  if (interfaceId === releaseInterfaceId.value && releaseDetail.value) return
  const sourceId = allInterfaces.value.find(item => item.id === interfaceId)?.dataSourceId
  if (sourceId) releaseSourceExpansion.value[sourceId] = true
  highlightedVersion.value = null
  await loadReleaseManagement(interfaceId)
}
async function submitReleaseFromManagement() {
  if (!releaseDetail.value || releaseDetail.value.summary.status === 'PENDING_APPROVAL') return
  try {
    const check = await api.validate(releaseDetail.value.summary.id)
    if (!check.valid) throw new Error(check.errors.join('；') || '发布前校验未通过')
    await ElMessageBox.confirm(`确认直接上线 ${releaseDetail.value.summary.interfaceCode} V${releaseDetail.value.summary.currentVersion}？上线后即可配置调用授权。`, '确认服务上线', { type: 'warning', confirmButtonText: '确认上线', cancelButtonText: '取消' })
    await api.directPublish(releaseDetail.value.summary.id, releaseNoteManagement.value)
    ElMessage.success(`V${releaseDetail.value.summary.currentVersion} 已上线，可配置 API 访问授权`)
    await Promise.all([loadReleaseManagement(releaseDetail.value.summary.id), loadAll()])
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '服务上线失败') }
}
async function offlineRelease() {
  if (!releaseDetail.value) return
  try {
    await ElMessageBox.confirm('下线后线上调用将停止，历史版本和链路记录仍会保留。确认下线吗？', '确认下线接口', { type: 'warning', confirmButtonText: '确认下线', cancelButtonText: '取消' })
    await api.offlineInterface(releaseDetail.value.summary.id)
    ElMessage.success('接口已下线，线上执行计划已撤销')
    await Promise.all([loadReleaseManagement(releaseDetail.value.summary.id), loadAll()])
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '下线失败') }
}
async function onlineRelease() {
  if (!releaseDetail.value?.summary.publishedVersion) return
  const summary = releaseDetail.value.summary
  try {
    await ElMessageBox.confirm(`将恢复最近一次已发布的 V${summary.publishedVersion} 执行计划和 Gateway 路由。${summary.currentVersion !== summary.publishedVersion ? `V${summary.currentVersion} 草稿会继续保留，不会进入线上流量。` : ''}现有有效授权将恢复调用，确认重新上线吗？`, '确认重新上线', { type: 'warning', confirmButtonText: '恢复线上服务', cancelButtonText: '取消' })
    await api.onlineInterface(summary.id)
    ElMessage.success(`线上 V${summary.publishedVersion} 已恢复`)
    await Promise.all([loadReleaseManagement(summary.id), loadAll()])
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '重新上线失败') }
}
async function archiveRelease() {
  if (!releaseDetail.value) return
  const target = { ...releaseDetail.value.summary }
  try {
    await ElMessageBox.confirm('归档后资源目录不再展示该 API，但版本、审批和链路记录仍会保留。确认归档删除吗？', '确认归档删除', { type: 'warning', confirmButtonText: '归档删除', cancelButtonText: '取消' })
    await api.deleteInterface(target.id)
    const nextId = removeInterfaceFromLocalState(target)
    ElMessage.success('API 已归档，历史审计数据已保留')
    if (nextId) await loadReleaseManagement(nextId)
    else { releaseDetail.value = null; releaseVersions.value = []; releaseInterfaceId.value = 0 }
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '归档失败') }
}

function removeInterfaceFromLocalState(target: InterfaceSummary) {
  const sourceRows = allInterfaces.value.filter(item => item.dataSourceId === target.dataSourceId && item.id !== target.id)
  allInterfaces.value = allInterfaces.value.filter(item => item.id !== target.id)
  interfaceCache.set(target.dataSourceId, sourceRows)
  if (selectedSourceId.value === target.dataSourceId) interfaces.value = sourceRows
  sources.value = sources.value.map(source => source.id === target.dataSourceId
    ? { ...source, interfaceCount: Math.max(0, source.interfaceCount - 1) } : source)
  dashboard.value.interfaceCount = Math.max(0, dashboard.value.interfaceCount - 1)
  if (target.status === 'DRAFT') dashboard.value.draftCount = Math.max(0, dashboard.value.draftCount - 1)
  if (target.status === 'PUBLISHED') dashboard.value.publishedCount = Math.max(0, dashboard.value.publishedCount - 1)
  detailCache.delete(target.id)
  selectedInterfaceBySource.delete(target.dataSourceId)
  const next = sourceRows[0] ?? allInterfaces.value[0]
  return next?.id ?? 0
}
function openProviderEditor(provider = selectedProvider.value) { if (!provider) return; providerForm.value = { ...provider }; providerDialog.value = true }
function openNewProvider() {
  providerForm.value = {
    id: 0, providerCode: '', providerName: '', providerType: 'DATA_VENDOR',
    accessDate: '', messageEncoding: 'UTF-8', chargeRequired: false,
    encryptionRequired: false, loginRequired: false, status: 'ACTIVE'
  }
  providerDialog.value = true
}
async function saveProvider() {
  const form = providerForm.value
  if (!form.providerCode.trim() || !form.providerName.trim()) {
    ElMessage.warning('请填写提供商代码和提供商名称')
    return
  }
  providerSaving.value = true
  try {
    const isNew = form.id === 0
    const saved = isNew ? await api.createProvider(form) : await api.updateProvider(form.id, form)
    if (isNew) {
      providers.value = [...providers.value, saved]
      dashboard.value.providerCount += 1
    } else {
      providers.value = providers.value.map(item => item.id === saved.id ? saved : item)
    }
    selectedProviderId.value = saved.id
    providerAccounts.value = []
    providerDialog.value = false
    ElMessage.success(isNew ? '资源提供商已新增' : '资源提供商资料已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资源提供商保存失败')
  } finally {
    providerSaving.value = false
  }
}
function openAccountDialog() { accountForm.value = { username: '', displayName: '', mobile: '', email: '', roleCode: 'PROVIDER_ADMIN', permissions: ['DATASOURCE_VIEW', 'API_VIEW'], status: 'ACTIVE' }; accountDialog.value = true }
async function saveAccount() { if (!selectedProviderId.value) return; await api.createProviderAccount(selectedProviderId.value, accountForm.value); accountDialog.value = false; await loadProviderAccounts(); ElMessage.success('资源提供商账号已创建') }
function logout() { loggedIn.value = false; user.value = null; activeMenu.value = 'overview' }

function openSourceWizard() {
  sourceStep.value = 0
  sourceError.value = ''
  sourceForm.value = { providerId: providers.value[0]?.id ?? 0, sourceCode: '', sourceName: '', applicationName: '', runtimeUrl: '', protocolType: 'HTTP_JSON', endpoint: '', defaultTimeoutMs: 3000 }
  sourceDialog.value = true
}
function advanceSourceWizard() {
  if (!sourceForm.value.providerId || !sourceForm.value.sourceName.trim() || !sourceForm.value.sourceCode.trim()) {
    sourceError.value = '请先选择资源提供商，并填写数据源名称和编码。'
    return
  }
  if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(sourceForm.value.sourceCode.trim())) {
    sourceError.value = '数据源编码以字母开头，可包含字母、数字、下划线和连字符。'
    return
  }
  sourceError.value = ''
  sourceStep.value = 1
}
async function createSource() {
  if (sourceSaving.value) return
  if (sourceForm.value.protocolType !== 'LOCAL_SQL' && !isHttpEndpoint(sourceForm.value.endpoint.trim())) {
    sourceError.value = '请填写有效的资源提供商基础地址，以 http:// 或 https:// 开头。'
    return
  }
  if (sourceForm.value.runtimeUrl.trim() && !isHttpEndpoint(sourceForm.value.runtimeUrl.trim())) {
    sourceError.value = '处理应用运行地址应以 http:// 或 https:// 开头。'
    return
  }
  sourceError.value = ''
  sourceSaving.value = true
  try {
  const sourceCode = sourceForm.value.sourceCode.trim().toLowerCase().replace(/_/g, '-')
  const created = await api.createDataSource({ ...sourceForm.value,
    sourceCode: sourceForm.value.sourceCode.trim(), sourceName: sourceForm.value.sourceName.trim(), endpoint: sourceForm.value.endpoint.trim(),
    applicationName: sourceForm.value.applicationName.trim() || `jsnx-edsp-data-source-${sourceCode}`,
    runtimeUrl: sourceForm.value.runtimeUrl.trim() || null, environment: 'TEST', instanceCount: 1 })
  sourceDialog.value = false
  selectedSourceId.value = created.id
  await loadAll()
  providerExpansion.value[sourceForm.value.providerId] = true
  sourceExpansion.value[created.id] = true
  ElMessage.success(`数据源 ${created.sourceName} 已创建，可继续开发 API`)
  openApiDialog()
  } catch (error) {
    sourceError.value = error instanceof Error ? error.message : '数据源创建失败，请重试'
  } finally { sourceSaving.value = false }
}

function openSourceRuntimeEditor() {
  if (!selectedSource.value) return
  sourceRuntimeForm.value = {
    applicationName: selectedSource.value.applicationName,
    runtimeUrl: selectedSource.value.runtimeUrl ?? ''
  }
  sourceRuntimeDialog.value = true
}

function openSourceSettingsEditor() {
  if (!selectedSource.value) return
  sourceSettingsError.value = ''
  sourceSettingsForm.value = {
    providerId: selectedSource.value.providerId ?? resolvedProviderId(selectedSource.value),
    sourceCode: selectedSource.value.sourceCode,
    sourceName: selectedSource.value.sourceName,
    protocolType: selectedSource.value.protocolType,
    endpoint: selectedSource.value.endpointUrl ?? '',
    defaultTimeoutMs: selectedSource.value.defaultTimeoutMs ?? 3000
  }
  sourceSettingsDialog.value = true
}

async function saveSourceSettings() {
  if (!selectedSource.value || sourceSettingsSaving.value) return
  const endpoint = sourceSettingsForm.value.endpoint.trim()
  if (!sourceSettingsForm.value.providerId || !sourceSettingsForm.value.sourceName.trim()) {
    sourceSettingsError.value = '请选择资源提供商并填写数据源名称。'
    return
  }
  if (sourceSettingsForm.value.protocolType !== 'LOCAL_SQL' && !endpoint) {
    sourceSettingsError.value = '请填写数据源基础地址。'
    return
  }
  if (sourceSettingsForm.value.protocolType !== 'LOCAL_SQL' && !isHttpEndpoint(endpoint)) {
    sourceSettingsError.value = '数据源基础地址应以 http:// 或 https:// 开头。'
    return
  }
  sourceSettingsError.value = ''
  sourceSettingsSaving.value = true
  try {
    const updated = await api.saveDataSourceSettings(selectedSource.value.id, {
      providerId: sourceSettingsForm.value.providerId,
      sourceName: sourceSettingsForm.value.sourceName.trim(),
      protocolType: sourceSettingsForm.value.protocolType,
      endpoint: sourceSettingsForm.value.protocolType === 'LOCAL_SQL' ? '' : endpoint,
      defaultTimeoutMs: sourceSettingsForm.value.defaultTimeoutMs
    })
    sources.value = sources.value.map(item => item.id === updated.id ? updated : item)
    sourceSettingsDialog.value = false
    lastUpstreamValidation.value = null
    lastOrchestrationValidation.value = null
    providerExpansion.value[updated.providerId] = true
    ElMessage.success('数据源接入信息已更新；继承配置的草稿和联调立即使用新值')
  } catch (error) {
    sourceSettingsError.value = error instanceof Error ? error.message : '数据源接入信息保存失败'
  } finally { sourceSettingsSaving.value = false }
}

async function saveSourceRuntime() {
  if (!selectedSource.value || sourceRuntimeSaving.value) return
  if (!sourceRuntimeForm.value.applicationName.trim() || !sourceRuntimeForm.value.runtimeUrl.trim()) {
    ElMessage.warning('请完整填写处理应用标识和运行地址')
    return
  }
  sourceRuntimeSaving.value = true
  try {
    const updated = await api.saveDataSourceRuntime(selectedSource.value.id, {
      applicationName: sourceRuntimeForm.value.applicationName.trim(),
      runtimeUrl: sourceRuntimeForm.value.runtimeUrl.trim()
    })
    const index = sources.value.findIndex(item => item.id === updated.id)
    if (index >= 0) sources.value[index] = updated
    sourceRuntimeDialog.value = false
    ElMessage.success('数据源应用运行路由已更新，Gateway 路由表将立即刷新')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '运行路由保存失败')
  } finally { sourceRuntimeSaving.value = false }
}

async function deleteCurrentInterface() {
  if (!detail.value || deletingApi.value) return
  const target = { ...detail.value.summary }
  if (target.status !== 'DRAFT' || target.publishedVersion != null) {
    ElMessage.warning(deleteApiRestriction.value)
    return
  }
  deletingApi.value = true
  try {
    await ElMessageBox.confirm(
      `确认删除 API「${target.interfaceName}」吗？删除后将从资源目录隐藏，且不能继续编辑或发布。`,
      '删除 API',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
    await api.deleteInterface(target.id)
    const nextId = removeInterfaceFromLocalState(target)
    ElMessage.success('API 已删除')
    if (nextId) {
      const next = allInterfaces.value.find(item => item.id === nextId)
      if (next) {
        selectedSourceId.value = next.dataSourceId
        selectedInterfaceId.value = next.id
        selectedInterfaceBySource.set(next.dataSourceId, next.id)
        interfaces.value = interfaceCache.get(next.dataSourceId) ?? []
        await loadDetail()
      }
    } else {
      detail.value = null
      selectedInterfaceId.value = 0
    }
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : 'API 删除失败')
    }
  } finally { deletingApi.value = false }
}

async function copyApiValue(value: string) {
  try { await navigator.clipboard.writeText(value); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败，请选择文本后手动复制') }
}

function openApiDialog() {
  const source = selectedSource.value ?? sources.value[0]
  initializeApiDialog(source)
}

function initializeApiDialog(source: DataSourceSummary | undefined) {
  const sequence = String(allResourceApis.value.filter(item => item.dataSourceId === source?.id).length + 1).padStart(2, '0')
  const sourceCode = source?.sourceCode ?? 'DATA'
  const pathCode = sourceCode.toLowerCase().replace(/_/g, '-')
  apiForm.value = {
    dataSourceId: source?.id ?? 1,
    interfaceCode: `${sourceCode}_API_${sequence}`,
    interfaceName: `${source?.sourceName ?? '外部数据'}查询接口${sequence}`,
    servicePath: `/api/v1/${pathCode}/query-${sequence}`,
    httpMethod: 'POST', configScope: 'INHERIT', upstreamPath: '', upstreamUrl: '',
    timeoutMode: 'INHERIT', timeoutMs: source?.defaultTimeoutMs ?? 3000, generateMock: false, upstreamRequestExample: null
  }
  vendorRequestExample.value = null
  vendorResponseExample.value = null
  apiDialog.value = true
}

function openApiDialogForSource(sourceId: number) {
  const source = sources.value.find(item => item.id === sourceId)
  if (!source) return
  initializeApiDialog(source)
}

async function runCurrentPrimaryAction() {
  if (guideBusy.value || testRunning.value || connectionSaving.value) return false
  guideBusy.value = true
  guideError.value = ''
  try {
    if (activeTab.value === 'overview') return await saveConnectionConfig()
    if (activeTab.value === 'request') return await saveMappings()
    if (activeTab.value === 'codes') return await saveResponseCodes()
    if (activeTab.value === 'security') return await saveSecurityPolicy()
    if (activeTab.value === 'test') return await runTest()
    if (activeTab.value === 'release') return await publishConfig()
    return false
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      guideError.value = error instanceof Error ? error.message : '操作未完成，请检查配置后重试'
      ElMessage.error(guideError.value)
    }
    return false
  } finally { guideBusy.value = false }
}
async function validateUpstreamCapability() {
  if (hasUnsavedChanges.value && !await saveConnectionConfig()) return false
  if (activeMenu.value === 'resource') {
    if (!detail.value || testRunning.value) return false
    testRunning.value = true
    testError.value = ''
    try {
      const result = await api.verifyResourceApi(detail.value.summary.id)
      testResult.value = result
      testSucceeded.value = true
      selectedTraceKey.value = result.traceNodes[0]?.key ?? 'DATA_SOURCE'
      lastUpstreamValidation.value = { interfaceId: detail.value.summary.id, connectionFingerprint: upstreamValidationFingerprint.value }
      const index = allResourceApis.value.findIndex(item => item.id === detail.value?.summary.id)
      if (index >= 0) allResourceApis.value[index] = { ...allResourceApis.value[index], verifiedAt: new Date().toISOString(), status: 'READY' }
      ElMessage.success('上游资源接口验证成功')
      return true
    } catch (error) {
      testError.value = error instanceof Error ? error.message : '上游资源接口验证失败'
      ElMessage.error(testError.value)
      return false
    } finally { testRunning.value = false }
  }
  testMode.value = 'UPSTREAM'
  return await runTest()
}
async function continueGuide() {
  if (!nextGuideStep.value || guideBusy.value || testRunning.value || connectionSaving.value) return
  const interfaceId = selectedInterfaceId.value
  const stepKey = activeTab.value
  const nextKey = nextGuideStep.value.key
  guideError.value = ''
  if (stepKey === 'request' && detail.value?.mappings.some(row => !row.sourcePath.trim() || !row.targetPath.trim())) {
    guideError.value = '请补全已添加的映射行来源路径和目标路径；如果无需转换，请删除映射行并保持自动透传。'
    return
  }
  if (hasUnsavedChanges.value && !await runCurrentPrimaryAction()) return
  if (selectedInterfaceId.value !== interfaceId || activeTab.value !== stepKey) return
  if (stepKey === 'overview' && !isHttpEndpoint(previewUpstreamUrl.value)) {
    guideError.value = '请先填写有效的 HTTP / HTTPS 上游地址，再继续配置字段映射。'
    return
  }
  if (stepKey === 'request' && !apiGuideSteps.value[1].done) {
    guideError.value = '请补全已添加的映射行来源路径和目标路径；如果无需转换，请删除映射行并保持自动透传。'
    return
  }
  if (stepKey === 'codes' && !apiGuideSteps.value[2].done) {
    guideError.value = '请填写完整的上游原始码与平台标准码，再继续安全策略配置。'
    return
  }
  if (nextKey === 'release') {
    releaseInterfaceId.value = selectedInterfaceId.value
    navigate('releases')
    return
  }
  await openGuideStep(nextKey)
}
async function createInterface() {
  if (!apiForm.value.interfaceName.trim() || !apiForm.value.interfaceCode.trim()) {
    ElMessage.warning('请完整填写资源 API 名称和资源 API 编码')
    return
  }
  apiCreating.value = true
  let created: ResourceApiSummary
  try {
    created = await api.createResourceApi({
      dataSourceId: apiForm.value.dataSourceId,
      resourceApiCode: apiForm.value.interfaceCode,
      resourceApiName: apiForm.value.interfaceName,
      httpMethod: apiForm.value.httpMethod,
      configScope: apiForm.value.configScope,
      upstreamPath: apiForm.value.upstreamPath,
      upstreamUrl: apiForm.value.upstreamUrl,
      generateMock: apiForm.value.generateMock,
      timeoutMs: apiForm.value.timeoutMode === 'OVERRIDE' ? apiForm.value.timeoutMs : null,
      requestExample: vendorRequestExample.value,
      responseExample: vendorResponseExample.value
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'API 草稿创建失败')
    apiCreating.value = false
    return
  }
  apiCreating.value = false
  apiDialog.value = false
  selectedSourceId.value = created.dataSourceId
  selectedInterfaceId.value = created.id
  activeMenu.value = 'resource'
  serviceDevelopmentStage.value = 'UPSTREAM'
  activeTab.value = 'overview'
  ElMessage.success(`资源 API 草稿 ${created.resourceApiCode} 已创建，请完善接口契约并保存`)
  try { await loadAll() }
  catch { ElMessage.warning('草稿已创建，列表刷新失败，请点击页面刷新按钮') }
}

async function saveConnectionConfig() {
  if (!detail.value || connectionSaving.value) return false
  if (!hasUnsavedChanges.value && !(activeMenu.value === 'resource' && detail.value.summary.upstreamStatus !== 'READY')) {
    ElMessage.success('当前上游配置已保存，无需重复保存')
    return true
  }
  if (!isHttpEndpoint(previewUpstreamUrl.value)) {
    guideError.value = '请填写有效的 HTTP / HTTPS 上游地址。'
    ElMessage.warning(guideError.value)
    return false
  }
  connectionSaving.value = true
  try {
    let upstreamRequestExample: Record<string, unknown>
    let upstreamResponseExample: Record<string, unknown>
    try {
      upstreamRequestExample = JSON.parse(upstreamRequestText.value)
      upstreamResponseExample = JSON.parse(responseText.value)
    } catch {
      guideError.value = '上游请求或响应样例不是有效 JSON，请检查后保存。'
      ElMessage.warning(guideError.value)
      return false
    }
    if (activeMenu.value === 'resource') {
      const resourceSaved = await api.saveResourceApi(detail.value.summary.id, {
        configScope: connectionForm.value.configScope,
        upstreamPath: connectionForm.value.upstreamPath,
        upstreamUrl: connectionForm.value.upstreamUrl,
        timeoutMs: connectionForm.value.timeoutMode === 'OVERRIDE' ? connectionForm.value.timeoutMs : null,
        requestExample: upstreamRequestExample,
        responseExample: upstreamResponseExample
      })
      detail.value = adaptResourceDetail(resourceSaved)
      const resourceIndex = allResourceApis.value.findIndex(item => item.id === resourceSaved.summary.id)
      if (resourceIndex >= 0) allResourceApis.value[resourceIndex] = resourceSaved.summary
      connectionForm.value.upstreamUrl = resourceSaved.summary.upstreamUrl
      savedConnectionBaseline.value = JSON.stringify(connectionForm.value)
      savedUpstreamRequestBaseline.value = JSON.stringify(upstreamRequestExample, null, 2)
      savedUpstreamResponseBaseline.value = JSON.stringify(upstreamResponseExample, null, 2)
      ElMessage.success('资源 API 已保存，可验证上游接口或创建平台标准服务')
      await loadInterfaces(false)
      return true
    }
    const saved = await api.saveConnectionConfig(detail.value.summary.id, {
      configScope: connectionForm.value.configScope,
      upstreamPath: connectionForm.value.upstreamPath,
      upstreamUrl: connectionForm.value.upstreamUrl,
      timeoutMs: connectionForm.value.timeoutMode === 'OVERRIDE' ? connectionForm.value.timeoutMs : null,
      upstreamRequestExample,
      upstreamResponseExample
    })
    detailCache.delete(detail.value.summary.id)
    interfaceCache.delete(selectedSourceId.value)
    detail.value = saved
    const summaryIndex = allInterfaces.value.findIndex(item => item.id === saved.summary.id)
    if (summaryIndex >= 0) allInterfaces.value[summaryIndex] = saved.summary
    connectionForm.value.upstreamUrl = saved.summary.upstreamUrl
    savedConnectionBaseline.value = JSON.stringify(connectionForm.value)
    savedUpstreamRequestBaseline.value = JSON.stringify(upstreamRequestExample, null, 2)
    savedUpstreamResponseBaseline.value = JSON.stringify(upstreamResponseExample, null, 2)
    ElMessage.success('上游请求、响应和连接配置已保存，可进入标准服务开发')
    await loadInterfaces(false)
    return true
  } catch (error) {
    guideError.value = error instanceof Error ? error.message : '连接配置保存失败'
    ElMessage.error(guideError.value)
    return false
  } finally { connectionSaving.value = false }
}

function openPlatformServiceDialog() {
  if (!selectedResourceApi.value || selectedResourceApi.value.status !== 'READY') {
    ElMessage.warning('请先保存当前资源 API，再创建平台标准服务')
    return
  }
  const resource = selectedResourceApi.value
  const sequence = String(resource.platformServiceCount + 1).padStart(2, '0')
  platformServiceForm.value = {
    resourceApiId: resource.id,
    serviceCode: `${resource.resourceApiCode}_SERVICE_${sequence}`,
    serviceName: resource.platformServiceCount ? `${resource.resourceApiName}标准服务${sequence}` : resource.resourceApiName.replace(/接口$/, '') + '标准服务',
    servicePath: `/api/v1/${resource.resourceApiCode.toLowerCase().replace(/_/g, '-')}/${sequence}`,
    httpMethod: 'POST'
  }
  platformServiceDialog.value = true
}

async function createPlatformService() {
  const form = platformServiceForm.value
  if (!form.serviceCode.trim() || !form.serviceName.trim() || !form.servicePath.trim()) {
    ElMessage.warning('请完整填写平台服务名称、编码和标准路径')
    return
  }
  platformServiceCreating.value = true
  try {
    const created = await api.createPlatformService(form)
    platformServiceDialog.value = false
    await loadAll()
    selectedSourceId.value = created.dataSourceId
    selectedInterfaceId.value = created.id
    selectedInterfaceBySource.set(created.dataSourceId, created.id)
    activeMenu.value = 'service-development'
    serviceDevelopmentStage.value = 'ORCHESTRATION'
    activeTab.value = 'request'
    testMode.value = 'PLATFORM'
    await loadInterfaces(false)
    ElMessage.success(`平台标准服务 ${created.interfaceCode} 已创建，已继承资源接口契约`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '平台标准服务创建失败')
  } finally { platformServiceCreating.value = false }
}

async function saveMappings() {
  if (!detail.value) return false
  if (detail.value.summary.id !== selectedInterfaceId.value) {
    ElMessage.warning('接口已切换，正在重新加载正确配置')
    await loadDetail()
    return false
  }
  let requestExample: Record<string, unknown>
  let upstreamResponseExample: Record<string, unknown>
  try {
    requestExample = syncRequestExampleForMappingEdits(
      JSON.parse(requestText.value) as Record<string, unknown>, detail.value.mappings)
    upstreamResponseExample = JSON.parse(responseText.value) as Record<string, unknown>
  } catch {
    guideError.value = '行内请求样例或上游响应样例不是有效的 JSON，请检查后再保存。'
    ElMessage.error(guideError.value)
    return false
  }
  const saved = await api.saveMappings(selectedInterfaceId.value, detail.value.mappings,
    standardServiceForm.value.servicePath, standardServiceForm.value.httpMethod,
    requestExample)
  detailCache.delete(selectedInterfaceId.value)
  detail.value.mappings = saved
  detail.value.requestExample = requestExample
  savedMappingsBaseline.value = structuredClone(saved)
  requestText.value = JSON.stringify(requestExample, null, 2)
  detail.value.upstreamResponseExample = upstreamResponseExample
  responseText.value = JSON.stringify(upstreamResponseExample, null, 2)
  savedRequestBaseline.value = requestText.value
  savedResponseBaseline.value = responseText.value
  detail.value.summary.servicePath = standardServiceForm.value.servicePath
  detail.value.summary.httpMethod = standardServiceForm.value.httpMethod
  savedStandardServiceBaseline.value = JSON.stringify(standardServiceForm.value)
  markDraftSaved()
  validation.value = null
  ElMessage.success('映射配置已保存，请重新验证当前草稿')
  return true
}
function addMapping() {
  if (!detail.value) return
  detail.value.mappings.push({ direction: mappingDirection.value, sourcePath: '', targetPath: '', transformType: 'DIRECT', transformConfig: {}, defaultValue: '', required: false, sortOrder: filteredMappings.value.length * 10 + 10 })
  nextTick(() => ElMessage.info('已新增映射行'))
}
function startMappingConfiguration() {
  addMapping()
}
function deleteMapping(row: FieldMapping) { if (detail.value) detail.value.mappings = detail.value.mappings.filter(item => item !== row) }

function flattenLeafPaths(value: unknown, prefix = ''): string[] {
  if (Array.isArray(value)) return prefix ? [prefix] : []
  if (value !== null && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
      flattenLeafPaths(child, prefix ? `${prefix}.${key}` : key)
    )
  }
  return prefix ? [prefix] : []
}

function generateMappingsFromSample() {
  if (!detail.value) return
  try {
    const sample = JSON.parse(mappingDirection.value === 'REQUEST' ? requestText.value : responseText.value)
    const paths = flattenLeafPaths(sample)
    if (!paths.length) return ElMessage.warning('样例报文中没有可生成的字段')
    const existing = new Set(detail.value.mappings
      .filter(item => item.direction === mappingDirection.value)
      .map(item => item.sourcePath))
    const additions = paths.filter(path => !existing.has(path)).map((path, index) => ({
      direction: mappingDirection.value,
      sourcePath: path,
      targetPath: path,
      transformType: 'DIRECT' as const,
      transformConfig: {},
      defaultValue: '',
      required: mappingDirection.value === 'REQUEST',
      sortOrder: (filteredMappings.value.length + index + 1) * 10
    }))
    detail.value.mappings.push(...additions)
    additions.length
      ? ElMessage.success(`已从样例生成 ${additions.length} 条映射，重复字段已跳过`)
      : ElMessage.info('样例中的字段均已存在映射')
  } catch { ElMessage.error('样例报文不是有效的 JSON') }
}

async function saveResponseCodes() {
  if (!detail.value) return false
  detail.value.vendorCodePath = detail.value.vendorCodePath?.trim() || 'result.code'
  detail.value.responseCodes = await api.saveResponseCodes(selectedInterfaceId.value, detail.value.vendorCodePath, detail.value.responseCodes)
  savedResponseCodesBaseline.value = JSON.stringify(detail.value.responseCodes)
  savedVendorCodePathBaseline.value = detail.value.vendorCodePath
  detailCache.delete(selectedInterfaceId.value)
  markDraftSaved()
  validation.value = null
  ElMessage.success('统一返回码配置已保存')
  return true
}
function addResponseCode() {
  detail.value?.responseCodes.push({ vendorCode: '', standardCode: 'EXT-SUCCESS', resultStatus: 'HIT', retryable: false, billable: true })
}

async function validateConfig() {
  if (hasUnsavedChanges.value) { ElMessage.warning('请先保存当前修改，再校验已保存的草稿'); return }
  const interfaceId = selectedInterfaceId.value
  const version = detail.value?.summary.currentVersion
  try {
    const result = await api.validate(interfaceId)
    if (interfaceId !== selectedInterfaceId.value || version !== detail.value?.summary.currentVersion) return
    validation.value = result
    result.valid ? ElMessage.success('配置校验通过') : ElMessage.error('配置校验未通过')
  } catch (error) {
    validation.value = null
    guideError.value = error instanceof Error ? error.message : '配置校验失败，请稍后重试'
    ElMessage.error(guideError.value)
  }
}
async function runTest() {
  if (!detail.value || testRunning.value) return false
  if (detail.value.summary.id !== selectedInterfaceId.value) {
    ElMessage.warning('接口已切换，正在重新加载正确配置，请稍后重试')
    await loadDetail()
    return
  }
  const testingInterfaceId = selectedInterfaceId.value
  const testingVersion = detail.value.summary.currentVersion
  const testingMode = testMode.value
  if (testingMode === 'PLATFORM' && !draftEditorMatchesSaved()) {
    ElMessage.warning('请先保存标准请求、映射和安全策略，再执行草稿链路测试')
    return false
  }
  if (testingMode === 'UPSTREAM') lastUpstreamValidation.value = null
  if (testingMode === 'PLATFORM') lastOrchestrationValidation.value = null
  if (testingMode === 'E2E') lastE2ETest.value = null
  const mappingSnapshot = JSON.parse(JSON.stringify(detail.value.mappings)) as FieldMapping[]
  const traceStartedAt = Date.now()
  const minimumTraceDurationMs = Math.max(1100, activeTraceDefinitions.value.length * 300)
  let phaseTimer: ReturnType<typeof setInterval> | undefined
  const controller = new AbortController()
  testAbortController.value = controller
  try {
    testRunning.value = true
    testSucceeded.value = false
    testError.value = ''
    testResult.value = null
    chainPhase.value = 0
    testElapsedSeconds.value = 0
    phaseTimer = setInterval(() => {
      testElapsedSeconds.value = Math.max(1, Math.floor((Date.now() - traceStartedAt) / 1000))
      if (chainPhase.value < activeTraceDefinitions.value.length - 1) chainPhase.value += 1
    }, 320)
    const requestPayload = JSON.parse(testMode.value === 'UPSTREAM' ? upstreamRequestText.value : requestText.value)
    if (testMode.value !== 'UPSTREAM') {
      const missingRequiredSources = mappingSnapshot
        .filter(mapping => mapping.direction === 'REQUEST' && mapping.required)
        .filter(mapping => readObjectPath(requestPayload, mapping.sourcePath) === undefined)
        .map(mapping => mapping.sourcePath)
      if (missingRequiredSources.length) {
        throw new Error(`联调请求缺少映射必填来源字段：${missingRequiredSources.join('、')}。请修改请求样例后重新保存草稿。`)
      }
    }
    if (testMode.value === 'E2E') {
      // The release workspace and the service workspace have independent caches.
      // Refresh the summary before an E2E run so a just-published version is not
      // mistaken for a draft after navigating between workspaces.
      const latestDetail = await api.detail(testingInterfaceId)
      if (testingInterfaceId !== selectedInterfaceId.value) return
      detail.value.summary = latestDetail.summary
      if (latestDetail.summary.publishedVersion == null
        || ['OFFLINE', 'DELETED'].includes(latestDetail.summary.status)) {
        throw new Error('当前接口没有可验收的线上版本。线上入口验收只测试已上线版本；草稿可按需执行“草稿链路测试”。')
      }
    }
    const e2eClient = testingMode === 'E2E' ? (() => {
      const grant = validTestAuthorizations.value.find(item => item.interfaceId === testingInterfaceId)
      return grant ? activeClients.value.find(item => item.id === grant.clientId) : undefined
    })() : undefined
    if (testingMode === 'E2E' && !e2eClient) {
      throw new Error('当前 API 没有今日生效的 TEST 授权。请在“API 访问授权”中为该 API 配置 TEST 环境，并确保有效期包含今日。')
    }
    const traceId = `EDSP-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
    const result = testMode.value === 'E2E'
      ? await api.esbDiagnostic(e2eClient!.clientCode, buildEsbXml(detail.value.summary.interfaceCode, traceId, e2eClient!.clientCode, requestPayload), traceId,
          { timeoutMs: requestDeadlineMs(previewTimeoutMs.value), signal: controller.signal }).then(diagnostic => ({
            upstreamRequest: diagnostic.parsedRequest,
            upstreamResponse: {},
            standardResponse: diagnostic.standardResponse,
            steps: diagnostic.nodes.map(node => ({ name: node.name, status: node.status, detail: node.detail, durationMs: node.durationMs })),
            traceNodes: diagnostic.nodes
          } as TestResult))
      : await api.test(testingInterfaceId, {
          request: requestPayload,
          callUpstream: true,
          mappings: mappingSnapshot,
          testMode: testMode.value
        }, { timeoutMs: requestDeadlineMs(previewTimeoutMs.value), signal: controller.signal })
    if (testingInterfaceId !== selectedInterfaceId.value) return
    testResult.value = result
    selectedTraceKey.value = result.traceNodes[0]?.key ?? (testMode.value === 'E2E' ? 'ESB_QUEUE' : 'DOWNSTREAM')
    const remainingTraceDuration = minimumTraceDurationMs - (Date.now() - traceStartedAt)
    if (remainingTraceDuration > 0) {
      await new Promise(resolve => setTimeout(resolve, remainingTraceDuration))
    }
    if (testingInterfaceId !== selectedInterfaceId.value) return
    chainPhase.value = activeTraceDefinitions.value.length
    if (result.traceNodes.some(node => ['ERROR', 'FAILED', 'FAILURE'].includes(node.status))
      || result.standardResponse.resultStatus === 'ERROR') {
      throw new Error('链路返回异常，请点击异常节点核对入参与出参，修正配置后重新测试。')
    }
    if (testingMode === 'UPSTREAM' && testingInterfaceId === detail.value.summary.id) {
      lastUpstreamValidation.value = { interfaceId: testingInterfaceId, connectionFingerprint: upstreamValidationFingerprint.value }
    }
    if (testingMode === 'PLATFORM' && testingVersion === detail.value.summary.currentVersion
      && JSON.stringify(mappingSnapshot) === JSON.stringify(savedMappingsBaseline.value)) {
      const proof = await api.validationStatus(testingInterfaceId)
      lastOrchestrationValidation.value = proof.orchestrationVerified
        ? { interfaceId: testingInterfaceId, version: testingVersion } : null
      if (!proof.orchestrationVerified) throw new Error('当前草稿未取得有效验证凭证，请保存配置后重新验证。')
    }
    if (isSuccessfulE2E(result, testingMode) && detail.value.summary.publishedVersion != null) {
      lastE2ETest.value = { interfaceId: testingInterfaceId, version: detail.value.summary.publishedVersion }
    }
    testSucceeded.value = true
    if (testingMode !== 'UPSTREAM') {
      try { await refreshBilling() } catch { ElMessage.warning('联调已完成，计费统计刷新失败，可稍后刷新') }
    }
    ElMessage.success(testMode.value === 'UPSTREAM' ? '上游接口验证成功'
      : testMode.value === 'PLATFORM' ? '草稿链路测试通过' : `线上 V${detail.value.summary.publishedVersion} 入口验收成功`)
    return true
  } catch (error) {
    const apiError = error && typeof error === 'object' && 'payload' in error && 'message' in error
      ? error as ApiRequestError : null
    if (apiError?.payload.code === 'TEST_EXECUTION_FAILED') {
      const nodes = Array.isArray(apiError.payload.traceNodes) ? apiError.payload.traceNodes as TestResult['traceNodes'] : []
      testResult.value = { upstreamRequest: {}, upstreamResponse: {}, standardResponse: {}, steps: [], traceNodes: nodes }
      const failedNode = nodes.find(node => ['ERROR', 'FAILED', 'FAILURE'].includes(node.status))
      selectedTraceKey.value = failedNode?.key ?? nodes[0]?.key ?? ''
      chainPhase.value = Math.max(0, activeTraceDefinitions.value.findIndex(node => node.key === selectedTraceKey.value))
      const traceId = typeof apiError.payload.traceId === 'string' ? apiError.payload.traceId : ''
      testError.value = `${apiError.message}${traceId ? `（Trace ID：${traceId}）` : ''}`
    } else {
      testError.value = error instanceof SyntaxError ? 'JSON 格式不正确' : error instanceof Error ? error.message : '测试执行失败'
    }
    ElMessage.error(testError.value)
    return false
  } finally {
    if (phaseTimer) clearInterval(phaseTimer)
    if (testAbortController.value === controller) testAbortController.value = null
    testRunning.value = false
  }
}

function cancelTest() {
  if (testRunning.value) testAbortController.value?.abort()
}

function chainNodeState(index: number) {
  const nodeKey = activeTraceDefinitions.value[index]?.key
  const actual = testResult.value?.traceNodes.find(node => node.key === nodeKey)
  if (!testRunning.value && actual) {
    if (['ERROR', 'FAILED', 'FAILURE'].includes(actual.status)) return 'error'
    if (actual.status === 'SUCCESS') return 'success'
  }
  if (testError.value && index === chainPhase.value) return 'error'
  if (testSucceeded.value || (testRunning.value && index < chainPhase.value)) return 'success'
  if (testRunning.value && index === chainPhase.value) return 'running'
  return 'waiting'
}

function chainNodeStatus(index: number) {
  const state = chainNodeState(index)
  return state === 'success' ? '已完成' : state === 'running' ? '处理中' : state === 'error' ? '异常' : '等待'
}

function selectTraceNode(key: string) {
  if (testResult.value?.traceNodes.some(node => node.key === key)) selectedTraceKey.value = key
}

function traceNodeDuration(key: string) {
  return testResult.value?.traceNodes.find(node => node.key === key)?.durationMs
}

function channelActive(index: number) {
  return testSucceeded.value || (testRunning.value && chainPhase.value > index)
}

function stepDuration(name: string) {
  return testResult.value?.steps.find(step => step.name.includes(name))?.durationMs ?? 0
}

function traceTotalDuration() {
  return testResult.value?.steps.reduce((sum, step) => sum + step.durationMs, 0) ?? 0
}

function mappingRowKey(row: FieldMapping) {
  const existing = mappingRowKeys.get(row)
  if (existing) return existing
  const key = `mapping-row-${++mappingRowKeySequence}`
  mappingRowKeys.set(row, key)
  return key
}
function onMappingRuleVisibleChange(row: FieldMapping, visible: boolean) {
  if (visible) activeMappingRuleRow.value = row
  else if (activeMappingRuleRow.value === row) activeMappingRuleRow.value = null
}
function enumSummary(row: FieldMapping) {
  const entries = Object.entries(row.transformConfig ?? {})
  if (!entries.length) return '尚未配置枚举规则'
  const preview = entries.slice(0, 2).map(([source, target]) => `${source} → ${target}`).join(' · ')
  return entries.length > 2 ? `${preview} · 共 ${entries.length} 项` : preview
}
function addEnumPair(sourceValue = '', targetValue = '') {
  enumPairs.value.push({ id: ++enumPairSequence, sourceValue, targetValue })
}
function removeEnumPair(id: number) {
  enumPairs.value = enumPairs.value.filter(item => item.id !== id)
}
function openEnumDrawer(row: FieldMapping) {
  editingEnumMapping.value = row
  enumPairs.value = Object.entries(row.transformConfig ?? {}).map(([sourceValue, targetValue]) => ({
    id: ++enumPairSequence, sourceValue, targetValue
  }))
  enumDefaultValue.value = row.defaultValue ?? ''
  if (!enumPairs.value.length) addEnumPair()
  enumDrawer.value = true
}
function switchToResponseMappings() {
  enumDrawer.value = false
  mappingDirection.value = 'RESPONSE'
  ElMessage.info('已切换到“返回给行内”，请从上游响应样例生成或新增映射')
}
function applyEnumConfig() {
  const row = editingEnumMapping.value
  if (!row) return
  const completePairs: Array<[string, string]> = []
  const seen = new Set<string>()
  for (const pair of enumPairs.value) {
    const source = pair.sourceValue.trim()
    const target = pair.targetValue.trim()
    if (!source && !target) continue
    if (!source || !target) {
      ElMessage.warning('每条枚举规则必须同时填写上游原值和行内标准值')
      return
    }
    if (seen.has(source)) {
      ElMessage.warning(`上游原值“${source}”重复，请合并后再保存`)
      return
    }
    seen.add(source)
    completePairs.push([source, target])
  }
  row.transformConfig = Object.fromEntries(completePairs)
  row.defaultValue = enumDefaultValue.value.trim()
  enumDrawer.value = false
  ElMessage.success(completePairs.length ? `已应用 ${completePairs.length} 条枚举规则` : '已清空枚举规则')
}
const availableAlgorithmsForEditing = computed(() => {
  const kind = editingSecurityMapping.value?.transformType
  return securityAlgorithms.value.filter(item => item.kind === kind)
})
const algorithmGroupsForEditing = computed(() => Array.from(
  availableAlgorithmsForEditing.value.reduce((groups, item) => {
    const values = groups.get(item.category) ?? []
    values.push(item)
    groups.set(item.category, values)
    return groups
  }, new Map<string, SecurityAlgorithmDescriptor[]>())
).map(([category, algorithms]) => ({ category, algorithms })))
const selectedSecurityAlgorithm = computed(() => securityAlgorithms.value.find(
  item => item.code === editingSecurityMapping.value?.transformConfig?.mode))

function algorithmName(row: FieldMapping) {
  if (!['ENCRYPT', 'SIGN'].includes(row.transformType)) return ''
  return securityAlgorithms.value.find(item => item.code === row.transformConfig?.mode)?.name
    ?? row.transformConfig?.mode ?? '未配置算法'
}
function algorithmConfigured(row: FieldMapping) {
  const descriptor = securityAlgorithms.value.find(item => item.code === row.transformConfig?.mode)
  return Boolean(descriptor && descriptor.parameters.every(parameter => !parameter.required || row.transformConfig?.[parameter.name]))
}
async function openAlgorithmDrawer(row: FieldMapping) {
  editingSecurityMapping.value = row
  algorithmDrawer.value = true
  if (!securityAlgorithms.value.length) {
    securityAlgorithms.value = await loadSecurityAlgorithms()
    if (!securityAlgorithms.value.length) {
      ElMessage.error(`算法目录加载失败：${securityAlgorithmLoadError.value || '管理服务未返回算法数据'}`)
    }
  }
}
function selectSecurityAlgorithm(code: string) {
  const row = editingSecurityMapping.value
  const descriptor = securityAlgorithms.value.find(item => item.code === code)
  if (!row || !descriptor) return
  const previous = row.transformConfig ?? {}
  row.transformConfig = { mode: code }
  descriptor.parameters.forEach(parameter => {
    row.transformConfig[parameter.name] = previous[parameter.name] || parameter.defaultValue || ''
  })
}
function onTransformTypeChange(row: FieldMapping) {
  if (row.transformType === 'ENUM') {
    row.transformConfig = {}
    nextTick(() => openEnumDrawer(row))
  } else if (row.transformType === 'ENCRYPT') {
    row.transformConfig = {}
    nextTick(() => openAlgorithmDrawer(row))
  } else if (row.transformType === 'SIGN') {
    row.transformConfig = {}
    nextTick(() => openAlgorithmDrawer(row))
  } else if (row.transformType !== 'DATE') {
    row.transformConfig = {}
  }
}
async function publishConfig() {
  if (!detail.value || !hasUnpublishedChanges.value || detail.value.summary.status === 'PENDING_APPROVAL') return false
  await validateConfig()
  if (!validation.value?.valid) return false
  const testHint = currentPreReleaseVerified.value
    ? '当前草稿已完成可选链路测试。'
    : '当前草稿尚未执行可选链路测试；该测试不影响上线。'
  await ElMessageBox.confirm(`${testHint} 确认上线 ${detail.value.summary.interfaceCode} V${detail.value.summary.currentVersion}？`, '确认服务上线', { confirmButtonText: '确认上线', cancelButtonText: '返回检查', type: 'warning' })
  const published = await api.directPublish(selectedInterfaceId.value, releaseNote.value)
  detail.value.summary = published
  ElMessage.success(`V${published.publishedVersion} 已上线，可配置 API 访问授权`)
  try { await loadAll() } catch { ElMessage.warning('服务已上线，页面刷新失败，请稍后刷新') }
  return true
}
function openApproval(row: ReleaseApproval) { selectedApproval.value = row; approvalComment.value = ''; approvalDialog.value = true }
async function decideApproval(decision: 'APPROVE'|'REJECT') { if (!selectedApproval.value) return; await api.decideApproval(selectedApproval.value.id, decision, approvalComment.value); approvalDialog.value = false; await Promise.all([refreshApprovals(), loadAll()]); ElMessage.success(decision === 'APPROVE' ? '审批通过，发布计划已激活' : '已驳回发布申请') }

function openClientRegistration(fromAuth = false) {
  returnToAuthAfterClient.value = fromAuth
  authDialog.value = false
  clientDialog.value = true
}
async function createClient() {
  const created = await api.createClient(clientForm.value)
  clientDialog.value = false
  clients.value = await api.clients()
  const createdId = Number((created as ClientApp | undefined)?.id)
  const selectedClient = activeClients.value.find(item => item.id === createdId) ?? activeClients.value[activeClients.value.length - 1]
  if (returnToAuthAfterClient.value && selectedClient) {
    authForm.value.clientId = selectedClient.id
    returnToAuthAfterClient.value = false
    authDialog.value = true
  }
  ElMessage.success('调用系统已登记')
}
function openAuthDialog() {
  Object.assign(authForm.value, defaultAuthorizationDates)
  authForm.value.clientId = activeClients.value[0]?.id ?? 0
  if (!authorizableInterfaces.value.some(item => item.id === authForm.value.interfaceId)) {
    authForm.value.interfaceId = authorizableInterfaces.value[0]?.id ?? 0
  }
  if (!authForm.value.interfaceId) {
    ElMessage.warning('暂无已发布且在线的 API，请先完成服务发布')
    return
  }
  authDialog.value = true
}
async function createAuthorization() {
  if (!activeClients.value.length || !authForm.value.clientId) {
    ElMessage.warning('请先登记并启用一个调用系统')
    return
  }
  if (!authForm.value.interfaceId) {
    ElMessage.warning('请选择需要授权的 API')
    return
  }
  await api.createAuthorization(authForm.value)
  authDialog.value = false
  authorizations.value = await api.authorizations()
  ElMessage.success('API 授权已生效')
}
function openBatchAuthorization() {
  if (!activeClients.value.length) {
    ElMessage.warning('请先登记并启用一个调用系统')
    return
  }
  if (!authorizableInterfaces.value.length) {
    ElMessage.warning('暂无已发布且在线的 API')
    return
  }
  Object.assign(batchAuthForm.value, defaultAuthorizationDates)
  batchAuthDateRange.value = [defaultAuthorizationDates.validFrom, defaultAuthorizationDates.validTo]
  batchAuthForm.value.clientId = activeClients.value[0]?.id ?? 0
  batchAuthForm.value.interfaceIds = []
  batchAuthFilters.value = { sourceId: 0, keyword: '', grantStatus: 'UNAUTHORIZED' }
  batchAuthPage.value = 1
  batchAuthDialog.value = true
}
async function createBatchAuthorization() {
  if (!batchAuthForm.value.clientId || !batchAuthForm.value.interfaceIds.length) {
    ElMessage.warning('请选择调用系统和至少一个已发布 API')
    return
  }
  try {
    batchAuthSubmitting.value = true
    ;[batchAuthForm.value.validFrom, batchAuthForm.value.validTo] = batchAuthDateRange.value
    await api.createAuthorizations(batchAuthForm.value)
    batchAuthDialog.value = false
    authorizations.value = await api.authorizations()
    ElMessage.success(`已批量授权 ${batchAuthForm.value.interfaceIds.length} 个 API`)
  } finally { batchAuthSubmitting.value = false }
}
function openBillingDialog(rule?: BillingRule) {
  if (rule) billingForm.value = { interfaceId: rule.interfaceId, billingMode: rule.billingMode, unitPrice: Number(rule.unitPrice), hitBillable: rule.hitBillable, missBillable: rule.missBillable, effectiveDate: rule.effectiveDate }
  else billingForm.value.interfaceId = allInterfaces.value[0]?.id ?? 1
  billingDialog.value = true
}
async function saveBilling() { await api.saveBillingRule(billingForm.value); billingDialog.value = false; await refreshBilling(); ElMessage.success('计费规则已生效') }
async function refreshBilling() { [billingRules.value, billingSummary.value, usageRecords.value] = await Promise.all([api.billingRules(), api.billingSummary(), api.usageRecords()]) }
async function refreshMonitor() {
  [monitorSummary.value, monitorPoints.value, monitorAlerts.value, usageRecords.value] = await Promise.all([
    api.monitorSummary(), api.monitorPoints(), api.monitorAlerts(), api.usageRecords()
  ])
}
async function searchCallLogs() { callLogs.value = await api.callLogs(callLogFilters.value) }
function resetCallLogFilters() { callLogFilters.value = { status: 'ALL', interfaceCode: '', keyword: '' }; void searchCallLogs() }
function openFailedLogs() { callLogFilters.value.status = 'FAILED'; activeMenu.value = 'logs'; void searchCallLogs() }
async function openCallTrace(row: UsageRecord) {
  const traceId = row.traceId?.trim()
  if (!traceId) {
    ElMessage.warning('该调用记录缺少 Trace ID，无法查询调用链路')
    return
  }
  activeCallTraceRecord.value = row
  callTrace.value = null
  callTraceError.value = ''
  selectedLogNodeKey.value = ''
  callTraceDrawer.value = true
  callTraceLoading.value = true
  try {
    const detail = await api.callTrace(traceId)
    if (!detail?.summary) throw new Error('链路详情缺少调用摘要')
    callTrace.value = detail
    selectedLogNodeKey.value = detail.nodes?.[0]?.key ?? ''
  } catch (error) {
    callTraceError.value = error instanceof Error ? error.message : '链路详情加载失败'
    ElMessage.error(`链路详情加载失败：${callTraceError.value}`)
  } finally {
    callTraceLoading.value = false
  }
}

async function loadVendorCatalog() {
  try { vendorCatalog.value = await vendorApi.catalog(); vendorOnline.value = true }
  catch { vendorOnline.value = false; vendorCatalog.value = [] }
}

async function bringVendorApiToPlatform(item: VendorApiDefinition, vendorSource: VendorDataSourceDefinition) {
  const importingKey = `${vendorSource.sourceCode}:${item.apiCode}`
  if (vendorImportingKey.value) return
  vendorImportingKey.value = importingKey

  try {
    // 跨系统只使用稳定的业务编码匹配，禁止假设数据库主键固定为 1。
    let provider = providers.value.find(candidate => candidate.providerCode === 'VENDOR_DEMO')
    if (!provider) {
      provider = await api.createProvider({
        providerCode: 'VENDOR_DEMO',
        providerName: '上游演示资源提供商',
        providerType: 'DATA_VENDOR',
        description: '由资源提供商开放目录自动接入',
        messageEncoding: 'UTF-8',
        chargeRequired: false,
        encryptionRequired: false,
        loginRequired: false,
        status: 'ACTIVE'
      })
      providers.value = [...providers.value, provider]
    }

    let source = sources.value.find(candidate => candidate.sourceCode === vendorSource.sourceCode)
    if (!source) {
      source = await api.createDataSource({
        providerId: provider.id,
        sourceCode: vendorSource.sourceCode,
        sourceName: vendorSource.sourceName,
        protocolType: 'HTTP_JSON',
        environment: 'TEST',
        instanceCount: 2,
        endpoint: `http://127.0.0.1:8090${vendorSource.basePath}`,
        defaultTimeoutMs: 3000
      })
      selectedSourceId.value = source.id
      await loadAll()
      source = sources.value.find(candidate => candidate.id === source?.id) ?? source
    }

    selectedSourceId.value = source.id
    apiForm.value = {
      dataSourceId: source.id,
      interfaceCode: item.apiCode,
      interfaceName: item.apiName,
      servicePath: `/api/v1/vendor/${item.apiCode.toLowerCase().replace(/_/g, '-')}`,
      httpMethod: item.httpMethod,
      configScope: 'OVERRIDE',
      upstreamPath: item.servicePath,
      upstreamUrl: `http://127.0.0.1:8090${item.servicePath}`,
      timeoutMode: 'INHERIT',
      timeoutMs: source.defaultTimeoutMs ?? 3000,
      generateMock: false,
      upstreamRequestExample: item.requestExample
    }
    vendorRequestExample.value = item.requestExample
    vendorResponseExample.value = item.responseBody
    apiDialog.value = true
    ElMessage.success('资源提供商和 API 配置已带入，请继续创建草稿')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '接入平台失败，请检查管理服务日志')
  } finally {
    vendorImportingKey.value = ''
  }
}

const monitorMaxDuration = computed(() => Math.max(1, ...monitorPoints.value.map(item => item.avgDurationMs)))
function formatJson(value: unknown) { return JSON.stringify(value, null, 2) }
function formatDateTime(value: string) { return value ? value.replace('T', ' ').slice(0, 16) : '-' }
function money(value: number) { return `¥ ${Number(value).toFixed(2)}` }
</script>

<template>
  <div v-if="!loggedIn" class="login-page">
    <main class="login-form-panel">
      <form class="login-box" @submit.prevent="login">
        <div class="login-brand">
          <span class="login-brand-mark">ED</span>
          <div class="login-brand-copy">
            <strong>外部数据平台</strong>
            <small>EXTERNAL DATA SERVICE PLATFORM</small>
          </div>
        </div>
        <div class="login-title"><h2>登录平台</h2><p>使用行内统一身份进入管理控制台</p></div>
        <label>用户名</label><el-input v-model="loginForm.username" :prefix-icon="UserIcon" size="large" />
        <label>密码</label><el-input v-model="loginForm.password" :prefix-icon="Lock" type="password" show-password size="large" />
        <div class="login-options"><el-checkbox :model-value="true">记住用户名</el-checkbox><span>演示账号：admin / admin123</span></div>
        <el-button native-type="submit" type="primary" size="large" :loading="loginLoading">登录</el-button>
        <p class="login-help">技术支持：省联社信息科技部 · 内线 6800</p>
      </form>
    </main>
    <footer class="login-page-footer">江苏农信外部数据服务管理控制台</footer>
  </div>

  <el-container v-else class="app-shell" :class="{'sidebar-collapsed':sidebarCollapsed}" v-loading="loading">
    <el-aside :width="sidebarCollapsed ? '68px' : '224px'" class="sidebar" :class="{collapsed:sidebarCollapsed}">
      <div class="brand"><div class="brand-mark">ED</div><div class="brand-copy"><div class="brand-name">外部数据平台</div><div class="brand-sub">External Data Service</div></div><button class="sidebar-collapse-button" type="button" :title="sidebarCollapsed?'展开菜单':'收起菜单'" :aria-label="sidebarCollapsed?'展开菜单':'收起菜单'" :aria-expanded="!sidebarCollapsed" @click.stop="sidebarCollapsed=!sidebarCollapsed"><component :is="sidebarCollapsed?Expand:Fold"/></button></div>
      <el-menu :default-active="activeMenu" :collapse="sidebarCollapsed" :collapse-transition="false" class="side-menu">
        <button v-if="!sidebarCollapsed" class="menu-section-label" type="button" @click="menuGroups.global=!menuGroups.global"><span>全局视图</span><ArrowRight :class="{expanded:menuGroups.global}"/></button>
        <el-menu-item aria-label="运营总览" v-show="menuGroups.global||sidebarCollapsed" index="overview" @click="navigate('overview')"><el-icon><DataBoard /></el-icon><template #title>运营总览</template></el-menu-item>
        <button v-if="!sidebarCollapsed" class="menu-section-label" type="button" @click="menuGroups.service=!menuGroups.service"><span>数据服务</span><ArrowRight :class="{expanded:menuGroups.service}"/></button>
        <template v-if="menuGroups.service||sidebarCollapsed">
          <el-menu-item aria-label="资源目录" index="discovery" @click="navigate('discovery')"><el-icon><OfficeBuilding /></el-icon><template #title>资源目录</template></el-menu-item>
          <el-menu-item aria-label="资源方管理" index="providers" @click="navigate('providers')"><el-icon><UserIcon /></el-icon><template #title>资源方管理</template></el-menu-item>
          <el-menu-item aria-label="数据资源接入" index="resource" @click="navigate('resource')"><el-icon><Connection /></el-icon><template #title>数据资源接入</template></el-menu-item>
          <el-menu-item aria-label="标准服务开发" index="service-development" @click="navigate('service-development')"><el-icon><Setting /></el-icon><template #title>标准服务开发</template></el-menu-item>
          <el-menu-item aria-label="服务发布" index="releases" @click="navigate('releases')"><el-icon><Promotion /></el-icon><template #title>服务发布</template></el-menu-item>
          <el-menu-item aria-label="API 访问授权" index="authorization" @click="navigate('authorization')"><el-icon><Key /></el-icon><template #title>API 访问授权</template></el-menu-item>
        </template>
        <button v-if="!sidebarCollapsed" class="menu-section-label" type="button" @click="menuGroups.operations=!menuGroups.operations"><span>运行管理</span><ArrowRight :class="{expanded:menuGroups.operations}"/></button>
        <template v-if="menuGroups.operations||sidebarCollapsed">
          <el-menu-item aria-label="计量管理" index="billing" @click="navigate('billing')"><el-icon><Tickets /></el-icon><template #title>计量管理</template></el-menu-item>
          <el-menu-item aria-label="链路追踪" index="logs" @click="navigate('logs')"><el-icon><DataAnalysis /></el-icon><template #title>链路追踪</template></el-menu-item>
          <el-menu-item aria-label="运行监控" index="monitor" @click="navigate('monitor')"><el-icon><Monitor /></el-icon><template #title>运行监控</template></el-menu-item>
        </template>
      </el-menu>
      <div class="sidebar-footer"><div><strong>外部数据服务管理</strong><span>资源接入 · 开发 · 运营</span></div></div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="crumbs"><span>外部数据平台</span><b>/</b><span>{{ pageMeta[0] }}</span></div>
        <div class="top-actions"><el-tag v-if="demo" type="warning" effect="plain">演示数据模式</el-tag><el-tag type="info" effect="plain">TEST 环境</el-tag><el-dropdown><div class="user"><span>{{ user?.displayName }}</span><b>管</b></div><template #dropdown><el-dropdown-menu><el-dropdown-item @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
      </el-header>

      <el-main class="main-content">
        <section class="page-heading" :class="{'compact-page-heading': activeMenu === 'releases' || activeMenu === 'authorization'}"><div><h1>{{ pageMeta[0] }}</h1><p>{{ pageMeta[1] }}</p></div><div class="heading-actions"><el-button :icon="Refresh" @click="refreshCurrentWorkspace">刷新</el-button><el-button v-if="activeMenu === 'resource'" type="primary" :icon="Plus" @click="openSourceWizard">接入数据源</el-button><el-button v-if="activeMenu === 'service-development'" type="primary" :icon="Plus" @click="navigate('resource')">从资源 API 创建服务</el-button><el-button v-if="activeMenu === 'authorization'" :icon="Files" @click="openBatchAuthorization">批量授权</el-button><el-button v-if="activeMenu === 'authorization'" type="primary" :icon="Plus" @click="openAuthDialog">新增授权</el-button><el-button v-if="activeMenu === 'billing'" type="primary" :icon="Plus" @click="openBillingDialog()">配置计费</el-button></div></section>

        <template v-if="activeMenu === 'overview'">
          <section class="cockpit-command">
            <div class="cockpit-runtime"><span class="live-beacon">运营概况</span><div><strong>服务与调用概览</strong><p>{{ dashboard.dataSourceCount }} 个数据源 · {{ dashboard.publishedCount }} 个已发布 API · {{ clients.length }} 个调用系统</p></div></div>
            <div class="cockpit-context"><span>运行环境<b>TEST</b></span><span>数据源实例<b>{{ overviewTotalInstances }}</b></span><span>当前告警<b :class="{ danger: monitorSummary.activeAlerts > 0 }">{{ monitorSummary.activeAlerts }}</b></span></div>
            <div class="cockpit-actions"><button @click="navigate('logs')"><Search/><span><strong>链路追踪</strong><small>检索 Trace ID</small></span><ArrowRight/></button><button @click="navigate('monitor')"><Monitor/><span><strong>运行监控</strong><small>查看节点耗时</small></span><ArrowRight/></button></div>
          </section>

          <section class="cockpit-kpis">
            <div class="cockpit-kpi primary"><span>今日调用量</span><strong>{{ monitorSummary.totalCalls.toLocaleString() }}</strong><small>全平台 API 请求</small><DataAnalysis/></div>
            <div class="cockpit-kpi"><span>调用成功率</span><strong>{{ overviewSuccessRate.toFixed(1) }}<em>%</em></strong><small :class="overviewSuccessRate >= 99 ? 'positive' : 'negative'">{{ overviewSuccessRate >= 99 ? '运行质量稳定' : '需关注失败调用' }}</small><CircleCheck/></div>
            <div class="cockpit-kpi"><span>平均响应耗时</span><strong>{{ monitorSummary.avgDurationMs }}<em> ms</em></strong><small>P95 {{ monitorSummary.p95DurationMs }} ms</small><Connection/></div>
            <div class="cockpit-kpi"><span>已发布 API</span><strong>{{ dashboard.publishedCount }}</strong><small>共 {{ dashboard.interfaceCount }} 个接口</small><Files/></div>
            <div class="cockpit-kpi"><span>有效服务授权</span><strong>{{ validAuthorizations.length }}</strong><small>{{ clients.length }} 个调用系统</small><Key/></div>
            <div class="cockpit-kpi"><span>本期计费金额</span><strong>{{ money(billingSummary.totalAmount) }}</strong><small>{{ billingSummary.billableCalls }} 次可计费调用</small><Wallet/></div>
          </section>

          <section class="cockpit-analytics">
            <div class="cockpit-panel trend-panel">
              <header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>平台调用趋势</strong></div><div class="chart-legend"><i class="success"></i>成功<i class="error"></i>异常</div></header>
              <div class="traffic-chart" :class="{ inspecting: activeTrendIndex !== null }">
                <div class="chart-grid"><i></i><i></i><i></i><i></i></div>
                <button v-for="(point,index) in monitorPoints" :key="point.timeLabel" type="button" class="traffic-column" :class="{ active: activeTrendIndex === index }" :aria-label="`${point.timeLabel}，调用 ${point.calls} 次，异常 ${point.errorCalls} 次`" @mouseenter="activeTrendIndex=index" @mouseleave="activeTrendIndex=null" @focus="activeTrendIndex=index" @blur="activeTrendIndex=null">
                  <div class="traffic-stack" :style="{ height: `${Math.max(8, point.calls / overviewMaxCalls * 100)}%` }"><i class="error" :style="{ height: `${point.calls ? point.errorCalls / point.calls * 100 : 0}%` }"></i></div>
                  <span>{{ point.timeLabel }}</span>
                </button>
                <div v-if="activeTrendPoint" class="chart-tooltip" :style="{ left: `${((activeTrendIndex ?? 0) + .5) / Math.max(1, monitorPoints.length) * 100}%`, transform: activeTrendIndex === 0 ? 'translateX(0)' : activeTrendIndex === monitorPoints.length - 1 ? 'translateX(-100%)' : 'translateX(-50%)' }"><header><strong>{{ activeTrendPoint.timeLabel }}</strong><span>{{ activeTrendPoint.calls }} 次调用</span></header><div><span>成功<b>{{ activeTrendPoint.successCalls }}</b></span><span>异常<b class="error">{{ activeTrendPoint.errorCalls }}</b></span><span>平均耗时<b>{{ activeTrendPoint.avgDurationMs }} ms</b></span><span>上游耗时<b>{{ activeTrendPoint.avgUpstreamDurationMs }} ms</b></span></div></div>
                <div v-if="!monitorPoints.length" class="chart-empty">暂无趋势数据</div>
              </div>
              <footer><span>峰值调用<b>{{ overviewMaxCalls }}</b></span><span>平均响应<b>{{ monitorSummary.avgDurationMs }} ms</b></span><span>上游平均耗时<b>{{ monitorSummary.avgUpstreamDurationMs }} ms</b></span></footer>
            </div>
            <div class="cockpit-panel result-panel">
              <header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>调用结果分布</strong></div><b>{{ billingSummary.totalCalls }} 次</b></header>
              <div class="result-visual"><div class="result-donut" :class="{ inspecting: activeResultItem }"><svg viewBox="0 0 100 100" role="img" aria-label="调用结果分布环形图"><circle class="donut-track" cx="50" cy="50" r="40" pathLength="100"/><circle v-for="item in overviewResultSegments" :key="item.key" class="donut-segment" :class="{ active: activeResultKey === item.key, muted: activeResultKey && activeResultKey !== item.key }" cx="50" cy="50" r="40" pathLength="100" fill="none" :stroke="item.color" :stroke-dasharray="`${item.percentage} ${100-item.percentage}`" :stroke-dashoffset="-item.offset" tabindex="0" :aria-label="`${item.label} ${item.value} 次，占比 ${item.percentage}%`" @mouseenter="activeResultKey=item.key" @mouseleave="activeResultKey=null" @focus="activeResultKey=item.key" @blur="activeResultKey=null"/></svg><span><strong>{{ activeResultItem ? activeResultItem.value : `${overviewSuccessRate.toFixed(1)}%` }}</strong><small>{{ activeResultItem ? activeResultItem.label : '成功率' }}</small><em v-if="activeResultItem">{{ activeResultItem.percentage }}%</em></span></div><div class="result-list"><div v-for="item in overviewResultMix" :key="item.key" :class="{ active: activeResultKey === item.key, muted: activeResultKey && activeResultKey !== item.key }" tabindex="0" @mouseenter="activeResultKey=item.key" @mouseleave="activeResultKey=null" @focus="activeResultKey=item.key" @blur="activeResultKey=null"><i :style="{background:item.color}"></i><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.percentage }}%</small></div></div></div>
              <button class="panel-link" @click="navigate('logs')">查看全部链路记录<ArrowRight/></button>
            </div>
          </section>

          <section class="cockpit-alerts" :class="{ clear: monitorSummary.activeAlerts === 0 }"><span><Warning v-if="monitorSummary.activeAlerts"/><CircleCheck v-else/></span><div><strong>{{ monitorSummary.activeAlerts ? `${monitorSummary.activeAlerts} 项运行告警待处理` : '当前无活动告警' }}</strong><small>{{ monitorSummary.activeAlerts ? monitorAlerts[0]?.detail || '请进入运行监控查看告警详情' : 'Gateway、数据源应用和上游服务链路均处于正常状态' }}</small></div><button @click="navigate('monitor')">告警中心<ArrowRight/></button></section>

          <section class="cockpit-operations">
            <div class="cockpit-panel source-health-panel"><header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>数据源登记状态</strong></div><b>{{ sources.length }} 个数据源</b></header><div class="source-health-list"><div v-for="source in sources.slice(0,5)" :key="source.id"><span :class="source.status.toLowerCase()"><i></i></span><div><strong>{{ source.sourceName }}</strong><small>{{ source.applicationName }}</small></div><b>{{ source.runtimeUrl ? '已配路由' : '待配路由' }}</b><em>V{{ source.configVersion }}</em></div></div><button class="panel-link" @click="navigate('resource')">管理数据源与 API<ArrowRight/></button></div>
            <div class="cockpit-panel ranking-panel"><header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>API 调用排行</strong></div><span>最近 {{ usageRecords.length }} 笔</span></header><div class="ranking-list"><div v-for="(item,index) in overviewTopInterfaces" :key="item.code"><b>{{ String(index + 1).padStart(2, '0') }}</b><div><strong>{{ item.name }}</strong><small>{{ item.code }}</small></div><span>{{ item.calls }} 次</span><em>{{ item.avgDuration }} ms</em><i :class="{warning:item.successRate<99}">{{ item.successRate }}%</i></div><div v-if="!overviewTopInterfaces.length" class="panel-empty">暂无 API 调用数据</div></div></div>
            <div class="cockpit-panel failure-panel"><header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>近期异常调用</strong></div><button @click="navigate('logs')">全部</button></header><div class="failure-list"><button v-for="item in overviewRecentFailures" :key="item.id" @click="navigate('logs')"><span>ERROR</span><div><strong>{{ item.interfaceName }}</strong><small>{{ item.nodeName || item.standardCode }}</small></div><time>{{ formatDateTime(item.calledAt) }}</time><ArrowRight/></button><div v-if="!overviewRecentFailures.length" class="panel-empty success"><CircleCheck/><span>暂无异常调用</span></div></div></div>
          </section>

          <section class="cockpit-shortcuts"><span>快速治理</span><button @click="openSourceWizard"><Connection/>接入数据源</button><button @click="navigate('resource')"><Files/>服务开发</button><button @click="navigate('releases')"><Promotion/>服务上线</button><button @click="navigate('authorization')"><Key/>访问控制</button><button @click="navigate('billing')"><Wallet/>计量规则</button></section>
        </template>

        <template v-else-if="activeMenu === 'discovery'">
          <section class="vendor-app-banner"><div class="vendor-brand-icon"><OfficeBuilding /></div><div><span>外部开放目录</span><h2>企信资源提供商 · 实时能力目录</h2><p>GET http://127.0.0.1:8090/open/catalog</p></div><div class="vendor-runtime" :class="{offline:!vendorOnline}"><i></i><strong>{{ vendorOnline ? '目录连接正常' : '资源提供商不可用' }}</strong><span>{{ vendorOnline ? `${vendorCatalog.length} 个数据源可接入` : '请独立启动资源提供商 Demo' }}</span></div></section>
          <section class="vendor-flow"><div><span>1</span><strong>资源方独立发布</strong><small>在 5190 新增数据源与 API</small></div><i></i><div><span>2</span><strong>平台实时发现</strong><small>读取开放目录，不共享数据库</small></div><i></i><div><span>3</span><strong>配置并热发布</strong><small>字段映射、联调和发布计划切换</small></div></section>
          <section v-if="vendorOnline" class="catalog-list"><div v-for="source in vendorCatalog" :key="source.id" class="plain-panel catalog-source"><div class="panel-heading"><div><strong>{{ source.sourceName }}</strong><span>{{ source.sourceCode }} · {{ source.description }}</span></div><el-tag type="success">{{ source.apis.length }} 个在线 API</el-tag></div><el-table :data="source.apis"><el-table-column label="供应商 API" min-width="220"><template #default="{ row }"><div class="api-name-cell"><span>{{ row.httpMethod }}</span><div><strong>{{ row.apiName }}</strong><small>{{ row.apiCode }}</small></div></div></template></el-table-column><el-table-column label="实时地址" min-width="280"><template #default="{ row }"><code>http://127.0.0.1:8090{{ row.servicePath }}</code></template></el-table-column><el-table-column label="版本" width="85"><template #default="{ row }">V{{ row.version }}</template></el-table-column><el-table-column label="更新时间" width="150"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column><el-table-column label="操作" width="140" fixed="right"><template #default="{ row }"><el-button text type="primary" :loading="vendorImportingKey === `${source.sourceCode}:${row.apiCode}`" :disabled="Boolean(vendorImportingKey)" @click="bringVendorApiToPlatform(row, source)">接入并创建草稿</el-button></template></el-table-column></el-table></div></section>
          <section v-else class="vendor-offline"><Warning/><strong>未连接到独立资源方应用</strong><span>执行 ./start-vendor.sh 后刷新目录。资源方应用与外部数据平台独立启停。</span></section>
        </template>

        <template v-else-if="activeMenu === 'providers'">
          <section class="provider-management">
            <aside class="provider-list plain-panel"><div class="panel-heading"><strong>资源提供商</strong><el-button :icon="Plus" text aria-label="新增资源提供商" @click="openNewProvider" /></div><button v-for="provider in providers" :key="provider.id" class="provider-item" :class="{active: provider.id === selectedProviderId}" @click="selectedProviderId = provider.id; loadProviderAccounts()"><span class="provider-avatar">{{ provider.providerName.slice(0,1) }}</span><span><strong>{{ provider.providerName }}</strong><small>{{ provider.providerCode }}</small></span><el-tag size="small" type="success">{{ provider.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></button></aside>
            <section v-if="selectedProvider" class="provider-detail plain-panel"><div class="panel-heading"><div><strong>{{ selectedProvider.providerName }}</strong><span>{{ selectedProvider.providerCode }} · 资源提供商主数据</span></div><el-button type="primary" :icon="Setting" @click="openProviderEditor()">编辑资料</el-button></div><div class="provider-info-grid"><div><span>提供商类型</span><strong>{{ selectedProvider.providerType || '-' }}</strong></div><div><span>经办人</span><strong>{{ selectedProvider.contactName || '-' }}</strong></div><div><span>接入时间</span><strong>{{ selectedProvider.accessDate || '-' }}</strong></div><div><span>通讯报文编码</span><strong>{{ selectedProvider.messageEncoding || '-' }}</strong></div><div><span>是否需要收费</span><strong>{{ selectedProvider.chargeRequired ? '是' : '否' }}</strong></div><div><span>是否需要加密</span><strong>{{ selectedProvider.encryptionRequired ? '是' : '否' }}</strong></div><div><span>是否需要登录</span><strong>{{ selectedProvider.loginRequired ? '是' : '否' }}</strong></div><div class="wide"><span>提供商描述</span><strong>{{ selectedProvider.description || '-' }}</strong></div></div><div class="section-toolbar"><div><strong>账号与权限</strong><span>独立维护资源提供商侧登录账号和可操作范围</span></div><el-button type="primary" :icon="Plus" @click="openAccountDialog">新增账号</el-button></div><el-table :data="providerAccounts" border><el-table-column prop="username" label="账号" width="150"/><el-table-column prop="displayName" label="显示名称" width="130"/><el-table-column prop="roleCode" label="角色" width="160"/><el-table-column label="权限" min-width="320"><template #default="{row}"><el-tag v-for="permission in row.permissions" :key="permission" size="small" effect="plain" class="permission-tag">{{ permission }}</el-tag></template></el-table-column><el-table-column prop="lastLoginAt" label="最近登录" width="170"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status==='ACTIVE'?'success':'info'">{{ row.status==='ACTIVE'?'启用':'停用' }}</el-tag></template></el-table-column></el-table></section>
          </section>
        </template>
        <template v-else-if="activeMenu === 'releases'">
          <section class="release-console" :class="{'catalog-collapsed':releaseCatalogCollapsed}">
            <aside class="release-api-list plain-panel" :class="{collapsed:releaseCatalogCollapsed}">
              <button v-if="releaseCatalogCollapsed" class="api-catalog-rail" type="button" title="展开发布资源目录" aria-label="展开发布资源目录" @click="releaseCatalogCollapsed=false"><Expand/><span>API</span></button>
              <template v-else>
              <div class="panel-heading"><div><strong>发布资源目录</strong><span>{{ sources.length }} 个数据源 · {{ allInterfaces.length }} 个 API</span></div><div class="catalog-heading-actions"><el-tag type="info">按数据源</el-tag><el-button text :icon="Fold" title="收起 API 目录" aria-label="收起 API 目录" @click="releaseCatalogCollapsed=true"/></div></div>
              <div class="release-tree-tools"><el-input v-model="releaseSearch" :prefix-icon="Search" clearable placeholder="搜索数据源或 API"/><div><button :disabled="!!releaseSearch.trim()" @click="setAllReleaseSourcesExpanded(true)">展开全部</button><button :disabled="!!releaseSearch.trim()" @click="setAllReleaseSourcesExpanded(false)">收起全部</button></div></div>
              <div class="release-tree">
                <div v-for="source in releaseFilteredSources" :key="source.id" class="release-source-group">
                  <div class="release-source-heading"><button class="release-expand" :class="{expanded:isReleaseSourceExpanded(source.id)}" :aria-expanded="isReleaseSourceExpanded(source.id)" @click="releaseSourceExpansion[source.id] = !isReleaseSourceExpanded(source.id)"><ArrowRight/></button><button class="release-source-label" @click="releaseSourceExpansion[source.id] = true"><span><strong>{{ source.sourceName }}</strong><small>{{ source.sourceCode }} · {{ releaseSourceInterfaces(source.id).length }} 个 API</small></span><i class="status-dot" :class="source.status.toLowerCase()"></i></button></div>
                  <div v-show="isReleaseSourceExpanded(source.id)" class="release-source-apis"><button v-for="item in releaseSourceInterfaces(source.id)" :key="item.id" :class="{active:item.id===releaseInterfaceId}" @click="selectReleaseInterface(item.id)"><span class="release-api-method">{{ item.httpMethod }}</span><span><strong>{{ item.interfaceName }}</strong><small>{{ item.interfaceCode }} · 草稿 V{{ item.currentVersion }}</small></span><el-tag size="small" :type="item.status==='OFFLINE'?'danger':item.status==='PENDING_APPROVAL'?'warning':item.publishedVersion!=null?'success':'info'">{{ item.status==='OFFLINE'?'已下线':item.status==='PENDING_APPROVAL'?'审批中':item.publishedVersion!=null?'线上 V'+item.publishedVersion:'未发布' }}</el-tag></button><div v-if="!releaseSourceInterfaces(source.id).length" class="release-tree-empty">没有匹配的 API</div></div>
                </div>
                <div v-if="!releaseFilteredSources.length" class="release-tree-empty">未找到匹配的数据源或 API</div>
              </div>
              </template>
            </aside>
            <div v-if="releaseDetail" class="release-console-main" :class="{'is-refreshing':releaseLoading}" aria-live="polite">
              <section class="release-command plain-panel">
                <div class="release-command-title"><span class="release-command-icon"><Promotion/></span><div><small aria-hidden="true" class="decorative-eyebrow"></small><h2>{{ releaseDetail.summary.interfaceName }}</h2><p><code>{{ releaseDetail.summary.interfaceCode }}</code><i></i>{{ releaseDetail.summary.servicePath }}</p></div></div>
                <div class="release-state"><span>当前草稿<strong>V{{ releaseDetail.summary.currentVersion }}</strong></span><b></b><span>线上生效<strong>{{ releaseDetail.summary.publishedVersion == null ? '-' : 'V'+releaseDetail.summary.publishedVersion }}</strong></span><el-tag effect="plain" :type="releaseDetail.summary.status==='OFFLINE'?'danger':releaseDetail.summary.status==='PENDING_APPROVAL'?'warning':releaseDetail.summary.publishedVersion!=null?'success':'info'">{{ releaseDetail.summary.status==='OFFLINE'?'已下线':releaseDetail.summary.status==='PENDING_APPROVAL'?'审批中':releaseDetail.summary.publishedVersion!=null?'运行中':'未上线' }}</el-tag></div>
                <div class="release-command-actions">
                  <el-button v-if="releaseDetail.summary.status!=='PENDING_APPROVAL' && releaseDetail.summary.currentVersion!==releaseDetail.summary.publishedVersion" type="primary" :icon="Promotion" @click="submitReleaseFromManagement">{{ releaseDetail.summary.status==='OFFLINE' ? `上线草稿 V${releaseDetail.summary.currentVersion}` : '直接上线' }}</el-button>
                  <el-button v-if="releaseDetail.summary.publishedVersion!=null" plain :icon="DocumentCopy" @click="openPublishedDetail(releaseDetail.summary.id)">查看接口详情</el-button>
                  <el-button v-if="releaseDetail.summary.publishedVersion!=null && releaseDetail.summary.status==='PUBLISHED'" type="primary" plain :icon="Key" @click="authForm.interfaceId=releaseDetail.summary.id; navigate('authorization')">配置访问授权</el-button>
                  <el-button v-if="releaseDetail.summary.status==='OFFLINE' && releaseDetail.summary.publishedVersion!=null" type="primary" :icon="Promotion" @click="onlineRelease">恢复线上 V{{ releaseDetail.summary.publishedVersion }}</el-button>
                  <el-button v-if="releaseDetail.summary.publishedVersion!=null && releaseDetail.summary.status!=='OFFLINE'" type="warning" plain @click="offlineRelease">下线接口</el-button>
                  <el-button v-if="releaseDetail.summary.status==='OFFLINE' || releaseDetail.summary.publishedVersion==null" type="danger" plain :icon="Delete" @click="archiveRelease">归档删除</el-button>
                </div>
              </section>
              <div v-if="!sources.find(source=>source.id===releaseDetail?.summary.dataSourceId)?.runtimeUrl" class="release-routing-warning" role="status"><div><strong>上线前需配置处理应用路由</strong><span>当前数据源尚未登记运行地址。补充后即可继续发布。</span></div><el-button @click="editReleaseRuntime">配置运行路由</el-button></div>
              <section class="release-lifecycle plain-panel"><div class="done"><span><CircleCheck/></span><strong>配置完成</strong><small>草稿已持久化</small></div><i></i><div :class="{done:releaseDetail.summary.publishedVersion!=null && releaseDetail.summary.status!=='OFFLINE'}"><span><Promotion/></span><strong>服务上线</strong><small>{{ releaseDetail.summary.publishedVersion==null?'等待上线':'生效 V'+releaseDetail.summary.publishedVersion }}</small></div><i></i><div :class="{done:releaseHasAuthorization}"><span><Key/></span><strong>访问授权</strong><small>{{ releaseHasAuthorization?'已有有效授权':'上线后配置' }}</small></div><i></i><div :class="{done:releaseDetail.summary.status==='OFFLINE'}"><span><Delete/></span><strong>下线归档</strong><small>{{ releaseDetail.summary.status==='OFFLINE'?'已停止服务':'按需执行' }}</small></div></section>
              <section v-if="releaseDetail.summary.currentVersion!==releaseDetail.summary.publishedVersion && releaseDetail.summary.status!=='PENDING_APPROVAL'" class="release-submit-panel plain-panel"><div><strong>待上线版本 V{{ releaseDetail.summary.currentVersion }}</strong><span>{{ releaseDetail.summary.status==='OFFLINE' ? `线上流量已停止；可上线草稿 V${releaseDetail.summary.currentVersion}，或恢复原线上 V${releaseDetail.summary.publishedVersion}。` : '保存线上版本的新配置时生成下一草稿版本；同一草稿反复保存不递增，上线也不额外加版本。' }}</span></div><el-input v-model="releaseNoteManagement" type="textarea" :rows="2" placeholder="填写本次变更内容"/></section>
              <section id="interface-version-history" class="version-history-panel plain-panel">
                <header><div><span aria-hidden="true" class="decorative-eyebrow"></span><h3>接口版本历史</h3><p>集中保存草稿、发布记录和执行计划快照。</p></div><el-tag type="info">{{ releaseVersions.length }} 个版本</el-tag></header>
                <el-alert v-if="releaseVersions.some(item => !item.historyComplete)" title="检测到旧版本记录不完整" type="warning" :closable="false" description="接口主表保留了已发布版本号，但旧审批单、发布记录或执行计划快照缺失；版本仍保留并阻止未经下线的删除。"/>
                <el-table :data="releaseVersions" border scrollbar-always-on :row-class-name="versionRowClass">
                  <el-table-column label="版本" width="92"><template #default="{row}"><strong>V{{ row.version }}</strong></template></el-table-column>
                  <el-table-column label="状态" width="190"><template #default="{row}"><el-tag :type="versionTagType(row)">{{ versionStatusText(row) }}</el-tag><small v-if="row.active" class="version-active-label">流量使用中</small></template></el-table-column>
                  <el-table-column label="发布说明" min-width="220"><template #default="{row}">{{ row.releaseNote || (row.historyComplete ? '未填写' : '旧数据未保留') }}</template></el-table-column>
                  <el-table-column label="申请 / 审批" min-width="170"><template #default="{row}"><span>{{ row.applicant || '-' }}</span><small class="version-cell-sub">{{ row.reviewedBy || row.releasedBy || '-' }}</small></template></el-table-column>
                  <el-table-column label="时间" width="180"><template #default="{row}">{{ row.releasedAt || row.reviewedAt || row.submittedAt || '-' }}</template></el-table-column>
                  <el-table-column label="计划快照" width="110"><template #default="{row}"><el-tag size="small" :type="row.snapshotAvailable?'success':'info'">{{ row.snapshotAvailable?'已留存':'无快照' }}</el-tag></template></el-table-column>
                  <el-table-column label="操作" width="90"><template #default="{row}"><el-button text type="primary" @click="showVersionDetail(row)">查看</el-button></template></el-table-column>
                </el-table>
              </section>
            </div>
            <div v-else class="plain-panel release-console-empty"><Promotion/><strong>选择一个接口</strong><span>查看版本、上线状态及生命周期操作</span></div>
          </section>
        </template>
        <template v-else-if="activeMenu === 'approvals'">
          <section class="metric-strip"><div><span>待审批</span><strong class="warning-text">{{ pendingApprovals.length }}</strong></div><div><span>已通过</span><strong class="success-text">{{ approvals.filter(item => item.status === 'APPROVED').length }}</strong></div><div><span>已驳回</span><strong>{{ approvals.filter(item => item.status === 'REJECTED').length }}</strong></div></section>
          <section class="plain-panel table-panel"><div class="panel-heading"><div><strong>接口发布审批</strong><span>只有审批通过才会写入发布记录并激活 PublishedPlan</span></div><el-button :icon="Refresh" @click="refreshApprovals">刷新</el-button></div><el-table :data="approvals" border><el-table-column prop="interfaceName" label="接口" min-width="220"/><el-table-column prop="interfaceCode" label="接口编码" width="180"/><el-table-column label="申请版本" width="100"><template #default="{row}">V{{ row.releaseVersion }}</template></el-table-column><el-table-column prop="applicant" label="申请人" width="120"/><el-table-column prop="approvalNode" label="当前节点" width="120"/><el-table-column prop="submittedAt" label="提交时间" width="180"/><el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.status==='APPROVED'?'success':row.status==='REJECTED'?'danger':'warning'">{{ row.status==='PENDING'?'待审批':row.status==='APPROVED'?'已通过':'已驳回' }}</el-tag></template></el-table-column><el-table-column label="操作" width="100"><template #default="{row}"><el-button v-if="row.status==='PENDING'" text type="primary" @click="openApproval(row)">处理</el-button></template></el-table-column></el-table></section>
        </template>
        <template v-else-if="activeMenu === 'resource' || activeMenu === 'service-development'">
          <section class="workbench resource-workbench" :class="{ 'source-switching': sourceSwitching, 'catalog-collapsed':resourceCatalogCollapsed }">
            <aside class="resource-tree-panel" :class="{collapsed:resourceCatalogCollapsed}">
              <button v-if="resourceCatalogCollapsed" class="api-catalog-rail" type="button" title="展开 API 资源目录" aria-label="展开 API 资源目录" @click="resourceCatalogCollapsed=false"><Expand/><span>API</span></button>
              <template v-else>
              <div class="panel-title"><div><span>{{ activeMenu==='resource' ? '资源 API 目录' : '平台服务目录' }}</span><small>{{ filteredProviderGroups.length }} 个资源提供商 · {{ sources.length }} 个数据源 · {{ workspaceInterfaceRows.length }} 个{{ activeMenu==='resource' ? '资源 API' : '标准服务' }}</small></div><div class="catalog-heading-actions"><el-button v-if="activeMenu==='resource'" :icon="Plus" text aria-label="新增数据源" @click="openSourceWizard"/><el-button :icon="Fold" text title="收起 API 目录" aria-label="收起 API 目录" @click="resourceCatalogCollapsed=true"/></div></div>
              <el-input v-model="sourceSearch" :prefix-icon="Search" placeholder="搜索提供商、数据源、API 或路径" clearable aria-label="搜索资源目录" />
              <div class="resource-tree-tools"><button :disabled="!!sourceSearch.trim()" @click="setAllSourcesExpanded(true)">展开全部</button><button :disabled="!!sourceSearch.trim()" @click="setAllSourcesExpanded(false)">收起全部</button><button :disabled="!selectedInterfaceId" @click="locateSelectedApi">定位当前</button></div>
              <p v-if="sourceSearch.trim()" class="resource-search-hint">找到 {{ filteredProviderGroups.length }} 个资源提供商、{{ filteredSources.length }} 个数据源，已展开匹配结果</p>
              <div class="resource-tree" aria-label="资源提供商、数据源与接口目录">
                <section v-for="providerGroup in filteredProviderGroups" :key="providerGroup.providerId" class="resource-provider-group">
                  <div class="resource-provider-heading">
                    <button class="source-expand-toggle provider-expand-toggle" :class="{expanded:isProviderExpanded(providerGroup.providerId)}" :aria-expanded="isProviderExpanded(providerGroup.providerId)" :aria-controls="'provider-sources-' + providerGroup.providerId" :aria-label="(isProviderExpanded(providerGroup.providerId) ? '收起' : '展开') + providerGroup.providerName" :disabled="!!sourceSearch.trim()" @click="providerExpansion[providerGroup.providerId] = !isProviderExpanded(providerGroup.providerId)"><ArrowRight/></button>
                    <button class="resource-provider-head" :title="providerGroup.providerName" :disabled="!!sourceSearch.trim()" @click="providerExpansion[providerGroup.providerId] = !isProviderExpanded(providerGroup.providerId)"><span><strong>{{ providerGroup.providerName }}</strong><small v-if="providerGroup.providerCode">{{ providerGroup.providerCode }}</small></span><em>{{ providerGroup.sources.length }} 个数据源</em></button>
                  </div>
                  <div v-show="isProviderExpanded(providerGroup.providerId)" :id="'provider-sources-' + providerGroup.providerId" class="resource-provider-sources">
                    <div v-for="source in providerGroup.sources" :key="source.id" class="resource-source-group" :class="{active: source.id === selectedSourceId}">
                      <div class="resource-source-heading">
                        <button class="source-expand-toggle" :class="{expanded:isSourceExpanded(source.id)}" :aria-expanded="isSourceExpanded(source.id)" :aria-controls="'source-apis-' + source.id" :aria-label="(isSourceExpanded(source.id) ? '收起' : '展开') + source.sourceName" :disabled="!!sourceSearch.trim()" @click="sourceExpansion[source.id] = !isSourceExpanded(source.id)"><ArrowRight/></button>
                        <button class="resource-source-head" :title="source.sourceName + ' · ' + source.sourceCode" @click="selectSource(source.id)"><span><strong>{{ source.sourceName }}</strong><small>{{ source.sourceCode }} · {{ sourceInterfaces(source.id).length }} 个{{ activeMenu==='resource' ? '资源 API' : '标准服务' }}</small></span><i class="status-dot" :class="source.status.toLowerCase()"></i></button>
                      </div>
                      <div v-show="isSourceExpanded(source.id)" :id="'source-apis-' + source.id">
                        <div class="resource-api-list">
                          <button v-for="apiItem in sourceInterfaces(source.id)" :key="apiItem.id" class="resource-api-item" :class="{active: apiItem.id === selectedInterfaceId}" :aria-current="apiItem.id === selectedInterfaceId ? 'page' : undefined" :title="apiItem.interfaceName + ' · ' + apiItem.servicePath" @click="selectResourceInterface(source.id, apiItem.id)"><span class="api-method">{{ apiItem.httpMethod }}</span><span><strong>{{ apiItem.interfaceName }}</strong><small>{{ apiItem.interfaceCode }}</small></span><el-tag size="small" :type="serviceDevelopmentStage==='UPSTREAM' ? (apiItem.upstreamStatus==='READY'?'success':'info') : apiItem.status === 'OFFLINE' ? 'danger' : apiItem.status === 'PUBLISHED' ? 'success' : 'info'">{{ serviceDevelopmentStage==='UPSTREAM' ? (apiItem.upstreamStatus==='READY'?'已接入':'未保存') : apiItem.status === 'OFFLINE' ? '已下线' : apiItem.status === 'PUBLISHED' ? '已发布' : '开发中' }}</el-tag></button>
                          <div v-if="!sourceInterfaces(source.id).length" class="resource-api-empty">{{ sourceSearch.trim() ? '没有匹配的 API' : '暂无 API，点击下方按钮创建' }}</div>
                        </div>
                        <button v-if="activeMenu==='resource'" class="resource-add-api" @click="openApiDialogForSource(source.id)"><Plus/> 新增资源 API</button>
                      </div>
                    </div>
                  </div>
                </section>
                <div v-if="!filteredProviderGroups.length" class="resource-tree-empty"><Files/><strong>未找到匹配资源</strong><span>尝试提供商、数据源、API 或服务路径</span><el-button text @click="sourceSearch=''">清空搜索</el-button></div>
              </div>
              <div class="resource-tree-footer">{{ workspaceInterfaceRows.length }} 个{{ activeMenu==='resource' ? '资源 API' : '标准服务' }} · 按资源提供商 / 数据源分组</div>
              </template>
            </aside>
            <div class="editor-panel" v-if="detail && selectedSource" :class="{ 'is-loading': detailLoading || sourceSwitching }" aria-live="polite">
              <header class="context-bar api-context-header">
                <div class="context-main">
                  <div class="api-breadcrumb">{{ selectedSource.providerName }}<span>/</span>{{ selectedSource.sourceName }}<span>/</span>{{ serviceDevelopmentStage === 'UPSTREAM' ? '上游能力' : '标准服务' }}</div>
                  <div class="context-title"><span class="method">{{ detail.summary.httpMethod }}</span><h2>{{ detail.summary.interfaceName }}</h2><el-tag v-if="activeMenu==='resource'" :type="detail.summary.upstreamStatus==='READY'?'success':'info'" size="small">{{ detail.summary.upstreamStatus==='READY'?'已接入':'接入草稿' }}</el-tag><el-tag v-else :type="detail.summary.status === 'OFFLINE' ? 'danger' : detail.summary.status === 'PUBLISHED' ? 'success' : 'info'" size="small">{{ detail.summary.status === 'OFFLINE' ? '线上已下线' : detail.summary.status === 'PUBLISHED' ? '已发布' : '开发中' }}</el-tag></div>
                  <div class="api-context-meta"><code>{{ detail.summary.interfaceCode }}</code><template v-if="activeMenu!=='resource'"><span>草稿 V{{ detail.summary.currentVersion }}</span><span v-if="detail.summary.publishedVersion == null">尚未发布</span><button v-else class="version-history-link" type="button" @click="openVersionHistory(detail.summary.publishedVersion)">已发布 V{{ detail.summary.publishedVersion }}</button></template><span v-else>供应商原始接口</span></div>
                </div>
                <div class="api-context-actions">
                  <el-button v-if="activeMenu==='resource'" :icon="Setting" @click="openSourceSettingsEditor">编辑数据源</el-button>
                  <el-button v-if="activeMenu==='resource'" :icon="Connection" @click="openSourceRuntimeEditor">运行路由</el-button>
                  <el-button v-if="activeMenu==='resource'" :icon="Plus" @click="openApiDialogForSource(selectedSource.id)">新增资源 API</el-button>
                  <el-button v-if="activeMenu!=='resource'" :icon="Promotion" @click="openVersionHistory(detail.summary.publishedVersion ?? undefined)">版本档案</el-button>
                  <el-tooltip v-if="activeMenu!=='resource'" :disabled="canDeleteApi" :content="deleteApiRestriction" placement="bottom"><span><el-button :icon="Delete" type="danger" plain :disabled="!canDeleteApi || detailLoading || sourceSwitching" :loading="deletingApi" @click="deleteCurrentInterface">删除服务</el-button></span></el-tooltip>
                </div>
              </header>
              <nav v-if="serviceDevelopmentStage==='UPSTREAM'" class="developer-tabs" aria-label="资源接口配置">
                <button v-for="(step,index) in upstreamSections" :key="step.title" type="button" :class="{active:upstreamSection===index}" :aria-current="upstreamSection===index?'page':undefined" @click="upstreamSection=index">{{ step.title }}</button>
              </nav>
              <nav v-if="serviceDevelopmentStage==='ORCHESTRATION'" class="developer-tabs" aria-label="标准服务配置"><button v-for="step in orchestrationGuideSteps" :key="step.key" :class="{active:activeTab===step.key,changed:activeTab===step.key && hasUnsavedChanges}" :aria-current="activeTab===step.key ? 'page' : undefined" :disabled="guideBusy || testRunning || connectionSaving" @click="openGuideStep(step.key)">{{ step.title }}</button></nav>
              <div class="api-request-bar" :class="{standard:serviceDevelopmentStage==='ORCHESTRATION'}">
                <span class="request-method">{{ serviceDevelopmentStage==='UPSTREAM' ? detail.summary.httpMethod : standardServiceForm.httpMethod }}</span>
                <code>{{ serviceDevelopmentStage==='UPSTREAM' ? (previewUpstreamUrl || '尚未配置接口地址') : standardServiceForm.servicePath }}</code>
                <span v-if="serviceDevelopmentStage==='UPSTREAM'" class="request-timeout">{{ previewTimeoutMs }} ms</span>
                <el-button v-if="serviceDevelopmentStage==='UPSTREAM'" :loading="testRunning" :disabled="connectionSaving || !previewUpstreamUrl" @click="validateUpstreamCapability">测试接口</el-button>
                <el-button v-else :disabled="guideBusy || testRunning" @click="activeTab='test';testMode='PLATFORM'">打开联调</el-button>
              </div>
              <div v-if="guideError" class="api-guide-error compact" role="alert">{{ guideError }}</div>
              <el-tabs v-model="activeTab" class="editor-tabs">
                <el-tab-pane v-if="serviceDevelopmentStage==='UPSTREAM'" name="overview" label="上游能力配置">
                  <div class="api-connection-workspace">
                    <div v-show="upstreamSection===0" class="api-config-columns">
                    <section class="api-config-card">
                        <header><div><h3>连接设置</h3></div></header>
                        <div class="api-card-body">
                          <span class="api-field-label">地址配置方式</span>
                          <div class="api-mode-options" role="group" aria-label="地址配置方式"><button type="button" :class="{selected:connectionForm.configScope==='INHERIT'}" :aria-pressed="connectionForm.configScope==='INHERIT'" @click="connectionForm.configScope='INHERIT'">继承数据源</button><button type="button" :class="{selected:connectionForm.configScope==='OVERRIDE'}" :aria-pressed="connectionForm.configScope==='OVERRIDE'" @click="connectionForm.configScope='OVERRIDE'">单接口覆盖</button></div>
                          <p class="api-field-help">{{ connectionForm.configScope==='INHERIT' ? '使用数据源基础地址，补充当前接口的相对路径。' : '为当前接口设置完整地址，不影响其他接口。' }}</p>
                          <div v-if="connectionForm.configScope==='INHERIT'" class="api-inherited-value"><span>数据源基础地址</span><code>{{ selectedSource.endpointUrl || '尚未配置，请使用单接口覆盖' }}</code><el-button text @click="openSourceSettingsEditor">修改</el-button></div>
                          <el-form label-position="top"><el-form-item :label="connectionForm.configScope==='INHERIT' ? '接口相对路径' : '接口完整地址'"><el-input v-if="connectionForm.configScope==='INHERIT'" v-model="connectionForm.upstreamPath" placeholder="/vendor/api/enterprise-basic"/><el-input v-else v-model="connectionForm.upstreamUrl" placeholder="https://provider.example.com/api/query"/></el-form-item></el-form>
                        </div>
                      </section>
                    </div>
                    <section v-show="upstreamSection===0" class="api-timeout-disclosure" :class="{overridden:connectionForm.timeoutMode==='OVERRIDE'}">
                      <button class="api-timeout-toggle" :aria-expanded="timeoutExpanded" aria-controls="api-timeout-options" @click="timeoutExpanded=!timeoutExpanded">
                        <Setting/><span><strong>请求超时 <em>可选配置</em></strong><small>{{ connectionForm.timeoutMode==='INHERIT' ? '继承数据源默认配置' : '已设置单接口覆盖，不影响其他接口' }} · {{ previewTimeoutMs }} ms</small></span><b>{{ timeoutExpanded ? '收起设置' : '按需调整' }}</b><ArrowRight :class="{expanded:timeoutExpanded}"/>
                      </button>
                      <div v-show="timeoutExpanded" id="api-timeout-options" class="api-card-body">
                          <span class="api-field-label">超时配置方式</span>
                          <div class="api-mode-options" role="group" aria-label="超时配置方式"><button type="button" :class="{selected:connectionForm.timeoutMode==='INHERIT'}" :aria-pressed="connectionForm.timeoutMode==='INHERIT'" @click="connectionForm.timeoutMode='INHERIT'">继承数据源</button><button type="button" :class="{selected:connectionForm.timeoutMode==='OVERRIDE'}" :aria-pressed="connectionForm.timeoutMode==='OVERRIDE'" @click="connectionForm.timeoutMode='OVERRIDE'">单接口覆盖</button></div>
                          <p class="api-field-help">数据源默认 {{ selectedSource.defaultTimeoutMs ?? 3000 }} ms，可为当前接口单独调整。</p>
                          <el-form label-position="top"><el-form-item label="超时时间"><div class="api-timeout-input"><el-input-number :model-value="previewTimeoutMs" @update:model-value="(value: number | undefined) => { if (value != null) connectionForm.timeoutMs = value }" :disabled="connectionForm.timeoutMode==='INHERIT'" :min="100" :max="120000" :step="500" controls-position="right"/><span>毫秒（ms）</span></div></el-form-item></el-form>
                        </div>
                    </section>
                    <section v-show="upstreamSection===0" class="api-preview-panel">
                      <header><div><h3>生效配置</h3></div><el-tag :type="hasUnsavedChanges ? 'warning' : detail.summary.upstreamStatus==='READY' ? 'success' : 'info'" effect="plain">{{ hasUnsavedChanges ? '待保存' : detail.summary.upstreamStatus==='READY' ? '已保存' : '草稿' }}</el-tag></header>
                      <dl><div><dt>完整上游地址</dt><dd><code>{{ previewUpstreamUrl || '请填写上游地址' }}</code><el-button v-if="previewUpstreamUrl" text :icon="DocumentCopy" aria-label="复制上游地址" @click="copyApiValue(previewUpstreamUrl)">复制</el-button></dd></div><div><dt>请求超时</dt><dd><strong>{{ previewTimeoutMs }} ms</strong><span>{{ connectionForm.timeoutMode==='INHERIT' ? '继承数据源' : '单接口覆盖' }}</span></dd></div></dl>
                    </section>
                    <section v-show="upstreamSection===1" class="upstream-contract-panel">
                      <header><div><div><h3>供应商原始报文</h3></div></div><el-tag size="small" type="info">JSON</el-tag></header>
                      <div class="upstream-contract-grid">
                        <label><span>请求报文</span><el-input v-model="upstreamRequestText" type="textarea" :autosize="{ minRows: 12, maxRows: 20 }" placeholder="填写资源方接口实际请求 JSON" /></label>
                        <label><span>响应报文</span><el-input v-model="responseText" type="textarea" :autosize="{ minRows: 12, maxRows: 20 }" placeholder="填写资源方接口实际响应 JSON" /></label>
                      </div>
                    </section>
                    <section v-show="upstreamSection===2" class="upstream-validation-panel" :class="{'has-error':!!testError && !currentUpstreamVerified}">
                      <div class="upstream-validation-copy"><div><h3>接口联调</h3></div></div>
                      <el-button class="upstream-validation-action" type="primary" :icon="Promotion" :loading="testRunning" :disabled="testRunning || connectionSaving" @click="validateUpstreamCapability">验证上游接口</el-button>
                      <div class="upstream-validation-result success" v-if="currentUpstreamVerified"><span class="status-dot"></span><strong>当前连接验证通过</strong><small>已收到上游原始响应</small></div>
                      <div class="upstream-validation-result error" v-else-if="testError" role="alert"><span class="status-dot offline"></span><div><strong>验证失败</strong><small>{{ testError }}</small></div></div>
                    </section>
                    <div v-if="upstreamSection===2" class="upstream-handoff">
                      <h3>标准服务</h3>
                      <div><span>上游接口</span><code>{{ previewUpstreamUrl }}</code></div>
                      <div><span>接入状态</span><strong>{{ upstreamSaveRequired ? '有待保存的配置' : '已保存，可创建标准服务' }}</strong></div>
                      <p v-if="!selectedSource.runtimeUrl" class="route-notice">处理应用运行地址尚未配置，服务上线前需补充。<el-button text type="primary" @click="openSourceRuntimeEditor">配置运行路由</el-button></p>
                    </div>
                  </div>
                </el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="request" label="请求转换">
                  <section class="service-origin-strip">
                    <span>来源资源 API</span>
                    <div><strong>{{ detail.summary.resourceApiName || '历史服务资源' }}</strong><code>{{ detail.summary.resourceApiCode || '-' }}</code></div>
                    <small>{{ resourceContractChanged ? '资源契约已变更，当前草稿仍保留原契约' : '草稿与线上版本独立，资源变更可同步到当前草稿' }}</small>
                    <el-button v-if="detail.summary.resourceApiId" :type="resourceContractChanged ? 'warning' : 'primary'" plain :disabled="guideBusy || testRunning" @click="syncResourceContract">同步资源契约</el-button>
                  </section>
                  <section class="standard-contract-strip">
                    <div><strong>平台 API 契约</strong><span>定义提供给行内系统的标准调用入口</span></div>
                    <el-select v-model="standardServiceForm.httpMethod" aria-label="平台 API HTTP 方法"><el-option label="POST" value="POST"/><el-option label="GET" value="GET"/><el-option label="PUT" value="PUT"/><el-option label="PATCH" value="PATCH"/></el-select>
                    <el-input v-model="standardServiceForm.servicePath" placeholder="/api/v1/enterprise/query" aria-label="平台标准服务路径"/>
                  </section>
                  <div class="mapping-toolbar refined">
                    <div class="mapping-mode"><el-segmented v-model="mappingDirection" :options="[{ label: '请求转换', value: 'REQUEST' }, { label: '响应标准化', value: 'RESPONSE' }]" /><el-tag :type="mappingIsPassThrough ? 'success' : 'warning'" effect="plain">{{ mappingModeLabel }}</el-tag><span>{{ mappingDirection === 'REQUEST' ? '行内标准请求 → 上游原始请求' : '上游原始响应 → 行内标准响应' }}</span></div>
                    <div><el-button :icon="DocumentChecked" @click="validateConfig">校验配置</el-button><el-button :icon="Plus" @click="startMappingConfiguration">{{ mappingIsPassThrough ? '配置字段转换' : '新增一行' }}</el-button></div>
                  </div>
                  <div class="mapping-sample-panel"><div class="mapping-sample-head"><div><DocumentCopy/><span><strong>{{ mappingDirection === 'REQUEST' ? '行内标准请求样例' : '上游原始响应样例' }}</strong><small>{{ mappingDirection === 'REQUEST' ? '定义平台对外请求字段' : '来自数据资源接入，仅用于生成标准化映射' }}</small></span></div><el-button type="primary" plain :icon="DocumentCopy" @click="generateMappingsFromSample">从样例生成映射</el-button></div><el-input v-if="mappingDirection === 'REQUEST'" v-model="requestText" type="textarea" :autosize="{minRows:5,maxRows:10}"/><el-input v-else v-model="responseText" readonly type="textarea" :autosize="{minRows:5,maxRows:10}"/></div>
                  <div class="mapping-summary"><div><strong>{{ mappingIsPassThrough ? '自动透传' : filteredMappings.length }}</strong><span>{{ mappingIsPassThrough ? '当前处理模式' : '映射规则' }}</span></div><div><strong>{{ filteredMappings.filter(item => item.required).length }}</strong><span>必填字段</span></div><p>{{ filteredMappings.length ? (mappingDirection === 'REQUEST' ? '将行内标准字段转换为上游接口所需字段，转换后再发起请求。' : '解析上游原始响应并转换为行内标准字段，屏蔽不同资源方的字段差异。') : (mappingDirection === 'REQUEST' ? '无需配置映射：平台标准请求按相同字段名与路径一对一发送给上游。' : '无需配置映射：供应商响应按相同字段名与路径一对一返回给行内调用系统。') }}</p></div>
                  <div class="mapping-board">
                    <div class="mapping-board-head"><span>#</span><span>{{ mappingDirection === 'REQUEST' ? '行内标准字段' : '上游原始字段' }}</span><span>转换规则</span><span>{{ mappingDirection === 'REQUEST' ? '上游请求字段' : '平台标准字段' }}</span><span>属性</span></div>
                    <div v-if="mappingIsPassThrough" class="mapping-empty"><Connection/><strong>当前采用自动透传</strong><span>{{ mappingDirection === 'REQUEST' ? '平台请求将按原路径一对一发送给上游，无需逐条配置映射。' : '供应商响应将按原报文一对一返回给行内调用系统。' }}</span><el-button type="primary" :icon="Plus" @click="startMappingConfiguration">配置字段转换</el-button></div>
                    <div v-for="(row,index) in filteredMappings" :key="mappingRowKey(row)" class="mapping-row" :class="{'is-rule-open':activeMappingRuleRow===row}">
                      <span class="mapping-index">{{ String(index + 1).padStart(2, '0') }}</span>
                      <div class="field-cell"><label>来源路径</label><el-input v-model="row.sourcePath" placeholder="例：data.sex_code" /><small>支持 JSONPath 点号路径</small></div>
                      <div class="transform-cell"><span class="connector-line"></span><el-select v-model="row.transformType" :teleported="false" :persistent="true" placement="bottom-start" popper-class="mapping-rule-dropdown" @visible-change="onMappingRuleVisibleChange(row, $event)" @change="onTransformTypeChange(row)"><el-option-group label="基础转换"><el-option label="直接映射" value="DIRECT"/><el-option label="枚举转换" value="ENUM"/><el-option label="默认值" value="DEFAULT"/><el-option label="日期格式化" value="DATE"/></el-option-group><el-option-group label="安全处理"><el-option label="加密处理" value="ENCRYPT"/><el-option label="签名计算" value="SIGN"/></el-option-group></el-select><span class="connector-arrow">→</span></div>
                      <div class="field-cell"><label>目标路径</label><el-input v-model="row.targetPath" placeholder="例：data.sex" /><div v-if="row.transformType === 'ENUM'" class="enum-summary" :class="{ready:Object.keys(row.transformConfig ?? {}).length}"><div><span>枚举规则</span><strong>{{ enumSummary(row) }}</strong></div><el-button size="small" plain @click="openEnumDrawer(row)">{{ Object.keys(row.transformConfig ?? {}).length ? '修改配置' : '立即配置' }}</el-button></div><div v-else-if="row.transformType === 'ENCRYPT' || row.transformType === 'SIGN'" class="algorithm-summary" :class="{ready:algorithmConfigured(row)}"><div><span>{{ row.transformType === 'ENCRYPT' ? '加密算法' : '签名算法' }}</span><strong>{{ algorithmName(row) }}</strong></div><el-button size="small" plain @click="openAlgorithmDrawer(row)">{{ algorithmConfigured(row) ? '修改配置' : '立即配置' }}</el-button></div><el-input v-else class="enum-input" v-model="row.defaultValue" placeholder="默认值（可选）" /></div>
                      <div class="mapping-actions"><el-switch v-model="row.required" inline-prompt active-text="必" inactive-text="选" /><el-button :icon="Delete" text type="danger" aria-label="删除映射" @click="deleteMapping(row)" /></div>
                    </div>
                  </div>
                  <div v-if="validation" class="validation-result" :class="validation.valid ? 'valid' : 'invalid'"><el-icon><CircleCheck /></el-icon><div><strong>{{ validation.valid ? '配置校验通过' : '配置校验失败' }}</strong><span v-for="item in [...validation.errors, ...validation.warnings]" :key="item">{{ item }}</span></div></div>
                </el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="codes" label="结果码映射"><div class="section-toolbar"><div><strong>上游结果码 → 平台标准码</strong><span>先指定供应商响应中的码值字段路径，再配置具体码值映射</span></div><div><el-button :icon="Plus" @click="addResponseCode">新增规则</el-button></div></div><section class="vendor-code-path-panel"><div><strong>供应商返回码字段路径</strong><span>平台会从供应商原始响应的该路径读取码值。支持点号路径，例如 <code>result.code</code>、<code>status.code</code>、<code>data.retCode</code>。</span></div><el-input v-model="detail.vendorCodePath" placeholder="例如：result.code" clearable /></section><el-table :data="detail.responseCodes" border><el-table-column label="上游原始码值" width="170"><template #default="{ row }"><el-input v-model="row.vendorCode" placeholder="例如 0000" /></template></el-table-column><el-table-column label="平台标准码" min-width="190"><template #default="{ row }"><el-input v-model="row.standardCode" /></template></el-table-column><el-table-column label="结果状态" width="135"><template #default="{ row }"><el-select v-model="row.resultStatus"><el-option label="HIT · 查到数据" value="HIT"/><el-option label="MISS · 无数据" value="MISS"/><el-option label="ERROR · 调用异常" value="ERROR"/></el-select></template></el-table-column><el-table-column label="允许重试" width="100" align="center"><template #default="{ row }"><el-switch v-model="row.retryable" /></template></el-table-column><el-table-column label="参与计量" width="100" align="center"><template #default="{ row }"><el-switch v-model="row.billable" /></template></el-table-column></el-table></el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="security" label="安全处理">
                  <div class="security-config-panel">
                    <div class="section-toolbar"><div><strong>接口级安全策略</strong><span>按接口配置加密、签名和密钥引用</span></div></div>
                    <div class="security-status" :class="{enabled: securityPolicy.enabled}"><Lock/><div><strong>{{ securityPolicy.enabled ? '已启用安全处理' : '未启用安全处理' }}</strong><span>{{ securityPolicy.enabled ? '新请求会按当前草稿策略执行加密和签名' : '适用于纯透传接口；后续可随时配置并发布' }}</span></div></div>
                    <el-form label-position="top" class="security-form" :class="{'policy-disabled':!securityPolicy.enabled}"><div class="form-grid"><el-form-item label="启用安全策略"><el-switch v-model="securityPolicy.enabled" active-text="启用" inactive-text="关闭"/></el-form-item><el-form-item label="请求字段"><el-input v-model="securityPolicy.requestFields" placeholder="例如：credit_code,customer_id；支持点号路径"/></el-form-item><el-form-item label="加密方式"><el-select v-model="securityPolicy.encryptMode" filterable><el-option label="不加密" value="NONE"/><el-option v-for="item in securityAlgorithms.filter(value=>value.kind==='ENCRYPT')" :key="item.code" :label="`${item.name} · ${item.category}`" :value="item.code"/></el-select></el-form-item><el-form-item label="加密密钥引用"><el-input v-model="securityPolicy.encryptionKeyReference" placeholder="config://edsp.security.keys.vendor-encryption"/><small>仅保存引用；不得填写明文密钥。</small></el-form-item><el-form-item label="签名方式"><el-select v-model="securityPolicy.signatureMode" filterable><el-option label="不签名" value="NONE"/><el-option v-for="item in securityAlgorithms.filter(value=>value.kind==='SIGN')" :key="item.code" :label="`${item.name} · ${item.category}`" :value="item.code"/></el-select></el-form-item><el-form-item label="签名密钥引用"><el-input v-model="securityPolicy.signingKeyReference" placeholder="config://edsp.security.keys.vendor-signing"/></el-form-item><el-form-item label="签名输出字段"><el-input v-model="securityPolicy.signatureField" placeholder="signature"/></el-form-item><el-form-item label="策略说明"><el-input v-model="securityPolicy.description" placeholder="记录策略用途和供应商要求"/></el-form-item></div></el-form>
                    <div class="security-execution-note"><span>运行方式</span><strong>标准请求 → 字段映射 → 加密 → 签名 → 上游调用</strong><small>密钥只保存引用，联调链路会展示安全节点的输入输出摘要。</small></div>
                  </div>
                </el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="test" label="链路验证">
                  <div class="trace-workbench-head"><div><span>TRACE WORKBENCH</span><strong>接口联调与链路追踪</strong><small>{{ detail.summary.interfaceCode }} · TEST</small></div><div class="trace-run-actions"><el-button v-if="testRunning" type="danger" plain @click="cancelTest">取消测试</el-button><el-button type="primary" :icon="Promotion" :loading="testRunning" :disabled="testRunning" @click="runCurrentPrimaryAction">{{ testRunning ? `请求中 ${testElapsedSeconds}s` : testActionLabel }}</el-button></div></div>
                  <div class="test-mode-selector orchestration-modes" role="radiogroup" aria-label="标准服务验证模式">
                    <button v-for="mode in orchestrationTestModes" :key="mode.value" type="button" role="radio" :aria-checked="testMode===mode.value" :class="{active:testMode===mode.value}" @click="selectTestMode(mode.value)">
                      <span>{{ mode.index }}</span><div><strong>{{ mode.label }}</strong><b>{{ mode.path }}</b><small>{{ mode.description }}</small></div><i></i>
                    </button>
                  </div>
                  <div class="test-mode-context"><div><span>{{ activeTestMode.path }}</span><strong>{{ activeTestMode.label }}</strong><small>{{ activeTestMode.description }}</small></div><code>{{ testMode === 'UPSTREAM' ? detail.summary.upstreamUrl : detail.summary.servicePath }}</code></div>
                  <el-alert v-if="resourceContractChanged && testMode==='PLATFORM'" type="warning" :closable="false" show-icon title="来源资源契约已变更。如需采用新入参，请先回到请求转换，点击“同步资源契约”并核对映射。" />
                  <div v-if="testMode==='E2E'" class="esb-contract-strip"><div><span>真实调用入口</span><strong>行内系统 → XML → 模拟 ESB 队列</strong><small>队列消费后由 service_id 解析服务路由，不再由调用方传 servicePath。</small></div><el-tag type="success" effect="plain">service_id：{{ detail.summary.interfaceCode }}</el-tag></div>
                  <div v-if="testMode==='E2E'" class="esb-contract-details">
                    <div><span>XML 必填元数据</span><strong>service_sn · service_id · requester_id</strong><small>分别标识全局流水、平台 API 和调用系统；业务字段放在 Service_Body/request。</small></div>
                    <div><span>平台处理方向</span><strong>request → 请求转换 → 上游服务</strong><small>平台内部和 Kafka 使用结构化 JSON，协议转换仅由 ESB Adapter 负责。</small></div>
                    <div><span>正式响应格式</span><strong>标准 XML</strong><small>平台标准响应由 Adapter 封装为 Service XML，并写入 ESB 响应队列。</small></div>
                  </div>
                  <div class="test-grid">
                    <div class="code-pane"><div class="code-title"><span>{{ testMode === 'UPSTREAM' ? '上游请求报文' : testMode === 'E2E' ? '行内 XML 入队报文' : '行内标准请求' }}</span><small>{{ testMode === 'UPSTREAM' ? '数据源应用发送' : testMode === 'E2E' ? 'ESB Adapter 消费并解析' : '用于验证请求转换' }}</small></div><el-input v-if="testMode==='UPSTREAM'" v-model="upstreamRequestText" type="textarea" :autosize="{ minRows: 10, maxRows: 16 }"/><el-input v-else-if="testMode==='PLATFORM'" v-model="requestText" type="textarea" :autosize="{ minRows: 10, maxRows: 16 }"/><pre v-else class="esb-xml-preview">{{ esbXmlPreview }}</pre></div>
                    <div v-if="testMode==='PLATFORM'" class="code-pane readonly"><div class="code-title"><span>{{ testResult ? '上游实际响应' : '上游响应契约参考' }}</span><small>验证会真实访问上游；本步骤不经过 ESB / Gateway</small></div><pre>{{ testResult ? formatJson(testResult.upstreamResponse) : responseText }}</pre></div>
                    <div v-else-if="testMode==='E2E'" class="code-pane readonly"><div class="code-title"><span>{{ esbXmlResponsePreview ? '写回 ESB 的 XML 响应' : '解析后的标准请求' }}</span><small>{{ esbXmlResponsePreview ? 'ESB Adapter 已完成响应封装' : 'ESB Adapter 输出给 Gateway' }}</small></div><pre>{{ esbXmlResponsePreview || formatJson(standardRequestPreview) }}</pre></div>
                    <div v-else class="connection-target"><span>UPSTREAM TARGET</span><strong>{{ detail.summary.upstreamUrl }}</strong><div><p><b>Method</b>{{ detail.summary.httpMethod }}</p><p><b>Timeout</b>{{ detail.timeoutMs }} ms</p><p><b>Adapter</b>{{ selectedSource.applicationName }}</p></div></div>
                  </div>
                  <div v-if="testRunning || testResult || testError" class="trace-console" :class="{running:testRunning,failed:!!testError}">
                    <div class="trace-header"><div><span class="trace-live"><i></i>{{ testRunning ? 'LIVE TRACE' : testError ? 'TRACE FAILED' : 'TRACE COMPLETE' }}</span><div><strong>外部数据调用链路</strong><small>{{ detail.summary.interfaceCode }} · {{ detail.summary.upstreamUrl }}</small></div></div><div class="trace-summary"><span>{{ testRunning ? '数据传输中' : testError ? '链路异常' : '全节点正常' }}</span><b>{{ testResult ? `${traceTotalDuration()} ms` : '-- ms' }}</b></div></div>
                    <div class="trace-track">
                      <template v-for="(node,index) in activeTraceDefinitions" :key="node.key">
                        <button class="trace-node" :class="[chainNodeState(index),{selected:selectedTraceKey===node.key}]" type="button" :aria-label="`查看${node.label}节点报文`" @click="selectTraceNode(node.key)">
                          <span><component :is="node.icon"/></span><strong>{{ node.label }}</strong><small>{{ node.short }}</small><b>{{ traceNodeDuration(node.key) ?? chainNodeStatus(index) }}</b>
                        </button>
                        <i v-if="index < activeTraceDefinitions.length - 1" class="data-channel" :class="{active:channelActive(index),done:testSucceeded||chainPhase>index}"><span></span><b></b></i>
                      </template>
                    </div>
                    <div v-if="testError" class="trace-error"><Warning/><div><strong>链路执行失败</strong><span>{{ testError }}</span></div></div>
                    <div v-if="selectedTraceNode" class="trace-inspector">
                      <header><div><span>{{ selectedTraceNode.category }}</span><strong>{{ selectedTraceNode.name }}</strong><small>{{ selectedTraceNode.detail }}</small></div><div><b>{{ selectedTraceNode.status }}</b><strong>{{ selectedTraceNode.durationMs }} ms</strong></div></header>
                      <div class="trace-inspector-grid"><section><div><span>INPUT</span><small>进入该节点的实际数据</small></div><pre>{{ formatJson(selectedTraceNode.input) }}</pre></section><section><div><span>OUTPUT</span><small>该节点处理后的实际数据</small></div><pre>{{ formatJson(selectedTraceNode.output) }}</pre></section></div>
                    </div>
                  </div>
                </el-tab-pane>
                <el-tab-pane v-if="false && detail" name="release" label="发布管理">
                  <div class="release-layout">
                    <div class="release-summary"><h3>发布前检查</h3><div class="release-version"><span>V{{ detail?.summary.publishedVersion ?? '-' }}</span><Promotion/><strong>V{{ detail?.summary.currentVersion }}</strong></div>
                      <div class="release-checks"><button v-for="step in apiGuideSteps.slice(0,5)" :key="step.key" class="api-release-check" :class="{done:step.done}" :disabled="guideBusy" @click="openGuideStep(step.key)"><component :is="step.done ? CircleCheck : Warning"/><span><strong>{{ step.title }}</strong><small>{{ step.hint }}</small></span><ArrowRight/></button></div>
                      <p class="api-field-help">以上是已保存配置的检查提示；可点击返回修正。最终配置合法性以发布前校验结果为准。</p>
                    </div>
                    <div class="release-form">
                      <label>发布说明</label><el-input v-model="releaseNote" type="textarea" :rows="4" placeholder="例如：新增企业查询接口，完成请求 / 响应映射与配置校验" :disabled="detail?.summary.status==='PENDING_APPROVAL'"/>
                      <div class="release-note">保存草稿 → 配置校验 → 上线 → 调用系统授权。链路测试可在上线前后按需执行，不构成发布条件。已发布过的 API 为保留版本、授权和调用日志，不再允许物理删除；停用时应撤销授权并走接口下线流程。</div>
                      <div class="release-actions"><el-button :icon="DocumentChecked" :disabled="guideBusy" @click="validateConfig">发布前校验</el-button></div>
                      <div v-if="validation" class="validation-result" :class="validation?.valid ? 'valid' : 'invalid'"><el-icon><component :is="validation?.valid ? CircleCheck : Warning"/></el-icon><div><strong>{{ validation?.valid ? '已保存草稿校验通过' : '配置校验未通过，请修正后重试' }}</strong><span v-for="item in [...(validation?.errors ?? []),...(validation?.warnings ?? [])]" :key="item">{{ item }}</span></div></div>
                      <div v-if="detail?.summary.status==='PENDING_APPROVAL'" class="api-release-followup"><strong>已提交，等待审批 · 尚未上线</strong><p>当前草稿 V{{ detail?.summary.currentVersion }} 已进入审批队列{{ publishResult ? '，审批单 ' + publishResult?.id : '' }}。请在审批中心查看处理结果；通过后再为调用系统授权。</p><el-button type="primary" @click="navigate('approvals')">前往审批中心</el-button><el-button :disabled="loading" @click="loadAll">刷新审批状态</el-button></div>
                      <div v-else-if="!hasUnpublishedChanges" class="api-release-followup"><strong>当前版本已发布</strong><p>下一步为机构 / 调用系统配置 API 授权和配额。已有授权无需重复创建；使用正式链路发起请求后，到调用日志核对执行结果。</p><el-button type="primary" @click="navigate('authorization')">前往服务授权</el-button><el-button @click="navigate('logs')">查看调用日志</el-button></div>
                      <div v-else class="api-release-followup"><strong>草稿准备提交</strong><p>{{ currentPreReleaseVerified ? '当前草稿已完成可选链路测试，可直接上线。' : '尚未执行草稿链路测试；该测试仅用于辅助检查，不影响直接上线。' }}</p><el-button v-if="!currentPreReleaseVerified" @click="openGuideStep('test')">前往可选链路测试</el-button></div>
                    </div>
                  </div>
                  <section id="interface-version-history" class="version-history-panel">
                    <header><div><span aria-hidden="true" class="decorative-eyebrow"></span><h3>接口版本历史</h3><p>发布管理保存接口的草稿、审批和线上版本；审批中心仅负责处理审批待办。</p></div><el-tag type="info">{{ interfaceVersions.length }} 个版本</el-tag></header>
                    <el-alert v-if="interfaceVersions.some(item => !item.historyComplete)" title="检测到旧版本记录不完整" type="warning" :closable="false" description="接口表保留了已发布版本号，但旧审批单、发布记录或执行计划快照缺失。版本仍展示并阻止物理删除，建议后续补录历史档案。"/>
                    <el-table :data="interfaceVersions" border :row-class-name="versionRowClass">
                      <el-table-column label="版本" width="92"><template #default="{row}"><strong>V{{ row.version }}</strong></template></el-table-column>
                      <el-table-column label="版本状态" width="180"><template #default="{row}"><el-tag :type="versionTagType(row)">{{ versionStatusText(row) }}</el-tag><small v-if="row.active" class="version-active-label">流量使用中</small></template></el-table-column>
                      <el-table-column prop="releaseNote" label="发布说明" min-width="220"><template #default="{row}">{{ row.releaseNote || (row.historyComplete ? '未填写' : '旧数据未保留') }}</template></el-table-column>
                      <el-table-column label="申请 / 审批" min-width="180"><template #default="{row}"><span>{{ row.applicant || '-' }}</span><small class="version-cell-sub">{{ row.reviewedBy || row.releasedBy || '-' }}</small></template></el-table-column>
                      <el-table-column label="发布时间" width="180"><template #default="{row}">{{ row.releasedAt || row.reviewedAt || row.submittedAt || '-' }}</template></el-table-column>
                      <el-table-column label="执行计划" width="112"><template #default="{row}"><el-tag size="small" :type="row.snapshotAvailable ? 'success' : 'info'">{{ row.snapshotAvailable ? '已留存' : '无快照' }}</el-tag></template></el-table-column>
                      <el-table-column label="操作" width="92"><template #default="{row}"><el-button text type="primary" @click="showVersionDetail(row)">查看</el-button></template></el-table-column>
                    </el-table>
                  </section>
                </el-tab-pane>
              </el-tabs>
              <footer class="resource-action-bar">
                <div><span class="resource-action-dot" :class="resourceEditorState.key"></span><div class="api-save-state"><strong>{{ resourceEditorState.label }}</strong><small>{{ resourceEditorState.hint }}</small></div></div>
                <div class="api-footer-actions">
                  <template v-if="serviceDevelopmentStage==='UPSTREAM'">
                    <el-button :loading="connectionSaving" :disabled="testRunning || detailLoading" @click="saveConnectionConfig">保存配置</el-button>
                    <el-button :loading="testRunning" :disabled="connectionSaving || !previewUpstreamUrl" @click="validateUpstreamCapability">测试接口</el-button>
                    <el-tooltip :disabled="!upstreamSaveRequired" content="请先保存当前配置" placement="top"><span><el-button type="primary" :disabled="testRunning || connectionSaving || upstreamSaveRequired" @click="openPlatformServiceDialog">创建标准服务</el-button></span></el-tooltip>
                  </template>
                  <template v-else>
                    <el-button :type="activeTab==='test' ? 'primary' : 'default'" :loading="guideBusy || testRunning || connectionSaving" :disabled="resourcePrimaryActionDisabled || deletingApi || detailLoading" @click="runCurrentPrimaryAction">{{ resourcePrimaryActionLabel }}</el-button>
                    <el-button v-if="activeTab==='test'" type="primary" :disabled="guideBusy || testRunning || connectionSaving" @click="selectServiceDevelopmentStage('RELEASE')">进入发布</el-button>
                  </template>
                </div>
              </footer>
            </div>
            <div v-else class="empty-editor" v-loading="detailLoading">
              <template v-if="detailLoading"><el-icon><Refresh /></el-icon><strong>正在加载接口配置</strong><span>加载完成前不会保留上一接口的映射数据</span></template>
              <template v-else><el-icon><Files /></el-icon><strong>{{ activeMenu==='resource' ? '该数据源暂无资源 API' : '该数据源暂无平台标准服务' }}</strong><span>{{ activeMenu==='resource' ? '先完善数据源信息，再定义资源提供商的原始请求与响应接口' : '请从已保存的资源 API 创建平台标准服务' }}</span><div v-if="activeMenu==='resource' && selectedSource" class="empty-editor-actions"><el-button :icon="Setting" @click="openSourceSettingsEditor">编辑数据源</el-button><el-button type="primary" :icon="Plus" @click="openApiDialog">新增资源 API</el-button></div><el-button v-else-if="activeMenu!=='resource'" type="primary" @click="navigate('resource')">前往数据资源接入</el-button></template>
            </div>
          </section>
        </template>

        <template v-else-if="activeMenu === 'authorization'">
          <section class="authorization-overview"><div class="plain-panel"><div class="panel-heading"><div><strong>调用系统</strong><span>{{ clients.length }} 个系统 · {{ validAuthorizations.length }} 条有效授权</span></div><el-button :icon="Plus" @click="openClientRegistration()">登记系统</el-button></div><template v-if="clients.length"><el-table :data="clients"><el-table-column prop="clientName" label="系统名称" min-width="160"/><el-table-column prop="clientCode" label="系统编码" min-width="130"/><el-table-column prop="institutionName" label="所属机构" min-width="160"/><el-table-column prop="ownerName" label="负责人" width="90"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '有效' : '停用' }}</el-tag></template></el-table-column></el-table></template><div v-else class="authorization-empty"><div class="authorization-empty-icon"><UserIcon /></div><strong>暂无调用系统</strong><span>先登记行内系统，才能为 API 配置访问授权。</span><el-button type="primary" plain :icon="Plus" @click="openClientRegistration()">登记第一个调用系统</el-button></div></div></section>
          <section class="plain-panel table-panel authorization-list-panel"><div class="panel-heading"><div><strong>API 授权清单</strong><span>授权即时生效，无需重启 Gateway 或数据源应用</span></div><div class="panel-heading-actions"><el-button :icon="Plus" @click="openBatchAuthorization">批量授权</el-button><el-button type="primary" :icon="Plus" @click="openAuthDialog">新增授权</el-button></div></div><div class="list-filter-bar"><el-input v-model="authorizationSearch" :prefix-icon="Search" clearable placeholder="搜索系统、API 名称或编码" aria-label="搜索授权"/><el-select v-model="authorizationClient" filterable aria-label="按调用系统筛选"><el-option label="全部调用系统" :value="0"/><el-option v-for="client in clients" :key="client.id" :label="client.clientName" :value="client.id"/></el-select><span>{{ filteredAuthorizations.length }} 条授权</span></div><el-table :data="pagedAuthorizations" border empty-text="暂无匹配的授权记录"><el-table-column prop="clientName" label="调用系统" min-width="160"/><el-table-column prop="interfaceName" label="授权 API" min-width="170"/><el-table-column prop="interfaceCode" label="接口编码" min-width="150"/><el-table-column prop="environment" label="环境" width="90"><template #default="{ row }"><el-tag :type="row.environment==='PROD'?'danger':'info'">{{ row.environment }}</el-tag></template></el-table-column><el-table-column prop="dailyQuota" label="日配额" width="110"/><el-table-column label="有效期" min-width="190"><template #default="{ row }">{{ row.validFrom }} 至 {{ row.validTo }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="authorizationStatus(row).type">{{ authorizationStatus(row).text }}</el-tag></template></el-table-column></el-table><el-pagination v-if="filteredAuthorizations.length>20" class="list-pagination" v-model:current-page="authorizationPage" :page-size="20" :total="filteredAuthorizations.length" layout="total, prev, pager, next"/></section>
        </template>

        <template v-else-if="activeMenu === 'billing'">
          <section class="billing-metrics"><div><span>本期调用量</span><strong>{{ billingSummary.totalCalls }}</strong><small>测试环境累计</small></div><div><span>可计费调用</span><strong>{{ billingSummary.billableCalls }}</strong><small>命中且符合规则</small></div><div><span>命中 / 未查得</span><strong>{{ billingSummary.hitCalls }} / {{ billingSummary.missCalls }}</strong><small>按统一结果状态统计</small></div><div class="amount"><span>本期预估费用</span><strong>{{ money(billingSummary.totalAmount) }}</strong><small>币种：人民币</small></div></section>
          <section class="plain-panel table-panel"><div class="panel-heading"><div><strong>接口计费规则</strong><span>平台标准结果决定是否计费，避免依赖供应商原始码</span></div><el-button type="primary" :icon="Plus" @click="openBillingDialog()">配置计费</el-button></div><el-table :data="billingRules" border><el-table-column prop="interfaceName" label="API" min-width="170"/><el-table-column prop="interfaceCode" label="接口编码" min-width="160"/><el-table-column label="计费方式" width="130"><template #default>按成功次数</template></el-table-column><el-table-column label="单价" width="110"><template #default="{ row }">¥ {{ Number(row.unitPrice).toFixed(4) }}</template></el-table-column><el-table-column label="HIT / MISS" width="140"><template #default="{ row }"><el-tag :type="row.hitBillable?'success':'info'">{{ row.hitBillable?'计费':'免费' }}</el-tag> / <el-tag :type="row.missBillable?'success':'info'">{{ row.missBillable?'计费':'免费' }}</el-tag></template></el-table-column><el-table-column prop="effectiveDate" label="生效日期" width="120"/><el-table-column width="90"><template #default="{ row }"><el-button text type="primary" @click="openBillingDialog(row)">编辑</el-button></template></el-table-column></el-table></section>
        </template>

        <template v-else-if="activeMenu === 'logs'">
          <section class="log-metrics"><div><span>当前结果集</span><strong>{{ callLogs.length }}</strong><small>最多展示最近 200 条</small></div><div><span>成功调用</span><strong class="success-text">{{ callLogs.length - failedCallCount }}</strong><small>链路完整结束</small></div><div><span>失败调用</span><strong class="danger-text">{{ failedCallCount }}</strong><small>需排查失败节点</small></div><div><span>平均耗时</span><strong>{{ callLogs.length ? Math.round(callLogs.reduce((sum,item)=>sum+item.durationMs,0)/callLogs.length) : 0 }} ms</strong><small>当前查询范围</small></div></section>
          <section class="plain-panel log-query"><div class="log-query-head"><div><strong>调用检索</strong><span>使用 Trace ID 可精确定位一次完整调用</span></div><el-tag type="info" effect="plain">日志规范 v1.0</el-tag></div><div class="log-query-grid"><el-input v-model="callLogFilters.keyword" :prefix-icon="Search" clearable placeholder="Trace ID / 接口名称 / 调用系统" @keyup.enter="searchCallLogs"/><el-select v-model="callLogFilters.status"><el-option label="全部状态" value="ALL"/><el-option label="成功" value="SUCCESS"/><el-option label="失败" value="FAILED"/></el-select><el-select v-model="callLogFilters.interfaceCode" clearable placeholder="全部接口"><el-option v-for="item in allInterfaces" :key="item.id" :label="`${item.interfaceName} · ${item.interfaceCode}`" :value="item.interfaceCode"/></el-select><el-button type="primary" :icon="Search" @click="searchCallLogs">查询</el-button><el-button @click="resetCallLogFilters">重置</el-button></div></section>
          <section class="plain-panel table-panel call-log-table"><div class="panel-heading"><div><strong>API 调用日志</strong><span>摘要字段用于快速检索，大报文仅在打开链路详情时加载</span></div></div><el-table :data="callLogs" border><el-table-column label="调用时间" prop="calledAt" width="175"/><el-table-column label="Trace ID" min-width="245"><template #default="{row}"><code>{{ row.traceId }}</code></template></el-table-column><el-table-column label="接口" min-width="220"><template #default="{row}"><div class="call-main"><strong>{{ row.interfaceName }}</strong><span>{{ row.interfaceCode }}</span></div></template></el-table-column><el-table-column prop="clientName" label="调用系统" width="150"/><el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="row.requestStatus==='SUCCESS'&&row.resultStatus!=='ERROR'?'success':'danger'">{{ row.requestStatus==='SUCCESS'&&row.resultStatus!=='ERROR'?'成功':'失败' }}</el-tag></template></el-table-column><el-table-column label="标准返回码" prop="standardCode" width="165"/><el-table-column label="总耗时" width="100"><template #default="{row}"><strong :class="row.durationMs>1000?'warning-text':''">{{ row.durationMs }} ms</strong></template></el-table-column><el-table-column label="操作" width="110" fixed="right"><template #default="{row}"><el-button text type="primary" @click="openCallTrace(row)">查看链路</el-button></template></el-table-column></el-table></section>
        </template>

        <template v-else-if="activeMenu === 'monitor'">
          <section class="monitor-command"><div class="monitor-health" :class="{warning:monitorSummary.activeAlerts>0}"><span><CircleCheck v-if="!monitorSummary.activeAlerts"/><Warning v-else/></span><div><small>REAL-TIME OPERATIONS</small><strong>{{ monitorSummary.activeAlerts ? '存在调用质量告警' : '当前暂无活动告警' }}</strong><p>基于已采集调用记录，查看耗时、异常与节点分布</p></div></div><div class="monitor-command-facts"><span>采集点<b>{{ monitorPoints.length }}</b></span><span>链路节点<b>3</b></span><span>活动告警<b :class="{danger:monitorSummary.activeAlerts}">{{ monitorSummary.activeAlerts }}</b></span></div><div class="monitor-command-actions"><button @click="openFailedLogs"><Warning/><span><strong>排查失败调用</strong><small>{{ monitorSummary.errorCalls }} 条待分析</small></span><ArrowRight/></button><button @click="navigate('logs')"><Search/><span><strong>检索 Trace ID</strong><small>进入调用日志</small></span><ArrowRight/></button><button @click="refreshMonitor"><Refresh/><span><strong>刷新快照</strong><small>更新全部指标</small></span></button></div></section>

          <section class="monitor-kpis"><div><span>近24小时调用</span><strong>{{ monitorSummary.totalCalls }}</strong><small>全链路请求</small><DataAnalysis/></div><div><span>调用成功率</span><strong :class="monitorSummary.successRate<99?'danger-text':'success-text'">{{ monitorSummary.successRate }}%</strong><small>{{ monitorSummary.errorCalls }} 次异常</small><CircleCheck/></div><div><span>端到端平均耗时</span><strong>{{ monitorSummary.avgDurationMs }}<em> ms</em></strong><small>平台 + 供应商</small><Connection/></div><div><span>P95 响应耗时</span><strong :class="monitorSummary.p95DurationMs>1000?'warning-text':''">{{ monitorSummary.p95DurationMs }}<em> ms</em></strong><small>SLA &lt; 1000 ms</small><DataAnalysis/></div><div><span>上游平均耗时</span><strong>{{ monitorSummary.avgUpstreamDurationMs }}<em> ms</em></strong><small>占比 {{ Math.round(monitorSummary.avgUpstreamDurationMs*100/Math.max(1,monitorSummary.avgDurationMs)) }}%</small><OfficeBuilding/></div><div><span>活动告警</span><strong :class="monitorSummary.activeAlerts?'danger-text':'success-text'">{{ monitorSummary.activeAlerts }}</strong><small>实时阈值检测</small><Warning/></div></section>

          <section class="monitor-main-grid">
            <div class="monitor-panel monitor-latency-panel"><header><div><span>LATENCY ANALYSIS</span><strong>调用耗时趋势</strong><small>端到端与上游供应商耗时对比</small></div><div class="monitor-legend"><i></i>端到端<b></b>上游</div></header><div class="monitor-latency-chart" :class="{inspecting:activeMonitorPointIndex!==null}"><div class="monitor-chart-grid"><i></i><i></i><i></i><i></i></div><button v-for="(point,index) in monitorTimeline" :key="point.timeLabel" type="button" class="monitor-latency-column" :class="{active:activeMonitorPointIndex===index}" :aria-label="`${point.timeLabel}，端到端 ${point.avgDurationMs} 毫秒，上游 ${point.avgUpstreamDurationMs} 毫秒`" @mouseenter="activeMonitorPointIndex=index" @mouseleave="activeMonitorPointIndex=null" @focus="activeMonitorPointIndex=index" @blur="activeMonitorPointIndex=null"><div><i :style="{height:`${Math.max(6,point.avgDurationMs/monitorMaxDuration*100)}%`}"></i><b :style="{height:`${Math.max(4,point.avgUpstreamDurationMs/monitorMaxDuration*100)}%`}"></b></div><span>{{ point.timeLabel }}</span></button><div v-if="activeMonitorPoint" class="monitor-chart-tooltip" :style="{left:`${((activeMonitorPointIndex??0)+.5)/Math.max(1,monitorTimeline.length)*100}%`,transform:activeMonitorPointIndex===0?'translateX(0)':activeMonitorPointIndex===monitorTimeline.length-1?'translateX(-100%)':'translateX(-50%)'}"><header><strong>{{ activeMonitorPoint.timeLabel }}</strong><span>{{ activeMonitorPoint.calls }} 次调用</span></header><div><span>端到端<b>{{ activeMonitorPoint.avgDurationMs }} ms</b></span><span>上游供应商<b>{{ activeMonitorPoint.avgUpstreamDurationMs }} ms</b></span><span>平台处理<b>{{ Math.max(0,activeMonitorPoint.avgDurationMs-activeMonitorPoint.avgUpstreamDurationMs) }} ms</b></span><span>异常调用<b class="error">{{ activeMonitorPoint.errorCalls }}</b></span></div></div><div v-if="!monitorTimeline.length" class="chart-empty">暂无近 24 小时调用数据</div></div><footer><span>平均耗时<b>{{ monitorSummary.avgDurationMs }} ms</b></span><span>P95 耗时<b>{{ monitorSummary.p95DurationMs }} ms</b></span><span>SLA 达标<b :class="monitorSummary.p95DurationMs<1000?'success-text':'danger-text'">{{ monitorSummary.p95DurationMs<1000?'是':'否' }}</b></span></footer></div>
            <div class="monitor-panel monitor-topology-panel"><header><div><span>CHAIN TOPOLOGY</span><strong>链路节点性能</strong><small>选择节点查看耗时贡献与运行状态</small></div><b>LIVE</b></header><div class="monitor-topology"><template v-for="(node,index) in monitorTopologyNodes" :key="node.key"><button type="button" :class="[{active:activeMonitorNodeKey===node.key},node.status.toLowerCase()]" @click="activeMonitorNodeKey=node.key"><span><component :is="node.icon"/></span><strong>{{ node.name }}</strong><small>{{ node.duration }} ms</small></button><i v-if="index<monitorTopologyNodes.length-1"><b></b></i></template></div><div v-if="activeMonitorNode" class="monitor-node-inspector"><div><span>{{ activeMonitorNode.code }}</span><strong>{{ activeMonitorNode.name }}</strong><small>{{ activeMonitorNode.description }}</small></div><div><span>平均耗时<strong>{{ activeMonitorNode.duration }} ms</strong></span><span>链路占比<strong>{{ activeMonitorNode.share }}%</strong></span><span>节点状态<strong :class="activeMonitorNode.status.toLowerCase()">{{ activeMonitorNode.status==='HEALTHY'?'正常':'关注' }}</strong></span></div></div></div>
          </section>

          <section class="monitor-alert-workspace"><header><div><span>ALERT CENTER</span><strong>运行告警与处置建议</strong></div><b>{{ monitorAlerts.length }} 项</b></header><div v-if="monitorAlerts.length" class="monitor-alert-body"><div class="monitor-alert-list"><button v-for="(alert,index) in monitorAlerts" :key="`${alert.title}-${index}`" :class="[alert.level.toLowerCase(),{active:selectedMonitorAlertIndex===index}]" @click="selectedMonitorAlertIndex=index"><span><Warning/></span><div><strong>{{ alert.title }}</strong><small>{{ alert.detail }}</small></div><time>{{ alert.timeLabel }}</time><ArrowRight/></button></div><div v-if="selectedMonitorAlert" class="monitor-alert-detail"><span :class="selectedMonitorAlert.level.toLowerCase()">{{ selectedMonitorAlert.level }}</span><h3>{{ selectedMonitorAlert.title }}</h3><p>{{ selectedMonitorAlert.detail }}</p><div><span>检测时间<b>{{ selectedMonitorAlert.timeLabel }}</b></span><span>影响范围<b>调用质量 / SLA</b></span></div><footer><el-button @click="refreshMonitor" :icon="Refresh">重新检测</el-button><el-button type="primary" @click="openFailedLogs" :icon="DataAnalysis">查看相关失败调用</el-button></footer></div></div><div v-else class="monitor-alert-empty"><CircleCheck/><strong>当前无活动告警</strong><span>所有节点均运行在配置阈值范围内</span></div></section>

          <section class="monitor-panel monitor-call-panel"><header><div><span>TRACE SAMPLES</span><strong>全链路调用明细</strong><small>定位异常、慢调用及各节点耗时贡献</small></div><div class="monitor-record-filters"><button :class="{active:monitorRecordFilter==='ALL'}" @click="monitorRecordFilter='ALL'">全部 {{ usageRecords.length }}</button><button :class="{active:monitorRecordFilter==='ERROR'}" @click="monitorRecordFilter='ERROR'">异常 {{ monitorErrorRecordCount }}</button><button :class="{active:monitorRecordFilter==='SLOW'}" @click="monitorRecordFilter='SLOW'">慢调用 {{ monitorSlowRecordCount }}</button></div></header><el-table :data="filteredMonitorRecords" class="monitor-call-table"><el-table-column label="调用信息" min-width="250"><template #default="{row}"><div class="call-main"><strong>{{ row.interfaceName }}</strong><span>{{ formatDateTime(row.calledAt) }} · {{ row.clientName || '联调工作台' }}</span><code>{{ row.traceId }}</code></div></template></el-table-column><el-table-column label="结果" width="130"><template #default="{row}"><el-tag :type="row.resultStatus==='HIT'?'success':row.resultStatus==='MISS'?'info':'danger'">{{ row.resultStatus }}</el-tag><small class="code-caption">{{ row.standardCode }}</small></template></el-table-column><el-table-column label="节点耗时贡献" min-width="320"><template #default="{row}"><div class="duration-breakdown"><div><span>平台处理</span><i><b :style="{width:`${Math.min(100,row.gatewayDurationMs/Math.max(1,row.durationMs)*100)}%`}"></b></i><strong>{{ row.gatewayDurationMs }} ms</strong></div><div><span>上游供应商</span><i><b class="vendor" :style="{width:`${Math.min(100,row.upstreamDurationMs/Math.max(1,row.durationMs)*100)}%`}"></b></i><strong>{{ row.upstreamDurationMs }} ms</strong></div></div></template></el-table-column><el-table-column label="总耗时" width="100"><template #default="{row}"><strong class="total-duration" :class="{slow:row.durationMs>=monitorSummary.p95DurationMs}">{{ row.durationMs }} ms</strong></template></el-table-column><el-table-column label="最终节点" min-width="150"><template #default="{row}"><code>{{ row.nodeName }}</code></template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button text type="primary" @click="openCallTrace(row)">查看链路</el-button></template></el-table-column></el-table></section>
        </template>
      </el-main>
    </el-container>
  </el-container>

  <el-drawer v-model="enumDrawer" size="600px" class="enum-config-drawer" destroy-on-close>
    <template #header><div class="enum-drawer-title"><span>ENUM TRANSFORM</span><strong>配置枚举转换</strong><small>{{ editingEnumMapping?.sourcePath || '来源字段' }} → {{ editingEnumMapping?.targetPath || '目标字段' }}</small><b :class="editingEnumMapping?.direction==='RESPONSE'?'response':'request'">{{ editingEnumMapping?.direction==='RESPONSE' ? '供应商返回 → 行内标准' : '行内请求 → 供应商请求' }}</b></div></template>
    <div v-if="editingEnumMapping" class="enum-drawer-body">
      <section v-if="editingEnumMapping.direction==='REQUEST'" class="enum-direction-warning"><div><strong>当前是“发给供应商”的请求适配</strong><p>这里不会处理供应商返回数据。若目标是将供应商返回值转换为行内标准值，请切换到“返回给行内”。</p></div><el-button type="warning" plain @click="switchToResponseMappings">切换到返回给行内</el-button></section>
      <section class="enum-explanation"><strong>转换逻辑</strong><p v-if="editingEnumMapping.direction==='REQUEST'">请求映射：当行内标准字段等于“行内标准原值”时，转换为供应商接口要求的“供应商目标值”。</p><p v-else>响应标准化：当供应商响应字段等于“供应商原值”时，转换为平台统一的“行内标准值”。</p><p>枚举未命中时优先使用默认值，未配置默认值则保留原值。</p></section>
      <section class="enum-pair-editor"><header><span>{{ editingEnumMapping.direction==='REQUEST' ? '行内标准原值' : '供应商原值' }}</span><i></i><span>{{ editingEnumMapping.direction==='REQUEST' ? '供应商目标值' : '行内标准值' }}</span><b>操作</b></header><div v-for="pair in enumPairs" :key="pair.id" class="enum-pair-row"><el-input v-model="pair.sourceValue" clearable :placeholder="editingEnumMapping.direction==='REQUEST' ? '例：MALE' : '例：1'"/><span>→</span><el-input v-model="pair.targetValue" clearable :placeholder="editingEnumMapping.direction==='REQUEST' ? '例：1' : '例：MALE'"/><el-button :icon="Delete" text type="danger" aria-label="删除枚举项" @click="removeEnumPair(pair.id)"/></div><div v-if="!enumPairs.length" class="enum-pair-empty">枚举项已清空，可新增规则或直接应用空配置</div><el-button class="enum-add-pair" :icon="Plus" plain @click="addEnumPair()">新增枚举项</el-button></section>
      <section class="enum-default"><label>未匹配默认值 <small>可选</small></label><el-input v-model="enumDefaultValue" clearable :placeholder="editingEnumMapping.direction==='REQUEST' ? '例如 0；留空时保留行内请求原值' : '例如 UNKNOWN；留空时保留供应商原值'"/><p>该默认值仅在{{ editingEnumMapping.direction==='REQUEST' ? '行内请求值' : '供应商返回值' }}未命中任何枚举规则时使用。</p></section>
    </div>
    <template #footer><div class="enum-drawer-footer"><span>已配置 {{ enumPairs.filter(item=>item.sourceValue.trim()&&item.targetValue.trim()).length }} 条有效规则</span><el-button @click="enumDrawer=false">取消</el-button><el-button type="primary" @click="applyEnumConfig">应用配置</el-button></div></template>
  </el-drawer>

  <el-drawer v-model="algorithmDrawer" size="520px" class="algorithm-config-drawer" destroy-on-close>
    <template #header><div class="algorithm-drawer-title"><span>SECURITY TRANSFORM</span><strong>{{ editingSecurityMapping?.transformType === 'ENCRYPT' ? '配置字段加密' : '配置字段签名' }}</strong><small>{{ editingSecurityMapping?.sourcePath || '来源字段' }} → {{ editingSecurityMapping?.targetPath || '目标字段' }}</small></div></template>
    <div v-if="editingSecurityMapping" class="algorithm-drawer-body">
      <section class="algorithm-picker"><label>选择算法</label><el-select :model-value="editingSecurityMapping.transformConfig.mode" filterable placeholder="输入名称或编码搜索算法" @change="selectSecurityAlgorithm(String($event))"><el-option-group v-for="group in algorithmGroupsForEditing" :key="group.category" :label="group.category"><el-option v-for="item in group.algorithms" :key="item.code" :label="item.name" :value="item.code"><span>{{ item.name }}</span><small>{{ item.code }}</small></el-option></el-option-group></el-select><p>算法由后台注册表提供，扩展算法不会增加主页面复杂度。</p></section>
      <section v-if="selectedSecurityAlgorithm" class="algorithm-detail-card"><header><div><span>{{ selectedSecurityAlgorithm.category }}</span><strong>{{ selectedSecurityAlgorithm.name }}</strong></div><el-tag :type="selectedSecurityAlgorithm.builtIn?'primary':'success'" effect="plain">{{ selectedSecurityAlgorithm.builtIn ? '平台内置' : '扩展算法' }}</el-tag></header><p>{{ selectedSecurityAlgorithm.description }}</p></section>
      <el-form v-if="selectedSecurityAlgorithm" label-position="top" class="algorithm-parameter-form"><el-form-item v-for="parameter in selectedSecurityAlgorithm.parameters" :key="parameter.name" :label="parameter.label" :required="parameter.required"><el-select v-if="parameter.type==='SELECT'" v-model="editingSecurityMapping.transformConfig[parameter.name]" placeholder="请选择"><el-option v-for="option in parameter.options" :key="option" :label="option" :value="option"/></el-select><el-input v-else v-model="editingSecurityMapping.transformConfig[parameter.name]" :placeholder="parameter.placeholder"/><small v-if="parameter.type==='SECRET_REFERENCE'">仅填写 config://、env:// 或 sys:// 引用，页面不会接触密钥明文。</small></el-form-item></el-form>
      <el-empty v-else :description="securityAlgorithms.length ? '请选择一种算法' : (securityAlgorithmLoadError || '算法目录尚未加载，请确认管理服务已更新')"/>
    </div>
    <template #footer><div class="algorithm-drawer-footer"><span :class="{ready:editingSecurityMapping && algorithmConfigured(editingSecurityMapping)}">{{ editingSecurityMapping && algorithmConfigured(editingSecurityMapping) ? '配置完整' : '请完成必填参数' }}</span><el-button @click="algorithmDrawer=false">取消</el-button><el-button type="primary" :disabled="!editingSecurityMapping || !algorithmConfigured(editingSecurityMapping)" @click="algorithmDrawer=false">应用配置</el-button></div></template>
  </el-drawer>

  <el-drawer v-model="callTraceDrawer" size="78%" class="call-trace-drawer" destroy-on-close><template #header><div class="drawer-trace-title"><div><span>TRACE DETAIL</span><strong>{{ callTrace?.summary.interfaceName || activeCallTraceRecord?.interfaceName || '调用链路详情' }}</strong><code>{{ callTrace?.summary.traceId || activeCallTraceRecord?.traceId || '-' }}</code></div><el-tag v-if="callTrace" :type="callTrace.summary.requestStatus==='SUCCESS'&&callTrace.summary.resultStatus!=='ERROR'?'success':'danger'" effect="dark">{{ callTrace.summary.requestStatus==='SUCCESS'&&callTrace.summary.resultStatus!=='ERROR'?'调用成功':'调用失败' }}</el-tag></div></template><div v-loading="callTraceLoading" class="persisted-trace"><template v-if="callTrace"><section class="trace-facts"><div><span>调用系统</span><strong>{{ callTrace.summary.clientName || '联调工作台' }}</strong></div><div><span>标准返回码</span><strong>{{ callTrace.summary.standardCode }}</strong></div><div><span>端到端耗时</span><strong>{{ callTrace.summary.durationMs }} ms</strong></div><div><span>调用时间</span><strong>{{ callTrace.summary.calledAt }}</strong></div></section><section class="persisted-trace-track"><template v-for="(node,index) in callTrace.nodes" :key="node.key"><button class="persisted-node" :class="[node.status.toLowerCase(),{active:selectedLogNodeKey===node.key}]" @click="selectedLogNodeKey=node.key"><span><CircleCheck v-if="node.status==='SUCCESS'"/><Warning v-else/></span><strong>{{ node.name }}</strong><small>{{ node.durationMs }} ms</small></button><div v-if="index<callTrace.nodes.length-1" class="trace-channel" :class="{failed:node.status!=='SUCCESS'}"><i></i></div></template></section><section v-if="logSelectedNode" class="persisted-node-detail"><header><div><span>{{ logSelectedNode.category }} / {{ logSelectedNode.key }}</span><strong>{{ logSelectedNode.name }}</strong><small>{{ logSelectedNode.detail }}</small></div><div><el-tag :type="logSelectedNode.status==='SUCCESS'?'success':'danger'">{{ logSelectedNode.status }}</el-tag><strong>{{ logSelectedNode.durationMs }} ms</strong></div></header><div><section><label>节点输入</label><pre>{{ formatJson(logSelectedNode.input) }}</pre></section><section><label>节点输出</label><pre>{{ formatJson(logSelectedNode.output) }}</pre></section></div></section></template><el-result v-else-if="callTraceError" icon="error" title="链路详情加载失败" :sub-title="callTraceError"><template #extra><el-button v-if="activeCallTraceRecord" type="primary" @click="openCallTrace(activeCallTraceRecord)">重新加载</el-button></template></el-result><el-empty v-else-if="!callTraceLoading" description="暂无可展示的链路详情"/></div></el-drawer>

  <el-dialog v-model="sourceDialog" title="接入数据源" width="680px" class="source-wizard" :close-on-click-modal="false" :close-on-press-escape="!sourceSaving" :show-close="!sourceSaving" destroy-on-close><el-steps :active="sourceStep" finish-status="success" simple><el-step title="基本信息"/><el-step title="连接配置"/></el-steps><el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" show-icon/><div class="wizard-body" v-if="sourceStep===0"><el-form label-position="top"><div class="form-grid"><el-form-item label="资源提供商" required><el-select v-model="sourceForm.providerId"><el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="item.id"/></el-select></el-form-item><el-form-item label="数据源名称" required><el-input v-model="sourceForm.sourceName" placeholder="例如：企业工商信息" maxlength="100"/></el-form-item><el-form-item label="数据源编码" required><el-input v-model="sourceForm.sourceCode" placeholder="例如：ENTERPRISE_INFO" maxlength="64"/></el-form-item><el-form-item label="协议类型"><el-select v-model="sourceForm.protocolType"><el-option label="HTTP JSON" value="HTTP_JSON"/><el-option label="WebService" value="SOAP"/><el-option label="本地数据集" value="LOCAL_SQL"/></el-select></el-form-item></div></el-form></div><div class="wizard-body" v-else><el-form label-position="top"><el-form-item label="资源提供商基础地址"><el-input v-model="sourceForm.endpoint" placeholder="https://provider.example.com"/><small class="form-hint">接口默认复用该基础地址；特殊接口可以单独覆盖</small></el-form-item><el-form-item label="数据源默认超时"><el-input-number v-model="sourceForm.defaultTimeoutMs" :min="100" :max="120000" :step="500"/><small class="form-hint">该数据源下新接口默认继承此值</small></el-form-item><details class="advanced-settings"><summary>处理应用路由 <span>可稍后配置，上线前必填</span></summary><div class="form-grid"><el-form-item label="处理应用标识"><el-input v-model="sourceForm.applicationName" :placeholder="`默认 jsnx-edsp-data-source-${sourceForm.sourceCode.toLowerCase().replace(/_/g,'-')}`"/></el-form-item><el-form-item label="运行地址 / 行内 VIP"><el-input v-model="sourceForm.runtimeUrl" placeholder="http://127.0.0.1:8185"/></el-form-item></div><small class="form-hint">允许接入阶段暂不填写；平台服务上线前必须完成配置。生产环境填写该数据源应用的行内 VIP，不填写单实例地址。</small></details><p class="quiet-note">创建后配置具体接口及报文，通过“接入验证”发起真实调用。</p></el-form></div><template #footer><el-button :disabled="sourceSaving" @click="sourceDialog=false">取消</el-button><el-button :disabled="sourceSaving" v-if="sourceStep>0" @click="sourceStep--">上一步</el-button><el-button v-if="sourceStep<1" type="primary" @click="advanceSourceWizard">下一步</el-button><el-button v-else type="primary" :loading="sourceSaving" @click="createSource">创建并配置接口</el-button></template></el-dialog>
  <el-dialog v-model="sourceSettingsDialog" title="编辑数据源" width="680px" :close-on-click-modal="false"><el-alert v-if="sourceSettingsError" :title="sourceSettingsError" type="error" :closable="false" show-icon/><el-form label-position="top" style="margin-top:18px"><div class="form-grid"><el-form-item label="资源提供商" required><el-select v-model="sourceSettingsForm.providerId" filterable><el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="item.id"/></el-select></el-form-item><el-form-item label="数据源名称" required><el-input v-model="sourceSettingsForm.sourceName" maxlength="100"/></el-form-item><el-form-item label="数据源编码"><el-input v-model="sourceSettingsForm.sourceCode" disabled/><small class="form-hint">编码是数据应用和路由的稳定标识，创建后不允许修改。</small></el-form-item><el-form-item label="协议类型" required><el-select v-model="sourceSettingsForm.protocolType"><el-option label="HTTP JSON" value="HTTP_JSON"/><el-option label="WebService" value="SOAP"/><el-option label="本地数据集" value="LOCAL_SQL"/></el-select></el-form-item></div><el-form-item v-if="sourceSettingsForm.protocolType!=='LOCAL_SQL'" label="数据源基础地址" required><el-input v-model="sourceSettingsForm.endpoint" placeholder="https://provider.example.com"/><small class="form-hint">该数据源下选择“继承数据源”的接口会复用此地址。</small></el-form-item><el-form-item label="默认请求超时"><el-input-number v-model="sourceSettingsForm.defaultTimeoutMs" :min="100" :max="120000" :step="500" controls-position="right"/><span class="form-unit">毫秒（ms）</span></el-form-item><el-alert title="修改影响" description="目录归属和草稿配置立即生效；继承配置的接口需要重新联调。已发布服务保持原发布快照，重新发布后才切换。" type="info" :closable="false" show-icon/></el-form><template #footer><el-button :disabled="sourceSettingsSaving" @click="sourceSettingsDialog=false">取消</el-button><el-button type="primary" :loading="sourceSettingsSaving" @click="saveSourceSettings">保存修改</el-button></template></el-dialog>
  <el-dialog v-model="sourceRuntimeDialog" title="数据处理应用路由" width="580px"><el-alert title="Gateway 根据 API 编码从发布路由表选择该处理应用；生产环境请填写行内 VIP。修改后无需重启 Gateway。" type="info" :closable="false"/><el-form label-position="top" style="margin-top:18px"><el-form-item label="处理应用标识" required><el-input v-model="sourceRuntimeForm.applicationName" placeholder="jsnx-edsp-data-source-example"/></el-form-item><el-form-item label="运行地址 / 行内 VIP" required><el-input v-model="sourceRuntimeForm.runtimeUrl" placeholder="http://data-app-vip.internal:8180"/></el-form-item></el-form><template #footer><el-button @click="sourceRuntimeDialog=false">取消</el-button><el-button type="primary" :loading="sourceRuntimeSaving" @click="saveSourceRuntime">保存并刷新路由</el-button></template></el-dialog>
  <el-dialog v-model="apiDialog" title="新增资源 API" width="660px">
    <el-form label-position="top"><div class="form-grid"><el-form-item label="所属数据源"><el-select v-model="apiForm.dataSourceId"><el-option v-for="item in sources" :key="item.id" :label="`${item.sourceName} · 默认 ${item.defaultTimeoutMs ?? 3000}ms`" :value="item.id"/></el-select></el-form-item><el-form-item label="上游 HTTP 方法"><el-select v-model="apiForm.httpMethod"><el-option label="POST" value="POST"/><el-option label="GET" value="GET"/></el-select></el-form-item><el-form-item label="资源 API 名称" required><el-input v-model="apiForm.interfaceName"/></el-form-item><el-form-item label="资源 API 编码" required><el-input v-model="apiForm.interfaceCode"/><small class="form-hint">用于唯一标识资源提供商的原始接口</small></el-form-item></div><section class="api-policy-section"><header><strong>上游连接策略</strong><span>资源 API 只描述供应商原始契约，不在此定义平台服务路径</span></header><el-segmented v-model="apiForm.configScope" :options="[{label:'继承数据源',value:'INHERIT'},{label:'单接口覆盖',value:'OVERRIDE'}]"/><div class="source-default-facts"><span>基础地址<b>{{ selectedApiSource?.endpointUrl || '未配置' }}</b></span><span>默认超时<b>{{ selectedApiSource?.defaultTimeoutMs ?? 3000 }} ms</b></span><span>服务封装<b>保存后单独创建</b></span></div><el-form-item v-if="apiForm.configScope==='INHERIT'" label="供应商接口相对路径" required><el-input v-model="apiForm.upstreamPath" placeholder="/vendor/api/query"/></el-form-item><el-form-item v-else label="接口独立完整地址" required><el-input v-model="apiForm.upstreamUrl" placeholder="https://provider.example.com/vendor/api/query"/></el-form-item><div class="timeout-policy"><div class="timeout-policy-choice"><label>超时策略</label><div class="timeout-choice-group" role="radiogroup" aria-label="超时策略"><button type="button" role="radio" :aria-checked="apiForm.timeoutMode==='INHERIT'" :class="{selected:apiForm.timeoutMode==='INHERIT'}" @click="apiForm.timeoutMode='INHERIT'"><span class="timeout-choice-radio"/><span><strong>继承数据源默认</strong><small>使用当前数据源的 {{ selectedApiSource?.defaultTimeoutMs ?? 3000 }} ms</small></span></button><button type="button" role="radio" :aria-checked="apiForm.timeoutMode==='OVERRIDE'" :class="{selected:apiForm.timeoutMode==='OVERRIDE'}" @click="apiForm.timeoutMode='OVERRIDE'"><span class="timeout-choice-radio"/><span><strong>当前接口覆盖</strong><small>仅修改此资源 API</small></span></button></div></div><div class="timeout-policy-value"><label>接口超时（毫秒）</label><el-input-number v-model="apiForm.timeoutMs" :disabled="apiForm.timeoutMode==='INHERIT'" :min="100" :max="120000" :step="500"/><small>{{ apiForm.timeoutMode==='INHERIT' ? '当前生效值：' + (selectedApiSource?.defaultTimeoutMs ?? 3000) + ' ms' : '设置后仅对此接口生效' }}</small></div></div></section></el-form>
    <template #footer><el-button @click="apiDialog=false">取消</el-button><el-button type="primary" :loading="apiCreating" @click="createInterface">创建资源草稿</el-button></template>
  </el-dialog>
  <el-dialog v-model="platformServiceDialog" title="基于资源 API 创建平台标准服务" width="680px">
    <div class="platform-service-origin" v-if="selectedResourceApi"><span>来源资源 API</span><strong>{{ selectedResourceApi.resourceApiName }}</strong><code>{{ selectedResourceApi.resourceApiCode }}</code><small>上游请求和响应样例将作为标准服务开发的初始基线，可选择透传或配置转换。</small></div>
    <el-form label-position="top"><div class="form-grid"><el-form-item label="平台服务名称" required><el-input v-model="platformServiceForm.serviceName"/></el-form-item><el-form-item label="平台服务编码" required><el-input v-model="platformServiceForm.serviceCode"/></el-form-item><el-form-item label="平台 HTTP 方法"><el-select v-model="platformServiceForm.httpMethod"><el-option label="POST" value="POST"/><el-option label="GET" value="GET"/></el-select></el-form-item><el-form-item label="平台标准路径" required><el-input v-model="platformServiceForm.servicePath" placeholder="/api/v1/enterprise/query"/></el-form-item></div></el-form>
    <template #footer><el-button @click="platformServiceDialog=false">取消</el-button><el-button type="primary" :loading="platformServiceCreating" @click="createPlatformService">创建并进入服务开发</el-button></template>
  </el-dialog>
  <el-dialog v-model="providerDialog" :title="providerForm.id ? '编辑资源提供商' : '新增资源提供商'" width="760px"><el-form label-position="top"><h3 class="dialog-section-title">基本信息</h3><div class="form-grid"><el-form-item label="提供商代码" required><el-input v-model="providerForm.providerCode" placeholder="请输入资源提供商代码"/></el-form-item><el-form-item label="提供商名称" required><el-input v-model="providerForm.providerName" placeholder="请输入资源提供商名称"/></el-form-item><el-form-item label="经办人"><el-input v-model="providerForm.contactName" placeholder="请输入经办人"/></el-form-item><el-form-item label="接入时间"><el-date-picker v-model="providerForm.accessDate" type="date" value-format="YYYY-MM-DD" placeholder="请选择接入时间" style="width:100%"/></el-form-item></div><el-form-item label="提供商描述"><el-input v-model="providerForm.description" type="textarea" :rows="3" placeholder="请输入资源提供商描述"/></el-form-item><h3 class="dialog-section-title">参数配置</h3><div class="form-grid"><el-form-item label="通讯报文编码"><el-select v-model="providerForm.messageEncoding" placeholder="请选择通讯报文编码"><el-option label="UTF-8" value="UTF-8"/><el-option label="GBK" value="GBK"/><el-option label="GB2312" value="GB2312"/></el-select></el-form-item><el-form-item label="提供商类型"><el-select v-model="providerForm.providerType" placeholder="请选择提供商类型"><el-option label="数据资源提供商" value="DATA_VENDOR"/><el-option label="征信机构" value="CREDIT"/><el-option label="政务数据机构" value="GOVERNMENT"/><el-option label="其他" value="OTHER"/></el-select></el-form-item><el-form-item label="是否需要收费"><el-radio-group v-model="providerForm.chargeRequired"><el-radio :value="false">否</el-radio><el-radio :value="true">是</el-radio></el-radio-group></el-form-item><el-form-item label="是否需要加密"><el-radio-group v-model="providerForm.encryptionRequired"><el-radio :value="false">否</el-radio><el-radio :value="true">是</el-radio></el-radio-group></el-form-item><el-form-item label="是否需要登录"><el-radio-group v-model="providerForm.loginRequired"><el-radio :value="false">否</el-radio><el-radio :value="true">是</el-radio></el-radio-group></el-form-item><el-form-item label="状态"><el-select v-model="providerForm.status"><el-option label="启用" value="ACTIVE"/><el-option label="停用" value="DISABLED"/></el-select></el-form-item></div></el-form><template #footer><el-button @click="providerDialog=false">取消</el-button><el-button type="primary" :loading="providerSaving" @click="saveProvider">{{ providerForm.id ? '保存修改' : '确认新增' }}</el-button></template></el-dialog>
  <el-dialog v-model="accountDialog" title="新增资源提供商账号" width="620px"><el-form label-position="top"><div class="form-grid"><el-form-item label="登录账号"><el-input v-model="accountForm.username"/></el-form-item><el-form-item label="显示名称"><el-input v-model="accountForm.displayName"/></el-form-item><el-form-item label="手机号"><el-input v-model="accountForm.mobile"/></el-form-item><el-form-item label="邮箱"><el-input v-model="accountForm.email"/></el-form-item><el-form-item label="角色"><el-select v-model="accountForm.roleCode"><el-option label="资源提供商管理员" value="PROVIDER_ADMIN"/><el-option label="审计人员" value="AUDITOR"/><el-option label="接口开发" value="API_DEVELOPER"/></el-select></el-form-item><el-form-item label="状态"><el-select v-model="accountForm.status"><el-option label="启用" value="ACTIVE"/><el-option label="停用" value="DISABLED"/></el-select></el-form-item></div><el-form-item label="权限"><el-checkbox-group v-model="accountForm.permissions"><el-checkbox label="DATASOURCE_VIEW">数据源查看</el-checkbox><el-checkbox label="DATASOURCE_EDIT">数据源编辑</el-checkbox><el-checkbox label="API_VIEW">API 查看</el-checkbox><el-checkbox label="API_EDIT">API 编辑</el-checkbox><el-checkbox label="API_TEST">API 联调</el-checkbox><el-checkbox label="CATALOG_PUBLISH">目录发布</el-checkbox><el-checkbox label="AUDIT_VIEW">审计查看</el-checkbox></el-checkbox-group></el-form-item></el-form><template #footer><el-button @click="accountDialog=false">取消</el-button><el-button type="primary" @click="saveAccount">保存账号</el-button></template></el-dialog>
  <el-dialog v-model="versionDialog" :title="selectedVersion ? '版本 V' + selectedVersion.version + ' 详情' : '版本详情'" width="680px">
    <div v-if="selectedVersion" class="version-detail-dialog">
      <div class="version-detail-hero"><strong>V{{ selectedVersion.version }}</strong><div><el-tag :type="versionTagType(selectedVersion)">{{ versionStatusText(selectedVersion) }}</el-tag><p>{{ selectedVersion.releaseNote || '未填写发布说明' }}</p></div></div>
      <dl><div><dt>申请人</dt><dd>{{ selectedVersion.applicant || '-' }}</dd></div><div><dt>审批人 / 发布人</dt><dd>{{ selectedVersion.reviewedBy || selectedVersion.releasedBy || '-' }}</dd></div><div><dt>提交时间</dt><dd>{{ selectedVersion.submittedAt || '-' }}</dd></div><div><dt>发布时间</dt><dd>{{ selectedVersion.releasedAt || selectedVersion.reviewedAt || '-' }}</dd></div><div><dt>执行计划快照</dt><dd>{{ selectedVersion.snapshotAvailable ? '已留存' : '未留存' }}</dd></div><div><dt>配置校验和</dt><dd><code>{{ selectedVersion.checksum || '-' }}</code></dd></div></dl>
      <el-alert v-if="!selectedVersion.historyComplete" title="历史档案不完整" type="warning" :closable="false" description="当前只能从接口主表确认该版本曾发布，未查询到完整审批与发布记录。"/>
    </div>
    <template #footer><el-button type="primary" @click="versionDialog=false">关闭</el-button></template>
  </el-dialog>
  <el-dialog v-model="approvalDialog" title="发布审批" width="640px"><div v-if="selectedApproval" class="approval-review"><div class="approval-title"><strong>{{ selectedApproval.interfaceName }}</strong><el-tag type="warning">V{{ selectedApproval.releaseVersion }} · 待审批</el-tag></div><div class="approval-meta"><span>接口编码：{{ selectedApproval.interfaceCode }}</span><span>申请人：{{ selectedApproval.applicant }}</span><span>提交时间：{{ selectedApproval.submittedAt }}</span></div><div class="approval-checklist"><p><el-icon><CircleCheck /></el-icon>字段映射与统一返回码已配置</p><p><el-icon><CircleCheck /></el-icon>草稿版本校验通过</p><p><el-icon><CircleCheck /></el-icon>审批通过后自动激活发布计划</p></div><el-input v-model="approvalComment" type="textarea" :rows="4" placeholder="填写审批意见"/></div><template #footer><el-button @click="approvalDialog=false">取消</el-button><el-button type="danger" @click="decideApproval('REJECT')">驳回</el-button><el-button type="primary" @click="decideApproval('APPROVE')">审批通过并发布</el-button></template></el-dialog>
  <el-dialog v-model="clientDialog" title="登记调用系统" width="600px"><el-form label-position="top"><div class="form-grid"><el-form-item label="系统名称"><el-input v-model="clientForm.clientName"/></el-form-item><el-form-item label="系统编码"><el-input v-model="clientForm.clientCode"/></el-form-item><el-form-item label="机构名称"><el-input v-model="clientForm.institutionName"/></el-form-item><el-form-item label="机构编码"><el-input v-model="clientForm.institutionCode"/></el-form-item></div><el-form-item label="负责人"><el-input v-model="clientForm.ownerName"/></el-form-item></el-form><template #footer><el-button @click="clientDialog=false; returnToAuthAfterClient=false">取消</el-button><el-button type="primary" @click="createClient">确认登记</el-button></template></el-dialog>
  <el-dialog v-model="authDialog" title="新增 API 授权" width="620px"><el-form label-position="top"><el-form-item label="调用系统"><template v-if="activeClients.length"><el-select v-model="authForm.clientId" placeholder="请选择调用系统" style="width:100%"><el-option v-for="item in activeClients" :key="item.id" :label="`${item.clientName} · ${item.institutionName}`" :value="item.id"/></el-select></template><div v-else class="auth-client-empty"><div><strong>暂无可用调用系统</strong><span>请先登记一个行内调用系统，再继续授权。</span></div><el-button type="primary" plain :icon="Plus" @click="openClientRegistration(true)">去登记</el-button></div></el-form-item><el-form-item label="授权 API"><el-select v-model="authForm.interfaceId" placeholder="请选择已发布 API" style="width:100%"><el-option v-for="item in authorizableInterfaces" :key="item.id" :label="`${item.interfaceName} · ${item.interfaceCode} · V${item.publishedVersion}`" :value="item.id"/></el-select></el-form-item><div class="form-grid"><el-form-item label="环境"><el-select v-model="authForm.environment"><el-option label="TEST" value="TEST"/><el-option label="PROD" value="PROD"/></el-select></el-form-item><el-form-item label="日调用配额"><el-input-number v-model="authForm.dailyQuota" :min="1" :step="1000"/></el-form-item><el-form-item label="生效日期"><el-date-picker v-model="authForm.validFrom" type="date" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="失效日期"><el-date-picker v-model="authForm.validTo" type="date" value-format="YYYY-MM-DD"/></el-form-item></div></el-form><template #footer><el-button @click="authDialog=false">取消</el-button><el-button type="primary" :disabled="!activeClients.length || !authForm.clientId || !authForm.interfaceId" @click="createAuthorization">确认授权</el-button></template></el-dialog>
  <el-dialog v-model="batchAuthDialog" width="min(1080px,94vw)" class="batch-auth-dialog" destroy-on-close>
    <template #header><div class="batch-auth-title"><span>BATCH AUTHORIZATION</span><strong>批量授予 API 访问权限</strong><small>通过筛选和跨页勾选快速处理大规模 API</small></div></template>
    <div class="batch-auth-layout">
      <section class="batch-auth-policy">
        <header><span>01</span><div><strong>授权策略</strong><small>一次配置，应用到所有已选 API</small></div></header>
        <el-form label-position="top">
          <el-form-item label="调用系统"><el-select v-model="batchAuthForm.clientId" filterable placeholder="搜索调用系统" style="width:100%" @change="resetBatchPage"><el-option v-for="item in activeClients" :key="item.id" :label="`${item.clientName} · ${item.clientCode}`" :value="item.id"/></el-select></el-form-item>
          <div class="batch-auth-policy-grid"><el-form-item label="环境"><el-segmented v-model="batchAuthForm.environment" :options="['TEST','PROD']" @change="resetBatchPage"/></el-form-item><el-form-item label="日调用配额"><el-input-number v-model="batchAuthForm.dailyQuota" :min="1" :step="1000" controls-position="right"/></el-form-item></div>
          <el-form-item label="授权有效期"><el-date-picker v-model="batchAuthDateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="生效日期" end-placeholder="失效日期" style="width:100%"/></el-form-item>
        </el-form>
        <div class="batch-auth-selected"><div><span>已选 API</span><strong>{{ batchAuthForm.interfaceIds.length }}</strong></div><button v-if="batchAuthForm.interfaceIds.length" type="button" @click="clearBatchSelection">清空全部</button></div>
        <div class="batch-auth-selected-list"><button v-for="item in selectedBatchAuthorizationInterfaces.slice(0,8)" :key="item.id" type="button" :title="'移除 '+item.interfaceName" @click="removeBatchSelection(item.id)"><span>{{ item.interfaceName }}</span><b>×</b></button><small v-if="selectedBatchAuthorizationInterfaces.length>8">还有 {{ selectedBatchAuthorizationInterfaces.length-8 }} 个 API 已选</small></div>
      </section>
      <section class="batch-auth-picker">
        <header><span>02</span><div><strong>选择 API</strong><small>{{ filteredBatchAuthorizationInterfaces.length }} 个筛选结果</small></div></header>
        <div class="batch-auth-filters"><el-select v-model="batchAuthFilters.sourceId" filterable @change="resetBatchPage"><el-option label="全部数据源" :value="0"/><el-option v-for="source in sources" :key="source.id" :label="source.sourceName" :value="source.id"/></el-select><el-input v-model="batchAuthFilters.keyword" :prefix-icon="Search" clearable placeholder="搜索 API 名称、编码或路径" @input="resetBatchPage"/><el-select v-model="batchAuthFilters.grantStatus" @change="resetBatchPage"><el-option label="尚未授权" value="UNAUTHORIZED"/><el-option label="已授权" value="AUTHORIZED"/><el-option label="全部状态" value="ALL"/></el-select></div>
        <div class="batch-auth-toolbar"><div><el-checkbox :model-value="pagedBatchAuthorizationInterfaces.length>0 && batchPageSelectedCount===pagedBatchAuthorizationInterfaces.length" :indeterminate="batchPageSelectedCount>0 && batchPageSelectedCount<pagedBatchAuthorizationInterfaces.length" @change="selectBatchPage">选择当前页</el-checkbox><button type="button" @click="selectAllBatchResults">选择全部 {{ filteredBatchAuthorizationInterfaces.length }} 个结果</button></div><span>已选 {{ batchAuthForm.interfaceIds.length }} 个</span></div>
        <div class="batch-auth-table"><div class="batch-auth-table-head"><span></span><span>API</span><span>数据源</span><span>版本</span><span>授权状态</span></div><label v-for="item in pagedBatchAuthorizationInterfaces" :key="item.id" class="batch-auth-row" :class="{selected:batchAuthForm.interfaceIds.includes(item.id)}"><el-checkbox :model-value="batchAuthForm.interfaceIds.includes(item.id)" @change="toggleBatchInterface(item.id,$event)"/><span><strong>{{ item.interfaceName }}</strong><small>{{ item.interfaceCode }} · {{ item.servicePath }}</small></span><span>{{ sources.find(source=>source.id===item.dataSourceId)?.sourceName || '-' }}</span><span>V{{ item.publishedVersion }}</span><el-tag size="small" :type="hasAuthorization(item.id)?'success':'info'">{{ hasAuthorization(item.id)?'已授权':'未授权' }}</el-tag></label><el-empty v-if="!pagedBatchAuthorizationInterfaces.length" :image-size="54" description="当前筛选条件下没有 API"/></div>
        <div class="batch-auth-pagination"><el-pagination v-model:current-page="batchAuthPage" v-model:page-size="batchAuthPageSize" :page-sizes="[20,50,100]" :total="filteredBatchAuthorizationInterfaces.length" layout="total, sizes, prev, pager, next"/></div>
      </section>
    </div>
    <template #footer><div class="batch-auth-footer"><span>授权成功后即时生效，已有授权将更新配额和有效期。</span><div><el-button @click="batchAuthDialog=false">取消</el-button><el-button type="primary" :loading="batchAuthSubmitting" :disabled="!batchAuthForm.clientId || !batchAuthForm.interfaceIds.length" @click="createBatchAuthorization">确认授权 {{ batchAuthForm.interfaceIds.length }} 个 API</el-button></div></div></template>
  </el-dialog>
  <el-drawer v-model="publishedDetailDrawer" title="已发布接口详情" size="620px"><div v-loading="publishedDetailLoading" class="published-interface-detail" v-if="publishedInterfaceDetail"><section class="published-detail-hero"><span>ONLINE SERVICE</span><strong>{{ publishedInterfaceDetail.summary.interfaceName }}</strong><code>{{ publishedInterfaceDetail.summary.interfaceCode }}</code></section><dl class="published-detail-facts"><div><dt>平台服务 URL</dt><dd>{{ publishedInterfaceDetail.summary.servicePath }}</dd></div><div><dt>HTTP 方法</dt><dd><el-tag type="success">{{ publishedInterfaceDetail.summary.httpMethod }}</el-tag></dd></div><div><dt>线上版本</dt><dd>V{{ publishedInterfaceDetail.summary.publishedVersion }}</dd></div><div><dt>上游地址</dt><dd>{{ publishedInterfaceDetail.summary.upstreamUrl || '-' }}</dd></div><div><dt>超时配置</dt><dd>{{ publishedInterfaceDetail.timeoutMs }} ms · {{ publishedInterfaceDetail.timeoutSource === 'INTERFACE' ? '接口覆盖' : '数据源默认' }}</dd></div><div><dt>当前状态</dt><dd><el-tag type="success">{{ publishedInterfaceDetail.summary.status === 'PUBLISHED' ? '运行中' : publishedInterfaceDetail.summary.status }}</el-tag></dd></div></dl><section class="published-detail-code"><div><label>平台请求样例</label><pre>{{ formatJson(publishedInterfaceDetail.requestExample) }}</pre></div><div><label>标准响应样例</label><pre>{{ formatJson(publishedInterfaceDetail.standardResponseExample) }}</pre></div></section></div><el-empty v-else-if="!publishedDetailLoading" description="暂无接口详情"/></el-drawer>
  <el-dialog v-model="billingDialog" title="配置接口计费规则" width="600px"><el-form label-position="top"><el-form-item label="计费 API"><el-select v-model="billingForm.interfaceId"><el-option v-for="item in allInterfaces" :key="item.id" :label="`${item.interfaceName} · ${item.interfaceCode}`" :value="item.id"/></el-select></el-form-item><div class="form-grid"><el-form-item label="计费方式"><el-select v-model="billingForm.billingMode"><el-option label="按成功次数" value="SUCCESS_COUNT"/><el-option label="按调用次数" value="REQUEST_COUNT"/></el-select></el-form-item><el-form-item label="单价（元/次）"><el-input-number v-model="billingForm.unitPrice" :precision="4" :step="0.01" :min="0"/></el-form-item><el-form-item label="HIT 是否计费"><el-switch v-model="billingForm.hitBillable"/></el-form-item><el-form-item label="MISS 是否计费"><el-switch v-model="billingForm.missBillable"/></el-form-item></div><el-form-item label="生效日期"><el-date-picker v-model="billingForm.effectiveDate" type="date" value-format="YYYY-MM-DD"/></el-form-item></el-form><template #footer><el-button @click="billingDialog=false">取消</el-button><el-button type="primary" @click="saveBilling">保存并生效</el-button></template></el-dialog>
</template>
