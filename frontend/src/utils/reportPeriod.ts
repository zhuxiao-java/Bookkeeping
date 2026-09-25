import type { ReportType } from '@/types/monthlyReport'
import { lastClosedMonth, monthRange } from './monthlyReport'

export const reportLabels: Record<ReportType, string> = { week: '周报', month: '月报', year: '年报' }
export const currentLabels: Record<ReportType, string> = { week: '本周', month: '本月', year: '本年' }
export const previousLabels: Record<ReportType, string> = { week: '上周', month: '上月', year: '上年' }
export const historyLabels: Record<ReportType, string> = { week: '前四个完整周', month: '前三个自然月', year: '' }
export const nextLabels: Record<ReportType, string> = { week: '下周', month: '下月', year: '下一年' }
const dateKey = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
export function isReportType(value: unknown): value is ReportType { return value === 'week' || value === 'month' || value === 'year' }
export function lastClosedPeriod(type: ReportType, now = new Date()): string {
  if (type === 'month') return lastClosedMonth(now)
  if (type === 'year') return String(now.getFullYear() - 1)
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  monday.setDate(monday.getDate() - (monday.getDay() + 6) % 7 - 7)
  return dateKey(monday)
}
export function periodRange(type: ReportType, key: string): [string, string] {
  if (type === 'month') return monthRange(key)
  if (type === 'year') return [`${key}-01-01`, `${key}-12-31`]
  const [year, month, day] = key.split('-').map(Number)
  return [key, dateKey(new Date(year, month - 1, day + 6))]
}
export function validPeriod(type: ReportType, key: string, now = new Date()): boolean {
  if (Number(key.slice(0, 4)) < 1900 || key > lastClosedPeriod(type, now)) return false
  if (type === 'year') return /^\d{4}$/.test(key)
  if (type === 'month') return /^\d{4}-(0[1-9]|1[0-2])$/.test(key)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(key)) return false
  const [y, m, d] = key.split('-').map(Number), date = new Date(y, m - 1, d)
  return dateKey(date) === key && date.getDay() === 1
}
export function periodTitle(type: ReportType, key: string): string {
  if (type === 'year') return `${key} 年`
  if (type === 'month') return `${key.slice(0, 4)} 年 ${Number(key.slice(5))} 月`
  return periodRange(type, key).join(' ~ ')
}
