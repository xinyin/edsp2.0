export interface DashboardSummary {
  providerCount: number
  dataSourceCount: number
  interfaceCount: number
  publishedCount: number
  draftCount: number
  activePublishedPlans: number
}
export interface ResourceDocument { id: number; providerId: number; providerName: string; dataSourceId?: number; dataSourceName?: string; resourceApiId?: number; resourceApiName?: string; documentName: string; contentType: string; fileSize: number; description?: string; uploadedAt: string }
export interface ResourceDirectoryRow { id: number; resourceApiCode: string; resourceApiName: string; httpMethod: string; status: string; updatedAt: string; providerId: number; providerName: string; dataSourceId: number; dataSourceName: string; dataSourceCode: string; protocolType: string }
export interface ResourceDirectoryPage { rows: ResourceDirectoryRow[]; total: number; page: number; size: number }
export interface UsageReportRow { level: number; path: string[]; label: string; callCount?: number; successCount?: number; failureCount?: number; billableCount?: number; averageDurationMs?: number; upstreamDurationMs?: number; children?: UsageReportRow[] }
export interface UsageReportResult { dimensions: string[]; metrics: string[]; rows: UsageReportRow[] }
export interface OperationAuditRecord { id: number; operatorName: string; action: string; targetPath: string; targetType: string; outcome: string; httpStatus: number; traceId?: string; clientIp?: string; occurredAt: string }
export interface OperationAuditPage { rows: OperationAuditRecord[]; total: number; page: number; size: number }

export interface LoginResult { token: string; displayName: string; role: string; institution: string }
export type ProviderAccessMode = 'HTTP' | 'DATABASE' | 'PLATFORM_SERVICE' | 'KAFKA'
export interface ProviderSummary { id: number; providerCode: string; providerName: string; creditCode?: string; providerType?: string; contactName?: string; contactPhone?: string; contactEmail?: string; servicePhone?: string; contractNo?: string; contractStartDate?: string; contractEndDate?: string; securityLevel?: string; settlementMode?: string; officeAddress?: string; description?: string; accessDate?: string; messageEncoding?: string; chargeRequired?: boolean; encryptionRequired?: boolean; loginRequired?: boolean; status: string; accessMode?: ProviderAccessMode; accessConfig?: Record<string, unknown>; applicationName?: string; runtimeUrl?: string; serverPort?: number; configVersion?: number }
export interface DatabaseConnectionTestResult { success: boolean; message: string; durationMs: number; databaseProduct: string | null }
export interface ProviderAccount { id: number; providerId: number; providerName?: string; username: string; displayName: string; mobile?: string; email?: string; roleCode: string; permissions: string[]; status: string; lastLoginAt?: string; createdAt?: string }
export interface ClientApp { id: number; clientCode: string; clientName: string; institutionCode: string; institutionName: string; ownerName?: string; status: string }
export interface AuthorizationRecord { id: number; clientId: number; clientName: string; interfaceId: number; interfaceName: string; interfaceCode: string; dailyQuota: number; validFrom: string; validTo: string; status: string; approvedBy?: string }
export interface BillingRule { id: number; interfaceId: number; interfaceName: string; interfaceCode: string; hitBillable: boolean; missBillable: boolean; effectiveDate: string; status: string }
export interface BillingSummary { totalCalls: number; billableCalls: number; hitCalls: number; missCalls: number; errorCalls: number }
export interface UsageRecord { id: number; traceId: string; clientName?: string; interfaceName: string; interfaceCode: string; resultStatus: string; standardCode: string; billable: boolean; durationMs: number; upstreamDurationMs: number; gatewayDurationMs: number; requestStatus: string; nodeName: string; calledAt: string }
export interface CallTraceDetail { summary: UsageRecord; nodes: TraceNode[] }
export interface MonitorSummary { totalCalls: number; successCalls: number; errorCalls: number; successRate: number; avgDurationMs: number; p95DurationMs: number; avgUpstreamDurationMs: number; activeAlerts: number }
export interface MonitorPoint { timeLabel: string; calls: number; successCalls: number; errorCalls: number; avgDurationMs: number; avgUpstreamDurationMs: number }
export interface MonitorAlert { level: string; title: string; detail: string; timeLabel: string }
export interface VendorApiDefinition { id: number; dataSourceId: number; dataSourceCode: string; dataSourceName: string; apiCode: string; apiName: string; servicePath: string; httpMethod: string; requestExample: Record<string, unknown>; responseBody: Record<string, unknown>; responseCode: string; delayMs: number; status: string; version: number; updatedAt: string }
export interface VendorDataSourceDefinition { id: number; sourceCode: string; sourceName: string; description: string; basePath: string; status: string; updatedAt: string; apis: VendorApiDefinition[] }

export interface DataSourceSummary {
  id: number
  providerId: number
  providerName: string
  sourceCode: string
  sourceName: string
  applicationName: string
  runtimeUrl?: string
  serverPort: number
  protocolType: string
  endpointUrl?: string
  defaultTimeoutMs?: number
  status: string
  instanceCount: number
  configVersion: number
  interfaceCount: number
}

export interface ProvisionStep { status: string; message: string }
export interface DataApplicationProvisionSettings { enabled: boolean; outputRoot: string; group: string; namespace: string }
export interface DataApplicationProvisionResult {
  provider: ProviderSummary
  routeSave: ProvisionStep
  generation: ProvisionStep & { outputDirectory?: string; packageName?: string; generatedFiles: string[] }
  nacos: ProvisionStep & { dataId: string; group: string; namespace: string }
  routeRefresh: ProvisionStep
}

export interface InterfaceSummary {
  id: number
  dataSourceId: number
  interfaceCode: string
  interfaceName: string
  servicePath: string
  httpMethod: string
  upstreamUrl: string
  upstreamPath?: string
  configScope?: 'INHERIT' | 'OVERRIDE'
  dispatchMode?: 'DIRECT' | 'ROUTER'
  routerServiceCode?: string
  effectiveTimeoutMs?: number
  upstreamStatus?: 'DRAFT' | 'READY'
  status: string
  currentVersion: number
  publishedVersion: number | null
  updatedAt: string
  resourceApiId?: number
  resourceApiCode?: string
  resourceApiName?: string
}

export interface ReleaseServiceRow {
  service: InterfaceSummary
  runStatus: 'UNPUBLISHED' | 'RUNNING' | 'OFFLINE'
  providerName: string
  dataSourceName: string
  activeAuthorizationClientCount: number
  latestPublishedAt?: string
}
export interface ReleaseServicePage { rows: ReleaseServiceRow[]; total: number; page: number; size: number }
export interface InterfacePage { rows: InterfaceSummary[]; total: number; page: number; size: number }
export interface ResourceApiPage { rows: ResourceApiSummary[]; total: number; page: number; size: number }

export interface ResourceApiSummary {
  id: number
  dataSourceId: number
  resourceApiCode: string
  resourceApiName: string
  httpMethod: string
  upstreamUrl: string
  upstreamPath?: string
  configScope: 'INHERIT' | 'OVERRIDE'
  effectiveTimeoutMs: number
  status: 'DRAFT' | 'READY'
  currentVersion: number
  verifiedAt?: string
  updatedAt: string
  platformServiceCount: number
}

export interface ResourceApiDetail {
  summary: ResourceApiSummary
  requestExample: Record<string, unknown>
  responseExample: Record<string, unknown>
  requestHeaders: Record<string, string>
  configuredTimeoutMs?: number | null
  timeoutSource: 'DATA_SOURCE' | 'RESOURCE_API'
  executionMode: 'HTTP' | 'SQL'
  sqlTemplate: string
  sqlParameters: Record<string, string>
}

export interface FieldMapping {
  id?: number
  direction: 'REQUEST' | 'RESPONSE'
  sourcePath: string
  targetPath: string
  transformType: 'DIRECT' | 'ENUM' | 'DEFAULT' | 'DATE' | 'ENCRYPT' | 'SIGN' | 'WHOLE_BODY' | 'CONSTANT'
  transformConfig: Record<string, string>
  defaultValue?: string
  required: boolean
  sortOrder: number
}

export interface SecurityPolicy {
  interfaceId: number
  enabled: boolean
  encryptMode: string
  signatureMode: string
  requestFields: string
  encryptionKeyReference: string
  signingKeyReference: string
  signatureField: string
  description: string
}

export interface SecurityAlgorithmParameter {
  name: string
  label: string
  type: 'TEXT' | 'SELECT' | 'SECRET_REFERENCE'
  required: boolean
  defaultValue: string
  options: string[]
  placeholder: string
}

export interface SecurityAlgorithmDescriptor {
  code: string
  name: string
  kind: 'ENCRYPT' | 'SIGN'
  category: string
  description: string
  parameters: SecurityAlgorithmParameter[]
  builtIn: boolean
}

export interface ResponseCodeMapping {
  id?: number
  vendorCode: string
  standardCode: string
  resultStatus: string
  billable: boolean
}

export interface PlatformResponseCode {
  code: string
  name: string
  resultStatus: 'HIT' | 'MISS' | 'ERROR'
  category: string
  description: string
  enabled: boolean
}

export interface PlatformResponseField {
  key: string
  label: string
  type: string
  required: boolean
  description: string
}

export interface PlatformResponseContract {
  id?: number
  contractCode: string
  contractName: string
  version: number
  description: string
  enabled: boolean
  defaultContract: boolean
  active: boolean
  fields: PlatformResponseField[]
}

export interface ResponsePolicy {
  dataMode: 'FIELD_MAPPING' | 'WHOLE_BODY'
  codeMode: 'VENDOR_CODE' | 'HTTP_SUCCESS'
  successCode: string
}

export interface InterfaceDetail {
  summary: InterfaceSummary
  requestHeaders?: Record<string, string>
  requestSchema: Record<string, unknown>
  responseSchema: Record<string, unknown>
  requestExample: Record<string, unknown>
  upstreamRequestExample: Record<string, unknown>
  upstreamResponseExample: Record<string, unknown>
  standardResponseExample: Record<string, unknown>
  timeoutMs: number
  configuredTimeoutMs?: number | null
  timeoutSource?: 'DATA_SOURCE' | 'INTERFACE'
  retryCount: number
  vendorCodePath: string
  mappings: FieldMapping[]
  responseCodes: ResponseCodeMapping[]
  responsePolicy: ResponsePolicy
  securityPolicy: SecurityPolicy
  executionMode?: 'HTTP' | 'SQL'
  sqlTemplate?: string
  sqlParameters?: Record<string, string>
}

export interface ValidationResult {
  valid: boolean
  errors: string[]
  warnings: string[]
}

export interface ExecutionStep {
  name: string
  status: string
  detail: string
  durationMs: number
}

export interface TraceNode {
  key: string
  name: string
  category: string
  status: string
  detail: string
  durationMs: number
  input: unknown
  output: unknown
}

export interface TestResult {
  upstreamRequest: Record<string, unknown>
  upstreamResponse: Record<string, unknown>
  standardResponse: Record<string, unknown>
  steps: ExecutionStep[]
  traceNodes: TraceNode[]
}

export interface EsbDiagnosticResult {
  upstreamRequest: Record<string, unknown>
  upstreamResponse: Record<string, unknown>
  standardResponse: Record<string, unknown>
  apiCode: string
  servicePath: string
  traceId: string
  parsedRequest: Record<string, unknown>
  xmlRequest: string
  xmlResponse: string
  nodes: TraceNode[]
}

export interface VendorMockConfig {
  id: number
  interfaceId: number
  interfaceCode: string
  mockName: string
  responseBody: Record<string, unknown>
  httpStatus: number
  delayMs: number
  enabled: boolean
  endpoint: string
}

export interface PublishResult {
  releaseId: number
  version: number
  checksum: string
  status: string
  activePlanVersion: number
}

export interface ReleaseApproval { id: number; interfaceId: number; interfaceCode: string; interfaceName: string; releaseVersion: number; applicant: string; releaseNote?: string; status: 'PENDING'|'APPROVED'|'REJECTED'; approvalNode: string; submittedAt: string; reviewedBy?: string; reviewComment?: string; reviewedAt?: string; checksum?: string }
export interface InterfaceVersionHistory {
  interfaceId: number
  version: number
  versionType: 'PUBLISHED'|'APPROVAL'|'DRAFT'
  status: 'ACTIVE'|'OFFLINE'|'SUPERSEDED'|'PUBLISHED_LEGACY'|'PENDING'|'APPROVED'|'REJECTED'|'DRAFT'
  active: boolean
  applicant?: string
  releaseNote?: string
  approvalNode?: string
  reviewedBy?: string
  reviewComment?: string
  submittedAt?: string
  reviewedAt?: string
  releasedBy?: string
  releasedAt?: string
  checksum?: string
  snapshotAvailable: boolean
  historyComplete: boolean
}
