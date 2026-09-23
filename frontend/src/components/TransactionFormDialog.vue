<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useRouter } from 'vue-router'
import { transactionApi } from '@/api'
import { useDictStore } from '@/stores/dict'
import { parseTagIds, type Transaction, type TransactionType } from '@/types/model'
import { addDays, isValidAmountInput, nowIso, sanitizeAmountInput } from '@/utils/format'
import { bus, TRANSACTION_CHANGED } from '@/utils/bus'
import AccountOption from '@/components/AccountOption.vue'

/**
 * 完整交易表单对话框（需求文档 4.2.3）：
 * 支持 新增（create）/ 编辑（edit，回显并 update）/ 复制（copy，回显但 save 新记录）。
 */
const props = defineProps<{
  modelValue: boolean
  mode: 'create' | 'edit' | 'copy' | 'backfill'
  /** 编辑/复制时回显的交易 */
  initial?: Transaction | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const dict = useDictStore()
const router = useRouter()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => { if (!saving.value) emit('update:modelValue', v) }
})

const isBackfill = computed(() => props.mode === 'backfill')
const title = computed(() =>
  isBackfill.value ? '补流水' : props.mode === 'edit' ? '编辑记录' : props.mode === 'copy' ? '复制记录' : '新增记录'
)

const typeTabs: { value: TransactionType; label: string }[] = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' }
]

const form = reactive({
  type: 'expense' as TransactionType,
  amount: '',
  fee: '',
  accountId: undefined as number | undefined,
  toAccountId: undefined as number | undefined,
  categoryId: undefined as number | undefined,
  transactionDate: '',
  note: '',
  tagIds: [] as number[]
})

const saving = ref(false)
const initializing = ref(false)
const formRef = ref<FormInstance>()
const amountRef = ref<{ focus: () => void }>()

function choosePastDay(days: number) {
  if (saving.value) return
  const now = nowIso()
  form.transactionDate = `${addDays(now, -days)}T${(form.transactionDate || now).slice(11, 19)}`
  fieldErrors.transactionDate = ''
}

function disableFutureDate(date: Date): boolean {
  const today = new Date()
  today.setHours(23, 59, 59, 999)
  return date.getTime() > today.getTime()
}

function beforeClose(done: () => void) {
  if (!saving.value) done()
}

/** 字段级内联错误：逐字段在下方展示红字，取代整表单单条 Toast */
const fieldErrors = reactive({
  amount: '',
  accountId: '',
  toAccountId: '',
  fee: '',
  categoryId: '',
  transactionDate: ''
})

function clearErrors() {
  fieldErrors.amount = ''
  fieldErrors.accountId = ''
  fieldErrors.toAccountId = ''
  fieldErrors.fee = ''
  fieldErrors.categoryId = ''
  fieldErrors.transactionDate = ''
}

/** 首个错误字段滚动到可视区并聚焦，避免长表单里错误在视野外 */
function focusFirstError() {
  nextTick(() => {
    const el = formRef.value?.$el?.querySelector('.el-form-item.is-error') as HTMLElement | null
    if (!el) return
    el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    const focusable = el.querySelector('input, textarea') as HTMLElement | null
    focusable?.focus?.()
  })
}

/** 类型 tabs 键盘操作：Enter / Space 选中（配合 role=tab / tabindex） */
function onTabKey(e: KeyboardEvent, t: TransactionType) {
  if (saving.value || initializing.value) return
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    form.type = t
  }
}

/** 当前类型的分类树（转账不展示分类）；级联选择器可逐级下钻到子分类 */
const categories = computed(() =>
  form.type === 'transfer' ? [] : dict.categoryTreeByType(form.type)
)

/** 当前类型没有任何分类时，引导跳转到 设置→分类管理 并定位到对应 Tab */
function goCreateCategory() {
  visible.value = false
  router.push(`/settings?menu=category&type=${form.type}`)
}

watch(visible, async (v, _, onCleanup) => {
  if (!v) return
  let cancelled = false
  onCleanup(() => { cancelled = true })
  initializing.value = true
  clearErrors()
  // 启动时字典加载失败（如桌面端后端子进程尚未就绪）时，在打开弹窗时补救重试
  await dict.loadAll().catch(() => {})
  if (cancelled) return
  if (props.initial && (props.mode === 'edit' || props.mode === 'copy')) {
    form.type = props.initial.type
    form.amount = String(props.initial.amount ?? '')
    form.fee = String(props.initial.fee ?? '')
    form.accountId = props.initial.accountId
    form.toAccountId = props.initial.toAccountId ?? undefined
    form.categoryId = props.initial.categoryId ?? undefined
    form.transactionDate = props.initial.transactionDate
    form.note = props.initial.note ?? ''
    form.tagIds = parseTagIds(props.initial.tags)
  } else {
    form.type = 'expense'
    form.amount = ''
    form.fee = ''
    form.accountId = dict.activeAccounts[0]?.id
    form.toAccountId = undefined
    form.categoryId = undefined
    form.transactionDate = nowIso()
    form.note = ''
    form.tagIds = []
    if (isBackfill.value) choosePastDay(1)
  }
  initializing.value = false
})

watch(
  () => form.type,
  () => {
    if (initializing.value) return
    form.categoryId = undefined
    form.toAccountId = undefined
    fieldErrors.categoryId = ''
    fieldErrors.toAccountId = ''
    fieldErrors.fee = ''
  },
  // 同步处理用户切换，初始化期间跳过，避免稍后清空回填字段。
  { flush: 'sync' }
)

function validate(): boolean {
  clearErrors()
  let ok = true
  if (!isValidAmountInput(form.amount) || Number(form.amount) <= 0) {
    fieldErrors.amount = '请输入大于 0 的金额，最多两位小数'
    ok = false
  }
  if (!form.accountId) {
    fieldErrors.accountId = '请选择账户'
    ok = false
  }
  if (form.type === 'transfer') {
    if (!form.toAccountId) {
      fieldErrors.toAccountId = '请选择转入账户'
      ok = false
    } else if (form.toAccountId === form.accountId) {
      fieldErrors.toAccountId = '转入账户不能与转出账户相同'
      ok = false
    }
    if (form.fee && !isValidAmountInput(form.fee)) {
      fieldErrors.fee = '手续费格式不正确'
      ok = false
    }
  } else if (!form.categoryId) {
    fieldErrors.categoryId = '请选择分类'
    ok = false
  }
  if (!form.transactionDate) {
    fieldErrors.transactionDate = '请选择日期'
    ok = false
  } else if (isBackfill.value) {
    const timestamp = new Date(form.transactionDate).getTime()
    if (!Number.isFinite(timestamp) || timestamp > Date.now()) {
      fieldErrors.transactionDate = '补录时间不能晚于当前时间，请选择已发生的时间'
      ok = false
    }
  }
  return ok
}

async function save(continueRecording = false) {
  if (saving.value || initializing.value) return
  if (!validate()) {
    focusFirstError()
    return
  }
  saving.value = true
  const keepOpen = isBackfill.value && continueRecording
  let succeeded = false
  try {
    const payload = {
      type: form.type,
      amount: Number(form.amount),
      fee: form.type === 'transfer' ? Number(form.fee || 0) : 0,
      accountId: form.accountId!,
      toAccountId: form.type === 'transfer' ? form.toAccountId! : null,
      categoryId: form.type === 'transfer' ? null : form.categoryId!,
      transactionDate: form.transactionDate,
      note: form.note.trim(),
      tags: JSON.stringify(form.tagIds)
    }
    if (props.mode === 'edit' && props.initial) {
      await transactionApi.update({ ...payload, id: props.initial.id })
      ElMessage.success('修改成功')
    } else {
      const exp = await transactionApi.saveWithReward(payload)
      ElMessage.success(isBackfill.value
        ? `已补录 ${payload.transactionDate.slice(0, 10)} 的流水，本次获得 ${exp} 点经验`
        : exp > 0 ? `记账成功，获得 ${exp} 点经验` : '保存成功')
    }
    bus.emit(TRANSACTION_CHANGED)
    emit('saved')
    succeeded = true
    if (keepOpen) {
      form.amount = ''
      form.fee = ''
      form.categoryId = undefined
      form.note = ''
      form.tagIds = []
      clearErrors()
      formRef.value?.clearValidate()
    } else {
      emit('update:modelValue', false)
    }
  } catch {
    // 失败提示由响应拦截器统一处理
  } finally {
    saving.value = false
  }
  if (succeeded && keepOpen) {
    await nextTick()
    amountRef.value?.focus()
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="560px"
    class="bk-dialog quiet-controls"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    :before-close="beforeClose"
    append-to-body
  >
    <div class="record-tabs" role="tablist" aria-label="交易类型">
      <button
        type="button"
        v-for="t in typeTabs"
        :key="t.value"
        class="record-tab"
        :class="[`tf-tab--${t.value}`, { 'is-active': form.type === t.value }]"
        role="tab"
        tabindex="0"
        :aria-selected="form.type === t.value"
        :disabled="saving || initializing"
        @click="form.type = t.value"
        @keydown="onTabKey($event, t.value)"
      >
        {{ t.label }}
      </button>
    </div>

    <el-form ref="formRef" label-position="top" :disabled="saving || initializing" :aria-busy="saving || initializing" @submit.prevent>
      <template v-if="isBackfill">
        <p class="tf-backfill-note">按发生日期计入账本；奖励计入今日额度，已完成的月结经验不重算。</p>
        <el-form-item label="发生时间" required :error="fieldErrors.transactionDate" class="tf-backfill-date">
          <el-date-picker
            v-model="form.transactionDate"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择实际发生时间"
            :disabled-date="disableFutureDate"
            style="width: 100%"
            @change="fieldErrors.transactionDate = ''"
          />
          <div class="tf-date-shortcuts">
            <el-button @click="choosePastDay(1)">昨天</el-button>
            <el-button @click="choosePastDay(2)">前天</el-button>
          </div>
        </el-form-item>
      </template>
      <el-form-item label="金额" required :error="fieldErrors.amount" class="record-amount">
        <el-input ref="amountRef" v-model="form.amount" placeholder="0.00" @input="form.amount = sanitizeAmountInput(form.amount); fieldErrors.amount = ''">
          <template #prepend>¥</template>
        </el-input>
      </el-form-item>

      <el-form-item label="账户" required :error="fieldErrors.accountId">
        <el-select v-model="form.accountId" :placeholder="form.type === 'transfer' ? '转出账户' : '选择账户'" style="width: 100%" @change="fieldErrors.accountId = ''">
          <el-option v-for="a in dict.activeAccounts" :key="a.id" :label="a.name" :value="a.id">
            <AccountOption :account="a" />
          </el-option>
        </el-select>
      </el-form-item>

      <template v-if="form.type === 'transfer'">
        <el-form-item label="转入账户" required :error="fieldErrors.toAccountId">
          <el-select v-model="form.toAccountId" placeholder="选择转入账户" style="width: 100%" @change="fieldErrors.toAccountId = ''">
            <el-option
              v-for="a in dict.activeAccounts"
              :key="a.id"
              :label="a.name"
              :value="a.id"
              :disabled="a.id === form.accountId"
            >
              <AccountOption :account="a" />
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="手续费" :error="fieldErrors.fee">
          <el-input v-model="form.fee" placeholder="0.00（可选）" @input="form.fee = sanitizeAmountInput(form.fee); fieldErrors.fee = ''">
            <template #prepend>¥</template>
          </el-input>
        </el-form-item>
      </template>

      <el-form-item v-else label="分类" required :error="fieldErrors.categoryId">
        <el-cascader
          v-model="form.categoryId"
          :options="categories"
          :props="{ value: 'id', label: 'name', emitPath: false, checkStrictly: true }"
          placeholder="选择分类"
          clearable
          style="width: 100%"
          @change="fieldErrors.categoryId = ''"
        >
          <template #default="{ data }">
            <span class="tf-cat-option">
              <span class="tf-cat-option__dot" :style="{ background: data.color || '#909399' }">
                {{ data.name.slice(0, 1) }}
              </span>
              {{ data.name }}
            </span>
          </template>
        </el-cascader>
        <div v-if="!categories.length" class="tf-cat-tip">
          暂无{{ form.type === 'income' ? '收入' : '支出' }}分类，
          <el-button link type="primary" @click="goCreateCategory">去创建</el-button>
        </div>
      </el-form-item>

      <h3 class="dialog-section__title">附加信息</h3>
      <el-form-item v-if="!isBackfill" label="日期" required :error="fieldErrors.transactionDate">
        <el-date-picker
          v-model="form.transactionDate"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="选择日期时间"
          style="width: 100%"
          @change="fieldErrors.transactionDate = ''"
        />
      </el-form-item>

      <el-form-item label="标签">
        <el-select v-model="form.tagIds" multiple clearable collapse-tags placeholder="选择标签（可选）" style="width: 100%">
          <el-option v-for="t in dict.tags" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.note" maxlength="200" show-word-limit placeholder="添加备注（可选）" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="dialog-footer">
        <el-button :disabled="saving" @click="visible = false">取消</el-button>
        <el-button :type="isBackfill ? 'default' : 'primary'" :loading="saving" :disabled="initializing" @click="save()">保存</el-button>
        <el-button v-if="isBackfill" type="primary" :loading="saving" :disabled="initializing" @click="save(true)">保存并继续</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.tf-backfill-note {
  margin: 0 0 18px;
  padding: 12px 14px;
  border-radius: var(--bk-radius-sm);
  background: var(--el-fill-color-light);
  color: var(--bk-text-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.tf-date-shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--bk-action-gap);
  margin-top: 8px;
}

.tf-date-shortcuts :deep(.el-button + .el-button) { margin-left: 0; }
.record-tab:disabled { cursor: wait; opacity: .6; }

.record-amount :deep(.el-input__inner) {
  height: 48px;
  font-size: 26px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.tf-cat-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.tf-cat-option__dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  color: #fff;
  font-size: 12px;
}

.tf-cat-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
