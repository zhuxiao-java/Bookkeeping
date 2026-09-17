<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, toRaw, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { transactionApi } from '@/api'
import { useDictStore } from '@/stores/dict'
import { useSettingsStore } from '@/stores/settings'
import type { Transaction } from '@/types/model'
import { addDays, addYears, daysInclusive, formatAmount, fenToYuan, toFen } from '@/utils/format'
import {
  adaptCategory,
  adaptMonthly,
  adaptTrend,
  type AccountBalanceChart,
  type CategoryAgg,
  type Granularity,
  type MonthlyBalance,
  type TrendBucket
} from '@/utils/aggregate'
import {
  computeReportAgg,
  EMPTY_REPORT_AGG,
  type ReportAggInput,
  type ReportAggResult
} from '@/utils/reportAgg'
import ReportAggWorker from '@/workers/reportAgg.worker?worker'
import { exportCsv } from '@/utils/csv'
import { useChart } from '@/composables/useChart'
import { chartPalette, resolveDistinctColors } from '@/utils/chartTheme'
import { bus, TRANSACTION_CHANGED, CATEGORY_CHANGED, ACCOUNT_CHANGED } from '@/utils/bus'
import CategoryDot from '@/components/CategoryDot.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 统计报表页（需求文档 4.3）：
 * 趋势图 + 分类占比环形图 + 月度盈亏 + 账户余额变化图（FR-RPT-03）
 * + 自定义区间对比（RP-02）+ CSV / PDF 导出（RP-01）。
 */
const dict = useDictStore()
const settings = useSettingsStore()
const router = useRouter()

const loading = ref(false)
const allTransactions = ref<Transaction[]>([])

/**
 * 后端统计聚合结果（stats/* 各自 silent，成功才填充；失败保持 null → 对应图表降级本地聚合）。
 * allTransactions 仍保留：降级聚合、空态判断、CSV 导出都要用。
 */
const beSummary = ref<{ income: number; expense: number } | null>(null)
const beTrend = ref<TrendBucket[] | null>(null)
const beCategory = ref<CategoryAgg[] | null>(null)
const beMonthly = ref<MonthlyBalance[] | null>(null)
/** 四路全失败即置真：本会话不再重复探测后端，纯本地聚合（与流水页 rangePageOk 同模式） */
let statsDead = false
/** 统计请求序号：防竞态，仅最新一次 refreshStats 的结果可落地（NEW-08） */
let statsSeq = 0

const granularity = ref<Granularity>('month')
const trendChartType = ref<'bar' | 'line'>('bar')
const incomeExpense = ref<'expense' | 'income'>('expense')
/** 分类聚合口径：root=父分类（子分类归并到一级）／self=子分类（按交易实际分类） */
const categoryGroupBy = ref<'root' | 'self'>('root')

const now = new Date()
const pad = (n: number) => String(n).padStart(2, '0')
const todayStr = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`

const filters = reactive({
  dateRange: [`${now.getFullYear()}-01-01`, todayStr] as [string, string]
})

const trend = useChart()
const trendEl = trend.elRef
const pie = useChart()
const pieEl = pie.elRef
const balance = useChart()
const balanceEl = balance.elRef
const balanceTrend = useChart()
const balanceTrendEl = balanceTrend.elRef

// —— 报表本地聚合迁 Web Worker（OPT-04）——
// 默认在 Worker 中计算趋势/分类/月度/账户余额推演等重聚合，不阻塞主线程；
// Worker 不可用（加载失败/运行异常）时降级为主线程同步计算，保证功能可用。
const localAgg = ref<ReportAggResult>(EMPTY_REPORT_AGG)
let aggWorker: Worker | null = null
let workerOk = true
/** 聚合作业序号：防竞态，仅最新一次下发的结果可落地 */
let aggSeq = 0
let aggTimer: ReturnType<typeof setTimeout> | null = null

function initAggWorker() {
  try {
    aggWorker = new ReportAggWorker()
    aggWorker.onmessage = (
      e: MessageEvent<{ id: number; result?: ReportAggResult; error?: string }>
    ) => {
      const { id, result, error } = e.data
      if (id !== aggSeq) return // 竞态保护：丢弃过期结果
      if (error || !result) {
        workerOk = false
        runAggSync()
        return
      }
      localAgg.value = result
      renderCharts()
    }
    aggWorker.onerror = () => {
      // Worker 脚本加载/运行失败：永久降级为主线程同步聚合
      workerOk = false
      aggWorker?.terminate()
      aggWorker = null
      runAggSync()
    }
  } catch {
    workerOk = false
  }
}

/** 采集当前聚合输入快照（全量流水 + 字典 + 区间/粒度/口径/对比区间） */
function buildAggInput(): ReportAggInput {
  return {
    // toRaw 脱掉响应式 Proxy：postMessage 结构化克隆无法克隆代理对象（DataCloneError）
    transactions: toRaw(allTransactions.value),
    accounts: toRaw(dict.accounts),
    categories: toRaw(dict.categories),
    start: filters.dateRange[0],
    end: filters.dateRange[1],
    granularity: granularity.value,
    incomeExpense: incomeExpense.value,
    categoryGroupBy: categoryGroupBy.value,
    compareRange: compareRange.value
  }
}

/** 主线程同步降级：Worker 不可用时直接计算并落地 */
function runAggSync() {
  aggSeq++ // 作废在途 Worker 结果
  try {
    localAgg.value = computeReportAgg(buildAggInput())
  } catch {
    // 计算异常保留上一次结果
  }
  renderCharts()
}

/** 触发一次聚合：Worker 健康则异步下发，否则同步降级 */
function runAgg() {
  if (workerOk && aggWorker) {
    const id = ++aggSeq
    try {
      aggWorker.postMessage({ id, input: buildAggInput() })
    } catch {
      // 输入不可结构化克隆（如残留响应式代理）：永久降级为主线程同步聚合
      workerOk = false
      aggWorker.terminate()
      aggWorker = null
      runAggSync()
    }
  } else {
    runAggSync()
  }
}

/** 输入变化后去抖触发聚合，避免快速拖动区间/切换控件时频繁重算 */
function scheduleAgg() {
  if (aggTimer != null) clearTimeout(aggTimer)
  aggTimer = setTimeout(() => {
    aggTimer = null
    runAgg()
  }, 120)
}

async function load() {
  loading.value = true
  try {
    // 首屏并行（NEW-08）：stats 直接驱动趋势/占比/月度图表，selectAll 仅供账户余额图、
    // 区间对比与降级聚合，两者互不阻塞；selectAll 回来后再补渲染一次账户余额图/降级项
    await Promise.all([
      transactionApi
        .selectAll()
        .then((rows) => { allTransactions.value = rows })
        .catch(() => { /* 全量拉取失败：保留已有数据，图表走 stats/降级 */ }),
      refreshStats()
    ])
    renderCharts()
  } finally {
    loading.value = false
  }
}

/**
 * 拉取后端统计聚合（stats/summary|trend|category|monthly）。四路各自 silent：
 * 任一失败该项 beX 保持 null → 该图表降级本地聚合；四路全失败置 statsDead，本会话不再探测。
 * 影响聚合口径的控件（范围 / 粒度 / 收支维度 / 分类口径）变化时重跑。
 */
async function refreshStats() {
  const seq = ++statsSeq
  if (statsDead) {
    renderCharts()
    return
  }
  const [start, end] = filters.dateRange
  const [sumRes, trendRes, catRes, monRes] = await Promise.allSettled([
    transactionApi.summary(start, end, { silent: true }),
    transactionApi.trend(start, end, granularity.value, { silent: true }),
    transactionApi.category(start, end, incomeExpense.value, categoryGroupBy.value, { silent: true }),
    transactionApi.monthly(start, end, { silent: true })
  ])
  // 竞态保护（NEW-08）：仅最新一次请求的结果才生效，避免快速切换粒度/区间时旧响应覆盖新结果
  if (seq !== statsSeq) return
  beSummary.value =
    sumRes.status === 'fulfilled'
      ? { income: Number(sumRes.value.income), expense: Number(sumRes.value.expense) }
      : null
  beTrend.value = trendRes.status === 'fulfilled' ? adaptTrend(trendRes.value, granularity.value) : null
  beCategory.value = catRes.status === 'fulfilled' ? adaptCategory(catRes.value) : null
  beMonthly.value =
    monRes.status === 'fulfilled'
      ? adaptMonthly(monRes.value.rows ?? [], start.slice(0, 7), end.slice(0, 7))
      : null
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

/** 区间内流水条数（空态判断）：来自 Worker 聚合，避免主线程 filter 万条 */
const rangeCount = computed(() => localAgg.value.rangeCount)

/** 范围内收支汇总：优先后端 stats/summary，降级 Worker 本地聚合 */
const summary = computed(() => beSummary.value ?? localAgg.value.summary)

/** 分类聚合（当前收入/支出维度 + 父/子分类口径）：优先后端 stats/category，降级 Worker 本地聚合 */
const categoryAgg = computed(() => beCategory.value ?? localAgg.value.category)

const pieTotal = computed(() =>
  categoryAgg.value.reduce((sum, c) => sum + Number(c.amount), 0)
)

/** 月度盈亏：优先后端 stats/monthly（前端补零为连续月），降级 Worker 本地聚合 */
const balanceRows = computed(() => beMonthly.value ?? localAgg.value.monthly)

/** 月度盈亏明细（附带范围内累计结余，以分累加避免浮点误差） */
const balanceTable = computed(() => {
  let cumFen = 0
  return balanceRows.value.map((r) => {
    cumFen += toFen(r.balance)
    return { ...r, cumulative: fenToYuan(cumFen) }
  })
})

/** 盈亏概览：盈余/亏损月份数 + 累计结余 */
const balanceSummary = computed(() => {
  let surplus = 0
  let deficit = 0
  for (const r of balanceRows.value) {
    if (r.balance > 0) surplus++
    else if (r.balance < 0) deficit++
  }
  const last = balanceTable.value[balanceTable.value.length - 1]
  return { surplus, deficit, cumulative: last ? last.cumulative : 0 }
})

/** 账户余额变化（FR-RPT-03）：Worker 按流水推演，依赖 selectAll 全量流水 + 账户字典 */
const accountBalance = computed<AccountBalanceChart>(() => localAgg.value.balance)

// —— 自定义区间对比（RP-02）：与上一期（环比）或去年同期（同比）对比 ——
// 在 Worker 中对 selectAll 全量流水按区间聚合，不额外请求后端、不阻塞主线程。
const compareMode = ref<'off' | 'prev' | 'year'>('off')

/** 对比区间：环比=紧邻的等长上一期；同比=去年同期（各减 1 年） */
const compareRange = computed<[string, string] | null>(() => {
  const [start, end] = filters.dateRange
  if (compareMode.value === 'prev') {
    const len = daysInclusive(start, end)
    const prevEnd = addDays(start, -1)
    return [addDays(prevEnd, -(len - 1)), prevEnd]
  }
  if (compareMode.value === 'year') {
    return [addYears(start, -1), addYears(end, -1)]
  }
  return null
})

/** 本期与对比期的收入/支出/结余，及各自变化量（本期 − 对比期）；数据来自 Worker 聚合 */
const compareData = computed(() => {
  const range = compareRange.value
  const c = localAgg.value.compare
  if (!range || !c) return null
  const curBalance = c.current.income - c.current.expense
  const prevBalance = c.previous.income - c.previous.expense
  return {
    range,
    current: { ...c.current, balance: curBalance },
    previous: { ...c.previous, balance: prevBalance },
    delta: {
      income: c.current.income - c.previous.income,
      expense: c.current.expense - c.previous.expense,
      balance: curBalance - prevBalance
    }
  }
})

/** 变化百分比文案（对比期为 0 时不显示百分比） */
function deltaPct(cur: number, prev: number): string {
  if (!prev) return ''
  const pct = ((cur - prev) / Math.abs(prev)) * 100
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%`
}

/** 对比表格行：收入/结余上升为好（绿）、支出上升为差（红） */
const compareRows = computed(() => {
  const d = compareData.value
  if (!d) return []
  const row = (label: string, cur: number, prev: number, goodWhenUp: boolean) => {
    const delta = cur - prev
    const tone = delta === 0 ? '' : (delta > 0) === goodWhenUp ? 'amount-income' : 'amount-expense'
    return { label, current: cur, previous: prev, delta, pct: deltaPct(cur, prev), tone }
  }
  return [
    row('收入', d.current.income, d.previous.income, true),
    row('支出', d.current.expense, d.previous.expense, false),
    row('结余', d.current.balance, d.previous.balance, true)
  ]
})

function renderCharts() {
  renderTrend()
  renderPie()
  renderBalance()
  renderAccountBalance()
}

/**
 * 图表钻取（前端细化 #3）：点分类占比扇区 / 月度盈亏柱 → 跳流水页带筛选。
 * 流水页已消费 ?categoryId=&start=&end=（TransactionView.applyFiltersFromRoute）。
 * 趋势图因粒度 key 形式不一（周/月/年）不做钻取，避免区间歧义。
 */
function drillToTransaction(query: Record<string, string | number>) {
  router.push({ path: '/transaction', query })
}
pie.onClick((p: any) => {
  const item = categoryAgg.value[p.dataIndex]
  if (item?.categoryId) {
    drillToTransaction({ categoryId: item.categoryId, start: filters.dateRange[0], end: filters.dateRange[1] })
  }
})
balance.onClick((p: any) => {
  const row = balanceRows.value[p.dataIndex]
  if (!row) return
  const start = `${row.year}-${pad(row.month)}-01`
  const end = `${row.year}-${pad(row.month)}-${pad(new Date(row.year, row.month, 0).getDate())}`
  drillToTransaction({ start, end })
})

function renderTrend() {
  // 优先后端 stats/trend（已适配为 TrendBucket），降级 Worker 本地分桶
  const buckets = beTrend.value ?? localAgg.value.trend
  const type = trendChartType.value
  const p = chartPalette()
  trend.render({
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入', '支出'], right: 0, textStyle: { color: p.axisText } },
    grid: { left: 56, right: 16, top: 40, bottom: 28 },
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
      {
        name: '收入',
        type,
        data: buckets.map((b) => b.income),
        itemStyle: { color: p.income },
        barMaxWidth: 18,
        smooth: true
      },
      {
        name: '支出',
        type,
        data: buckets.map((b) => b.expense),
        itemStyle: { color: p.expense },
        barMaxWidth: 18,
        smooth: true
      }
    ]
  })
}

function renderPie() {
  const p = chartPalette()
  const items = categoryAgg.value
  // 渲染期去重分配：子分类继承父色时也能在饼图里拉开，避免同色扇区无法区分
  const colors = resolveDistinctColors(items)
  const data = items.map((c, i) => ({
    name: c.name,
    value: Number(c.amount),
    itemStyle: { color: colors[i] }
  }))
  pie.render({
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c}（{d}%）' },
    legend: { bottom: 0, textStyle: { color: p.axisText } },
    series: [
      {
        type: 'pie',
        radius: ['40%', '62%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 4, borderColor: 'transparent', borderWidth: 2 },
        // 百分比直标作为颜色的冗余编码（色弱友好），与底部图例互补
        label: { show: true, formatter: '{d}%', fontSize: 10, color: p.axisText },
        labelLine: { show: true, length: 4, length2: 4 },
        minShowLabelAngle: 12,
        data
      }
    ]
  })
}

/** 月度盈亏：正负分色柱状图（盈余绿向上、亏损红向下，值轴天然含 0 基准） */
function renderBalance() {
  const rows = balanceRows.value
  const p = chartPalette()
  balance.render({
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
    grid: { left: 56, right: 16, top: 24, bottom: 28 },
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
        name: '结余',
        type: 'bar',
        barMaxWidth: 32,
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
          formatter: (p: any) =>
            p.value === 0 ? '' : `${p.value > 0 ? '+' : '-'}${formatAmount(Math.abs(p.value))}`
        }
      }
    ]
  })
}

/** 账户余额变化图：每个账户一条折线，X 轴为期初 + 区间内变动日（阶梯变化） */
function renderAccountBalance() {
  const chart = accountBalance.value
  const p = chartPalette()
  balanceTrend.render({
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v: number) => `¥${formatAmount(v)}`
    },
    legend: {
      type: 'scroll',
      bottom: 0,
      data: chart.series.map((s) => s.name),
      textStyle: { color: p.axisText }
    },
    grid: { left: 60, right: 16, top: 24, bottom: 40 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: chart.labels.map((d) => d.slice(5)),
      axisLabel: { color: p.axisText },
      axisLine: { lineStyle: { color: p.axisLine } }
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { color: p.axisText },
      splitLine: { lineStyle: { color: p.splitLine } }
    },
    series: chart.series.map((s) => ({
      name: s.name,
      type: 'line',
      step: 'end',
      smooth: false,
      showSymbol: false,
      data: s.data,
      itemStyle: { color: s.color },
      lineStyle: { color: s.color, width: 2 }
    }))
  })
}

/** 导出 PDF（RP-01）：走浏览器打印，打印样式见 styles/index.css 的 @media print，用户在打印对话框选“另存为 PDF” */
function exportPdf() {
  window.print()
}

function exportDetail() {
  if (!categoryAgg.value.length) {
    ElMessage.info('当前范围没有可导出的数据')
    return
  }
  exportCsv(
    `分类明细_${filters.dateRange[0]}_${filters.dateRange[1]}.csv`,
    ['分类', '金额', '笔数', '占比'],
    categoryAgg.value.map((c) => [
      c.name,
      formatAmount(c.amount),
      c.count,
      `${((Number(c.amount) / (pieTotal.value || 1)) * 100).toFixed(1)}%`
    ])
  )
}

function exportBalance() {
  if (!rangeCount.value) {
    ElMessage.info('当前范围没有可导出的数据')
    return
  }
  exportCsv(
    `月度盈亏_${filters.dateRange[0]}_${filters.dateRange[1]}.csv`,
    ['月份', '收入', '支出', '结余', '累计结余'],
    balanceTable.value.map((r) => [
      `${r.year}-${String(r.month).padStart(2, '0')}`,
      formatAmount(r.income),
      formatAmount(r.expense),
      formatAmount(r.balance),
      formatAmount(r.cumulative)
    ])
  )
}

function onChanged() {
  load()
}

onMounted(() => {
  initAggWorker()
  load()
  bus.on(TRANSACTION_CHANGED, onChanged)
  bus.on(CATEGORY_CHANGED, onChanged)
  bus.on(ACCOUNT_CHANGED, onChanged)
})

// 任一聚合输入变化即去抖重算（Worker 异步落地后自动重绘）
watch(allTransactions, scheduleAgg)
watch(() => [filters.dateRange[0], filters.dateRange[1]], scheduleAgg)
watch([granularity, incomeExpense, categoryGroupBy, compareMode], scheduleAgg)
watch(() => [dict.accounts, dict.categories], scheduleAgg)

// 主题切换后图表按新 CSS token 重绘（canvas 不随 CSS 变量自动刷新）
watch(() => settings.isDark, () => renderCharts())

onBeforeUnmount(() => {
  bus.off(TRANSACTION_CHANGED, onChanged)
  bus.off(CATEGORY_CHANGED, onChanged)
  bus.off(ACCOUNT_CHANGED, onChanged)
  if (aggTimer != null) clearTimeout(aggTimer)
  aggWorker?.terminate()
  aggWorker = null
})
</script>

<template>
  <div class="page page--comfortable quiet-controls" v-loading="loading && allTransactions.length > 0">
    <!-- 页头叙事 -->
    <header class="page-head page-head--actions">
      <div class="row-copy">
        <h1 class="page-head__title">报表</h1>
        <p class="page-head__sub">多维度查看你的收支</p>
      </div>
      <div class="toolbar page-head__actions">
        <el-button text data-guide="rp-pdf" @click="exportPdf">导出 PDF</el-button>
      </div>
    </header>

    <!-- 筛选条（轻量工具条；保留 filter-card 类供打印样式钩子） -->
    <div class="surface filter-card quiet-controls bk-enter" data-guide="rp-range">
      <div class="split-row">
        <div class="row-copy">
          <h2 id="report-range-heading" class="row-copy__title">统计范围</h2>
          <p class="row-copy__desc">选择一段时间，回顾收支变化</p>
        </div>
        <div class="toolbar" role="group" aria-labelledby="report-range-heading">
          <el-radio-group v-model="granularity" class="segment" aria-label="统计粒度" @change="refreshStats">
            <el-radio-button value="day">日</el-radio-button>
            <el-radio-button value="week">周</el-radio-button>
            <el-radio-button value="month">月</el-radio-button>
            <el-radio-button value="year">年</el-radio-button>
          </el-radio-group>

          <el-date-picker
            v-model="filters.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="~"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            aria-label="统计日期范围"
            @change="refreshStats"
          />
        </div>
      </div>

      <div class="split-row">
        <div class="row-copy">
          <h2 id="report-compare-heading" class="row-copy__title">区间对比</h2>
          <p class="row-copy__desc">和上一期或去年同期对照看看</p>
        </div>
        <el-radio-group v-model="compareMode" class="segment" aria-labelledby="report-compare-heading">
          <el-radio-button value="off">不对比</el-radio-button>
          <el-radio-button value="prev">环比上期</el-radio-button>
          <el-radio-button value="year">同比去年</el-radio-button>
        </el-radio-group>
      </div>

    </div>

    <section class="page-section" aria-labelledby="report-summary-heading">
      <h2 id="report-summary-heading" class="section-heading">区间汇总</h2>
      <div class="surface report-summary">
        <div><span>范围内收入</span><strong class="amount-income">¥{{ formatAmount(summary.income) }}</strong></div>
        <div><span>范围内支出</span><strong class="amount-expense">¥{{ formatAmount(summary.expense) }}</strong></div>
      </div>

    <!-- 区间对比（RP-02）：本期 vs 环比上期 / 同比去年 -->
    <el-card v-if="compareData" shadow="never" class="compare-card">
      <template #header>
        <div class="card-head">
          <h2 class="card-head__title">区间对比</h2>
          <span class="card-hint">
            本期 {{ filters.dateRange[0] }} ~ {{ filters.dateRange[1] }}
            · 对比期 {{ compareData.range[0] }} ~ {{ compareData.range[1] }}
          </span>
        </div>
      </template>
      <el-table :data="compareRows" style="width: 100%">
        <el-table-column prop="label" label="项目" min-width="100" />
        <el-table-column label="本期" min-width="140" align="right">
          <template #default="{ row }">¥{{ formatAmount(row.current) }}</template>
        </el-table-column>
        <el-table-column label="对比期" min-width="140" align="right">
          <template #default="{ row }">¥{{ formatAmount(row.previous) }}</template>
        </el-table-column>
        <el-table-column label="变化" min-width="200" align="right">
          <template #default="{ row }">
            <span :class="row.tone">
              {{ row.delta >= 0 ? '+' : '-' }}¥{{ formatAmount(Math.abs(row.delta)) }}
              <em v-if="row.pct" class="compare-card__pct">{{ row.pct }}</em>
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    </section>

    <!-- 图表区 -->
    <section class="page-section" aria-labelledby="report-analysis-heading">
      <h2 id="report-analysis-heading" class="section-heading">图表分析</h2>
    <div class="chart-row">
      <el-card shadow="never" class="chart-card chart-card--wide" data-guide="rp-trend">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">收支趋势</h2>
            <el-radio-group v-model="trendChartType" class="segment" aria-label="趋势图样式" size="small" @change="renderTrend">
              <el-radio-button value="bar">柱状</el-radio-button>
              <el-radio-button value="line">折线</el-radio-button>
            </el-radio-group>
          </div>
        </template>
        <div class="chart-slot">
          <div ref="trendEl" class="chart-box" />
          <el-skeleton v-if="loading && !rangeCount" class="chart-skel" animated :rows="6" />
          <EmptyState v-else-if="!rangeCount" :size="88" description="范围内暂无数据" class="chart-empty" />
        </div>
      </el-card>

      <el-card shadow="never" class="chart-card" data-guide="rp-pie">
        <template #header>
          <div class="card-head">
            <h2 class="card-head__title">分类占比</h2>
            <div class="card-head__ctrls">
              <el-radio-group v-model="incomeExpense" class="segment" aria-label="收支类型" size="small" @change="refreshStats">
                <el-radio-button value="expense">支出</el-radio-button>
                <el-radio-button value="income">收入</el-radio-button>
              </el-radio-group>
              <el-radio-group v-model="categoryGroupBy" class="segment" aria-label="分类层级" size="small" @change="refreshStats">
                <el-radio-button value="root">父分类</el-radio-button>
                <el-radio-button value="self">子分类</el-radio-button>
              </el-radio-group>
            </div>
          </div>
        </template>
        <div class="chart-slot">
          <div ref="pieEl" class="chart-box chart-box--drill" title="点击查看该分类流水" />
          <el-skeleton v-if="loading && !categoryAgg.length" class="chart-skel" animated :rows="6" />
          <EmptyState v-else-if="!categoryAgg.length" :size="88" description="范围内暂无数据" class="chart-empty" />
          <div v-else class="pie-center">
            <div class="pie-center__value">¥{{ formatAmount(pieTotal) }}</div>
            <div class="pie-center__label">总{{ incomeExpense === 'expense' ? '支出' : '收入' }}</div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 月度盈亏 -->
    <el-card shadow="never" class="chart-card pl-card" data-guide="rp-pl">
      <template #header>
        <div class="card-head">
          <h2 class="card-head__title">月度盈亏（按月）</h2>
          <span class="card-metric">
            盈余 <b class="amount-income">{{ balanceSummary.surplus }}</b> 个月 ·
            亏损 <b class="amount-expense">{{ balanceSummary.deficit }}</b> 个月 ·
            累计结余
            <b :class="balanceSummary.cumulative >= 0 ? 'amount-income' : 'amount-expense'">
              {{ balanceSummary.cumulative >= 0 ? '+' : '-' }}¥{{ formatAmount(Math.abs(balanceSummary.cumulative)) }}
            </b>
          </span>
        </div>
      </template>
      <div class="chart-slot">
        <div ref="balanceEl" class="chart-box chart-box--drill" title="点击查看该月流水" />
        <el-skeleton v-if="loading && !rangeCount" class="chart-skel" animated :rows="6" />
        <EmptyState v-else-if="!rangeCount" :size="88" description="范围内暂无数据" class="chart-empty" />
      </div>
    </el-card>

    <!-- 账户余额变化（FR-RPT-03） -->
    <el-card shadow="never" class="chart-card" data-guide="rp-balance">
      <template #header>
        <div class="card-head">
          <h2 class="card-head__title">账户余额变化</h2>
          <span class="card-hint">按流水推演（期初余额 + 区间净变动）</span>
        </div>
      </template>
      <div class="chart-slot">
        <div ref="balanceTrendEl" class="chart-box" />
        <el-skeleton v-if="loading && !accountBalance.series.length" class="chart-skel" animated :rows="6" />
        <EmptyState v-else-if="!accountBalance.series.length" :size="88" description="范围内暂无账户变动" class="chart-empty" />
      </div>
    </el-card>

    </section>

    <section class="page-section" aria-labelledby="report-detail-heading">
      <h2 id="report-detail-heading" class="section-heading">收支明细</h2>
    <!-- 月度盈亏明细 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <h2 class="card-head__title">月度盈亏明细</h2>
          <el-button text size="small" @click="exportBalance">导出 CSV</el-button>
        </div>
      </template>
      <el-table :data="balanceTable" style="width: 100%">
        <el-table-column label="月份" min-width="110">
          <template #default="{ row }">{{ row.year }}年{{ row.month }}月</template>
        </el-table-column>
        <el-table-column label="收入" min-width="130" align="right">
          <template #default="{ row }"><span class="amount-income">¥{{ formatAmount(row.income) }}</span></template>
        </el-table-column>
        <el-table-column label="支出" min-width="130" align="right">
          <template #default="{ row }"><span class="amount-expense">¥{{ formatAmount(row.expense) }}</span></template>
        </el-table-column>
        <el-table-column label="结余" min-width="190" align="right">
          <template #default="{ row }">
            <el-tag
              :type="row.balance > 0 ? 'success' : row.balance < 0 ? 'danger' : 'info'"
              effect="light"
              size="small"
              class="pl-tag"
            >
              {{ row.balance > 0 ? '盈余' : row.balance < 0 ? '亏损' : '持平' }}
            </el-tag>
            <span class="amount-strong" :class="row.balance >= 0 ? 'amount-income' : 'amount-expense'">
              {{ row.balance >= 0 ? '+' : '-' }}¥{{ formatAmount(Math.abs(row.balance)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="累计结余" min-width="140" align="right">
          <template #default="{ row }">
            <span :class="row.cumulative >= 0 ? 'amount-income' : 'amount-expense'">
              {{ row.cumulative >= 0 ? '+' : '-' }}¥{{ formatAmount(Math.abs(row.cumulative)) }}
            </span>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState :size="88" description="范围内暂无数据" />
        </template>
      </el-table>
    </el-card>

    <!-- 分类明细 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <h2 class="card-head__title">分类明细（{{ incomeExpense === 'expense' ? '支出' : '收入' }} · {{ categoryGroupBy === 'root' ? '父分类' : '子分类' }}）</h2>
          <el-button text size="small" data-guide="rp-export" @click="exportDetail">导出 CSV</el-button>
        </div>
      </template>
      <el-table :data="categoryAgg" style="width: 100%">
        <el-table-column label="分类" min-width="160">
          <template #default="{ row }">
            <div class="cat-cell">
              <CategoryDot :name="row.name" :icon="row.icon" :color="row.color" :size="28" />
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="160" align="right">
          <template #default="{ row }">¥{{ formatAmount(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="count" label="笔数" width="100" />
        <el-table-column label="占比" min-width="220">
          <template #default="{ row }">
            <el-progress
              :percentage="pieTotal ? Math.round((Number(row.amount) / pieTotal) * 100) : 0"
              :color="row.color"
              :stroke-width="10"
            />
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState :size="88" description="范围内暂无数据" />
        </template>
      </el-table>
    </el-card>
    </section>
  </div>
</template>

<style scoped>
.filter-card .split-row {
  flex-wrap: wrap;
}

.report-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px 28px;
  padding: var(--bk-panel-padding);
}

.report-summary > div { border-top: 0; }

.report-summary span {
  color: var(--bk-text-secondary);
  font-size: 13px;
}

.report-summary strong {
  display: block;
  margin-top: 8px;
  font-size: 26px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.page-section > .section-heading {
  padding-inline: 4px;
}

.chart-row {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: var(--bk-gap);
}

.chart-card--wide {
  min-width: 0;
}

.chart-box {
  height: 320px;
  width: 100%;
}

/* 可钻取图表（分类占比/月度盈亏）：鼠标手型提示可点击 */
.chart-box--drill {
  cursor: pointer;
}

/* 首屏图表骨架（前端细化 #1）：绝对覆盖在 chart-box 上，与 el-empty 同位 */
.chart-skel {
  position: absolute;
  inset: 0;
  padding: 32px 24px;
  background: var(--bk-surface);
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bk-surface);
}

.chart-card {
  position: relative;
  min-width: 0;
}

/* 图表与骨架/空态的定位容器：使覆盖层 inset:0 仅盖图表区，不再遮住卡片标题 */
.chart-slot {
  position: relative;
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
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.pie-center__label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 2px;
}

.cat-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pl-tag {
  margin-right: 8px;
}

.compare-card__pct {
  font-style: normal;
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 窄窗口自适应：图表双栏→单栏（卡片标题换行依赖全局响应式） */
@container content (max-width: 1040px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}
@container content (max-width: 620px) {
  .report-summary { grid-template-columns: 1fr; }
  .filter-card .toolbar { width: 100%; }
  .filter-card :deep(.el-date-editor) { width: 100%; min-width: 0; }
}
</style>
