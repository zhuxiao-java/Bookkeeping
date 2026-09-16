<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
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
  if (!monthTotalBudget.value) return 0
  return Math.round((Number(monthTotalBudget.value.amountUsed) / Number(monthTotalBudget.value.amount)) * 100)
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
  return Math.max(0, Number(b.amount) - Number(b.amountUsed))
})

function budgetStatus(pct: number): 'success' | 'warning' | 'exception' {
  if (pct >= 100) return 'exception'
  if (pct >= 80) return 'warning'
  return 'success'
}

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
  const colors = resolveDistinctColors(items)
  const data = items.map((c, i) => ({ name: c.name, value: Number(c.amount), itemStyle: { color: colors[i] } }))
  pie.render({
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c}（{d}%）' },
    series: [
      {
        type: 'pie',
        radius: ['42%', '60%'],
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
  <div class="page page--comfortable dash" v-loading="loading && transactions.length > 0">
    <!-- Hero：本月结余大数字 + 环比 + 等级 chip（叙事化首屏，弱化后台看板感） -->
    <section class="hero bk-enter">
      <div class="hero__main">
        <h1 class="hero__label">{{ monthLabel }} · 本月结余</h1>
        <div class="hero__balance" :class="heroBalance >= 0 ? 'amount-income' : 'amount-expense'">
          {{ heroBalance >= 0 ? '' : '-' }}¥{{ formatAmount(Math.abs(heroBalanceAnim), decimals) }}
        </div>
        <div class="hero__meta">
          <span class="hero__chip hero__chip--income">收入 ¥{{ formatAmount(monthSummary.income, decimals) }}</span>
          <span class="hero__chip hero__chip--expense">支出 ¥{{ formatAmount(monthSummary.expense, decimals) }}</span>
          <span
            v-if="heroDelta != null"
            class="hero__delta"
            :class="heroDelta > 0 ? 'is-up' : heroDelta < 0 ? 'is-down' : ''"
          >
            环比 {{ heroDelta > 0 ? '↑' : heroDelta < 0 ? '↓' : '·' }}
            ¥{{ formatAmount(Math.abs(heroDelta), decimals) }}
          </span>
        </div>
      </div>

      <!-- 等级 chip（后端等级接口可用时展示），点击进等级页 -->
      <div v-if="level.info" class="hero__level" role="link" tabindex="0" title="查看我的等级" @click="router.push('/level')" @keydown.enter="router.push('/level')">
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
      </div>
    </section>

    <!-- 签到卡片（后端签到接口可用时展示） -->
    <CheckInCard />

    <!-- 统计 tile（Hero 已展示本月收入/支出/结余，此处只补充不重复的指标） -->
    <section class="page-section" aria-labelledby="dashboard-summary-heading">
      <h2 id="dashboard-summary-heading" class="section-heading">本月概况</h2>
      <div class="stat-grid" data-guide="stat-cards">
        <div class="tile bk-enter">
          <div class="tile__label">总资产</div>
          <div class="tile__value">
            <template v-if="totalAssetByCurrency.length">
              <span v-for="([currency, total], i) in totalAssetByCurrency" :key="currency">
                {{ i > 0 ? ' + ' : '' }}{{ CURRENCY_SYMBOL[currency as 'CNY' | 'DOLLAR'] }}{{ formatAmount(total, decimals) }}
              </span>
            </template>
            <template v-else>¥0.00</template>
          </div>
          <div v-if="totalAssetByCurrency.length > 1" class="tile__sub">按币种分列，未做汇率换算</div>
        </div>
        <div class="tile bk-enter">
          <div class="tile__label">本月记账</div>
          <div class="tile__value">{{ monthTxCount }}<span class="tile__unit">笔</span></div>
        </div>
        <div class="tile bk-enter">
          <div class="tile__label">储蓄率</div>
          <div
            class="tile__value"
            :class="savingRate == null ? '' : savingRate < 0 ? 'amount-expense' : 'amount-income'"
          >{{ savingRate == null ? '—' : savingRate + '%' }}</div>
          <div class="tile__sub">本月结余 / 收入</div>
        </div>
        <div class="tile bk-enter">
          <div class="tile__label">预算剩余</div>
          <div class="tile__value" :class="budgetRemaining == null ? '' : 'amount-strong'">
            {{ budgetRemaining == null ? '—' : '¥' + formatAmount(budgetRemaining, decimals) }}
          </div>
          <div class="tile__sub">{{ monthTotalBudget ? '本月总预算可用' : '本月未设置预算' }}</div>
        </div>
      </div>
    </section>

    <!-- 图表区 -->
    <div class="chart-row">
      <el-card shadow="never" class="chart-card" data-guide="trend">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">{{ monthLabel }} 收支趋势</h2>
            <span class="card-hint">点击图表查看对应流水</span>
          </div>
        </template>
        <div class="chart-slot">
          <div ref="trendEl" class="chart-box" title="点击查看当天流水" />
          <el-skeleton v-if="loading && !monthTransactions.length" class="chart-skel" animated :rows="5" />
          <el-empty v-else-if="!monthTransactions.length" description="本月暂无记录" class="chart-empty" />
        </div>
      </el-card>

      <el-card shadow="never" class="chart-card" data-guide="pie">
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
          <el-empty v-else-if="!expenseAgg.length" description="本月暂无支出" class="chart-empty" />
          <div v-else class="pie-center">
            <div class="pie-center__value">¥{{ formatAmount(expenseTotal, decimals) }}</div>
            <div class="pie-center__label">本月支出</div>
          </div>
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
          <el-button link type="primary" @click="router.push('/report')">查看报表</el-button>
        </div>
      </template>
      <div class="chart-slot">
        <div ref="plEl" class="pl-box" title="点击查看该月流水" />
        <el-skeleton v-if="loading && !transactions.length" class="chart-skel" animated :rows="4" />
        <el-empty v-else-if="!transactions.length" description="还没有记录" class="chart-empty" />
      </div>
    </el-card>

    <!-- 最近流水 + 预算概况 -->
    <div class="bottom-row" data-guide="recent">
      <el-card shadow="never" class="bottom-card">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">最近流水</h2>
            <el-button link type="primary" @click="router.push('/transaction')">查看全部</el-button>
          </div>
        </template>
        <template v-if="recentTransactions.length">
          <div v-for="row in recentTransactions" :key="row.id" class="recent-row">
            <CategoryDot
              v-if="dict.categoryById(row.categoryId)"
              :name="dict.categoryById(row.categoryId)!.name"
              :icon="dict.categoryById(row.categoryId)!.icon"
              :color="dict.categoryById(row.categoryId)!.color"
              :size="30"
            />
            <span v-else class="recent-row__dot-placeholder" />
            <div class="recent-row__meta">
              <div class="recent-row__note">{{ row.note || transactionTypeLabel(row.type) }}</div>
              <div class="recent-row__sub">
                {{ formatDateTime(row.transactionDate) }}
                <template v-if="rowTags(row).length">
                  · <span v-for="tag in rowTags(row)" :key="tag.id" class="recent-row__tag" :style="{ color: tag.color }">{{ tag.name }}</span>
                </template>
              </div>
            </div>
            <div class="recent-row__amount amount-strong" :class="TRANSACTION_TYPE_COLOR_CLASS[row.type]">
              {{ row.type === 'income' ? '+' : '-' }}{{ formatAmount(row.amount, decimals) }}
            </div>
          </div>
        </template>
        <EmptyState v-else-if="!loading" description="还没有记录，点击顶栏「记一笔」开始记账" />
      </el-card>

      <el-card shadow="never" class="bottom-card">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">{{ monthLabel }} 预算</h2>
            <el-button link type="primary" @click="router.push('/budget')">管理预算</el-button>
          </div>
        </template>
        <template v-if="monthTotalBudget">
          <div class="budget-line">
            <span class="budget-line__label">月度总预算</span>
            <span>
              ¥{{ formatAmount(Number(monthTotalBudget.amountUsed), decimals) }} / ¥{{ formatAmount(monthTotalBudget.amount, decimals) }}
            </span>
          </div>
          <el-progress :percentage="budgetPct" :status="budgetStatus(budgetPct)" :stroke-width="14" />
          <el-alert
            v-if="budgetPct >= 100"
            title="总预算已超支，请注意控制支出"
            type="error"
            :closable="false"
            show-icon
            class="budget-alert"
          />
          <el-alert
            v-else-if="budgetPct >= 80"
            title="总预算即将用完，请留意支出"
            type="warning"
            :closable="false"
            show-icon
            class="budget-alert"
          />
        </template>
        <EmptyState v-else-if="!loading" description="本月未设置预算">
          <el-button type="primary" @click="router.push('/budget')">去设置预算</el-button>
        </EmptyState>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.page-section > .section-heading {
  padding-inline: 4px;
}

/* —— Hero 叙事首屏 —— */
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 26px 28px;
  border-radius: var(--bk-radius-xl);
  background:
    radial-gradient(120% 140% at 0% 0%, rgba(47, 181, 124, 0.14) 0%, transparent 55%),
    linear-gradient(135deg, var(--bk-surface) 0%, var(--bk-surface) 100%);
  border: 0;
}

.hero__main {
  min-width: 0;
}

.hero__label {
  margin: 0;
  font-size: 13px;
  color: var(--bk-text-secondary);
  font-weight: 500;
}

.hero__balance {
  font-size: var(--bk-font-display);
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

.hero__chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: var(--bk-radius-pill);
  font-size: 13px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  background: var(--bk-surface-2);
  color: var(--bk-text-regular);
}

.hero__chip--income {
  background: rgba(47, 181, 124, 0.12);
  color: var(--bk-income-text);
}

.hero__chip--expense {
  background: rgba(240, 98, 93, 0.12);
  color: var(--bk-expense-text);
}

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
  flex-shrink: 0;
  padding: 14px 16px;
  border-radius: var(--bk-radius-lg);
  background: var(--bk-surface-2);
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
}

.hero__level:hover {
  box-shadow: var(--bk-shadow-sm);
}

.hero__level:focus-visible {
  outline: 2px solid var(--bk-button-primary);
  outline-offset: 3px;
}

.hero__level-meta {
  min-width: 168px;
}

.hero__level-title {
  font-size: 14px;
  font-weight: 700;
}

.hero__level-hint {
  color: var(--bk-text-secondary);
  font-size: 11px;
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
  font-weight: 800;
  color: var(--bk-primary);
  font-variant-numeric: tabular-nums;
}

.hero__level-expl {
  color: var(--bk-text-secondary);
  font-size: 11px;
  margin-top: 2px;
}

/* —— 统计 tile —— */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--bk-gap);
}

.tile {
  padding: 22px 24px;
  min-width: 0;
  border-radius: var(--bk-radius-lg);
  background: var(--bk-surface);
}

.tile__label {
  color: var(--bk-text-secondary);
  font-size: 13px;
}

.tile__value {
  font-size: 26px;
  font-weight: 700;
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
  grid-template-columns: 3fr 2fr;
  gap: var(--bk-gap);
}

.chart-card {
  position: relative;
  min-width: 0;
}

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
  background: var(--bk-surface);
}

.pie-center {
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  transform: translateY(-60%);
  text-align: center;
  pointer-events: none;
}

.pie-center__value {
  font-size: 18px;
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
  grid-template-columns: 3fr 2fr;
  gap: var(--bk-gap);
}

.bottom-card {
  min-width: 0;
}

.recent-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--bk-border-light);
}

.recent-row:last-child {
  border-bottom: none;
}

.recent-row__dot-placeholder {
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-row__sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 2px;
}

.recent-row__tag {
  margin-right: 4px;
}

.recent-row__amount {
  font-size: 15px;
}

.budget-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.budget-line__label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.budget-alert {
  margin-top: 12px;
}

/* 窄窗口自适应（桌面端窗口可自由缩放）：统计卡 4→2→1，图表/底部双栏→单栏 */
@media (max-width: 1180px) {
  .hero {
    flex-wrap: wrap;
  }

  .dash :deep(.cc__row) {
    flex-wrap: wrap;
  }

  .dash :deep(.cc__main) {
    flex: 1 1 240px;
  }

  .dash :deep(.cc__week) {
    margin-left: 70px;
  }

  .dash :deep(.cc__link) {
    margin-left: auto;
  }

  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .chart-row,
  .bottom-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .dash :deep(.cc__week) {
    margin-left: 0;
  }

  .stat-grid {
    grid-template-columns: 1fr;
  }

  .hero {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
