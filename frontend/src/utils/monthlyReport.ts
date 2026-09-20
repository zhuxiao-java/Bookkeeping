import type { AiReply, MonthlyFact } from '@/types/monthlyReport'

export function lastClosedMonth(now = new Date()): string {
  const date = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}
export function monthRange(month: string): [string, string] {
  const [year, m] = month.split('-').map(Number)
  return [`${month}-01`, `${month}-${String(new Date(year, m, 0).getDate()).padStart(2, '0')}`]
}
export function cents(value: string): bigint {
  if (!/^\d+(\.\d{1,2})?$/.test(value)) throw new Error('金额必须为非负十进制数，最多两位小数')
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}
function money(value: bigint): string { return `${value / 100n}.${String(value % 100n).padStart(2, '0')}` }
export function simulate(amount: string, count: number, mode: 'count' | 'percent', input: string): string | null {
  if (!input || !/^\d+$/.test(input) || !Number.isSafeInteger(count) || count < 1) return null
  const n = BigInt(input), total = cents(amount)
  const divisor = mode === 'count' ? BigInt(count) : 100n
  if (n < 1n || n > divisor) return null
  // 在整数分上计算并四舍五入；不先将大额金额转换为浮点数。
  return money((total * n * 2n + divisor) / (2n * divisor))
}
export const currencyName = (v: string) => ({ CNY: '人民币', DOLLAR: '美元', UNKNOWN: '币种未知' }[v] ?? v)
export function comparison(current: string, previous: string | null, growth: string | null): string {
  if (previous === null) return '上月无记录，暂不比较'
  if (Number(previous) === 0) return Number(current) > 0 ? '本月新增' : '与上月持平'
  return growth == null ? '暂不比较' : `环比 ${Number(growth) > 0 ? '+' : ''}${growth}%`
}
const valueLabels: Record<string, string> = {
  income: '收入', expense: '消费', fees: '手续费', balance: '结余', amount: '金额/预算', used: '已用',
  excess: '超额', excessPercentage: '超额比例 %', previousAmount: '上月金额', increase: '增加额', growth: '增幅 %',
  count: '笔数', previousCount: '上月笔数', average: '平均单笔', previousAverage: '上月单笔', combinedAmount: '合计', share: '占比 %'
}
export function factEvidence(fact: MonthlyFact): string {
  return Object.entries(fact.values).map(([key, value]) => `${valueLabels[key] ?? key}：${value ?? '暂无'}`).join(' · ')
}
const errors: Record<string, string> = {
  INVALID_URL: '仅支持 HTTPS 服务根地址，不能含凭据、查询参数或片段。',
  INVALID_CONFIG: '请检查服务地址和模型配置。', INVALID_KEY: '请先输入有效的 API Key。',
  STORAGE_FAILED: 'AI 设置保存失败，本次自动发送已关闭。请检查磁盘权限；旧授权可能仍在磁盘中，重启前请再次撤销并确认保存成功。',
  CONFIG_CHANGED: '配置已变化，请重新检查并授权。', CONSENT_REQUIRED: '请先预览摘要并明确授权本次发送。',
  UNAVAILABLE: 'AI 内部通道未启用，请在已配置内部通道的 Electron 中使用。', BUSY: '已有请求正在处理，请稍后再试。',
  STALE: '统计已变化，请刷新本地月报后重新预览。', CANCELLED: '已停止后续发送；已发请求无法保证从服务商撤回。',
  TIMEOUT: '请求超时，可能已计费；请核对后手动重试。', INTERRUPTED: '请求中断或结果不确定，可能已计费；请手动重试。',
  NETWORK: '无法连接 AI 服务，请检查网络和服务地址。', BACKEND: '本地任务服务未就绪或凭据不匹配，请重启桌面应用后重试。',
  HTTP_401: 'AI 服务鉴权失败，请检查 API Key。', HTTP_403: 'AI 服务拒绝访问，请检查密钥权限。',
  HTTP_429: 'AI 服务限额或频率受限，请检查额度后手动重试。',
  INVALID_OUTPUT: 'AI 响应结构不符合约定，基础月报仍可使用。', INVALID_FACTS: 'AI 引用了无效的事实，已拒绝该解读。',
  INVALID_JSON: 'AI 未返回有效 JSON，请检查服务是否兼容。', REDIRECT_REJECTED: '服务返回了重定向，已阻止密钥转发，请检查根地址。',
  SUMMARY_TOO_LARGE: '统计摘要过大，未发送；请使用本地月报。', OUTPUT_TOO_LARGE: 'AI 响应超过安全上限。'
}
export const aiError = (code?: string | null) => errors[code ?? ''] ?? 'AI 请求失败，基础月报仍可使用；请核对服务后手动重试。'
export async function unwrapAi<T>(request: Promise<AiReply<T>>): Promise<T> {
  const result = await request
  if (!result.ok) throw new Error(aiError(result.errorCode))
  return result.value
}
