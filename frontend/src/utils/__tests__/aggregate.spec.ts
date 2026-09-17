import { describe, it, expect, vi } from 'vitest'
import { withChartTheme } from '@/utils/chartTheme'
import {
  inDateRange,
  inMonth,
  bucketizeTrend,
  monthlyBalance,
  sumIncomeExpense,
  aggregateByCategory,
  resolveRootCategory,
  accountBalanceSeries,
  adaptTrend,
  adaptCategory,
  adaptMonthly
} from '@/utils/aggregate'
import type {
  Account,
  Category,
  Transaction,
  TransactionTrendItem,
  TransactionCategoryStat,
  TransactionMonthlyInfo
} from '@/types/model'

// —— 测试夹具工厂（补齐必填字段，用例只覆盖关心的属性）——
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

describe('区间判定', () => {
  it('inDateRange 取日期部分闭区间比较', () => {
    expect(inDateRange('2026-09-16T23:59:00', '2026-09-01', '2026-09-30')).toBe(true)
    expect(inDateRange('2026-09-01T00:00:00', '2026-09-01', '2026-09-30')).toBe(true)
    expect(inDateRange('2026-10-01T00:00:00', '2026-09-01', '2026-09-30')).toBe(false)
  })
  it('inMonth 按 yyyy-MM 前缀匹配', () => {
    expect(inMonth('2026-09-16T00:00:00', 2026, 9)).toBe(true)
    expect(inMonth('2026-08-16T00:00:00', 2026, 9)).toBe(false)
  })
})

describe('sumIncomeExpense', () => {
  it('分别汇总收支，transfer 不计入', () => {
    const s = sumIncomeExpense([
      tx({ type: 'income', amount: 100 }),
      tx({ type: 'income', amount: 50.5 }),
      tx({ type: 'expense', amount: 30 }),
      tx({ type: 'transfer', amount: 999 })
    ])
    expect(s.income).toBe(150.5)
    expect(s.expense).toBe(30)
  })
})

describe('bucketizeTrend', () => {
  it('按日分桶，收支各自求和，key 升序，排除 transfer', () => {
    const buckets = bucketizeTrend(
      [
        tx({ type: 'expense', amount: 30, transactionDate: '2026-09-14T10:00:00' }),
        tx({ type: 'expense', amount: 20, transactionDate: '2026-09-14T18:00:00' }),
        tx({ type: 'income', amount: 100, transactionDate: '2026-09-15T09:00:00' }),
        tx({ type: 'transfer', amount: 500, transactionDate: '2026-09-16T09:00:00' })
      ],
      'day'
    )
    expect(buckets).toHaveLength(2)
    expect(buckets[0].key).toBe('2026-09-14')
    expect(buckets[0].expense).toBe(50)
    expect(buckets[0].income).toBe(0)
    expect(buckets[1].key).toBe('2026-09-15')
    expect(buckets[1].income).toBe(100)
  })
})

describe('monthlyBalance', () => {
  it('连续补全无数据的月份为 0，balance = income - expense', () => {
    const rows = monthlyBalance(
      [
        tx({ type: 'income', amount: 500, transactionDate: '2026-08-10T00:00:00' }),
        tx({ type: 'expense', amount: 200, transactionDate: '2026-08-20T00:00:00' })
      ],
      '2026-07',
      '2026-09'
    )
    expect(rows.map((r) => r.key)).toEqual(['2026-07', '2026-08', '2026-09'])
    expect(rows[1]).toMatchObject({ income: 500, expense: 200, balance: 300 })
    expect(rows[0].balance).toBe(0)
    expect(rows[2].balance).toBe(0)
  })
})

describe('aggregateByCategory（三级分类归并）', () => {
  // 餐饮(1) → 外卖(2) → 某店(3)，交易记在孙分类 3 上
  const categories = [
    cat({ id: 1, name: '餐饮', parentId: null }),
    cat({ id: 2, name: '外卖', parentId: 1 }),
    cat({ id: 3, name: '某店', parentId: 2 })
  ]
  const txns = [tx({ type: 'expense', amount: 50, categoryId: 3 })]

  it('resolveRootCategory 沿 parentId 链回溯到一级', () => {
    const byId = new Map(categories.map((c) => [c.id, c]))
    expect(resolveRootCategory(byId, 3)?.id).toBe(1)
  })

  it("groupBy='root' 时孙分类金额归并到一级分类", () => {
    const agg = aggregateByCategory(txns, categories, 'expense', 'root')
    expect(agg).toHaveLength(1)
    expect(agg[0]).toMatchObject({ categoryId: 1, name: '餐饮', amount: 50, count: 1 })
  })

  it("groupBy='self' 时按实际所记分类聚合，不归并", () => {
    const agg = aggregateByCategory(txns, categories, 'expense', 'self')
    expect(agg).toHaveLength(1)
    expect(agg[0]).toMatchObject({ categoryId: 3, name: '某店', amount: 50 })
  })

  it('已删除分类的交易被跳过', () => {
    const agg = aggregateByCategory([tx({ categoryId: 999 })], categories, 'expense', 'root')
    expect(agg).toHaveLength(0)
  })
})

describe('accountBalanceSeries', () => {
  it('期初余额 + 区间内净变动，阶梯取点', () => {
    const chart = accountBalanceSeries(
      [tx({ type: 'expense', amount: 30, accountId: 1, transactionDate: '2026-09-10T00:00:00' })],
      [acc({ id: 1, name: '现金', initialBalance: 100 })],
      '2026-09-01',
      '2026-09-30'
    )
    expect(chart.labels).toEqual(['2026-09-01', '2026-09-10'])
    expect(chart.series[0].data).toEqual([100, 70])
  })
})

describe('聚合结果的图表主题', () => {
  it('保留系列、坐标轴、格式化和图例交互，不修改输入', () => {
    const formatter = (value: unknown) => String(value)
    const option = {
      series: [{ type: 'bar', data: [10, 20] }],
      xAxis: { type: 'category', data: ['餐饮', '交通'] },
      tooltip: { trigger: 'axis', formatter, textStyle: { fontSize: 15 } },
      legend: { type: 'scroll', selected: { 餐饮: false } }
    }
    const themed = withChartTheme(option)
    expect(themed.series).toBe(option.series)
    expect(themed.xAxis).toBe(option.xAxis)
    expect(themed.tooltip).toMatchObject({ trigger: 'axis', formatter, confine: true, textStyle: { fontSize: 15 } })
    expect(themed.legend).toMatchObject({ type: 'scroll', selected: { 餐饮: false } })
    expect(option.tooltip).not.toHaveProperty('backgroundColor')
  })

  it('支持多组图例和提示层，未配置时不新增组件', () => {
    const themed = withChartTheme({ tooltip: [{ show: false }], legend: [{ bottom: 0 }, { top: 0 }] })
    expect(themed.tooltip).toEqual([expect.objectContaining({ show: false })])
    expect(themed.legend).toEqual([expect.objectContaining({ bottom: 0 }), expect.objectContaining({ top: 0 })])
    expect(withChartTheme({})).not.toHaveProperty('legend')
    expect(withChartTheme({})).not.toHaveProperty('tooltip')
  })

  it('每次渲染读取当前主题令牌', () => {
    let surface = '#ffffff'
    vi.stubGlobal('window', {})
    vi.stubGlobal('document', { documentElement: {} })
    vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: (key: string) => key === '--bk-surface' ? surface : '' }))
    try {
      expect(withChartTheme({ tooltip: {} }).tooltip).toMatchObject({ backgroundColor: '#ffffff' })
      surface = '#1c2224'
      expect(withChartTheme({ tooltip: {} }).tooltip).toMatchObject({ backgroundColor: '#1c2224' })
    } finally {
      vi.unstubAllGlobals()
    }
  })
})

describe('后端 stats 适配器', () => {
  it('adaptTrend 按粒度解释未补零的 key 生成 label', () => {
    const day: TransactionTrendItem[] = [{ key: '2026-9-14', income: 0, expense: 10 }]
    expect(adaptTrend(day, 'day')[0].label).toBe('09-14')
    expect(adaptTrend([{ key: '2026-9', income: 0, expense: 0 }], 'month')[0].label).toBe('9月')
    expect(adaptTrend([{ key: '2026-9-1', income: 0, expense: 0 }], 'week')[0].label).toBe('周一')
    expect(adaptTrend([{ key: '2026', income: 0, expense: 0 }], 'year')[0].label).toBe('2026年')
  })

  it('adaptCategory 按金额降序（后端分组无序）', () => {
    const rows: TransactionCategoryStat[] = [
      { categoryId: 1, name: '餐饮', icon: '', color: '', amount: 10, count: 1 },
      { categoryId: 2, name: '交通', icon: '', color: '', amount: 50, count: 2 }
    ]
    const agg = adaptCategory(rows)
    expect(agg[0]).toMatchObject({ categoryId: 2, amount: 50, count: 2 })
    expect(agg[1].categoryId).toBe(1)
  })

  it('adaptMonthly 稀疏行补成连续月份', () => {
    const rows: TransactionMonthlyInfo[] = [
      { key: '2026-08', year: 2026, month: 8, income: 5, expense: 2, balance: 3, cumulative: 3 }
    ]
    const out = adaptMonthly(rows, '2026-07', '2026-09')
    expect(out.map((r) => r.key)).toEqual(['2026-07', '2026-08', '2026-09'])
    expect(out[1]).toMatchObject({ income: 5, expense: 2, balance: 3 })
    expect(out[0].balance).toBe(0)
  })
})
