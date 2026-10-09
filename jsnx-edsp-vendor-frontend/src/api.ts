export interface VendorApi {
  id: number; dataSourceId: number; dataSourceCode: string; dataSourceName: string
  apiCode: string; apiName: string; servicePath: string; httpMethod: string
  requestExample: Record<string, unknown>; responseBody: Record<string, unknown>
  responseCode: string; delayMs: number; status: string; version: number; updatedAt: string
}

export interface VendorDataSource {
  id: number; sourceCode: string; sourceName: string; description: string
  basePath: string; status: string; updatedAt: string; apis: VendorApi[]
}
export interface EnterpriseRecord { id: number; enterpriseCode: string; creditCode: string; enterpriseName: string; legalPerson?: string; sexCode?: string; riskLevel?: string; registrationStatus: string }

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { headers: { 'Content-Type': 'application/json' }, ...init })
  const text = await response.text()
  if (!response.ok) throw new Error(text || '请求失败')
  return text ? JSON.parse(text) as T : undefined as T
}

export const vendorApi = {
  dataSources: () => request<VendorDataSource[]>('/admin/data-sources'),
  createDataSource: (body: Record<string, unknown>) => request<VendorDataSource>('/admin/data-sources', { method: 'POST', body: JSON.stringify(body) }),
  updateDataSource: (id: number, body: Record<string, unknown>) => request<VendorDataSource>(`/admin/data-sources/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  createApi: (body: Record<string, unknown>) => request<VendorApi>('/admin/apis', { method: 'POST', body: JSON.stringify(body) }),
  updateApi: (id: number, body: Record<string, unknown>) => request<VendorApi>(`/admin/apis/${id}`, { method: 'PUT', body: JSON.stringify(body) })
  ,enterpriseRecords: () => request<EnterpriseRecord[]>('/admin/enterprise-records')
  ,saveEnterpriseRecord: (body: Record<string, unknown>) => request<EnterpriseRecord>('/admin/enterprise-records', { method: 'POST', body: JSON.stringify(body) })
}
