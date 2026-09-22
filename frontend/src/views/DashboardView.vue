<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Calendar, Wallet, Tickets, TrendCharts, PieChart, Switch, ArrowRight } from '@element-plus/icons-vue'
import { budgetApi, transactionApi } from '@/api'
import { useDictStore } from '@/stores/dict'
import { useSettingsStore } from '@/stores/settings'
import { parseTagIds, type BudgetInfo, type Transaction } from '@/types/model'
import { formatAmount, formatDateTime, fenToYuan, toFen } from '@/utils/format'
import {
  adaptCategory,
  adaptMonthly,
  adaptTrend,
  aggregateByCategory,
  bucketizeTrend,
  inMonth,
  monthlyBalance,
  shiftMonthKey,
  sumIncomeExpense,
  type CategoryAgg,
  type MonthlyBalance,
  type TrendBucket
} from '@/utils/aggregate'
import { TRANSACTION_TYPE_COLOR_CLASS, transactionTypeLabel, CURRENCY_SYMBOL } from '@/utils/constants'
import { useChart } from '@/composables/useChart'
import { useCountUp } from '@/composables/useCountUp'
import { chartPalette, resolveDistinctColors } from '@/utils/chartTheme'
import { bus, TRANSACTION_CHANGED, ACCOUNT_CHANGED, BUDGET_CHANGED, CATEGORY_CHANGED } from '@/utils/bus'
import CategoryDot from '@/components/CategoryDot.vue'
import LevelLogo from '@/components/LevelLogo.vue'
import CheckInCard from '@/components/CheckInCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useLevelStore } from '@/stores/level'
import { useCheckInStore } from '@/stores/checkin'

/** 总览仪表盘（需求文档 3.1 增强页）：本月收支概览 + 趋势 + 占比 + 最近流水 + 预算概况 */
const router = useRouter()
const dict = useDictStore()
const settings = useSettingsStore()
const level = useLevelStore()
const checkin = useCheckInStore()
const decimals = computed(() => settings.decimalPlaces)

const loading = ref(false)
const transactions = ref<Transaction[]>([])
const budgets = ref<BudgetInfo[]>([])

/**
 * 后端统计聚合结果（stats/* 各自 silent，成功才填充；失败保持 null → 对应卡片/图表降级本地聚合）。
 * transactions（selectAll）仍保留：最近流水、空态判断、降级聚合都要用。
 */
const beMonthSummary = ref<{ income: number; expense: number } | null>(null)
const beTrend = ref<TrendBucket[] | null>(null)
const beExpenseAgg = ref<CategoryAgg[] | null>(null)
const bePlRows = ref<MonthlyBalance[] | null>(null)
/** 最近流水：优先后端 /transaction/recent（silent），失败保持 null → 降级 selectAll 本地倒序取前 5 */
const beRecent = ref<Transaction[] | null>(null)
/** 四路全失败即置真：本会话不再重复探测后端，纯本地聚合 */
let statsDead = false

const now = new Date()
const year = now.getFullYear()
const month = now.getMonth() + 1
const monthLabel = `${year}年${month}月`
const pad2 = (n: number) => String(n).padStart(2, '0')
/** 本月区间（整月，含未来日期，与本地 inMonth 口径一致）：summary/trend/category 用 */
const monthStart = `${year}-${pad2(month)}-01`
const monthEnd = `${year}-${pad2(month)}-${pad2(new Date(year, month, 0).getDate())}`
/** 近 PL_MONTHS 个月盈亏（含当月）区间起点键 yyyy-MM：monthly 用 */
const PL_MONTHS = 6
const plStartKey = shiftMonthKey(`${year}-${pad2(month)}`, -(PL_MONTHS - 1))

const trend = useChart()
const trendEl = trend.elRef
const pie = useChart()
const pieEl = pie.elRef
const pl = useChart()
const plEl = pl.elRef

/** 支出占比聚合口径：root=父分类（子分类归并到一级）／self=子分类（按交易实际分类） */
const pieGroupBy = ref<'root' | 'self'>('root')

async function load() {
  loading.value = true
  try {
    // 首屏并行（对齐 ReportView，#8）：selectAll 与 stats/预算互不阻塞，
    // stats 直接驱动本月概览/趋势/占比/盈亏，selectAll 仅供最近流水与降级聚合。
    // 账户字典由 dict store 统一加载，此处不再重复 selectAll（原为冗余请求）。
    await Promise.all([
      transactionApi
        .selectAll()
        .then((rows) => { transactions.value = rows })
        .catch(() => { /* 全量拉取失败：保留已有数据，图表走 stats/降级 */ }),
      // 预算卡为次要信息：searchBudget 失败时降级为空，不拖垮整个总览页
      budgetApi
        .searchBudget(year, month)
        .then((infos) => { budgets.value = infos })
        .catch(() => { budgets.value = [] as BudgetInfo[] }),
      refreshStats()
    ])
    // selectAll 可能晚于 stats 落地：补渲染一次，让降级项/最近流水用上全量数据
    renderCharts()
  } finally {
    loading.value = false
  }
}

/**
 * 拉取后端统计聚合：本月 summary / trend(day) / category(expense) + 近 PL_MONTHS 月 monthly。
 * 四路各自 silent，任一失败该项降级本地聚合；四路全失败置 statsDead，本会话不再探测。
 */
async function refreshStats() {
  if (statsDead) {
    renderCharts()
    return
  }
  const [sumRes, trendRes, catRes, monRes] = await Promise.allSettled([
    transactionApi.summary(monthStart, monthEnd, { silent: true }),
    transactionApi.trend(monthStart, monthEnd, 'day', { silent: true }),
    transactionApi.category(monthStart, monthEnd, 'expense', pieGroupBy.value, { silent: true }),
    transactionApi.monthly(`${plStartKey}-01`, monthEnd, { silent: true })
  ])
  beMonthSummary.value =
    sumRes.status === 'fulfilled'
      ? { income: Number(sumRes.value.income), expense: Number(sumRes.value.expense) }
      : null
  beTrend.value = trendRes.status === 'fulfilled' ? adaptTrend(trendRes.value, 'day') : null
  beExpenseAgg.value = catRes.status === 'fulfilled' ? adaptCategory(catRes.value) : null
  bePlRows.value =
    monRes.status === 'fulfilled'
      ? adaptMonthly(monRes.value.rows ?? [], plStartKey, `${year}-${pad2(month)}`)
      : null
  // 最近流水为独立 silent 路，不纳入四路聚合的 statsDead 判定
  beRecent.value = await transactionApi.recent({ silent: true }).catch(() => null)
  if (
    sumRes.status === 'rejected' &&
    trendRes.status === 'rejected' &&
    catRes.status === 'rejected' &&
    monRes.status === 'rejected'
  ) {
    statsDead = true
  }
  renderCharts()
}

const monthTransactions = computed(() =>
  transactions.value.filter((t) => inMonth(t.transactionDate, year, month))
)

/** 本月收支：优先后端 stats/summary，降级本地聚合 */
const monthSummary = computed(() => beMonthSummary.value ?? sumIncomeExpense(monthTransactions.value))

/** 近 PL_MONTHS 个月盈亏（含当月）：优先后端 stats/monthly（补零为连续月），降级本地聚合 */
const plRows = computed(
  () => bePlRows.value ?? monthlyBalance(transactions.value, plStartKey, `${year}-${pad2(month)}`)
)
const plSummary = computed(() => {
  let surplus = 0
  let deficit = 0
  let cumFen = 0
  for (const r of plRows.value) {
    if (r.balance > 0) surplus++
    else if (r.balance < 0) deficit++
    cumFen += toFen(r.balance)
  }
  return { surplus, deficit, cumulative: fenToYuan(cumFen) }
})

/** Hero 大数字：本月结余（count-up 动画） */
const heroBalance = computed(() => monthSummary.value.income - monthSummary.value.expense)
const heroBalanceAnim = useCountUp(heroBalance)
/** 上月结余（取近月盈亏倒数第二条），用于环比 chip；不足两月时为 null */
const prevBalance = computed(() => {
  const rows = plRows.value
  return rows.length >= 2 ? rows[rows.length - 2].balance : null
})
/** 环比变化量（本月结余 − 上月结余）；null 表示无可比月份 */
const heroDelta = computed(() => (prevBalance.value == null ? null : heroBalance.value - prevBalance.value))

/** 总资产（按币种分组，仅统计启用账户） */
const totalAssetByCurrency = computed(() => {
  const map = new Map<string, number>()
  for (const a of dict.accounts) {
    if (a.archived !== 0) continue
    map.set(a.currency, (map.get(a.currency) ?? 0) + Number(a.currentBalance ?? 0))
  }
  return [...map.entries()]
})

/** 最近 5 笔：优先后端 /transaction/recent（已按日期倒序），降级 selectAll 结果本地倒序取前 5 */
const recentTransactions = computed(
  () =>
    beRecent.value ??
    [...transactions.value]
      .sort((a, b) => (a.transactionDate < b.transactionDate ? 1 : -1))
      .slice(0, 5)
)

/** 当月总预算及使用 */
const monthTotalBudget = computed(() => budgets.value.find((b) => b.categoryId == null) ?? null)
const budgetPct = computed(() => {
  const b = monthTotalBudget.value
  if (!b || Number(b.amount) <= 0) return 0
  return Math.max(0, Math.round((Number(b.amountUsed) / Number(b.amount)) * 100))
})
const budgetDifference = computed(() => {
  const b = monthTotalBudget.value
  return b ? fenToYuan(toFen(b.amount) - toFen(b.amountUsed)) : null
})
const budgetExceeded = computed(() => (budgetDifference.value ?? 0) < 0)
const budgetLabel = computed(() => {
  if (!monthTotalBudget.value) return '未设置预算'
  if (budgetExceeded.value) return '已超支'
  if (budgetDifference.value === 0) return '已用完'
  return budgetPct.value >= 80 ? '临近上限' : '预算充足'
})
const budgetTone = computed(() => {
  if (budgetExceeded.value) return 'exception'
  return budgetDifference.value === 0 || budgetPct.value >= 80 ? 'warning' : 'success'
})
const budgetHint = computed(() => {
  if (budgetExceeded.value) return '支出已超过本月计划，留意接下来的每一笔。'
  if (budgetDifference.value === 0) return '本月预算已用完，暂未超支。'
  if (budgetPct.value >= 80) return '预算即将用完，为接下来的生活留一些余地。'
  return '支出仍在计划内，按自己的节奏记录生活。'
})

/** 本月记账笔数（Hero 已展示收入/支出/结余，此处补充不重复的量化指标） */
const monthTxCount = computed(() => monthTransactions.value.length)

/** 储蓄率 = 本月结余 / 本月收入（收入为 0 时无意义，返回 null 显示占位） */
const savingRate = computed(() => {
  const inc = monthSummary.value.income
  if (inc <= 0) return null
  return Math.round(((inc - monthSummary.value.expense) / inc) * 100)
})

/** 本月总预算剩余额度（未设置总预算返回 null；已超支按 0 显示） */
const budgetRemaining = computed(() => {
  const b = monthTotalBudget.value
  if (!b) return null
  return Math.max(0, budgetDifference.value ?? 0)
})

function renderCharts() {
  renderTrend()
  renderPie()
  renderPL()
}

function renderTrend() {
  const p = chartPalette()
  // 优先后端 stats/trend（本月按日，已适配为 TrendBucket），降级本地分桶
  const buckets = beTrend.value ?? bucketizeTrend(monthTransactions.value, 'day')
  trend.render({
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入', '支出'], right: 0, textStyle: { color: p.axisText } },
    grid: { left: 52, right: 12, top: 36, bottom: 24 },
    xAxis: {
      type: 'category',
      data: buckets.map((b) => b.label),
      axisLabel: { color: p.axisText },
      axisLine: { lineStyle: { color: p.axisLine } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: p.axisText },
      splitLine: { lineStyle: { color: p.splitLine } }
    },
    series: [
      { name: '收入', type: 'line', smooth: true, data: buckets.map((b) => b.income), itemStyle: { color: p.income }, areaStyle: { opacity: 0.08 } },
      { name: '支出', type: 'line', smooth: true, data: buckets.map((b) => b.expense), itemStyle: { color: p.expense }, areaStyle: { opacity: 0.08 } }
    ]
  })
}

function renderPie() {
  const p = chartPalette()
  const items = expenseAgg.value
  // 渲染期去重分配：优先沿用分类存库色，撞色/近似色自动换色，保证每个扇区可区分
  const colors = expenseColors.value
  const data = items.map((c, i) => ({ name: c.name, value: Number(c.amount), itemStyle: { color: colors[i] } }))
  pie.render({
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c}（{d}%）' },
    series: [
      {
        type: 'pie',
        radius: ['54%', '74%'],
        center: ['50%', '50%'],
        // 百分比直标作为颜色的冗余编码（色弱友好）；过小扇区自动隐藏标签避免拥挤
        label: { show: true, formatter: '{d}%', fontSize: 10, color: p.axisText },
        labelLine: { show: true, length: 4, length2: 4 },
        minShowLabelAngle: 15,
        itemStyle: { borderRadius: 4, borderColor: 'transparent', borderWidth: 2 },
        data
      }
    ]
  })
}

/** 近 N 个月盈亏：正负分色柱状图（盈余绿向上、亏损红向下，值轴天然含 0 基准） */
function renderPL() {
  const p = chartPalette()
  const rows = plRows.value
  pl.render({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps: any) => {
        const p0 = Array.isArray(ps) ? ps[0] : ps
        const row = rows[p0.dataIndex]
        if (!row) return ''
        const word = row.balance > 0 ? '盈余' : row.balance < 0 ? '亏损' : '持平'
        return `${row.year}年${row.month}月<br/>收入：¥${formatAmount(row.income)}<br/>支出：¥${formatAmount(row.expense)}<br/>${word}：¥${formatAmount(Math.abs(row.balance))}`
      }
    },
    grid: { left: 48, right: 12, top: 20, bottom: 24 },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.label),
      axisLabel: { color: p.axisText },
      axisLine: { lineStyle: { color: p.axisLine } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: p.axisText },
      splitLine: { lineStyle: { color: p.splitLine } }
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 26,
        data: rows.map((r) => ({
          value: r.balance,
          itemStyle: {
            color: r.balance >= 0 ? p.income : p.expense,
            borderRadius: r.balance >= 0 ? [4, 4, 0, 0] : [0, 0, 4, 4]
          },
          label: { position: r.balance >= 0 ? 'top' : 'bottom' }
        })),
        label: {
          show: true,
          color: p.axisText,
          fontSize: 11,
          formatter: (pp: any) =>
            pp.value === 0 ? '' : `${pp.value > 0 ? '+' : '-'}${formatAmount(Math.abs(pp.value))}`
        }
      }
    ]
  })
}

/** 支出分类占比：优先后端 stats/category，降级本地聚合 */
const expenseAgg = computed(
  () =>
    beExpenseAgg.value ??
    aggregateByCategory(monthTransactions.value, dict.categories, 'expense', pieGroupBy.value)
)
const expenseTotal = computed(() => monthSummary.value.expense)
// 图例与扇区共用颜色分配，父子分类切换时同步更新。
const expenseColors = computed(() => resolveDistinctColors(expenseAgg.value))
const expenseLegend = computed(() => {
  const total = expenseAgg.value.reduce((sum, item) => sum + toFen(item.amount), 0)
  return expenseAgg.value.slice(0, 3).map((item, index) => ({
    ...item,
    color: expenseColors.value[index],
    percent: total > 0 ? Math.round(toFen(item.amount) / total * 100) : 0
  }))
})

// 切换父/子分类聚合口径：影响后端 category 口径，需重拉统计（降级时本地聚合亦随之重算）
watch(pieGroupBy, () => refreshStats())

// 主题切换（含跟随系统）后图表按新 CSS token 重绘：ECharts 是 canvas，不会随 CSS 变量自动刷新
watch(() => settings.isDark, () => renderCharts())

/**
 * 图表钻取（前端细化 #3）：点击图表 → 跳流水页并带上对应筛选。
 * 流水页已消费 ?categoryId=&start=&end=（见 TransactionView.applyFiltersFromRoute）。
 */
function drillToTransaction(query: Record<string, string | number>) {
  router.push({ path: '/transaction', query })
}
// 支出占比扇区 → 该分类 + 本月区间
pie.onClick((p: any) => {
  const item = expenseAgg.value[p.dataIndex]
  if (item?.categoryId) drillToTransaction({ categoryId: item.categoryId, start: monthStart, end: monthEnd })
})
// 收支趋势（按日）→ 当天（后端 key 未补零如 2026-9-14，本地已补零，统一规范化）
trend.onClick((p: any) => {
  const buckets = beTrend.value ?? bucketizeTrend(monthTransactions.value, 'day')
  const b = buckets[p.dataIndex]
  if (!b?.key) return
  const parts = b.key.split('-').map(Number)
  if (parts.length !== 3 || parts.some((n) => !Number.isFinite(n))) return
  const day = `${parts[0]}-${pad2(parts[1])}-${pad2(parts[2])}`
  drillToTransaction({ start: day, end: day })
})
// 近月盈亏柱 → 该月整月
pl.onClick((p: any) => {
  const row = plRows.value[p.dataIndex]
  if (!row) return
  const start = `${row.year}-${pad2(row.month)}-01`
  const end = `${row.year}-${pad2(row.month)}-${pad2(new Date(row.year, row.month, 0).getDate())}`
  drillToTransaction({ start, end })
})

/** 当前经验展示值（取整 + 千分位） */
const expDisplay = computed(() =>
  level.info ? Math.round(Number(level.info.experience)).toLocaleString('zh-CN') : '0'
)

function rowTags(row: Transaction) {
  return parseTagIds(row.tags)
    .map((id) => dict.tagById(id))
    .filter((t): t is NonNullable<typeof t> => !!t)
}

function rowCurrency(row: Transaction) {
  const account = dict.accountById(row.accountId)
  return account ? CURRENCY_SYMBOL[account.currency] ?? '' : ''
}

function onChanged() {
  load()
}

onMounted(() => {
  load()
  level.loadCurrent()
  checkin.load()
  bus.on(TRANSACTION_CHANGED, onChanged)
  bus.on(ACCOUNT_CHANGED, onChanged)
  bus.on(BUDGET_CHANGED, onChanged)
  bus.on(CATEGORY_CHANGED, onChanged)
})

onBeforeUnmount(() => {
  bus.off(TRANSACTION_CHANGED, onChanged)
  bus.off(ACCOUNT_CHANGED, onChanged)
  bus.off(BUDGET_CHANGED, onChanged)
  bus.off(CATEGORY_CHANGED, onChanged)
})
</script>

<template>
  <div class="page page--comfortable quiet-controls dash" v-loading="loading && transactions.length > 0">
    <header class="page-head page-head--actions">
      <div>
        <h1 class="page-head__title">总览</h1>
        <p class="page-head__sub">从今天的小记录，看见生活与收支的变化。</p>
      </div>
      <span class="month-stamp"><el-icon aria-hidden="true"><Calendar /></el-icon>{{ monthLabel }}</span>
    </header>
    <!-- Hero：本月结余大数字 + 环比 + 等级 chip（叙事化首屏，弱化后台看板感） -->
    <section class="hero bk-enter">
      <div class="hero__main">
        <h2 class="hero__label">本月结余</h2>
        <div class="hero__balance" :class="heroBalance >= 0 ? 'amount-income' : 'amount-expense'">
          {{ heroBalance >= 0 ? '' : '-' }}¥{{ formatAmount(Math.abs(heroBalanceAnim), decimals) }}
        </div>
        <div class="hero__meta">
          <span
            v-if="heroDelta != null"
            class="hero__delta"
            :class="heroDelta > 0 ? 'is-up' : heroDelta < 0 ? 'is-down' : ''"
          >
            较上月 {{ heroDelta > 0 ? '↑' : heroDelta < 0 ? '↓' : '·' }}
            ¥{{ formatAmount(Math.abs(heroDelta), decimals) }}
          </span>
        </div>
        <div class="hero__flows">
          <div class="hero__flow">
            <span><i class="flow-dot flow-dot--income" aria-hidden="true" />本月收入</span>
            <strong class="amount-income">¥{{ formatAmount(monthSummary.income, decimals) }}</strong>
          </div>
          <div class="hero__flow">
            <span><i class="flow-dot flow-dot--expense" aria-hidden="true" />本月支出</span>
            <strong class="amount-expense">¥{{ formatAmount(monthSummary.expense, decimals) }}</strong>
          </div>
        </div>
      </div>

      <!-- 等级 chip（后端等级接口可用时展示），点击进等级页 -->
      <router-link v-if="level.info" to="/level" class="hero__level" title="查看我的等级">
        <LevelLogo :level="level.info.level" :size="42" />
        <div class="hero__level-meta">
          <div class="hero__level-title">Lv.{{ level.info.level }} {{ level.info.leveName }}</div>
          <div class="hero__level-hint">
            {{ level.info.nextLevelName ? `距「${level.info.nextLevelName}」还差 ${level.remainExp} 经验` : '已达最高等级' }}
          </div>
          <el-progress :percentage="level.progress" :show-text="false" :stroke-width="6" color="var(--bk-primary)" />
        </div>
        <div class="hero__level-exp">
          <span class="hero__level-expv">{{ expDisplay }}</span>
          <span class="hero__level-expl">经验</span>
        </div>
      </router-link>
    </section>

    <!-- 签到卡片（后端签到接口可用时展示） -->
    <CheckInCard />

    <!-- 统计 tile（Hero 已展示本月收入/支出/结余，此处只补充不重复的指标） -->
    <section class="page-section" aria-labelledby="dashboard-summary-heading">
      <h2 id="dashboard-summary-heading" class="section-heading">本月概况</h2>
      <div class="stat-grid" data-guide="stat-cards">
        <div class="tile bk-enter">
          <div class="tile__label">总资产<el-icon aria-hidden="true"><Wallet /></el-icon></div>
          <div class="tile__value">
            <template v-if="totalAssetByCurrency.length">
              <span v-for="[currency, total] in totalAssetByCurrency" :key="currency" class="tile__currency">
                {{ CURRENCY_SYMBOL[currency as 'CNY' | 'DOLLAR'] }}{{ formatAmount(total, decimals) }}
              </span>
            </template>
            <template v-else>¥{{ formatAmount(0, decimals) }}</template>
          </div>
          <div class="tile__sub">{{ totalAssetByCurrency.length > 1 ? '按币种分列，未做汇率换算' : '当前启用账户的余额合计' }}</div>
        </div>
        <div class="tile bk-enter">
          <div class="tile__label">本月记账<el-icon aria-hidden="true"><Tickets /></el-icon></div>
          <div class="tile__value">{{ monthTxCount }}<span class="tile__unit">笔</span></div>
          <div class="tile__sub">每一笔，都是生活的足迹</div>
        </div>
        <div class="tile bk-enter">
          <div class="tile__label">储蓄率<el-icon aria-hidden="true"><TrendCharts /></el-icon></div>
          <div
            class="tile__value"
            :class="savingRate == null ? '' : savingRate < 0 ? 'amount-expense' : 'amount-income'"
          >{{ savingRate == null ? '—' : savingRate + '%' }}</div>
          <div class="tile__sub">{{ savingRate == null ? '本月暂无收入，暂不计算' : '本月结余 / 收入' }}</div>
        </div>
        <div class="tile bk-enter">
          <div class="tile__label">{{ budgetExceeded ? '预算超支' : '预算剩余' }}<el-icon aria-hidden="true"><PieChart /></el-icon></div>
          <div class="tile__value" :class="{ 'amount-expense': budgetExceeded }">
            {{ budgetRemaining == null ? '—' : '¥' + formatAmount(Math.abs(budgetDifference ?? 0), decimals) }}
          </div>
          <div class="tile__sub" :class="{ 'amount-expense': budgetExceeded }">{{ budgetLabel }}</div>
        </div>
      </div>
    </section>

    <!-- 图表区 -->
    <section class="page-section" aria-labelledby="dashboard-analysis-heading">
      <h2 id="dashboard-analysis-heading" class="section-heading">收支分析</h2>
    <div class="chart-row">
      <el-card shadow="never" class="chart-card trend-card" data-guide="trend">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">{{ monthLabel }} 收支趋势</h2>
            <span class="card-hint">点击图表查看对应流水</span>
          </div>
        </template>
        <div class="chart-slot">
          <div ref="trendEl" class="chart-box" title="点击查看当天流水" />
          <el-skeleton v-if="loading && !monthTransactions.length" class="chart-skel" animated :rows="5" />
          <EmptyState v-else-if="!monthTransactions.length" description="本月暂无记录" :size="80" class="chart-empty" />
        </div>
      </el-card>

      <el-card shadow="never" class="chart-card expense-card" data-guide="pie">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">支出分类占比</h2>
            <el-radio-group v-model="pieGroupBy" class="segment" aria-label="支出分类层级" size="small">
              <el-radio-button value="root">父分类</el-radio-button>
              <el-radio-button value="self">子分类</el-radio-button>
            </el-radio-group>
          </div>
        </template>
        <div class="chart-slot">
          <div ref="pieEl" class="chart-box" title="点击查看该分类流水" />
          <el-skeleton v-if="loading && !expenseAgg.length" class="chart-skel" animated :rows="5" />
          <EmptyState v-else-if="!expenseAgg.length" description="本月暂无支出" :size="80" class="chart-empty" />
          <div v-else class="pie-center">
            <div class="pie-center__value">{{ expenseAgg.length }}</div>
            <div class="pie-center__label">个支出分类</div>
          </div>
        </div>
        <div v-if="expenseAgg.length" class="expense-breakdown">
          <div class="expense-total"><span>本月支出</span><strong>¥{{ formatAmount(expenseTotal, decimals) }}</strong></div>
          <ul class="expense-legend" aria-label="支出金额最高的三个分类">
            <li v-for="item in expenseLegend" :key="item.categoryId">
              <button type="button" :disabled="!item.categoryId" @click="drillToTransaction({ type: 'expense', categoryId: item.categoryId, start: monthStart, end: monthEnd })">
                <i class="flow-dot" :style="{ background: item.color }" aria-hidden="true" />
                <span class="expense-legend__name">{{ item.name }}</span>
                <span class="expense-legend__amount">¥{{ formatAmount(item.amount, decimals) }}</span>
                <span class="expense-legend__percent">{{ item.percent }}%</span>
              </button>
            </li>
          </ul>
          <el-button text type="primary" class="expense-more" @click="drillToTransaction({ type: 'expense', start: monthStart, end: monthEnd })">
            查看全部支出<el-icon aria-hidden="true"><ArrowRight /></el-icon>
          </el-button>
        </div>
      </el-card>
    </div>

    <!-- 近 N 个月盈亏 -->
    <el-card shadow="never" class="chart-card pl-card" data-guide="pl">
      <template #header>
        <div class="card-head">
          <div class="card-head__copy">
            <h2 class="card-head__title">近 {{ PL_MONTHS }} 个月盈亏</h2>
            <span class="card-metric">
              盈余 <b class="amount-income">{{ plSummary.surplus }}</b> ·
              亏损 <b class="amount-expense">{{ plSummary.deficit }}</b> ·
              累计
              <b :class="plSummary.cumulative >= 0 ? 'amount-income' : 'amount-expense'">
                {{ plSummary.cumulative >= 0 ? '+' : '-' }}¥{{ formatAmount(Math.abs(plSummary.cumulative), decimals) }}
              </b>
            </span>
          </div>
          <el-button text type="primary" @click="router.push('/report')">查看报表</el-button>
        </div>
      </template>
      <div class="chart-slot">
        <div ref="plEl" class="pl-box" title="点击查看该月流水" />
        <el-skeleton v-if="loading && !transactions.length" class="chart-skel" animated :rows="4" />
        <EmptyState v-else-if="!transactions.length" description="还没有记录" :size="72" class="chart-empty" />
      </div>
    </el-card>

    </section>

    <!-- 最近流水 + 预算概况 -->
    <section class="page-section" aria-labelledby="dashboard-record-heading">
      <h2 id="dashboard-record-heading" class="section-heading">账本动态</h2>
    <div class="bottom-row" data-guide="recent">
      <el-card shadow="never" class="bottom-card">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">最近流水</h2>
            <el-button text type="primary" @click="router.push('/transaction')">查看全部</el-button>
          </div>
        </template>
        <template v-if="recentTransactions.length">
          <router-link v-for="row in recentTransactions" :key="row.id" :to="{ path: '/transaction', query: { bizId: row.id } }" class="recent-row">
            <CategoryDot
              v-if="row.type !== 'transfer' && dict.categoryById(row.categoryId)"
              :name="dict.categoryById(row.categoryId)!.name"
              :icon="dict.categoryById(row.categoryId)!.icon"
              :color="dict.categoryById(row.categoryId)!.color"
              :size="30"
            />
            <span v-else class="recent-row__dot-placeholder" :class="TRANSACTION_TYPE_COLOR_CLASS[row.type]" aria-hidden="true">
              <el-icon><Switch v-if="row.type === 'transfer'" /><Tickets v-else /></el-icon>
            </span>
            <div class="recent-row__meta">
              <div class="recent-row__note">{{ row.note || transactionTypeLabel(row.type) }}</div>
              <div class="recent-row__account">
                {{ dict.accountById(row.accountId)?.name || '未知账户' }}
                <template v-if="row.type === 'transfer'"> → {{ dict.accountById(row.toAccountId)?.name || '未知账户' }}</template>
              </div>
              <div class="recent-row__sub"><time :datetime="row.transactionDate">{{ formatDateTime(row.transactionDate) }}</time></div>
              <div v-if="rowTags(row).length" class="recent-row__tags">
                <span v-for="tag in rowTags(row)" :key="tag.id" class="recent-row__tag">{{ tag.name }}</span>
              </div>
            </div>
            <div class="recent-row__money">
              <span class="recent-row__type">{{ transactionTypeLabel(row.type) }}</span>
              <span class="recent-row__amount amount-strong" :class="TRANSACTION_TYPE_COLOR_CLASS[row.type]">
                {{ row.type === 'income' ? '+' : row.type === 'expense' ? '−' : '' }}{{ rowCurrency(row) }}{{ formatAmount(row.amount, decimals) }}
              </span>
            </div>
          </router-link>
        </template>
        <el-skeleton v-else-if="loading" animated :rows="5" />
        <EmptyState v-else description="还没有记录，点击顶栏「记一笔」开始记账" />
      </el-card>

      <el-card shadow="never" class="bottom-card">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">{{ monthLabel }} 预算</h2>
            <el-button text type="primary" @click="router.push('/budget')">管理预算</el-button>
          </div>
        </template>
        <template v-if="monthTotalBudget">
          <div class="budget-overview" :class="`is-${budgetTone}`">
            <div class="budget-line">
              <span class="budget-line__label">{{ budgetExceeded ? '超出预算' : '本月还可用' }}</span>
              <span class="budget-badge">{{ budgetLabel }}</span>
            </div>
            <div class="budget-balance" :class="{ 'amount-expense': budgetExceeded }">¥{{ formatAmount(Math.abs(budgetDifference ?? 0), decimals) }}</div>
            <div class="budget-line budget-usage">
              <span>预算使用情况</span>
              <strong>{{ Number(monthTotalBudget.amount) > 0 ? budgetPct + '%' : '—' }}</strong>
            </div>
            <el-progress :percentage="budgetExceeded ? 100 : Math.min(100, budgetPct)" :status="budgetTone" :show-text="false" :stroke-width="8" aria-label="本月预算使用情况" />
            <dl class="budget-details">
              <div><dt>已支出</dt><dd>¥{{ formatAmount(monthTotalBudget.amountUsed, decimals) }}</dd></div>
              <div><dt>总预算</dt><dd>¥{{ formatAmount(monthTotalBudget.amount, decimals) }}</dd></div>
            </dl>
            <p class="budget-hint">{{ budgetHint }}</p>
          </div>
        </template>
        <el-skeleton v-else-if="loading" animated :rows="4" />
        <EmptyState v-else description="本月未设置预算">
          <el-button type="primary" @click="router.push('/budget')">去设置预算</el-button>
        </EmptyState>
      </el-card>
    </div>
    </section>
  </div>
</template>

<style scoped>
.month-stamp {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: var(--bk-radius-pill);
  background: var(--bk-surface-2);
  color: var(--bk-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

/* —— Hero 叙事首屏 —— */
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
  padding: var(--bk-row-padding) var(--bk-panel-padding);
  border-radius: var(--bk-radius-lg);
  background: linear-gradient(110deg, color-mix(in srgb, var(--bk-primary-soft) 52%, var(--bk-surface)), var(--bk-surface));
  border: 0;
}

.hero__main {
  flex: 1 1 340px;
  min-width: 0;
}

.hero__label {
  margin: 0;
  font-size: 13px;
  color: var(--bk-text-secondary);
  font-weight: 500;
}

.hero__balance {
  font-size: clamp(32px, 4.8cqi, 46px);
  overflow-wrap: anywhere;
  font-weight: 650;
  line-height: 1.1;
  margin: 8px 0 14px;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.hero__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.hero__flows {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-width: 440px;
  margin-top: 22px;
  gap: 16px;
}
.hero__flow { min-width: 0; }
.hero__flow + .hero__flow { border-left: 1px solid var(--bk-border); padding-left: 16px; }
.hero__flow > span { display: flex; align-items: center; gap: 7px; color: var(--bk-text-secondary); font-size: 12px; }
.hero__flow strong { display: block; margin-top: 4px; font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.flow-dot { display: inline-block; width: 7px; height: 7px; flex: none; border-radius: 50%; }
.flow-dot--income { background: var(--bk-income); }
.flow-dot--expense { background: var(--bk-expense); }

.hero__delta {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: var(--bk-radius-pill);
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  background: var(--bk-surface-2);
  color: var(--bk-text-secondary);
}

.hero__delta.is-up {
  background: rgba(47, 181, 124, 0.14);
  color: var(--bk-income-text);
}

.hero__delta.is-down {
  background: rgba(240, 98, 93, 0.14);
  color: var(--bk-expense-text);
}

/* Hero 右侧等级 chip */
.hero__level {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 0 1 290px;
  padding: 16px;
  border-radius: var(--bk-radius-lg);
  background: color-mix(in srgb, var(--bk-primary-soft) 40%, var(--bk-surface));
  cursor: pointer;
  max-width: 100%;
  box-sizing: border-box;
  color: var(--bk-text);
  text-decoration: none;
  transition: background-color 0.18s;
}

.hero__level:hover {
  background: var(--bk-primary-soft);
}

.hero__level:focus-visible {
  outline: 2px solid var(--bk-button-primary);
  outline-offset: 3px;
}

.hero__level-meta {
  min-width: 0;
  flex: 1;
}

.hero__level-title {
  font-size: 14px;
  font-weight: 700;
}

.hero__level-hint {
  color: var(--bk-text-secondary);
  font-size: 12px;
  margin: 2px 0 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero__level-exp {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding-left: 12px;
  border-left: 1px solid var(--bk-border-light);
}

.hero__level-expv {
  font-size: 20px;
  font-weight: 650;
  color: var(--bk-primary);
  font-variant-numeric: tabular-nums;
}

.hero__level-expl {
  color: var(--bk-text-secondary);
  font-size: 12px;
  margin-top: 2px;
}

/* —— 统计 tile —— */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--bk-gap);
}

.tile {
  padding: var(--bk-row-padding) var(--bk-panel-padding);
  min-width: 0;
  border-radius: var(--bk-radius-lg);
  background: var(--bk-surface);
}

.tile__label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--bk-text-secondary);
  font-size: 13px;
}
.tile__label .el-icon { font-size: 17px; color: var(--bk-button-primary); }
.tile__currency { display: block; }
.tile__sub.amount-expense { color: var(--bk-expense-text); }

.tile__value {
  font-size: 26px;
  font-weight: 650;
  overflow-wrap: anywhere;
  margin-top: 8px;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
}

.tile__sub {
  color: var(--bk-text-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.tile__unit {
  margin-left: 4px;
  font-size: 14px;
  font-weight: 500;
  color: var(--bk-text-secondary);
}

.chart-row {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: var(--bk-gap);
}

.chart-card {
  position: relative;
  min-width: 0;
}
.trend-card { display: flex; flex-direction: column; }
.trend-card :deep(.el-card__body) { flex: 1; display: flex; }
.trend-card .chart-slot { flex: 1; min-width: 0; min-height: 360px; }
.trend-card .chart-box { position: absolute; inset: 0; height: 100%; }
.expense-card .chart-box { height: 210px; }
.expense-breakdown { display: flex; flex-direction: column; gap: 8px; }
.expense-total { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 6px 12px; padding-bottom: 10px; border-bottom: 1px solid var(--bk-border-light); font-size: 12px; color: var(--bk-text-secondary); }
.expense-total strong { font-size: 18px; font-variant-numeric: tabular-nums; color: var(--bk-text); overflow-wrap: anywhere; }
.expense-legend { list-style: none; padding: 0; margin: 0; }
.expense-legend button {
  display: grid;
  grid-template-columns: 7px minmax(0, 1fr) minmax(0, auto) 32px;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 40px;
  padding: 8px 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--bk-text-regular);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.expense-legend button:not(:disabled):hover { background: var(--bk-primary-soft); }
.expense-legend button:disabled { cursor: default; }
.expense-legend__name, .expense-legend__amount { overflow-wrap: anywhere; }
.expense-legend__amount, .expense-legend__percent { text-align: right; font-variant-numeric: tabular-nums; }
.expense-legend__percent { color: var(--bk-text-secondary); }
.expense-more { align-self: flex-start; }
.expense-more .el-icon { margin-left: 6px; }

/* 图表与骨架/空态的定位容器：使覆盖层 inset:0 仅盖图表区，不再遮住卡片标题 */
.chart-slot {
  position: relative;
}

.chart-box {
  height: 260px;
  width: 100%;
  cursor: pointer;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bk-surface);
}

/* 首屏图表骨架（前端细化 #1）：绝对覆盖在 chart-box 上，与 el-empty 同位 */
.chart-skel {
  position: absolute;
  inset: 0;
  padding: 28px 24px;
  box-sizing: border-box;
  background: var(--bk-surface);
}

.pie-center {
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  transform: translateY(-50%);
  text-align: center;
  pointer-events: none;
}

.pie-center__value {
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.pie-center__label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 2px;
}

.pl-box {
  height: 200px;
  width: 100%;
  cursor: pointer;
}

.bottom-row {
  display: grid;
  align-items: start;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: var(--bk-gap);
}

.bottom-card {
  min-width: 0;
  container: dashboard-card / inline-size;
}

.recent-row {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) minmax(0, auto);
  align-items: start;
  gap: 12px;
  padding: var(--bk-row-padding) 0;
  border-bottom: 1px solid var(--bk-border-light);
  color: var(--bk-text);
  text-decoration: none;
  border-radius: 6px;
}
.recent-row:hover { background: color-mix(in srgb, var(--bk-primary-soft) 35%, var(--bk-surface)); }
.recent-row:hover .recent-row__note { color: var(--bk-button-primary); }
.recent-row:focus-visible { outline: 2px solid var(--bk-button-primary); outline-offset: 3px; }

.recent-row:last-child {
  border-bottom: none;
}

.recent-row__dot-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--el-fill-color);
  flex-shrink: 0;
}

.recent-row__meta {
  flex: 1;
  min-width: 0;
}

.recent-row__note {
  font-size: 14px;
  overflow-wrap: anywhere;
}

.recent-row__account, .recent-row__sub {
  color: var(--bk-text-secondary);
  font-size: 12px;
  margin-top: 4px;
  overflow-wrap: anywhere;
}
.recent-row__tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.recent-row__tag { padding: 1px 6px; border-radius: 4px; background: var(--bk-surface-2); color: var(--bk-text-secondary); font-size: 11px; overflow-wrap: anywhere; }
.recent-row__money { display: flex; flex-direction: column; gap: 3px; text-align: right; min-width: 0; }
.recent-row__type { font-size: 11px; color: var(--bk-text-secondary); }
.recent-row__amount { font-size: 15px; overflow-wrap: anywhere; }

.budget-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.budget-line__label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.budget-overview { --budget-tone: var(--bk-button-primary); }
.budget-overview.is-warning { --budget-tone: var(--bk-button-warning); }
.budget-overview.is-exception { --budget-tone: var(--bk-expense-text); }
.budget-badge { padding: 3px 9px; border-radius: var(--bk-radius-pill); font-size: 12px; color: var(--budget-tone); background: color-mix(in srgb, var(--budget-tone) 10%, var(--bk-surface)); }
.budget-balance { font-size: 32px; font-weight: 650; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.budget-usage { margin-top: 22px; margin-bottom: 10px; font-size: 12px; color: var(--bk-text-secondary); }
.budget-usage strong { color: var(--budget-tone); font-variant-numeric: tabular-nums; }
.budget-details { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 18px 0 0; }
.budget-details dt { font-size: 12px; color: var(--bk-text-secondary); }
.budget-details dd { margin: 4px 0 0; font-size: 14px; font-weight: 550; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.budget-hint { margin: 20px 0 0; padding-top: 16px; border-top: 1px solid var(--bk-border-light); font-size: 12px; color: var(--bk-text-secondary); }

@container dashboard-card (max-width: 440px) {
  .recent-row { grid-template-columns: 30px minmax(0, 1fr); }
  .recent-row__money { grid-column: 2; flex-direction: row; align-items: baseline; justify-content: space-between; gap: 12px; }
}

/* 窄窗口自适应（桌面端窗口可自由缩放）：统计卡 4→2→1，图表/底部双栏→单栏 */
@container page (max-width: 880px) {
  .hero {
    flex-wrap: wrap;
  }

  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .chart-row,
  .bottom-row {
    grid-template-columns: 1fr;
  }
}

@container page (max-width: 520px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }

  .hero {
    flex-direction: column;
    align-items: stretch;
  }
  .hero__main, .hero__level { flex-basis: auto; }
  .hero__flows { max-width: none; }
  .hero__level { width: 100%; }
  .trend-card .chart-slot { min-height: 280px; }
}
</style>
