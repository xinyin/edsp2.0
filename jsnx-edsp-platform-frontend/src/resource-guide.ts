import type { FieldMapping, ResponseCodeMapping, TestResult } from './types'

export const guideAdvice: Record<string, { objective: string; tasks: string[]; tip: string }> = {
  overview: { objective: '先确认谁来调用、平台再调用谁。', tasks: ['核对上方的行内标准服务路径。', '填写上游相对路径，或选择单接口覆盖并填入完整地址。', '核对下方完整地址预览；超时默认继承，无特殊要求无需调整。'], tip: '保存只更新草稿，不会改变线上正在使用的接口。' },
  request: { objective: '按需定义“行内标准请求”和“行内标准响应”的转换规则。', tasks: ['字段名称和路径一致时保持为空，平台会自动按原路径一对一透传。', '仅在字段名称、路径、枚举或安全处理存在差异时，点击“配置字段转换”生成规则。', '切换“响应标准化”，需要适配供应商字段时再配置目标字段。'], tip: '空映射不是未完成，而是明确的自动透传模式；保存后即可联调和发布。' },
  codes: { objective: '让调用方无需理解不同上游服务的结果码。', tasks: ['根据上游接口文档填写原始返回码及对应的平台标准码。', '区分 HIT（命中）、MISS（无数据）、ERROR（异常）。', '确认每个结果是否参与计量、是否允许重试。'], tip: '不能把“无数据”当作“调用异常”；具体语义以上游接口文档为准。' },
  security: { objective: '仅在上游接口要求加密或签名时配置。', tasks: ['普通透传接口保持关闭，直接继续到链路验证。', '如需安全处理，开启策略并指定字段、算法和密钥引用。', '在下一步查看安全节点，核对实际送往上游的报文。'], tip: '算法必须来自后台注册目录并经过安全评审；密钥只保存配置引用，不在页面留存明文。' },
  test: { objective: '验证标准请求转换、响应标准化和结果码规则。', tasks: ['先执行“标准编排验证”，使用上游响应样例核对双向转换。', '服务上线并完成访问授权后，再执行“ESB 完整链路验收”。', '点击圆形节点查看每一步实际入参与出参。'], tip: '上游接口验证结果可由绑定的资源 API 复用；标准编排验证通过后才能上线，完整链路验收在上线并授权后进行。' },
  release: { objective: '校验当前草稿并上线，再为调用系统授权。', tasks: ['检查上游验证和标准编排验证结果。', '填写变更说明并直接上线当前版本。', '上线后在 API 访问授权中配置调用系统、配额和有效期。'], tip: '审批扩展能力仍保留；当前阶段直接生成发布快照并原子激活执行计划。' }
}

export function isHttpEndpoint(value: string) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !!url.hostname }
  catch { return false }
}

export function buildResourceGuide(input: { upstreamUrl: string; servicePath: string; mappings: FieldMapping[]; responseCodes: ResponseCodeMapping[]; securityEnabled: boolean; status: string; currentVersion: number; publishedVersion?: number | null; orchestrationVerified: boolean }) {
  const requestRows = input.mappings.filter(row => row.direction === 'REQUEST')
  const responseRows = input.mappings.filter(row => row.direction === 'RESPONSE')
  const requestCount = requestRows.filter(row => row.sourcePath.trim() && row.targetPath.trim()).length
  const responseCount = responseRows.filter(row => row.sourcePath.trim() && row.targetPath.trim()).length
  const mappingsComplete = [...requestRows, ...responseRows].every(row => row.sourcePath.trim() && row.targetPath.trim())
  const codesReady = input.responseCodes.length > 0 && input.responseCodes.every(row => row.vendorCode.trim() && row.standardCode.trim())
  const published = input.status === 'PUBLISHED' && input.publishedVersion === input.currentVersion
  const passThrough = requestRows.length === 0 && responseRows.length === 0
  return [
    { key: 'overview', index: '01', title: '定义接口', hint: '路径与上游连接', done: isHttpEndpoint(input.upstreamUrl) && !!input.servicePath.trim(), state: '连接配置' },
    { key: 'request', index: '02', title: '字段标准化', hint: passThrough ? '请求、响应均透传' : `请求 ${requestRows.length ? requestCount : '透传'} · 响应 ${responseRows.length ? responseCount : '透传'}`, done: passThrough || mappingsComplete, state: passThrough ? '无需字段转换' : mappingsComplete ? '按方向转换' : '存在未完成字段' },
    { key: 'codes', index: '03', title: '统一返回码', hint: `${input.responseCodes.length} 条结果码规则`, done: codesReady, state: '结果与计费语义' },
    { key: 'security', index: '04', title: '安全策略', hint: input.securityEnabled ? '已配置 · 待联调核对' : '可选 · 默认不启用', done: true, state: input.securityEnabled ? '策略已保存' : '无需配置可跳过' },
    { key: 'test', index: '05', title: '链路验证', hint: input.orchestrationVerified ? '当前草稿编排验证通过' : '验证转换与标准化', done: input.orchestrationVerified, state: '发布前编排验证' },
    { key: 'release', index: '06', title: '服务上线', hint: published ? '当前版本已上线' : '校验 → 上线 → 授权', done: published, state: '发布后生效' }
  ]
}

export function isSuccessfulE2E(result: TestResult, mode: string) {
  const required = result.traceNodes.some(node => node.key === 'ESB_QUEUE')
    ? ['ESB_QUEUE', 'ESB_ADAPTER', 'GATEWAY', 'DATA_APPLICATION', 'SUPPLIER', 'STANDARD_RESPONSE']
    : ['DOWNSTREAM', 'VENDOR', 'RESPONSE_MAPPING']
  return mode === 'E2E' && required.every(key => result.traceNodes.some(node => node.key === key && node.status === 'SUCCESS'))
    && result.traceNodes.every(node => ['SUCCESS', 'SKIPPED'].includes(node.status))
    && result.standardResponse.resultStatus !== 'ERROR'
}

export function requestDeadlineMs(upstreamTimeoutMs: number) {
  const upstream = Number.isFinite(upstreamTimeoutMs) ? upstreamTimeoutMs : 3000
  return Math.min(125_000, Math.max(10_000, upstream + 5_000))
}
