import type { MonthlyFact, ReportType } from '@/types/monthlyReport'

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
export function comparison(current: string, previous: string | null, growth: string | null, type: ReportType = 'month'): string {
  const prev = { week: '上周', month: '上月', year: '上年' }[type]
  const now = { week: '本周', month: '本月', year: '本年' }[type]
  if (previous === null) return `${prev}无记录，暂不比较`
  if (Number(previous) === 0) return Number(current) > 0 ? `${now}新增` : `与${prev}持平`
  return growth == null ? '暂不比较' : `${type === 'year' ? '较上年' : '环比'} ${Number(growth) > 0 ? '+' : ''}${growth}%`
}
const valueLabels: Record<string, string> = {
  income: '收入', expense: '消费', fees: '手续费', balance: '结余', amount: '金额/预算', used: '已用',
  excess: '超额', excessPercentage: '超额比例 %', previousAmount: '上月金额', increase: '增加额', growth: '增幅 %',
  count: '笔数', previousCount: '上月笔数', average: '平均单笔', previousAverage: '上月单笔', combinedAmount: '合计', share: '占比 %'
}
export function factEvidence(fact: MonthlyFact, type: ReportType = 'month'): string {
  const prev = { week: '上周', month: '上月', year: '上年' }[type]
  return Object.entries(fact.values).map(([key, value]) => `${(valueLabels[key] ?? key).replace('上月', prev)}：${value ?? '暂无'}`).join(' · ')
}
const errors: Record<string, string> = {
  INVALID_URL: '仅支持 HTTPS 服务根地址，不能含凭据、查询参数或片段。',
  INVALID_CONFIG: '请检查服务地址和模型配置。', INVALID_KEY: '请先输入有效的 API Key。',
  STORAGE_FAILED: '本地 AI 数据读写失败，操作尚未确认成功；请检查磁盘状态并重新读取配置和已保存报告。',
  CONFIG_CHANGED: '配置已变化，请重新预览并确认。', CONSENT_REQUIRED: '请先预览摘要并明确确认本次发送。',
  UNAVAILABLE: 'AI 服务尚未配置，请检查连接设置。', BUSY: '已有请求正在处理，请稍后再试。',
  STALE: '账单或报告已变化，请重新预览并确认。', CANCELLED: '已停止当前调用；已发请求无法保证从服务商撤回。',
  NO_DATA: '所选周期没有可分析的流水，未调用 AI。',
  TIMEOUT: '请求超时，可能已计费；请先重新读取已保存报告，再决定是否重试。',
  NETWORK: '无法连接 AI 服务，请检查网络和服务地址。', BACKEND: '本地后端尚未就绪或正在恢复数据，请检查服务状态后重试。',
  HTTP_401: 'AI 服务鉴权失败，请检查 API Key。', HTTP_403: 'AI 服务拒绝访问，请检查密钥权限。',
  HTTP_429: 'AI 服务限额或频率受限，请检查额度后手动重试。',
  INVALID_OUTPUT: 'AI 响应不符合约定，未保存本次报告，已有报告保持不变。', INVALID_FACTS: 'AI 引用了无效的事实，已拒绝该解读。',
  INVALID_JSON: 'AI 未返回有效 JSON，请检查服务是否兼容。', REDIRECT_REJECTED: '服务返回了重定向，已阻止密钥转发，请检查根地址。',
  SUMMARY_TOO_LARGE: '统计摘要超过安全上限，未发送。', OUTPUT_TOO_LARGE: 'AI 响应超过安全上限。'
}
export const aiError = (code?: string | null) => errors[code ?? ''] ?? 'AI 请求失败，已有报告保持不变；请核对服务后手动重试。'
/**
 * 从抛出的错误提取展示文案：AI 调用改走 HTTP 后，后端已将中文提示放入抛错（ApiError extends Error）的 message，
 * 直接取用即可；不泄露供应商响应正文。
 */
export function aiFail(e: unknown, fallback = 'AI 请求失败，已有报告保持不变；请核对服务后手动重试。'): string {
  if (e && typeof e === 'object') {
    const error = e as { code?: string; isAxiosError?: boolean }
    if (error.isAxiosError) return error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT'
      ? '本地接口等待超时，报告可能已保存；请先重新读取已保存报告，勿重复发送。'
      : '无法连接本地后端，请确认 Java 服务已启动，并检查开发代理地址。'
    if (error.code && errors[error.code]) return errors[error.code]
  }
  return e instanceof Error && e.message ? e.message : fallback
}
