/**
 * 金额与日期格式化工具。
 * 金额参与统计计算时统一转"分"（整数）运算，避免浮点误差（需求文档 3.2）。
 */

/** 金额格式化：千分位 + 指定小数位（默认 2） */
export function formatAmount(
  amount: number | string | null | undefined,
  decimals = 2
): string {
  const num = Number(amount ?? 0)
  if (Number.isNaN(num)) return (0).toFixed(decimals)
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  })
}

/** 带 +/- 符号的金额展示：收入 +、支出 - */
export function formatSignedAmount(
  amount: number | string | null | undefined,
  decimals = 2
): string {
  const num = Number(amount ?? 0)
  const sign = num > 0 ? '+' : num < 0 ? '-' : ''
  return `${sign}${formatAmount(Math.abs(num), decimals)}`
}

/** 元 → 分（整数），用于聚合计算 */
export function toFen(amount: number | string | null | undefined): number {
  const num = Number(amount ?? 0)
  if (Number.isNaN(num)) return 0
  return Math.round(num * 100)
}

/** 分 → 元 */
export function fenToYuan(fen: number): number {
  return fen / 100
}

/** 金额（元，最多两位小数）字符串校验 */
export function isValidAmountInput(value: string): boolean {
  return /^\d+(\.\d{1,2})?$/.test(value.trim())
}

/** 金额输入过滤：仅保留数字与一个小数点，小数最多两位 */
export function sanitizeAmountInput(val: string): string {
  let s = val.replace(/[^\d.]/g, '')
  const firstDot = s.indexOf('.')
  if (firstDot >= 0) {
    s = s.slice(0, firstDot + 1) + s.slice(firstDot + 1).replace(/\./g, '')
  }
  const dot = s.indexOf('.')
  if (dot >= 0) s = s.slice(0, dot + 3)
  return s
}

/** yyyy-MM-ddTHH:mm:ss → yyyy-MM-dd HH:mm */
export function formatDateTime(iso?: string | null): string {
  if (!iso) return '-'
  return iso.replace('T', ' ').slice(0, 16)
}

/** yyyy-MM-ddTHH:mm:ss → yyyy-MM-dd */
export function formatDate(iso?: string | null): string {
  if (!iso) return '-'
  return iso.slice(0, 10)
}

/** 当前时间的 ISO 字符串（yyyy-MM-ddTHH:mm:ss），供交易日期默认值 */
export function nowIso(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

// —— 日期区间运算（报表区间对比 RP-02 用；均为本地日，yyyy-MM-dd）——

/** yyyy-MM-dd → 本地 Date（避免 UTC 偏移导致跨日） */
function parseYmd(ymd: string): Date {
  const [y, m, d] = ymd.slice(0, 10).split('-').map(Number)
  return new Date(y, (m || 1) - 1, d || 1)
}

/** Date → yyyy-MM-dd */
function toYmd(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** yyyy-MM-dd 偏移 n 天（n 可为负） */
export function addDays(ymd: string, n: number): string {
  const d = parseYmd(ymd)
  d.setDate(d.getDate() + n)
  return toYmd(d)
}

/** yyyy-MM-dd 偏移 n 年（n 可为负，用于同比去年同期） */
export function addYears(ymd: string, n: number): string {
  const d = parseYmd(ymd)
  d.setFullYear(d.getFullYear() + n)
  return toYmd(d)
}

/** [start, end] 闭区间的天数（含首尾，至少 1） */
export function daysInclusive(start: string, end: string): number {
  const ms = parseYmd(end).getTime() - parseYmd(start).getTime()
  return Math.max(1, Math.round(ms / 86400000) + 1)
}
