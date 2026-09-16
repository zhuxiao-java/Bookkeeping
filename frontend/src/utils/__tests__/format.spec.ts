import { describe, it, expect } from 'vitest'
import {
  formatAmount,
  formatSignedAmount,
  toFen,
  fenToYuan,
  isValidAmountInput,
  sanitizeAmountInput,
  formatDateTime,
  formatDate,
  addDays,
  addYears,
  daysInclusive
} from '@/utils/format'

describe('formatAmount', () => {
  it('千分位 + 两位小数', () => {
    expect(formatAmount(1234.5)).toBe('1,234.50')
  })
  it('按小数位四舍五入', () => {
    expect(formatAmount(1234.567, 2)).toBe('1,234.57')
  })
  it('0 位小数不带小数点', () => {
    expect(formatAmount(1000, 0)).toBe('1,000')
  })
  it('null / 非法值降级为 0', () => {
    expect(formatAmount(null)).toBe('0.00')
    expect(formatAmount('abc')).toBe('0.00')
  })
})

describe('formatSignedAmount', () => {
  it('正数带 +，负数带 -', () => {
    expect(formatSignedAmount(12.3)).toBe('+12.30')
    expect(formatSignedAmount(-12.3)).toBe('-12.30')
  })
})

describe('金额分转换（浮点精度）', () => {
  it('元 → 分四舍五入为整数', () => {
    expect(toFen(12.34)).toBe(1234)
    expect(toFen('12.34')).toBe(1234)
    expect(toFen(null)).toBe(0)
  })
  it('0.1 + 0.2 的浮点误差被 toFen 消除', () => {
    // 直接相加得 0.30000000000000004，转分后应为精确的 30
    expect(toFen(0.1 + 0.2)).toBe(30)
    expect(toFen(0.1) + toFen(0.2)).toBe(30)
  })
  it('分 → 元', () => {
    expect(fenToYuan(1234)).toBe(12.34)
  })
})

describe('金额输入校验/过滤', () => {
  it('最多两位小数的合法输入', () => {
    expect(isValidAmountInput('12.34')).toBe(true)
    expect(isValidAmountInput('12')).toBe(true)
    expect(isValidAmountInput('12.345')).toBe(false)
    expect(isValidAmountInput('12.')).toBe(false)
    expect(isValidAmountInput('abc')).toBe(false)
  })
  it('过滤非法字符并截断到两位小数', () => {
    expect(sanitizeAmountInput('1a2.3.4')).toBe('12.34')
    expect(sanitizeAmountInput('¥99.999')).toBe('99.99')
  })
})

describe('日期格式化', () => {
  it('ISO → yyyy-MM-dd HH:mm', () => {
    expect(formatDateTime('2026-09-16T13:45:00')).toBe('2026-09-16 13:45')
    expect(formatDateTime(null)).toBe('-')
  })
  it('ISO → yyyy-MM-dd', () => {
    expect(formatDate('2026-09-16T13:45:00')).toBe('2026-09-16')
  })
})

describe('日期区间运算', () => {
  it('addDays 跨月/跨年', () => {
    expect(addDays('2026-09-16', 1)).toBe('2026-09-17')
    expect(addDays('2026-09-30', 1)).toBe('2026-10-01')
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31')
  })
  it('addYears 同比去年', () => {
    expect(addYears('2026-09-16', -1)).toBe('2025-09-16')
  })
  it('daysInclusive 含首尾，至少 1', () => {
    expect(daysInclusive('2026-09-01', '2026-09-30')).toBe(30)
    expect(daysInclusive('2026-09-16', '2026-09-16')).toBe(1)
    // 反向区间兜底为 1，不产生负数
    expect(daysInclusive('2026-09-30', '2026-09-01')).toBe(1)
  })
})
