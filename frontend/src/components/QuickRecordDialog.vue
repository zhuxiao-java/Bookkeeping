<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useRouter } from 'vue-router'
import { transactionApi } from '@/api'
import { useDictStore } from '@/stores/dict'
import type { Category, TransactionType } from '@/types/model'
import { isValidAmountInput, nowIso, sanitizeAmountInput } from '@/utils/format'
import { bus, TRANSACTION_CHANGED } from '@/utils/bus'
import AccountOption from '@/components/AccountOption.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 快速记账弹窗（需求文档 4.2.4）：
 * 类型 + 金额 + 账户 + 分类（宫格）+ 标签 + 备注，日期默认当前时间。
 * 支持"保存并记下一笔"连续录入；支持常用模板（TR-03 / FR-TRX-09，localStorage 持久化）。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const dict = useDictStore()
const router = useRouter()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const typeTabs: { value: TransactionType; label: string }[] = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' }
]
/** 保存按钮随类型变色 */
const saveBtnType = computed(() =>
  type.value === 'expense' ? 'danger' : type.value === 'income' ? 'success' : 'primary'
)

const type = ref<TransactionType>('expense')
const amount = ref('')
const accountId = ref<number | undefined>()
const toAccountId = ref<number | undefined>()
const fee = ref('')
const categoryId = ref<number | undefined>()
/** 分类下钻路径：pathIds[0]=选中的一级，pathIds[1]=选中的二级……末端即 categoryId */
const pathIds = ref<number[]>([])
const note = ref('')
const tagIds = ref<number[]>([])
const saving = ref(false)
const formRef = ref<FormInstance>()
const amountInputRef = ref<HTMLInputElement>()

/** 字段级内联错误：逐字段展示红字，取代单条 Toast */
const fieldErrors = reactive({
  amount: '',
  accountId: '',
  toAccountId: '',
  fee: '',
  categoryId: ''
})

function clearErrors() {
  fieldErrors.amount = ''
  fieldErrors.accountId = ''
  fieldErrors.toAccountId = ''
  fieldErrors.fee = ''
  fieldErrors.categoryId = ''
}

/** 金额在表单外的大输入框，有错时优先聚焦它；否则滚动到首个错误项 */
function focusFirstError() {
  nextTick(() => {
    if (fieldErrors.amount) {
      amountInputRef.value?.focus()
      return
    }
    const el = formRef.value?.$el?.querySelector('.el-form-item.is-error') as HTMLElement | null
    el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
}

/** 类型 tabs 键盘操作：Enter / Space 选中 */
function onTabKey(e: KeyboardEvent, t: TransactionType) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    type.value = t
  }
}

// —— 记忆与草稿（NEW-09）：记住上次账户/分类、常用分类排序、未提交草稿、模板失效检查 ——
const MEM_KEY = 'bookkeeping-quick-memory'
const DRAFT_KEY = 'bookkeeping-quick-draft'
const CAT_USAGE_KEY = 'bookkeeping-quick-cat-usage'

/** 程序化回填（记忆 / 草稿 / 模板）期间置真，抑制 watch(type) 清空分类与转入账户 */
let restoring = false

interface QuickMemory {
  type?: TransactionType
  accountId?: number
  toAccountId?: number
  categoryId?: number
}
interface QuickDraft extends QuickMemory {
  amount: string
  fee: string
  note: string
  tagIds: number[]
}

function readJson<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}
function writeJson(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // 存储配额不足时忽略，不影响记账
  }
}

/** 分类使用频次：常用分类在宫格中靠前（NEW-09） */
const catUsage = ref<Record<string, number>>(readJson<Record<string, number>>(CAT_USAGE_KEY) ?? {})
function bumpUsage(id?: number) {
  if (id == null) return
  catUsage.value = { ...catUsage.value, [id]: (catUsage.value[id] ?? 0) + 1 }
  writeJson(CAT_USAGE_KEY, catUsage.value)
}

function validType(t?: TransactionType): t is TransactionType {
  return t === 'expense' || t === 'income' || t === 'transfer'
}
/** 账户仍有效（未删除） */
function validAccount(id?: number): boolean {
  return id != null && !!dict.accountById(id)
}
/** 分类仍有效且属于当前收支类型（转账无分类） */
function validCategory(id: number | undefined, t: TransactionType): boolean {
  if (id == null || t === 'transfer') return false
  const c = dict.categoryById(id)
  return !!c && c.type === t
}

function saveMemory() {
  const mem: QuickMemory = {
    type: type.value,
    accountId: accountId.value,
    toAccountId: toAccountId.value,
    categoryId: categoryId.value
  }
  writeJson(MEM_KEY, mem)
}

/** 草稿是否有值得恢复的内容（金额 / 备注 / 标签任一非空） */
function draftWorthRestoring(d?: QuickDraft | null): boolean {
  if (!d) return false
  return (d.amount ?? '').trim() !== '' || (d.note ?? '').trim() !== '' || (d.tagIds?.length ?? 0) > 0
}
function hasUnsavedContent(): boolean {
  return amount.value.trim() !== '' || note.value.trim() !== '' || tagIds.value.length > 0
}
function persistDraft() {
  if (hasUnsavedContent()) {
    const draft: QuickDraft = {
      type: type.value,
      amount: amount.value,
      accountId: accountId.value,
      toAccountId: toAccountId.value,
      fee: fee.value,
      categoryId: categoryId.value,
      note: note.value,
      tagIds: [...tagIds.value]
    }
    writeJson(DRAFT_KEY, draft)
  } else {
    clearDraft()
  }
}
function clearDraft() {
  try {
    localStorage.removeItem(DRAFT_KEY)
  } catch {
    // ignore
  }
}

/** 当前类型可选的一级分类（支出→expense，收入→income，转账不展示），常用分类优先（NEW-09） */
const categories = computed(() =>
  type.value === 'transfer' ? [] : sortByUsage(dict.rootCategoriesByType(type.value))
)

/** 指定父分类的未归档子分类（常用优先，其次按 sortOrder，NEW-09） */
function childrenOf(id?: number): Category[] {
  if (id == null) return []
  return sortByUsage(dict.childrenByParentId.get(id) ?? [])
}

/** 宫格分类排序：使用频次高的靠前，其余按 sortOrder / id 稳定排序（NEW-09） */
function sortByUsage(list: Category[]): Category[] {
  return [...list].sort((a, b) => {
    const ua = catUsage.value[a.id] ?? 0
    const ub = catUsage.value[b.id] ?? 0
    if (ua !== ub) return ub - ua
    return (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id
  })
}

/** 第 k 层宫格数据：k=0 为一级分类，k>0 为路径上第 k-1 项的子分类 */
function levelAt(k: number): Category[] {
  return k === 0 ? categories.value : childrenOf(pathIds.value[k - 1])
}

/** 第 k 层是否展示：一级恒展示（有数据时），下级需上一级已选且确有子分类 */
function showLevel(k: number): boolean {
  if (k === 0) return categories.value.length > 0
  return pathIds.value[k - 1] != null && levelAt(k).length > 0
}

/** 选中第 k 层的分类 c：截断更深层路径，并同步最终分类 */
function selectAt(k: number, c: Category) {
  pathIds.value = [...pathIds.value.slice(0, k), c.id]
  categoryId.value = c.id
  fieldErrors.categoryId = ''
}

/** 当前收支类型文案（空分类引导用） */
const typeLabel = computed(() => (type.value === 'income' ? '收入' : '支出'))

/** 当前类型没有任何分类时，引导跳转到 设置→分类管理 并定位到对应 Tab */
function goCreateCategory() {
  visible.value = false
  router.push(`/settings?menu=category&type=${type.value}`)
}

watch(visible, async (v) => {
  if (!v) {
    // 关闭时留存未提交草稿，下次打开可恢复（NEW-09）
    persistDraft()
    return
  }
  clearErrors()
  // 启动时字典加载失败（如桌面端后端子进程尚未就绪）时，在打开弹窗时补救重试，
  // 否则账户/分类下拉会一直空着
  await dict.loadAll().catch(() => {})
  resetForm()
  restoreDraft()
})

watch(type, () => {
  // 程序化回填（记忆 / 草稿 / 模板）期间不清空，避免刚恢复的分类被抹掉（NEW-09）
  if (restoring) return
  categoryId.value = undefined
  pathIds.value = []
  toAccountId.value = undefined
  fieldErrors.categoryId = ''
  fieldErrors.toAccountId = ''
  fieldErrors.fee = ''
})

/** 重置表单：不再硬编码「支出 + 第一个账户」，而是恢复上次使用的类型/账户/分类（NEW-09） */
function resetForm() {
  const mem = readJson<QuickMemory>(MEM_KEY) ?? {}
  restoring = true
  type.value = validType(mem.type) ? mem.type : 'expense'
  amount.value = ''
  accountId.value = validAccount(mem.accountId) ? mem.accountId : dict.activeAccounts[0]?.id
  toAccountId.value = defaultToAccount(mem.toAccountId)
  fee.value = ''
  categoryId.value = validCategory(mem.categoryId, type.value) ? mem.categoryId : undefined
  pathIds.value = categoryId.value != null ? pathToCategory(categoryId.value) : []
  note.value = ''
  tagIds.value = []
  nextTick(() => {
    restoring = false
  })
}

/** 转账默认转入账户：记住的上次转入账户（仍有效且不同于转出），否则第一个不同于转出的启用账户 */
function defaultToAccount(remembered?: number): number | undefined {
  if (type.value !== 'transfer') return undefined
  if (validAccount(remembered) && remembered !== accountId.value) return remembered
  return dict.activeAccounts.find((a) => a.id !== accountId.value)?.id
}

/** 打开时恢复未提交草稿（仅当草稿有实际内容），恢复后即清除，避免反复提示（NEW-09） */
function restoreDraft() {
  const draft = readJson<QuickDraft>(DRAFT_KEY)
  if (!draftWorthRestoring(draft)) return
  const d = draft as QuickDraft
  restoring = true
  type.value = validType(d.type) ? d.type : 'expense'
  amount.value = d.amount ?? ''
  accountId.value = validAccount(d.accountId) ? d.accountId : dict.activeAccounts[0]?.id
  toAccountId.value = validAccount(d.toAccountId) ? d.toAccountId : defaultToAccount(undefined)
  fee.value = d.fee ?? ''
  categoryId.value = validCategory(d.categoryId, type.value) ? d.categoryId : undefined
  pathIds.value = categoryId.value != null ? pathToCategory(d.categoryId) : []
  note.value = d.note ?? ''
  tagIds.value = (d.tagIds ?? []).filter((id) => !!dict.tagById(id))
  nextTick(() => {
    restoring = false
  })
  clearDraft()
  ElMessage.info('已恢复上次未提交的记账草稿')
}

// —— 常用模板（TR-03 / FR-TRX-09）：localStorage 持久化，一键回填表单 ——
const TPL_KEY = 'bookkeeping-quick-templates'

interface QuickTemplate {
  id: string
  name: string
  type: TransactionType
  amount: string
  accountId?: number
  toAccountId?: number
  fee: string
  categoryId?: number
  note: string
  tagIds: number[]
}

function loadTemplates(): QuickTemplate[] {
  try {
    const raw = localStorage.getItem(TPL_KEY)
    const parsed = raw ? (JSON.parse(raw) as QuickTemplate[]) : []
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

const templates = ref<QuickTemplate[]>(loadTemplates())

function persistTemplates() {
  try {
    localStorage.setItem(TPL_KEY, JSON.stringify(templates.value))
  } catch {
    // 存储配额不足时忽略，不影响记账
  }
}

/** 根据分类 id 回溯出宫格下钻路径（含选中项，与 selectAt 维护的 pathIds 一致） */
function pathToCategory(id?: number): number[] {
  if (id == null) return []
  const chain: number[] = []
  let cur = dict.categoryById(id)
  for (let guard = 0; cur && guard < 10; guard++) {
    chain.unshift(cur.id)
    cur = cur.parentId != null ? dict.categoryById(cur.parentId) : undefined
  }
  return chain
}

function applyTemplate(t: QuickTemplate) {
  // 模板失效检查（NEW-09）：关联账户/分类被删除时拦截套用
  const issue = templateIssue(t)
  if (issue) {
    ElMessage.warning(`该模板${issue}，无法套用`)
    return
  }
  // 用 restoring 标志抑制 watch(type) 清空，故可同步回填全部字段（NEW-09）
  restoring = true
  type.value = t.type
  amount.value = t.amount ?? ''
  accountId.value = validAccount(t.accountId) ? t.accountId : dict.activeAccounts[0]?.id
  toAccountId.value =
    t.type === 'transfer'
      ? validAccount(t.toAccountId)
        ? t.toAccountId
        : defaultToAccount(undefined)
      : undefined
  fee.value = t.fee ?? ''
  categoryId.value = validCategory(t.categoryId, t.type) ? t.categoryId : undefined
  pathIds.value = categoryId.value != null ? pathToCategory(t.categoryId) : []
  note.value = t.note ?? ''
  tagIds.value = [...(t.tagIds ?? [])]
  nextTick(() => {
    restoring = false
  })
}

async function saveTemplate() {
  if (type.value !== 'transfer' && !categoryId.value) {
    ElMessage.warning('请先选择分类再存为模板')
    return
  }
  let name: string
  try {
    const { value } = await ElMessageBox.prompt('给这个模板起个名字', '存为模板', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: note.value || dict.categoryById(categoryId.value)?.name || '常用模板',
      inputValidator: (v: string) => (v && v.trim() ? true : '模板名不能为空')
    })
    name = value.trim()
  } catch {
    return
  }
  const tpl: QuickTemplate = {
    id: `${Date.now()}`,
    name,
    type: type.value,
    amount: amount.value,
    accountId: accountId.value,
    toAccountId: toAccountId.value,
    fee: fee.value,
    categoryId: categoryId.value,
    note: note.value,
    tagIds: [...tagIds.value]
  }
  templates.value = [...templates.value, tpl]
  persistTemplates()
  ElMessage.success('模板已保存')
}

function removeTemplate(t: QuickTemplate) {
  templates.value = templates.value.filter((x) => x.id !== t.id)
  persistTemplates()
}

/** 模板失效检查（NEW-09）：关联账户/分类被删除时返回原因，供 UI 置灰与套用拦截 */
function templateIssue(t: QuickTemplate): string | null {
  if (t.accountId != null && !dict.accountById(t.accountId)) return '关联账户已删除'
  if (t.type === 'transfer') {
    if (t.toAccountId != null && !dict.accountById(t.toAccountId)) return '转入账户已删除'
  } else if (t.categoryId != null && !dict.categoryById(t.categoryId)) {
    return '关联分类已删除'
  }
  return null
}

function validate(): boolean {
  clearErrors()
  let ok = true
  if (!isValidAmountInput(amount.value) || Number(amount.value) <= 0) {
    fieldErrors.amount = '请输入大于 0 的金额，最多两位小数'
    ok = false
  }
  if (!accountId.value) {
    fieldErrors.accountId = '请选择账户'
    ok = false
  }
  if (type.value === 'transfer') {
    if (!toAccountId.value) {
      fieldErrors.toAccountId = '请选择转入账户'
      ok = false
    } else if (toAccountId.value === accountId.value) {
      fieldErrors.toAccountId = '转入账户不能与转出账户相同'
      ok = false
    }
    if (fee.value && !isValidAmountInput(fee.value)) {
      fieldErrors.fee = '手续费格式不正确'
      ok = false
    }
  } else if (!categoryId.value) {
    fieldErrors.categoryId = '请选择分类'
    ok = false
  }
  return ok
}

async function save(keepOpen: boolean) {
  if (!validate()) {
    focusFirstError()
    return
  }
  saving.value = true
  try {
    const exp = await transactionApi.saveWithReward({
      type: type.value,
      amount: Number(amount.value),
      fee: type.value === 'transfer' ? Number(fee.value || 0) : 0,
      accountId: accountId.value!,
      toAccountId: type.value === 'transfer' ? toAccountId.value! : null,
      categoryId: type.value === 'transfer' ? null : categoryId.value!,
      transactionDate: nowIso(),
      note: note.value.trim(),
      tags: JSON.stringify(tagIds.value)
    })
    ElMessage.success(exp > 0 ? `记账成功，获得 ${exp} 点经验` : '记账成功')
    bus.emit(TRANSACTION_CHANGED)
    emit('saved')
    // 记住本次类型/账户/分类，并累计分类使用频次（常用分类排序，NEW-09）
    saveMemory()
    bumpUsage(categoryId.value)
    clearDraft()
    // 无论是否连续记账都先清空「内容类」字段：避免关闭监听把刚提交的数据又存成草稿（NEW-09）
    amount.value = ''
    note.value = ''
    tagIds.value = []
    if (!keepOpen) {
      visible.value = false
    }
  } catch {
    // 失败提示由响应拦截器统一处理
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="快速记账"
    width="560px"
    class="bk-dialog quiet-controls"
    :close-on-click-modal="false"
    append-to-body
  >
    <div class="record-tabs" role="tablist" aria-label="交易类型">
      <button
        type="button"
        v-for="t in typeTabs"
        :key="t.value"
        class="record-tab"
        :class="[`qr-tab--${t.value}`, { 'is-active': type === t.value }]"
        role="tab"
        tabindex="0"
        :aria-selected="type === t.value"
        @click="type = t.value"
        @keydown="onTabKey($event, t.value)"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- 常用模板（TR-03）：点击回填，× 删除；失效模板置灰并拦截套用（NEW-09） -->
    <div v-if="templates.length" class="qr-tpls">
      <span class="qr-tpls__label">模板</span>
      <div
        v-for="t in templates"
        :key="t.id"
        class="qr-tpl"
        :class="{ 'is-stale': !!templateIssue(t) }"
        :title="templateIssue(t) ? `${t.name}（${templateIssue(t)}）` : `一键填入「${t.name}」`"
      >
        <button type="button" class="qr-tpl__name" :disabled="!!templateIssue(t)" @click="applyTemplate(t)">{{ t.name }}</button>
        <button type="button" class="qr-tpl__del" :aria-label="`删除模板${t.name}`" @click="removeTemplate(t)">×</button>
      </div>
    </div>

    <div class="qr-amount" :class="{ 'is-error': fieldErrors.amount }">
      <span class="qr-amount__symbol">¥</span>
      <input
        ref="amountInputRef"
        v-model="amount"
        class="qr-amount__input"
        inputmode="decimal"
        aria-label="记账金额"
        :aria-invalid="!!fieldErrors.amount"
        aria-describedby="quick-amount-error"
        placeholder="0.00"
        @input="amount = sanitizeAmountInput(amount); fieldErrors.amount = ''"
      />
    </div>
    <div v-if="fieldErrors.amount" id="quick-amount-error" class="qr-amount__error" role="alert">{{ fieldErrors.amount }}</div>

    <el-form ref="formRef" label-position="top" class="qr-form" @submit.prevent>
      <el-form-item label="账户" :error="fieldErrors.accountId">
        <el-select v-model="accountId" placeholder="选择账户" style="width: 100%" @change="fieldErrors.accountId = ''">
          <el-option
            v-for="a in dict.activeAccounts"
            :key="a.id"
            :label="a.name"
            :value="a.id"
          >
            <AccountOption :account="a" />
          </el-option>
        </el-select>
      </el-form-item>

      <template v-if="type === 'transfer'">
        <el-form-item label="转入账户" :error="fieldErrors.toAccountId">
          <el-select v-model="toAccountId" placeholder="选择转入账户" style="width: 100%" @change="fieldErrors.toAccountId = ''">
            <el-option
              v-for="a in dict.activeAccounts"
              :key="a.id"
              :label="a.name"
              :value="a.id"
              :disabled="a.id === accountId"
            >
              <AccountOption :account="a" />
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="手续费（可选）" :error="fieldErrors.fee">
          <el-input v-model="fee" placeholder="0.00" @input="fee = sanitizeAmountInput(fee); fieldErrors.fee = ''">
            <template #prepend>¥</template>
          </el-input>
        </el-form-item>
      </template>

      <el-form-item v-else label="分类" :error="fieldErrors.categoryId">
        <div v-if="categories.length" class="qr-cats-wrap">
          <template v-for="k in [0, 1, 2]" :key="k">
            <div v-if="showLevel(k)" class="qr-cats-level">
              <div v-if="k > 0" class="qr-cats-crumb">
                {{ dict.categoryById(pathIds[k - 1])?.name }} 的子分类
              </div>
              <div class="qr-cats">
                <button
                  type="button"
                  v-for="c in levelAt(k)"
                  :key="c.id"
                  class="qr-cat"
                  :class="{ 'is-active': pathIds[k] === c.id, 'is-selected': categoryId === c.id }"
                  :aria-pressed="categoryId === c.id"
                  @click="selectAt(k, c)"
                >
                  <span class="qr-cat__dot" :style="{ background: c.color || '#909399' }">
                    {{ c.name.slice(0, 1) }}
                  </span>
                  <span class="qr-cat__name">{{ c.name }}</span>
                </button>
              </div>
            </div>
          </template>
        </div>
        <EmptyState v-else :size="64" :description="`暂无${typeLabel}分类`">
          <el-button type="primary" link @click="goCreateCategory">去创建{{ typeLabel }}分类</el-button>
        </EmptyState>
      </el-form-item>

      <h3 class="dialog-section__title">附加信息</h3>
      <el-form-item label="标签（可选）">
        <el-select
          v-model="tagIds"
          multiple
          clearable
          collapse-tags
          placeholder="选择标签"
          style="width: 100%"
        >
          <el-option v-for="t in dict.tags" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="备注（可选）">
        <el-input v-model="note" maxlength="200" show-word-limit placeholder="添加备注..." />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="dialog-footer qr-footer">
        <el-button text type="primary" @click="saveTemplate">存为模板</el-button>
        <div class="qr-footer__spacer" />
        <el-button @click="visible = false">取消</el-button>
        <el-button :loading="saving" @click="save(true)">保存并记下一笔</el-button>
        <el-button :type="saveBtnType" :loading="saving" @click="save(false)">保存</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.qr-amount:focus-within {
  box-shadow: 0 0 0 2px var(--bk-primary-soft);
  border-color: var(--bk-button-primary);
}

.qr-amount {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  margin-bottom: 22px;
  background: var(--bk-surface-2);
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
}

.qr-amount.is-error {
  border-color: var(--el-color-danger);
}

.qr-amount__error {
  margin: -10px 0 16px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--bk-expense-text);
}

.qr-amount__symbol {
  font-size: 22px;
  color: var(--el-text-color-secondary);
}

.qr-amount__input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 28px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
  min-width: 0;
}

.qr-cats-wrap {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.qr-cats-crumb {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.qr-cats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: 8px;
  width: 100%;
}

.qr-cat {
  background: var(--bk-surface);
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px 4px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.qr-cat:hover {
  border-color: var(--el-color-primary-light-5);
}

.qr-cat.is-active {
  border-color: var(--el-color-primary);
}

.qr-cat.is-selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.qr-cat__dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: #fff;
  font-size: 13px;
}

.qr-cat__name {
  font-size: 12px;
  color: var(--el-text-color-regular);
  max-width: 100%;
  overflow-wrap: anywhere;
}

.qr-form :deep(.el-form-item) {
  margin-bottom: 22px;
}

.qr-tpls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.qr-tpls__label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.qr-tpl {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid var(--el-border-color);
  border-radius: 14px;
  background: var(--el-fill-color-light);
  cursor: pointer;
  font-size: 12px;
  color: var(--el-text-color-regular);
  transition: all 0.15s;
}

.qr-tpl:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.qr-tpl.is-stale {
  color: var(--bk-text-secondary);
  border-style: dashed;
}

.qr-tpl.is-stale:hover {
  border-color: var(--el-color-warning);
  color: var(--el-color-warning);
}

.qr-tpl__name,
.qr-tpl__del {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 4px;
  overflow-wrap: anywhere;
}

.qr-tpl__name:disabled { cursor: not-allowed; }

.qr-tpl__del {
  font-size: 16px;
  line-height: 1;
  color: var(--el-text-color-secondary);
}

.qr-tpl__del:hover {
  color: var(--bk-expense-text);
}

.qr-footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.qr-footer__spacer {
  flex: 1;
}
</style>
