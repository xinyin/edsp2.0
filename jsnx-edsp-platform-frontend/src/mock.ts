import type { DashboardSummary, DataSourceSummary, FieldMapping, InterfaceDetail, InterfaceSummary, TestResult, ValidationResult } from './types'

export const dashboard: DashboardSummary = {
  providerCount: 2,
  dataSourceCount: 4,
  interfaceCount: 12,
  publishedCount: 8,
  draftCount: 4,
  activePublishedPlans: 8
}

export const dataSources: DataSourceSummary[] = [
  { id: 1, providerId: 1, providerName: '企信资源提供商', sourceCode: 'ENTERPRISE', sourceName: '企业工商数据源', applicationName: 'jsnx-edsp-data-enterprise', protocolType: 'HTTP_JSON', environment: 'TEST', status: 'ACTIVE', instanceCount: 2, configVersion: 3, interfaceCount: 5 },
  { id: 2, providerId: 1, providerName: '企信资源提供商', sourceCode: 'CREDIT', sourceName: '征信报告数据源', applicationName: 'jsnx-edsp-data-credit', protocolType: 'HTTP_JSON', environment: 'TEST', status: 'ACTIVE', instanceCount: 2, configVersion: 2, interfaceCount: 3 },
  { id: 3, providerId: 2, providerName: '司法资源提供商', sourceCode: 'JUDICIAL', sourceName: '司法涉诉数据源', applicationName: 'jsnx-edsp-data-source-judicial', protocolType: 'HTTP_JSON', environment: 'TEST', status: 'ACTIVE', instanceCount: 2, configVersion: 4, interfaceCount: 3 },
  { id: 4, providerId: 3, providerName: '行内数据平台', sourceCode: 'LOCAL_DATASET', sourceName: '本地数据集服务', applicationName: 'jsnx-edsp-data-source-local', protocolType: 'LOCAL_SQL', environment: 'TEST', status: 'TRIAL', instanceCount: 2, configVersion: 1, interfaceCount: 1 }
]

export const interfaces: InterfaceSummary[] = [
  { id: 1, dataSourceId: 1, interfaceCode: 'ENTERPRISE_BASIC', interfaceName: '企业基本信息查询', servicePath: '/api/v1/enterprise/basic', httpMethod: 'POST', upstreamUrl: 'https://sandbox.vendor.example.com/open/enterprise/basic', status: 'PUBLISHED', currentVersion: 3, publishedVersion: 2, updatedAt: '2026-07-24 09:30:00' },
  { id: 2, dataSourceId: 1, interfaceCode: 'ENTERPRISE_RISK', interfaceName: '企业风险扫描', servicePath: '/api/v1/enterprise/risk', httpMethod: 'POST', upstreamUrl: 'https://sandbox.vendor.example.com/open/enterprise/risk', status: 'DRAFT', currentVersion: 1, publishedVersion: null, updatedAt: '2026-07-24 08:42:00' },
  { id: 3, dataSourceId: 1, interfaceCode: 'ENTERPRISE_SHAREHOLDER', interfaceName: '企业股东信息查询', servicePath: '/api/v1/enterprise/shareholder', httpMethod: 'POST', upstreamUrl: 'https://sandbox.vendor.example.com/open/enterprise/shareholder', status: 'PUBLISHED', currentVersion: 2, publishedVersion: 2, updatedAt: '2026-07-23 16:10:00' },
  { id: 4, dataSourceId: 2, interfaceCode: 'PERSON_VERIFY', interfaceName: '个人身份核验', servicePath: '/api/v1/person/verify', httpMethod: 'POST', upstreamUrl: 'https://sandbox.vendor.example.com/open/person/verify', status: 'PUBLISHED', currentVersion: 2, publishedVersion: 2, updatedAt: '2026-07-23 15:20:00' },
  { id: 5, dataSourceId: 3, interfaceCode: 'JUDICIAL_RISK', interfaceName: '司法风险查询', servicePath: '/api/v1/judicial/risk', httpMethod: 'POST', upstreamUrl: 'https://sandbox.judicial.example.com/open/risk', status: 'DRAFT', currentVersion: 4, publishedVersion: 3, updatedAt: '2026-07-24 09:10:00' },
  { id: 6, dataSourceId: 4, interfaceCode: 'LOCAL_ENTERPRISE', interfaceName: '本地企业数据查询', servicePath: '/api/v1/local/enterprise', httpMethod: 'POST', upstreamUrl: 'mysql://edsp_demo/local_enterprise', status: 'PUBLISHED', currentVersion: 1, publishedVersion: 1, updatedAt: '2026-07-22 11:00:00' }
]

const requestMappings: FieldMapping[] = [
  { id: 1, direction: 'REQUEST', sourcePath: 'creditCode', targetPath: 'credit_code', transformType: 'DIRECT', transformConfig: {}, required: true, sortOrder: 10 },
  { id: 2, direction: 'REQUEST', sourcePath: 'queryReason', targetPath: 'query_reason', transformType: 'DIRECT', transformConfig: {}, defaultValue: '行内业务查询', required: false, sortOrder: 20 }
]

const responseMappings: FieldMapping[] = [
  { id: 3, direction: 'RESPONSE', sourcePath: 'data.qymc', targetPath: 'data.enterpriseName', transformType: 'DIRECT', transformConfig: {}, required: true, sortOrder: 10 },
  { id: 4, direction: 'RESPONSE', sourcePath: 'data.credit_code', targetPath: 'data.creditCode', transformType: 'DIRECT', transformConfig: {}, required: true, sortOrder: 20 },
  { id: 5, direction: 'RESPONSE', sourcePath: 'data.fddbr', targetPath: 'data.legalPerson', transformType: 'DIRECT', transformConfig: {}, required: false, sortOrder: 30 },
  { id: 6, direction: 'RESPONSE', sourcePath: 'data.xingbie', targetPath: 'data.sex', transformType: 'ENUM', transformConfig: { '1': 'MALE', '2': 'FEMALE' }, defaultValue: 'UNKNOWN', required: false, sortOrder: 40 }
]

export let interfaceDetail: InterfaceDetail = {
  summary: interfaces[0],
  requestSchema: { type: 'object', required: ['creditCode'], properties: { creditCode: { type: 'string', title: '统一社会信用代码' }, queryReason: { type: 'string', title: '查询原因' } } },
  responseSchema: { type: 'object', properties: { enterpriseName: { type: 'string' }, creditCode: { type: 'string' }, legalPerson: { type: 'string' }, sex: { type: 'string' } } },
  requestExample: { creditCode: '91320583MA1EXAMPLE', queryReason: '授信准入' },
  upstreamRequestExample: { credit_code: '91320583MA1EXAMPLE', query_reason: '授信准入' },
  upstreamResponseExample: { result: { code: '0000', message: 'success' }, data: { qymc: '昆山示例科技有限公司', credit_code: '91320583MA1EXAMPLE', fddbr: '张三', xingbie: '1' } },
  standardResponseExample: { code: 'EXT-SUCCESS', message: '查询成功', resultStatus: 'HIT', data: { enterpriseName: '昆山示例科技有限公司', creditCode: '91320583MA1EXAMPLE', legalPerson: '张三', sex: 'MALE' } },
  timeoutMs: 3000,
  vendorCodePath: 'result.code',
  retryCount: 0,
  mappings: [...requestMappings, ...responseMappings],
  securityPolicy: {
    interfaceId: 1,
    enabled: true,
    encryptMode: 'AES_256_GCM',
    signatureMode: 'HMAC_SHA256',
    requestFields: 'credit_code',
    encryptionKeyReference: 'config://edsp.security.keys.enterprise-encryption',
    signingKeyReference: 'config://edsp.security.keys.enterprise-signing',
    signatureField: 'signature',
    description: '演示：请求字段加密后签名，密钥仅保存引用。'
  },
  responseCodes: [
    { id: 1, vendorCode: '0000', standardCode: 'EXT-SUCCESS', resultStatus: 'HIT', retryable: false, billable: true },
    { id: 2, vendorCode: '1001', standardCode: 'EXT-NO-DATA', resultStatus: 'MISS', retryable: false, billable: false },
    { id: 3, vendorCode: '9999', standardCode: 'EXT-VENDOR-ERROR', resultStatus: 'ERROR', retryable: true, billable: false }
  ]
}

export function getInterfaceDetail(id: number): InterfaceDetail {
  const result = structuredClone(interfaceDetail)
  const summary = interfaces.find(item => item.id === id)
  if (summary) result.summary = structuredClone(summary)
  return result
}

const getPath = (value: Record<string, any>, path: string) => path.split('.').reduce<any>((current, key) => current?.[key], value)
const setPath = (value: Record<string, any>, path: string, next: unknown) => {
  const parts = path.split('.')
  let cursor = value
  parts.slice(0, -1).forEach((part) => { cursor = cursor[part] ??= {} })
  cursor[parts[parts.length - 1]] = next
}

export function runMockTest(request: Record<string, unknown>, response: Record<string, unknown>): TestResult {
  const upstreamRequest: Record<string, unknown> = {}
  requestMappings.forEach((mapping) => setPath(upstreamRequest, mapping.targetPath, getPath(request, mapping.sourcePath) ?? mapping.defaultValue))
  const data: Record<string, unknown> = {}
  responseMappings.forEach((mapping) => {
    let value = getPath(response, mapping.sourcePath) ?? mapping.defaultValue
    if (mapping.transformType === 'ENUM') value = mapping.transformConfig[String(value)] ?? mapping.defaultValue
    setPath(data, mapping.targetPath, value)
  })
  const standardResponse = { code: 'EXT-SUCCESS', message: '查询成功', resultStatus: 'HIT', traceId: 'DEMO202607240001', data: (data as any).data }
  const gatewayRequest = { method: 'POST', path: '/api/v1/enterprise/basic', headers: { 'X-App-Id': 'DEMO_DOWNSTREAM_SYSTEM' }, body: request }
  return {
    upstreamRequest,
    upstreamResponse: response,
    standardResponse,
    steps: [
      { name: '请求Schema校验', status: '通过', detail: '必填字段与类型校验完成', durationMs: 1 },
      { name: '请求字段映射', status: '通过', detail: `生成上游请求字段 ${Object.keys(upstreamRequest).length} 个`, durationMs: 2 },
      { name: '认证与加解密', status: '通过', detail: '使用数据源认证配置引用', durationMs: 3 },
      { name: '响应解析', status: '通过', detail: '供应商返回码 0000', durationMs: 6 },
      { name: '字段标准化', status: '通过', detail: '生成平台统一响应', durationMs: 7 }
    ],
    traceNodes: [
      { key: 'DOWNSTREAM', name: '下游调用系统', category: 'CLIENT', status: 'SUCCESS', detail: '模拟外围系统发起标准请求', durationMs: 1, input: { systemCode: 'DEMO_DOWNSTREAM_SYSTEM', requestBody: request }, output: gatewayRequest },
      { key: 'GATEWAY', name: '统一 Gateway', category: 'PLATFORM', status: 'SUCCESS', detail: '认证、授权、配额与路由完成', durationMs: 1, input: gatewayRequest, output: { route: 'enterprise', body: request } },
      { key: 'SCHEMA', name: 'Schema 校验', category: 'PLATFORM', status: 'SUCCESS', detail: '请求结构校验通过', durationMs: 1, input: request, output: { valid: true, body: request } },
      { key: 'REQUEST_MAPPING', name: '请求标准化', category: 'PLATFORM', status: 'SUCCESS', detail: '转换为供应商字段', durationMs: 2, input: request, output: upstreamRequest },
      { key: 'SECURITY', name: '安全处理', category: 'PLATFORM', status: 'SUCCESS', detail: '装载认证与安全策略', durationMs: 1, input: upstreamRequest, output: { algorithm: 'PASSTHROUGH_DEMO', body: upstreamRequest } },
      { key: 'DATA_SOURCE', name: '数据源应用', category: 'ADAPTER', status: 'SUCCESS', detail: '独立适配器执行协议转换', durationMs: 1, input: upstreamRequest, output: { endpoint: 'vendor/api', body: upstreamRequest } },
      { key: 'VENDOR', name: '上游供应商', category: 'EXTERNAL', status: 'SUCCESS', detail: '供应商响应完成', durationMs: 80, input: upstreamRequest, output: response },
      { key: 'RESPONSE_MAPPING', name: '响应标准化', category: 'PLATFORM', status: 'SUCCESS', detail: '字段与返回码标准化', durationMs: 7, input: response, output: standardResponse },
      { key: 'BILLING', name: '计量计费', category: 'PLATFORM', status: 'SUCCESS', detail: '调用明细已记录', durationMs: 1, input: { traceId: standardResponse.traceId }, output: { recorded: true, billable: true } }
    ]
  }
}

export const validation: ValidationResult = { valid: true, errors: [], warnings: ['当前为TEST环境，发布后仅测试应用可调用'] }
