import type {
  Account,
  Category,
  Transaction,
  TransactionCategoryStat,
  TransactionMonthlyInfo,
  TransactionTrendItem
} from '@/types/model'
import { toFen } from './format'
import { COLOR_PALETTE } from './constants'

/** 金额（分）→ 元（2 位） */
const toYuan = (fen: number) => Math.round(fen) / 100

export type Granularity = 'day' | 'week' | 'month' | 'year'

/** 判断 ISO 日期是否落在 [start, end]（含边界，yyyy-MM-dd） */
export function inDateRange(iso: string, start: string, end: string): boolean {
  const d = iso.slice(0, 10)
  return d >= start && d <= end
}

/** 判断 ISO 日期是否属于指定年月 */
export function inMonth(iso: string, year: number, month: number): boolean {
  const prefix = `${year}-${String(month).padStart(2, '0')}`
  return iso.slice(0, 7) === prefix
}

function weekStartKey(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)
  const date = new Date(y, m - 1, d)
  const weekday = (date.getDay() + 6) % 7 // 周一 = 0
  date.setDate(date.getDate() - weekday)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export interface TrendBucket {
  /** 分桶键 */
  key: string
  /** 展示标签 */
  label: string
  income: number
  expense: number
}

/**
 * 按时间维度分桶聚合收支趋势（GAP-05 过渡方案：前端聚合）。
 * 金额单位为元，内部以分累加避免浮点误差。
 */
export function bucketizeTrend(
  transactions: Transaction[],
  granularity: Granularity
): TrendBucket[] {
  const buckets = new Map<string, TrendBucket>()

  const keyOf = (iso: string): { key: string; label: string } => {
    const date = iso.slice(0, 10)
    switch (granularity) {
      case 'day':
        return { key: date, label: date.slice(5) }
      case 'week': {
        const key = weekStartKey(iso)
        return { key, label: `${Number(key.slice(5, 7))}/${Number(key.slice(8, 10))}` }
      }
      case 'month':
        return { key: iso.slice(0, 7), label: `${Number(iso.slice(5, 7))}月` }
      case 'year':
        return { key: iso.slice(0, 4), label: `${iso.slice(0, 4)}年` }
    }
  }

  for (const t of transactions) {
    if (t.type === 'transfer') continue
    const { key, label } = keyOf(t.transactionDate)
    let bucket = buckets.get(key)
    if (!bucket) {
      bucket = { key, label, income: 0, expense: 0 }
      buckets.set(key, bucket)
    }
    const fen = toFen(t.amount)
    if (t.type === 'income') bucket.income += fen
    else bucket.expense += fen
  }

  return [...buckets.values()]
    .sort((a, b) => (a.key < b.key ? -1 : 1))
    .map((b) => ({ ...b, income: toYuan(b.income), expense: toYuan(b.expense) }))
}

export interface MonthlyBalance {
  /** 月份键 yyyy-MM */
  key: string
  /** 展示标签：范围内同年显示“M月”，跨年显示“yy/MM” */
  label: string
  year: number
  month: number
  /** 收入（元） */
  income: number
  /** 支出（元） */
  expense: number
  /** 结余（元）= 收入 − 支出；正为盈余、负为亏损 */
  balance: number
}

/**
 * 按月聚合盈亏（收入 − 支出），返回 [startKey, endKey] 之间**连续**的月份序列，
 * 无交易的月份补 0，保证图表月份轴不断档。startKey/endKey 形如 'yyyy-MM'。
 * 金额内部以分累加避免浮点误差；transfer 不计入。
 */
export function monthlyBalance(
  transactions: Transaction[],
  startKey: string,
  endKey: string
): MonthlyBalance[] {
  const pad = (n: number) => String(n).padStart(2, '0')
  let sy = Number(startKey.slice(0, 4))
  let sm = Number(startKey.slice(5, 7))
  let ey = Number(endKey.slice(0, 4))
  let em = Number(endKey.slice(5, 7))
  // 起止顺序纠正，避免反向区间产生空序列
  if (ey < sy || (ey === sy && em < sm)) {
    ;[sy, ey] = [ey, sy]
    ;[sm, em] = [em, sm]
  }

  // 逐月生成连续的月份键
  const keys: string[] = []
  let cy = sy
  let cm = sm
  while (cy < ey || (cy === ey && cm <= em)) {
    keys.push(`${cy}-${pad(cm)}`)
    cm++
    if (cm > 12) {
      cm = 1
      cy++
    }
  }

  // 按月累加收入/支出（分）
  const incFen = new Map<string, number>()
  const expFen = new Map<string, number>()
  for (const t of transactions) {
    if (t.type !== 'income' && t.type !== 'expense') continue
    const k = t.transactionDate.slice(0, 7)
    if (t.type === 'income') incFen.set(k, (incFen.get(k) ?? 0) + toFen(t.amount))
    else expFen.set(k, (expFen.get(k) ?? 0) + toFen(t.amount))
  }

  const crossYear = sy !== ey
  return keys.map((k) => {
    const incomeFen = incFen.get(k) ?? 0
    const expenseFen = expFen.get(k) ?? 0
    const y = Number(k.slice(0, 4))
    const m = Number(k.slice(5, 7))
    return {
      key: k,
      label: crossYear ? `${String(y).slice(2)}/${pad(m)}` : `${m}月`,
      year: y,
      month: m,
      income: toYuan(incomeFen),
      expense: toYuan(expenseFen),
      balance: toYuan(incomeFen - expenseFen)
    }
  })
}

/** 月份键偏移：shiftMonthKey('2026-03', -2) → '2026-01'；供“近 N 个月”区间起点计算 */
export function shiftMonthKey(key: string, delta: number): string {
  const y = Number(key.slice(0, 4))
  const m = Number(key.slice(5, 7))
  const d = new Date(y, m - 1 + delta, 1)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`
}

export interface CategoryAgg {
  categoryId: number
  name: string
  color: string
  icon: string
  /** 金额（元） */
  amount: number
  count: number
}

/**
 * 沿 parentId 链向上解析某个分类的“一级分类”（根祖先）。
 * byId 为 id→分类 映射；父级缺失（已删除）时以当前可达的最高层为根；
 * guard 防脏数据成环导致死循环。id 不存在时返回 undefined。
 */
export function resolveRootCategory(
  byId: Map<number, Category>,
  id: number
): Category | undefined {
  let cur = byId.get(id)
  if (!cur) return undefined
  for (let guard = 0; guard < 10; guard++) {
    if (cur.parentId == null) break
    const parent = byId.get(cur.parentId)
    if (!parent) break
    cur = parent
  }
  return cur
}

/**
 * 按分类聚合金额。type 为 income / expense；transfer 交易不计入。
 * groupBy='root'（父分类展示）：子/孙分类金额沿 parentId 链逐级归并到其一级分类（兼容三层树）；
 * groupBy='self'（子分类展示）：按交易实际所记分类聚合，不归并。
 */
export function aggregateByCategory(
  transactions: Transaction[],
  categories: Category[],
  type: 'income' | 'expense',
  groupBy: 'root' | 'self' = 'root'
): CategoryAgg[] {
  const byId = new Map<number, Category>()
  for (const c of categories) byId.set(c.id, c)

  const agg = new Map<number, CategoryAgg>()

  for (const t of transactions) {
    if (t.type !== type || t.categoryId == null) continue
    const self = byId.get(t.categoryId)
    // 分类可能已被删除：跳过
    if (!self) continue
    const target = groupBy === 'self' ? self : resolveRootCategory(byId, self.id) ?? self
    let item = agg.get(target.id)
    if (!item) {
      item = {
        categoryId: target.id,
        name: target.name,
        color: target.color || '#909399',
        icon: target.icon,
        amount: 0,
        count: 0
      }
      agg.set(target.id, item)
    }
    item.amount = toYuan(toFen(item.amount) + toFen(t.amount))
    item.count++
  }

  return [...agg.values()].sort((a, b) => b.amount - a.amount)
}

/** 汇总收入/支出总额（元）；transfer 不计入 */
export function sumIncomeExpense(transactions: Transaction[]): {
  income: number
  expense: number
} {
  let incomeFen = 0
  let expenseFen = 0
  for (const t of transactions) {
    if (t.type === 'income') incomeFen += toFen(t.amount)
    else if (t.type === 'expense') expenseFen += toFen(t.amount)
  }
  return { income: toYuan(incomeFen), expense: toYuan(expenseFen) }
}

// —— 账户余额变化（FR-RPT-03；纯前端按流水推演，无后端端点）——

export interface AccountBalanceSeries {
  accountId: number
  name: string
  color: string
  /** 与 labels 对齐的各时点余额（元） */
  data: number[]
}

export interface AccountBalanceChart {
  /** X 轴：首项为区间起点（期初余额），其后为区间内发生变动的日期 */
  labels: string[]
  series: AccountBalanceSeries[]
}

/**
 * 按流水推演各账户在 [start, end] 区间的余额变化曲线。
 * 余额 = 账户 initialBalance + 截至该时点的净变动（收入加、支出减、转账转出减 amount+fee / 转入加 amount）。
 * 期初余额已计入 start 之前的全部流水；区间内仅在有流水的日期取点（阶梯变化），避免逐日铺满。
 * 只纳入区间内（含之前）被流水触及、且存在于 accounts 字典的账户；金额以分累加规避浮点误差。
 */
export function accountBalanceSeries(
  transactions: Transaction[],
  accounts: Account[],
  start: string,
  end: string
): AccountBalanceChart {
  const byId = new Map<number, Account>()
  for (const a of accounts) byId.set(a.id, a)

  // 按日期升序（同日按 id 稳定排序）；ISO 文本字典序即时间序
  const sorted = [...transactions].sort(
    (a, b) =>
      a.transactionDate < b.transactionDate ? -1 : a.transactionDate > b.transactionDate ? 1 : a.id - b.id
  )

  // 被流水触及（截至 end）且在字典中的账户
  const touched = new Set<number>()
  for (const t of sorted) {
    if (t.transactionDate.slice(0, 10) > end) break
    touched.add(t.accountId)
    if (t.type === 'transfer' && t.toAccountId != null) touched.add(t.toAccountId)
  }
  const ids = [...touched].filter((id) => byId.has(id))
  if (!ids.length) return { labels: [], series: [] }

  // 各账户余额（分），起点为初始余额
  const bal = new Map<number, number>()
  for (const id of ids) bal.set(id, toFen(byId.get(id)!.initialBalance))

  const apply = (t: Transaction) => {
    const amt = toFen(t.amount)
    if (t.type === 'income') {
      bal.set(t.accountId, (bal.get(t.accountId) ?? 0) + amt)
    } else if (t.type === 'expense') {
      bal.set(t.accountId, (bal.get(t.accountId) ?? 0) - amt)
    } else if (t.type === 'transfer') {
      bal.set(t.accountId, (bal.get(t.accountId) ?? 0) - amt - toFen(t.fee))
      if (t.toAccountId != null) bal.set(t.toAccountId, (bal.get(t.toAccountId) ?? 0) + amt)
    }
  }

  // 期初余额：应用 start 之前的全部流水
  const changeDates: string[] = []
  // 各变动日的余额快照（date → 账户余额分映射）
  const snapshots = new Map<string, Map<number, number>>()
  let openSnapshot: Map<number, number> | null = null
  for (const t of sorted) {
    const d = t.transactionDate.slice(0, 10)
    if (d > end) break
    if (d < start) {
      apply(t)
      continue
    }
    if (openSnapshot == null) openSnapshot = new Map(bal)
    apply(t)
    // 同日多笔：以当日最后一笔后的状态为该日快照
    if (changeDates[changeDates.length - 1] !== d) changeDates.push(d)
    snapshots.set(d, new Map(bal))
  }
  if (openSnapshot == null) openSnapshot = new Map(bal)

  const labels = [start, ...changeDates]
  const at = (snap: Map<number, number>, id: number) => toYuan(snap.get(id) ?? 0)
  const series: AccountBalanceSeries[] = ids.map((id, i) => ({
    accountId: id,
    name: byId.get(id)!.name,
    color: COLOR_PALETTE[i % COLOR_PALETTE.length],
    data: [at(openSnapshot!, id), ...changeDates.map((d) => at(snapshots.get(d)!, id))]
  }))
  return { labels, series }
}

// —— 后端 stats/* 响应 → 视图形状适配 ——
// GAP-05：后端聚合就绪后由这些适配器接管，失败仍回退到上面的本地聚合函数。

/** 两位补零 */
const pad2 = (n: number) => String(n).padStart(2, '0')

/**
 * 后端 trend 分桶 key → 展示标签（契约 §14.2：label 由前端按 key 生成，后端不返回）。
 * 实际后端 key 未补零，且 day/week 三段同形（year-month-dayOfMonth ｜ year-month-dayOfWeek），
 * 必须按 granularity 解释第三段：
 *   year="2026" → "2026年"；month="2026-9" → "9月"；day="2026-9-14" → "09-14"；
 *   week="2026-9-1" → 第三段为 ISO 星期值(1~7) → "周一"…"周日"（后端按星期归并，非按周一起始日）。
 */
export function trendLabelFromKey(key: string, granularity: Granularity): string {
  const parts = key.split('-')
  switch (granularity) {
    case 'year':
      return `${parts[0]}年`
    case 'month':
      return `${Number(parts[1])}月`
    case 'day':
      return `${pad2(Number(parts[1]))}-${pad2(Number(parts[2]))}`
    case 'week': {
      const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
      return names[Number(parts[2]) - 1] ?? key
    }
  }
}

/**
 * 后端 stats/trend 结果 → TrendBucket[]。保留后端返回顺序（已按 f_date 升序），
 * 不再排序：后端 key 未补零，字符串序 ≠ 时间序。
 */
export function adaptTrend(items: TransactionTrendItem[], granularity: Granularity): TrendBucket[] {
  return items.map((it) => ({
    key: it.key,
    label: trendLabelFromKey(it.key, granularity),
    income: Number(it.income),
    expense: Number(it.expense)
  }))
}

/** 后端 stats/category 结果 → CategoryAgg[]，按金额降序（后端 HashMap 分组无序）。 */
export function adaptCategory(items: TransactionCategoryStat[]): CategoryAgg[] {
  return items
    .map((it) => ({
      categoryId: it.categoryId,
      name: it.name,
      color: it.color || '#909399',
      icon: it.icon,
      amount: Number(it.amount),
      count: it.count
    }))
    .sort((a, b) => b.amount - a.amount)
}

/**
 * 后端 stats/monthly 的稀疏 rows → 连续月份 MonthlyBalance[]。
 * 实际后端只返回有交易的月（契约 §14.4 要求连续补零，后端未做），故前端按 [startKey,endKey]（yyyy-MM）
 * 逐月补零，保证图表月份轴不断档；label / balance 与本地 monthlyBalance 口径一致。
 * cumulative / 盈余亏损月数由调用方在连续序列上重算（规避后端 end+1 月溢出污染）。
 */
export function adaptMonthly(
  rows: TransactionMonthlyInfo[],
  startKey: string,
  endKey: string
): MonthlyBalance[] {
  const byMonth = new Map<string, TransactionMonthlyInfo>()
  for (const r of rows) byMonth.set(`${r.year}-${pad2(r.month)}`, r)

  let sy = Number(startKey.slice(0, 4))
  let sm = Number(startKey.slice(5, 7))
  let ey = Number(endKey.slice(0, 4))
  let em = Number(endKey.slice(5, 7))
  if (ey < sy || (ey === sy && em < sm)) {
    ;[sy, ey] = [ey, sy]
    ;[sm, em] = [em, sm]
  }

  const keys: string[] = []
  let cy = sy
  let cm = sm
  while (cy < ey || (cy === ey && cm <= em)) {
    keys.push(`${cy}-${pad2(cm)}`)
    cm++
    if (cm > 12) {
      cm = 1
      cy++
    }
  }

  const crossYear = sy !== ey
  return keys.map((k) => {
    const r = byMonth.get(k)
    const y = Number(k.slice(0, 4))
    const m = Number(k.slice(5, 7))
    const income = r ? Number(r.income) : 0
    const expense = r ? Number(r.expense) : 0
    return {
      key: k,
      label: crossYear ? `${String(y).slice(2)}/${pad2(m)}` : `${m}月`,
      year: y,
      month: m,
      income,
      expense,
      balance: toYuan(toFen(income) - toFen(expense))
    }
  })
}
