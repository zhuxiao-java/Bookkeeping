import type { Account, Category, Transaction } from '@/types/model'
import {
  accountBalanceSeries,
  aggregateByCategory,
  bucketizeTrend,
  inDateRange,
  monthlyBalance,
  sumIncomeExpense,
  type AccountBalanceChart,
  type CategoryAgg,
  type Granularity,
  type MonthlyBalance,
  type TrendBucket
} from './aggregate'

/**
 * 报表本地聚合（OPT-04）：把 ReportView 在万条流水上的重计算集中为一个纯函数，
 * 既供 Web Worker 调用（默认，不阻塞主线程），也供 Worker 不可用时主线程降级复用。
 * 输入 / 输出均为可结构化克隆的纯数据，便于 postMessage 传递。
 */

/** 聚合输入快照 */
export interface ReportAggInput {
  transactions: Transaction[]
  accounts: Account[]
  categories: Category[]
  /** 区间起止（yyyy-MM-dd，含边界） */
  start: string
  end: string
  granularity: Granularity
  incomeExpense: 'income' | 'expense'
  categoryGroupBy: 'root' | 'self'
  /** 对比区间（环比/同比），null 表示不对比 */
  compareRange: [string, string] | null
}

/** 聚合结果（趋势/分类/月度为本地降级口径；后端 stats 命中时由调用方优先采用后端值） */
export interface ReportAggResult {
  balance: AccountBalanceChart
  trend: TrendBucket[]
  category: CategoryAgg[]
  monthly: MonthlyBalance[]
  summary: { income: number; expense: number }
  /** 区间内流水条数（空态判断用，替代主线程 filter） */
  rangeCount: number
  compare: {
    current: { income: number; expense: number }
    previous: { income: number; expense: number }
  } | null
}

/** 空结果：Worker 尚未返回时的初始值，各图表据此显示空态 */
export const EMPTY_REPORT_AGG: ReportAggResult = {
  balance: { labels: [], series: [] },
  trend: [],
  category: [],
  monthly: [],
  summary: { income: 0, expense: 0 },
  rangeCount: 0,
  compare: null
}

/** 在全量流水上一次性算出报表所需的本地聚合（纯函数，无副作用） */
export function computeReportAgg(input: ReportAggInput): ReportAggResult {
  const { transactions, accounts, categories, start, end, compareRange } = input
  const range = transactions.filter((t) => inDateRange(t.transactionDate, start, end))
  const compare = compareRange
    ? {
        current: sumIncomeExpense(range),
        previous: sumIncomeExpense(
          transactions.filter((t) => inDateRange(t.transactionDate, compareRange[0], compareRange[1]))
        )
      }
    : null
  return {
    balance: accountBalanceSeries(transactions, accounts, start, end),
    trend: bucketizeTrend(range, input.granularity),
    category: aggregateByCategory(range, categories, input.incomeExpense, input.categoryGroupBy),
    monthly: monthlyBalance(range, start.slice(0, 7), end.slice(0, 7)),
    summary: sumIncomeExpense(range),
    rangeCount: range.length,
    compare
  }
}
