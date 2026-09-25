import { describe, expect, it } from 'vitest'
import { aiError, aiFail, cents, comparison, currencyName, factEvidence, lastClosedMonth, monthRange, simulate } from './monthlyReport'
import { lastClosedPeriod, validPeriod, periodRange, periodTitle } from './reportPeriod'

describe('月报自然月边界', () => {
  it('跨年默认上月、闰年及普通二月', () => {
    expect(lastClosedMonth(new Date(2025, 0, 1))).toBe('2024-12')
    expect(lastClosedMonth(new Date(2024, 2, 31))).toBe('2024-02')
    expect(monthRange('2024-02')).toEqual(['2024-02-01', '2024-02-29'])
    expect(monthRange('2023-02')).toEqual(['2023-02-01', '2023-02-28'])
    expect(monthRange('2024-12')).toEqual(['2024-12-01', '2024-12-31'])
  })
})
describe('周报与年报周期', () => {
  it('周日仍选上一完整周，周一切换，跨年按周一归档', () => {
    expect(lastClosedPeriod('week', new Date(2025, 0, 5, 23, 59))).toBe('2024-12-23')
    expect(lastClosedPeriod('week', new Date(2025, 0, 6))).toBe('2024-12-30')
    expect(lastClosedPeriod('year', new Date(2025, 0, 1))).toBe('2024')
    expect(periodRange('week', '2024-12-30')).toEqual(['2024-12-30', '2025-01-05'])
    expect(periodRange('year', '2024')).toEqual(['2024-01-01', '2024-12-31'])
    expect(periodTitle('week', '2024-12-30')).toContain('2025-01-05')
  })
  it('拒绝非周一、伪日期、未结束及1900年前的周期', () => {
    const now = new Date(2025, 0, 6)
    for (const key of ['2024-12-31', '2024-02-30', '2025-01-06', '1899-12-25']) expect(validPeriod('week', key, now)).toBe(false)
    expect(validPeriod('week', '2024-12-30', now)).toBe(true)
    expect(validPeriod('year', '2025', now)).toBe(false)
    expect(validPeriod('year', '2024', now)).toBe(true)
    expect(validPeriod('year', '1899', now)).toBe(false)
    expect(validPeriod('month', '2024-13', now)).toBe(false)
  })
  it('比较文案随周期切换，不带月度残留', () => {
    expect(comparison('20', null, null, 'week')).toContain('上周无记录')
    expect(comparison('20', '0', null, 'year')).toBe('本年新增')
    expect(comparison('120', '100', '20.00', 'year')).toBe('较上年 +20.00%')
  })
})
describe('本地目标试算', () => {
  it('以整数分保持大额精度并四舍五入', () => {
    expect(cents('0.1') + cents('0.20')).toBe(30n)
    expect(cents('9007199254740993.01')).toBe(900719925474099301n)
    expect(simulate('900.00', 30, 'count', '4')).toBe('120.00')
    expect(simulate('0.01', 2, 'count', '1')).toBe('0.01')
    expect(simulate('100.00', 3, 'count', '1')).toBe('33.33')
    expect(simulate('9007199254740993.01', 1, 'percent', '100')).toBe('9007199254740993.01')
    expect(simulate('900.00', 30, 'percent', '20')).toBe('180.00')
  })
  it('未输入、无消费笔数、越界或非整数不展示试算', () => {
    for (const value of ['', '0', '-1', '1.5', 'NaN', '31']) expect(simulate('900', 30, 'count', value)).toBeNull()
    expect(simulate('900', 30, 'percent', '101')).toBeNull()
    expect(simulate('900', 0, 'percent', '20')).toBeNull()
    expect(simulate('900', Number.MAX_SAFE_INTEGER + 1, 'count', '1')).toBeNull()
    for (const value of ['-1', '1e3', '0.001', 'NaN', '']) expect(() => cents(value)).toThrow()
  })
})
describe('比较、币种与本地事实展示', () => {
  it('缺失历史和零分母不产生无穷百分比', () => {
    expect(comparison('20.00', null, null)).toContain('上月无记录')
    expect(comparison('20.00', '0.00', null)).toBe('本月新增')
    expect(comparison('0.00', '0.00', null)).toBe('与上月持平')
    expect(comparison('120.00', '100.00', '20.00')).toBe('环比 +20.00%')
    expect(comparison('80.00', '100.00', '-20.00')).toBe('环比 -20.00%')
    expect(currencyName('UNKNOWN')).toBe('币种未知')
    expect(currencyName('EUR')).toBe('EUR')
  })
  it('依据取自本地数值，错误信息不回显供应商正文', () => {
    expect(factEvidence({ id: 'f', kind: 'top', title: '分类', currency: 'CNY', categoryId: 1,
      values: { amount: '900.00', count: '30', previousAmount: null }, suggestion: '查看' }))
      .toBe('金额/预算：900.00 · 笔数：30 · 上月金额：暂无')
    expect(aiFail({ isAxiosError: true, code: 'ERR_NETWORK' })).toContain('无法连接本地后端')
    expect(aiFail({ isAxiosError: true, code: 'ECONNABORTED' })).toContain('勿重复发送')
    expect(aiFail({ code: 'HTTP_401', message: '敏感正文' })).toContain('鉴权失败')
    expect(aiError('UNAVAILABLE')).not.toContain('Electron')
    expect(aiError('TIMEOUT')).toContain('可能已计费')
    expect(aiError('私密错误正文')).not.toContain('私密错误正文')
    // 抛错改走 HTTP 后取后端中文 message；无有效 message 时回退安全默认文案
    expect(aiFail(new Error('配置已变更，请重新确认后再试'))).toBe('配置已变更，请重新确认后再试')
    expect(aiFail('非错误对象')).toContain('已有报告保持不变')
  })
})
