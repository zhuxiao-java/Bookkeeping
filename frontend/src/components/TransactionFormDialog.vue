<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useRouter } from 'vue-router'
import { transactionApi } from '@/api'
import { useDictStore } from '@/stores/dict'
import { parseTagIds, type Transaction, type TransactionType } from '@/types/model'
import { isValidAmountInput, nowIso, sanitizeAmountInput } from '@/utils/format'
import { bus, TRANSACTION_CHANGED } from '@/utils/bus'
import AccountOption from '@/components/AccountOption.vue'

/**
 * 完整交易表单对话框（需求文档 4.2.3）：
 * 支持 新增（create）/ 编辑（edit，回显并 update）/ 复制（copy，回显但 save 新记录）。
 */
const props = defineProps<{
  modelValue: boolean
  mode: 'create' | 'edit' | 'copy'
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
  set: (v) => emit('update:modelValue', v)
})

const title = computed(() =>
  props.mode === 'edit' ? '编辑记录' : props.mode === 'copy' ? '复制记录' : '新增记录'
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
const formRef = ref<FormInstance>()

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

watch(visible, async (v) => {
  if (!v) return
  clearErrors()
  // 启动时字典加载失败（如桌面端后端子进程尚未就绪）时，在打开弹窗时补救重试
  await dict.loadAll().catch(() => {})
  if (props.initial && props.mode !== 'create') {
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
  }
})

watch(
  () => form.type,
  () => {
    form.categoryId = undefined
    form.toAccountId = undefined
    fieldErrors.categoryId = ''
    fieldErrors.toAccountId = ''
    fieldErrors.fee = ''
  }
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
  }
  return ok
}

async function save() {
  if (!validate()) {
    focusFirstError()
    return
  }
  saving.value = true
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
      ElMessage.success(exp > 0 ? `记账成功，获得 ${exp} 点经验` : '保存成功')
    }
    bus.emit(TRANSACTION_CHANGED)
    emit('saved')
    visible.value = false
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
    :title="title"
    width="560px"
    :close-on-click-modal="false"
    append-to-body
  >
    <div class="tf-tabs" role="tablist">
      <div
        v-for="t in typeTabs"
        :key="t.value"
        class="tf-tab"
        :class="[`tf-tab--${t.value}`, { 'is-active': form.type === t.value }]"
        role="tab"
        tabindex="0"
        :aria-selected="form.type === t.value"
        @click="form.type = t.value"
        @keydown="onTabKey($event, t.value)"
      >
        {{ t.label }}
      </div>
    </div>

    <el-form ref="formRef" label-width="90px" @submit.prevent>
      <el-form-item label="金额" required :error="fieldErrors.amount">
        <el-input v-model="form.amount" placeholder="0.00" @input="form.amount = sanitizeAmountInput(form.amount); fieldErrors.amount = ''">
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

      <el-form-item label="日期" required :error="fieldErrors.transactionDate">
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
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tf-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 18px;
}

.tf-tab {
  flex: 1;
  text-align: center;
  padding: 8px 0;
  border-radius: 8px;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
  cursor: pointer;
  font-size: 14px;
  user-select: none;
  transition: all 0.15s;
}

.tf-tab--expense.is-active {
  background: var(--el-color-danger);
  color: #fff;
}

.tf-tab--income.is-active {
  background: var(--el-color-success);
  color: #fff;
}

.tf-tab--transfer.is-active {
  background: var(--el-color-primary);
  color: #fff;
}

.tf-tab:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
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
