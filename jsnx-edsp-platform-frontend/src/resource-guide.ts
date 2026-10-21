import type { FieldMapping, ResponseCodeMapping, ResponsePolicy, TestResult } from './types'

export const guideAdvice: Record<string, { objective: string; tasks: string[]; tip: string }> = {
  overview: { objective: '先确认谁来调用、平台再调用谁。', tasks: ['核对上方的行内标准服务路径。', '填写上游相对路径，或选择单接口覆盖并填入完整地址。', '核对下方完整地址预览；超时默认继承，无特殊要求无需调整。'], tip: '保存只更新草稿，不会改变线上正在使用的接口。' },
  request: { objective: '按需定义请求转换和响应封装。', tasks: ['请求字段一致时可保持空映射。', '响应可选择字段映射或将整份供应商报文放入 data。'], tip: '响应封装方式随草稿保存，发布后才影响线上调用。' },
  codes: { objective: '统一平台结果状态。', tasks: ['有业务码时按供应商返回码映射。', '无业务码时可按 HTTP 成功判定。'], tip: '非 2xx 调用仍按上游错误处理。' },
  security: { objective: '仅在上游接口要求加密或签名时配置。', tasks: ['普通透传接口保持关闭，直接继续到链路测试。', '如需安全处理，开启策略并指定字段、算法和密钥引用。', '在下一步查看安全节点，核对实际送往上游的报文。'], tip: '算法必须来自后台注册目录并经过安全评审；密钥只保存配置引用，不在页面留存明文。' },
  test: { objective: '按需测试当前草稿的实际业务调用。', tasks: ['服务编排测试会经资源提供商处理应用真实调用上游。', '全链路测试会经过模拟 ESB 和 Gateway 的内部测试通道。', '两类测试都不依赖发布状态和业务授权，也不决定能否发布。'], tip: '测试是独立诊断工具，不生成发布凭证；是否发布由开发人员根据测试结果自行决策。' },
  release: { objective: '完成必要配置校验后发布，再为调用系统授权。', tasks: ['平台自动检查配置结构是否合法。', '填写变更说明并发布当前草稿版本。', '发布后在 API 访问授权中配置调用系统、配额和有效期。'], tip: '首次发布为 V1；同一草稿多次保存不增加发布版本，下次发布才进入 V2。历史已发布版本号保留。' }
}

export function isHttpEndpoint(value: string) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !!url.hostname }
  catch { return false }
}

export function buildResourceGuide(input: { upstreamUrl: string; servicePath: string; mappings: FieldMapping[]; responseCodes: ResponseCodeMapping[]; responsePolicy?: ResponsePolicy; securityEnabled: boolean; status: string; currentVersion: number; publishedVersion?: number | null; orchestrationVerified: boolean }) {
  const requestRows = input.mappings.filter(row => row.direction === 'REQUEST')
  const responseRows = input.mappings.filter(row => row.direction === 'RESPONSE')
  const requestCount = requestRows.filter(row => row.sourcePath.trim() && row.targetPath.trim()).length
  const responseCount = responseRows.filter(row => row.sourcePath.trim() && row.targetPath.trim()).length
  const wholeBody = input.responsePolicy?.dataMode === 'WHOLE_BODY'
  const mappingsComplete = [...requestRows, ...(wholeBody ? [] : responseRows)].every(row => row.sourcePath.trim() && row.targetPath.trim())
  const codesReady = input.responsePolicy?.codeMode === 'HTTP_SUCCESS'
    || input.responseCodes.length > 0 && input.responseCodes.every(row => row.vendorCode.trim() && row.standardCode.trim())
  const published = input.status === 'PUBLISHED' && input.publishedVersion === input.currentVersion
  const passThrough = requestRows.length === 0 && (wholeBody || responseRows.length === 0)
  return [
    { key: 'overview', index: '01', title: '定义接口', hint: '路径与上游连接', done: isHttpEndpoint(input.upstreamUrl) && !!input.servicePath.trim(), state: '连接配置' },
    { key: 'request', index: '02', title: '字段标准化', hint: passThrough ? '请求无映射 · 响应整报文' : `请求 ${requestRows.length ? requestCount : '无映射'} · 响应 ${wholeBody ? '整报文' : responseRows.length ? responseCount : '无映射'}`, done: passThrough || mappingsComplete, state: passThrough ? '无需字段转换' : mappingsComplete ? '按方向转换' : '存在未完成字段' },
    { key: 'codes', index: '03', title: '统一返回码', hint: input.responsePolicy?.codeMode === 'HTTP_SUCCESS' ? '按 HTTP 成功判定' : `${input.responseCodes.length} 条结果码规则`, done: codesReady, state: '结果与计费语义' },
    { key: 'security', index: '04', title: '安全策略', hint: input.securityEnabled ? '已配置 · 待联调核对' : '可选 · 默认不启用', done: true, state: input.securityEnabled ? '策略已保存' : '无需配置可跳过' },
    { key: 'test', index: '05', title: '链路测试', hint: input.orchestrationVerified ? '当前服务编排测试通过' : '可选 · 真实调用上游', done: true, state: input.orchestrationVerified ? '已完成辅助测试' : '不影响发布' },
    { key: 'release', index: '06', title: '服务发布', hint: published ? '当前版本已发布' : '校验 → 发布 → 授权', done: published, state: '发布后生效' }
  ]
}

export function isSuccessfulE2E(result: TestResult, mode: string) {
  const required = result.traceNodes.some(node => node.key === 'ESB_ADAPTER')
    ? ['ESB_ADAPTER', 'GATEWAY', 'DATA_SOURCE', 'VENDOR', 'RESPONSE_MAPPING', 'STANDARD_RESPONSE']
    : ['DOWNSTREAM', 'VENDOR', 'RESPONSE_MAPPING']
  return mode === 'E2E' && required.every(key => result.traceNodes.some(node => node.key === key && node.status === 'SUCCESS'))
    && result.traceNodes.every(node => ['SUCCESS', 'SKIPPED'].includes(node.status))
    && result.standardResponse.resultStatus !== 'ERROR'
}

export function requestDeadlineMs(upstreamTimeoutMs: number) {
  const upstream = Number.isFinite(upstreamTimeoutMs) ? upstreamTimeoutMs : 3000
  return Math.min(125_000, Math.max(10_000, upstream + 5_000))
}
