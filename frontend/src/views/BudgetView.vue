<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, MoreFilled } from '@element-plus/icons-vue'
import { budgetApi, ApiError } from '@/api'
import { useDictStore } from '@/stores/dict'
import { useSettingsStore } from '@/stores/settings'
import type { BudgetInfo } from '@/types/model'
import { formatAmount, sanitizeAmountInput } from '@/utils/format'
import { useChart } from '@/composables/useChart'
import { chartPalette } from '@/utils/chartTheme'
import { bus, TRANSACTION_CHANGED, BUDGET_CHANGED, CATEGORY_CHANGED } from '@/utils/bus'
import CategoryDot from '@/components/CategoryDot.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 预算管理页（需求文档 4.4）：
 * 月份切换 + 总预算卡片 + 分类预算卡片 + 超支提醒。
 * 预算与已用金额均走后端 searchBudget（按年月）：amountUsed 由后端计算返回，前端不再聚合（收尾 GAP-05）。
 */
const dict = useDictStore()
const settings = useSettingsStore()
const route = useRoute()
const router = useRouter()
const decimals = computed(() => settings.decimalPlaces)

const now = new Date()
const year = ref(now.getFullYear())
const month = ref(now.getMonth() + 1)

const loading = ref(false)
const budgets = ref<BudgetInfo[]>([])

/** 预算执行历史对比（RP-03 / FR-BGT-06）：近 N 个月总预算 vs 已用，均走 searchBudget（silent） */
const HISTORY_MONTHS = 6
const historyChart = useChart()
const historyEl = historyChart.elRef
const budgetHistory = ref<{ label: string; amount: number; used: number }[]>([])

/** 历史月份 searchBudget 结果缓存（NEW-10）：key=`y-m`，业务变更（记账/改预算）后整体失效，减少重复请求 */
const budgetCache = new Map<string, BudgetInfo[]>()
const cacheKey = (y: number, m: number) => `${y}-${m}`
/** 取某月预算：命中缓存直接返回，否则请求并写入缓存 */
function fetchMonth(y: number, m: number, silent = false): Promise<BudgetInfo[]> {
  const key = cacheKey(y, m)
  const cached = budgetCache.get(key)
  if (cached) return Promise.resolve(cached)
  return budgetApi.searchBudget(y, m, silent ? { silent: true } : undefined).then((rows) => {
    budgetCache.set(key, rows)
    return rows
  })
}

/** 站内信跳转定位的预算 id（/budget?bizId=5），命中的卡片高亮并滚动到可视区 */
const focusId = ref<number | null>(null)
const pageRef = ref<HTMLElement | null>(null)

const dialogVisible = ref(false)
const editing = ref<BudgetInfo | null>(null)
const saving = ref(false)
const form = reactive({
  monthValue: '' as string, // yyyy-MM
  budgetType: 'total' as 'total' | 'category',
  categoryId: undefined as number | undefined,
  amount: ''
})

const monthLabel = computed(() => `${year.value}年${month.value}月`)

/** 当月总预算（categoryId 为空） */
const totalBudget = computed(() => budgets.value.find((b) => b.categoryId == null) ?? null)

/** 当月分类预算（仅支出一级分类） */
const categoryBudgets = computed(() => budgets.value.filter((b) => b.categoryId != null))

/** 本月剩余天数（当月为负表示已过完） */
const daysLeft = computed(() => {
  const today = new Date()
  if (today.getFullYear() !== year.value || today.getMonth() + 1 !== month.value) {
    return null
  }
  const lastDay = new Date(year.value, month.value, 0).getDate()
  return Math.max(0, lastDay - today.getDate())
})

/** 本月总预算剩余日均可花（含今天）；非当月或无总预算时为 null（NEW-10） */
const dailyAffordable = computed<number | null>(() => {
  if (!totalBudget.value || daysLeft.value === null) return null
  const remaining = Math.max(0, Number(totalBudget.value.amount) - spentOf(totalBudget.value))
  const days = daysLeft.value + 1 // 含今天
  return days > 0 ? remaining / days : null
})

/** 月底预测（NEW-10）：按当前日均支出线性外推到月底，仅当月且有总预算时有效 */
const forecast = computed<{ avgDaily: number; projected: number; over: boolean } | null>(() => {
  if (!totalBudget.value || daysLeft.value === null) return null
  const elapsed = new Date().getDate() // 当月已过天数（含今天）
  if (elapsed <= 0) return null
  const spent = spentOf(totalBudget.value)
  const avgDaily = spent / elapsed
  const projected = spent + avgDaily * daysLeft.value
  return { avgDaily, projected, over: projected > Number(totalBudget.value.amount) }
})

/** 预算使用率颜色：<80% success、80~100 warning、>=100 danger */
function progressStatus(pct: number): 'success' | 'warning' | 'exception' {
  if (pct >= 100) return 'exception'
  if (pct >= 80) return 'warning'
  return 'success'
}

function pctOf(spent: number, amount: number): number {
  if (!amount) return 0
  return Math.round((spent / amount) * 100)
}

async function load() {
  loading.value = true
  try {
    budgets.value = await fetchMonth(year.value, month.value)
  } finally {
    loading.value = false
  }
  // 历史对比为次要信息，独立拉取不阻塞当前月份渲染
  loadHistory()
}

/**
 * 拉取截至当前显示月份的近 HISTORY_MONTHS 个月总预算（categoryId==null）与已用金额。
 * 各月 searchBudget 均 silent，未设总预算的月不入图；全部不可用时隐藏对比卡。
 */
async function loadHistory() {
  const months: { y: number; m: number }[] = []
  let y = year.value
  let m = month.value
  for (let i = 0; i < HISTORY_MONTHS; i++) {
    months.unshift({ y, m })
    m--
    if (m === 0) {
      m = 12
      y--
    }
  }
  const results = await Promise.allSettled(
    months.map(({ y, m }) => fetchMonth(y, m, true))
  )
  const crossYear = months[0].y !== months[months.length - 1].y
  const pad = (n: number) => String(n).padStart(2, '0')
  const rows: { label: string; amount: number; used: number }[] = []
  months.forEach(({ y, m }, i) => {
    const r = results[i]
    if (r.status !== 'fulfilled') return
    const total = r.value.find((b) => b.categoryId == null)
    if (!total) return
    rows.push({
      label: crossYear ? `${String(y).slice(2)}/${pad(m)}` : `${m}月`,
      amount: Number(total.amount),
      used: Number(total.amountUsed)
    })
  })
  budgetHistory.value = rows
  // 对比卡受 v-if 控制，首次有数据时需等 DOM 渲染后再初始化图表
  await nextTick()
  renderHistory()
}

/** 预算执行历史对比：每月“预算 vs 已用”分组柱状图 */
function renderHistory() {
  const rows = budgetHistory.value
  const p = chartPalette()
  historyChart.render({
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `¥${formatAmount(v)}` },
    legend: { data: ['预算', '已用'], right: 0, textStyle: { color: p.axisText } },
    grid: { left: 56, right: 16, top: 36, bottom: 28 },
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
        name: '预算',
        type: 'bar',
        barMaxWidth: 22,
        data: rows.map((r) => r.amount),
        itemStyle: { color: p.primary, borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '已用',
        type: 'bar',
        barMaxWidth: 22,
        data: rows.map((r) => ({
          value: r.used,
          itemStyle: {
            color: r.used > r.amount ? p.expense : p.income,
            borderRadius: [4, 4, 0, 0]
          }
        }))
      }
    ]
  })
}

function prevMonth() {
  focusId.value = null
  month.value--
  if (month.value === 0) {
    month.value = 12
    year.value--
  }
  load()
}

function nextMonth() {
  focusId.value = null
  month.value++
  if (month.value === 13) {
    month.value = 1
    year.value++
  }
  load()
}

/** 支出一级分类（分类预算可选项） */
const expenseRootCategories = computed(() => dict.rootCategoriesByType('expense'))

function openCreate() {
  editing.value = null
  form.monthValue = `${year.value}-${String(month.value).padStart(2, '0')}`
  form.budgetType = 'total'
  form.categoryId = undefined
  form.amount = ''
  dialogVisible.value = true
}

function openEdit(budget: BudgetInfo) {
  editing.value = budget
  // BudgetInfo 不含 year/month：预算必属当前显示月份（searchBudget 按 year/month 查询），直接用视图年月
  form.monthValue = `${year.value}-${String(month.value).padStart(2, '0')}`
  form.budgetType = budget.categoryId == null ? 'total' : 'category'
  form.categoryId = budget.categoryId ?? undefined
  form.amount = String(budget.amount ?? '')
  dialogVisible.value = true
}

async function save() {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(form.monthValue)) {
    ElMessage.warning('请选择预算月份')
    return
  }
  if (form.budgetType === 'category' && !form.categoryId) {
    ElMessage.warning('请选择分类')
    return
  }
  const amountNum = Number(form.amount)
  if (!form.amount || Number.isNaN(amountNum) || amountNum <= 0) {
    ElMessage.warning('请输入正确的预算金额（大于 0）')
    return
  }
  const [y, m] = form.monthValue.split('-').map(Number)
  saving.value = true
  try {
    const payload = {
      categoryId: form.budgetType === 'category' ? form.categoryId! : null,
      amount: amountNum,
      month: m,
      year: y
    }
    if (editing.value) {
      await budgetApi.update({ ...payload, id: editing.value.id })
      ElMessage.success('修改成功')
    } else {
      await budgetApi.save(payload)
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    bus.emit(BUDGET_CHANGED)
    // 编辑可能修改了月份，同步页面显示的月份
    year.value = y
    month.value = m
    await load()
  } catch (e) {
    if (e instanceof ApiError && (e.code === 'B003' || e.code === 'B004')) {
      // 同月预算已存在：切换为编辑提示
      ElMessage.info('该月已存在相同预算，可直接在卡片上编辑修改')
    }
  } finally {
    saving.value = false
  }
}

async function remove(budget: BudgetInfo) {
  const label = budget.categoryId == null ? '总预算' : dict.categoryById(budget.categoryId)?.name ?? '分类预算'
  try {
    await ElMessageBox.confirm(`确定删除${monthLabel.value}的「${label}」预算吗？`, '删除预算', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await budgetApi.remove(budget.id)
    ElMessage.success('删除成功')
    bus.emit(BUDGET_CHANGED)
    await load()
  } catch {
    // 拦截器已提示
  }
}

/** 已用金额直接取后端返回的 amountUsed（元） */
function spentOf(budget: BudgetInfo): number {
  return Number(budget.amountUsed)
}

const copying = ref(false)
/** 复制上月预算到当前月（NEW-10）：总预算 + 分类预算逐条创建，同月已存在的自动跳过 */
async function copyLastMonth() {
  let py = year.value
  let pm = month.value - 1
  if (pm === 0) {
    pm = 12
    py--
  }
  copying.value = true
  try {
    const prev = await fetchMonth(py, pm, true)
    if (!prev.length) {
      ElMessage.info(`上月（${py}年${pm}月）没有可复制的预算`)
      return
    }
    let created = 0
    let exists = 0
    for (const b of prev) {
      try {
        await budgetApi.save({
          categoryId: b.categoryId ?? null,
          amount: Number(b.amount),
          month: month.value,
          year: year.value
        })
        created++
      } catch (e) {
        if (e instanceof ApiError && (e.code === 'B003' || e.code === 'B004')) exists++
      }
    }
    bus.emit(BUDGET_CHANGED)
    ElMessage.success(exists ? `已复制 ${created} 条预算，${exists} 条因已存在跳过` : `已复制 ${created} 条预算`)
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    copying.value = false
  }
}

/** 点预算看对应流水（NEW-10）：跳转流水页并按分类（可空）+ 当月区间预筛选 */
function viewTransactions(budget: BudgetInfo | null) {
  const pad = (n: number) => String(n).padStart(2, '0')
  const lastDay = new Date(year.value, month.value, 0).getDate()
  const query: Record<string, string> = {
    start: `${year.value}-${pad(month.value)}-01`,
    end: `${year.value}-${pad(month.value)}-${pad(lastDay)}`
  }
  if (budget?.categoryId != null) query.categoryId = String(budget.categoryId)
  router.push({ path: '/transaction', query })
}

function onChanged() {
  // 记账 / 改预算会影响 amountUsed 与历史，缓存整体失效后重拉（NEW-10）
  budgetCache.clear()
  load()
}

/**
 * 消费地址栏上的 bizId（站内信详情「查看预算管理」）。
 * 预算可能不在当前显示月份，故先取详情拿年月并切过去；
 * 定位后把 bizId 从 URL 上抹除，保证同一条消息再次跳转仍能触发 watch。
 */
async function applyFocusFromRoute() {
  const raw = route.query.bizId
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  if (raw == null || !Number.isFinite(id)) return
  const rest = { ...route.query }
  delete rest.bizId
  router.replace({ query: rest })
  try {
    const target = await budgetApi.detail(id, { silent: true })
    if (target?.year && target?.month) {
      year.value = target.year
      month.value = target.month
    }
  } catch {
    // 详情不可用：按当前月份尝试定位
  }
  focusId.value = id
  await load()
  await nextTick()
  pageRef.value?.querySelector('.is-focused')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  if (!budgets.value.some((b) => b.id === id)) {
    ElMessage.warning('未找到关联的预算，可能已被删除')
    focusId.value = null
  }
}

watch(
  () => route.query.bizId,
  (v) => {
    if (v != null) applyFocusFromRoute()
  }
)

// 主题切换后历史对比图按新 CSS token 重绘（canvas 不随 CSS 变量自动刷新）
watch(
  () => settings.isDark,
  () => {
    if (budgetHistory.value.length) renderHistory()
  }
)

onMounted(() => {
  if (route.query.bizId != null) applyFocusFromRoute()
  else load()
  bus.on(TRANSACTION_CHANGED, onChanged)
  bus.on(BUDGET_CHANGED, onChanged)
  bus.on(CATEGORY_CHANGED, onChanged)
})

onBeforeUnmount(() => {
  bus.off(TRANSACTION_CHANGED, onChanged)
  bus.off(BUDGET_CHANGED, onChanged)
  bus.off(CATEGORY_CHANGED, onChanged)
})
</script>

<template>
  <div class="page page--comfortable quiet-controls" ref="pageRef" v-loading="loading">
    <!-- 页头叙事 + 月份导航工具行 -->
    <header class="page-head page-head--actions">
      <div class="row-copy">
        <h1 class="page-head__title">预算</h1>
        <p class="page-head__sub">按月规划支出，跟踪执行进度</p>
      </div>
      <div class="toolbar page-head__actions">
        <el-button text :loading="copying" data-guide="bd-copy" @click="copyLastMonth">复制上月</el-button>
        <el-button type="primary" data-guide="bd-create" @click="openCreate">+ 设置预算</el-button>
      </div>
    </header>
    <section class="page-section" aria-labelledby="budget-month-heading">
      <div class="toolbar page-section__head">
        <h2 id="budget-month-heading" class="section-heading">月度计划</h2>
        <nav class="month-nav" data-guide="bd-month" aria-label="预算月份">
          <el-button text circle aria-label="上个月" @click="prevMonth"><el-icon><ArrowLeft /></el-icon></el-button>
          <span class="month-nav__label" aria-live="polite">{{ monthLabel }}</span>
          <el-button text circle aria-label="下个月" @click="nextMonth"><el-icon><ArrowRight /></el-icon></el-button>
        </nav>
      </div>

      <!-- 总预算卡片 -->
      <el-card
        v-if="totalBudget"
        shadow="never"
        class="total-card"
        data-guide="bd-total"
        :class="{ 'is-focused': focusId === totalBudget.id }"
      >
        <div class="total-card__row">
          <div class="total-card__info">
            <div class="total-card__label">月度总预算</div>
            <div class="budget-metrics">
              <div><span>已使用</span><strong class="amount-expense">¥{{ formatAmount(spentOf(totalBudget), decimals) }}</strong></div>
              <div><span>预算额度</span><strong>¥{{ formatAmount(totalBudget.amount, decimals) }}</strong></div>
              <div><span>剩余可用</span><strong>¥{{ formatAmount(Math.max(0, Number(totalBudget.amount) - spentOf(totalBudget)), decimals) }}</strong></div>
            </div>
            <div v-if="daysLeft !== null || dailyAffordable !== null" class="total-card__sub">
              <span v-if="daysLeft !== null">本月还剩 {{ daysLeft }} 天</span>
              <span v-if="dailyAffordable !== null">日均可花 ¥{{ formatAmount(dailyAffordable, decimals) }}</span>
            </div>
            <div v-if="forecast" class="total-card__forecast">
              按当前日均 ¥{{ formatAmount(forecast.avgDaily, decimals) }}，预计月底支出
              <span :class="forecast.over ? 'amount-expense' : 'amount-income'">
                ¥{{ formatAmount(forecast.projected, decimals) }}
              </span>
              <template v-if="forecast.over">（将超支）</template>
            </div>
          </div>
          <div class="total-card__actions">
            <el-button text @click="viewTransactions(totalBudget)">流水</el-button>
            <el-button text type="primary" @click="openEdit(totalBudget)">编辑</el-button>
            <el-button text type="danger" @click="remove(totalBudget)">删除</el-button>
          </div>
        </div>
        <el-progress
          class="total-card__progress"
          :percentage="pctOf(spentOf(totalBudget), Number(totalBudget.amount))"
          :status="progressStatus(pctOf(spentOf(totalBudget), Number(totalBudget.amount)))"
          :stroke-width="10"
          :show-text="true"
        />
        <el-alert
          v-if="spentOf(totalBudget) > Number(totalBudget.amount)"
          title="本月总预算已超支，请注意控制支出"
          type="error"
          :closable="false"
          show-icon
          class="total-card__alert"
        />
      </el-card>

      <el-card v-else shadow="never">
        <EmptyState description="本月还未设置总预算" :size="104">
          <el-button type="primary" @click="openCreate">设置月度总预算</el-button>
        </EmptyState>
      </el-card>
    </section>

    <!-- 分类预算卡片 -->
    <section v-if="categoryBudgets.length" class="page-section" aria-labelledby="budget-category-heading">
      <h2 id="budget-category-heading" class="section-heading">分类规划</h2>
      <div class="budget-grid" data-guide="bd-list">
        <el-card
          v-for="budget in categoryBudgets"
          :key="budget.id"
          shadow="hover"
          class="budget-card"
          :class="{ 'is-focused': focusId === budget.id }"
        >
          <div class="budget-card__header">
            <CategoryDot
              :name="dict.categoryById(budget.categoryId)?.name ?? '?'"
              :icon="dict.categoryById(budget.categoryId)?.icon"
              :color="dict.categoryById(budget.categoryId)?.color"
              :size="34"
            />
            <div class="budget-card__meta">
              <div class="budget-card__name">{{ dict.categoryById(budget.categoryId)?.name ?? '未知分类' }}</div>
              <div class="budget-card__sub">
                已用 ¥{{ formatAmount(spentOf(budget), decimals) }} / ¥{{ formatAmount(budget.amount, decimals) }}
              </div>
            </div>
            <el-dropdown
              trigger="click"
              @command="(cmd: string) => cmd === 'edit' ? openEdit(budget) : remove(budget)"
            >
              <el-button text circle aria-label="分类预算的更多操作"><el-icon><MoreFilled /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item command="delete" class="danger-item">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <el-progress
            :percentage="pctOf(spentOf(budget), Number(budget.amount))"
            :status="progressStatus(pctOf(spentOf(budget), Number(budget.amount)))"
            :stroke-width="10"
          />
          <el-tag v-if="spentOf(budget) > Number(budget.amount)" type="danger" size="small" effect="light" class="budget-card__over">
            已超支 ¥{{ formatAmount(spentOf(budget) - Number(budget.amount), decimals) }}
          </el-tag>
          <el-button text type="primary" size="small" class="budget-card__link" @click="viewTransactions(budget)">
            查看该分类流水 →
          </el-button>
        </el-card>
      </div>
    </section>

    <!-- 预算执行历史对比（RP-03） -->
    <el-card v-if="budgetHistory.length" shadow="never" class="history-card">
      <template #header>
        <div class="card-head">
          <h2 class="card-head__title">预算执行历史对比</h2>
          <span class="card-hint">近 {{ budgetHistory.length }} 个月总预算 vs 已用</span>
        </div>
      </template>
      <div ref="historyEl" class="history-card__chart" />
    </el-card>

    <!-- 新增/编辑预算 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑预算' : '设置预算'"
      width="460px"
      class="bk-dialog quiet-controls"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="预算月份" required>
          <el-date-picker
            v-model="form.monthValue"
            type="month"
            value-format="YYYY-MM"
            placeholder="选择月份"
            style="width: 100%"
            :clearable="false"
          />
        </el-form-item>
        <el-form-item label="预算类型" required>
          <el-radio-group v-model="form.budgetType" class="segment" aria-label="预算类型" :disabled="!!editing">
            <el-radio-button value="total">总预算</el-radio-button>
            <el-radio-button value="category">分类预算</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.budgetType === 'category'" label="分类" required>
          <el-select v-model="form.categoryId" placeholder="选择支出分类" style="width: 100%" :disabled="!!editing">
            <el-option v-for="c in expenseRootCategories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="预算金额" required>
          <el-input v-model="form.amount" placeholder="0.00" @input="form.amount = sanitizeAmountInput(form.amount)">
            <template #prepend>¥</template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-section > .section-heading {
  padding-inline: 4px;
}

.month-nav {
  display: flex;
  align-items: center;
  gap: 12px;
}

.month-nav__label {
  font-size: 14px;
  font-weight: 550;
  min-width: 100px;
  text-align: center;
}

.total-card__info {
  min-width: 0;
  flex: 1 1 520px;
}

.total-card__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
}

.total-card__label {
  color: var(--bk-text-secondary);
  font-size: 13px;
}

.budget-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; margin-top: 20px; }
.budget-metrics > div { display: flex; flex-direction: column; min-width: 0; gap: 6px; }
.budget-metrics span { font-size: 12px; color: var(--bk-text-secondary); }
.budget-metrics strong { font-size: 24px; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }

.total-card__sub {
  color: var(--bk-text-secondary);
  font-size: 13px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  margin-top: 20px;
}

.total-card__forecast {
  color: var(--bk-text-secondary);
  font-size: 13px;
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: var(--bk-radius-sm);
  background: var(--bk-surface-2);
}

.budget-card__link {
  align-self: flex-start;
  padding: 0;
}

.total-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-left: auto;
}

.total-card__progress {
  margin-top: 16px;
}

.total-card__alert {
  margin-top: 12px;
}

.budget-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 300px), 1fr));
  gap: var(--bk-gap);
}

.budget-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.budget-card__header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.budget-card__meta {
  flex: 1;
  min-width: 0;
}

.budget-card__name {
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.budget-card__sub {
  color: var(--bk-text-secondary);
  font-size: 12px;
  margin-top: 5px;
  overflow-wrap: anywhere;
}

.budget-card__over {
  align-self: flex-start;
}

.danger-item {
  color: var(--el-color-danger);
}

/* 站内信跳转定位：命中的预算卡片高亮 */
.is-focused {
  --el-card-border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.history-card__chart {
  height: 300px;
  width: 100%;
}
.total-card__actions > .el-button + .el-button { margin-left: 0; }
@container content (max-width: 540px) {
  .budget-metrics { grid-template-columns: minmax(0, 1fr); gap: 14px; }
  .budget-metrics > div { flex-direction: row; align-items: baseline; justify-content: space-between; gap: 12px; }
  .budget-metrics strong { font-size: 22px; }
}
</style>
