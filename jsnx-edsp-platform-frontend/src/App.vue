<script setup lang="ts">
import './resource-workbench.css'
import HelpTip from './components/HelpTip.vue'
import CopyableBlock from './components/CopyableBlock.vue'
import { computed, nextTick, ref, watch } from 'vue'
import { useDisplayPreference } from './composables/useDisplayPreference'
import {
  Bell, Check, CircleCheck, Connection, DataAnalysis, DataBoard, Delete,
  DocumentChecked, DocumentCopy, Edit, Files, Key, Lock, Monitor, Plus, Promotion, Refresh,
  Search, Setting, SwitchButton, Tickets, User as UserIcon, Wallet, OfficeBuilding, Warning,
  Fold, Expand, Upload,
  Guide, ArrowRight
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiRequestError, isDemoMode } from './api'
import { describeTraceFailure } from './trace-diagnostics'
import { buildResourceGuide, guideAdvice, isHttpEndpoint, isSuccessfulE2E, requestDeadlineMs } from './resource-guide'
import type {
  AuthorizationRecord, BillingRule, BillingSummary, CallTraceDetail, ClientApp, DashboardSummary,
  DataSourceSummary, FieldMapping, InterfaceDetail, InterfaceSummary, InterfaceVersionHistory, ReleaseServiceRow, LoginResult,
  MonitorAlert, MonitorPoint, MonitorSummary, PlatformResponseCode, ProviderAccount, ProviderSummary, ReleaseApproval, TestResult,
  ResourceApiDetail, ResourceApiSummary, ResourceDirectoryRow, ResourceDocument, UsageRecord, UsageReportRow, OperationAuditRecord, ValidationResult,
  DataApplicationProvisionResult, DataApplicationProvisionSettings, DatabaseConnectionTestResult, SecurityAlgorithmDescriptor, SecurityPolicy, PlatformResponseContract
} from './types'

const loggedIn = ref(false)
const loginLoading = ref(false)
const loginForm = ref({ username: '', password: '' })
const user = ref<LoginResult | null>(null)
const activeMenu = ref('overview')
const sidebarCollapsed = useDisplayPreference('sidebar-collapsed')
const resourceCatalogCollapsed = useDisplayPreference('resource-catalog-collapsed')
const menuGroups = ref({ resources: true, services: true, platform: true, operations: true })
const loading = ref(false)
const demo = ref(false)
const dashboard = ref<DashboardSummary>({ providerCount: 0, dataSourceCount: 0, interfaceCount: 0, publishedCount: 0, draftCount: 0, activePublishedPlans: 0 })
const providers = ref<ProviderSummary[]>([])
const selectedProviderId = ref(0)
const providerAccounts = ref<ProviderAccount[]>([])
const providerDialog = ref(false)
const providerSaving = ref(false)
const databaseConnectionTesting = ref(false)
const databaseConnectionResult = ref<DatabaseConnectionTestResult | null>(null)
const providerForm = ref<ProviderSummary>({ id: 0, providerCode: '', providerName: '', status: 'ACTIVE', accessMode: 'HTTP', accessConfig: {} })
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
const activeAuthorizationCount = ref(0)
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
const resourceDocuments = ref<ResourceDocument[]>([])
const documentDialog = ref(false)
const catalogSearch = ref('')
const directoryFilters = ref({ keyword: '', providerId: 0, dataSourceId: 0, status: '', protocolType: '' })
const directoryRows = ref<ResourceDirectoryRow[]>([])
const directoryTotal = ref(0)
const directoryPage = ref(1)
const directorySize = ref(20)
const directoryLoading = ref(false)
const documentUploading = ref(false)
const documentForm = ref({ providerId: 0, dataSourceId: 0, resourceApiId: 0, description: '', file: null as File | null })
const reportDimensionOptions = [
  { value: 'PROVIDER', label: '资源提供商' }, { value: 'DATA_SOURCE', label: '数据源' },
  { value: 'API', label: '服务 API' }, { value: 'DAY', label: '日期' },
  { value: 'CLIENT', label: '调用系统' }, { value: 'RESULT', label: '结果状态' }
]
const reportMetricOptions = [
  { value: 'callCount', label: '调用量' }, { value: 'successCount', label: '成功量' },
  { value: 'failureCount', label: '失败量' }, { value: 'billableCount', label: '计费量' },
  { value: 'amount', label: '金额' }, { value: 'averageDurationMs', label: '平均耗时 (ms)' },
  { value: 'upstreamDurationMs', label: '上游平均耗时 (ms)' }
]
const reportDimensions = ref(['PROVIDER', 'DATA_SOURCE', 'API'])
const reportMetrics = ref(['callCount', 'successCount', 'failureCount', 'amount'])
function localDate(offsetDays = 0) {
  const date = new Date()
  date.setDate(date.getDate() + offsetDays)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
const reportRange = ref<[string, string]>([localDate(-30), localDate()])
const reportRows = ref<UsageReportRow[]>([])
const reportLoading = ref(false)
const reportTotal = ref<UsageReportRow | null>(null)
const reportTree = computed(() => {
  const nodes = new Map<string, UsageReportRow>()
  const roots: UsageReportRow[] = []
  for (const row of reportRows.value.filter(item => item.level > 0)) nodes.set(JSON.stringify(row.path), { ...row, children: [] })
  for (const node of nodes.values()) {
    const parent = nodes.get(JSON.stringify(node.path.slice(0, -1)))
    if (parent) parent.children!.push(node)
    else roots.push(node)
  }
  return roots
})
const auditRange = ref<[string, string]>([localDate(-30), localDate()])
const auditFilters = ref({ action: '', operator: '', targetType: '', outcome: '' })
const auditRows = ref<OperationAuditRecord[]>([])
const auditPage = ref(1)
const auditSize = ref(50)
const auditTotal = ref(0)
const auditLoading = ref(false)
let auditQuerySequence = 0
const selectedAuditRecord = ref<OperationAuditRecord | null>(null)
const auditDetailDrawer = ref(false)
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
const savedResponsePolicyBaseline = ref('')
const detailLoading = ref(false)
const sourceSwitching = ref(false)
const interfaceCache = new Map<number, InterfaceSummary[]>()
const interfaceCatalogLoaded = ref(false)
const detailCache = new Map<number, InterfaceDetail>()
const selectedInterfaceBySource = new Map<number, number>()
const selectedResourceApiBySource = new Map<number, number>()
let sourceRequestSequence = 0
const activeTab = ref('overview')
const upstreamSection = ref(0)
const upstreamSections = [
  { title: '连接配置' },
  { title: '接口报文' },
  { title: '接入测试' }
]
watch(selectedInterfaceId, () => { upstreamSection.value = 0; connectionDetailsExpanded.value = false })
type ServiceDevelopmentStage = 'UPSTREAM' | 'ORCHESTRATION'
const serviceDevelopmentStage = ref<ServiceDevelopmentStage>('UPSTREAM')
// Start field standardization from the request flow, which is the first step
// when adapting the platform contract to an upstream resource API.
const mappingDirection = ref<'REQUEST' | 'RESPONSE'>('REQUEST')
const sourceSearch = ref('')
const providerExpansion = ref<Record<number, boolean>>({})
const sourceExpansion = ref<Record<number, boolean>>({})
const timeoutExpanded = ref(false)
const connectionDetailsExpanded = ref(false)
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
const releaseDetailSource = computed(() => sources.value.find(source => source.id === releaseDetail.value?.summary.dataSourceId))
const releaseDetailProvider = computed(() => providers.value.find(provider => provider.id === releaseDetailSource.value?.providerId))
const releaseVersions = ref<InterfaceVersionHistory[]>([])
const releaseLoading = ref(false)
let releaseRequestSequence = 0
let releaseServiceRequestSequence = 0
const releaseServiceRows = ref<ReleaseServiceRow[]>([])
const releaseServiceTotal = ref(0)
const releaseServicePage = ref(1)
const releaseServiceSize = ref(20)
const releaseServiceLoading = ref(false)
const releaseDetailDrawer = ref(false)
const releaseFilters = ref({ keyword: '', providerId: 0, dataSourceId: 0, runStatus: 'ALL', authorizationStatus: 'ALL' })
const releaseQuickView = ref('ALL')
const releaseNote = ref('完成接口字段标准化配置并通过联调验证')
const requestText = ref('{}')
const responseText = ref('{}')
const responseFileInput = ref<HTMLInputElement | null>(null)
const upstreamRequestText = ref('{}')
const testMode = ref<'UPSTREAM' | 'PLATFORM' | 'E2E'>('PLATFORM')
const savedRequestBaseline = ref('{}')
const savedResponseBaseline = ref('{}')
const savedConnectionBaseline = ref('')
const savedUpstreamRequestBaseline = ref('{}')
const savedUpstreamResponseBaseline = ref('{}')
const savedResponseCodesBaseline = ref('')
const platformResponseCodes = ref<PlatformResponseCode[]>([])
type PlatformManagementTab = 'contract' | 'codes' | 'security' | 'audit'
const platformManagementTab = ref<PlatformManagementTab>('contract')
const platformCodeSearch = ref('')
const platformResponseContracts = ref<PlatformResponseContract[]>([])
const selectedPlatformContractId = ref(0)
const platformContractFieldsDrawer = ref(false)
const contractEditorDialog = ref(false)
const contractEditorOriginalId = ref(0)
const contractEditorSaving = ref(false)
const contractEditorForm = ref<PlatformResponseContract>({
  contractCode: '', contractName: '', version: 1, description: '', enabled: true, defaultContract: false, active: false, fields: []
})
const selectedPlatformContract = computed(() => platformResponseContracts.value.find(item => item.id === selectedPlatformContractId.value)
  ?? platformResponseContracts.value.find(item => item.defaultContract)
  ?? platformResponseContracts.value[0])
const platformContractFields = computed(() => selectedPlatformContract.value?.fields ?? [])
const activeSidebarMenu = computed(() => activeMenu.value === 'platform-management'
  ? `platform-management-${platformManagementTab.value}`
  : activeMenu.value)
const filteredPlatformResponseCodes = computed(() => {
  const keyword = platformCodeSearch.value.trim().toLowerCase()
  if (!keyword) return platformResponseCodes.value
  return platformResponseCodes.value.filter(item => `${item.code} ${item.name} ${item.description}`.toLowerCase().includes(keyword))
})
const codeEditorDialog = ref(false)
const codeEditorOriginal = ref('')
const codeEditorSaving = ref(false)
const codeEditorForm = ref<PlatformResponseCode>({ code: '', name: '', resultStatus: 'HIT', description: '', enabled: true })
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
const sourceRuntimeResult = ref<DataApplicationProvisionResult | null>(null)
const sourceRuntimeStatusLoading = ref(false)
const sourceRuntimeStatusLoaded = ref(false)
const provisionSettings = ref<DataApplicationProvisionSettings | null>(null)
const sourceStep = ref(0)
const sourceSaving = ref(false)
const sourceError = ref('')
// Instance count is managed by deployment orchestration, not resource onboarding.
const sourceForm = ref({ providerId: 0, sourceCode: '', sourceName: '', protocolType: 'HTTP_JSON', endpoint: '', defaultTimeoutMs: 3000 })
const sourceSettingsForm = ref({ providerId: 0, sourceCode: '', sourceName: '', protocolType: 'HTTP_JSON', endpoint: '', defaultTimeoutMs: 3000 })
const sourceRuntimeForm = ref({ applicationName: '', runtimeUrl: '', serverPort: 8199 })
const apiDialog = ref(false)
const apiCreating = ref(false)
const databaseTables = ref<{ name: string; type: string; remarks?: string }[]>([])
const databaseColumns = ref<{ name: string; type: string; nullable: boolean; ordinal: number }[]>([])
const databaseSelectedTable = ref('')
const databaseSelectedFields = ref<string[]>([])
const databaseFilterFields = ref<string[]>([])
const databaseSchemaLoading = ref(false)
const databaseQueryMode = ref<'TABLE' | 'SQL'>('TABLE')
const apiMetadataDialog = ref(false)
const apiMetadataSaving = ref(false)
const apiMetadataForm = ref({ resourceApiCode: '', resourceApiName: '', httpMethod: 'POST' })
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
const canDeleteApi = computed(() => {
  const summary = detail.value?.summary
  return (summary?.status === 'DRAFT' && summary.publishedVersion == null)
    || (summary?.status === 'OFFLINE' && summary.publishedVersion != null)
})
const deleteApiRestriction = computed(() => {
  if (canDeleteApi.value) return ''
  if (detail.value?.summary.status === 'PENDING_APPROVAL') return '接口正在审批中，不能删除；请进入服务发布查看状态，或到变更审批处理。'
  if (detail.value?.summary.publishedVersion != null) return '接口已有发布版本，请先在服务发布中下线，再归档删除；版本、授权和链路记录会保留。'
  return '仅支持归档从未发布的草稿，或已下线的服务。'
})
const apiForm = ref({ dataSourceId: 1, interfaceCode: '', interfaceName: '', servicePath: '', httpMethod: 'POST', configScope: 'INHERIT' as 'INHERIT'|'OVERRIDE', upstreamPath: '', upstreamUrl: '', timeoutMode: 'INHERIT' as 'INHERIT'|'OVERRIDE', timeoutMs: 3000, generateMock: false, upstreamRequestExample: null as Record<string, unknown> | null, executionMode: 'HTTP' as 'HTTP'|'SQL', sqlTemplate: '', sqlParameters: '{}' })
const platformServiceDialog = ref(false)
const platformServiceCreating = ref(false)
const platformServiceForm = ref({ resourceApiId: 0, serviceCode: '', serviceName: '', servicePath: '', httpMethod: 'POST' })
const serviceResourceOptions = computed(() => allResourceApis.value
  .filter(resource => resource.status === 'READY')
  .map(resource => {
    const source = sources.value.find(item => item.id === resource.dataSourceId)
    const provider = providers.value.find(item => item.id === source?.providerId)
    return { ...resource, lineage: `${provider?.providerName ?? source?.providerName ?? '未知提供商'} / ${source?.sourceName ?? '未知数据源'}` }
  }))
const serviceCreationResource = computed(() => serviceResourceOptions.value.find(item => item.id === platformServiceForm.value.resourceApiId))
const connectionForm = ref({ configScope: 'INHERIT' as 'INHERIT'|'OVERRIDE', upstreamPath: '', upstreamUrl: '', timeoutMode: 'INHERIT' as 'INHERIT'|'OVERRIDE', timeoutMs: 3000, requestHeaders: [] as { name: string; value: string }[], executionMode: 'HTTP' as 'HTTP'|'SQL', sqlTemplate: '', sqlParameters: '{}' })
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
const authForm = ref({ clientId: 1, interfaceId: 1, dailyQuota: 50000, ...defaultAuthorizationDates, approvedBy: '平台管理员' })
const authDialogMode = ref<'CREATE' | 'EDIT'>('CREATE')
const editingAuthorization = ref<AuthorizationRecord | null>(null)
const batchAuthDialog = ref(false)
const batchAuthForm = ref({ clientId: 0, interfaceIds: [] as number[], dailyQuota: 50000, ...defaultAuthorizationDates, approvedBy: '平台管理员' })
const batchAuthDateRange = ref<[string, string]>([defaultAuthorizationDates.validFrom, defaultAuthorizationDates.validTo])
const batchAuthFilters = ref({ sourceId: 0, keyword: '' })
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
  { key: 'DATA_SOURCE', label: '资源提供商处理应用', short: 'ADAPTER', icon: Files },
  { key: 'VENDOR', label: '上游服务', short: 'UPSTREAM', icon: OfficeBuilding },
  { key: 'MOCK_RESPONSE', label: '响应样例', short: 'MOCK', icon: DocumentCopy },
  { key: 'RESPONSE_MAPPING', label: '响应标准化', short: 'NORMALIZE', icon: DataAnalysis },
  { key: 'BILLING', label: '计量计费', short: 'BILLING', icon: Wallet }
]
const esbTraceNodeDefinitions = [
  { key: 'ESB_QUEUE', label: 'ESB 队列', short: 'XML QUEUE', icon: Tickets },
  { key: 'ESB_ADAPTER', label: 'ESB Adapter', short: 'PARSE', icon: Connection },
  { key: 'GATEWAY', label: '统一 Gateway', short: 'ROUTE', icon: DataBoard },
  ...traceNodeDefinitions.filter(node => !['DOWNSTREAM', 'GATEWAY', 'MOCK_RESPONSE'].includes(node.key)),
  { key: 'STANDARD_RESPONSE', label: 'ESB XML 响应', short: 'XML REPLY', icon: DataAnalysis }
]

const testModes = [
  { value: 'UPSTREAM' as const, index: '01', label: '资源接口测试', path: '处理应用 → 资源提供商', description: '通过资源提供商处理应用发送真实请求，检查连接和原始响应。' },
  { value: 'PLATFORM' as const, index: '02', label: '服务编排测试', path: '标准请求 → 转换与安全 → 上游调用 → 标准响应', description: '使用当前已保存草稿测试转换、安全处理和真实上游调用，不经过 ESB 和 Gateway，不要求服务已发布。' },
  { value: 'E2E' as const, index: '03', label: '全链路测试', path: 'ESB → Adapter → Gateway → 处理应用 → 资源提供商', description: '通过内部测试通道执行当前草稿，包含 ESB、Gateway 和 XML 响应回写。不依赖发布状态和业务授权，不代表正式入口授权验收。' }
]
const orchestrationTestModes = computed(() => testModes.filter(mode => mode.value !== 'UPSTREAM'))

const activeTraceDefinitions = computed(() => (testMode.value === 'E2E' ? esbTraceNodeDefinitions : traceNodeDefinitions).filter(node => {
  if (testMode.value === 'UPSTREAM') return ['DATA_SOURCE', 'VENDOR'].includes(node.key)
  if (testMode.value === 'PLATFORM') return !['GATEWAY', 'MOCK_RESPONSE'].includes(node.key)
  return node.key !== 'MOCK_RESPONSE'
}))

const activeTestMode = computed(() => testModes.find(mode => mode.value === testMode.value) ?? testModes[2])
const testActionLabel = computed(() => testMode.value === 'UPSTREAM'
  ? '测试资源接口' : testMode.value === 'PLATFORM' ? '执行编排测试' : '执行全链路测试')

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
  try { return buildEsbXml(detail.value.summary.interfaceCode, esbTestTraceId.value, 'EDSP_TEST_TOOL', JSON.parse(requestText.value)) }
  catch { return '请输入有效 JSON 后生成 XML 入队报文' }
})
const esbTestTraceId = ref(`EDSP-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`)
const esbXmlOverride = ref<string | null>(null)
const esbXmlText = computed({
  get: () => esbXmlOverride.value ?? esbXmlPreview.value,
  set: value => { esbXmlOverride.value = value }
})
watch(selectedInterfaceId, () => { esbXmlOverride.value = null; esbTestTraceId.value = `EDSP-${Date.now()}-${Math.random().toString(16).slice(2, 10)}` })
function resetEsbXml() {
  esbXmlOverride.value = null
  esbTestTraceId.value = `EDSP-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}
function esbTraceId(xml: string, fallback: string) {
  const parsed = new DOMParser().parseFromString(xml, 'application/xml')
  return parsed.getElementsByTagName('service_sn')[0]?.textContent?.trim() || fallback
}
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
  overview: ['全局概览', '查看平台调用表现与服务使用情况'],
  discovery: ['资源目录', '查看平台已接入的数据资源'],
  documents: ['文档管理', '管理资源提供商接口文档'],
  reports: ['报表中心', '查看与下载调用统计报表'],
  resource: ['数据资源接入', '配置上游请求、响应和连接验证'],
  'service-development': ['标准服务开发', '定义平台标准请求、响应与安全编排'],
  authorization: ['API 访问授权', '按机构和调用系统授予平台 API 访问权限'],
  billing: ['计量管理', '按统一结果码生成调用计量明细并汇总费用'],
  monitor: ['运行监控', '监控 Gateway、资源提供商处理应用和上游服务的全链路耗时'],
  logs: ['链路追踪', '统一检索全行 API 调用，定位失败节点并查看节点级输入输出'],
  providers: ['资源方管理', '维护资源提供方资料、平台账号、角色与数据权限'],
  'platform-management': ({
    contract: ['标准响应', '维护平台统一返回结构，点击查看可浏览字段定义。'],
    codes: ['返回码字典', '维护平台返回码和结果状态，供标准服务引用。'],
    security: ['安全能力', '查看平台已注册的签名和加密算法。密钥和接口启用范围在标准服务开发中配置。'],
    audit: ['操作审计', '检索平台配置与业务操作记录']
  })[platformManagementTab.value],
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
    requestHeaders: item.requestHeaders ?? {},
    requestExample: structuredClone(item.requestExample), upstreamRequestExample: structuredClone(item.requestExample),
    upstreamResponseExample: structuredClone(item.responseExample), standardResponseExample: structuredClone(item.responseExample),
    timeoutMs: item.summary.effectiveTimeoutMs, configuredTimeoutMs: item.configuredTimeoutMs,
    timeoutSource: item.timeoutSource === 'RESOURCE_API' ? 'INTERFACE' : 'DATA_SOURCE', retryCount: 0,
    vendorCodePath: 'result.code', mappings: [], responseCodes: [],
    responsePolicy: { dataMode: 'WHOLE_BODY', codeMode: 'VENDOR_CODE', successCode: '0000' },
    executionMode: item.executionMode ?? 'HTTP', sqlTemplate: item.sqlTemplate ?? '', sqlParameters: item.sqlParameters ?? {},
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
const mappingModeLabel = computed(() => mappingDirection.value === 'REQUEST'
  ? (mappingIsPassThrough.value ? '自动透传' : '字段转换')
  : mappingIsPassThrough.value ? '默认封装到 data' : '响应映射')
const responseStructurePreview = computed(() => {
  if (!detail.value) return '{}'
  try {
    const supplier = JSON.parse(responseText.value) as Record<string, unknown>
    if (!supplier || typeof supplier !== 'object' || Array.isArray(supplier)) return '请填写 JSON 对象样例'
    const policy = detail.value.responsePolicy
    const upstreamCode = readPreviewPath(supplier, detail.value.vendorCodePath)
    const matched = detail.value.responseCodes.find(item => item.vendorCode === String(upstreamCode ?? ''))
    const status = policy.codeMode === 'HTTP_SUCCESS' ? 'HIT' : matched?.resultStatus || 'ERROR'
    const result: Record<string, unknown> = {
      code: policy.codeMode === 'HTTP_SUCCESS' ? policy.successCode || '0000' : matched?.standardCode || 'EXT-VENDOR-ERROR',
      message: status === 'HIT' ? '查询成功' : status === 'MISS' ? '未查询到符合条件的数据' : '供应商返回异常',
      resultStatus: status, traceId: 'PREVIEW' }
    const rows = detail.value.mappings.filter(item => item.direction === 'RESPONSE')
    if (!rows.length && status === 'HIT') result.data = supplier
    for (const row of rows) {
      if (!row.targetPath.trim()) continue
      const path = row.targetPath.startsWith('$.') ? row.targetPath.slice(2)
        : row.targetPath === 'data' || row.targetPath.startsWith('data.') ? row.targetPath : `data.${row.targetPath}`
      if (!/^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(path)) continue
      if (['code', 'message', 'resultStatus', 'traceId', 'msg', 'response_code', 'resp_code', 'status'].includes(path.split('.')[0])
        && !['code', 'message'].includes(path)) return `不能覆盖平台运行字段或将标准字段改为对象：${row.targetPath}`
      if (status !== 'HIT' && !['code', 'message'].includes(path)) continue
      let source = row.transformType === 'WHOLE_BODY' ? supplier : readPreviewPath(supplier, row.sourcePath)
      if (source == null || String(source).trim() === '') source = row.defaultValue
      if (row.transformType === 'CONSTANT') source = row.transformConfig?.value ?? source
      if (row.transformType === 'ENUM' && source != null) source = row.transformConfig?.[String(source)] ?? row.defaultValue ?? source
      if (row.required && (source == null || String(source).trim() === '')) return `必填映射字段缺失：${row.sourcePath}`
      if (source == null) continue
      if (['code', 'message'].includes(path)) {
        if (typeof source === 'object') return `平台响应字段 $.${path} 只能赋予文本、数字或布尔值`
        if (String(source).trim() === '') continue
        source = String(source)
      }
      writePreviewPath(result, path, source)
    }
    if (!('data' in result)) result.data = {}
    return JSON.stringify(result, null, 2)
  } catch { return '上游响应样例不是有效的 JSON' }
})
function readPreviewPath(source: Record<string, unknown>, path: string): unknown {
  if (path === '$') return source
  return path.replace(/^\$\./, '').split('.').reduce<unknown>((value, key) =>
    value && typeof value === 'object' ? (value as Record<string, unknown>)[key] : undefined, source)
}
function writePreviewPath(target: Record<string, unknown>, path: string, value: unknown) {
  const parts = path.split('.')
  let current = target
  for (const part of parts.slice(0, -1)) {
    if (!current[part] || typeof current[part] !== 'object' || Array.isArray(current[part])) current[part] = {}
    current = current[part] as Record<string, unknown>
  }
  current[parts[parts.length - 1]] = value
}
const hasUnpublishedChanges = computed(() => !!detail.value && detail.value.summary.publishedVersion !== detail.value.summary.currentVersion)
const authorizableInterfaces = computed(() => allInterfaces.value.filter(item =>
  item.publishedVersion != null && !['OFFLINE', 'DELETED'].includes(item.status)))
const activeClients = computed(() => clients.value.filter(item => item.status === 'ACTIVE'))
const today = computed(() => new Date().toISOString().slice(0, 10))
const validAuthorizations = computed(() => authorizations.value.filter(item => item.status === 'ACTIVE'
  && item.validFrom <= today.value && item.validTo >= today.value))
const existingAuthInterfaceIds = computed(() => new Set(authorizations.value
  .filter(grant => grant.clientId === authForm.value.clientId).map(grant => grant.interfaceId)))
const availableAuthInterfaces = computed(() => authorizableInterfaces.value.filter(item =>
  !existingAuthInterfaceIds.value.has(item.id)))
watch(() => authForm.value.clientId, () => {
  if (authDialogMode.value === 'EDIT') return
  if (!availableAuthInterfaces.value.some(item => item.id === authForm.value.interfaceId)) {
    authForm.value.interfaceId = availableAuthInterfaces.value[0]?.id ?? 0
  }
})
function authorizationStatus(item: AuthorizationRecord) {
  if (item.status !== 'ACTIVE') return { text: '已停用', type: 'info' as const }
  if (item.validFrom > today.value) return { text: '待生效', type: 'warning' as const }
  if (item.validTo < today.value) return { text: '已过期', type: 'danger' as const }
  return { text: '生效', type: 'success' as const }
}
const existingBatchAuthInterfaceIds = computed(() => new Set(authorizations.value
  .filter(grant => grant.clientId === batchAuthForm.value.clientId).map(grant => grant.interfaceId)))
function hasAuthorization(interfaceId: number) { return existingBatchAuthInterfaceIds.value.has(interfaceId) }
const filteredBatchAuthorizationInterfaces = computed(() => {
  const keyword = batchAuthFilters.value.keyword.trim().toLowerCase()
  return authorizableInterfaces.value.filter(item => {
    if (batchAuthFilters.value.sourceId && item.dataSourceId !== batchAuthFilters.value.sourceId) return false
    if (keyword && !`${item.interfaceName}${item.interfaceCode}${item.servicePath}`.toLowerCase().includes(keyword)) return false
    return !hasAuthorization(item.id)
  })
})
watch(() => batchAuthForm.value.clientId, () => {
  batchAuthForm.value.interfaceIds = []
  batchAuthPage.value = 1
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
watch(selectedProviderId, () => { databaseConnectionResult.value = null })
const pendingApprovals = computed(() => approvals.value.filter(item => item.status === 'PENDING'))
const logSelectedNode = computed(() => callTrace.value?.nodes.find(node => node.key === selectedLogNodeKey.value) ?? callTrace.value?.nodes[0])
const failedCallCount = computed(() => callLogs.value.filter(item => item.requestStatus !== 'SUCCESS' || item.resultStatus === 'ERROR').length)
const overviewSuccessRate = computed(() => monitorSummary.value.totalCalls > 0 ? monitorSummary.value.successRate : 100)
const overviewMaxCalls = computed(() => Math.max(0, ...monitorPoints.value.map(item => item.calls)))
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
const monitorTimeline = computed(() => monitorPoints.value.filter(point => point.calls > 0))
const activeMonitorPoint = computed(() => activeMonitorPointIndex.value == null ? null : monitorTimeline.value[activeMonitorPointIndex.value])
const monitorPlatformDuration = computed(() => Math.max(2, monitorSummary.value.avgDurationMs - monitorSummary.value.avgUpstreamDurationMs))
const monitorTopologyNodes = computed(() => {
  const gatewayDuration = Math.max(1, Math.round(monitorPlatformDuration.value * .42))
  const adapterDuration = Math.max(1, monitorPlatformDuration.value - gatewayDuration)
  return [
    { key: 'GATEWAY', name: '统一 Gateway', code: 'ACCESS', duration: gatewayDuration, description: '认证、授权、配额与动态路由', status: gatewayDuration > 200 ? 'WARNING' : 'HEALTHY', icon: Connection },
    { key: 'ADAPTER', name: '资源提供商处理应用', code: 'ADAPTER', duration: adapterDuration, description: '协议适配、字段转换与供应商调用', status: adapterDuration > 300 ? 'WARNING' : 'HEALTHY', icon: Files },
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
    && JSON.stringify(detail.value.responsePolicy) === savedResponsePolicyBaseline.value
    && requestText.value === savedRequestBaseline.value
    && responseText.value === savedResponseBaseline.value
    && JSON.stringify(securityPolicy.value) === savedSecurityBaseline.value
    && (detail.value.responsePolicy.codeMode === 'HTTP_SUCCESS'
      || JSON.stringify(detail.value.responseCodes) === savedResponseCodesBaseline.value
        && (detail.value.vendorCodePath || 'result.code') === (savedVendorCodePathBaseline.value || 'result.code'))
    && JSON.stringify(standardServiceForm.value) === savedStandardServiceBaseline.value
}
const apiGuideSteps = computed(() => buildResourceGuide({
  upstreamUrl: detail.value?.summary.upstreamUrl ?? '', servicePath: detail.value?.summary.servicePath ?? '',
  mappings: savedMappingsBaseline.value, responseCodes: JSON.parse(savedResponseCodesBaseline.value || '[]'),
  responsePolicy: JSON.parse(savedResponsePolicyBaseline.value || 'null') ?? undefined,
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
    || JSON.stringify(detail.value.responsePolicy) !== savedResponsePolicyBaseline.value
    || requestText.value !== savedRequestBaseline.value || responseText.value !== savedResponseBaseline.value
    || JSON.stringify(standardServiceForm.value) !== savedStandardServiceBaseline.value
  if (activeTab.value === 'codes') return JSON.stringify(detail.value.responsePolicy) !== savedResponsePolicyBaseline.value
    || (detail.value.responsePolicy.codeMode !== 'HTTP_SUCCESS'
      && (JSON.stringify(detail.value.responseCodes) !== savedResponseCodesBaseline.value
        || detail.value.vendorCodePath !== savedVendorCodePathBaseline.value))
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
  if (!loginForm.value.username.trim() || !loginForm.value.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loginLoading.value = true
  try {
    user.value = await api.login(loginForm.value.username.trim(), loginForm.value.password)
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
    const loadCatalog = ['resource', 'service-development', 'authorization', 'billing'].includes(activeMenu.value)
    const loadResourceCatalog = ['resource', 'service-development'].includes(activeMenu.value)
    const loadAuth = activeMenu.value === 'authorization'
    const [dashboardData, providerData, sourceData, resourceApiData, interfaceData, clientData, authData, authSummary, rules, summary, usage, monitor, points, alerts, approvalData, algorithmData] = await Promise.all([
      api.dashboard(), api.providers(), api.dataSources(), loadResourceCatalog ? api.resourceApis() : Promise.resolve([]), loadCatalog ? api.interfaces() : Promise.resolve([]), api.clients(), loadAuth ? api.authorizations() : Promise.resolve([]), loadAuth ? Promise.resolve({ activeCount: 0 }) : api.authorizationSummary(), api.billingRules(), api.billingSummary(), api.usageRecords(),
      api.monitorSummary(), api.monitorPoints(), api.monitorAlerts(), api.approvals(), loadSecurityAlgorithms()
    ])
    dashboard.value = dashboardData
    providers.value = providerData
    sources.value = sourceData
    if (loadResourceCatalog) allResourceApis.value = resourceApiData
    if (loadCatalog) {
      allInterfaces.value = interfaceData
      interfaceCatalogLoaded.value = true
      interfaceCache.clear()
      sourceData.forEach(source => {
        interfaceCache.set(source.id, interfaceData.filter(item => item.dataSourceId === source.id))
      })
    }
    clients.value = clientData
    authorizations.value = authData
    activeAuthorizationCount.value = loadAuth
      ? authData.filter(item => item.status === 'ACTIVE' && item.validFrom <= today.value && item.validTo >= today.value && clientData.some(client => client.id === item.clientId && client.status === 'ACTIVE')).length
      : authSummary.activeCount
    billingRules.value = rules
    billingSummary.value = summary
    usageRecords.value = usage
    monitorSummary.value = monitor
    monitorPoints.value = points
    monitorAlerts.value = alerts
    approvals.value = approvalData
    securityAlgorithms.value = algorithmData
    try { platformResponseCodes.value = await api.platformResponseCodes() }
    catch { platformResponseCodes.value = [] }
    try { await refreshPlatformContracts() }
    catch { platformResponseContracts.value = [] }
    demo.value = isDemoMode()
    if (!selectedSourceId.value && sourceData.length) selectedSourceId.value = sourceData[0].id
    if (!selectedProviderId.value && providerData.length) { selectedProviderId.value = providerData[0].id; await loadProviderAccounts() }
    if (loadCatalog) await loadInterfaces(false)
  } finally { loading.value = false }
}

async function refreshOverview() {
  const [dashboardData, monitor, points, billing, usage] = await Promise.all([
    api.dashboard(), api.monitorSummary(), api.monitorPoints(), api.billingSummary(), api.usageRecords()
  ])
  dashboard.value = dashboardData
  monitorSummary.value = monitor
  monitorPoints.value = points
  billingSummary.value = billing
  usageRecords.value = usage
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

async function loadPlatformInterfaceCatalog() {
  const rows = await api.interfaces()
  allInterfaces.value = rows
  interfaceCatalogLoaded.value = true
  interfaceCache.clear()
  sources.value.forEach(source => interfaceCache.set(source.id, rows.filter(item => item.dataSourceId === source.id)))
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
    loadedDetail.responsePolicy = loadedDetail.responsePolicy ?? {
      dataMode: loadedDetail.mappings.some(item => item.direction === 'RESPONSE') ? 'FIELD_MAPPING' : 'WHOLE_BODY',
      codeMode: 'VENDOR_CODE', successCode: '0000'
    }
    if (!resourceWorkspace && loadedDetail.responsePolicy.dataMode === 'WHOLE_BODY') {
      loadedDetail.mappings = loadedDetail.mappings.filter(item => item.direction !== 'RESPONSE')
      loadedDetail.mappings.push({ direction: 'RESPONSE', sourcePath: '$', targetPath: '$.data',
        transformType: 'WHOLE_BODY', transformConfig: {}, defaultValue: '', required: false, sortOrder: 10 })
      loadedDetail.responsePolicy.dataMode = 'FIELD_MAPPING'
    }
    if (!resourceWorkspace) detailCache.set(requestedInterfaceId, structuredClone(loadedDetail))
    detail.value = loadedDetail
    interfaceVersions.value = loadedVersions
    guideError.value = ''
    connectionForm.value = {
      configScope: loadedDetail.summary.configScope ?? 'OVERRIDE',
      upstreamPath: loadedDetail.summary.upstreamPath ?? '',
      upstreamUrl: loadedDetail.summary.upstreamUrl,
      timeoutMode: loadedDetail.configuredTimeoutMs == null ? 'INHERIT' : 'OVERRIDE',
      timeoutMs: loadedDetail.configuredTimeoutMs ?? loadedDetail.timeoutMs,
      requestHeaders: resourceWorkspace
        ? Object.entries(loadedDetail.requestHeaders ?? {}).map(([name, value]) => ({ name, value }))
        : [],
      executionMode: resourceWorkspace ? (loadedDetail.executionMode ?? 'HTTP') : 'HTTP',
      sqlTemplate: resourceWorkspace ? (loadedDetail.sqlTemplate ?? '') : '',
      sqlParameters: resourceWorkspace ? JSON.stringify(loadedDetail.sqlParameters ?? {}, null, 2) : '{}'
    }
    securityPolicy.value = loadedDetail.securityPolicy ?? { interfaceId: loadedDetail.summary.id, enabled: false, encryptMode: 'NONE', signatureMode: 'NONE', requestFields: '', encryptionKeyReference: '', signingKeyReference: '', signatureField: 'signature', description: '' }
    savedMappingsBaseline.value = structuredClone(loadedDetail.mappings)
    savedResponsePolicyBaseline.value = JSON.stringify(loadedDetail.responsePolicy)
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
    await ElMessageBox.confirm('更新草稿的上游地址、超时及请求/响应结构。已有映射保留，未单独修改的透传样例随之更新；线上版本不变，更新后需重新验证。', '同步资源接口定义', { confirmButtonText: '同步到草稿', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  try {
    guideBusy.value = true
    await api.syncResourceContract(id)
    detailCache.delete(id)
    if (selectedInterfaceId.value === id) await loadDetail()
    ElMessage.success('资源接口定义已同步，请核对映射；可按需执行服务编排测试')
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
      detail.value.responsePolicy = JSON.parse(savedResponsePolicyBaseline.value)
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
  if (menu === 'overview') void refreshOverview()
  if (menu === 'authorization') {
    await loadPlatformInterfaceCatalog()
  } else if (['resource', 'service-development', 'billing'].includes(menu)) {
    if (!interfaceCatalogLoaded.value) await loadPlatformInterfaceCatalog()
  }
  if (menu === 'authorization') {
    authorizations.value = await api.authorizations()
    activeAuthorizationCount.value = authorizations.value.filter(item => item.status === 'ACTIVE' && item.validFrom <= today.value && item.validTo >= today.value && clients.value.some(client => client.id === item.clientId && client.status === 'ACTIVE')).length
  }
  if (['resource', 'service-development'].includes(menu) && !allResourceApis.value.length) allResourceApis.value = await api.resourceApis()
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
  if (menu === 'discovery') void loadResourceDirectory()
  if (menu === 'documents') void loadResourceDocuments()
  if (menu === 'reports') void loadUsageReport()
  if (menu === 'monitor') void refreshMonitor()
  if (menu === 'logs') void searchCallLogs()
  if (menu === 'providers' && selectedProviderId.value) await loadProviderAccounts()
  if (menu === 'platform-management') {
    const [, , algorithms] = await Promise.all([refreshPlatformCodes(), refreshPlatformContracts(), loadSecurityAlgorithms()])
    securityAlgorithms.value = algorithms
    if (platformManagementTab.value === 'audit') void loadOperationAudit()
  }
  if (menu === 'approvals') void refreshApprovals()
  if (menu === 'releases') void loadReleaseServices()
  await nextTick()
  document.querySelector('.page-heading')?.scrollIntoView({ block: 'start' })
}
function openPlatformManagement(tab: PlatformManagementTab = 'contract') {
  platformManagementTab.value = tab
  if (activeMenu.value !== 'platform-management') void navigate('platform-management')
  else if (tab === 'audit') void loadOperationAudit()
}
function viewPlatformContract(item: PlatformResponseContract) {
  selectedPlatformContractId.value = item.id ?? 0
  platformContractFieldsDrawer.value = true
}
async function refreshCurrentWorkspace() {
  if (guideBusy.value || testRunning.value || connectionSaving.value) return
  if (['resource', 'service-development'].includes(activeMenu.value) && !await confirmDiscardUnsaved()) return
  try {
    if (activeMenu.value === 'overview') await refreshOverview()
    else if (activeMenu.value === 'discovery') await loadResourceDirectory()
    else if (activeMenu.value === 'documents') await loadResourceDocuments()
    else if (activeMenu.value === 'reports') await loadUsageReport()
    else if (activeMenu.value === 'monitor') await refreshMonitor()
    else if (activeMenu.value === 'logs') await searchCallLogs()
    else if (activeMenu.value === 'releases') await Promise.all([loadReleaseServices(), releaseDetailDrawer.value ? loadReleaseManagement() : Promise.resolve()])
    else if (activeMenu.value === 'platform-management') {
      await refreshPlatformCodes()
      await refreshPlatformContracts()
      securityAlgorithms.value = await loadSecurityAlgorithms()
      if (platformManagementTab.value === 'audit') await loadOperationAudit()
    }
    else await loadAll()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '刷新失败，请重试') }
}
function editReleaseRuntime() {
  if (!releaseDetail.value) return
  goToProviderManagement(releaseDetail.value.summary.dataSourceId)
}
async function goToProviderManagement(sourceId = selectedSourceId.value) {
  const providerId = sources.value.find(item => item.id === sourceId)?.providerId
  await navigate('providers')
  if (activeMenu.value === 'providers' && providerId) {
    selectedProviderId.value = providerId
    await loadProviderAccounts()
  }
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
  publishedInterfaceDetail.value = null
  try { publishedInterfaceDetail.value = await api.detail(interfaceId) }
  catch (error) { publishedInterfaceDetail.value = null; ElMessage.error(error instanceof Error ? error.message : '接口详情加载失败') }
  finally { publishedDetailLoading.value = false }
}
function versionRowClass(row: InterfaceVersionHistory) { return row.version === highlightedVersion.value ? 'version-row-highlight' : '' }
async function openVersionHistory(version?: number) {
  releaseInterfaceId.value = selectedInterfaceId.value
  highlightedVersion.value = version ?? null
  activeMenu.value = 'releases'
  releaseDetailDrawer.value = true
  void loadReleaseServices()
  await loadReleaseManagement(releaseInterfaceId.value)
  highlightedVersion.value = version ?? null
}
async function loadReleaseManagement(interfaceId = releaseInterfaceId.value) {
  if (!interfaceId) return
  const requestSequence = ++releaseRequestSequence
  releaseInterfaceId.value = interfaceId
  releaseLoading.value = true
  releaseDetail.value = null
  releaseVersions.value = []
  try {
    const [nextDetail, nextVersions] = await Promise.all([api.detail(interfaceId), api.interfaceVersions(interfaceId)])
    if (requestSequence !== releaseRequestSequence || interfaceId !== releaseInterfaceId.value) return
    releaseDetail.value = nextDetail
    releaseVersions.value = nextVersions
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '发布档案加载失败') }
  finally { if (requestSequence === releaseRequestSequence) releaseLoading.value = false }
}
async function loadReleaseServices(reset = false) {
  if (reset) releaseServicePage.value = 1
  const requestSequence = ++releaseServiceRequestSequence
  releaseServiceLoading.value = true
  try {
    const result = await api.releaseServices({ ...releaseFilters.value, page: releaseServicePage.value, size: releaseServiceSize.value })
    if (requestSequence !== releaseServiceRequestSequence) return
    releaseServiceRows.value = result.rows
    releaseServiceTotal.value = result.total
    if (!result.rows.length && result.total > 0 && releaseServicePage.value > 1) {
      releaseServicePage.value -= 1
      return await loadReleaseServices()
    }
    if (!result.rows.some(row => row.service.id === releaseInterfaceId.value)) releaseDetailDrawer.value = false
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '服务列表加载失败') }
  finally { if (requestSequence === releaseServiceRequestSequence) releaseServiceLoading.value = false }
}
function applyReleaseQuickView(view: string) {
  releaseQuickView.value = view
  if (view === 'RUNNING_AUTHORIZED') { releaseFilters.value.runStatus = 'RUNNING'; releaseFilters.value.authorizationStatus = 'AUTHORIZED' }
  else if (view === 'RUNNING_UNAUTHORIZED') { releaseFilters.value.runStatus = 'RUNNING'; releaseFilters.value.authorizationStatus = 'UNAUTHORIZED' }
  else if (view === 'OFFLINE') { releaseFilters.value.runStatus = 'OFFLINE'; releaseFilters.value.authorizationStatus = 'ALL' }
  else { releaseFilters.value.runStatus = 'ALL'; releaseFilters.value.authorizationStatus = 'ALL' }
  void loadReleaseServices(true)
}
function releaseRunStatusLabel(status: ReleaseServiceRow['runStatus']) {
  return ({ UNPUBLISHED: '未发布', RUNNING: '运行中', OFFLINE: '已下线' } as const)[status]
}
function releaseServiceAuthLabel(row: ReleaseServiceRow) {
  return row.activeAuthorizationClientCount > 0 ? `${row.activeAuthorizationClientCount} 个调用系统` : '无有效授权'
}
function handleReleaseServiceRowClick(row: ReleaseServiceRow, column: { label?: string }) {
  if (column.label !== '操作') void selectReleaseInterface(row.service.id)
}
async function selectReleaseInterface(interfaceId: number) {
  releaseDetailDrawer.value = true
  highlightedVersion.value = null
  await loadReleaseManagement(interfaceId)
}
async function handleReleaseRowCommand(command: string, row: ReleaseServiceRow) {
  const interfaceId = row.service.id
  if (command === 'published') return openPublishedDetail(interfaceId)
  if (command === 'authorize') {
    authForm.value.interfaceId = interfaceId
    navigate('authorization')
    return
  }
  if (command === 'archive') return archiveRelease(row.service)
}
async function submitReleaseFromManagement(summary: InterfaceSummary) {
  if (summary.status === 'PENDING_APPROVAL' || summary.currentVersion === summary.publishedVersion) return
  try {
    const check = await api.validate(summary.id)
    if (!check.valid) throw new Error(check.errors.join('；') || '发布前校验未通过')
    const { value: releaseNote } = await ElMessageBox.prompt(`确认发布 ${summary.interfaceCode} V${summary.currentVersion}？`, '发布服务', {
      confirmButtonText: '确认发布', cancelButtonText: '取消', inputPlaceholder: '填写本次变更内容',
      inputValidator: value => !!value.trim() || '请填写发布说明'
    })
    await api.directPublish(summary.id, releaseNote.trim())
    ElMessage.success(`V${summary.currentVersion} 已发布并生效`)
    await Promise.all([loadReleaseServices(), loadAll()])
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '服务发布失败') }
}
async function offlineRelease(summary: InterfaceSummary) {
  try {
    await ElMessageBox.confirm('下线后线上调用将停止，历史版本和链路记录仍会保留。确认下线吗？', '确认下线接口', { type: 'warning', confirmButtonText: '确认下线', cancelButtonText: '取消' })
    await api.offlineInterface(summary.id)
    ElMessage.success('接口已下线，线上执行计划已撤销')
    await Promise.all([loadReleaseServices(), loadAll()])
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '下线失败') }
}
async function onlineRelease(summary: InterfaceSummary) {
  if (!summary.publishedVersion) return
  try {
    await ElMessageBox.confirm(`将恢复最近一次已发布的 V${summary.publishedVersion} 执行计划和 Gateway 路由。${summary.currentVersion !== summary.publishedVersion ? `V${summary.currentVersion} 草稿会继续保留，不会进入线上流量。` : ''}现有有效授权将恢复调用，确认重新上线吗？`, '确认重新上线', { type: 'warning', confirmButtonText: '恢复线上服务', cancelButtonText: '取消' })
    await api.onlineInterface(summary.id)
    ElMessage.success(`线上 V${summary.publishedVersion} 已恢复`)
    await Promise.all([loadReleaseServices(), loadAll()])
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '重新上线失败') }
}
async function archiveRelease(target: InterfaceSummary) {
  try {
    await ElMessageBox.confirm('归档后资源目录不再展示该 API，但版本、审批和链路记录仍会保留。确认归档删除吗？', '确认归档删除', { type: 'warning', confirmButtonText: '归档删除', cancelButtonText: '取消' })
    await api.deleteInterface(target.id)
    removeInterfaceFromLocalState(target)
    ElMessage.success('API 已归档，历史审计数据已保留')
    releaseDetailDrawer.value = false
    releaseDetail.value = null
    releaseVersions.value = []
    releaseInterfaceId.value = 0
    await loadReleaseServices()
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
function accessConfigDefaults(mode = 'HTTP') {
  if (mode === 'DATABASE') return { databaseType: 'MYSQL', driver: 'com.mysql.cj.jdbc.Driver', jdbcUrl: '', username: '', password: '', initialSize: 2, minIdle: 2, maxActive: 20 }
  if (mode === 'PLATFORM_SERVICE') return { serviceName: '' }
  if (mode === 'KAFKA') return { brokerList: '', securityProtocol: 'PLAINTEXT' }
  return { baseUrl: '' }
}
function changeProviderAccessMode(mode: string) { providerForm.value.accessConfig = accessConfigDefaults(mode) }
function openProviderEditor(provider = selectedProvider.value) {
  if (!provider) return
  providerForm.value = { ...provider, accessMode: provider.accessMode || 'HTTP', accessConfig: { ...accessConfigDefaults(provider.accessMode || 'HTTP'), ...(provider.accessConfig || {}), password: '' } }
  providerDialog.value = true
}
function openNewProvider() {
  providerForm.value = {
    id: 0, providerCode: '', providerName: '', providerType: 'DATA_VENDOR',
    accessDate: '', messageEncoding: 'UTF-8', chargeRequired: false,
    encryptionRequired: false, loginRequired: false, status: 'ACTIVE', accessMode: 'HTTP', accessConfig: accessConfigDefaults('HTTP')
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
    databaseConnectionResult.value = null
    providerAccounts.value = []
    providerDialog.value = false
    ElMessage.success(isNew ? '资源提供商已新增' : '资源提供商资料已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资源提供商保存失败')
  } finally {
    providerSaving.value = false
  }
}
async function testDatabaseConnection() {
  const provider = selectedProvider.value
  if (!provider || provider.accessMode !== 'DATABASE' || databaseConnectionTesting.value) return
  databaseConnectionTesting.value = true
  databaseConnectionResult.value = null
  try {
    const result = await api.testDatabaseConnection(provider.id)
    if (selectedProviderId.value === provider.id) databaseConnectionResult.value = result
  } catch (error) {
    if (selectedProviderId.value === provider.id) databaseConnectionResult.value = {
      success: false,
      message: error instanceof Error ? error.message : '连接测试失败',
      durationMs: 0,
      databaseProduct: null
    }
  } finally {
    databaseConnectionTesting.value = false
  }
}
async function deleteSelectedProvider() {
  const provider = selectedProvider.value
  if (!provider) return
  const routeNotice = provider.runtimeUrl
    ? '\n该资源方的平台路由登记将一并移除，但不会停止处理应用，也不会删除 Nacos 配置或生成的代码。'
    : ''
  try {
    await ElMessageBox.confirm(
      `确认删除资源提供商“${provider.providerName}”（${provider.providerCode}）？仅当其下没有数据源、资源提供商账号和接口文档时才允许删除。${routeNotice}`,
      '删除资源提供商',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', distinguishCancelAndClose: true }
    )
    await api.deleteProvider(provider.id)
    providers.value = providers.value.filter(item => item.id !== provider.id)
    providerAccounts.value = []
    selectedProviderId.value = providers.value[0]?.id ?? 0
    if (selectedProviderId.value) await loadProviderAccounts()
    dashboard.value.providerCount = Math.max(0, dashboard.value.providerCount - 1)
    ElMessage.success('资源提供商已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '资源提供商删除失败')
  }
}
function openAccountDialog() { accountForm.value = { username: '', displayName: '', mobile: '', email: '', roleCode: 'PROVIDER_ADMIN', permissions: ['DATASOURCE_VIEW', 'API_VIEW'], status: 'ACTIVE' }; accountDialog.value = true }
async function saveAccount() { if (!selectedProviderId.value) return; await api.createProviderAccount(selectedProviderId.value, accountForm.value); accountDialog.value = false; await loadProviderAccounts(); ElMessage.success('资源提供商账号已创建') }
function clearLocalSession() { loggedIn.value = false; user.value = null; api.clearOperatorSession(); activeMenu.value = 'overview' }
async function logout() {
  try { await api.logout() }
  catch (error) { ElMessage.warning(error instanceof Error ? `退出记录未能保存：${error.message}` : '退出记录未能保存') }
  finally { clearLocalSession() }
}
function handleSessionExpired() {
  if (!loggedIn.value) return
  clearLocalSession()
  ElMessage.warning('登录会话已失效，请重新登录')
}
if (typeof window !== 'undefined') window.addEventListener('edsp:session-expired', handleSessionExpired)

function openSourceWizard() {
  sourceStep.value = 0
  sourceError.value = ''
  sourceForm.value = { providerId: providers.value[0]?.id ?? 0, sourceCode: '', sourceName: '', protocolType: 'HTTP_JSON', endpoint: '', defaultTimeoutMs: 3000 }
  applySourceProviderDefaults()
  sourceDialog.value = true
}
function applySourceProviderDefaults() {
  const provider = providers.value.find(item => item.id === sourceForm.value.providerId)
  if (provider?.accessMode === 'HTTP' || !provider?.accessMode) {
    sourceForm.value.endpoint = String(provider?.accessConfig?.baseUrl || '')
  }
}
function advanceSourceWizard() {
  if (!sourceForm.value.providerId || !sourceForm.value.sourceName.trim() || !sourceForm.value.sourceCode.trim()) {
    sourceError.value = '请先选择资源提供商，并填写数据源名称和编码。'
    return
  }
  if (!/^[A-Za-z0-9_-]+$/.test(sourceForm.value.sourceCode.trim())) {
    sourceError.value = '数据源编码不能包含空格或特殊字符。'
    return
  }
  if (!sourceForm.value.endpoint.trim()) applySourceProviderDefaults()
  sourceError.value = ''
  sourceStep.value = 1
}
async function createSource() {
  if (sourceSaving.value) return
  if (sourceForm.value.protocolType !== 'LOCAL_SQL' && !isHttpEndpoint(sourceForm.value.endpoint.trim())) {
    sourceError.value = '请填写有效的资源提供商基础地址，以 http:// 或 https:// 开头。'
    return
  }
  sourceError.value = ''
  sourceSaving.value = true
  try {
  const created = await api.createDataSource({ ...sourceForm.value,
    sourceCode: sourceForm.value.sourceCode.trim(), sourceName: sourceForm.value.sourceName.trim(), endpoint: sourceForm.value.endpoint.trim(),
    instanceCount: 1 })
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

async function openSourceRuntimeEditor() {
  const provider = selectedProvider.value
  if (!provider) return
  selectedProviderId.value = provider.id
  sourceRuntimeResult.value = null
  sourceRuntimeStatusLoaded.value = false
  sourceRuntimeForm.value = {
    applicationName: provider.applicationName || `jsnx-edsp-data-provider-${provider.providerCode.toLowerCase().replace(/_/g, '-')}`,
    runtimeUrl: provider.runtimeUrl ?? '',
    serverPort: provider.serverPort || runtimeAddressPort(provider.runtimeUrl) || 8199
  }
  sourceRuntimeDialog.value = true
  sourceRuntimeStatusLoading.value = true
  const [settings, status] = await Promise.allSettled([
    api.dataApplicationProvisionSettings(), api.providerRuntimeStatus(provider.id)
  ])
  provisionSettings.value = settings.status === 'fulfilled' ? settings.value : null
  if (status.status === 'fulfilled') {
    sourceRuntimeResult.value = status.value
    sourceRuntimeStatusLoaded.value = true
  }
  sourceRuntimeStatusLoading.value = false
}

function runtimeAddressPort(value?: string) {
  if (!value) return null
  try {
    const parsed = new URL(value)
    if (parsed.port) return Number(parsed.port)
    return parsed.protocol === 'https:' ? 443 : parsed.protocol === 'http:' ? 80 : null
  } catch { return null }
}

function provisionStatusLabel(status: string) {
  return ({ SUCCESS: '已完成', REUSED: '已复用', REQUESTED: '已触发', FAILED: '失败', SKIPPED: '已跳过' } as Record<string, string>)[status] ?? status
}
function hasProvisionedRuntime() {
  const result = sourceRuntimeResult.value
  return !!result && ['SUCCESS', 'REUSED'].includes(result.generation.status)
}
function providerAccessModeLabel(mode?: string) {
  return ({ HTTP: 'HTTP 接口', DATABASE: '数据库', PLATFORM_SERVICE: '平台微服务', KAFKA: 'Kafka' } as Record<string, string>)[mode || 'HTTP'] || mode || '-'
}
function providerCategoryLabel(value?: string) {
  return ({ DATA_VENDOR: '数据资源提供商', CREDIT: '征信机构', GOVERNMENT: '政务数据机构', OTHER: '其他' } as Record<string, string>)[value || ''] || value || '-'
}
function providerRoleLabel(value: string) {
  return ({ PROVIDER_ADMIN: '资源提供商管理员', AUDITOR: '审计人员', API_DEVELOPER: '接口开发' } as Record<string, string>)[value] || value
}
function providerPermissionLabel(value: string) {
  return ({ DATASOURCE_VIEW: '数据源查看', DATASOURCE_EDIT: '数据源编辑', API_VIEW: '接口查看', API_EDIT: '接口编辑', API_TEST: '接口测试', CATALOG_PUBLISH: '目录发布', AUDIT_VIEW: '审计查看' } as Record<string, string>)[value] || value
}
function traceStatusLabel(value: string) {
  return ({ SUCCESS: '成功', ERROR: '失败', FAILED: '失败', SKIPPED: '已跳过', RUNNING: '处理中', PENDING: '待处理' } as Record<string, string>)[value] || value
}
function resultStatusLabel(value: string) {
  return ({ HIT: '命中数据', MISS: '未命中', ERROR: '调用异常' } as Record<string, string>)[value] || value
}

async function copyGeneratedPath() {
  const path = sourceRuntimeResult.value?.generation.outputDirectory
  if (!path) return
  try { await navigator.clipboard.writeText(path); ElMessage.success('生成目录已复制') }
  catch { ElMessage.warning('浏览器不允许复制，请手动复制目录') }
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
  const provider = providers.value.find(item => item.id === selectedProviderId.value)
  if (!provider || sourceRuntimeSaving.value) return
  if (!sourceRuntimeForm.value.applicationName.trim() || !sourceRuntimeForm.value.runtimeUrl.trim()
    || sourceRuntimeForm.value.serverPort < 1 || sourceRuntimeForm.value.serverPort > 65535) {
    ElMessage.warning('请填写处理应用标识、应用访问地址和有效的应用监听端口')
    return
  }
  sourceRuntimeSaving.value = true
  try {
    const result = await api.saveProviderRuntime(provider.id, {
      applicationName: sourceRuntimeForm.value.applicationName.trim(),
      runtimeUrl: sourceRuntimeForm.value.runtimeUrl.trim(),
      serverPort: sourceRuntimeForm.value.serverPort
    })
    providers.value = providers.value.map(item => item.id === result.provider.id ? result.provider : item)
    databaseConnectionResult.value = null
    sources.value = await api.dataSources()
    sourceRuntimeResult.value = result
    sourceRuntimeStatusLoaded.value = false
    if (result.generation.status === 'FAILED' || result.nacos.status === 'FAILED') {
      ElMessage.warning('访问配置已保存，工程生成或 Nacos 同步存在失败，请查看处理结果')
    } else ElMessage.success('应用路由、Java 工程和 Nacos 配置处理完成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '应用路由配置保存失败')
  } finally { sourceRuntimeSaving.value = false }
}

async function deleteCurrentInterface() {
  if (!detail.value || deletingApi.value) return
  const target = { ...detail.value.summary }
  if (!canDeleteApi.value) {
    ElMessage.warning(deleteApiRestriction.value)
    return
  }
  deletingApi.value = true
  try {
    await ElMessageBox.confirm(
      `确认归档服务「${target.interfaceName}」吗？归档后将从服务目录隐藏，不能继续编辑或发布；版本、授权和链路记录仍会保留。`,
      '归档服务',
      { confirmButtonText: '确认归档', cancelButtonText: '取消', type: 'warning' }
    )
    await api.deleteInterface(target.id)
    const nextId = removeInterfaceFromLocalState(target)
    ElMessage.success('服务已归档，历史记录已保留')
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
    timeoutMode: 'INHERIT', timeoutMs: source?.defaultTimeoutMs ?? 3000, generateMock: false, upstreamRequestExample: null,
    executionMode: source?.protocolType === 'LOCAL_SQL' ? 'SQL' : 'HTTP', sqlTemplate: '', sqlParameters: '{}'
  }
  vendorRequestExample.value = null
  vendorResponseExample.value = null
  databaseTables.value = []
  databaseColumns.value = []
  databaseSelectedTable.value = ''
  databaseSelectedFields.value = []
  databaseFilterFields.value = []
  databaseQueryMode.value = 'TABLE'
  apiDialog.value = true
  if (source?.protocolType === 'LOCAL_SQL') void loadDatabaseTables(source.id)
}

async function loadDatabaseTables(sourceId: number) {
  databaseSchemaLoading.value = true
  try {
    const result = await api.databaseTables(sourceId)
    databaseTables.value = result.tables ?? []
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取数据库表失败')
  } finally { databaseSchemaLoading.value = false }
}

async function loadDatabaseColumns(tableName: string) {
  databaseSelectedTable.value = tableName
  databaseSelectedFields.value = []
  databaseFilterFields.value = []
  databaseColumns.value = []
  if (!tableName) return
  databaseSchemaLoading.value = true
  try {
    const result = await api.databaseColumns(apiForm.value.dataSourceId, tableName)
    databaseColumns.value = result.columns ?? []
    databaseSelectedFields.value = databaseColumns.value.map(column => column.name)
    updateDatabaseQuery()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取数据库字段失败')
  } finally { databaseSchemaLoading.value = false }
}

function updateDatabaseQuery() {
  if (!databaseSelectedTable.value || !databaseSelectedFields.value.length) {
    apiForm.value.sqlTemplate = ''
    apiForm.value.sqlParameters = '{}'
    vendorRequestExample.value = {}
    return
  }
  const databaseType = String(providers.value.find(item => item.id === selectedApiSource.value?.providerId)?.accessConfig?.databaseType ?? 'MYSQL')
  const quote = (name: string) => databaseType === 'MYSQL' ? `\`${name}\`` : `"${name}"`
  const parameters = Object.fromEntries(databaseFilterFields.value.map(name => {
    const type = databaseColumns.value.find(column => column.name === name)?.type.toUpperCase() ?? ''
    const parameterType = /BOOL|BIT/.test(type) ? 'BOOLEAN'
      : /DATE/.test(type) ? (type.includes('TIME') ? 'DATETIME' : 'DATE')
        : /TIMESTAMP|DATETIME/.test(type) ? 'DATETIME'
        : /INT/.test(type) ? 'INTEGER'
          : /DECIMAL|NUMERIC|NUMBER|FLOAT|DOUBLE|REAL/.test(type) ? 'NUMBER' : 'STRING'
    return [name, parameterType]
  }))
  const filters = databaseFilterFields.value.map(name => `${quote(name)} = :${name}`)
  apiForm.value.sqlTemplate = `SELECT ${databaseSelectedFields.value.map(quote).join(', ')}\nFROM ${quote(databaseSelectedTable.value)}${filters.length ? `\nWHERE ${filters.join(' AND ')}` : ''}`
  apiForm.value.sqlParameters = JSON.stringify(parameters, null, 2)
  vendorRequestExample.value = Object.fromEntries(databaseFilterFields.value.map(name => [name, '']))
}

function openApiDialogForSource(sourceId: number) {
  const source = sources.value.find(item => item.id === sourceId)
  if (!source) return
  initializeApiDialog(source)
}

function openResourceApiMetadataEditor() {
  if (activeMenu.value !== 'resource' || !detail.value) return
  apiMetadataForm.value = {
    resourceApiCode: detail.value.summary.interfaceCode,
    resourceApiName: detail.value.summary.interfaceName,
    httpMethod: detail.value.summary.httpMethod
  }
  apiMetadataDialog.value = true
}

async function saveResourceApiMetadata() {
  if (!detail.value || apiMetadataSaving.value) return
  const resourceApiCode = apiMetadataForm.value.resourceApiCode.trim()
  const resourceApiName = apiMetadataForm.value.resourceApiName.trim()
  if (!resourceApiCode || !resourceApiName) {
    ElMessage.warning('请完整填写资源 API 名称和编码')
    return
  }
  if (!/^[A-Za-z0-9_-]+$/.test(resourceApiCode)) {
    ElMessage.warning('资源 API 编码不能包含空格或特殊字符')
    return
  }
  apiMetadataSaving.value = true
  try {
    const updated = await api.updateResourceApiMetadata(detail.value.summary.id, {
      resourceApiCode,
      resourceApiName,
      httpMethod: apiMetadataForm.value.httpMethod
    })
    const index = allResourceApis.value.findIndex(item => item.id === updated.id)
    if (index >= 0) allResourceApis.value[index] = updated
    detail.value.summary = {
      ...detail.value.summary,
      interfaceCode: updated.resourceApiCode,
      interfaceName: updated.resourceApiName,
      httpMethod: updated.httpMethod,
      currentVersion: updated.currentVersion,
      upstreamStatus: updated.status,
      updatedAt: updated.updatedAt
    }
    lastUpstreamValidation.value = null
    apiMetadataDialog.value = false
    ElMessage.success('资源 API 基本信息已更新，请按需重新验证上游接口')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资源 API 基本信息保存失败')
  } finally {
    apiMetadataSaving.value = false
  }
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
    testResult.value = null
    testSucceeded.value = false
    lastUpstreamValidation.value = null
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
  if (apiForm.value.executionMode === 'SQL' && databaseQueryMode.value === 'TABLE'
      && (!databaseSelectedTable.value || !databaseSelectedFields.value.length)) {
    ElMessage.warning('请选择数据表和至少一个返回字段')
    return
  }
  if (apiForm.value.executionMode === 'SQL' && !apiForm.value.sqlTemplate.trim()) {
    ElMessage.warning('请配置数据库查询语句')
    return
  }
  apiCreating.value = true
  let created: ResourceApiSummary
  try {
    let sqlParameters: Record<string, string> = {}
    try {
      const parsed = JSON.parse(apiForm.value.sqlParameters || '{}')
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error()
      sqlParameters = parsed
    } catch {
      ElMessage.warning('SQL 参数必须是有效的 JSON 对象')
      apiCreating.value = false
      return
    }
    let requestExample = vendorRequestExample.value ?? {}
    if (apiForm.value.executionMode === 'SQL') {
      const parameterNames = [...new Set([...apiForm.value.sqlTemplate.matchAll(/:([A-Za-z_][A-Za-z0-9_]*)/g)].map(match => match[1]))]
      const defaults: Record<string, unknown> = { STRING: '', NUMBER: 0, DECIMAL: 0, INTEGER: 0, INT: 0, LONG: 0,
        BOOLEAN: false, BOOL: false, DATE: '2026-01-01', DATETIME: '2026-01-01T00:00:00' }
      const sample: Record<string, unknown> = {}
      parameterNames.forEach(name => {
        const type = String(sqlParameters[name] ?? 'STRING').toUpperCase()
        if (!(type in defaults)) throw new Error(`SQL 参数 ${name} 的类型不支持`)
        sqlParameters[name] = type
        sample[name] = defaults[type]
      })
      if (!Object.keys(requestExample).length) requestExample = sample
    }
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
      requestExample,
      responseExample: vendorResponseExample.value,
      executionMode: apiForm.value.executionMode,
      sqlTemplate: apiForm.value.sqlTemplate,
      sqlParameters
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
  ElMessage.success(`资源 API 草稿 ${created.resourceApiCode} 已创建，请完善接口定义并保存`)
  try { await loadAll() }
  catch { ElMessage.warning('草稿已创建，列表刷新失败，请点击页面刷新按钮') }
}

async function saveConnectionConfig() {
  if (!detail.value || connectionSaving.value) return false
  if (!hasUnsavedChanges.value && !(activeMenu.value === 'resource' && detail.value.summary.upstreamStatus !== 'READY')) {
    ElMessage.success('当前上游配置已保存，无需重复保存')
    return true
  }
  if (connectionForm.value.executionMode !== 'SQL' && !isHttpEndpoint(previewUpstreamUrl.value)) {
    guideError.value = '请填写有效的 HTTP / HTTPS 上游地址。'
    ElMessage.warning(guideError.value)
    return false
  }
  const requestHeaders: Record<string, string> = {}
  if (activeMenu.value === 'resource') {
    for (const row of connectionForm.value.requestHeaders) {
      const name = row.name.trim()
      if (!name && !row.value) continue
      if (!name || !row.value || Object.keys(requestHeaders).some(existing => existing.toLowerCase() === name.toLowerCase())) {
        ElMessage.warning('请填写完整且不重复的请求头名称和值')
        return false
      }
      requestHeaders[name] = row.value
    }
  }
  connectionSaving.value = true
  try {
    let upstreamRequestExample: Record<string, unknown>
    let upstreamResponseExample: Record<string, unknown>
    let sqlParameters: Record<string, string> = {}
    try {
      upstreamRequestExample = JSON.parse(upstreamRequestText.value)
      upstreamResponseExample = JSON.parse(responseText.value)
      const parsedParameters = JSON.parse(connectionForm.value.sqlParameters || '{}')
      if (!parsedParameters || Array.isArray(parsedParameters) || typeof parsedParameters !== 'object') throw new Error()
      sqlParameters = parsedParameters
    } catch {
      guideError.value = '上游请求、响应样例或 SQL 参数不是有效 JSON，请检查后保存。'
      ElMessage.warning(guideError.value)
      return false
    }
    if (activeMenu.value === 'resource') {
      const resourceSaved = await api.saveResourceApi(detail.value.summary.id, {
        configScope: connectionForm.value.configScope,
        upstreamPath: connectionForm.value.upstreamPath,
        upstreamUrl: connectionForm.value.upstreamUrl,
        timeoutMs: connectionForm.value.timeoutMode === 'OVERRIDE' ? connectionForm.value.timeoutMs : null,
        requestHeaders,
        requestExample: upstreamRequestExample,
        responseExample: upstreamResponseExample,
        executionMode: connectionForm.value.executionMode,
        sqlTemplate: connectionForm.value.sqlTemplate,
        sqlParameters
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

function selectServiceResource(resourceApiId: number) {
  const resource = serviceResourceOptions.value.find(item => item.id === resourceApiId)
  if (!resource) {
    platformServiceForm.value = { resourceApiId: 0, serviceCode: '', serviceName: '', servicePath: '', httpMethod: 'POST' }
    return
  }
  const sequence = String(resource.platformServiceCount + 1).padStart(2, '0')
  platformServiceForm.value = {
    resourceApiId: resource.id,
    serviceCode: `${resource.resourceApiCode}_SERVICE_${sequence}`,
    serviceName: resource.platformServiceCount ? `${resource.resourceApiName}标准服务${sequence}` : resource.resourceApiName.replace(/接口$/, '') + '标准服务',
    servicePath: `/api/v1/${resource.resourceApiCode.toLowerCase().replace(/_/g, '-')}/${sequence}`,
    httpMethod: 'POST'
  }
}

function openPlatformServiceDialog() {
  const resource = activeMenu.value === 'resource' ? selectedResourceApi.value : undefined
  if (activeMenu.value === 'resource' && (!resource || resource.status !== 'READY' || upstreamSaveRequired.value)) {
    ElMessage.warning('请先保存当前资源 API，再创建平台标准服务')
    return
  }
  selectServiceResource(resource?.id ?? 0)
  platformServiceDialog.value = true
}

async function createPlatformService() {
  const form = platformServiceForm.value
  if (!serviceCreationResource.value) {
    ElMessage.warning('请选择已保存的资源 API')
    return
  }
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
    ElMessage.success(`平台标准服务 ${created.interfaceCode} 已创建，已继承资源接口定义`)
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
    if (!upstreamResponseExample || typeof upstreamResponseExample !== 'object' || Array.isArray(upstreamResponseExample)) {
      throw new Error('上游响应样例必须是 JSON 对象')
    }
  } catch (error) {
    guideError.value = error instanceof Error && error.message === '上游响应样例必须是 JSON 对象'
      ? error.message : '行内请求样例或上游响应样例不是有效的 JSON，请检查后再保存。'
    ElMessage.error(guideError.value)
    return false
  }
  const saved = await api.saveMappings(selectedInterfaceId.value, detail.value.mappings,
    standardServiceForm.value.servicePath, standardServiceForm.value.httpMethod,
    requestExample, upstreamResponseExample, detail.value.responsePolicy)
  detailCache.delete(selectedInterfaceId.value)
  detail.value.mappings = saved
  detail.value.requestExample = requestExample
  savedMappingsBaseline.value = structuredClone(saved)
  savedResponsePolicyBaseline.value = JSON.stringify(detail.value.responsePolicy)
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
async function importUpstreamResponse(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    if (file.size > 1024 * 1024) throw new Error('JSON 文件不能超过 1 MB')
    const parsed: unknown = JSON.parse(await file.text())
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('响应样例的根节点必须是 JSON 对象')
    }
    responseText.value = JSON.stringify(parsed, null, 2)
    ElMessage.success('响应样例已导入，请保存映射配置')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入 JSON 失败')
  } finally {
    input.value = ''
  }
}
function addMapping(direction = mappingDirection.value) {
  if (!detail.value) return
  detail.value.mappings.push({ direction, sourcePath: '', targetPath: '', transformType: 'DIRECT', transformConfig: {}, defaultValue: '', required: false, sortOrder: detail.value.mappings.filter(item => item.direction === direction).length * 10 + 10 })
  nextTick(() => ElMessage.info('已新增映射行'))
}
function startMappingConfiguration() {
  if (mappingDirection.value === 'RESPONSE' && detail.value?.responsePolicy.dataMode !== 'FIELD_MAPPING') {
    detail.value!.responsePolicy.dataMode = 'FIELD_MAPPING'
  }
  addMapping()
}
function onResponseSourceChange(row: FieldMapping, value: string) {
  if (value === 'WHOLE_BODY') {
    row.transformType = 'WHOLE_BODY'
    row.sourcePath = '$'
    row.targetPath = row.targetPath?.trim() || '$.data'
    row.transformConfig = {}
    row.required = false
  } else if (value === 'CONSTANT') {
    row.transformType = 'CONSTANT'
    row.sourcePath = '$'
    row.transformConfig = { value: '' }
    row.required = false
  } else if (row.transformType === 'WHOLE_BODY' || row.transformType === 'CONSTANT') {
    row.transformType = 'DIRECT'
    row.sourcePath = ''
    row.transformConfig = {}
  }
}
function deleteMapping(row: FieldMapping) {
  if (!detail.value) return
  detail.value.mappings = detail.value.mappings.filter(item => item !== row)
  if (row.direction === 'RESPONSE' && !detail.value.mappings.some(item => item.direction === 'RESPONSE')) {
    detail.value.responsePolicy.dataMode = 'WHOLE_BODY'
  }
}

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
    if (mappingDirection.value === 'RESPONSE') detail.value.responsePolicy.dataMode = 'FIELD_MAPPING'
    const existing = new Set(detail.value.mappings
      .filter(item => item.direction === mappingDirection.value)
      .map(item => item.sourcePath))
    const additions = paths.filter(path => !existing.has(path)).map((path, index) => ({
      direction: mappingDirection.value,
      sourcePath: path,
      targetPath: mappingDirection.value === 'RESPONSE' ? `$.data.${path}` : path,
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
  if (JSON.stringify(detail.value.responsePolicy) !== savedResponsePolicyBaseline.value) {
    if (!await saveMappings()) return false
  }
  if (detail.value.responsePolicy.codeMode === 'HTTP_SUCCESS') return true
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
  const selected = platformResponseCodes.value.find(item => item.enabled && item.resultStatus === 'HIT')
  detail.value?.responseCodes.push({ vendorCode: '', standardCode: selected?.code ?? '', resultStatus: selected?.resultStatus ?? 'HIT', retryable: false, billable: true })
}
function selectPlatformCode(row: InterfaceDetail['responseCodes'][number], code: string) {
  const selected = platformResponseCodes.value.find(item => item.code === code)
  if (selected) row.resultStatus = selected.resultStatus
}
async function refreshPlatformCodes() {
  platformResponseCodes.value = await api.platformResponseCodes()
}
async function refreshPlatformContracts() {
  platformResponseContracts.value = await api.platformResponseContracts()
  if (!selectedPlatformContractId.value || !platformResponseContracts.value.some(item => item.id === selectedPlatformContractId.value)) {
    selectedPlatformContractId.value = platformResponseContracts.value.find(item => item.defaultContract)?.id
      ?? platformResponseContracts.value[0]?.id ?? 0
  }
}
function editPlatformCode(item?: PlatformResponseCode) {
  codeEditorOriginal.value = item?.code ?? ''
  codeEditorForm.value = item ? { ...item } : { code: '', name: '', resultStatus: 'HIT', description: '', enabled: true }
  codeEditorDialog.value = true
}
async function savePlatformCode() {
  const form = { ...codeEditorForm.value, code: codeEditorForm.value.code.trim(), name: codeEditorForm.value.name.trim() }
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(form.code) || !form.name) {
    ElMessage.warning('请填写合法的返回码和名称')
    return
  }
  codeEditorSaving.value = true
  try {
    if (codeEditorOriginal.value) await api.updatePlatformResponseCode(codeEditorOriginal.value, form)
    else await api.createPlatformResponseCode(form)
    await refreshPlatformCodes()
    codeEditorDialog.value = false
    ElMessage.success('平台返回码已保存')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') }
  finally { codeEditorSaving.value = false }
}
function clonePlatformContract(item: PlatformResponseContract): PlatformResponseContract {
  return { ...item, fields: item.fields.map(field => ({ ...field })) }
}
async function activatePlatformContract(item: PlatformResponseContract) {
  if (!item.id || item.active) return
  try {
    await ElMessageBox.confirm(`确认让“${item.contractName} V${item.version}”立即生效？后续平台接口将按该版本补齐标准字段，未映射字段输出为空。`, '立即生效', { type: 'warning', confirmButtonText: '确认生效', cancelButtonText: '取消' })
    await api.activatePlatformResponseContract(item.id)
    await refreshPlatformContracts()
    ElMessage.success('标准响应已生效，后续调用将使用最新版本')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '标准响应生效失败')
  }
}
function editPlatformContract(item?: PlatformResponseContract) {
  contractEditorOriginalId.value = item?.id ?? 0
  contractEditorForm.value = item ? clonePlatformContract(item) : {
    contractCode: '', contractName: '', version: 1, description: '', enabled: true, defaultContract: false, active: false,
    fields: [{ key: '', label: '', type: 'STRING', required: false, description: '' }]
  }
  contractEditorDialog.value = true
}
function addContractField() {
  contractEditorForm.value.fields.push({ key: '', label: '', type: 'STRING', required: false, description: '' })
}
function removeContractField(index: number) {
  if (contractEditorForm.value.fields.length <= 1) {
    ElMessage.warning('标准响应至少需要保留一个字段')
    return
  }
  contractEditorForm.value.fields.splice(index, 1)
}
async function savePlatformContract() {
  if (contractEditorSaving.value) return
  const form = clonePlatformContract(contractEditorForm.value)
  form.contractCode = form.contractCode.trim()
  form.contractName = form.contractName.trim()
  form.description = form.description.trim()
  form.fields = form.fields.map(field => ({ ...field, key: field.key.trim(), label: field.label.trim(), type: field.type.trim().toUpperCase(), description: field.description.trim() }))
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(form.contractCode) || !form.contractName || form.version < 1 || !form.fields.length
    || form.fields.some(field => !/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(field.key) || !field.label || !field.type)) {
    ElMessage.warning('请填写合法的标准响应编码、名称和字段')
    return
  }
  contractEditorSaving.value = true
  try {
    const saved = contractEditorOriginalId.value
      ? await api.updatePlatformResponseContract(contractEditorOriginalId.value, form)
      : await api.createPlatformResponseContract(form)
    await refreshPlatformContracts()
    selectedPlatformContractId.value = saved.id ?? selectedPlatformContractId.value
    contractEditorDialog.value = false
    ElMessage.success('标准响应已保存')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '标准响应保存失败') }
  finally { contractEditorSaving.value = false }
}
async function deletePlatformContract(item: PlatformResponseContract) {
  if (!item.id) return
  try {
    await ElMessageBox.confirm(`确定删除标准响应“${item.contractName}”？默认项不能删除。`, '删除标准响应', { type: 'warning' })
    await api.deletePlatformResponseContract(item.id)
    await refreshPlatformContracts()
    ElMessage.success('标准响应已删除')
  } catch (error) {
    if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message)
  }
}
async function deletePlatformCode(item: PlatformResponseCode) {
  try {
    await ElMessageBox.confirm(`确定删除平台返回码 ${item.code}？已被接口引用的返回码不能删除。`, '删除返回码', { type: 'warning' })
    await api.deletePlatformResponseCode(item.code)
    await refreshPlatformCodes()
    ElMessage.success('已删除')
  } catch (error) {
    if (error instanceof Error && error.message !== 'cancel') ElMessage.error(error.message)
  }
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
    ElMessage.warning('请先保存标准请求、映射和安全策略，再执行服务编排测试')
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
    const requestPayload = testingMode === 'E2E' ? {} : JSON.parse(testingMode === 'UPSTREAM' ? upstreamRequestText.value : requestText.value)
    if (testingMode === 'PLATFORM') {
      const missingRequiredSources = mappingSnapshot
        .filter(mapping => mapping.direction === 'REQUEST' && mapping.required)
        .filter(mapping => readObjectPath(requestPayload, mapping.sourcePath) === undefined)
        .map(mapping => mapping.sourcePath)
      if (missingRequiredSources.length) {
        throw new Error(`联调请求缺少映射必填来源字段：${missingRequiredSources.join('、')}。请修改请求样例后重新保存草稿。`)
      }
    }
    if (testingMode === 'E2E' && esbXmlOverride.value === null) {
      esbTestTraceId.value = `EDSP-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
    }
    const xmlRequest = testingMode === 'E2E' ? esbXmlText.value.trim() : ''
    if (testingMode === 'E2E' && (!xmlRequest || xmlRequest.startsWith('请输入有效 JSON'))) {
      throw new Error('请填写有效的 ESB XML 请求报文')
    }
    const traceId = testingMode === 'E2E' ? esbTraceId(xmlRequest, esbTestTraceId.value)
      : `EDSP-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
    const result = testingMode === 'E2E'
      ? await api.esbDiagnostic('EDSP_TEST_TOOL', xmlRequest, traceId,
          { timeoutMs: requestDeadlineMs(previewTimeoutMs.value), signal: controller.signal }).then(diagnostic => ({
            upstreamRequest: diagnostic.upstreamRequest,
            upstreamResponse: diagnostic.upstreamResponse,
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
    const failure = describeTraceFailure(result.traceNodes, result.standardResponse,
      detail.value.vendorCodePath, detail.value.responseCodes)
    selectedTraceKey.value = failure?.node?.key ?? result.traceNodes[0]?.key ?? (testMode.value === 'E2E' ? 'ESB_QUEUE' : 'DOWNSTREAM')
    const remainingTraceDuration = minimumTraceDurationMs - (Date.now() - traceStartedAt)
    if (remainingTraceDuration > 0) {
      await new Promise(resolve => setTimeout(resolve, remainingTraceDuration))
    }
    if (testingInterfaceId !== selectedInterfaceId.value) return
    chainPhase.value = activeTraceDefinitions.value.length
    if (failure) throw new Error(failure.reason)
    if (testingMode === 'UPSTREAM' && testingInterfaceId === detail.value.summary.id) {
      lastUpstreamValidation.value = { interfaceId: testingInterfaceId, connectionFingerprint: upstreamValidationFingerprint.value }
    }
    if (testingMode === 'PLATFORM' && testingVersion === detail.value.summary.currentVersion
      && JSON.stringify(mappingSnapshot) === JSON.stringify(savedMappingsBaseline.value)) {
      lastOrchestrationValidation.value = { interfaceId: testingInterfaceId, version: testingVersion }
    }
    if (isSuccessfulE2E(result, testingMode)) {
      lastE2ETest.value = { interfaceId: testingInterfaceId, version: testingVersion }
    }
    testSucceeded.value = true
    if (testingMode !== 'UPSTREAM') {
      try { await refreshBilling() } catch { ElMessage.warning('联调已完成，计费统计刷新失败，可稍后刷新') }
    }
    ElMessage.success(testMode.value === 'UPSTREAM' ? '上游接口验证成功'
      : testMode.value === 'PLATFORM' ? '服务编排测试通过' : '全链路测试通过')
    return true
  } catch (error) {
    const apiError = error && typeof error === 'object' && 'payload' in error && 'message' in error
      ? error as ApiRequestError : null
    if (apiError?.status === 401 && testingMode === 'E2E') {
      testError.value = '登录状态已失效，请重新登录后执行全链路测试'
      loggedIn.value = false
      user.value = null
      api.clearOperatorSession()
      ElMessage.warning(testError.value)
      return false
    }
    if (apiError?.payload.code === 'TEST_EXECUTION_FAILED') {
      const nodes = Array.isArray(apiError.payload.traceNodes) ? apiError.payload.traceNodes as TestResult['traceNodes'] : []
      testResult.value = { upstreamRequest: {}, upstreamResponse: {}, standardResponse: {}, steps: [], traceNodes: nodes }
      const failure = describeTraceFailure(nodes, {}, detail.value?.vendorCodePath,
        detail.value?.responseCodes)
      selectedTraceKey.value = failure?.node?.key ?? nodes[0]?.key ?? ''
      chainPhase.value = Math.max(0, activeTraceDefinitions.value.findIndex(node => node.key === selectedTraceKey.value))
      const traceId = typeof apiError.payload.traceId === 'string' ? apiError.payload.traceId : ''
      testError.value = `${failure?.reason ?? apiError.message}${traceId ? `（Trace ID：${traceId}）` : ''}`
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
  await ElMessageBox.confirm(`确认发布 ${detail.value.summary.interfaceCode} V${detail.value.summary.currentVersion}？`, '确认发布服务', { confirmButtonText: '确认发布', cancelButtonText: '取消', type: 'warning' })
  const published = await api.directPublish(selectedInterfaceId.value, releaseNote.value)
  detail.value.summary = published
  ElMessage.success(`V${published.publishedVersion} 已发布并生效，可配置 API 访问授权`)
  try { await loadAll() } catch { ElMessage.warning('服务已发布，页面刷新失败，请稍后刷新') }
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
  authDialogMode.value = 'CREATE'
  editingAuthorization.value = null
  Object.assign(authForm.value, defaultAuthorizationDates)
  authForm.value.clientId = activeClients.value.find(item => item.id === authorizationClient.value)?.id
    ?? activeClients.value[0]?.id ?? 0
  if (!availableAuthInterfaces.value.some(item => item.id === authForm.value.interfaceId)) {
    authForm.value.interfaceId = availableAuthInterfaces.value[0]?.id ?? 0
  }
  authDialog.value = true
}
function editAuthorization(row: AuthorizationRecord) {
  if (!authorizableInterfaces.value.some(item => item.id === row.interfaceId)) {
    ElMessage.warning('该 API 当前未上线，不能修改授权')
    return
  }
  authDialogMode.value = 'EDIT'
  editingAuthorization.value = row
  authForm.value = {
    clientId: row.clientId, interfaceId: row.interfaceId, dailyQuota: row.dailyQuota,
    validFrom: row.validFrom, validTo: row.validTo, approvedBy: '平台管理员'
  }
  authDialog.value = true
}
async function createAuthorization() {
  if (!activeClients.value.length || !authForm.value.clientId) {
    ElMessage.warning('请先登记并启用一个调用系统')
    return
  }
  if (authDialogMode.value === 'CREATE' && !availableAuthInterfaces.value.some(item => item.id === authForm.value.interfaceId)) {
    ElMessage.warning('请选择该调用系统尚未获得有效授权的 API')
    return
  }
  if (authDialogMode.value === 'EDIT' && !authorizations.value.some(item =>
    item.clientId === authForm.value.clientId && item.interfaceId === authForm.value.interfaceId)) {
    ElMessage.warning('原授权记录已变化，请刷新后重试')
    return
  }
  await api.createAuthorization(authForm.value)
  authDialog.value = false
  authorizations.value = await api.authorizations()
  ElMessage.success(authDialogMode.value === 'EDIT' ? '授权配置已更新' : 'API 授权已生效')
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
  batchAuthFilters.value = { sourceId: 0, keyword: '' }
  batchAuthPage.value = 1
  batchAuthDialog.value = true
}
async function createBatchAuthorization() {
  if (!batchAuthForm.value.clientId || !batchAuthForm.value.interfaceIds.length) {
    ElMessage.warning('请选择调用系统和至少一个已发布 API')
    return
  }
  if (batchAuthForm.value.interfaceIds.some(id => !authorizableInterfaces.value.some(item => item.id === id) || hasAuthorization(id))) {
    ElMessage.warning('部分 API 已有授权记录或不再在线，请重新选择')
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
    selectedLogNodeKey.value = describeTraceFailure(detail.nodes ?? [])?.node?.key ?? detail.nodes?.[0]?.key ?? ''
  } catch (error) {
    callTraceError.value = error instanceof Error ? error.message : '链路详情加载失败'
    ElMessage.error(`链路详情加载失败：${callTraceError.value}`)
  } finally {
    callTraceLoading.value = false
  }
}

const catalogProviders = computed(() => providers.value.map(provider => ({
  ...provider,
  sources: sources.value.filter(source => source.providerId === provider.id).map(source => ({
    ...source, apis: allResourceApis.value.filter(item => item.dataSourceId === source.id)
  }))
})).filter(provider => provider.sources.length > 0))
const filteredCatalogProviders = computed(() => {
  const keyword = catalogSearch.value.trim().toLowerCase()
  if (!keyword) return catalogProviders.value
  return catalogProviders.value.map(provider => ({
    ...provider,
    sources: `${provider.providerName} ${provider.providerCode}`.toLowerCase().includes(keyword) ? provider.sources : provider.sources.filter(source => `${source.sourceName} ${source.sourceCode}`.toLowerCase().includes(keyword)
      || source.apis.some(item => `${item.resourceApiName} ${item.resourceApiCode}`.toLowerCase().includes(keyword)))
  })).filter(provider => `${provider.providerName} ${provider.providerCode}`.toLowerCase().includes(keyword) || provider.sources.length > 0)
})
const documentSources = computed(() => sources.value.filter(source => source.providerId === documentForm.value.providerId))
const documentApis = computed(() => allResourceApis.value.filter(item => item.dataSourceId === documentForm.value.dataSourceId))

async function loadResourceDirectory() {
  directoryLoading.value = true
  try {
    const result = await api.resourceDirectory({ ...directoryFilters.value, page: directoryPage.value, size: directorySize.value })
    directoryRows.value = result.rows
    directoryTotal.value = result.total
  } catch (error) {
    if (!(error instanceof ApiRequestError && error.status === 401))
      ElMessage.error(error instanceof Error ? error.message : '资源目录加载失败')
  }
  finally { directoryLoading.value = false }
}
function searchResourceDirectory() { directoryPage.value = 1; void loadResourceDirectory() }
function resetResourceDirectory() {
  directoryFilters.value = { keyword: '', providerId: 0, dataSourceId: 0, status: '', protocolType: '' }
  searchResourceDirectory()
}

async function loadResourceDocuments() {
  try { resourceDocuments.value = await api.resourceDocuments() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '文档列表加载失败') }
}
async function uploadResourceDocument() {
  if (!documentForm.value.providerId || !documentForm.value.file) {
    ElMessage.warning('请选择资源提供商和接口文档')
    return
  }
  documentUploading.value = true
  try {
    const form = new FormData()
    form.append('providerId', String(documentForm.value.providerId))
    if (documentForm.value.dataSourceId) form.append('dataSourceId', String(documentForm.value.dataSourceId))
    if (documentForm.value.resourceApiId) form.append('resourceApiId', String(documentForm.value.resourceApiId))
    form.append('description', documentForm.value.description)
    form.append('file', documentForm.value.file)
    await api.uploadResourceDocument(form)
    documentDialog.value = false
    documentForm.value.file = null
    documentForm.value.description = ''
    await loadResourceDocuments()
    ElMessage.success('文档已上传')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '上传失败') }
  finally { documentUploading.value = false }
}
function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
async function downloadResourceDocument(item: ResourceDocument) {
  try { saveBlob(await api.downloadResourceDocument(item.id), item.documentName) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '下载失败') }
}
async function deleteResourceDocument(item: ResourceDocument) {
  try {
    await ElMessageBox.confirm(`删除“${item.documentName}”？`, '删除文档', { type: 'warning' })
    await api.deleteResourceDocument(item.id)
    await loadResourceDocuments()
    ElMessage.success('文档已删除')
  } catch (error) { if (error !== 'cancel') ElMessage.error(error instanceof Error ? error.message : '删除失败') }
}
async function loadUsageReport() {
  if (!reportRange.value?.length) return
  if (!reportDimensions.value.length || !reportMetrics.value.length || reportDimensions.value.length > 4) {
    ElMessage.warning('请选择 1 至 4 个维度，并至少选择一个度量')
    return
  }
  reportLoading.value = true
  reportRows.value = []
  reportTotal.value = null
  try {
    const result = await api.usageReport(reportDimensions.value, reportMetrics.value, reportRange.value[0], reportRange.value[1])
    reportRows.value = result.rows
    reportTotal.value = result.rows.find(item => item.level === 0) ?? null
  }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '报表加载失败') }
  finally { reportLoading.value = false }
}
async function downloadUsageReport() {
  try {
    const [from, to] = reportRange.value
    saveBlob(await api.exportUsageReport(reportDimensions.value, reportMetrics.value, from, to), `外部数据平台-汇总报表-${from}-${to}.csv`)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '报表下载失败') }
}
async function loadOperationAudit() {
  if (!auditRange.value?.length) return
  const sequence = ++auditQuerySequence
  auditLoading.value = true
  try {
    const page = await api.operationAudit({ from: auditRange.value[0], to: auditRange.value[1], page: auditPage.value, size: auditSize.value,
      action: auditFilters.value.action || undefined, operator: auditFilters.value.operator.trim() || undefined,
      targetType: auditFilters.value.targetType || undefined, outcome: auditFilters.value.outcome || undefined })
    if (sequence !== auditQuerySequence) return
    if (!Array.isArray(page.rows)) throw new Error('管理服务版本过旧，请更新 Admin 服务后重试')
    auditRows.value = page.rows
    auditTotal.value = page.total
  }
  catch (error) { if (sequence === auditQuerySequence) { auditRows.value = []; auditTotal.value = 0; ElMessage.error(error instanceof Error ? error.message : '操作审计加载失败') } }
  finally { if (sequence === auditQuerySequence) auditLoading.value = false }
}
function searchOperationAudit() { auditPage.value = 1; void loadOperationAudit() }
function resetOperationAudit() {
  auditRange.value = [localDate(-30), localDate()]
  auditFilters.value = { action: '', operator: '', targetType: '', outcome: '' }
  searchOperationAudit()
}
const auditActionLabels: Record<string, string> = { POST: '新增', PUT: '修改', PATCH: '修改', DELETE: '删除', LOGIN: '登录', LOGOUT: '退出' }
const auditOperationLabels: Record<string, string> = {
  activate: '生效', publish: '发布', 'direct-publish': '发布', offline: '下线', online: '上线',
  'sync-resource': '同步资源', decision: '审批', batch: '批量授权'
}
function auditActionLabel(row: OperationAuditRecord) {
  const segments = row.targetPath.split('/')
  const operation = segments[segments.length - 1] || ''
  return row.action === 'POST' ? (auditOperationLabels[operation] || auditActionLabels.POST) : (auditActionLabels[row.action] || row.action)
}
const auditTargetLabels: Record<string, string> = {
  providers: '资源提供商', 'provider-accounts': '资源方账号', 'data-sources': '数据源',
  'resource-apis': '数据资源 API', 'resource-documents': '资源文档',
  'platform-services': '标准服务', interfaces: '标准服务', approvals: '发布审批',
  clients: '调用系统', authorizations: '访问授权', billing: '计量规则',
  'platform-response-codes': '平台返回码', 'platform-response-contracts': '平台标准响应', auth: '用户会话'
}

const monitorMaxDuration = computed(() => Math.max(1, ...monitorPoints.value.map(item => item.avgDurationMs)))
function formatJson(value: unknown) { return JSON.stringify(value, null, 2) }
function formatDateTime(value: string) { return value ? value.replace('T', ' ').slice(0, 16) : '-' }
function money(value: number) { return `¥ ${Number(value).toFixed(2)}` }
</script>

<template>
  <div v-if="!loggedIn" class="bank-login-page">
    <div class="bank-login-grid" aria-hidden="true"></div>
    <main class="bank-login-shell">
      <section class="bank-login-brand-pane" aria-label="平台品牌">
        <div class="bank-login-brand">
          <img src="/images/jsrcub-emblem.png" alt="江苏农商联合银行标识" />
          <div class="bank-login-brand-name">
            <strong>江苏农村商业联合银行</strong>
            <span>EXTERNAL DATA PLATFORM</span>
          </div>
        </div>
        <div class="bank-login-headline">
          <h1>外部数据<br />开发管理平台</h1>
          <span class="bank-login-divider"></span>
          <p>统一接入 · 标准服务 · 全链路管理</p>
        </div>
        <div class="bank-login-features" aria-label="平台能力">
          <span><i></i>数据资源接入</span>
          <span><i></i>标准服务开发</span>
          <span><i></i>运行链路追踪</span>
        </div>
      </section>
      <section class="bank-login-form-pane" aria-label="账号登录">
        <div class="bank-login-form-header">
          <h2>欢迎使用</h2>
          <p>使用平台账号登录</p>
        </div>
        <form class="bank-login-form" @submit.prevent="login">
          <div class="bank-login-field">
            <label for="login-username">账号</label>
            <el-input id="login-username" v-model="loginForm.username" autocomplete="username" placeholder="请输入账号" size="large">
              <template #prefix><el-icon><UserIcon /></el-icon></template>
            </el-input>
          </div>
          <div class="bank-login-field">
            <label for="login-password">密码</label>
            <el-input id="login-password" v-model="loginForm.password" autocomplete="current-password" placeholder="请输入密码" type="password" show-password size="large">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </div>
          <el-button class="bank-login-submit" native-type="submit" type="primary" size="large" :loading="loginLoading">登录</el-button>
        </form>
        <footer class="bank-login-footer">
          <span>仅限授权用户访问</span>
          <span>© 2026 江苏农村商业联合银行</span>
        </footer>
      </section>
    </main>
  </div>

  <el-container v-else class="app-shell" :class="{'sidebar-collapsed':sidebarCollapsed}" v-loading="loading">
    <el-aside :width="sidebarCollapsed ? '68px' : '224px'" class="sidebar" :class="{collapsed:sidebarCollapsed}">
      <div class="brand"><img class="brand-mark" src="/images/jsrcub-emblem.png" alt="江苏农村商业联合银行标识"/><div class="brand-copy"><div class="brand-name">外部数据平台</div><div class="brand-sub">External Data Service</div></div><button class="sidebar-collapse-button" type="button" :title="sidebarCollapsed?'展开菜单':'收起菜单'" :aria-label="sidebarCollapsed?'展开菜单':'收起菜单'" :aria-expanded="!sidebarCollapsed" @click.stop="sidebarCollapsed=!sidebarCollapsed"><component :is="sidebarCollapsed?Expand:Fold"/></button></div>
      <el-menu :default-active="activeSidebarMenu" :collapse="sidebarCollapsed" :collapse-transition="false" class="side-menu">
        <el-menu-item aria-label="全局概览" index="overview" @click="navigate('overview')"><el-icon><DataBoard /></el-icon><template #title>全局概览</template></el-menu-item>
        <button v-if="!sidebarCollapsed" class="menu-section-label" type="button" @click="menuGroups.resources=!menuGroups.resources"><span>资源管理</span><ArrowRight :class="{expanded:menuGroups.resources}"/></button>
        <template v-if="menuGroups.resources||sidebarCollapsed">
          <el-menu-item aria-label="资源方管理" index="providers" @click="navigate('providers')"><el-icon><OfficeBuilding /></el-icon><template #title>资源方管理</template></el-menu-item>
          <el-menu-item aria-label="数据资源接入" index="resource" @click="navigate('resource')"><el-icon><Connection /></el-icon><template #title>数据资源接入</template></el-menu-item>
          <el-menu-item aria-label="资源目录" index="discovery" @click="navigate('discovery')"><el-icon><Search /></el-icon><template #title>资源目录</template></el-menu-item>
          <el-menu-item aria-label="文档管理" index="documents" @click="navigate('documents')"><el-icon><DocumentCopy /></el-icon><template #title>文档管理</template></el-menu-item>
        </template>
        <button v-if="!sidebarCollapsed" class="menu-section-label" type="button" @click="menuGroups.services=!menuGroups.services"><span>服务管理</span><ArrowRight :class="{expanded:menuGroups.services}"/></button>
        <template v-if="menuGroups.services||sidebarCollapsed">
          <el-menu-item aria-label="标准服务开发" index="service-development" @click="navigate('service-development')"><el-icon><Setting /></el-icon><template #title>标准服务开发</template></el-menu-item>
          <el-menu-item aria-label="服务发布" index="releases" @click="navigate('releases')"><el-icon><Promotion /></el-icon><template #title>服务发布</template></el-menu-item>
          <el-menu-item aria-label="API 访问授权" index="authorization" @click="navigate('authorization')"><el-icon><Key /></el-icon><template #title>API 访问授权</template></el-menu-item>
        </template>
        <button v-if="!sidebarCollapsed" class="menu-section-label" type="button" @click="menuGroups.platform=!menuGroups.platform"><span>平台管理</span><ArrowRight :class="{expanded:menuGroups.platform}"/></button>
        <template v-if="menuGroups.platform||sidebarCollapsed">
          <el-menu-item aria-label="标准响应" index="platform-management-contract" @click="openPlatformManagement('contract')"><el-icon><DocumentChecked /></el-icon><template #title>标准响应</template></el-menu-item>
          <el-menu-item aria-label="返回码字典" index="platform-management-codes" @click="openPlatformManagement('codes')"><el-icon><Tickets /></el-icon><template #title>返回码字典</template></el-menu-item>
          <el-menu-item aria-label="安全能力" index="platform-management-security" @click="openPlatformManagement('security')"><el-icon><Lock /></el-icon><template #title>安全能力</template></el-menu-item>
          <el-menu-item aria-label="操作审计" index="platform-management-audit" @click="openPlatformManagement('audit')"><el-icon><Tickets /></el-icon><template #title>操作审计</template></el-menu-item>
        </template>
        <button v-if="!sidebarCollapsed" class="menu-section-label" type="button" @click="menuGroups.operations=!menuGroups.operations"><span>运行管理</span><ArrowRight :class="{expanded:menuGroups.operations}"/></button>
        <template v-if="menuGroups.operations||sidebarCollapsed">
          <el-menu-item aria-label="计量管理" index="billing" @click="navigate('billing')"><el-icon><Tickets /></el-icon><template #title>计量管理</template></el-menu-item>
          <el-menu-item aria-label="链路追踪" index="logs" @click="navigate('logs')"><el-icon><DataAnalysis /></el-icon><template #title>链路追踪</template></el-menu-item>
          <el-menu-item aria-label="运行监控" index="monitor" @click="navigate('monitor')"><el-icon><Monitor /></el-icon><template #title>运行监控</template></el-menu-item>
        </template>
        <el-menu-item aria-label="报表中心" index="reports" @click="navigate('reports')"><el-icon><DataAnalysis /></el-icon><template #title>报表中心</template></el-menu-item>
      </el-menu>
      <div class="sidebar-footer"><div><strong>外部数据服务管理</strong><span>资源接入 · 开发 · 运营</span></div></div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="crumbs"><span>外部数据平台</span><b>/</b><template v-if="activeMenu === 'platform-management'"><span>平台管理</span><b>/</b></template><span>{{ pageMeta[0] }}</span></div>
        <div class="top-actions"><el-tag v-if="demo" type="warning" effect="plain">演示数据模式</el-tag><el-dropdown><div class="user"><span>{{ user?.displayName }}</span><b>管</b></div><template #dropdown><el-dropdown-menu><el-dropdown-item @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
      </el-header>

      <el-main class="main-content">
        <section class="page-heading" :class="{'compact-page-heading': activeMenu === 'releases' || activeMenu === 'authorization'}"><div><h1>{{ pageMeta[0] }}<HelpTip :title="pageMeta[0]" :content="pageMeta[1]"/></h1></div><div class="heading-actions"><el-button :icon="Refresh" @click="refreshCurrentWorkspace">刷新</el-button><el-button v-if="activeMenu === 'resource'" type="primary" :icon="Plus" @click="openSourceWizard">接入数据源</el-button><el-button v-if="activeMenu === 'authorization'" :icon="Files" @click="openBatchAuthorization">批量授权</el-button><el-button v-if="activeMenu === 'authorization'" type="primary" :icon="Plus" @click="openAuthDialog">新增授权</el-button><el-button v-if="activeMenu === 'billing'" type="primary" :icon="Plus" @click="openBillingDialog()">配置计费</el-button></div></section>

        <template v-if="activeMenu === 'overview'">
          <section class="cockpit-command">
            <div class="cockpit-runtime"><span class="live-beacon">运营概况</span><div><strong>服务与调用概览</strong><p>{{ dashboard.dataSourceCount }} 个数据源 · {{ dashboard.publishedCount }} 个已发布 API · {{ clients.length }} 个调用系统</p></div></div>
            <div class="cockpit-actions"><button @click="navigate('logs')"><Search/><span><strong>链路追踪</strong><small>检索 Trace ID</small></span><ArrowRight/></button><button @click="navigate('monitor')"><Monitor/><span><strong>运行监控</strong><small>查看节点耗时</small></span><ArrowRight/></button></div>
          </section>

          <section class="cockpit-kpis">
            <div class="cockpit-kpi primary"><span>累计调用量</span><strong>{{ monitorSummary.totalCalls.toLocaleString() }}</strong><small>全平台 API 请求</small><DataAnalysis/></div>
            <div class="cockpit-kpi"><span>调用成功率</span><strong>{{ overviewSuccessRate.toFixed(1) }}<em>%</em></strong><small :class="overviewSuccessRate >= 99 ? 'positive' : 'negative'">{{ overviewSuccessRate >= 99 ? '调用正常' : '存在异常调用' }}</small><CircleCheck/></div>
            <div class="cockpit-kpi"><span>累计异常调用</span><strong>{{ monitorSummary.errorCalls.toLocaleString() }}</strong><small>全平台失败请求</small><Warning/></div>
            <div class="cockpit-kpi"><span>已发布 API</span><strong>{{ dashboard.publishedCount }}</strong><small>共 {{ dashboard.interfaceCount }} 个接口</small><Files/></div>
            <div class="cockpit-kpi"><span>有效服务授权</span><strong>{{ activeAuthorizationCount }}</strong><small>{{ clients.length }} 个调用系统</small><Key/></div>
          </section>

          <section class="cockpit-analytics">
            <div class="cockpit-panel trend-panel">
              <header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>平台调用趋势 · 近 24 小时</strong></div><div class="chart-legend"><i class="success"></i>成功<i class="error"></i>异常</div></header>
              <div class="traffic-chart" :class="{ inspecting: activeTrendIndex !== null }">
                <div class="chart-grid"><i></i><i></i><i></i><i></i></div>
                <button v-for="(point,index) in monitorPoints" :key="point.timeLabel" type="button" class="traffic-column" :class="{ active: activeTrendIndex === index }" :aria-label="`${point.timeLabel}，调用 ${point.calls} 次，异常 ${point.errorCalls} 次`" @mouseenter="activeTrendIndex=index" @mouseleave="activeTrendIndex=null" @focus="activeTrendIndex=index" @blur="activeTrendIndex=null">
                  <div v-if="point.calls" class="traffic-stack" :style="{ height: `${Math.max(8, point.calls / Math.max(1, overviewMaxCalls) * 100)}%` }"><i class="error" :style="{ height: `${point.errorCalls / point.calls * 100}%` }"></i></div>
                  <span v-if="index % 4 === 0 || index === monitorPoints.length - 1">{{ point.timeLabel }}</span>
                </button>
                <div v-if="activeTrendPoint" class="chart-tooltip" :style="{ left: `${((activeTrendIndex ?? 0) + .5) / Math.max(1, monitorPoints.length) * 100}%`, transform: activeTrendIndex === 0 ? 'translateX(0)' : activeTrendIndex === monitorPoints.length - 1 ? 'translateX(-100%)' : 'translateX(-50%)' }"><header><strong>{{ activeTrendPoint.timeLabel }}</strong><span>{{ activeTrendPoint.calls }} 次调用</span></header><div><span>成功<b>{{ activeTrendPoint.successCalls }}</b></span><span>异常<b class="error">{{ activeTrendPoint.errorCalls }}</b></span><span>平均耗时<b>{{ activeTrendPoint.avgDurationMs }} ms</b></span><span>上游耗时<b>{{ activeTrendPoint.avgUpstreamDurationMs }} ms</b></span></div></div>
                <div v-if="!monitorPoints.some(point => point.calls > 0)" class="chart-empty">近 24 小时暂无调用</div>
              </div>
              <footer><span>峰值调用<b>{{ overviewMaxCalls }}</b></span><span>平均响应<b>{{ monitorSummary.avgDurationMs }} ms</b></span><span>API 数量<b>{{ dashboard.interfaceCount }}</b></span></footer>
            </div>
            <div class="cockpit-panel result-panel">
              <header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>调用结果分布</strong></div><b>{{ billingSummary.totalCalls }} 次</b></header>
              <div class="result-visual"><div class="result-donut" :class="{ inspecting: activeResultItem }"><svg viewBox="0 0 100 100" role="img" aria-label="调用结果分布环形图"><circle class="donut-track" cx="50" cy="50" r="40" pathLength="100"/><circle v-for="item in overviewResultSegments" :key="item.key" class="donut-segment" :class="{ active: activeResultKey === item.key, muted: activeResultKey && activeResultKey !== item.key }" cx="50" cy="50" r="40" pathLength="100" fill="none" :stroke="item.color" :stroke-dasharray="`${item.percentage} ${100-item.percentage}`" :stroke-dashoffset="-item.offset" tabindex="0" :aria-label="`${item.label} ${item.value} 次，占比 ${item.percentage}%`" @mouseenter="activeResultKey=item.key" @mouseleave="activeResultKey=null" @focus="activeResultKey=item.key" @blur="activeResultKey=null"/></svg><span><strong>{{ activeResultItem ? activeResultItem.value : `${overviewSuccessRate.toFixed(1)}%` }}</strong><small>{{ activeResultItem ? activeResultItem.label : '成功率' }}</small><em v-if="activeResultItem">{{ activeResultItem.percentage }}%</em></span></div><div class="result-list"><div v-for="item in overviewResultMix" :key="item.key" :class="{ active: activeResultKey === item.key, muted: activeResultKey && activeResultKey !== item.key }" tabindex="0" @mouseenter="activeResultKey=item.key" @mouseleave="activeResultKey=null" @focus="activeResultKey=item.key" @blur="activeResultKey=null"><i :style="{background:item.color}"></i><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.percentage }}%</small></div></div></div>
              <button class="panel-link" @click="navigate('logs')">查看全部链路记录<ArrowRight/></button>
            </div>
          </section>

          <section class="cockpit-operations">
            <div class="cockpit-panel ranking-panel"><header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>API 调用排行</strong></div><span>最近 {{ usageRecords.length }} 笔</span></header><div class="ranking-list"><div v-for="(item,index) in overviewTopInterfaces" :key="item.code"><b>{{ String(index + 1).padStart(2, '0') }}</b><div><strong>{{ item.name }}</strong><small>{{ item.code }}</small></div><span>{{ item.calls }} 次</span><em>{{ item.avgDuration }} ms</em><i :class="{warning:item.successRate<99}">{{ item.successRate }}%</i></div><div v-if="!overviewTopInterfaces.length" class="panel-empty">暂无 API 调用数据</div></div></div>
            <div class="cockpit-panel failure-panel"><header><div><span aria-hidden="true" class="decorative-eyebrow"></span><strong>近期异常调用</strong></div><button @click="navigate('logs')">全部</button></header><div class="failure-list"><button v-for="item in overviewRecentFailures" :key="item.id" @click="navigate('logs')"><span>ERROR</span><div><strong>{{ item.interfaceName }}</strong><small>{{ item.nodeName || item.standardCode }}</small></div><time>{{ formatDateTime(item.calledAt) }}</time><ArrowRight/></button><div v-if="!overviewRecentFailures.length" class="panel-empty success"><CircleCheck/><span>暂无异常调用</span></div></div></div>
          </section>

        </template>

        <template v-else-if="activeMenu === 'discovery'">
          <section class="resource-directory">
            <div class="directory-filters">
              <el-input v-model="directoryFilters.keyword" :prefix-icon="Search" clearable placeholder="资源名称或编码" @keyup.enter="searchResourceDirectory"/>
              <el-select v-model="directoryFilters.providerId" filterable placeholder="资源提供商" @change="directoryFilters.dataSourceId=0"><el-option label="全部资源提供商" :value="0"/><el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="item.id"/></el-select>
              <el-select v-model="directoryFilters.dataSourceId" filterable placeholder="数据源"><el-option label="全部数据源" :value="0"/><el-option v-for="item in sources.filter(source=>!directoryFilters.providerId || source.providerId===directoryFilters.providerId)" :key="item.id" :label="item.sourceName" :value="item.id"/></el-select>
              <el-select v-model="directoryFilters.status" placeholder="资源状态"><el-option label="全部状态" value=""/><el-option label="已保存" value="READY"/><el-option label="草稿" value="DRAFT"/></el-select>
              <el-select v-model="directoryFilters.protocolType" placeholder="资源类型"><el-option label="全部类型" value=""/><el-option v-for="item in [...new Set(sources.map(source=>source.protocolType))]" :key="item" :label="item" :value="item"/></el-select>
              <div class="directory-filter-actions"><el-button type="primary" @click="searchResourceDirectory">查询</el-button><el-button @click="resetResourceDirectory">重置</el-button></div>
            </div>
            <el-table :data="directoryRows" v-loading="directoryLoading" size="small" empty-text="暂无符合条件的资源 API" table-layout="fixed">
              <el-table-column prop="resourceApiCode" label="资源编码" min-width="180" show-overflow-tooltip/>
              <el-table-column prop="resourceApiName" label="资源名称" min-width="180" show-overflow-tooltip/>
              <el-table-column prop="providerName" label="资源提供商" min-width="150" show-overflow-tooltip/>
              <el-table-column prop="dataSourceName" label="数据源" min-width="150" show-overflow-tooltip/>
              <el-table-column prop="protocolType" label="资源类型" min-width="130"/>
              <el-table-column label="状态" width="90"><template #default="{row}">{{ row.status==='READY' ? '已保存' : '草稿' }}</template></el-table-column>
              <el-table-column label="更新时间" width="165"><template #default="{row}">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
            </el-table>
            <div class="directory-pagination"><el-pagination v-model:current-page="directoryPage" v-model:page-size="directorySize" :page-sizes="[20,50,100]" :total="directoryTotal" layout="total, sizes, prev, pager, next" @change="loadResourceDirectory"/></div>
          </section>
        </template>

        <template v-else-if="activeMenu === 'documents'">
          <section class="document-workspace">
            <div class="document-toolbar"><el-button type="primary" :icon="Plus" @click="documentDialog=true">上传文档</el-button></div>
            <el-table :data="resourceDocuments" empty-text="暂无接口文档"><el-table-column prop="documentName" label="文档名称" min-width="230"/><el-table-column prop="providerName" label="资源提供商" min-width="150"/><el-table-column prop="dataSourceName" label="数据源" min-width="130"><template #default="{row}">{{ row.dataSourceName || '-' }}</template></el-table-column><el-table-column prop="resourceApiName" label="资源 API" min-width="150"><template #default="{row}">{{ row.resourceApiName || '-' }}</template></el-table-column><el-table-column label="大小" width="110"><template #default="{row}">{{ (row.fileSize / 1024).toFixed(1) }} KB</template></el-table-column><el-table-column label="上传时间" width="170"><template #default="{row}">{{ formatDateTime(row.uploadedAt) }}</template></el-table-column><el-table-column label="操作" width="150" fixed="right"><template #default="{row}"><el-button link type="primary" @click="downloadResourceDocument(row)">下载</el-button><el-button link type="danger" @click="deleteResourceDocument(row)">删除</el-button></template></el-table-column></el-table>
          </section>
        </template>

        <template v-else-if="activeMenu === 'reports'">
          <section class="report-workspace">
            <div class="report-toolbar"><el-select v-model="reportDimensions" multiple collapse-tags :max-collapse-tags="2" aria-label="统计维度" placeholder="选择维度（按选择顺序展开）"><el-option v-for="option in reportDimensionOptions" :key="option.value" :label="option.label" :value="option.value"/></el-select><el-select v-model="reportMetrics" multiple collapse-tags :max-collapse-tags="2" aria-label="统计度量" placeholder="选择度量"><el-option v-for="option in reportMetricOptions" :key="option.value" :label="option.label" :value="option.value"/></el-select><el-date-picker v-model="reportRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"/><el-button type="primary" :loading="reportLoading" @click="loadUsageReport">查询</el-button><el-button :disabled="!reportRows.length" @click="downloadUsageReport">下载 CSV</el-button></div>
            <div v-if="reportTotal" class="report-total"><strong>总计</strong><span v-for="metric in reportMetrics" :key="metric">{{ reportMetricOptions.find(item=>item.value===metric)?.label }} <b>{{ reportTotal[metric as keyof UsageReportRow] ?? '-' }}</b></span></div>
            <el-table :data="reportTree" :row-key="(row:UsageReportRow)=>JSON.stringify(row.path)" :tree-props="{ children: 'children' }" v-loading="reportLoading" empty-text="当前条件下暂无调用记录"><el-table-column prop="label" label="统计层级" min-width="260"/><el-table-column v-for="metric in reportMetrics" :key="metric" :prop="metric" :label="reportMetricOptions.find(item=>item.value===metric)?.label || metric" min-width="125"/></el-table>
          </section>
        </template>

        <template v-else-if="activeMenu === 'providers'">
          <section class="provider-management">
            <aside class="provider-list plain-panel"><div class="panel-heading"><strong>资源提供商</strong><el-button :icon="Plus" text aria-label="新增资源提供商" @click="openNewProvider" /></div><button v-for="provider in providers" :key="provider.id" class="provider-item" :class="{active: provider.id === selectedProviderId}" @click="selectedProviderId = provider.id; loadProviderAccounts()"><span class="provider-avatar">{{ provider.providerName.slice(0,1) }}</span><span><strong>{{ provider.providerName }}</strong><small>{{ provider.providerCode }}</small></span><el-tag size="small" type="success">{{ provider.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></button></aside>
            <section v-if="selectedProvider" class="provider-detail plain-panel">
              <div class="panel-heading">
                <div><strong>{{ selectedProvider.providerName }}</strong><span>{{ selectedProvider.providerCode }} · 提供商信息</span></div>
                <div>
                  <el-button v-if="selectedProvider.accessMode === 'DATABASE'" :icon="Connection" :loading="databaseConnectionTesting" @click="testDatabaseConnection">测试连接</el-button>
                  <el-button @click="openSourceRuntimeEditor">配置应用路由</el-button>
                  <el-button type="primary" :icon="Setting" @click="openProviderEditor()">编辑资料</el-button>
                  <el-button type="danger" plain @click="deleteSelectedProvider">删除资源方</el-button>
                </div>
              </div>
              <div v-if="selectedProvider.accessMode === 'DATABASE' && databaseConnectionResult" class="database-test-result" :class="databaseConnectionResult.success ? 'is-success' : 'is-error'" role="status">
                <el-icon><CircleCheck v-if="databaseConnectionResult.success"/><Warning v-else/></el-icon>
                <span>{{ databaseConnectionResult.message }}</span>
                <small v-if="databaseConnectionResult.success && databaseConnectionResult.databaseProduct">{{ databaseConnectionResult.databaseProduct }}</small>
                <small v-if="databaseConnectionResult.durationMs">{{ databaseConnectionResult.durationMs }} ms</small>
              </div>
              <div class="provider-info-grid"><div><span>机构类别</span><strong>{{ providerCategoryLabel(selectedProvider.providerType) }}</strong></div><div><span>接入类型</span><strong>{{ providerAccessModeLabel(selectedProvider.accessMode) }}</strong></div><div><span>处理应用</span><strong>{{ selectedProvider.applicationName || '待配置' }}</strong></div><div><span>应用路由</span><strong>{{ selectedProvider.runtimeUrl ? '已配置' : '待配置' }}</strong></div><div><span>经办人</span><strong>{{ selectedProvider.contactName || '-' }}</strong></div><div><span>接入时间</span><strong>{{ selectedProvider.accessDate || '-' }}</strong></div><div><span>通讯报文编码</span><strong>{{ selectedProvider.messageEncoding || '-' }}</strong></div><div><span>收费要求</span><strong>{{ selectedProvider.chargeRequired ? '是' : '否' }}</strong></div><div><span>加密要求</span><strong>{{ selectedProvider.encryptionRequired ? '是' : '否' }}</strong></div><div><span>登录要求</span><strong>{{ selectedProvider.loginRequired ? '是' : '否' }}</strong></div><div class="wide"><span>提供商描述</span><strong>{{ selectedProvider.description || '-' }}</strong></div></div><details v-if="selectedProvider.runtimeUrl" class="address-disclosure provider-address-disclosure"><summary>查看应用路由地址</summary><div class="address-disclosure-body"><span>平台访问处理应用</span><code>{{ selectedProvider.runtimeUrl }}</code><el-button text :icon="DocumentCopy" aria-label="复制应用路由地址" @click="copyApiValue(selectedProvider.runtimeUrl)">复制</el-button></div></details><div class="section-toolbar"><div><strong>账号与权限</strong><HelpTip title="账号与权限" content="维护资源提供商侧账号及其可操作范围。此处账号不是行内 API 调用系统的访问凭证；调用系统在 API 访问授权中登记。"/></div><el-button type="primary" :icon="Plus" @click="openAccountDialog">新增账号</el-button></div><el-table :data="providerAccounts" border><el-table-column prop="username" label="账号" width="150"/><el-table-column prop="displayName" label="显示名称" width="130"/><el-table-column label="角色" width="160"><template #default="{row}">{{ providerRoleLabel(row.roleCode) }}</template></el-table-column><el-table-column label="权限" min-width="320"><template #default="{row}"><el-tag v-for="permission in row.permissions" :key="permission" size="small" effect="plain" class="permission-tag">{{ providerPermissionLabel(permission) }}</el-tag></template></el-table-column><el-table-column prop="lastLoginAt" label="最近登录" width="170"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status==='ACTIVE'?'success':'info'">{{ row.status==='ACTIVE'?'启用':'停用' }}</el-tag></template></el-table-column></el-table>
            </section>
          </section>
        </template>
        <template v-else-if="activeMenu === 'platform-management'">
          <section class="platform-management">
            <section v-if="platformManagementTab==='contract'" class="platform-management-panel plain-panel">
              <div class="platform-list-toolbar"><el-button type="primary" :icon="Plus" @click="editPlatformContract()">新增标准响应</el-button></div>
              <el-table :data="platformResponseContracts" size="small" class="platform-contract-list" empty-text="暂无标准响应"><el-table-column prop="contractCode" label="编码" width="180"><template #default="{row}"><code>{{ row.contractCode }}</code></template></el-table-column><el-table-column prop="contractName" label="名称" min-width="200"/><el-table-column label="版本" width="80"><template #default="{row}">V{{ row.version }}</template></el-table-column><el-table-column label="状态" width="120"><template #default="{row}"><el-tag size="small" :type="row.active?'success':row.enabled?'info':'warning'">{{ row.active ? '当前生效' : row.enabled ? '待生效' : '停用' }}</el-tag></template></el-table-column><el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip/><el-table-column label="操作" width="240" fixed="right"><template #default="{row}"><el-button text type="primary" @click.stop="viewPlatformContract(row)">查看</el-button><el-button text type="primary" @click.stop="editPlatformContract(row)">编辑</el-button><el-button text type="success" :disabled="row.active || !row.enabled" @click.stop="activatePlatformContract(row)">立即生效</el-button><el-button text type="danger" :disabled="row.defaultContract || row.active" @click.stop="deletePlatformContract(row)">删除</el-button></template></el-table-column></el-table>
            </section>

            <section v-else-if="platformManagementTab==='codes'" class="platform-management-panel plain-panel">
              <div class="platform-toolbar"><el-input v-model="platformCodeSearch" :prefix-icon="Search" clearable placeholder="搜索返回码、名称或说明"/><el-button type="primary" :icon="Plus" @click="editPlatformCode()">新增返回码</el-button></div>
              <el-table :data="filteredPlatformResponseCodes" v-loading="loading" size="small" class="platform-code-table" empty-text="暂无平台返回码"><el-table-column prop="code" label="返回码" width="180"><template #default="{row}"><code>{{ row.code }}</code></template></el-table-column><el-table-column prop="name" label="名称" min-width="160"/><el-table-column label="结果状态" width="110"><template #default="{row}"><el-tag size="small" :type="row.resultStatus==='HIT'?'success':row.resultStatus==='MISS'?'info':'danger'">{{ row.resultStatus==='HIT'?'命中':row.resultStatus==='MISS'?'未命中':'异常' }}</el-tag></template></el-table-column><el-table-column label="状态" width="90"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column prop="description" label="说明" min-width="240" show-overflow-tooltip/><el-table-column label="操作" width="130" fixed="right"><template #default="{row}"><el-button text type="primary" @click="editPlatformCode(row)">编辑</el-button><el-button text type="danger" @click="deletePlatformCode(row)">删除</el-button></template></el-table-column></el-table>
            </section>

            <section v-else-if="platformManagementTab==='security'" class="platform-management-panel plain-panel">
              <el-table :data="securityAlgorithms" size="small" class="platform-code-table" empty-text="安全算法目录尚未加载"><el-table-column prop="code" label="能力编码" width="190"><template #default="{row}"><code>{{ row.code }}</code></template></el-table-column><el-table-column prop="name" label="名称" min-width="150"/><el-table-column label="类型" width="110"><template #default="{row}"><el-tag size="small" effect="plain">{{ row.kind==='ENCRYPT'?'加密':'签名' }}</el-tag></template></el-table-column><el-table-column prop="category" label="分类" width="150"/><el-table-column prop="description" label="说明" min-width="280" show-overflow-tooltip/><el-table-column label="来源" width="100"><template #default="{row}">{{ row.builtIn ? '内置' : '扩展' }}</template></el-table-column></el-table>
            </section>

            <section v-else-if="platformManagementTab==='audit'" class="platform-management-panel plain-panel">
              <div class="audit-filter-grid">
                <el-date-picker v-model="auditRange" class="audit-date-picker" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" unlink-panels/>
                <el-select v-model="auditFilters.action" clearable placeholder="全部操作类型"><el-option label="登录" value="LOGIN"/><el-option label="退出" value="LOGOUT"/><el-option label="提交（POST）" value="POST"/><el-option label="修改（PUT）" value="PUT"/><el-option label="局部修改（PATCH）" value="PATCH"/><el-option label="删除（DELETE）" value="DELETE"/></el-select>
                <el-input v-model="auditFilters.operator" clearable placeholder="操作账号前缀" @keyup.enter="searchOperationAudit"/>
                <el-select v-model="auditFilters.targetType" clearable placeholder="全部对象类型"><el-option v-for="(label,key) in auditTargetLabels" :key="key" :label="label" :value="key"/></el-select>
                <el-select v-model="auditFilters.outcome" clearable placeholder="全部结果"><el-option label="成功" value="SUCCESS"/><el-option label="失败" value="FAILURE"/></el-select>
                <div class="audit-filter-actions"><el-button type="primary" :loading="auditLoading" @click="searchOperationAudit">查询</el-button><el-button :disabled="auditLoading" @click="resetOperationAudit">重置</el-button></div>
              </div>
              <div class="audit-results-bar"><span>操作记录</span><strong>{{ auditTotal.toLocaleString() }}</strong><small>条</small></div>
              <el-table :data="auditRows" v-loading="auditLoading" size="small" row-key="id" class="audit-table" empty-text="当前条件下暂无操作记录" @row-click="(row:OperationAuditRecord)=>{selectedAuditRecord=row;auditDetailDrawer=true}">
                <el-table-column prop="occurredAt" label="操作时间" width="170"><template #default="{row}">{{ formatDateTime(row.occurredAt) }}</template></el-table-column>
                <el-table-column prop="operatorName" label="操作账号" width="130" show-overflow-tooltip/>
                <el-table-column label="操作类型" width="110"><template #default="{row}">{{ auditActionLabel(row) }}</template></el-table-column>
                <el-table-column label="操作对象" min-width="220" show-overflow-tooltip><template #default="{row}"><span class="audit-target">{{ auditTargetLabels[row.targetType] || row.targetType }}</span><code>{{ row.targetPath }}</code></template></el-table-column>
                <el-table-column label="结果" width="90"><template #default="{row}"><el-tag size="small" effect="plain" :type="row.outcome==='SUCCESS'?'success':'danger'">{{ row.outcome==='SUCCESS'?'成功':'失败' }}</el-tag></template></el-table-column>
                <el-table-column prop="traceId" label="追踪号" min-width="150" show-overflow-tooltip/>
              </el-table>
              <el-pagination v-model:current-page="auditPage" v-model:page-size="auditSize" class="audit-pagination" :total="auditTotal" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper" :disabled="auditLoading" @current-change="loadOperationAudit" @size-change="searchOperationAudit"/>
              <el-drawer v-model="auditDetailDrawer" title="操作记录详情" size="min(520px, 92vw)" destroy-on-close>
                <el-descriptions v-if="selectedAuditRecord" :column="1" border size="small">
                  <el-descriptions-item label="操作时间">{{ formatDateTime(selectedAuditRecord.occurredAt) }}</el-descriptions-item>
                  <el-descriptions-item label="操作账号">{{ selectedAuditRecord.operatorName }}</el-descriptions-item>
                  <el-descriptions-item label="操作类型">{{ auditActionLabel(selectedAuditRecord) }}</el-descriptions-item>
                  <el-descriptions-item label="操作对象">{{ auditTargetLabels[selectedAuditRecord.targetType] || selectedAuditRecord.targetType }}</el-descriptions-item>
                  <el-descriptions-item label="请求路径"><code>{{ selectedAuditRecord.targetPath }}</code></el-descriptions-item>
                  <el-descriptions-item label="处理结果">{{ selectedAuditRecord.outcome==='SUCCESS'?'成功':'失败' }}（HTTP {{ selectedAuditRecord.httpStatus }}）</el-descriptions-item>
                  <el-descriptions-item label="客户端地址">{{ selectedAuditRecord.clientIp || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="追踪号">{{ selectedAuditRecord.traceId || '-' }}</el-descriptions-item>
                </el-descriptions>
              </el-drawer>
            </section>
          </section>
        </template>
        <template v-else-if="activeMenu === 'releases'">
          <section class="release-console release-console-list">
            <section class="release-service-list plain-panel">
              <div class="release-service-filters">
                <el-input v-model="releaseFilters.keyword" :prefix-icon="Search" clearable placeholder="搜索服务名称、编码或路径" aria-label="搜索服务" @keyup.enter="loadReleaseServices(true)" @clear="loadReleaseServices(true)"/>
                <el-select v-model="releaseFilters.providerId" filterable aria-label="资源提供商" @change="releaseFilters.dataSourceId=0;loadReleaseServices(true)"><el-option label="全部资源提供商" :value="0"/><el-option v-for="provider in providers" :key="provider.id" :label="provider.providerName" :value="provider.id"/></el-select>
                <el-select v-model="releaseFilters.dataSourceId" filterable aria-label="数据源" @change="loadReleaseServices(true)"><el-option label="全部数据源" :value="0"/><el-option v-for="source in sources.filter(item=>!releaseFilters.providerId || item.providerId===releaseFilters.providerId)" :key="source.id" :label="source.sourceName" :value="source.id"/></el-select>
                <el-select v-model="releaseFilters.runStatus" aria-label="运行状态" @change="releaseQuickView='CUSTOM';loadReleaseServices(true)"><el-option label="全部运行状态" value="ALL"/><el-option label="未发布" value="UNPUBLISHED"/><el-option label="运行中" value="RUNNING"/><el-option label="已下线" value="OFFLINE"/></el-select>
                <el-select v-model="releaseFilters.authorizationStatus" aria-label="授权状态" @change="releaseQuickView='CUSTOM';loadReleaseServices(true)"><el-option label="全部授权状态" value="ALL"/><el-option label="无有效授权" value="UNAUTHORIZED"/><el-option label="有有效授权" value="AUTHORIZED"/></el-select>
                <el-button type="primary" @click="loadReleaseServices(true)">查询</el-button>
              </div>
              <div class="release-quick-views"><button v-for="view in [{key:'ALL',label:'全部服务'},{key:'RUNNING_UNAUTHORIZED',label:'运行中未授权'},{key:'RUNNING_AUTHORIZED',label:'运行中已授权'},{key:'OFFLINE',label:'已下线'}]" :key="view.key" :class="{active:releaseQuickView===view.key}" @click="applyReleaseQuickView(view.key)">{{ view.label }}</button><span>共 {{ releaseServiceTotal }} 个服务</span></div>
              <el-table :data="releaseServiceRows" v-loading="releaseServiceLoading" row-key="service.id" size="small" class="release-services-table" empty-text="当前筛选条件下暂无服务" @row-click="handleReleaseServiceRowClick">
                <el-table-column label="服务名称 / 编码" min-width="230"><template #default="{row}"><strong>{{ row.service.interfaceName }}</strong><small>{{ row.service.interfaceCode }}</small></template></el-table-column>
                <el-table-column label="资源归属" min-width="160"><template #default="{row}"><span>{{ row.providerName }}</span><small>{{ row.dataSourceName }}</small></template></el-table-column>
                <el-table-column label="开发版本" width="95"><template #default="{row}">V{{ row.service.currentVersion }}</template></el-table-column>
                <el-table-column label="线上版本" width="95"><template #default="{row}">{{ row.service.publishedVersion==null ? '-' : 'V'+row.service.publishedVersion }}</template></el-table-column>
                <el-table-column label="运行状态" width="105"><template #default="{row}"><el-tag size="small" :type="row.runStatus==='OFFLINE'?'danger':row.runStatus==='RUNNING'?'success':'info'">{{ releaseRunStatusLabel(row.runStatus) }}</el-tag></template></el-table-column>
                <el-table-column label="有效授权调用系统数" min-width="150"><template #default="{row}">{{ releaseServiceAuthLabel(row) }}</template></el-table-column>
                <el-table-column label="最近发布时间" min-width="155"><template #default="{row}">{{ row.latestPublishedAt ? formatDateTime(row.latestPublishedAt) : '-' }}</template></el-table-column>
                <el-table-column label="操作" min-width="190" fixed="right" align="center" header-align="center"><template #default="{row}"><div class="release-row-actions" @click.stop><el-button text type="primary" @click.stop="selectReleaseInterface(row.service.id)">详情</el-button><el-button v-if="row.service.status!=='PENDING_APPROVAL' && row.service.currentVersion!==row.service.publishedVersion" text type="primary" @click.stop="submitReleaseFromManagement(row.service)">发布</el-button><el-button v-else-if="row.runStatus==='OFFLINE'" text type="primary" @click.stop="onlineRelease(row.service)">上线</el-button><el-button v-else-if="row.runStatus==='RUNNING'" text type="warning" @click.stop="offlineRelease(row.service)">下线</el-button><el-dropdown trigger="click" @command="(command:string)=>handleReleaseRowCommand(command,row)"><el-button text aria-label="更多操作">更多操作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="published" :disabled="row.service.publishedVersion==null">版本与配置</el-dropdown-item><el-dropdown-item command="authorize" :disabled="row.runStatus!=='RUNNING'">访问授权</el-dropdown-item><el-dropdown-item command="archive" :disabled="!(row.runStatus==='OFFLINE' && row.service.publishedVersion!=null) && !(row.service.status==='DRAFT' && row.service.publishedVersion==null)">归档删除</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></template></el-table-column>
              </el-table>
              <el-pagination v-model:current-page="releaseServicePage" v-model:page-size="releaseServiceSize" :total="releaseServiceTotal" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" background @current-change="loadReleaseServices()" @size-change="loadReleaseServices(true)"/>
            </section>
          </section>
          <el-drawer v-model="releaseDetailDrawer" title="服务发布详情" size="min(960px, 92vw)" class="release-detail-drawer">
            <div v-if="releaseDetail" class="release-console-main" :class="{'is-refreshing':releaseLoading}" aria-live="polite">
              <section class="release-command plain-panel">
                <div class="release-command-title"><span class="release-command-icon"><Promotion/></span><div><small aria-hidden="true" class="decorative-eyebrow"></small><h2>{{ releaseDetail.summary.interfaceName }}</h2><p><code>{{ releaseDetail.summary.interfaceCode }}</code></p></div></div>
                <div class="release-state"><span>当前开发版本<strong>V{{ releaseDetail.summary.currentVersion }}</strong></span><b></b><span>{{ releaseDetail.summary.status==='OFFLINE' ? '最近上线' : '线上生效' }}<strong>{{ releaseDetail.summary.publishedVersion == null ? '-' : 'V'+releaseDetail.summary.publishedVersion }}</strong></span><el-tag effect="plain" :type="releaseDetail.summary.status==='OFFLINE'?'danger':releaseDetail.summary.status==='PENDING_APPROVAL'?'warning':releaseDetail.summary.publishedVersion!=null?'success':'info'">{{ releaseDetail.summary.status==='OFFLINE'?'已下线':releaseDetail.summary.status==='PENDING_APPROVAL'?'审批中':releaseDetail.summary.publishedVersion!=null?'运行中':'未上线' }}</el-tag></div>
                <p v-if="releaseDetail.summary.publishedVersion != null && releaseDetail.summary.currentVersion !== releaseDetail.summary.publishedVersion" class="release-version-note">开发版本 V{{ releaseDetail.summary.currentVersion }} 尚未发布；{{ releaseDetail.summary.status === 'OFFLINE' ? '服务已下线，最近上线版本为' : '线上仍使用' }} V{{ releaseDetail.summary.publishedVersion }}。</p>
              </section>
              <section class="release-config-summary plain-panel">
                <header><h3>当前开发配置</h3><span>V{{ releaseDetail.summary.currentVersion }}</span></header>
                <dl>
                  <div class="release-config-route"><dt>内部 HTTP 服务路径</dt><dd><el-tag size="small" effect="plain" type="info">{{ releaseDetail.summary.httpMethod }}</el-tag><code>{{ releaseDetail.summary.servicePath }}</code><el-button text :icon="DocumentCopy" aria-label="复制服务路径" title="复制服务路径" @click="copyApiValue(releaseDetail.summary.servicePath)"/></dd></div>
                  <div><dt>资源提供商</dt><dd>{{ releaseDetailProvider?.providerName || releaseDetailSource?.providerName || '-' }}</dd></div>
                  <div><dt>数据源</dt><dd>{{ releaseDetailSource?.sourceName || '-' }}</dd></div>
                  <div><dt>来源资源 API</dt><dd>{{ releaseDetail.summary.resourceApiName || '-' }}<code v-if="releaseDetail.summary.resourceApiCode">{{ releaseDetail.summary.resourceApiCode }}</code></dd></div>
                  <div><dt>处理应用</dt><dd>{{ releaseDetailProvider?.applicationName || releaseDetailSource?.applicationName || '未配置' }}</dd></div>
                  <div><dt>上游接口路径 / 地址</dt><dd><code>{{ releaseDetail.summary.upstreamPath || releaseDetail.summary.upstreamUrl || '-' }}</code></dd></div>
                  <div><dt>调用超时</dt><dd>{{ releaseDetail.timeoutMs }} ms</dd></div>
                </dl>
              </section>
              <div v-if="!sources.find(source=>source.id===releaseDetail?.summary.dataSourceId)?.runtimeUrl" class="release-routing-warning" role="status"><div><strong>发布前需配置应用路由</strong><span>当前资源提供商尚未配置 Gateway 可访问的处理应用地址，完成配置后可继续发布。</span></div><el-button @click="editReleaseRuntime">前往资源方管理</el-button></div>
              <section id="interface-version-history" class="version-history-panel plain-panel">
                <header><div><h3>接口版本历史</h3></div><el-tag type="info">{{ releaseVersions.length }} 个版本</el-tag></header>
                <el-alert v-if="releaseVersions.some(item => !item.historyComplete)" title="检测到旧版本记录不完整" type="warning" :closable="false" description="接口主表保留了已发布版本号，但旧审批单、发布记录或执行计划快照缺失；版本仍保留并阻止未经下线的删除。"/>
                <el-table :data="releaseVersions" border :row-class-name="versionRowClass">
                  <el-table-column label="版本" width="92"><template #default="{row}"><strong>V{{ row.version }}</strong></template></el-table-column>
                  <el-table-column label="状态" width="150"><template #default="{row}"><el-tag :type="versionTagType(row)">{{ versionStatusText(row) }}</el-tag><small v-if="row.active" class="version-active-label">流量使用中</small></template></el-table-column>
                  <el-table-column label="发布说明" min-width="170" show-overflow-tooltip><template #default="{row}">{{ row.releaseNote || (row.historyComplete ? '未填写' : '旧数据未保留') }}</template></el-table-column>
                  <el-table-column label="申请 / 审批" min-width="135"><template #default="{row}"><span>{{ row.applicant || '-' }}</span><small class="version-cell-sub">{{ row.reviewedBy || row.releasedBy || '-' }}</small></template></el-table-column>
                  <el-table-column label="时间" width="150"><template #default="{row}">{{ row.releasedAt || row.reviewedAt || row.submittedAt || '-' }}</template></el-table-column>
                  <el-table-column label="计划快照" width="100"><template #default="{row}"><el-tag size="small" :type="row.snapshotAvailable?'success':'info'">{{ row.snapshotAvailable?'已留存':'无快照' }}</el-tag></template></el-table-column>
                  <el-table-column label="操作" width="70" align="center" header-align="center"><template #default="{row}"><el-button text type="primary" @click="showVersionDetail(row)">查看</el-button></template></el-table-column>
                </el-table>
              </section>
            </div>
            <div v-else class="plain-panel release-console-empty"><Promotion/><strong>{{ releaseLoading ? '服务详情加载中' : '服务详情加载失败，请重新打开' }}</strong></div>
          </el-drawer>
        </template>
        <template v-else-if="activeMenu === 'approvals'">
          <section class="metric-strip"><div><span>待审批</span><strong class="warning-text">{{ pendingApprovals.length }}</strong></div><div><span>已通过</span><strong class="success-text">{{ approvals.filter(item => item.status === 'APPROVED').length }}</strong></div><div><span>已驳回</span><strong>{{ approvals.filter(item => item.status === 'REJECTED').length }}</strong></div></section>
          <section class="plain-panel table-panel"><div class="panel-heading"><div><strong>接口发布审批</strong><span>只有审批通过才会写入发布记录并激活 PublishedPlan</span></div><el-button :icon="Refresh" @click="refreshApprovals">刷新</el-button></div><el-table :data="approvals" border><el-table-column prop="interfaceName" label="接口" min-width="220"/><el-table-column prop="interfaceCode" label="接口编码" width="180"/><el-table-column label="申请版本" width="100"><template #default="{row}">V{{ row.releaseVersion }}</template></el-table-column><el-table-column prop="applicant" label="申请人" width="120"/><el-table-column prop="approvalNode" label="当前节点" width="120"/><el-table-column prop="submittedAt" label="提交时间" width="180"/><el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.status==='APPROVED'?'success':row.status==='REJECTED'?'danger':'warning'">{{ row.status==='PENDING'?'待审批':row.status==='APPROVED'?'已通过':'已驳回' }}</el-tag></template></el-table-column><el-table-column label="操作" width="100"><template #default="{row}"><el-button v-if="row.status==='PENDING'" text type="primary" @click="openApproval(row)">处理</el-button></template></el-table-column></el-table></section>
        </template>
        <template v-else-if="activeMenu === 'resource' || activeMenu === 'service-development'">
          <section class="workbench resource-workbench" :class="{ 'source-switching': sourceSwitching, 'catalog-collapsed':resourceCatalogCollapsed, 'service-workbench':activeMenu==='service-development', 'resource-access-workbench':activeMenu==='resource' }">
            <aside class="resource-tree-panel" :class="{collapsed:resourceCatalogCollapsed}">
              <button v-if="resourceCatalogCollapsed" class="api-catalog-rail" type="button" title="展开 API 资源目录" aria-label="展开 API 资源目录" @click="resourceCatalogCollapsed=false"><Expand/><span>API</span></button>
              <template v-else>
              <div class="panel-title"><div><span>{{ activeMenu==='resource' ? '资源 API 目录' : '平台服务目录' }}</span></div><div class="catalog-heading-actions"><el-button :icon="Fold" text title="收起 API 目录" aria-label="收起 API 目录" @click="resourceCatalogCollapsed=true"/></div></div>
              <el-button v-if="activeMenu==='service-development'" class="create-standard-service" type="primary" plain :icon="Plus" @click="openPlatformServiceDialog">新增标准服务</el-button>
              <el-input v-model="sourceSearch" :prefix-icon="Search" placeholder="搜索提供商、数据源、API 或路径" clearable aria-label="搜索资源目录" />
              <div class="resource-tree-tools"><button :disabled="!!sourceSearch.trim()" @click="setAllSourcesExpanded(true)">展开全部</button><button :disabled="!!sourceSearch.trim()" @click="setAllSourcesExpanded(false)">收起全部</button><button :disabled="!selectedInterfaceId" @click="locateSelectedApi">定位当前</button></div>
              <p v-if="sourceSearch.trim()" class="resource-search-hint">找到 {{ filteredProviderGroups.length }} 个资源提供商、{{ filteredSources.length }} 个数据源，已展开匹配结果</p>
              <div class="resource-tree" aria-label="资源提供商、数据源与接口目录">
                <section v-for="providerGroup in filteredProviderGroups" :key="providerGroup.providerId" class="resource-provider-group">
                  <div class="resource-provider-heading">
                    <button class="source-expand-toggle provider-expand-toggle" :class="{expanded:isProviderExpanded(providerGroup.providerId)}" :aria-expanded="isProviderExpanded(providerGroup.providerId)" :aria-controls="'provider-sources-' + providerGroup.providerId" :aria-label="(isProviderExpanded(providerGroup.providerId) ? '收起' : '展开') + providerGroup.providerName" :disabled="!!sourceSearch.trim()" @click="providerExpansion[providerGroup.providerId] = !isProviderExpanded(providerGroup.providerId)"><ArrowRight/></button>
                    <button class="resource-provider-head" :title="providerGroup.providerName" :disabled="!!sourceSearch.trim()" @click="providerExpansion[providerGroup.providerId] = !isProviderExpanded(providerGroup.providerId)"><span><strong>{{ providerGroup.providerName }}</strong><small v-if="activeMenu==='resource' && providerGroup.providerCode">{{ providerGroup.providerCode }}</small></span><em>{{ providerGroup.sources.length }} 个数据源</em></button>
                  </div>
                  <div v-show="isProviderExpanded(providerGroup.providerId)" :id="'provider-sources-' + providerGroup.providerId" class="resource-provider-sources">
                    <div v-for="source in providerGroup.sources" :key="source.id" class="resource-source-group" :class="{active: source.id === selectedSourceId}">
                      <div class="resource-source-heading">
                        <button class="source-expand-toggle" :class="{expanded:isSourceExpanded(source.id)}" :aria-expanded="isSourceExpanded(source.id)" :aria-controls="'source-apis-' + source.id" :aria-label="(isSourceExpanded(source.id) ? '收起' : '展开') + source.sourceName" :disabled="!!sourceSearch.trim()" @click="sourceExpansion[source.id] = !isSourceExpanded(source.id)"><ArrowRight/></button>
                        <button class="resource-source-head" :title="source.sourceName + ' · ' + source.sourceCode" @click="selectSource(source.id)"><span><strong>{{ source.sourceName }}</strong><small>{{ activeMenu==='resource' ? source.sourceCode + ' · ' : '' }}{{ sourceInterfaces(source.id).length }} 个{{ activeMenu==='resource' ? '资源 API' : '标准服务' }}</small></span><i class="status-dot" :class="source.status.toLowerCase()"></i></button>
                      </div>
                      <div v-show="isSourceExpanded(source.id)" :id="'source-apis-' + source.id">
                        <div class="resource-api-list">
                          <button v-for="apiItem in sourceInterfaces(source.id)" :key="apiItem.id" class="resource-api-item" :class="{active: apiItem.id === selectedInterfaceId}" :aria-current="apiItem.id === selectedInterfaceId ? 'page' : undefined" :title="apiItem.interfaceName + ' · ' + apiItem.interfaceCode" @click="selectResourceInterface(source.id, apiItem.id)"><span v-if="activeMenu==='resource'" class="api-method">{{ apiItem.httpMethod }}</span><span><strong>{{ apiItem.interfaceName }}</strong><small v-if="activeMenu==='resource'">{{ apiItem.interfaceCode }}</small></span><el-tag size="small" :type="serviceDevelopmentStage==='UPSTREAM' ? (apiItem.upstreamStatus==='READY'?'success':'info') : apiItem.status === 'OFFLINE' ? 'danger' : apiItem.status === 'PUBLISHED' ? 'success' : 'info'">{{ serviceDevelopmentStage==='UPSTREAM' ? (apiItem.upstreamStatus==='READY'?'已接入':'未保存') : apiItem.status === 'OFFLINE' ? '已下线' : apiItem.status === 'PUBLISHED' ? '已发布' : '开发中' }}</el-tag></button>
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
                  <div class="api-breadcrumb">{{ selectedSource.providerName }}<span>/</span>{{ selectedSource.sourceName }}<template v-if="activeMenu!=='resource'"><span>/</span>标准服务</template></div>
                  <div class="context-title"><span v-if="activeMenu==='resource'" class="method">{{ detail.summary.httpMethod }}</span><h2>{{ detail.summary.interfaceName }}</h2><el-tag v-if="activeMenu==='resource'" :type="detail.summary.upstreamStatus==='READY'?'success':'info'" size="small">{{ detail.summary.upstreamStatus==='READY'?'已接入':'接入草稿' }}</el-tag><el-tag v-else :type="detail.summary.status === 'OFFLINE' ? 'danger' : detail.summary.status === 'PUBLISHED' ? 'success' : 'info'" size="small">{{ detail.summary.status === 'OFFLINE' ? '线上已下线' : detail.summary.status === 'PUBLISHED' ? '已发布' : '开发中' }}</el-tag></div>
                  <div v-if="activeMenu==='resource'" class="api-context-meta"><code>{{ detail.summary.interfaceCode }}</code></div>
                </div>
                <div class="api-context-actions">
                  <el-button v-if="activeMenu==='resource'" :icon="Edit" @click="openResourceApiMetadataEditor">编辑 API</el-button>
                  <el-button v-if="activeMenu==='resource'" :icon="Setting" @click="openSourceSettingsEditor">编辑数据源</el-button>

                  <el-button v-if="activeMenu!=='resource'" :icon="Promotion" @click="openVersionHistory(detail.summary.publishedVersion ?? undefined)">版本档案</el-button>
                  <el-tooltip v-if="activeMenu!=='resource'" :disabled="canDeleteApi" :content="deleteApiRestriction" placement="bottom"><span><el-button :icon="Delete" type="danger" plain :disabled="!canDeleteApi || detailLoading || sourceSwitching" :loading="deletingApi" @click="deleteCurrentInterface">归档服务</el-button></span></el-tooltip>
                </div>
              </header>
              <nav v-if="serviceDevelopmentStage==='UPSTREAM'" class="developer-tabs" aria-label="资源接口配置">
                <button v-for="(step,index) in upstreamSections" :key="step.title" type="button" :class="{active:upstreamSection===index}" :aria-current="upstreamSection===index?'page':undefined" @click="upstreamSection=index">{{ step.title }}</button>
              </nav>
              <nav v-if="serviceDevelopmentStage==='ORCHESTRATION'" class="developer-tabs" aria-label="标准服务配置"><button v-for="step in orchestrationGuideSteps" :key="step.key" :class="{active:activeTab===step.key,changed:activeTab===step.key && hasUnsavedChanges}" :aria-current="activeTab===step.key ? 'page' : undefined" :disabled="guideBusy || testRunning || connectionSaving" @click="openGuideStep(step.key)">{{ step.title }}</button></nav>
              <div v-if="guideError" class="api-guide-error compact" role="alert">{{ guideError }}</div>
              <el-tabs v-model="activeTab" class="editor-tabs">
                <el-tab-pane v-if="serviceDevelopmentStage==='UPSTREAM'" name="overview" label="上游能力配置">
                  <div class="api-connection-workspace">
                    <div v-show="upstreamSection===0" class="api-config-columns">
                    <section class="api-config-card">
                        <header><div><h3>接口连接</h3></div><el-tag :type="previewUpstreamUrl ? 'info' : 'warning'" effect="plain" size="small">{{ connectionForm.configScope==='INHERIT' ? '继承数据源' : '独立配置' }}</el-tag></header>
                        <div class="api-card-body">
                          <template v-if="connectionForm.executionMode==='SQL'">
                            <el-form label-position="top">
                              <el-form-item label="SQL 查询语句" required><CopyableBlock :value="connectionForm.sqlTemplate"><el-input v-model="connectionForm.sqlTemplate" type="textarea" :rows="10" resize="vertical" placeholder="SELECT id, name FROM user_info WHERE user_id = :userId"/></CopyableBlock></el-form-item>
                              <el-form-item label="请求参数 JSON"><CopyableBlock :value="connectionForm.sqlParameters"><el-input v-model="connectionForm.sqlParameters" type="textarea" :rows="5" resize="vertical" placeholder="{\n  &quot;userId&quot;: &quot;10001&quot;\n}"/></CopyableBlock></el-form-item>
                            </el-form>
                          </template>
                          <template v-else>
                          <el-form v-if="connectionForm.configScope==='INHERIT'" label-position="top"><el-form-item label="接口路径"><el-input v-model="connectionForm.upstreamPath" placeholder="/vendor/api/enterprise-basic"/></el-form-item></el-form>
                          <div class="connection-summary"><span>上游连接</span><strong>{{ connectionForm.configScope==='INHERIT' ? (selectedSource.endpointUrl ? `继承「${selectedSource.sourceName}」` : '数据源地址待配置') : '当前接口独立配置' }}</strong><button type="button" :aria-expanded="connectionDetailsExpanded" aria-controls="resource-connection-details" @click="connectionDetailsExpanded=!connectionDetailsExpanded">{{ connectionDetailsExpanded ? '收起详情' : '查看详情' }}<ArrowRight :class="{expanded:connectionDetailsExpanded}"/></button></div>
                          <div v-if="connectionDetailsExpanded || !previewUpstreamUrl" id="resource-connection-details" class="connection-details">
                            <span class="api-field-label">地址配置方式<HelpTip title="地址配置" content="默认继承数据源的供应商基础地址。只有当前接口使用不同地址时，才选择独立配置。"/></span>
                            <div class="api-mode-options" role="group" aria-label="地址配置方式"><button type="button" :class="{selected:connectionForm.configScope==='INHERIT'}" :aria-pressed="connectionForm.configScope==='INHERIT'" @click="connectionForm.configScope='INHERIT'">继承数据源</button><button type="button" :class="{selected:connectionForm.configScope==='OVERRIDE'}" :aria-pressed="connectionForm.configScope==='OVERRIDE'" @click="connectionForm.configScope='OVERRIDE'">独立地址</button></div>
                            <div v-if="connectionForm.configScope==='INHERIT'" class="address-detail-row"><span>供应商基础地址</span><code>{{ selectedSource.endpointUrl || '尚未配置' }}</code><el-button text @click="openSourceSettingsEditor">修改</el-button></div>
                            <el-form v-else label-position="top"><el-form-item label="当前接口的供应商地址"><el-input v-model="connectionForm.upstreamUrl" placeholder="https://provider.example.com/api/query"/></el-form-item></el-form>
                            <div v-if="previewUpstreamUrl" class="address-detail-row"><span>最终请求地址</span><code>{{ previewUpstreamUrl }}</code><el-button text :icon="DocumentCopy" aria-label="复制供应商请求地址" @click="copyApiValue(previewUpstreamUrl)">复制</el-button></div>
                            <div class="address-detail-row"><span>处理应用路由</span><code>{{ selectedSource.runtimeUrl || '尚未配置' }}</code><el-button text @click="goToProviderManagement()">{{ selectedSource.runtimeUrl ? '查看' : '配置' }}</el-button></div>
                          </div>
                          <details class="upstream-headers-panel">
                            <summary>自定义请求头 <span>{{ connectionForm.requestHeaders.length ? `${connectionForm.requestHeaders.length} 项` : '未配置' }}</span></summary>
                            <div class="upstream-header-rows" v-for="(header, index) in connectionForm.requestHeaders" :key="index">
                              <el-input v-model="header.name" placeholder="Header 名称" aria-label="Header 名称"/>
                              <el-input v-model="header.value" placeholder="请求头值" aria-label="Header 值"/>
                              <el-button :icon="Delete" text aria-label="删除请求头" @click="connectionForm.requestHeaders.splice(index, 1)"/>
                            </div>
                            <el-button :icon="Plus" text @click="connectionForm.requestHeaders.push({ name: '', value: '' })">添加请求头</el-button>
                          </details>
                          </template>
                        </div>
                      </section>
                    </div>
                    <section v-show="upstreamSection===0" class="api-timeout-disclosure" :class="{overridden:connectionForm.timeoutMode==='OVERRIDE'}">
                      <button class="api-timeout-toggle" :aria-expanded="timeoutExpanded" aria-controls="api-timeout-options" @click="timeoutExpanded=!timeoutExpanded">
                        <Setting/><span><strong>请求超时 <em>可选配置</em></strong><small>{{ connectionForm.timeoutMode==='INHERIT' ? '继承数据源默认配置' : '已设置单接口覆盖，不影响其他接口' }} · {{ previewTimeoutMs }} ms</small></span><b>{{ timeoutExpanded ? '收起设置' : '按需调整' }}</b><ArrowRight :class="{expanded:timeoutExpanded}"/>
                      </button>
                      <div v-show="timeoutExpanded" id="api-timeout-options" class="api-card-body">
                          <span class="api-field-label">超时配置方式</span>
                          <div class="api-mode-options" role="group" aria-label="超时配置方式"><button type="button" :class="{selected:connectionForm.timeoutMode==='INHERIT'}" :aria-pressed="connectionForm.timeoutMode==='INHERIT'" @click="connectionForm.timeoutMode='INHERIT'">继承数据源</button><button type="button" :class="{selected:connectionForm.timeoutMode==='OVERRIDE'}" :aria-pressed="connectionForm.timeoutMode==='OVERRIDE'" @click="connectionForm.timeoutMode='OVERRIDE'">独立超时</button></div>
                          <el-form label-position="top"><el-form-item label="超时时间"><div class="api-timeout-input"><el-input-number :model-value="previewTimeoutMs" @update:model-value="(value: number | undefined) => { if (value != null) connectionForm.timeoutMs = value }" :disabled="connectionForm.timeoutMode==='INHERIT'" :min="100" :max="120000" :step="500" controls-position="right"/><span>毫秒（ms）</span></div></el-form-item></el-form>
                        </div>
                    </section>
                    <section v-show="upstreamSection===1" class="upstream-contract-panel">
                      <header><div><div><h3>供应商原始报文</h3></div></div><el-tag size="small" type="info">JSON</el-tag></header>
                      <div class="upstream-contract-grid">
                        <div class="upstream-contract-field"><span>请求报文</span><CopyableBlock :value="upstreamRequestText"><el-input v-model="upstreamRequestText" type="textarea" :autosize="{ minRows: 12, maxRows: 20 }" placeholder="填写资源方接口实际请求 JSON" /></CopyableBlock></div>
                        <div class="upstream-contract-field"><span>响应报文</span><CopyableBlock :value="responseText"><el-input v-model="responseText" type="textarea" :autosize="{ minRows: 12, maxRows: 20 }" placeholder="填写资源方接口实际响应 JSON" /></CopyableBlock></div>
                      </div>
                    </section>
                    <section v-show="upstreamSection===2" class="upstream-validation-panel" :class="{'has-error':!!testError && !currentUpstreamVerified}">
                      <div class="upstream-validation-copy"><div><h3>测试结果<HelpTip title="测试范围" content="通过提供商处理应用发送已保存的原始请求，展示供应商原始响应，不执行标准服务的字段转换、签名或加解密。测试结果不限制标准服务开发。"/></h3></div></div>
                      <div class="upstream-validation-result success" v-if="currentUpstreamVerified"><span class="status-dot"></span><strong>当前连接验证通过</strong><small>已收到上游原始响应</small></div>
                      <div class="upstream-validation-result error" v-else-if="testError" role="alert"><span class="status-dot offline"></span><div><strong>验证失败</strong><small>{{ testError }}</small></div></div>
                      <div v-else class="upstream-validation-idle">尚未执行测试</div>
                    </section>
                    <section v-if="upstreamSection===2" class="resource-test-detail">
                      <details class="address-disclosure test-route-disclosure"><summary>查看调用路径</summary><div class="address-disclosure-body"><span>处理应用</span><code>{{ selectedSource.applicationName || '未配置应用' }} · {{ selectedSource.runtimeUrl || '尚未配置地址' }}</code></div><div class="address-disclosure-body"><span>供应商接口</span><code>{{ previewUpstreamUrl || '尚未配置地址' }}</code></div></details>
                      <div class="test-grid">
                        <div class="code-pane readonly"><div class="code-title"><span>{{ testResult ? '本次原始请求' : '原始请求样例' }}</span></div><CopyableBlock :value="testResult ? formatJson(testResult.upstreamRequest) : upstreamRequestText"><pre>{{ testResult ? formatJson(testResult.upstreamRequest) : upstreamRequestText }}</pre></CopyableBlock></div>
                        <div class="code-pane readonly"><div class="code-title"><span>供应商原始响应</span></div><CopyableBlock :value="testResult ? formatJson(testResult.upstreamResponse) : testError || '等待执行测试'"><pre>{{ testResult ? formatJson(testResult.upstreamResponse) : testError || '等待执行测试' }}</pre></CopyableBlock></div>
                      </div>
                    </section>
                    <p v-if="upstreamSection===2 && !selectedSource.runtimeUrl" class="route-notice">资源提供商尚未配置处理应用。<el-button text type="primary" @click="goToProviderManagement()">前往资源方管理</el-button></p>
                  </div>
                </el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="request" label="请求转换">
                  <details :key="detail.summary.id" class="service-configuration-details">
                    <summary><span>接口信息</span><el-tag v-if="resourceContractChanged" type="warning" size="small" effect="plain">来源定义已变更</el-tag><ArrowRight/></summary>
                    <div class="service-configuration-content">
                      <dl class="service-configuration-facts"><div><dt>服务编码</dt><dd><code>{{ detail.summary.interfaceCode }}</code></dd></div><div><dt>当前草稿</dt><dd>V{{ detail.summary.currentVersion }}</dd></div><div><dt>已发布版本</dt><dd>{{ detail.summary.publishedVersion == null ? '无' : 'V' + detail.summary.publishedVersion }}</dd></div><div><dt>来源资源接口</dt><dd>{{ detail.summary.resourceApiName || '历史服务资源' }}<code>{{ detail.summary.resourceApiCode || '-' }}</code></dd></div></dl>
                      <el-button v-if="detail.summary.resourceApiId" :type="resourceContractChanged ? 'warning' : 'default'" plain :disabled="guideBusy || testRunning" @click="syncResourceContract">同步资源接口定义</el-button>
                      <div class="service-route-fields"><div class="service-route-heading">内部转发<HelpTip title="内部转发" content="ESB Adapter 根据服务编码查询内部服务路径，经 Gateway 转发至处理应用。行内调用系统使用 ESB 队列。"/></div><div class="form-grid"><el-form-item label="HTTP 方法"><el-select v-model="standardServiceForm.httpMethod" aria-label="内部 HTTP 方法"><el-option v-for="method in ['POST','GET','PUT','PATCH']" :key="method" :label="method" :value="method"/></el-select></el-form-item><el-form-item label="服务路径"><el-input v-model="standardServiceForm.servicePath" aria-label="内部服务路径"/></el-form-item></div></div>
                    </div>
                  </details>
                  <div class="mapping-toolbar refined">
                    <div class="mapping-mode"><el-segmented v-model="mappingDirection" :options="[{ label: '请求转换', value: 'REQUEST' }, { label: '响应标准化', value: 'RESPONSE' }]" /><el-tag :type="mappingIsPassThrough ? 'success' : 'warning'" effect="plain">{{ mappingModeLabel }}</el-tag><span>{{ mappingDirection === 'REQUEST' ? '行内标准请求 → 上游原始请求' : '上游原始响应 → 行内标准响应' }}</span></div>
                    <div><HelpTip title="映射规则" content="请求无映射时按原字段透传；响应无映射时将完整报文放入 data。以 $. 开头表示平台根节点。$.code、$.message 可取上游字段或固定值，未配置或可选来源为空时沿用平台默认值。结果状态和计量仍按统一返回码规则判定。"/><el-button :icon="DocumentChecked" @click="validateConfig">校验配置</el-button><el-button :icon="Plus" @click="startMappingConfiguration">{{ mappingIsPassThrough ? '新增映射' : '新增规则' }}</el-button></div>
                  </div>
                  <div class="mapping-sample-panel"><div class="mapping-sample-head"><div><DocumentCopy/><span><strong>{{ mappingDirection === 'REQUEST' ? '行内标准请求样例' : '上游原始响应样例' }}</strong><HelpTip title="报文与映射" :content="mappingDirection==='REQUEST' ? '填写行内标准请求 JSON，可从样例批量生成字段映射。嵌套对象和数组请按实际路径配置。' : '可编辑或导入上游响应 JSON 样例，再生成映射规则。修改仅保存到当前标准服务草稿。'"/></span></div><div><input v-if="mappingDirection === 'RESPONSE'" ref="responseFileInput" type="file" accept=".json,application/json" class="visually-hidden" aria-label="选择响应 JSON 文件" @change="importUpstreamResponse"/><el-button v-if="mappingDirection === 'RESPONSE'" :icon="Upload" @click="responseFileInput?.click()">导入 JSON</el-button><el-button type="primary" plain :icon="DocumentCopy" @click="generateMappingsFromSample">从样例生成映射</el-button></div></div><CopyableBlock :value="mappingDirection === 'REQUEST' ? requestText : responseText"><el-input v-if="mappingDirection === 'REQUEST'" v-model="requestText" type="textarea" :autosize="{minRows:5,maxRows:10}"/><el-input v-else v-model="responseText" type="textarea" :autosize="{minRows:5,maxRows:10}" aria-label="上游原始响应样例"/></CopyableBlock></div>
                  <div class="mapping-board">
                    <div class="mapping-board-head"><span>#</span><span>{{ mappingDirection === 'REQUEST' ? '行内标准字段' : '供应商响应来源' }}</span><span>处理方式</span><span>{{ mappingDirection === 'REQUEST' ? '上游请求字段' : '写入平台响应' }}</span><span>属性</span></div>
                    <div v-if="mappingIsPassThrough" class="mapping-empty"><Connection/><strong>{{ mappingDirection === 'REQUEST' ? '请求按原字段透传' : '完整响应默认写入 data' }}</strong><el-button type="primary" :icon="Plus" @click="startMappingConfiguration">新增映射</el-button></div>
                    <div v-for="(row,index) in filteredMappings" :key="mappingRowKey(row)" class="mapping-row" :class="{'is-rule-open':activeMappingRuleRow===row}">
                      <span class="mapping-index">{{ String(index + 1).padStart(2, '0') }}</span>
                      <div class="field-cell"><label>{{ mappingDirection === 'REQUEST' ? '行内标准字段' : '供应商响应来源' }}</label><el-select v-if="mappingDirection==='RESPONSE'" :model-value="['WHOLE_BODY','CONSTANT'].includes(row.transformType) ? row.transformType : 'FIELD'" aria-label="响应来源类型" @change="onResponseSourceChange(row,$event)"><el-option label="完整供应商报文" value="WHOLE_BODY"/><el-option label="指定字段 / 对象" value="FIELD"/><el-option label="固定值" value="CONSTANT"/></el-select><el-input v-if="row.transformType==='CONSTANT'" v-model="row.transformConfig.value" placeholder="填写固定值" aria-label="响应固定值"/><el-input v-else-if="row.transformType!=='WHOLE_BODY'" v-model="row.sourcePath" :placeholder="mappingDirection==='RESPONSE' ? '$.support.url' : '来源字段路径'" aria-label="来源字段路径" /></div>
                      <div class="transform-cell"><label class="mapping-rule-label">处理方式</label><span v-if="row.transformType==='WHOLE_BODY' || row.transformType==='CONSTANT'" class="whole-body-method">{{ row.transformType==='CONSTANT' ? '固定赋值' : '原样复制' }}</span><el-select v-else v-model="row.transformType" aria-label="转换规则" :teleported="false" :persistent="true" placement="bottom-start" popper-class="mapping-rule-dropdown" @visible-change="onMappingRuleVisibleChange(row, $event)" @change="onTransformTypeChange(row)"><el-option-group label="基础转换"><el-option label="直接映射" value="DIRECT"/><el-option label="枚举转换" value="ENUM"/><el-option label="默认值" value="DEFAULT"/><el-option label="日期格式化" value="DATE"/></el-option-group><el-option-group label="安全处理"><el-option label="加密处理" value="ENCRYPT"/><el-option label="签名计算" value="SIGN"/></el-option-group></el-select><span class="connector-arrow" aria-hidden="true">→</span></div>
                      <div class="field-cell"><label>{{ mappingDirection === 'REQUEST' ? '上游请求字段' : '平台响应目标路径' }}</label><el-input v-model="row.targetPath" :placeholder="mappingDirection==='RESPONSE' ? '$.data、$.code 或 $.message' : '目标字段路径'" aria-label="目标字段路径" /><div v-if="row.transformType === 'ENUM'" class="enum-summary" :class="{ready:Object.keys(row.transformConfig ?? {}).length}"><div><span>枚举规则</span><strong>{{ enumSummary(row) }}</strong></div><el-button size="small" plain @click="openEnumDrawer(row)">{{ Object.keys(row.transformConfig ?? {}).length ? '修改配置' : '立即配置' }}</el-button></div><div v-else-if="row.transformType === 'ENCRYPT' || row.transformType === 'SIGN'" class="algorithm-summary" :class="{ready:algorithmConfigured(row)}"><div><span>{{ row.transformType === 'ENCRYPT' ? '加密算法' : '签名算法' }}</span><strong>{{ algorithmName(row) }}</strong></div><el-button size="small" plain @click="openAlgorithmDrawer(row)">{{ algorithmConfigured(row) ? '修改配置' : '立即配置' }}</el-button></div><el-input v-else-if="!['WHOLE_BODY','CONSTANT'].includes(row.transformType)" class="enum-input" v-model="row.defaultValue" placeholder="默认值（可选）" /></div>
                      <div class="mapping-actions"><el-switch v-model="row.required" inline-prompt active-text="必" inactive-text="选" aria-label="必填字段" /><el-button :icon="Delete" text type="danger" aria-label="删除映射" @click="deleteMapping(row)" /></div>
                    </div>
                  </div>
                  <div v-if="mappingDirection==='RESPONSE'" class="response-preview-panel"><div class="section-toolbar"><strong>平台响应结构预览</strong><HelpTip title="结构预览" content="根据响应样例和当前规则预览，支持直接取值、固定值、枚举和返回码判定。日期及安全算法的实际结果请执行链路测试查看。"/></div><CopyableBlock :value="responseStructurePreview"><pre>{{ responseStructurePreview }}</pre></CopyableBlock></div>
                  <div v-if="validation" class="validation-result" :class="validation.valid ? 'valid' : 'invalid'"><el-icon><CircleCheck /></el-icon><div><strong>{{ validation.valid ? '配置校验通过' : '配置校验失败' }}</strong><span v-for="item in [...validation.errors, ...validation.warnings]" :key="item">{{ item }}</span></div></div>
                </el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="codes" label="结果码映射">
                  <div class="section-toolbar"><div><strong>统一返回码</strong><span>选择平台管理中已登记的标准返回码</span></div><el-button :icon="Setting" @click="openPlatformManagement('codes')">前往平台管理</el-button></div>
                  <div class="code-mode-row"><span>成功判定</span><el-select v-model="detail.responsePolicy.codeMode" aria-label="成功判定方式"><el-option label="按供应商业务码" value="VENDOR_CODE"/><el-option label="HTTP 成功即成功" value="HTTP_SUCCESS"/></el-select></div>
                  <div v-if="detail.responsePolicy.codeMode==='HTTP_SUCCESS'" class="code-mode-row"><span>平台成功码</span><el-select v-model="detail.responsePolicy.successCode" filterable aria-label="平台成功码"><el-option v-for="item in platformResponseCodes.filter(code => code.enabled && code.resultStatus==='HIT')" :key="item.code" :label="`${item.code} · ${item.name}`" :value="item.code"/></el-select></div>
                  <template v-else>
                    <div class="section-toolbar"><div><strong>上游结果码 → 平台返回码</strong><HelpTip title="结果码映射" content="供应商码由响应字段提取，平台返回码从统一字典选择。结果状态由所选平台码确定。"/></div><div><el-button :icon="Plus" @click="addResponseCode">新增规则</el-button></div></div>
                    <section class="vendor-code-path-panel"><div><strong>供应商返回码字段路径</strong><HelpTip title="返回码取值路径" content="从供应商原始响应按路径提取返回码，例如 result.code、status.code 或 data.retCode。请以该接口的实际响应结构为准。"/></div><el-input v-model="detail.vendorCodePath" placeholder="例如：result.code" clearable /></section>
                    <el-table :data="detail.responseCodes" border><el-table-column label="上游原始码值" min-width="130"><template #default="{ row }"><el-input v-model="row.vendorCode" placeholder="例如 0000" /></template></el-table-column><el-table-column label="平台返回码" min-width="190"><template #default="{ row }"><el-select v-model="row.standardCode" filterable @change="selectPlatformCode(row,$event)"><el-option v-for="item in platformResponseCodes.filter(code => code.enabled)" :key="item.code" :label="`${item.code} · ${item.name}`" :value="item.code"/></el-select></template></el-table-column><el-table-column label="结果状态" width="100"><template #default="{ row }">{{ row.resultStatus==='HIT' ? '命中' : row.resultStatus==='MISS' ? '未命中' : '异常' }}</template></el-table-column><el-table-column label="允许重试" width="90" align="center"><template #default="{ row }"><el-switch v-model="row.retryable" /></template></el-table-column><el-table-column label="参与计量" width="90" align="center"><template #default="{ row }"><el-switch v-model="row.billable" /></template></el-table-column><el-table-column label="操作" width="70" align="center"><template #default="{ $index }"><el-button :icon="Delete" text type="danger" aria-label="删除返回码规则" @click="detail.responseCodes.splice($index,1)"/></template></el-table-column></el-table>
                  </template>
                </el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="security" label="安全处理">
                  <div class="security-config-panel">
                    <div class="section-toolbar"><div><strong>接口级安全策略</strong><HelpTip title="接口安全策略" content="执行顺序为字段映射、加密、签名、上游调用。密钥仅保存配置引用，不得填入明文。草稿测试使用草稿策略，正式调用使用已发布策略。"/></div></div>
                    <div class="security-status" :class="{enabled: securityPolicy.enabled}"><Lock/><div><strong>{{ securityPolicy.enabled ? '已启用安全处理' : '未启用安全处理' }}</strong></div></div>
                    <el-form label-position="top" class="security-form" :class="{'policy-disabled':!securityPolicy.enabled}"><div class="form-grid"><el-form-item label="启用安全策略"><el-switch v-model="securityPolicy.enabled" active-text="启用" inactive-text="关闭"/></el-form-item><el-form-item label="请求字段"><el-input v-model="securityPolicy.requestFields" placeholder="例如：credit_code,customer_id；支持点号路径"/></el-form-item><el-form-item label="加密方式"><el-select v-model="securityPolicy.encryptMode" filterable><el-option label="不加密" value="NONE"/><el-option v-for="item in securityAlgorithms.filter(value=>value.kind==='ENCRYPT')" :key="item.code" :label="`${item.name} · ${item.category}`" :value="item.code"/></el-select></el-form-item><el-form-item label="加密密钥引用"><el-input v-model="securityPolicy.encryptionKeyReference" placeholder="config://edsp.security.keys.vendor-encryption"/><HelpTip title="密钥引用" content="只填写 config://、env:// 或 sys:// 引用，禁止保存密钥明文。"/></el-form-item><el-form-item label="签名方式"><el-select v-model="securityPolicy.signatureMode" filterable><el-option label="不签名" value="NONE"/><el-option v-for="item in securityAlgorithms.filter(value=>value.kind==='SIGN')" :key="item.code" :label="`${item.name} · ${item.category}`" :value="item.code"/></el-select></el-form-item><el-form-item label="签名密钥引用"><el-input v-model="securityPolicy.signingKeyReference" placeholder="config://edsp.security.keys.vendor-signing"/></el-form-item><el-form-item label="签名输出字段"><el-input v-model="securityPolicy.signatureField" placeholder="signature"/></el-form-item><el-form-item label="策略说明"><el-input v-model="securityPolicy.description" placeholder="记录策略用途和供应商要求"/></el-form-item></div></el-form>

                  </div>
                </el-tab-pane>
                <el-tab-pane v-if="serviceDevelopmentStage==='ORCHESTRATION'" name="test" label="链路测试">
                  <div class="trace-workbench-head"><div><strong>链路测试</strong><HelpTip title="链路测试" content="测试是独立诊断工具，不作为发布前置条件。选择编排测试检查业务处理，选择全链路测试检查 ESB 和 Gateway；执行后点击节点查看实际输入、输出及耗时。"/><small>{{ detail.summary.interfaceCode }}</small></div><div class="trace-run-actions"><el-button v-if="testRunning" type="danger" plain @click="cancelTest">取消测试</el-button><el-button type="primary" :icon="Promotion" :loading="testRunning" :disabled="testRunning" @click="runCurrentPrimaryAction">{{ testRunning ? `请求中 ${testElapsedSeconds}s` : testActionLabel }}</el-button></div></div>
                  <div class="test-mode-selector orchestration-modes" role="radiogroup" aria-label="标准服务验证模式">
                    <button v-for="mode in orchestrationTestModes" :key="mode.value" type="button" role="radio" :aria-checked="testMode===mode.value" :class="{active:testMode===mode.value}" @click="selectTestMode(mode.value)">
                      <span>{{ mode.index }}</span><div><strong>{{ mode.label }}</strong><b>{{ mode.path }}</b></div><i></i>
                    </button>
                  </div>
                  <div class="test-context-bar"><span>{{ activeTestMode.label }}<HelpTip :title="activeTestMode.label" :content="activeTestMode.description"/></span><code>{{ detail.summary.servicePath }}</code></div>
                    <el-alert v-if="resourceContractChanged && testMode==='PLATFORM'" type="warning" :closable="false" show-icon title="来源资源接口定义已变更。如需采用新入参，请先回到请求转换，点击“同步资源接口定义”并核对映射。" />
                  <div class="test-grid">
                    <div class="code-pane"><div class="code-title"><span>{{ testMode === 'UPSTREAM' ? '上游请求报文' : testMode === 'E2E' ? '行内 XML 入队报文' : '行内标准请求' }}</span><div v-if="testMode==='E2E'"><el-button v-if="esbXmlOverride !== null" text :icon="Refresh" title="恢复自动生成报文" aria-label="恢复自动生成报文" @click="resetEsbXml"/><small>ESB Adapter 消费并解析</small></div><small v-else>{{ testMode === 'UPSTREAM' ? '资源提供商处理应用发送' : '用于验证请求转换' }}</small></div><CopyableBlock :value="testMode === 'UPSTREAM' ? upstreamRequestText : testMode === 'PLATFORM' ? requestText : esbXmlText"><el-input v-if="testMode==='UPSTREAM'" v-model="upstreamRequestText" type="textarea" :autosize="{ minRows: 10, maxRows: 16 }"/><el-input v-else-if="testMode==='PLATFORM'" v-model="requestText" type="textarea" :autosize="{ minRows: 10, maxRows: 16 }"/><el-input v-else v-model="esbXmlText" type="textarea" :autosize="{ minRows: 10, maxRows: 16 }" aria-label="行内 XML 入队报文"/></CopyableBlock></div>
                    <div v-if="testMode==='PLATFORM'" class="code-pane readonly"><div class="code-title"><span>{{ testResult ? '上游实际响应' : '上游响应结构参考' }}</span><HelpTip title="编排测试范围" content="真实访问上游接口，验证报文转换和安全处理，不经过 ESB 和 Gateway。"/></div><CopyableBlock :value="testResult ? formatJson(testResult.upstreamResponse) : responseText"><pre>{{ testResult ? formatJson(testResult.upstreamResponse) : responseText }}</pre></CopyableBlock></div>
                    <div v-else-if="testMode==='E2E'" class="code-pane readonly"><div class="code-title"><span>{{ esbXmlResponsePreview ? '写回 ESB 的 XML 响应' : '解析后的标准请求' }}</span><small>{{ esbXmlResponsePreview ? 'ESB Adapter 已完成响应封装' : 'ESB Adapter 输出给 Gateway' }}</small></div><CopyableBlock :value="esbXmlResponsePreview || formatJson(standardRequestPreview)"><pre>{{ esbXmlResponsePreview || formatJson(standardRequestPreview) }}</pre></CopyableBlock></div>
                    <div v-else class="connection-target"><span>上游调用目标</span><strong>{{ detail.summary.upstreamUrl }}</strong><div><p><b>请求方式</b>{{ detail.summary.httpMethod }}</p><p><b>请求超时</b>{{ detail.timeoutMs }} ms</p><p><b>处理应用</b>{{ selectedSource.applicationName }}</p></div></div>
                  </div>
                  <div v-if="testRunning || testResult || testError" class="trace-console" :class="{running:testRunning,failed:!!testError}">
                    <div class="trace-header"><div><span class="trace-live"><i></i>{{ testRunning ? '执行中' : testError ? '执行失败' : '执行完成' }}</span><div><strong>外部数据调用链路</strong><small>{{ detail.summary.interfaceCode }} · {{ detail.summary.upstreamUrl }}</small></div></div><div class="trace-summary"><span>{{ testRunning ? '数据传输中' : testError ? '链路异常' : '全节点正常' }}</span><b>{{ testResult ? `${traceTotalDuration()} ms` : '-- ms' }}</b></div></div>
                    <div class="trace-track">
                      <template v-for="(node,index) in activeTraceDefinitions" :key="node.key">
                        <button class="trace-node" :class="[chainNodeState(index),{selected:selectedTraceKey===node.key}]" type="button" :aria-label="`查看${node.label}节点报文`" @click="selectTraceNode(node.key)">
                          <span><component :is="node.icon"/></span><strong>{{ node.label }}</strong><small>{{ node.short }}</small><b>{{ traceNodeDuration(node.key) ?? chainNodeStatus(index) }}</b>
                        </button>
                        <i v-if="index < activeTraceDefinitions.length - 1" class="data-channel" :class="{active:channelActive(index),done:testSucceeded||chainPhase>index}"><span></span><b></b></i>
                      </template>
                    </div>
                    <div v-if="testError" class="trace-error" role="alert"><Warning/><div><strong>{{ selectedTraceNode && ['ERROR','FAILED','FAILURE'].includes(selectedTraceNode.status) ? `异常节点：${selectedTraceNode.name}` : '链路执行失败' }}</strong><span>{{ testError }}</span></div></div>
                    <div v-if="selectedTraceNode" class="trace-inspector">
                      <header><div><span>{{ selectedTraceNode.category }}</span><strong>{{ selectedTraceNode.name }}</strong><small>{{ selectedTraceNode.detail }}</small></div><div><b>{{ traceStatusLabel(selectedTraceNode.status) }}</b><strong>{{ selectedTraceNode.durationMs }} ms</strong></div></header>
                      <div class="trace-inspector-grid"><section><div><span>节点输入</span><small>进入该节点的实际数据</small></div><CopyableBlock :value="formatJson(selectedTraceNode.input)"><pre>{{ formatJson(selectedTraceNode.input) }}</pre></CopyableBlock></section><section><div><span>节点输出</span><small>该节点处理后的实际数据</small></div><CopyableBlock :value="formatJson(selectedTraceNode.output)"><pre>{{ formatJson(selectedTraceNode.output) }}</pre></CopyableBlock></section></div>
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
                      <label>发布说明</label><CopyableBlock :value="releaseNote"><el-input v-model="releaseNote" type="textarea" :rows="4" placeholder="例如：新增企业查询接口，完成请求 / 响应映射与配置校验" :disabled="detail?.summary.status==='PENDING_APPROVAL'"/></CopyableBlock>
                      <div class="release-note">保存草稿 → 配置校验 → 发布 → 调用系统授权。链路测试可按需执行，不构成发布条件。已发布的服务需先下线，才能归档；版本、授权和调用日志仍会保留。</div>
                      <div class="release-actions"><el-button :icon="DocumentChecked" :disabled="guideBusy" @click="validateConfig">发布前校验</el-button></div>
                      <div v-if="validation" class="validation-result" :class="validation?.valid ? 'valid' : 'invalid'"><el-icon><component :is="validation?.valid ? CircleCheck : Warning"/></el-icon><div><strong>{{ validation?.valid ? '已保存草稿校验通过' : '配置校验未通过，请修正后重试' }}</strong><span v-for="item in [...(validation?.errors ?? []),...(validation?.warnings ?? [])]" :key="item">{{ item }}</span></div></div>
                      <div v-if="detail?.summary.status==='PENDING_APPROVAL'" class="api-release-followup"><strong>已提交，等待审批 · 尚未上线</strong><p>当前草稿 V{{ detail?.summary.currentVersion }} 已进入审批队列{{ publishResult ? '，审批单 ' + publishResult?.id : '' }}。请在审批中心查看处理结果；通过后再为调用系统授权。</p><el-button type="primary" @click="navigate('approvals')">前往审批中心</el-button><el-button :disabled="loading" @click="loadAll">刷新审批状态</el-button></div>
                      <div v-else-if="!hasUnpublishedChanges" class="api-release-followup"><strong>当前版本已发布</strong><p>下一步为机构 / 调用系统配置 API 授权和配额。已有授权无需重复创建；使用正式链路发起请求后，到调用日志核对执行结果。</p><el-button type="primary" @click="navigate('authorization')">前往服务授权</el-button><el-button @click="navigate('logs')">查看调用日志</el-button></div>
                      <div v-else class="api-release-followup"><strong>草稿准备提交</strong><p>发布操作只检查接口配置是否合法；链路测试是独立工具，不构成上线条件。</p></div>
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
                <div><span class="resource-action-dot" :class="resourceEditorState.key"></span><div class="api-save-state"><strong>{{ resourceEditorState.label }}</strong><small v-if="activeMenu!=='resource'">{{ resourceEditorState.hint }}</small></div></div>
                <div class="api-footer-actions">
                  <template v-if="serviceDevelopmentStage==='UPSTREAM'">
                    <el-button :loading="connectionSaving" :disabled="testRunning || detailLoading" @click="saveConnectionConfig">保存接入配置</el-button>
                    <el-button :loading="testRunning" :disabled="connectionSaving || !previewUpstreamUrl" @click="validateUpstreamCapability">测试接口</el-button>
                    <el-tooltip :disabled="!upstreamSaveRequired" content="请先保存当前配置" placement="top"><span><el-button type="primary" :disabled="testRunning || connectionSaving || upstreamSaveRequired" @click="openPlatformServiceDialog">创建标准服务</el-button></span></el-tooltip>
                  </template>
                  <template v-else>
                    <el-button :type="activeTab==='test' ? 'primary' : 'default'" :loading="guideBusy || testRunning || connectionSaving" :disabled="resourcePrimaryActionDisabled || deletingApi || detailLoading" @click="runCurrentPrimaryAction">{{ resourcePrimaryActionLabel }}</el-button>
                    <el-button v-if="activeTab==='test'" type="primary" :disabled="guideBusy || testRunning || connectionSaving" @click="selectServiceDevelopmentStage('RELEASE')">前往服务发布</el-button>
                  </template>
                </div>
              </footer>
            </div>
            <div v-else class="empty-editor" v-loading="detailLoading">
              <template v-if="detailLoading"><el-icon><Refresh /></el-icon><strong>正在加载接口配置</strong><span>加载完成前不会保留上一接口的映射数据</span></template>
              <template v-else><el-icon><Files /></el-icon><strong>{{ activeMenu==='resource' ? '该数据源暂无资源 API' : '暂无标准服务' }}</strong><span>{{ activeMenu==='resource' ? '先完善数据源信息，再定义资源提供商的原始请求与响应接口' : '从已保存的资源 API 创建标准服务' }}</span><div v-if="activeMenu==='resource' && selectedSource" class="empty-editor-actions"><el-button :icon="Setting" @click="openSourceSettingsEditor">编辑数据源</el-button><el-button type="primary" :icon="Plus" @click="openApiDialog">新增资源 API</el-button></div><el-button v-else-if="activeMenu!=='resource'" type="primary" :icon="Plus" @click="openPlatformServiceDialog">新增标准服务</el-button></template>
            </div>
          </section>
        </template>

        <template v-else-if="activeMenu === 'authorization'">
          <section class="authorization-overview"><div class="plain-panel"><div class="panel-heading"><div><strong>调用系统</strong><span>{{ clients.length }} 个系统 · {{ validAuthorizations.length }} 条有效授权</span></div><el-button :icon="Plus" @click="openClientRegistration()">登记系统</el-button></div><template v-if="clients.length"><el-table :data="clients"><el-table-column prop="clientName" label="系统名称" min-width="160"/><el-table-column prop="clientCode" label="系统编码" min-width="130"/><el-table-column prop="institutionName" label="所属机构" min-width="160"/><el-table-column prop="ownerName" label="负责人" width="90"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '有效' : '停用' }}</el-tag></template></el-table-column></el-table></template><div v-else class="authorization-empty"><div class="authorization-empty-icon"><UserIcon /></div><strong>暂无调用系统</strong><span>先登记行内系统，才能为 API 配置访问授权。</span><el-button type="primary" plain :icon="Plus" @click="openClientRegistration()">登记第一个调用系统</el-button></div></div></section>
          <section class="plain-panel table-panel authorization-list-panel"><div class="panel-heading"><div><strong>API 授权清单</strong><HelpTip title="API 访问授权" content="按调用系统配置可访问的 API、日配额和有效期。正式调用同时要求服务已上线且授权有效；内部链路测试不使用业务授权。"/></div></div><div class="list-filter-bar"><el-input v-model="authorizationSearch" :prefix-icon="Search" clearable placeholder="搜索系统、API 名称或编码" aria-label="搜索授权"/><el-select v-model="authorizationClient" filterable aria-label="按调用系统筛选"><el-option label="全部调用系统" :value="0"/><el-option v-for="client in clients" :key="client.id" :label="client.clientName" :value="client.id"/></el-select><span>{{ filteredAuthorizations.length }} 条授权</span></div><el-table :data="pagedAuthorizations" border empty-text="暂无匹配的授权记录"><el-table-column prop="clientName" label="调用系统" min-width="160"/><el-table-column prop="interfaceName" label="授权 API" min-width="170"/><el-table-column prop="interfaceCode" label="接口编码" min-width="150"/><el-table-column prop="dailyQuota" label="日配额" width="110"/><el-table-column label="有效期" min-width="190"><template #default="{ row }">{{ row.validFrom }} 至 {{ row.validTo }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="authorizationStatus(row).type">{{ authorizationStatus(row).text }}</el-tag></template></el-table-column><el-table-column label="操作" width="82" align="center"><template #default="{ row }"><el-button text type="primary" @click="editAuthorization(row)">编辑</el-button></template></el-table-column></el-table><el-pagination v-if="filteredAuthorizations.length>20" class="list-pagination" v-model:current-page="authorizationPage" :page-size="20" :total="filteredAuthorizations.length" layout="total, prev, pager, next"/></section>
        </template>

        <template v-else-if="activeMenu === 'billing'">
          <section class="billing-metrics"><div><span>本期调用量</span><strong>{{ billingSummary.totalCalls }}</strong><small>累计调用量</small></div><div><span>可计费调用</span><strong>{{ billingSummary.billableCalls }}</strong><small>命中且符合规则</small></div><div><span>命中 / 未查得</span><strong>{{ billingSummary.hitCalls }} / {{ billingSummary.missCalls }}</strong><small>按统一结果状态统计</small></div><div class="amount"><span>本期预估费用</span><strong>{{ money(billingSummary.totalAmount) }}</strong><small>币种：人民币</small></div></section>
          <section class="plain-panel table-panel"><div class="panel-heading"><div><strong>接口计费规则</strong></div></div><el-table :data="billingRules" border><el-table-column prop="interfaceName" label="API" min-width="170"/><el-table-column prop="interfaceCode" label="接口编码" min-width="160"/><el-table-column label="计费方式" width="130"><template #default>按成功次数</template></el-table-column><el-table-column label="单价" width="110"><template #default="{ row }">¥ {{ Number(row.unitPrice).toFixed(4) }}</template></el-table-column><el-table-column label="HIT / MISS" width="140"><template #default="{ row }"><el-tag :type="row.hitBillable?'success':'info'">{{ row.hitBillable?'计费':'免费' }}</el-tag> / <el-tag :type="row.missBillable?'success':'info'">{{ row.missBillable?'计费':'免费' }}</el-tag></template></el-table-column><el-table-column prop="effectiveDate" label="生效日期" width="120"/><el-table-column width="90"><template #default="{ row }"><el-button text type="primary" @click="openBillingDialog(row)">编辑</el-button></template></el-table-column></el-table></section>
        </template>

        <template v-else-if="activeMenu === 'logs'">
          <section class="log-metrics"><div><span>当前结果集</span><strong>{{ callLogs.length }}</strong><small>最多展示最近 200 条</small></div><div><span>成功调用</span><strong class="success-text">{{ callLogs.length - failedCallCount }}</strong><small>链路完整结束</small></div><div><span>失败调用</span><strong class="danger-text">{{ failedCallCount }}</strong><small>需排查失败节点</small></div><div><span>平均耗时</span><strong>{{ callLogs.length ? Math.round(callLogs.reduce((sum,item)=>sum+item.durationMs,0)/callLogs.length) : 0 }} ms</strong><small>当前查询范围</small></div></section>
          <section class="plain-panel log-query"><div class="log-query-head"><div><strong>调用检索</strong><HelpTip title="调用检索" content="使用全局流水号（Trace ID）定位完整调用。可结合接口和成功、失败状态筛选；点击查看链路，按节点检查实际报文和耗时。"/></div></div><div class="log-query-grid"><el-input v-model="callLogFilters.keyword" :prefix-icon="Search" clearable placeholder="全局流水号 / 接口名称 / 调用系统" @keyup.enter="searchCallLogs"/><el-select v-model="callLogFilters.status"><el-option label="全部状态" value="ALL"/><el-option label="成功" value="SUCCESS"/><el-option label="失败" value="FAILED"/></el-select><el-select v-model="callLogFilters.interfaceCode" clearable placeholder="全部接口"><el-option v-for="item in allInterfaces" :key="item.id" :label="`${item.interfaceName} · ${item.interfaceCode}`" :value="item.interfaceCode"/></el-select><el-button type="primary" :icon="Search" @click="searchCallLogs">查询</el-button><el-button @click="resetCallLogFilters">重置</el-button></div></section>
          <section class="plain-panel table-panel call-log-table"><div class="panel-heading"><div><strong>API 调用日志</strong></div></div><el-table :data="callLogs" border><el-table-column label="调用时间" prop="calledAt" width="175"/><el-table-column label="全局流水号" min-width="245"><template #default="{row}"><code>{{ row.traceId }}</code></template></el-table-column><el-table-column label="接口" min-width="220"><template #default="{row}"><div class="call-main"><strong>{{ row.interfaceName }}</strong><span>{{ row.interfaceCode }}</span></div></template></el-table-column><el-table-column prop="clientName" label="调用系统" width="150"/><el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="row.requestStatus==='SUCCESS'&&row.resultStatus!=='ERROR'?'success':'danger'">{{ row.requestStatus==='SUCCESS'&&row.resultStatus!=='ERROR'?'成功':'失败' }}</el-tag></template></el-table-column><el-table-column label="标准返回码" prop="standardCode" width="165"/><el-table-column label="总耗时" width="100"><template #default="{row}"><strong :class="row.durationMs>1000?'warning-text':''">{{ row.durationMs }} ms</strong></template></el-table-column><el-table-column label="操作" width="110" fixed="right"><template #default="{row}"><el-button text type="primary" @click="openCallTrace(row)">查看链路</el-button></template></el-table-column></el-table></section>
        </template>

        <template v-else-if="activeMenu === 'monitor'">
          <section class="monitor-command"><div class="monitor-health" :class="{warning:monitorSummary.activeAlerts>0}"><span><CircleCheck v-if="!monitorSummary.activeAlerts"/><Warning v-else/></span><div><strong>{{ monitorSummary.activeAlerts ? '存在调用质量告警' : '当前暂无活动告警' }}</strong></div></div><div class="monitor-command-facts"><span>采集点<b>{{ monitorPoints.length }}</b></span><span>链路节点<b>3</b></span><span>活动告警<b :class="{danger:monitorSummary.activeAlerts}">{{ monitorSummary.activeAlerts }}</b></span></div><div class="monitor-command-actions"><button @click="openFailedLogs"><Warning/><span><strong>排查失败调用</strong><small>{{ monitorSummary.errorCalls }} 条待分析</small></span><ArrowRight/></button><button @click="navigate('logs')"><Search/><span><strong>检索 Trace ID</strong></span><ArrowRight/></button></div></section>

          <section class="monitor-kpis"><div><span>近24小时调用</span><strong>{{ monitorSummary.totalCalls }}</strong><small>全链路请求</small><DataAnalysis/></div><div><span>调用成功率</span><strong :class="monitorSummary.successRate<99?'danger-text':'success-text'">{{ monitorSummary.successRate }}%</strong><small>{{ monitorSummary.errorCalls }} 次异常</small><CircleCheck/></div><div><span>端到端平均耗时</span><strong>{{ monitorSummary.avgDurationMs }}<em> ms</em></strong><small>平台 + 供应商</small><Connection/></div><div><span>P95 响应耗时</span><strong :class="monitorSummary.p95DurationMs>1000?'warning-text':''">{{ monitorSummary.p95DurationMs }}<em> ms</em></strong><small>SLA &lt; 1000 ms</small><DataAnalysis/></div><div><span>上游平均耗时</span><strong>{{ monitorSummary.avgUpstreamDurationMs }}<em> ms</em></strong><small>占比 {{ Math.round(monitorSummary.avgUpstreamDurationMs*100/Math.max(1,monitorSummary.avgDurationMs)) }}%</small><OfficeBuilding/></div><div><span>活动告警</span><strong :class="monitorSummary.activeAlerts?'danger-text':'success-text'">{{ monitorSummary.activeAlerts }}</strong><small>实时阈值检测</small><Warning/></div></section>

          <section class="monitor-main-grid">
            <div class="monitor-panel monitor-latency-panel"><header><div><span>LATENCY ANALYSIS</span><strong>调用耗时趋势</strong><small>端到端与上游供应商耗时对比</small></div><div class="monitor-legend"><i></i>端到端<b></b>上游</div></header><div class="monitor-latency-chart" :class="{inspecting:activeMonitorPointIndex!==null}"><div class="monitor-chart-grid"><i></i><i></i><i></i><i></i></div><button v-for="(point,index) in monitorTimeline" :key="point.timeLabel" type="button" class="monitor-latency-column" :class="{active:activeMonitorPointIndex===index}" :aria-label="`${point.timeLabel}，端到端 ${point.avgDurationMs} 毫秒，上游 ${point.avgUpstreamDurationMs} 毫秒`" @mouseenter="activeMonitorPointIndex=index" @mouseleave="activeMonitorPointIndex=null" @focus="activeMonitorPointIndex=index" @blur="activeMonitorPointIndex=null"><div><i :style="{height:`${Math.max(6,point.avgDurationMs/monitorMaxDuration*100)}%`}"></i><b :style="{height:`${Math.max(4,point.avgUpstreamDurationMs/monitorMaxDuration*100)}%`}"></b></div><span>{{ point.timeLabel }}</span></button><div v-if="activeMonitorPoint" class="monitor-chart-tooltip" :style="{left:`${((activeMonitorPointIndex??0)+.5)/Math.max(1,monitorTimeline.length)*100}%`,transform:activeMonitorPointIndex===0?'translateX(0)':activeMonitorPointIndex===monitorTimeline.length-1?'translateX(-100%)':'translateX(-50%)'}"><header><strong>{{ activeMonitorPoint.timeLabel }}</strong><span>{{ activeMonitorPoint.calls }} 次调用</span></header><div><span>端到端<b>{{ activeMonitorPoint.avgDurationMs }} ms</b></span><span>上游供应商<b>{{ activeMonitorPoint.avgUpstreamDurationMs }} ms</b></span><span>平台处理<b>{{ Math.max(0,activeMonitorPoint.avgDurationMs-activeMonitorPoint.avgUpstreamDurationMs) }} ms</b></span><span>异常调用<b class="error">{{ activeMonitorPoint.errorCalls }}</b></span></div></div><div v-if="!monitorTimeline.length" class="chart-empty">暂无近 24 小时调用数据</div></div><footer><span>平均耗时<b>{{ monitorSummary.avgDurationMs }} ms</b></span><span>P95 耗时<b>{{ monitorSummary.p95DurationMs }} ms</b></span><span>SLA 达标<b :class="monitorSummary.p95DurationMs<1000?'success-text':'danger-text'">{{ monitorSummary.p95DurationMs<1000?'是':'否' }}</b></span></footer></div>
            <div class="monitor-panel monitor-topology-panel"><header><div><span>CHAIN TOPOLOGY</span><strong>链路节点性能</strong><small>选择节点查看耗时贡献与运行状态</small></div><b>LIVE</b></header><div class="monitor-topology"><template v-for="(node,index) in monitorTopologyNodes" :key="node.key"><button type="button" :class="[{active:activeMonitorNodeKey===node.key},node.status.toLowerCase()]" @click="activeMonitorNodeKey=node.key"><span><component :is="node.icon"/></span><strong>{{ node.name }}</strong><small>{{ node.duration }} ms</small></button><i v-if="index<monitorTopologyNodes.length-1"><b></b></i></template></div><div v-if="activeMonitorNode" class="monitor-node-inspector"><div><span>{{ activeMonitorNode.code }}</span><strong>{{ activeMonitorNode.name }}</strong><small>{{ activeMonitorNode.description }}</small></div><div><span>平均耗时<strong>{{ activeMonitorNode.duration }} ms</strong></span><span>链路占比<strong>{{ activeMonitorNode.share }}%</strong></span><span>节点状态<strong :class="activeMonitorNode.status.toLowerCase()">{{ activeMonitorNode.status==='HEALTHY'?'正常':'关注' }}</strong></span></div></div></div>
          </section>

          <section class="monitor-alert-workspace"><header><div><span>ALERT CENTER</span><strong>运行告警与处置建议</strong></div><b>{{ monitorAlerts.length }} 项</b></header><div v-if="monitorAlerts.length" class="monitor-alert-body"><div class="monitor-alert-list"><button v-for="(alert,index) in monitorAlerts" :key="`${alert.title}-${index}`" :class="[alert.level.toLowerCase(),{active:selectedMonitorAlertIndex===index}]" @click="selectedMonitorAlertIndex=index"><span><Warning/></span><div><strong>{{ alert.title }}</strong><small>{{ alert.detail }}</small></div><time>{{ alert.timeLabel }}</time><ArrowRight/></button></div><div v-if="selectedMonitorAlert" class="monitor-alert-detail"><span :class="selectedMonitorAlert.level.toLowerCase()">{{ selectedMonitorAlert.level }}</span><h3>{{ selectedMonitorAlert.title }}</h3><p>{{ selectedMonitorAlert.detail }}</p><div><span>检测时间<b>{{ selectedMonitorAlert.timeLabel }}</b></span><span>影响范围<b>调用质量 / SLA</b></span></div><footer><el-button @click="refreshMonitor" :icon="Refresh">重新检测</el-button><el-button type="primary" @click="openFailedLogs" :icon="DataAnalysis">查看相关失败调用</el-button></footer></div></div><div v-else class="monitor-alert-empty"><CircleCheck/><strong>当前无活动告警</strong><span>所有节点均运行在配置阈值范围内</span></div></section>

          <section class="monitor-panel monitor-call-panel"><header><div><span>TRACE SAMPLES</span><strong>全链路调用明细</strong><small>定位异常、慢调用及各节点耗时贡献</small></div><div class="monitor-record-filters"><button :class="{active:monitorRecordFilter==='ALL'}" @click="monitorRecordFilter='ALL'">全部 {{ usageRecords.length }}</button><button :class="{active:monitorRecordFilter==='ERROR'}" @click="monitorRecordFilter='ERROR'">异常 {{ monitorErrorRecordCount }}</button><button :class="{active:monitorRecordFilter==='SLOW'}" @click="monitorRecordFilter='SLOW'">慢调用 {{ monitorSlowRecordCount }}</button></div></header><el-table :data="filteredMonitorRecords" class="monitor-call-table"><el-table-column label="调用信息" min-width="250"><template #default="{row}"><div class="call-main"><strong>{{ row.interfaceName }}</strong><span>{{ formatDateTime(row.calledAt) }} · {{ row.clientName || '联调工作台' }}</span><code>{{ row.traceId }}</code></div></template></el-table-column><el-table-column label="结果" width="130"><template #default="{row}"><el-tag :type="row.resultStatus==='HIT'?'success':row.resultStatus==='MISS'?'info':'danger'">{{ resultStatusLabel(row.resultStatus) }}</el-tag><small class="code-caption">{{ row.standardCode }}</small></template></el-table-column><el-table-column label="节点耗时贡献" min-width="320"><template #default="{row}"><div class="duration-breakdown"><div><span>平台处理</span><i><b :style="{width:`${Math.min(100,row.gatewayDurationMs/Math.max(1,row.durationMs)*100)}%`}"></b></i><strong>{{ row.gatewayDurationMs }} ms</strong></div><div><span>上游供应商</span><i><b class="vendor" :style="{width:`${Math.min(100,row.upstreamDurationMs/Math.max(1,row.durationMs)*100)}%`}"></b></i><strong>{{ row.upstreamDurationMs }} ms</strong></div></div></template></el-table-column><el-table-column label="总耗时" width="100"><template #default="{row}"><strong class="total-duration" :class="{slow:row.durationMs>=monitorSummary.p95DurationMs}">{{ row.durationMs }} ms</strong></template></el-table-column><el-table-column label="最终节点" min-width="150"><template #default="{row}"><code>{{ row.nodeName }}</code></template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button text type="primary" @click="openCallTrace(row)">查看链路</el-button></template></el-table-column></el-table></section>
        </template>
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="documentDialog" title="上传第三方接口文档" width="min(560px, 94vw)" destroy-on-close>
    <el-form label-position="top"><el-form-item label="资源提供商" required><el-select v-model="documentForm.providerId" filterable placeholder="选择资源提供商" style="width:100%" @change="documentForm.dataSourceId=0;documentForm.resourceApiId=0"><el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="item.id"/></el-select></el-form-item><el-form-item label="数据源"><el-select v-model="documentForm.dataSourceId" clearable placeholder="可选" style="width:100%" @change="documentForm.resourceApiId=0"><el-option v-for="item in documentSources" :key="item.id" :label="item.sourceName" :value="item.id"/></el-select></el-form-item><el-form-item label="资源 API"><el-select v-model="documentForm.resourceApiId" clearable :disabled="!documentForm.dataSourceId" placeholder="可选" style="width:100%"><el-option v-for="item in documentApis" :key="item.id" :label="item.resourceApiName" :value="item.id"/></el-select></el-form-item><el-form-item label="接口文档" required><input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.txt" @change="documentForm.file=($event.target as HTMLInputElement).files?.[0] || null"/></el-form-item><el-form-item label="备注"><el-input v-model="documentForm.description" maxlength="500" show-word-limit/></el-form-item></el-form>
    <template #footer><el-button @click="documentDialog=false">取消</el-button><el-button type="primary" :loading="documentUploading" :disabled="!documentForm.providerId || !documentForm.file" @click="uploadResourceDocument">上传</el-button></template>
  </el-dialog>
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

  <el-drawer v-model="callTraceDrawer" size="78%" class="call-trace-drawer" destroy-on-close><template #header><div class="drawer-trace-title"><div><span>调用链路</span><strong>{{ callTrace?.summary.interfaceName || activeCallTraceRecord?.interfaceName || '调用链路详情' }}</strong><code>{{ callTrace?.summary.traceId || activeCallTraceRecord?.traceId || '-' }}</code></div><el-tag v-if="callTrace" :type="callTrace.summary.requestStatus==='SUCCESS'&&callTrace.summary.resultStatus!=='ERROR'?'success':'danger'" effect="dark">{{ callTrace.summary.requestStatus==='SUCCESS'&&callTrace.summary.resultStatus!=='ERROR'?'调用成功':'调用失败' }}</el-tag></div></template><div v-loading="callTraceLoading" class="persisted-trace"><template v-if="callTrace"><section class="trace-facts"><div><span>调用系统</span><strong>{{ callTrace.summary.clientName || '联调工作台' }}</strong></div><div><span>标准返回码</span><strong>{{ callTrace.summary.standardCode }}</strong></div><div><span>端到端耗时</span><strong>{{ callTrace.summary.durationMs }} ms</strong></div><div><span>调用时间</span><strong>{{ callTrace.summary.calledAt }}</strong></div></section><section class="persisted-trace-track"><template v-for="(node,index) in callTrace.nodes" :key="node.key"><button class="persisted-node" :class="[node.status.toLowerCase(),{active:selectedLogNodeKey===node.key}]" @click="selectedLogNodeKey=node.key"><span><CircleCheck v-if="node.status==='SUCCESS'"/><Warning v-else/></span><strong>{{ node.name }}</strong><small>{{ node.durationMs }} ms</small></button><div v-if="index<callTrace.nodes.length-1" class="trace-channel" :class="{failed:node.status!=='SUCCESS'}"><i></i></div></template></section><section v-if="logSelectedNode" class="persisted-node-detail"><header><div><span>{{ logSelectedNode.category }} / {{ logSelectedNode.key }}</span><strong>{{ logSelectedNode.name }}</strong><small>{{ logSelectedNode.detail }}</small></div><div><el-tag :type="logSelectedNode.status==='SUCCESS'?'success':'danger'">{{ traceStatusLabel(logSelectedNode.status) }}</el-tag><strong>{{ logSelectedNode.durationMs }} ms</strong></div></header><div><section><label>节点输入</label><CopyableBlock :value="formatJson(logSelectedNode.input)"><pre>{{ formatJson(logSelectedNode.input) }}</pre></CopyableBlock></section><section><label>节点输出</label><CopyableBlock :value="formatJson(logSelectedNode.output)"><pre>{{ formatJson(logSelectedNode.output) }}</pre></CopyableBlock></section></div></section></template><el-result v-else-if="callTraceError" icon="error" title="链路详情加载失败" :sub-title="callTraceError"><template #extra><el-button v-if="activeCallTraceRecord" type="primary" @click="openCallTrace(activeCallTraceRecord)">重新加载</el-button></template></el-result><el-empty v-else-if="!callTraceLoading" description="暂无可展示的链路详情"/></div></el-drawer>

  <el-dialog v-model="sourceDialog" title="接入数据源" width="680px" class="source-wizard" :close-on-click-modal="false" :close-on-press-escape="!sourceSaving" :show-close="!sourceSaving" destroy-on-close><el-steps :active="sourceStep" finish-status="success" simple><el-step title="基本信息"/><el-step title="连接配置"/></el-steps><el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" show-icon/><div class="wizard-body" v-if="sourceStep===0"><el-form label-position="top"><div class="form-grid"><el-form-item label="资源提供商" required><el-select v-model="sourceForm.providerId"><el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="item.id"/></el-select></el-form-item><el-form-item label="数据源名称" required><el-input v-model="sourceForm.sourceName" placeholder="例如：企业工商信息" maxlength="100"/></el-form-item><el-form-item label="数据源编码" required><el-input v-model="sourceForm.sourceCode" placeholder="例如：ENTERPRISE_INFO" maxlength="64"/></el-form-item><el-form-item label="报文协议"><el-select v-model="sourceForm.protocolType"><el-option label="HTTP JSON" value="HTTP_JSON"/><el-option label="WebService" value="SOAP"/><el-option label="数据库" value="LOCAL_SQL"/></el-select></el-form-item></div></el-form></div><div class="wizard-body" v-else><el-form label-position="top"><el-alert v-if="!providers.find(item=>item.id===sourceForm.providerId)?.runtimeUrl" title="当前资源提供商尚未配置应用路由，数据源可以保存，但联调和上线前必须完成提供商路由配置。" type="warning" :closable="false"/><el-form-item v-if="sourceForm.protocolType!=='LOCAL_SQL'" label="数据源基础地址"><el-input v-model="sourceForm.endpoint" placeholder="https://provider.example.com"/><HelpTip title="数据源基础地址" content="资源接口默认继承此供应商基础地址，并拼接接口相对路径；个别接口可单独配置完整地址。"/></el-form-item><el-form-item label="数据源默认超时"><el-input-number v-model="sourceForm.defaultTimeoutMs" :min="100" :max="120000" :step="500"/><span class="form-unit">毫秒（ms）</span></el-form-item></el-form></div><template #footer><el-button :disabled="sourceSaving" @click="sourceDialog=false">取消</el-button><el-button :disabled="sourceSaving" v-if="sourceStep>0" @click="sourceStep--">上一步</el-button><el-button v-if="sourceStep<1" type="primary" @click="advanceSourceWizard">下一步</el-button><el-button v-else type="primary" :loading="sourceSaving" @click="createSource">创建并配置接口</el-button></template></el-dialog>
  <el-dialog v-model="sourceSettingsDialog" title="编辑数据源" width="680px" :close-on-click-modal="false"><el-alert v-if="sourceSettingsError" :title="sourceSettingsError" type="error" :closable="false" show-icon/><el-form label-position="top" style="margin-top:18px"><div class="form-grid"><el-form-item label="资源提供商" required><el-select v-model="sourceSettingsForm.providerId" filterable><el-option v-for="item in providers" :key="item.id" :label="item.providerName" :value="item.id"/></el-select></el-form-item><el-form-item label="数据源名称" required><el-input v-model="sourceSettingsForm.sourceName" maxlength="100"/></el-form-item><el-form-item label="数据源编码"><el-input v-model="sourceSettingsForm.sourceCode" disabled/><HelpTip title="数据源编码" content="数据源唯一标识，创建后不可修改。配置应用路由统一在资源方管理中维护。"/></el-form-item><el-form-item label="协议类型" required><el-select v-model="sourceSettingsForm.protocolType"><el-option label="HTTP JSON" value="HTTP_JSON"/><el-option label="WebService" value="SOAP"/><el-option label="本地数据集" value="LOCAL_SQL"/></el-select></el-form-item></div><el-form-item v-if="sourceSettingsForm.protocolType!=='LOCAL_SQL'" label="数据源基础地址" required><el-input v-model="sourceSettingsForm.endpoint" placeholder="https://provider.example.com"/><HelpTip title="基础地址继承" content="选择继承数据源的资源接口使用此供应商地址。已发布服务继续使用发布快照，重新发布后才应用新配置。"/></el-form-item><el-form-item label="默认请求超时"><el-input-number v-model="sourceSettingsForm.defaultTimeoutMs" :min="100" :max="120000" :step="500" controls-position="right"/><span class="form-unit">毫秒（ms）</span></el-form-item><div class="dialog-context"><span>变更生效范围</span><HelpTip title="变更生效范围" content="目录归属和草稿配置立即生效；继承配置的接口建议重新测试。已发布服务保持原发布快照，重新发布后切换。"/></div></el-form><template #footer><el-button :disabled="sourceSettingsSaving" @click="sourceSettingsDialog=false">取消</el-button><el-button type="primary" :loading="sourceSettingsSaving" @click="saveSourceSettings">保存修改</el-button></template></el-dialog>
  <el-dialog v-model="sourceRuntimeDialog" v-loading="sourceRuntimeStatusLoading" title="资源提供商应用路由配置" width="680px" class="runtime-provision-dialog" :close-on-click-modal="!sourceRuntimeSaving"><div class="dialog-context"><span>{{ selectedProvider?.providerName }}</span><HelpTip title="配置应用路由" content="同一资源提供商下的数据源和接口共用独立处理应用。接入测试、编排测试及正式调用均经该应用访问资源提供商。"/></div><el-form label-position="top" style="margin-top:18px"><el-form-item required><template #label>处理应用标识<HelpTip title="处理应用标识" content="用于 Java 模块名、Spring 应用名和 Nacos Data ID，须与实际部署的应用一致。"/></template><el-input v-model="sourceRuntimeForm.applicationName" placeholder="jsnx-edsp-data-provider-example"/></el-form-item><div class="form-grid"><el-form-item required><template #label>应用访问地址<HelpTip title="应用访问地址" content="Gateway 和管理服务访问处理应用的基础地址，非供应商接口地址。单实例填写协议、主机和端口；采用 VIP 时填写 VIP 地址。"/></template><el-input v-model="sourceRuntimeForm.runtimeUrl" placeholder="http://127.0.0.1:8201"/></el-form-item><el-form-item required><template #label>应用监听端口<HelpTip title="应用监听端口" content="写入 Nacos 的 server.port，决定 Java 应用实际监听端口。直接访问时应与应用访问地址端口一致；有 VIP 转发时可以不同。"/></template><el-input-number v-model="sourceRuntimeForm.serverPort" :min="1" :max="65535" :step="1" controls-position="right"/></el-form-item></div></el-form><div class="provision-destination"><span>Java 工程生成目录</span><code>{{ provisionSettings?.outputRoot || '由 Admin 服务端配置决定' }}</code><HelpTip title="工程生成与部署" content="此操作保存路由、生成 Java 工程并同步 Nacos，不自动部署。生成代码仍需纳入 Git、打包并发布运行。"/></div><section v-if="sourceRuntimeResult" class="provision-result"><header><strong>{{ sourceRuntimeStatusLoaded ? '已保存的执行信息' : '本次执行结果' }}</strong><span>工程与配置中心状态</span></header><div class="provision-step"><i :class="sourceRuntimeResult.routeSave.status.toLowerCase()"></i><div><strong>路由持久化</strong><small>{{ sourceRuntimeResult.routeSave.message }}</small></div><b>{{ provisionStatusLabel(sourceRuntimeResult.routeSave.status) }}</b></div><div class="provision-step"><i :class="sourceRuntimeResult.generation.status.toLowerCase()"></i><div><strong>Java 工程</strong><small>{{ sourceRuntimeResult.generation.message }}</small><small v-if="sourceRuntimeResult.generation.packageName">{{ sourceRuntimeResult.generation.packageName }} · {{ sourceRuntimeResult.generation.generatedFiles.length }} 个生成文件</small><code v-if="sourceRuntimeResult.generation.outputDirectory">{{ sourceRuntimeResult.generation.outputDirectory }}</code></div><b>{{ provisionStatusLabel(sourceRuntimeResult.generation.status) }}</b></div><div class="provision-step"><i :class="sourceRuntimeResult.nacos.status.toLowerCase()"></i><div><strong>Nacos 配置</strong><small>{{ sourceRuntimeResult.nacos.dataId || '-' }} · Group {{ sourceRuntimeResult.nacos.group }} · Namespace {{ sourceRuntimeResult.nacos.namespace || 'public' }}</small><small>{{ sourceRuntimeResult.nacos.message }}</small></div><b>{{ provisionStatusLabel(sourceRuntimeResult.nacos.status) }}</b></div><div class="provision-step"><i :class="sourceRuntimeResult.routeRefresh.status.toLowerCase()"></i><div><strong>Gateway 路由刷新</strong><small>{{ sourceRuntimeResult.routeRefresh.message }}</small></div><b>{{ provisionStatusLabel(sourceRuntimeResult.routeRefresh.status) }}</b></div></section><template #footer><el-button v-if="sourceRuntimeResult?.generation.outputDirectory" @click="copyGeneratedPath">复制生成目录</el-button><el-button @click="sourceRuntimeDialog=false">{{ sourceRuntimeResult ? '关闭' : '取消' }}</el-button><el-button type="primary" :loading="sourceRuntimeSaving" @click="saveSourceRuntime">{{ hasProvisionedRuntime() ? '重新生成并同步' : '保存、生成并同步' }}</el-button></template></el-dialog>
  <el-dialog v-model="apiDialog" title="新增资源 API" width="660px">
    <el-form label-position="top">
      <div class="form-grid">
        <el-form-item label="所属数据源"><el-select v-model="apiForm.dataSourceId" @change="initializeApiDialog(sources.find(item => item.id === apiForm.dataSourceId))"><el-option v-for="item in sources" :key="item.id" :label="`${item.sourceName} · 默认 ${item.defaultTimeoutMs ?? 3000}ms`" :value="item.id"/></el-select></el-form-item>
        <el-form-item :label="apiForm.executionMode==='SQL' ? '查询方式' : '上游 HTTP 方法'"><el-tag v-if="apiForm.executionMode==='SQL'" effect="plain">数据库 SQL</el-tag><el-select v-else v-model="apiForm.httpMethod"><el-option label="POST" value="POST"/><el-option label="GET" value="GET"/></el-select></el-form-item>
        <el-form-item label="资源 API 名称" required><el-input v-model="apiForm.interfaceName"/></el-form-item>
        <el-form-item label="资源 API 编码" required><el-input v-model="apiForm.interfaceCode"/></el-form-item>
      </div>
      <template v-if="apiForm.executionMode==='SQL'">
        <el-segmented v-model="databaseQueryMode" :options="[{label:'选表查询',value:'TABLE'},{label:'编写 SQL',value:'SQL'}]"/>
        <template v-if="databaseQueryMode==='TABLE'">
          <el-form-item label="数据库表" required><el-select v-model="databaseSelectedTable" filterable :loading="databaseSchemaLoading" placeholder="选择需要查询的数据表" @change="loadDatabaseColumns"><el-option v-for="table in databaseTables" :key="table.name" :label="`${table.name} · ${table.type}`" :value="table.name"/></el-select><el-button text :icon="Refresh" :loading="databaseSchemaLoading" aria-label="刷新数据表" @click="loadDatabaseTables(apiForm.dataSourceId)"/></el-form-item>
          <el-form-item label="返回字段" required><el-select v-model="databaseSelectedFields" multiple collapse-tags collapse-tags-tooltip filterable placeholder="选择服务需要返回的字段" @change="updateDatabaseQuery"><el-option v-for="column in databaseColumns" :key="column.name" :label="`${column.name} · ${column.type}`" :value="column.name"/></el-select></el-form-item>
          <el-form-item label="查询条件字段"><el-select v-model="databaseFilterFields" multiple collapse-tags collapse-tags-tooltip filterable placeholder="可选；每个字段生成一个等值请求参数" @change="updateDatabaseQuery"><el-option v-for="column in databaseColumns" :key="column.name" :label="`${column.name} · ${column.type}`" :value="column.name"/></el-select></el-form-item>
          <el-form-item label="生成的只读查询"><CopyableBlock :value="apiForm.sqlTemplate"><el-input :model-value="apiForm.sqlTemplate" type="textarea" :rows="5" readonly resize="vertical"/></CopyableBlock></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="SQL 查询语句" required><CopyableBlock :value="apiForm.sqlTemplate"><el-input v-model="apiForm.sqlTemplate" type="textarea" :rows="9" resize="vertical" placeholder="SELECT id, name FROM user_info WHERE user_id = :userId"/></CopyableBlock></el-form-item>
          <el-form-item label="请求参数 JSON"><CopyableBlock :value="apiForm.sqlParameters"><el-input v-model="apiForm.sqlParameters" type="textarea" :rows="5" resize="vertical" placeholder="{\n  &quot;userId&quot;: &quot;STRING&quot;\n}"/></CopyableBlock></el-form-item>
        </template>
      </template>
      <section v-else class="api-policy-section">
        <header><strong>上游连接策略</strong></header><el-segmented v-model="apiForm.configScope" :options="[{label:'继承数据源',value:'INHERIT'},{label:'单接口覆盖',value:'OVERRIDE'}]"/>
        <div class="source-default-facts"><span>基础地址<b>{{ selectedApiSource?.endpointUrl || '未配置' }}</b></span><span>默认超时<b>{{ selectedApiSource?.defaultTimeoutMs ?? 3000 }} ms</b></span></div>
        <el-form-item v-if="apiForm.configScope==='INHERIT'" label="供应商接口相对路径" required><el-input v-model="apiForm.upstreamPath" placeholder="/vendor/api/query"/></el-form-item>
        <el-form-item v-else label="接口独立完整地址" required><el-input v-model="apiForm.upstreamUrl" placeholder="https://provider.example.com/vendor/api/query"/></el-form-item>
        <div class="timeout-policy"><div class="timeout-policy-choice"><label>超时策略</label><div class="timeout-choice-group" role="radiogroup" aria-label="超时策略"><button type="button" role="radio" :aria-checked="apiForm.timeoutMode==='INHERIT'" :class="{selected:apiForm.timeoutMode==='INHERIT'}" @click="apiForm.timeoutMode='INHERIT'"><span class="timeout-choice-radio"/><span><strong>继承数据源默认</strong><small>使用当前数据源的 {{ selectedApiSource?.defaultTimeoutMs ?? 3000 }} ms</small></span></button><button type="button" role="radio" :aria-checked="apiForm.timeoutMode==='OVERRIDE'" :class="{selected:apiForm.timeoutMode==='OVERRIDE'}" @click="apiForm.timeoutMode='OVERRIDE'"><span class="timeout-choice-radio"/><span><strong>当前接口覆盖</strong><small>仅修改此资源 API</small></span></button></div></div><div class="timeout-policy-value"><label>接口超时（毫秒）</label><el-input-number v-model="apiForm.timeoutMs" :disabled="apiForm.timeoutMode==='INHERIT'" :min="100" :max="120000" :step="500"/><small>{{ apiForm.timeoutMode==='INHERIT' ? '当前生效值：' + (selectedApiSource?.defaultTimeoutMs ?? 3000) + ' ms' : '设置后仅对此接口生效' }}</small></div></div>
      </section>
    </el-form>
    <template #footer><el-button @click="apiDialog=false">取消</el-button><el-button type="primary" :loading="apiCreating" @click="createInterface">创建资源草稿</el-button></template>
  </el-dialog>
  <el-dialog v-model="apiMetadataDialog" title="编辑资源 API" width="560px">
    <el-form label-position="top">
      <el-form-item label="资源 API 名称" required><el-input v-model="apiMetadataForm.resourceApiName" maxlength="128" show-word-limit/></el-form-item>
      <el-form-item label="资源 API 编码" required><el-input v-model="apiMetadataForm.resourceApiCode" maxlength="64"/><small class="form-hint">平台内唯一；允许字母、数字、下划线和半角连字符</small></el-form-item>
      <el-form-item label="上游 HTTP 方法" required><el-select v-model="apiMetadataForm.httpMethod" style="width:100%"><el-option v-for="method in ['GET','POST','PUT','DELETE','PATCH']" :key="method" :label="method" :value="method"/></el-select></el-form-item>
    </el-form>
    <template #footer><el-button @click="apiMetadataDialog=false">取消</el-button><el-button type="primary" :loading="apiMetadataSaving" @click="saveResourceApiMetadata">保存修改</el-button></template>
  </el-dialog>
  <el-drawer v-model="platformContractFieldsDrawer" :title="selectedPlatformContract ? `${selectedPlatformContract.contractName} · 字段定义` : '字段定义'" size="min(820px,94vw)" append-to-body>
    <el-table v-if="selectedPlatformContract" :data="platformContractFields" size="small" class="platform-contract-table" empty-text="暂无字段"><el-table-column type="index" label="#" width="54"/><el-table-column prop="key" label="字段编码" min-width="150"><template #default="{row}"><code>{{ row.key }}</code></template></el-table-column><el-table-column prop="label" label="显示名称" min-width="120"/><el-table-column prop="type" label="类型" width="100"/><el-table-column label="必填" width="76"><template #default="{row}"><el-tag size="small" :type="row.required?'success':'info'" effect="plain">{{ row.required ? '是' : '否' }}</el-tag></template></el-table-column><el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip/></el-table>
  </el-drawer>
  <el-dialog v-model="contractEditorDialog" :title="contractEditorOriginalId ? '编辑标准响应' : '新增标准响应'" width="min(920px,94vw)" append-to-body>
    <el-form label-position="top">
      <div class="form-grid"><el-form-item label="标准响应编码" required><el-input v-model="contractEditorForm.contractCode" maxlength="64" placeholder="例如 EDSP_STANDARD"/></el-form-item><el-form-item label="标准响应名称" required><el-input v-model="contractEditorForm.contractName" maxlength="128" placeholder="例如 平台标准响应 V1"/></el-form-item><el-form-item label="版本号" required><el-input-number v-model="contractEditorForm.version" :min="1" :max="999"/></el-form-item><el-form-item label="默认使用"><el-switch v-model="contractEditorForm.defaultContract" active-text="是" inactive-text="否"/></el-form-item></div>
      <el-form-item label="说明"><el-input v-model="contractEditorForm.description" maxlength="500" placeholder="说明该标准响应的适用范围"/></el-form-item>
      <div class="contract-fields-editor"><header><div><strong>字段定义</strong><small>字段编码用于平台响应结构；接口业务字段请在标准服务开发中配置。</small></div><el-button type="primary" plain :icon="Plus" @click="addContractField">新增字段</el-button></header><div v-for="(field,index) in contractEditorForm.fields" :key="index" class="contract-field-editor-row"><span class="contract-field-index">{{ String(index + 1).padStart(2, '0') }}</span><el-input v-model="field.key" placeholder="字段编码"/><el-input v-model="field.label" placeholder="显示名称"/><el-select v-model="field.type" placeholder="类型"><el-option label="字符串" value="STRING"/><el-option label="数字" value="NUMBER"/><el-option label="枚举" value="ENUM"/><el-option label="对象" value="OBJECT"/><el-option label="数组" value="ARRAY"/><el-option label="布尔" value="BOOLEAN"/></el-select><el-switch v-model="field.required" inline-prompt active-text="必" inactive-text="选"/><el-input v-model="field.description" placeholder="字段说明"/><el-button text type="danger" :icon="Delete" aria-label="删除字段" @click="removeContractField(index)"/></div></div>
    </el-form>
    <template #footer><el-button @click="contractEditorDialog=false">取消</el-button><el-button type="primary" :loading="contractEditorSaving" @click="savePlatformContract">保存标准响应</el-button></template>
  </el-dialog>
  <el-dialog v-model="codeEditorDialog" :title="codeEditorOriginal ? '修改平台返回码' : '新增平台返回码'" width="min(520px,94vw)" append-to-body>
    <el-form label-position="top"><div class="form-grid"><el-form-item label="返回码" required><el-input v-model="codeEditorForm.code" maxlength="64"/></el-form-item><el-form-item label="名称" required><el-input v-model="codeEditorForm.name" maxlength="128"/></el-form-item><el-form-item label="结果状态"><el-select v-model="codeEditorForm.resultStatus"><el-option label="命中" value="HIT"/><el-option label="未命中" value="MISS"/><el-option label="异常" value="ERROR"/></el-select></el-form-item><el-form-item label="启用"><el-switch v-model="codeEditorForm.enabled"/></el-form-item></div><el-form-item label="说明"><CopyableBlock :value="codeEditorForm.description"><el-input v-model="codeEditorForm.description" maxlength="500" type="textarea" :rows="2"/></CopyableBlock></el-form-item></el-form>
    <template #footer><el-button @click="codeEditorDialog=false">取消</el-button><el-button type="primary" :loading="codeEditorSaving" @click="savePlatformCode">保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="platformServiceDialog" title="基于资源 API 创建平台标准服务" width="680px">
    <el-form label-position="top">
      <el-form-item required><template #label>来源资源 API<HelpTip title="来源资源 API" content="可选择任意已保存的资源 API，不要求测试通过。同一资源 API 可封装为多个标准服务。创建时复制原始请求和响应作为开发基准，后续资源变更可手动同步至服务草稿。"/></template><el-select :model-value="platformServiceForm.resourceApiId || undefined" filterable placeholder="搜索提供商、数据源、接口名称或编码" class="service-resource-select" @change="selectServiceResource(Number($event))"><el-option v-for="resource in serviceResourceOptions" :key="resource.id" :value="resource.id" :label="resource.lineage + ' / ' + resource.resourceApiName + ' · ' + resource.resourceApiCode"><div class="service-resource-option"><strong>{{ resource.resourceApiName }} · {{ resource.resourceApiCode }}</strong><small>{{ resource.lineage }}</small></div></el-option></el-select></el-form-item>
      <el-empty v-if="!serviceResourceOptions.length" description="暂无已保存的资源 API" :image-size="56"><el-button @click="platformServiceDialog=false;navigate('resource')">前往数据资源接入</el-button></el-empty>
      <div v-if="serviceCreationResource" class="service-resource-lineage"><span>{{ serviceCreationResource.lineage }}</span><code>{{ serviceCreationResource.resourceApiCode }}</code><span>{{ serviceCreationResource.verifiedAt ? '曾测试通过' : '未验证' }}</span></div>
      <div class="form-grid"><el-form-item label="标准服务名称" required><el-input v-model="platformServiceForm.serviceName"/></el-form-item><el-form-item required><template #label>标准服务编码<HelpTip title="标准服务编码" content="行内接口文档中的服务唯一标识，对应 XML 的 Service_Header/service_id。"/></template><el-input v-model="platformServiceForm.serviceCode" maxlength="100"/></el-form-item></div>
      <div class="esb-contract-strip"><span>接入方式：ESB 队列（IBM MQ）</span><span>XML 请求 / XML 响应</span></div>
      <details class="advanced-settings"><summary>内部转发配置</summary><div class="form-grid"><el-form-item label="内部 HTTP 方法"><el-select v-model="platformServiceForm.httpMethod"><el-option label="POST" value="POST"/><el-option label="GET" value="GET"/></el-select></el-form-item><el-form-item label="内部服务路径" required><el-input v-model="platformServiceForm.servicePath" placeholder="选择资源 API 后自动生成"/></el-form-item></div></details>
    </el-form>
    <template #footer><el-button @click="platformServiceDialog=false">取消</el-button><el-button type="primary" :disabled="!serviceCreationResource" :loading="platformServiceCreating" @click="createPlatformService">创建并进入服务开发</el-button></template>
  </el-dialog>
  <el-dialog v-model="providerDialog" :title="providerForm.id ? '编辑资源提供商' : '新增资源提供商'" width="760px" class="provider-editor-dialog">
    <el-form label-position="top">
      <h3 class="dialog-section-title">基本信息</h3>
      <div class="form-grid"><el-form-item label="提供商代码" required><el-input v-model="providerForm.providerCode"/></el-form-item><el-form-item label="提供商名称" required><el-input v-model="providerForm.providerName"/></el-form-item><el-form-item label="经办人"><el-input v-model="providerForm.contactName"/></el-form-item><el-form-item label="接入时间"><el-date-picker v-model="providerForm.accessDate" type="date" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item></div>
      <el-form-item label="提供商描述"><CopyableBlock :value="providerForm.description || ''"><el-input v-model="providerForm.description" type="textarea" :rows="2"/></CopyableBlock></el-form-item>
      <h3 class="dialog-section-title">接入方式</h3>
      <div class="form-grid"><el-form-item label="机构类别"><el-select v-model="providerForm.providerType"><el-option label="数据资源提供商" value="DATA_VENDOR"/><el-option label="征信机构" value="CREDIT"/><el-option label="政务数据机构" value="GOVERNMENT"/><el-option label="其他" value="OTHER"/></el-select></el-form-item><el-form-item label="接入类型" required><el-select v-model="providerForm.accessMode" @change="changeProviderAccessMode(String($event))"><el-option label="HTTP 接口" value="HTTP"/><el-option label="数据库" value="DATABASE"/><el-option label="平台微服务" value="PLATFORM_SERVICE"/><el-option label="Kafka" value="KAFKA"/></el-select></el-form-item></div>
      <div v-if="providerForm.accessMode==='HTTP'" class="form-grid"><el-form-item label="HTTP 基础地址"><el-input v-model="providerForm.accessConfig!.baseUrl" placeholder="https://provider.example.com"/></el-form-item><el-form-item label="通讯报文编码"><el-select v-model="providerForm.messageEncoding"><el-option label="UTF-8" value="UTF-8"/><el-option label="GBK" value="GBK"/><el-option label="GB2312" value="GB2312"/></el-select></el-form-item></div>
      <template v-else-if="providerForm.accessMode==='DATABASE'"><div class="form-grid"><el-form-item label="数据库类型"><el-select v-model="providerForm.accessConfig!.databaseType"><el-option label="MySQL" value="MYSQL"/><el-option label="Oracle" value="ORACLE"/><el-option label="PostgreSQL" value="POSTGRESQL"/></el-select></el-form-item><el-form-item label="JDBC Driver"><el-input v-model="providerForm.accessConfig!.driver"/></el-form-item></div><el-form-item label="JDBC URL"><el-input v-model="providerForm.accessConfig!.jdbcUrl"/></el-form-item><div class="form-grid"><el-form-item label="用户名"><el-input v-model="providerForm.accessConfig!.username"/></el-form-item><el-form-item label="密码"><el-input v-model="providerForm.accessConfig!.password" type="password" show-password autocomplete="new-password" :placeholder="providerForm.accessConfig!.passwordConfigured ? '已设置，留空保持原密码' : '请输入数据库密码'"/></el-form-item></div><div class="form-grid"><el-form-item label="初始连接数"><el-input-number v-model="providerForm.accessConfig!.initialSize" :min="0"/></el-form-item><el-form-item label="最小空闲"><el-input-number v-model="providerForm.accessConfig!.minIdle" :min="0"/></el-form-item><el-form-item label="最大连接数"><el-input-number v-model="providerForm.accessConfig!.maxActive" :min="1"/></el-form-item></div></template>
      <el-form-item v-else-if="providerForm.accessMode==='PLATFORM_SERVICE'" label="平台服务名"><el-input v-model="providerForm.accessConfig!.serviceName" placeholder="请填写注册中心服务名"/></el-form-item>
      <template v-else><el-form-item label="Broker List"><el-input v-model="providerForm.accessConfig!.brokerList" placeholder="kafka01:9092,kafka02:9092"/></el-form-item><el-form-item label="Security Protocol"><el-select v-model="providerForm.accessConfig!.securityProtocol"><el-option label="PLAINTEXT" value="PLAINTEXT"/><el-option label="SASL_SSL" value="SASL_SSL"/></el-select></el-form-item></template>
      <h3 class="dialog-section-title">策略配置</h3><div class="form-grid"><el-form-item label="是否需要收费"><el-switch v-model="providerForm.chargeRequired"/></el-form-item><el-form-item label="是否需要加密"><el-switch v-model="providerForm.encryptionRequired"/></el-form-item><el-form-item label="是否需要登录"><el-switch v-model="providerForm.loginRequired"/></el-form-item><el-form-item label="状态"><el-select v-model="providerForm.status"><el-option label="启用" value="ACTIVE"/><el-option label="停用" value="DISABLED"/></el-select></el-form-item></div>
    </el-form>
    <template #footer><el-button @click="providerDialog=false">取消</el-button><el-button type="primary" :loading="providerSaving" @click="saveProvider">{{ providerForm.id ? '保存修改' : '确认新增' }}</el-button></template>
  </el-dialog>
  <el-dialog v-model="accountDialog" title="新增资源提供商账号" width="620px"><el-form label-position="top"><div class="form-grid"><el-form-item label="登录账号"><el-input v-model="accountForm.username"/></el-form-item><el-form-item label="显示名称"><el-input v-model="accountForm.displayName"/></el-form-item><el-form-item label="手机号"><el-input v-model="accountForm.mobile"/></el-form-item><el-form-item label="邮箱"><el-input v-model="accountForm.email"/></el-form-item><el-form-item label="角色"><el-select v-model="accountForm.roleCode"><el-option label="资源提供商管理员" value="PROVIDER_ADMIN"/><el-option label="审计人员" value="AUDITOR"/><el-option label="接口开发" value="API_DEVELOPER"/></el-select></el-form-item><el-form-item label="状态"><el-select v-model="accountForm.status"><el-option label="启用" value="ACTIVE"/><el-option label="停用" value="DISABLED"/></el-select></el-form-item></div><el-form-item label="权限"><el-checkbox-group v-model="accountForm.permissions"><el-checkbox label="DATASOURCE_VIEW">数据源查看</el-checkbox><el-checkbox label="DATASOURCE_EDIT">数据源编辑</el-checkbox><el-checkbox label="API_VIEW">API 查看</el-checkbox><el-checkbox label="API_EDIT">API 编辑</el-checkbox><el-checkbox label="API_TEST">API 联调</el-checkbox><el-checkbox label="CATALOG_PUBLISH">目录发布</el-checkbox><el-checkbox label="AUDIT_VIEW">审计查看</el-checkbox></el-checkbox-group></el-form-item></el-form><template #footer><el-button @click="accountDialog=false">取消</el-button><el-button type="primary" @click="saveAccount">保存账号</el-button></template></el-dialog>
  <el-dialog v-model="versionDialog" :title="selectedVersion ? '版本 V' + selectedVersion.version + ' 详情' : '版本详情'" width="680px">
    <div v-if="selectedVersion" class="version-detail-dialog">
      <div class="version-detail-hero"><strong>V{{ selectedVersion.version }}</strong><div><el-tag :type="versionTagType(selectedVersion)">{{ versionStatusText(selectedVersion) }}</el-tag><p>{{ selectedVersion.releaseNote || '未填写发布说明' }}</p></div></div>
      <dl><div><dt>申请人</dt><dd>{{ selectedVersion.applicant || '-' }}</dd></div><div><dt>审批人 / 发布人</dt><dd>{{ selectedVersion.reviewedBy || selectedVersion.releasedBy || '-' }}</dd></div><div><dt>提交时间</dt><dd>{{ selectedVersion.submittedAt || '-' }}</dd></div><div><dt>发布时间</dt><dd>{{ selectedVersion.releasedAt || selectedVersion.reviewedAt || '-' }}</dd></div><div><dt>执行计划快照</dt><dd>{{ selectedVersion.snapshotAvailable ? '已留存' : '未留存' }}</dd></div><div><dt>配置校验和</dt><dd><code>{{ selectedVersion.checksum || '-' }}</code></dd></div></dl>
      <el-alert v-if="!selectedVersion.historyComplete" title="历史档案不完整" type="warning" :closable="false" description="当前只能从接口主表确认该版本曾发布，未查询到完整审批与发布记录。"/>
    </div>
    <template #footer><el-button type="primary" @click="versionDialog=false">关闭</el-button></template>
  </el-dialog>
  <el-dialog v-model="approvalDialog" title="发布审批" width="640px"><div v-if="selectedApproval" class="approval-review"><div class="approval-title"><strong>{{ selectedApproval.interfaceName }}</strong><el-tag type="warning">V{{ selectedApproval.releaseVersion }} · 待审批</el-tag></div><div class="approval-meta"><span>接口编码：{{ selectedApproval.interfaceCode }}</span><span>申请人：{{ selectedApproval.applicant }}</span><span>提交时间：{{ selectedApproval.submittedAt }}</span></div><div class="approval-checklist"><p><el-icon><CircleCheck /></el-icon>字段映射与统一返回码已配置</p><p><el-icon><CircleCheck /></el-icon>草稿版本校验通过</p><p><el-icon><CircleCheck /></el-icon>审批通过后自动激活发布计划</p></div><CopyableBlock :value="approvalComment"><el-input v-model="approvalComment" type="textarea" :rows="4" placeholder="填写审批意见"/></CopyableBlock></div><template #footer><el-button @click="approvalDialog=false">取消</el-button><el-button type="danger" @click="decideApproval('REJECT')">驳回</el-button><el-button type="primary" @click="decideApproval('APPROVE')">审批通过并发布</el-button></template></el-dialog>
  <el-dialog v-model="clientDialog" title="登记调用系统" width="600px"><el-form label-position="top"><div class="form-grid"><el-form-item label="系统名称"><el-input v-model="clientForm.clientName"/></el-form-item><el-form-item label="系统编码"><el-input v-model="clientForm.clientCode"/></el-form-item><el-form-item label="机构名称"><el-input v-model="clientForm.institutionName"/></el-form-item><el-form-item label="机构编码"><el-input v-model="clientForm.institutionCode"/></el-form-item></div><el-form-item label="负责人"><el-input v-model="clientForm.ownerName"/></el-form-item></el-form><template #footer><el-button @click="clientDialog=false; returnToAuthAfterClient=false">取消</el-button><el-button type="primary" @click="createClient">确认登记</el-button></template></el-dialog>
  <el-dialog v-model="authDialog" :title="authDialogMode === 'EDIT' ? '编辑 API 授权' : '新增 API 授权'" width="620px"><el-form label-position="top"><el-form-item label="调用系统"><span v-if="authDialogMode === 'EDIT'" class="authorization-edit-value">{{ editingAuthorization?.clientName || '-' }}</span><template v-else-if="activeClients.length"><el-select v-model="authForm.clientId" placeholder="请选择调用系统" style="width:100%"><el-option v-for="item in activeClients" :key="item.id" :label="`${item.clientName} · ${item.institutionName}`" :value="item.id"/></el-select></template><div v-else class="auth-client-empty"><div><strong>暂无可用调用系统</strong><span>请先登记一个行内调用系统，再继续授权。</span></div><el-button type="primary" plain :icon="Plus" @click="openClientRegistration(true)">去登记</el-button></div></el-form-item><el-form-item label="授权 API"><span v-if="authDialogMode === 'EDIT'" class="authorization-edit-value">{{ editingAuthorization?.interfaceName || '-' }} · {{ editingAuthorization?.interfaceCode || '-' }}</span><el-select v-else v-model="authForm.interfaceId" :disabled="!availableAuthInterfaces.length" placeholder="请选择该系统尚未授权的 API" no-data-text="该系统暂无可新增授权的 API" style="width:100%"><el-option v-for="item in availableAuthInterfaces" :key="item.id" :label="`${item.interfaceName} · ${item.interfaceCode} · V${item.publishedVersion}`" :value="item.id"/></el-select><small v-if="authDialogMode === 'CREATE' && !availableAuthInterfaces.length" class="authorization-candidate-empty">该调用系统暂无可新增授权的线上 API。</small></el-form-item><div class="form-grid"><el-form-item label="日调用配额"><el-input-number v-model="authForm.dailyQuota" :min="1" :step="1000"/></el-form-item><el-form-item label="生效日期"><el-date-picker v-model="authForm.validFrom" type="date" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="失效日期"><el-date-picker v-model="authForm.validTo" type="date" value-format="YYYY-MM-DD"/></el-form-item></div></el-form><template #footer><el-button @click="authDialog=false">取消</el-button><el-button type="primary" :disabled="!activeClients.length || !authForm.clientId || (authDialogMode === 'CREATE' && !availableAuthInterfaces.some(item => item.id === authForm.interfaceId))" @click="createAuthorization">{{ authDialogMode === 'EDIT' ? '保存修改' : '确认授权' }}</el-button></template></el-dialog>
  <el-dialog v-model="batchAuthDialog" width="min(1080px,94vw)" class="batch-auth-dialog" destroy-on-close>
    <template #header><div class="batch-auth-title"><span>BATCH AUTHORIZATION</span><strong>批量授予 API 访问权限</strong><small>通过筛选和跨页勾选快速处理大规模 API</small></div></template>
    <div class="batch-auth-layout">
      <section class="batch-auth-policy">
        <header><span>01</span><div><strong>授权策略</strong><small>一次配置，应用到所有已选 API</small></div></header>
        <el-form label-position="top">
          <el-form-item label="调用系统"><el-select v-model="batchAuthForm.clientId" filterable placeholder="搜索调用系统" style="width:100%" @change="resetBatchPage"><el-option v-for="item in activeClients" :key="item.id" :label="`${item.clientName} · ${item.clientCode}`" :value="item.id"/></el-select></el-form-item>
          <div class="batch-auth-policy-grid"><el-form-item label="日调用配额"><el-input-number v-model="batchAuthForm.dailyQuota" :min="1" :step="1000" controls-position="right"/></el-form-item></div>
          <el-form-item label="授权有效期"><el-date-picker v-model="batchAuthDateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="生效日期" end-placeholder="失效日期" style="width:100%"/></el-form-item>
        </el-form>
        <div class="batch-auth-selected"><div><span>已选 API</span><strong>{{ batchAuthForm.interfaceIds.length }}</strong></div><button v-if="batchAuthForm.interfaceIds.length" type="button" @click="clearBatchSelection">清空全部</button></div>
        <div class="batch-auth-selected-list"><button v-for="item in selectedBatchAuthorizationInterfaces.slice(0,8)" :key="item.id" type="button" :title="'移除 '+item.interfaceName" @click="removeBatchSelection(item.id)"><span>{{ item.interfaceName }}</span><b>×</b></button><small v-if="selectedBatchAuthorizationInterfaces.length>8">还有 {{ selectedBatchAuthorizationInterfaces.length-8 }} 个 API 已选</small></div>
      </section>
      <section class="batch-auth-picker">
        <header><span>02</span><div><strong>选择 API</strong><small>{{ filteredBatchAuthorizationInterfaces.length }} 个筛选结果</small></div></header>
        <div class="batch-auth-filters"><el-select v-model="batchAuthFilters.sourceId" filterable @change="resetBatchPage"><el-option label="全部数据源" :value="0"/><el-option v-for="source in sources" :key="source.id" :label="source.sourceName" :value="source.id"/></el-select><el-input v-model="batchAuthFilters.keyword" :prefix-icon="Search" clearable placeholder="搜索 API 名称、编码或路径" @input="resetBatchPage"/></div>
        <div class="batch-auth-toolbar"><div><el-checkbox :model-value="pagedBatchAuthorizationInterfaces.length>0 && batchPageSelectedCount===pagedBatchAuthorizationInterfaces.length" :indeterminate="batchPageSelectedCount>0 && batchPageSelectedCount<pagedBatchAuthorizationInterfaces.length" @change="selectBatchPage">选择当前页</el-checkbox><button type="button" @click="selectAllBatchResults">选择全部 {{ filteredBatchAuthorizationInterfaces.length }} 个结果</button></div><span>已选 {{ batchAuthForm.interfaceIds.length }} 个</span></div>
        <div class="batch-auth-table"><div class="batch-auth-table-head"><span></span><span>API</span><span>数据源</span><span>版本</span></div><label v-for="item in pagedBatchAuthorizationInterfaces" :key="item.id" class="batch-auth-row" :class="{selected:batchAuthForm.interfaceIds.includes(item.id)}"><el-checkbox :model-value="batchAuthForm.interfaceIds.includes(item.id)" @change="toggleBatchInterface(item.id,$event)"/><span><strong>{{ item.interfaceName }}</strong><small>{{ item.interfaceCode }} · {{ item.servicePath }}</small></span><span>{{ sources.find(source=>source.id===item.dataSourceId)?.sourceName || '-' }}</span><span>V{{ item.publishedVersion }}</span></label><el-empty v-if="!pagedBatchAuthorizationInterfaces.length" :image-size="54" description="当前筛选条件下没有 API"/></div>
        <div class="batch-auth-pagination"><el-pagination v-model:current-page="batchAuthPage" v-model:page-size="batchAuthPageSize" :page-sizes="[20,50,100]" :total="filteredBatchAuthorizationInterfaces.length" layout="total, sizes, prev, pager, next"/></div>
      </section>
    </div>
    <template #footer><div class="batch-auth-footer"><span>只展示当前调用系统尚未登记授权的 API。</span><div><el-button @click="batchAuthDialog=false">取消</el-button><el-button type="primary" :loading="batchAuthSubmitting" :disabled="!batchAuthForm.clientId || !batchAuthForm.interfaceIds.length" @click="createBatchAuthorization">确认授权 {{ batchAuthForm.interfaceIds.length }} 个 API</el-button></div></div></template>
  </el-dialog>
  <el-drawer v-model="publishedDetailDrawer" title="服务版本详情" size="620px"><div v-loading="publishedDetailLoading" class="published-interface-detail" v-if="publishedInterfaceDetail"><section class="published-detail-hero"><span>SERVICE VERSION</span><strong>{{ publishedInterfaceDetail.summary.interfaceName }}</strong><code>{{ publishedInterfaceDetail.summary.interfaceCode }}</code></section><p v-if="publishedInterfaceDetail.summary.publishedVersion != null && publishedInterfaceDetail.summary.currentVersion !== publishedInterfaceDetail.summary.publishedVersion" class="release-version-note">当前开发配置为 V{{ publishedInterfaceDetail.summary.currentVersion }}；{{ publishedInterfaceDetail.summary.status === 'OFFLINE' ? '服务已下线，最近上线版本为' : '线上仍使用' }} V{{ publishedInterfaceDetail.summary.publishedVersion }}。以下请求与响应样例来自当前开发配置，不代表已发布版本。</p><dl class="published-detail-facts"><div><dt>当前开发版本</dt><dd>V{{ publishedInterfaceDetail.summary.currentVersion }}</dd></div><div><dt>{{ publishedInterfaceDetail.summary.status === 'OFFLINE' ? '最近上线版本' : '线上版本' }}</dt><dd>{{ publishedInterfaceDetail.summary.publishedVersion == null ? '未发布' : 'V' + publishedInterfaceDetail.summary.publishedVersion }}</dd></div><div><dt>当前开发路径</dt><dd>{{ publishedInterfaceDetail.summary.servicePath }}</dd></div><div><dt>当前开发方法</dt><dd><el-tag type="info">{{ publishedInterfaceDetail.summary.httpMethod }}</el-tag></dd></div><div><dt>当前开发上游地址</dt><dd>{{ publishedInterfaceDetail.summary.upstreamUrl || '-' }}</dd></div><div><dt>调用超时</dt><dd>{{ publishedInterfaceDetail.timeoutMs }} ms · {{ publishedInterfaceDetail.timeoutSource === 'INTERFACE' ? '接口覆盖' : '数据源默认' }}</dd></div><div><dt>当前状态</dt><dd><el-tag :type="publishedInterfaceDetail.summary.status === 'OFFLINE' ? 'danger' : 'info'">{{ publishedInterfaceDetail.summary.status === 'OFFLINE' ? '已下线' : publishedInterfaceDetail.summary.currentVersion !== publishedInterfaceDetail.summary.publishedVersion ? '有未发布变更' : '运行中' }}</el-tag></dd></div></dl><section class="published-detail-code"><div><label>当前开发请求样例</label><CopyableBlock :value="formatJson(publishedInterfaceDetail.requestExample)"><pre>{{ formatJson(publishedInterfaceDetail.requestExample) }}</pre></CopyableBlock></div><div><label>当前开发响应样例</label><CopyableBlock :value="formatJson(publishedInterfaceDetail.standardResponseExample)"><pre>{{ formatJson(publishedInterfaceDetail.standardResponseExample) }}</pre></CopyableBlock></div></section></div><el-empty v-else-if="!publishedDetailLoading" description="暂无接口详情"/></el-drawer>
  <el-dialog v-model="billingDialog" title="配置接口计费规则" width="600px"><el-form label-position="top"><el-form-item label="计费 API"><el-select v-model="billingForm.interfaceId"><el-option v-for="item in allInterfaces" :key="item.id" :label="`${item.interfaceName} · ${item.interfaceCode}`" :value="item.id"/></el-select></el-form-item><div class="form-grid"><el-form-item label="计费方式"><el-select v-model="billingForm.billingMode"><el-option label="按成功次数" value="SUCCESS_COUNT"/><el-option label="按调用次数" value="REQUEST_COUNT"/></el-select></el-form-item><el-form-item label="单价（元/次）"><el-input-number v-model="billingForm.unitPrice" :precision="4" :step="0.01" :min="0"/></el-form-item><el-form-item label="HIT 是否计费"><el-switch v-model="billingForm.hitBillable"/></el-form-item><el-form-item label="MISS 是否计费"><el-switch v-model="billingForm.missBillable"/></el-form-item></div><el-form-item label="生效日期"><el-date-picker v-model="billingForm.effectiveDate" type="date" value-format="YYYY-MM-DD"/></el-form-item></el-form><template #footer><el-button @click="billingDialog=false">取消</el-button><el-button type="primary" @click="saveBilling">保存并生效</el-button></template></el-dialog>
</template>
