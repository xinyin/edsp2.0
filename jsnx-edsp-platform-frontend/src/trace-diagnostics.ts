import type { TraceNode } from './types'

type ResponseCode = { vendorCode: string; resultStatus: string }

const failed = (node: TraceNode) => ['ERROR', 'FAILED', 'FAILURE'].includes(node.status)
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value)
  ? value as Record<string, unknown> : {}

function readPath(source: Record<string, unknown>, path: string): unknown {
  return path.replace(/^\$\./, '').split('.').reduce<unknown>((value, key) =>
    value && typeof value === 'object' ? (value as Record<string, unknown>)[key] : undefined, source)
}

export function describeTraceFailure(
  nodes: TraceNode[], standardResponse: Record<string, unknown> = {},
  vendorCodePath = '', responseCodes: ResponseCode[] = []
): { node?: TraceNode; reason: string } | null {
  const node = nodes.find(failed)
  if (!node && standardResponse.resultStatus !== 'ERROR') return null

  const detail = node?.detail?.trim() ?? ''
  const generic = !detail || ['完成供应商响应封装', '调用失败，请结合标准返回码定位', 'ESB 真实链路诊断节点'].includes(detail)
  if (!generic) return { node, reason: detail.replace(/^调用在该节点失败：/, '') }

  if (node?.key === 'RESPONSE_MAPPING' && vendorCodePath) {
    const upstream = object(object(node.input).vendorResponse)
    const rawCode = readPath(upstream, vendorCodePath)
    const vendorCode = rawCode == null ? '' : String(rawCode).trim()
    if (!vendorCode) return { node, reason: `未从上游响应读取到业务返回码（字段路径：${vendorCodePath}）。请核对字段路径；无业务码的接口可选择 HTTP 成功判定。` }
    if (!responseCodes.some(item => item.vendorCode === vendorCode)) {
      return { node, reason: `上游业务返回码 ${vendorCode} 未配置结果码映射。请在结果码映射中补充该码值。` }
    }
    return { node, reason: `上游业务返回码 ${vendorCode} 被配置为异常状态。请核对供应商响应与结果码映射。` }
  }

  const output = object(node?.output)
  const message = output.message ?? standardResponse.message
  if (typeof message === 'string' && message.trim()) {
    const code = output.code ?? standardResponse.code
    return { node, reason: code ? `${message}（返回码：${String(code)}）` : message }
  }
  return { node, reason: detail || '节点返回异常，请查看该节点的输入和输出。' }
}
