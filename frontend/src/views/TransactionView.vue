<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Edit, DocumentCopy, Delete, Wallet, Plus } from '@element-plus/icons-vue'
import { transactionApi, ApiError } from '@/api'
import { useDictStore } from '@/stores/dict'
import { useSettingsStore } from '@/stores/settings'
import { parseTagIds, type SearchQuery, type Transaction } from '@/types/model'
import { formatAmount, formatDateTime } from '@/utils/format'
import { TRANSACTION_TYPE_COLOR_CLASS, transactionTypeLabel } from '@/utils/constants'
import { bus, TRANSACTION_CHANGED, ACCOUNT_CHANGED, CATEGORY_CHANGED, TAG_CHANGED } from '@/utils/bus'
import { inDateRange } from '@/utils/aggregate'
import CategoryDot from '@/components/CategoryDot.vue'
import AccountOption from '@/components/AccountOption.vue'
import TransactionFormDialog from '@/components/TransactionFormDialog.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 交易流水页（需求文档 4.2）。
 * 查询统一走后端分页 POST /transaction/page（type/accountId/categoryId/note 条件已生效）。
 * 日期区间依赖后端区间操作符 ge/le（GAP-04，契约见 docs/frontend-requirements.md 第 12 章）：
 * - 后端就绪：区间也走 page（key=date → f_date，值补 T00:00:00 / T23:59:59）
 * - 后端未就绪：首次探测失败后静默降级 selectAll + 本地过滤 + 本地分页，本会话不再重复探测
 */
const dict = useDictStore()
const settings = useSettingsStore()
const route = useRoute()
const router = useRouter()
const decimals = computed(() => settings.decimalPlaces)

const loading = ref(false)
const list = ref<Transaction[]>([])
const total = ref(0)

const pageNum = ref(1)
const pageSize = ref(20)

/**
 * 站内信跳转定位的流水 id（/transaction?bizId=123）：
 * 非空时忽略其他筛选、按 id 精确查询并高亮该行（消息关联的流水不一定在当前筛选结果里）。
 */
const focusId = ref<number | null>(null)

/**
 * 后端日期区间查询能力探测结果：
 * null=未探测，true=已支持（走 page），false=未支持（本会话降级本地过滤，不再重复探测）。
 * 契约见 docs/frontend-requirements.md 第 12 章（GAP-04）。
 */
let rangePageOk: boolean | null = null

const filters = reactive({
  type: '' as '' | Transaction['type'],
  accountId: undefined as number | undefined,
  categoryId: undefined as number | undefined,
  /** 标签筛选：tags 为 JSON 数组字符串，后端 page 无法可靠 in/like 数组元素，故走本地过滤 */
  tagId: undefined as number | undefined,
  keyword: '',
  dateRange: null as [string, string] | null,
  /** 金额区间（字符串，空为不限）：走本地过滤，因 f_amount 为 TEXT，后端 ge/le 字典序比较不可靠（NEW-06） */
  amountMin: '',
  amountMax: ''
})

/** 是否有生效的筛选条件：用于区分“本来就没数据”与“筛选后无匹配”两种空态 */
const hasActiveFilter = computed(
  () =>
    filters.type !== '' ||
    filters.accountId != null ||
    filters.categoryId != null ||
    filters.tagId != null ||
    filters.keyword.trim() !== '' ||
    filters.dateRange != null ||
    filters.amountMin.trim() !== '' ||
    filters.amountMax.trim() !== ''
)

const formDialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit' | 'copy',
  initial: null as Transaction | null
})

/** 分类级联选项（完整三层树：一级→二级→三级，复用 dict.categoryTreeByType，与录入端一致，NEW-01） */
const categoryOptions = computed(() =>
  dict.categoryTreeByType('expense').concat(dict.categoryTreeByType('income'))
)

/** 选中分类（含其全部后代，任意深度）的 id 集合，用于 in 查询（NEW-01） */
function categoryIdsWithChildren(): number[] {
  if (!filters.categoryId) return []
  return dict.descendantIds(filters.categoryId)
}

function buildQueryList() {
  const queryList: SearchQuery[] = []
  // 定位模式：只查这一条，不受筛选栏影响
  if (focusId.value != null) {
    queryList.push({ key: 'id', value: focusId.value, query: 'eq' })
    return queryList
  }
  if (filters.type) queryList.push({ key: 'type', value: filters.type, query: 'eq' })
  if (filters.accountId != null) queryList.push({ key: 'accountId', value: filters.accountId, query: 'eq' })
  const catIds = categoryIdsWithChildren()
  if (catIds.length) queryList.push({ key: 'categoryId', value: catIds, query: 'in' })
  // 关键字不在此下推：多字段搜索（备注/分类名/账户名/标签名）为 OR 语义，后端 page 无法表达，统一走本地过滤
  if (filters.dateRange) {
    const [start, end] = filters.dateRange
    // 日期列特例：DTO 属性 transactionDate 会转出 f_transaction_date（不存在），实际列是 f_date，故 key 传 date。
    // f_date 存为 ISO 文本（yyyy-MM-ddTHH:mm:ss），字典序即时间序；ge/le 区间操作符待后端支持（第 12 章）。
    queryList.push({ key: 'date', value: `${start}T00:00:00`, query: 'ge' })
    queryList.push({ key: 'date', value: `${end}T23:59:59`, query: 'le' })
  }
  return queryList
}

/** 是否启用了金额区间筛选（金额走本地过滤，因 f_amount 为 TEXT，后端 ge/le 字典序比较不可靠） */
function hasAmountFilter(): boolean {
  return filters.amountMin.trim() !== '' || filters.amountMax.trim() !== ''
}

/**
 * 是否必须走本地过滤：金额区间（TEXT 无法后端比较）、标签（JSON 数组无法后端 in）、
 * 关键字多字段搜索（备注/分类名/账户名/标签名的 OR 语义后端 page 无法表达）任一命中即本地过滤。
 */
function needLocalFilter(): boolean {
  return hasAmountFilter() || filters.tagId != null || filters.keyword.trim() !== ''
}

/** 关键字多字段匹配：备注 / 分类名 / 账户名（含转入）/ 标签名，任一命中即匹配（kw 需已 trim+小写） */
function matchesKeyword(t: Transaction, kw: string): boolean {
  if ((t.note ?? '').toLowerCase().includes(kw)) return true
  const cat = dict.categoryById(t.categoryId)
  if (cat && cat.name.toLowerCase().includes(kw)) return true
  const acc = dict.accountById(t.accountId)
  if (acc && acc.name.toLowerCase().includes(kw)) return true
  const toAcc = dict.accountById(t.toAccountId)
  if (toAcc && toAcc.name.toLowerCase().includes(kw)) return true
  for (const id of parseTagIds(t.tags)) {
    const tag = dict.tagById(id)
    if (tag && tag.name.toLowerCase().includes(kw)) return true
  }
  return false
}

/** 本地过滤 + 本地分页：用于后端区间未就绪，或金额区间筛选（NEW-06） */
async function loadByLocalFilter() {
  const all = await transactionApi.selectAll()
  const range = filters.dateRange
  const minAmt = filters.amountMin.trim() === '' ? null : Number(filters.amountMin)
  const maxAmt = filters.amountMax.trim() === '' ? null : Number(filters.amountMax)
  const kw = filters.keyword.trim().toLowerCase()
  const tagId = filters.tagId
  const filtered = all.filter((t) => {
    if (focusId.value != null && t.id !== focusId.value) return false
    if (range && !inDateRange(t.transactionDate, range[0], range[1])) return false
    if (filters.type && t.type !== filters.type) return false
    if (filters.accountId != null && t.accountId !== filters.accountId) return false
    const catIds = categoryIdsWithChildren()
    if (catIds.length && (t.categoryId == null || !catIds.includes(t.categoryId))) return false
    if (tagId != null && !parseTagIds(t.tags).includes(tagId)) return false
    if (kw && !matchesKeyword(t, kw)) return false
    const amt = Number(t.amount)
    if (minAmt != null && Number.isFinite(minAmt) && amt < minAmt) return false
    if (maxAmt != null && Number.isFinite(maxAmt) && amt > maxAmt) return false
    return true
  })
  total.value = filtered.length
  const startIdx = (pageNum.value - 1) * pageSize.value
  list.value = filtered.slice(startIdx, startIdx + pageSize.value)
}

async function load() {
  loading.value = true
  try {
    // 金额区间/标签/关键字多字段均无法走后端分页，强制本地过滤（NEW-06 + 前端细化）
    if (needLocalFilter()) {
      await loadByLocalFilter()
      return
    }
    // 日期区间且已探测出后端不支持：直接降级，不再重复发失败请求
    if (filters.dateRange && rangePageOk === false) {
      await loadByLocalFilter()
      return
    }
    try {
      // 统一走后端分页；带日期区间时 silent，便于失败后静默降级（不弹错）
      const result = await transactionApi.page(pageNum.value, pageSize.value, buildQueryList(), {
        silent: !!filters.dateRange
      })
      if (filters.dateRange) rangePageOk = true
      list.value = result.list
      total.value = result.total
    } catch (e) {
      // 非区间查询失败照常抛出（http 层已提示）；区间查询失败则降级本地过滤
      if (!filters.dateRange) throw e
      if (rangePageOk !== true) rangePageOk = false
      await loadByLocalFilter()
    }
  } finally {
    loading.value = false
    // 批量模式下每次刷新后清空选择，避免选中项与当前页不一致（NEW-06）
    if (batchMode.value) selectedIds.value = []
  }
}

function search() {
  pageNum.value = 1
  load()
}

function resetFilters() {
  focusId.value = null
  filters.type = ''
  filters.accountId = undefined
  filters.categoryId = undefined
  filters.tagId = undefined
  filters.keyword = ''
  filters.dateRange = null
  filters.amountMin = ''
  filters.amountMax = ''
  search()
}

/** 快捷日期范围 */
function applyShortcut(kind: 'week' | 'month' | 'year') {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  let start: Date
  if (kind === 'week') {
    start = new Date(now)
    start.setDate(now.getDate() - ((now.getDay() + 6) % 7))
  } else if (kind === 'month') {
    start = new Date(now.getFullYear(), now.getMonth(), 1)
  } else {
    start = new Date(now.getFullYear(), 0, 1)
  }
  filters.dateRange = [fmt(start), fmt(now)]
  search()
}

function openCreate() {
  formDialog.mode = 'create'
  formDialog.initial = null
  formDialog.visible = true
}

function openEdit(row: Transaction) {
  formDialog.mode = 'edit'
  formDialog.initial = row
  formDialog.visible = true
}

function openCopy(row: Transaction) {
  formDialog.mode = 'copy'
  formDialog.initial = row
  formDialog.visible = true
}

async function remove(row: Transaction) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这条记录吗？', '删除记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await transactionApi.remove(row.id)
    ElMessage.success('删除成功')
    bus.emit(TRANSACTION_CHANGED)
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'S0813') {
      ElMessage.info('删除失败，请稍后重试')
    }
  }
}

// —— 批量管理（NEW-06）：当前页多选，支持批量删除 / 批量改分类 ——
const batchMode = ref(false)
const selectedIds = ref<number[]>([])
const batchLoading = ref(false)
const batchCategoryDialog = reactive({ visible: false, categoryId: undefined as number | undefined })

const allSelected = computed(
  () => list.value.length > 0 && list.value.every((t) => selectedIds.value.includes(t.id))
)
const someSelected = computed(() => selectedIds.value.length > 0 && !allSelected.value)

function toggleBatchMode() {
  batchMode.value = !batchMode.value
  selectedIds.value = []
}

function toggleSelect(id: number) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) selectedIds.value.splice(idx, 1)
  else selectedIds.value.push(id)
}

function toggleSelectAll() {
  selectedIds.value = allSelected.value ? [] : list.value.map((t) => t.id)
}

async function batchRemove() {
  if (!selectedIds.value.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedIds.value.length} 条记录吗？删除后不可恢复。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  batchLoading.value = true
  try {
    const n = await transactionApi.batchDelete(selectedIds.value)
    ElMessage.success(`已删除 ${n} 条记录`)
    selectedIds.value = []
    bus.emit(TRANSACTION_CHANGED)
    await load()
  } catch {
    // 失败提示由拦截器统一处理
  } finally {
    batchLoading.value = false
  }
}

function openBatchCategory() {
  if (!selectedIds.value.length) return
  batchCategoryDialog.categoryId = undefined
  batchCategoryDialog.visible = true
}

async function confirmBatchCategory() {
  if (!batchCategoryDialog.categoryId) {
    ElMessage.warning('请选择目标分类')
    return
  }
  // 转账无分类：排除当前页选中的转账流水（NEW-06）
  const ids = selectedIds.value.filter((id) => {
    const row = list.value.find((t) => t.id === id)
    return !row || row.type !== 'transfer'
  })
  if (!ids.length) {
    ElMessage.info('所选记录均为转账，无分类可修改')
    return
  }
  batchLoading.value = true
  try {
    const n = await transactionApi.batchUpdateCategory(ids, batchCategoryDialog.categoryId)
    ElMessage.success(`已修改 ${n} 条记录的分类`)
    batchCategoryDialog.visible = false
    selectedIds.value = []
    bus.emit(TRANSACTION_CHANGED)
    await load()
  } catch {
    // 失败提示由拦截器统一处理
  } finally {
    batchLoading.value = false
  }
}

/** 取账户对象；返回 null 表示账户已被删除 */
function accountOf(id?: number | null) {
  return dict.accountById(id) ?? null
}

function typeColorClass(type: string): string {
  return TRANSACTION_TYPE_COLOR_CLASS[type as Transaction['type']] ?? ''
}

function rowTags(row: Transaction): { id: number; name: string; color: string }[] {
  return parseTagIds(row.tags)
    .map((id) => dict.tagById(id))
    .filter((t): t is NonNullable<typeof t> => !!t)
    .map((t) => ({ id: t.id, name: t.name, color: t.color || '#909399' }))
}

function onChanged() {
  load()
}

/** 退出定位模式，恢复完整列表 */
function clearFocus() {
  focusId.value = null
  search()
}

/**
 * 消费地址栏上的 bizId（站内信详情「查看交易流水」）：
 * 定位后立刻把 bizId 从 URL 上抹除，保证同一条消息再次跳转仍能触发 watch。
 */
async function applyFocusFromRoute() {
  const raw = route.query.bizId
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  if (raw == null || !Number.isFinite(id)) return
  const rest = { ...route.query }
  delete rest.bizId
  router.replace({ query: rest })
  focusId.value = id
  pageNum.value = 1
  await load()
  // 流水已删除：提示并回到完整列表，避免停留在空表格
  if (!list.value.length) {
    ElMessage.warning('未找到关联的交易流水，可能已被删除')
    focusId.value = null
    await load()
  }
}

watch(
  () => route.query.bizId,
  (v) => {
    if (v != null) applyFocusFromRoute()
  }
)

/**
 * 消费地址栏上的筛选参数（预算页「查看流水」跳转：?categoryId=&start=&end=，NEW-10）：
 * 命中即写入筛选栏并抹除查询参数，保证再次跳转仍能触发；返回是否命中。
 */
function applyFiltersFromRoute(): boolean {
  const q = route.query
  const start = typeof q.start === 'string' ? q.start : ''
  const end = typeof q.end === 'string' ? q.end : ''
  const catRaw = q.categoryId
  const catId = Number(Array.isArray(catRaw) ? catRaw[0] : catRaw)
  const hasCat = catRaw != null && Number.isFinite(catId) && catId > 0
  if (!start && !end && !hasCat) return false
  if (start && end) filters.dateRange = [start, end]
  if (hasCat) filters.categoryId = catId
  const rest = { ...q }
  delete rest.start
  delete rest.end
  delete rest.categoryId
  router.replace({ query: rest })
  pageNum.value = 1
  return true
}

watch(
  () => route.query,
  () => {
    if (route.query.bizId != null) return
    if (route.query.start != null || route.query.categoryId != null) {
      if (applyFiltersFromRoute()) load()
    }
  }
)

onMounted(() => {
  if (route.query.bizId != null) applyFocusFromRoute()
  else {
    applyFiltersFromRoute()
    load()
  }
  bus.on(TRANSACTION_CHANGED, onChanged)
  bus.on(ACCOUNT_CHANGED, onChanged)
  bus.on(CATEGORY_CHANGED, onChanged)
  bus.on(TAG_CHANGED, onChanged)
})

onBeforeUnmount(() => {
  bus.off(TRANSACTION_CHANGED, onChanged)
  bus.off(ACCOUNT_CHANGED, onChanged)
  bus.off(CATEGORY_CHANGED, onChanged)
  bus.off(TAG_CHANGED, onChanged)
})
</script>

<template>
  <div class="page page--comfortable quiet-controls">
    <!-- 页头叙事 -->
    <header class="page-head page-head--actions">
      <div class="row-copy">
        <h1 class="page-head__title">流水</h1>
        <p class="page-head__sub">查看、筛选与管理每一笔收支</p>
      </div>
      <div class="toolbar page-head__actions">
        <el-button :type="batchMode ? 'warning' : 'default'" text data-guide="tx-batch" @click="toggleBatchMode">
          {{ batchMode ? '退出批量' : '批量管理' }}
        </el-button>
        <el-button type="primary" :icon="Plus" data-guide="tx-create" @click="openCreate">新增记录</el-button>
      </div>
    </header>

    <!-- 筛选栏（轻量工具条；保留 filter-card 类供打印样式钩子） -->
    <div class="surface filter-card quiet-controls bk-enter" data-guide="tx-filters">
      <!-- 分组一：筛选维度（类型 / 账户 / 分类 / 标签 / 日期 + 快捷区间） -->
      <div class="split-row tx-filter-row">
        <h2 id="tx-category-heading" class="row-copy__title">分类与账户</h2>
        <div class="toolbar tx-dimensions" role="group" aria-labelledby="tx-category-heading">
          <el-select v-model="filters.type" aria-label="流水类型" placeholder="全部类型" clearable style="width: 110px" @change="search">
            <el-option label="支出" value="expense" />
            <el-option label="收入" value="income" />
            <el-option label="转账" value="transfer" />
          </el-select>

          <el-select v-model="filters.accountId" aria-label="账户" placeholder="全部账户" clearable style="width: 160px" @change="search">
            <el-option v-for="a in dict.accounts" :key="a.id" :label="a.name" :value="a.id">
              <AccountOption :account="a" />
            </el-option>
          </el-select>

          <el-cascader
            v-model="filters.categoryId"
            aria-label="流水分类"
            :options="categoryOptions"
            :props="{ value: 'id', label: 'name', emitPath: false, checkStrictly: true }"
            placeholder="全部分类"
            clearable
            style="width: 160px"
            @change="search"
          />

          <el-select
            v-model="filters.tagId"
            aria-label="流水标签"
            placeholder="全部标签"
            clearable
            style="width: 130px"
            @change="search"
          >
            <el-option v-for="t in dict.tags" :key="t.id" :label="t.name" :value="t.id">
              <span class="tag-opt">
                <span class="tag-opt__dot" :style="{ background: t.color || '#909399' }" />
                {{ t.name }}
              </span>
            </el-option>
          </el-select>
        </div>
      </div>
      <div class="split-row tx-filter-row">
        <h2 id="tx-date-heading" class="row-copy__title">时间范围</h2>
        <div class="toolbar" role="group" aria-labelledby="tx-date-heading">
          <div class="tx-date" data-guide="tx-date">
            <el-date-picker
              aria-label="流水日期范围"
              v-model="filters.dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="~"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              style="width: 100%"
              @change="search"
            />
          </div>
          <div class="toolbar__group" role="group" aria-label="快捷时间范围">
            <el-button size="small" text @click="applyShortcut('week')">本周</el-button>
            <el-button size="small" text @click="applyShortcut('month')">本月</el-button>
            <el-button size="small" text @click="applyShortcut('year')">今年</el-button>
          </div>
        </div>
      </div>

      <!-- 分组二：检索条件 + 主操作（视觉分层，操作右对齐） -->
      <div class="split-row tx-filter-row">
        <h2 id="tx-search-heading" class="row-copy__title">关键词与金额</h2>
        <div class="toolbar" role="group" aria-labelledby="tx-search-heading">
          <el-input
            v-model="filters.keyword"
            aria-label="搜索备注、分类、账户或标签"
            placeholder="搜索备注/分类/账户/标签"
            clearable
            style="width: 240px"
            @keyup.enter="search"
            @clear="search"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <div class="toolbar__group tx-amount" role="group" aria-label="金额区间">
            <el-input
              v-model="filters.amountMin"
              aria-label="最小金额"
              placeholder="最小金额"
              clearable
              style="width: 140px"
              @keyup.enter="search"
              @clear="search"
            >
              <template #prepend>¥</template>
            </el-input>
            <span class="amount-sep">~</span>
            <el-input
              v-model="filters.amountMax"
              aria-label="最大金额"
              placeholder="最大金额"
              clearable
              style="width: 140px"
              @keyup.enter="search"
              @clear="search"
            >
              <template #prepend>¥</template>
            </el-input>
          </div>
          <div class="toolbar__group">
            <el-button @click="search">查询</el-button>
            <el-button text @click="resetFilters">重置</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 站内信跳转定位：只看关联流水，可一键回到完整列表 -->
    <div v-if="focusId != null" class="focus-tip">
      <el-icon><Search /></el-icon>
      <span>已定位到站内信关联的流水（ID {{ focusId }}），其他筛选条件暂不生效</span>
      <el-button link type="primary" size="small" @click="clearFocus">查看全部</el-button>
    </div>

    <!-- 流水列表（消费级流水行，替代列状表格；保留 data-guide="tx-table"） -->
    <div class="panel tx-panel">
      <div class="card-head tx-panel__head">
        <h2 class="card-head__title">交易记录</h2>
        <span class="card-hint">{{ loading ? '正在加载…' : `共 ${total} 笔记录` }}</span>
      </div>
      <!-- 批量管理操作条（NEW-06） -->
      <div v-if="batchMode" class="batch-bar">
        <el-checkbox
          :model-value="allSelected"
          :indeterminate="someSelected"
          @change="toggleSelectAll"
        >全选本页</el-checkbox>
        <span class="batch-bar__count">已选 {{ selectedIds.length }} 项</span>
        <div class="batch-bar__spacer" />
        <el-button size="small" :disabled="!selectedIds.length" @click="openBatchCategory">批量改分类</el-button>
        <el-button size="small" type="danger" :disabled="!selectedIds.length" :loading="batchLoading" @click="batchRemove">批量删除</el-button>
      </div>
      <div v-loading="loading && list.length > 0" class="tx-list" :class="{ 'is-batch': batchMode }" data-guide="tx-table">
        <!-- 首屏骨架（前端细化 #1）：首次加载且无数据时用骨架行占位，避免白屏 + spinner 的廉价感 -->
        <el-skeleton v-if="loading && !list.length" class="tx-skeleton" animated>
          <template #template>
            <div v-for="i in 6" :key="i" class="tx-skel-row">
              <el-skeleton-item variant="circle" class="tx-skel-icon" />
              <div class="tx-skel-main">
                <el-skeleton-item variant="text" style="width: 38%" />
                <el-skeleton-item variant="text" style="width: 62%; margin-top: 8px" />
              </div>
              <el-skeleton-item variant="text" class="tx-skel-amount" />
            </div>
          </template>
        </el-skeleton>
        <div
          v-for="row in list"
          :key="row.id"
          class="tx-row"
          :class="{ 'is-focused': focusId != null && row.id === focusId }"
        >
          <el-checkbox
            v-if="batchMode"
            class="tx-row__check"
            :aria-label="`选择流水 ${row.note || row.id}`"
            :model-value="selectedIds.includes(row.id)"
            @change="toggleSelect(row.id)"
          />
          <div class="tx-row__icon">
            <CategoryDot
              v-if="dict.categoryById(row.categoryId)"
              :name="dict.categoryById(row.categoryId)!.name"
              :icon="dict.categoryById(row.categoryId)!.icon"
              :color="dict.categoryById(row.categoryId)!.color"
              :size="40"
            />
            <span v-else class="tx-row__icon-fallback"><el-icon><Wallet /></el-icon></span>
          </div>

          <div class="tx-row__main">
            <div class="tx-row__title">
              <span class="tx-row__note">{{ row.note || dict.categoryById(row.categoryId)?.name || transactionTypeLabel(row.type) }}</span>
              <span
                v-for="tag in rowTags(row)"
                :key="tag.id"
                class="tag-chip"
                :style="{ '--tag-color': tag.color || 'var(--bk-text-secondary)' }"
              >{{ tag.name }}</span>
            </div>
            <div class="tx-row__sub">
              <span>{{ accountOf(row.accountId)?.name ?? '已删除账户' }}</span>
              <template v-if="row.type === 'transfer'">
                <span class="tx-row__arrow">→</span>
                <span>{{ accountOf(row.toAccountId)?.name ?? '已删除账户' }}</span>
              </template>
              <span class="tx-row__sep">·</span>
              <span>{{ formatDateTime(row.transactionDate) }}</span>
            </div>
          </div>

          <div class="tx-row__amount">
            <span class="amount-strong" :class="typeColorClass(row.type)">
              {{ row.type === 'income' ? '+' : '-' }}{{ formatAmount(row.amount, decimals) }}
            </span>
            <span v-if="row.type === 'transfer' && Number(row.fee) > 0" class="fee-sub">
              手续费 {{ formatAmount(row.fee, decimals) }}
            </span>
          </div>

          <div class="tx-row__actions">
            <el-button text circle size="small" type="primary" title="编辑" aria-label="编辑流水" @click="openEdit(row)"><el-icon><Edit /></el-icon></el-button>
            <el-button text circle size="small" type="primary" title="复制" aria-label="复制流水" @click="openCopy(row)"><el-icon><DocumentCopy /></el-icon></el-button>
            <el-button text circle size="small" type="danger" title="删除" aria-label="删除流水" @click="remove(row)"><el-icon><Delete /></el-icon></el-button>
          </div>
        </div>

        <EmptyState
          v-if="!loading && !list.length"
          :description="
            hasActiveFilter
              ? '没有符合当前筛选条件的流水，试试调整或清除筛选条件'
              : '暂无交易记录，点击右上角「新增记录」或顶栏「记一笔」开始记账'
          "
        >
          <el-button v-if="hasActiveFilter" type="primary" plain @click="resetFilters">清除筛选条件</el-button>
        </EmptyState>
      </div>

      <div v-if="total > 0" class="pagination-row">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="search"
        />
      </div>
    </div>

    <TransactionFormDialog v-model="formDialog.visible" :mode="formDialog.mode" :initial="formDialog.initial" @saved="load" />

    <!-- 批量修改分类对话框（NEW-06） -->
    <el-dialog v-model="batchCategoryDialog.visible" title="批量修改分类" width="440px" class="bk-dialog quiet-controls" append-to-body>
      <p class="dialog-intro">为已选择的 {{ selectedIds.length }} 笔记录设置同一分类。</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="目标分类">
          <el-cascader
            v-model="batchCategoryDialog.categoryId"
            :options="categoryOptions"
            :props="{ value: 'id', label: 'name', emitPath: false, checkStrictly: true }"
            placeholder="选择分类"
            clearable
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="batchCategoryDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="batchLoading" @click="confirmBatchCategory">确定</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 筛选分组：组间用浅色分隔线做视觉分层 */
.tx-filter-row {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  align-items: start;
  gap: 18px;
}

.tx-filter-row > h2 { padding-top: 6px; }
.tx-filter-row .toolbar > :deep(.el-input) { max-width: 100%; }
.tx-dimensions { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
}

.tx-dimensions > :deep(.el-select),
.tx-dimensions > :deep(.el-cascader) {
  width: 100% !important;
  min-width: 0;
  max-width: none;
}

.tx-date {
  flex: 0 1 300px;
  min-width: 0;
  max-width: 100%;
}

.tx-amount {
  flex-wrap: nowrap;
  max-width: 100%;
}

.tx-amount :deep(.el-input) {
  min-width: 0;
}

@container page (max-width: 820px) {
  .tx-filter-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
}

.fee-sub {
  font-size: 12px;
  color: var(--bk-text-secondary);
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  gap: 5px;
  background: color-mix(in srgb, var(--tag-color) 10%, var(--bk-surface));
  color: var(--bk-text-regular);
  border: 0;
  border-radius: var(--bk-radius-pill);
  font-size: 12px;
  line-height: 18px;
  flex-shrink: 0;
}

.tag-chip::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: var(--tag-color); }

/* 标签下拉选项：色点 + 名称 */
.tag-opt {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.tag-opt__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* —— 消费级流水行 —— */
.tx-panel__head { padding-bottom: var(--bk-gap); }
.tx-list.is-batch .tx-row { grid-template-columns: 22px 40px minmax(0, 1fr) auto auto; }

.tx-list {
  display: flex;
  flex-direction: column;
  min-height: 120px;
}

/* 首屏骨架行（与 .tx-row 布局对齐） */
.tx-skeleton {
  width: 100%;
}

.tx-skel-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: var(--bk-row-padding) 0;
}

.tx-skel-icon {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
}

.tx-skel-main {
  flex: 1;
  min-width: 0;
}

.tx-skel-amount {
  width: 88px;
  flex-shrink: 0;
}

.tx-row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 14px;
  padding: var(--bk-row-padding) 0;
  border-bottom: 1px solid var(--bk-border-light);
  border-radius: var(--bk-radius-md);
  transition: background-color 0.16s ease;
}

.tx-row:last-child { border-bottom: 0; }

.tx-row:hover {
  background: var(--bk-surface-2);
}

.tx-row__icon {
  flex-shrink: 0;
}

.tx-row__icon-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--bk-surface-2);
  color: var(--bk-text-secondary);
  font-size: 18px;
}

.tx-row__main {
  flex: 1;
  min-width: 0;
}

.tx-row__title {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 0;
}

.tx-row__note {
  font-size: 14px;
  font-weight: 550;
  color: var(--bk-text);
  overflow-wrap: anywhere;
}

.tx-row__sub {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  margin-top: 3px;
  font-size: 12px;
  color: var(--bk-text-secondary);
}

.tx-row__sep {
  opacity: 0.6;
}

.tx-row__arrow {
  color: var(--bk-transfer);
  margin: 0 2px;
}

.tx-row__amount {
  flex-shrink: 0;
  min-width: 100px;
  max-width: 240px;
  overflow-wrap: anywhere;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  text-align: right;
}

.tx-row__amount .amount-strong {
  font-size: 17px;
  font-weight: 600;
}

.tx-row__actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--bk-action-gap);
  opacity: 0;
  transition: opacity 0.16s ease;
}

.tx-row__actions > .el-button + .el-button { margin-left: 0; }

@media (hover: none) {
  .tx-row__actions { opacity: 1; }
}

.tx-row:hover .tx-row__actions,
.tx-row:focus-within .tx-row__actions,
.tx-row.is-focused .tx-row__actions {
  opacity: 1;
}

/* 站内信跳转定位命中的流水行高亮 */
.tx-row.is-focused {
  background: var(--el-color-primary-light-9);
  box-shadow: inset 0 0 0 1px var(--el-color-primary-light-7);
}

/* 批量管理操作条（NEW-06） */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  flex-wrap: wrap;
  margin-bottom: 8px;
  border-radius: var(--bk-radius-md);
  background: var(--el-color-primary-light-9);
}

.batch-bar__count {
  font-size: 13px;
  color: var(--bk-text-secondary);
}

.batch-bar__spacer {
  flex: 1;
}

.tx-row__check {
  flex-shrink: 0;
  margin-right: 4px;
}

.amount-sep {
  color: var(--bk-text-secondary);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--bk-border-light);
}
.pagination-row :deep(.el-pagination) { flex-wrap: wrap; gap: 8px; }
.batch-bar > .el-button + .el-button { margin-left: 0; }

/* 站内信跳转定位提示条 */
.focus-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-radius: var(--bk-radius-md);
  font-size: 13px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
@container page (max-width: 620px) {
  .tx-dimensions { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .tx-row { grid-template-columns: 40px minmax(0, 1fr) auto; gap: 10px; }
  .tx-list.is-batch .tx-row { grid-template-columns: 22px 40px minmax(0, 1fr) auto; }
  .tx-row__actions { grid-column: 2 / -1; justify-content: flex-end; opacity: 1; }
  .tx-list.is-batch .tx-row__actions { grid-column: 3 / -1; }
  .tx-row__amount { min-width: 0; max-width: 160px; }
}
</style>
