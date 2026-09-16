import { describe, it, expect } from 'vitest'
import { computeReportAgg, EMPTY_REPORT_AGG, type ReportAggInput } from '@/utils/reportAgg'
import type { Account, Category, Transaction } from '@/types/model'

const txBase: Transaction = {
  id: 1,
  type: 'expense',
  amount: 0,
  fee: 0,
  accountId: 1,
  toAccountId: null,
  categoryId: 1,
  transactionDate: '2026-09-16T00:00:00',
  note: '',
  tags: '[]'
}
const tx = (p: Partial<Transaction>): Transaction => ({ ...txBase, ...p })

const catBase: Category = {
  id: 1,
  name: '',
  parentId: null,
  icon: '',
  color: '',
  type: 'expense',
  sortOrder: 0,
  archived: 0
}
const cat = (p: Partial<Category>): Category => ({ ...catBase, ...p })

const accBase: Account = {
  id: 1,
  name: '',
  type: 'cash',
  initialBalance: 0,
  currentBalance: 0,
  currency: 'CNY',
  archived: 0
}
const acc = (p: Partial<Account>): Account => ({ ...accBase, ...p })

// 餐饮(1) → 外卖(2) → 某店(3)；交易分布在 8 月与 9 月
const categories = [
  cat({ id: 1, name: '餐饮' }),
  cat({ id: 2, name: '外卖', parentId: 1 }),
  cat({ id: 3, name: '某店', parentId: 2 })
]

function makeInput(overrides: Partial<ReportAggInput> = {}): ReportAggInput {
  return {
    transactions: [
      tx({ id: 1, type: 'expense', amount: 50, categoryId: 3, transactionDate: '2026-09-10T00:00:00' }),
      tx({ id: 2, type: 'income', amount: 1000, categoryId: 1, transactionDate: '2026-09-15T00:00:00' }),
      // 区间外（8 月）：仅参与余额期初，不计入 range/summary/category
      tx({ id: 3, type: 'expense', amount: 20, categoryId: 3, transactionDate: '2026-08-10T00:00:00' })
    ],
    accounts: [acc({ id: 1, name: '现金', initialBalance: 0 })],
    categories,
    start: '2026-09-01',
    end: '2026-09-30',
    granularity: 'day',
    incomeExpense: 'expense',
    categoryGroupBy: 'root',
    compareRange: ['2026-08-01', '2026-08-31'],
    ...overrides
  }
}

describe('EMPTY_REPORT_AGG', () => {
  it('初始空结果各图表可安全渲染空态', () => {
    expect(EMPTY_REPORT_AGG.rangeCount).toBe(0)
    expect(EMPTY_REPORT_AGG.trend).toEqual([])
    expect(EMPTY_REPORT_AGG.compare).toBeNull()
  })
})

describe('computeReportAgg', () => {
  const r = computeReportAgg(makeInput())

  it('区间过滤：仅统计 9 月两笔', () => {
    expect(r.rangeCount).toBe(2)
  })

  it('汇总收支（transfer 不计，区间外不计）', () => {
    expect(r.summary).toEqual({ income: 1000, expense: 50 })
  })

  it('分类按 root 归并：孙分类金额上卷到一级', () => {
    expect(r.category).toHaveLength(1)
    expect(r.category[0]).toMatchObject({ categoryId: 1, name: '餐饮', amount: 50, count: 1 })
  })

  it('趋势按日分桶', () => {
    expect(r.trend.map((b) => b.key)).toEqual(['2026-09-10', '2026-09-15'])
  })

  it('月度盈亏：区间仅 9 月一个月', () => {
    expect(r.monthly).toHaveLength(1)
    expect(r.monthly[0]).toMatchObject({ key: '2026-09', income: 1000, expense: 50, balance: 950 })
  })

  it('余额曲线用全量流水推期初：8 月支出使 9 月起点为 -20', () => {
    expect(r.balance.labels[0]).toBe('2026-09-01')
    expect(r.balance.series[0].data[0]).toBe(-20)
    // 9/10 支出 50 → -70；9/15 收入 1000 → 930
    expect(r.balance.series[0].data).toEqual([-20, -70, 930])
  })

  it('对比区间：current 为本区间，previous 为对比区间', () => {
    expect(r.compare).not.toBeNull()
    expect(r.compare!.current).toEqual({ income: 1000, expense: 50 })
    expect(r.compare!.previous).toEqual({ income: 0, expense: 20 })
  })

  it('compareRange 为 null 时不做对比', () => {
    const noCompare = computeReportAgg(makeInput({ compareRange: null }))
    expect(noCompare.compare).toBeNull()
  })

  it("categoryGroupBy='self' 时不归并，落在实际孙分类", () => {
    const self = computeReportAgg(makeInput({ categoryGroupBy: 'self' }))
    expect(self.category[0]).toMatchObject({ categoryId: 3, name: '某店', amount: 50 })
  })
})
