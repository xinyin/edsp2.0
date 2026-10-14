import * as mock from './mock'
import type { AuthorizationRecord, BillingRule, BillingSummary, CallTraceDetail, ClientApp, DashboardSummary, DataApplicationProvisionResult, DataApplicationProvisionSettings, DataSourceSummary, EsbDiagnosticResult, FieldMapping, InterfaceDetail, InterfaceSummary, InterfaceVersionHistory, LoginResult, MonitorAlert, MonitorPoint, MonitorSummary, ProviderAccount, ProviderSummary, ReleaseApproval, ResourceApiDetail, ResourceApiSummary, SecurityAlgorithmDescriptor, SecurityPolicy, TestResult, UsageRecord, ValidationResult, VendorDataSourceDefinition } from './types'

const demoMode = import.meta.env.VITE_DEMO_MODE === 'mock'
export const isDemoMode = () => demoMode
let operatorToken = ''

export class ApiRequestError extends Error {
  constructor(message: string, readonly status: number, readonly payload: Record<string, unknown>) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

async function request<T>(path: string, init?: RequestInit, fallback?: () => T | Promise<T>, timeoutMs = 30_000): Promise<T> {
  if (demoMode && fallback) return fallback()
  const controller = new AbortController()
  const externalSignal = init?.signal
  let timedOut = false
  const abortFromCaller = () => controller.abort(externalSignal?.reason)
  if (externalSignal?.aborted) abortFromCaller()
  else externalSignal?.addEventListener('abort', abortFromCaller, { once: true })
  const timer = globalThis.setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
  let response: Response
  try {
    response = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init, signal: controller.signal })
  } catch (error) {
    if (controller.signal.aborted) {
      if (timedOut) throw new Error(`请求超过 ${Math.ceil(timeoutMs / 1000)} 秒，已自动终止`)
      throw new Error('请求已取消')
    }
    throw error
  } finally {
    globalThis.clearTimeout(timer)
    externalSignal?.removeEventListener('abort', abortFromCaller)
  }
  const responseText = await response.text()
  if (!response.ok) {
    if (response.status === 405 && init?.method === 'DELETE') {
      throw new Error('当前运行的管理服务未支持 API 删除，请更新管理服务后重试（405）')
    }
    let payload: Record<string, unknown> = {}
    try {
      const parsed: unknown = JSON.parse(responseText)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) payload = parsed as Record<string, unknown>
    } catch { /* Keep raw response as the message. */ }
    const message = typeof payload.message === 'string' ? payload.message : responseText
    throw new ApiRequestError(message, response.status, payload)
  }
  if (!responseText) return undefined as T
  return JSON.parse(responseText) as T
}

export const api = {
  login: async (username: string, password: string) => {
    const user = await request<LoginResult>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
    operatorToken = user.token
    return user
  },
  dashboard: () => request<DashboardSummary>('/api/dashboard', undefined, () => mock.dashboard),
  securityAlgorithms: () => request<SecurityAlgorithmDescriptor[]>('/api/security-algorithms'),
  providers: () => request<ProviderSummary[]>('/api/providers', undefined, () => [{ id: 1, providerCode: 'QXB', providerName: '企信资源提供商', contactName: '张经理', accessDate: '2026-01-01', messageEncoding: 'UTF-8', chargeRequired: true, encryptionRequired: true, loginRequired: true, status: 'ACTIVE' }]),
  createProvider: (body: Omit<ProviderSummary, 'id'>) => request<ProviderSummary>('/api/providers', { method: 'POST', body: JSON.stringify(body) }),
  updateProvider: (id: number, body: Omit<ProviderSummary, 'id'>) => request<ProviderSummary>(`/api/providers/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  providerAccounts: (providerId: number) => request<ProviderAccount[]>(`/api/providers/${providerId}/accounts`),
  createProviderAccount: (providerId: number, body: Omit<ProviderAccount, 'id'|'providerId'|'providerName'>) => request<ProviderAccount>(`/api/providers/${providerId}/accounts`, { method: 'POST', body: JSON.stringify(body) }),
  updateProviderAccount: (id: number, body: Omit<ProviderAccount, 'id'|'providerId'|'providerName'>) => request<ProviderAccount>(`/api/provider-accounts/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  dataSources: () => request<DataSourceSummary[]>('/api/data-sources', undefined, () => mock.dataSources),
  createDataSource: (body: Record<string, unknown>) => request<DataSourceSummary>('/api/data-sources', { method: 'POST', body: JSON.stringify(body) }),
  dataApplicationProvisionSettings: () => request<DataApplicationProvisionSettings>('/api/data-application-provision/settings'),
  saveDataSourceRuntime: (id: number, body: { applicationName: string; runtimeUrl: string }) =>
    request<DataApplicationProvisionResult>(`/api/data-sources/${id}/runtime`, { method: 'PUT', body: JSON.stringify(body) }, undefined, 60_000),
  saveDataSourceSettings: (id: number, body: { providerId: number; sourceName: string; protocolType: string; endpoint: string; defaultTimeoutMs: number }) =>
    request<DataSourceSummary>(`/api/data-sources/${id}/settings`, { method: 'PUT', body: JSON.stringify(body) }),
  resourceApis: (sourceId?: number) => request<ResourceApiSummary[]>(sourceId ? `/api/resource-apis?dataSourceId=${sourceId}` : '/api/resource-apis'),
  createResourceApi: (body: Record<string, unknown>) => request<ResourceApiSummary>('/api/resource-apis', { method: 'POST', body: JSON.stringify(body) }),
  resourceApi: (id: number) => request<ResourceApiDetail>(`/api/resource-apis/${id}`),
  resourceValidationStatus: (id: number) => request<{ upstreamVerified: boolean }>(`/api/resource-apis/${id}/validation-status`),
  saveResourceApi: (id: number, body: Record<string, unknown>) => request<ResourceApiDetail>(`/api/resource-apis/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  verifyResourceApi: (id: number) => request<TestResult>(`/api/resource-apis/${id}/verify`, { method: 'POST' }),
  createPlatformService: (body: Record<string, unknown>) => request<InterfaceSummary>('/api/platform-services', { method: 'POST', body: JSON.stringify(body) }),
  interfaces: (sourceId?: number) => request<InterfaceSummary[]>(sourceId ? `/api/interfaces?dataSourceId=${sourceId}` : '/api/interfaces', undefined, () => sourceId ? mock.interfaces.filter(item => item.dataSourceId === sourceId) : mock.interfaces),
  deleteInterface: (id: number) => request<void>(`/api/interfaces/${id}`, { method: 'DELETE' }, () => {
    const index = mock.interfaces.findIndex(item => item.id === id)
    if (index >= 0) mock.interfaces.splice(index, 1)
  }),
  offlineInterface: (id: number, reason = '接口下线') => request<InterfaceSummary>(`/api/interfaces/${id}/offline`, { method: 'POST', body: JSON.stringify({ operatedBy: '平台管理员', reason }) }, () => {
    const item = mock.interfaces.find(row => row.id === id)
    if (!item) throw new Error('接口不存在')
    item.status = 'OFFLINE'
    return structuredClone(item)
  }),
  onlineInterface: (id: number, reason = '恢复最近已发布版本') => request<InterfaceSummary>(`/api/interfaces/${id}/online`, { method: 'POST', body: JSON.stringify({ operatedBy: '平台管理员', reason }) }, () => {
    const item = mock.interfaces.find(row => row.id === id)
    if (!item) throw new Error('接口不存在')
    item.status = item.currentVersion === item.publishedVersion ? 'PUBLISHED' : 'DRAFT'
    return structuredClone(item)
  }),
  detail: (id: number) => request<InterfaceDetail>(`/api/interfaces/${id}`, undefined, () => mock.getInterfaceDetail(id)),
  validationStatus: (id: number) => request<{ upstreamVerified: boolean; orchestrationVerified: boolean; resourceContractChanged: boolean }>(`/api/interfaces/${id}/validation-status`),
  syncResourceContract: (id: number) => request<InterfaceDetail>(`/api/interfaces/${id}/sync-resource`, { method: 'POST' }),
  interfaceVersions: (id: number) => request<InterfaceVersionHistory[]>(`/api/interfaces/${id}/versions`, undefined, () => {
    const item = mock.getInterfaceDetail(id).summary
    const rows: InterfaceVersionHistory[] = [{
      interfaceId: id, version: item.currentVersion, versionType: item.publishedVersion === item.currentVersion ? 'PUBLISHED' : 'DRAFT',
      status: item.publishedVersion === item.currentVersion ? 'ACTIVE' : 'DRAFT', active: item.publishedVersion === item.currentVersion,
      snapshotAvailable: item.publishedVersion === item.currentVersion, historyComplete: true
    }]
    if (item.publishedVersion != null && item.publishedVersion !== item.currentVersion) rows.push({
      interfaceId: id, version: item.publishedVersion, versionType: 'PUBLISHED', status: 'ACTIVE', active: true,
      snapshotAvailable: true, historyComplete: true
    })
    return rows
  }),
  saveConnectionConfig: (id: number, body: { configScope: 'INHERIT'|'OVERRIDE'; upstreamPath?: string; upstreamUrl?: string; timeoutMs: number|null; upstreamRequestExample: Record<string, unknown>; upstreamResponseExample: Record<string, unknown> }) =>
    request<InterfaceDetail>(`/api/interfaces/${id}/connection-config`, { method: 'PUT', body: JSON.stringify(body) }),
  saveMappings: (id: number, mappings: FieldMapping[], servicePath: string, httpMethod: string, requestExample: Record<string, unknown>) => request<FieldMapping[]>(`/api/interfaces/${id}/mappings`, {
    method: 'PUT', body: JSON.stringify({ mappings, servicePath, httpMethod, requestExample })
  }, () => {
    const plainMappings = JSON.parse(JSON.stringify(mappings)) as FieldMapping[]
    mock.interfaceDetail.mappings = plainMappings
    mock.interfaceDetail.requestExample = structuredClone(requestExample)
    mock.interfaceDetail.summary.currentVersion += 1
    if (mock.interfaceDetail.summary.status !== 'OFFLINE') mock.interfaceDetail.summary.status = 'DRAFT'
    return plainMappings
  }),
  saveResponseCodes: (id: number, vendorCodePath: string, responseCodes: InterfaceDetail['responseCodes']) => request<InterfaceDetail['responseCodes']>(`/api/interfaces/${id}/response-codes`, { method: 'PUT', body: JSON.stringify({ vendorCodePath, responseCodes }) }, () => {
    mock.interfaceDetail.vendorCodePath = vendorCodePath || 'result.code'
    mock.interfaceDetail.responseCodes = structuredClone(responseCodes)
    mock.interfaceDetail.summary.currentVersion += 1
    if (mock.interfaceDetail.summary.status !== 'OFFLINE') mock.interfaceDetail.summary.status = 'DRAFT'
    return structuredClone(mock.interfaceDetail.responseCodes)
  }),
  securityPolicy: (id: number) => request<SecurityPolicy>(`/api/interfaces/${id}/security-policy`, undefined, () => ({
    ...(structuredClone(mock.interfaceDetail.securityPolicy)), interfaceId: id
  })),
  saveSecurityPolicy: (id: number, policy: Omit<SecurityPolicy, 'interfaceId'>) => request<SecurityPolicy>(`/api/interfaces/${id}/security-policy`, { method: 'PUT', body: JSON.stringify(policy) }, () => {
    mock.interfaceDetail.securityPolicy = { interfaceId: id, ...structuredClone(policy) }
    mock.interfaceDetail.summary.currentVersion += 1
    if (mock.interfaceDetail.summary.status !== 'OFFLINE') mock.interfaceDetail.summary.status = 'DRAFT'
    return structuredClone(mock.interfaceDetail.securityPolicy)
  }),
  validate: (id: number) => request<ValidationResult>(`/api/interfaces/${id}/validate`, { method: 'POST' }, () => mock.validation),
  test: (id: number, body: { request: Record<string, unknown>, upstreamResponse?: Record<string, unknown>, callUpstream?: boolean, mappings?: FieldMapping[], testMode?: 'UPSTREAM' | 'PLATFORM' | 'E2E' }, options?: { timeoutMs?: number; signal?: AbortSignal }) => request<TestResult>(`/api/interfaces/${id}/test`, {
    method: 'POST', body: JSON.stringify(body), signal: options?.signal
  }, () => mock.runMockTest(body.request, body.upstreamResponse ?? {}), options?.timeoutMs),
  esbDiagnostic: async (_clientCode: string, xml: string, traceId: string, options?: { timeoutMs?: number; signal?: AbortSignal }) => request<EsbDiagnosticResult>('/api/test-tools/chain', {
    method: 'POST', headers: { 'Content-Type': 'application/xml', 'X-Edsp-Operator-Token': operatorToken, 'X-Trace-Id': traceId }, body: xml, signal: options?.signal
  }, undefined, options?.timeoutMs),
  submitApproval: (id: number, releaseNote: string) => request<ReleaseApproval>(`/api/interfaces/${id}/approval-requests`, {
    method: 'POST', body: JSON.stringify({ releaseNote, releasedBy: '平台管理员' })
  }),
  directPublish: (id: number, releaseNote: string) => request<InterfaceSummary>(`/api/interfaces/${id}/direct-publish`, {
    method: 'POST', body: JSON.stringify({ releaseNote, releasedBy: '平台管理员' })
  }),
  approvals: (status?: string) => request<ReleaseApproval[]>(status ? `/api/approvals?status=${status}` : '/api/approvals'),
  decideApproval: (id: number, decision: 'APPROVE'|'REJECT', reviewComment: string) => request<ReleaseApproval>(`/api/approvals/${id}/decision`, { method: 'POST', body: JSON.stringify({ decision, reviewedBy: '技术审批员', reviewComment }) }),
  clients: () => request<ClientApp[]>('/api/clients'),
  createClient: (body: Record<string, unknown>) => request<ClientApp>('/api/clients', { method: 'POST', body: JSON.stringify(body) }),
  authorizations: () => request<AuthorizationRecord[]>('/api/authorizations'),
  createAuthorization: (body: Record<string, unknown>) => request<AuthorizationRecord>('/api/authorizations', { method: 'POST', body: JSON.stringify(body) }),
  createAuthorizations: (body: Record<string, unknown>) => request<AuthorizationRecord[]>('/api/authorizations/batch', { method: 'POST', body: JSON.stringify(body) }),
  billingRules: () => request<BillingRule[]>('/api/billing/rules'),
  saveBillingRule: (body: Record<string, unknown>) => request<BillingRule>('/api/billing/rules', { method: 'PUT', body: JSON.stringify(body) }),
  billingSummary: () => request<BillingSummary>('/api/billing/summary'),
  usageRecords: () => request<UsageRecord[]>('/api/usage-records')
  ,callLogs: (filters: { status?: string; interfaceCode?: string; keyword?: string }) => {
    const query = new URLSearchParams(Object.entries(filters).filter(([,value]) => value).map(([key,value]) => [key, String(value)]))
    return request<UsageRecord[]>(`/api/call-logs${query.size ? `?${query}` : ''}`)
  }
  ,callTrace: (traceId: string) => request<CallTraceDetail>(`/api/call-logs/${encodeURIComponent(traceId)}`)
  ,monitorSummary: () => request<MonitorSummary>('/api/monitor/summary')
  ,monitorPoints: () => request<MonitorPoint[]>('/api/monitor/points')
  ,monitorAlerts: () => request<MonitorAlert[]>('/api/monitor/alerts')
}

async function vendorRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/vendor-api${path}`, { headers: { 'Content-Type': 'application/json' }, ...init })
  const body = await response.text()
  if (!response.ok) throw new Error(body || '供应商应用请求失败')
  return body ? JSON.parse(body) as T : undefined as T
}

export const vendorApi = {
  catalog: () => vendorRequest<VendorDataSourceDefinition[]>('/open/catalog')
}
