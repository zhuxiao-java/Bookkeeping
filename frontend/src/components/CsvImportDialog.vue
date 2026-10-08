<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import EmptyState from './EmptyState.vue'
import AccountOption from './AccountOption.vue'
import { backupApi, ApiError } from '@/api'
import type { CsvImportResult, CsvPreviewResult, CsvRowStatus } from '@/api'
import { useDictStore } from '@/stores/dict'

/**
 * 流水导入向导：三步流程
 * 1) 选择文件 → 2) 为文件中的账户选择记入账户，并预览 → 3) 导入结果
 * 小于等于 0 的选择表示按文件中的名称新建账户。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'imported', result: CsvImportResult): void
}>()

const dict = useDictStore()
/** 按文件中的账户名新建，而不是记入已有账户 */
const CREATE_ACCOUNT = -1

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

type Step = 0 | 1 | 2
const step = ref<Step>(0)

const fileInput = ref<HTMLInputElement>()
const file = ref<File | null>(null)
const previewing = ref(false)
const importing = ref(false)

const preview = ref<CsvPreviewResult | null>(null)
const result = ref<CsvImportResult | null>(null)
/** 是否跳过重复行（与库中或文件内），默认开启 */
const skipDuplicates = ref(true)
/** 预览表状态筛选 */
const statusFilter = ref<'all' | CsvRowStatus>('all')
/** 文件中的账户名 → 已有账户 id；CREATE_ACCOUNT 表示按该名称新建 */
const accountChoices = reactive<Record<string, number | null>>({})
/** 把文件中的全部账户改记到同一个已有账户 */
const bulkAccountId = ref<number | undefined>()
let previewSeq = 0

const filteredRows = computed(() => {
  const rows = preview.value?.rows ?? []
  if (statusFilter.value === 'all') return rows
  return rows.filter((r) => r.status === statusFilter.value)
})

function statusTagType(status: CsvRowStatus): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'valid') return 'success'
  if (status === 'invalid') return 'danger'
  if (status === 'ignored') return 'info'
  return 'warning'
}
function statusLabel(status: CsvRowStatus): string {
  if (status === 'valid') return '可导入'
  if (status === 'invalid') return '无法解析'
  if (status === 'ignored') return '已跳过'
  return '重复'
}

const accountNames = computed(() => preview.value?.accountNames ?? [])

const accountsReady = computed(() => accountNames.value.every((name) => accountChoices[name] != null))

/** 打开时重置向导；关闭时清理状态 */
watch(visible, (v) => {
  if (v) {
    reset()
    dict.loadAll().catch(() => {})
  }
})

function reset() {
  step.value = 0
  file.value = null
  preview.value = null
  result.value = null
  skipDuplicates.value = true
  statusFilter.value = 'all'
  previewing.value = false
  importing.value = false
  bulkAccountId.value = undefined
  clearChoices()
}

function clearChoices() {
  for (const key of Object.keys(accountChoices)) delete accountChoices[key]
}

function matchAccountId(name: string): number | null {
  const accounts = dict.activeAccounts
  const exact = accounts.find((account) => account.name === name)
  if (exact) return exact.id
  if (name === '微信零钱') return accounts.find((account) => account.name === '零钱')?.id ?? null
  if (name === '支付宝') {
    return accounts.find((account) => account.name === '余额' || account.name === '账户余额' || account.name === '支付宝余额')?.id ?? null
  }
  return null
}

function ensureChoices(names: string[]) {
  for (const name of names) {
    if (!(name in accountChoices)) accountChoices[name] = matchAccountId(name)
  }
}

function partialMap(): Record<string, number> | undefined {
  const map: Record<string, number> = {}
  for (const name of accountNames.value) {
    const value = accountChoices[name]
    if (value != null) map[name] = value
  }
  return Object.keys(map).length ? map : undefined
}

function chosenMap(): Record<string, number> | undefined {
  if (!accountNames.value.length) return undefined
  const map: Record<string, number> = {}
  for (const name of accountNames.value) {
    const value = accountChoices[name]
    if (value == null) return undefined
    map[name] = value
  }
  return map
}

function pickFile() {
  fileInput.value?.click()
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const picked = input.files?.[0]
  input.value = ''
  if (!picked) return
  file.value = picked
  await loadPreview()
}

async function loadPreview() {
  if (!file.value) return
  const seq = ++previewSeq
  previewing.value = true
  try {
    await dict.loadAll().catch(() => {})
    const result = await backupApi.previewTransactionsCsv(file.value, partialMap())
    if (seq !== previewSeq) return
    preview.value = result
    ensureChoices(result.accountNames ?? [])
    step.value = 1
  } catch (err) {
    if (seq !== previewSeq) return
    if (!(err instanceof ApiError)) {
      ElMessage.error(err instanceof Error ? err.message : 'CSV 解析失败')
    }
  } finally {
    if (seq === previewSeq) previewing.value = false
  }
}

function onBulkAccount(id: number | undefined) {
  if (id == null) return
  for (const name of accountNames.value) accountChoices[name] = id
  void loadPreview()
}

function onAccountChoice() {
  bulkAccountId.value = undefined
  void loadPreview()
}

async function doImport() {
  if (!file.value || !accountsReady.value) return
  importing.value = true
  try {
    result.value = await backupApi.importTransactionsCsv(file.value, skipDuplicates.value, chosenMap())
    step.value = 2
    emit('imported', result.value)
  } catch (err) {
    if (!(err instanceof ApiError)) {
      ElMessage.error(err instanceof Error ? err.message : '导入失败')
    }
  } finally {
    importing.value = false
  }
}

function backToPick() {
  step.value = 0
  file.value = null
  preview.value = null
}

function close() {
  visible.value = false
}

/** 可导入行数（预览统计），用于禁用「开始导入」按钮 */
const importableCount = computed(() => {
  const p = preview.value
  if (!p) return 0
  // 跳过重复时，仅 valid 会被导入；否则 valid + duplicate 都会尝试导入
  return skipDuplicates.value ? p.validCount : p.validCount + p.duplicateCount
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="导入流水"
    width="820px"
    :close-on-click-modal="false"
    append-to-body
    class="bk-dialog quiet-controls csv-dialog"
  >
    <el-steps :active="step" align-center finish-status="success" class="csv-steps">
      <el-step title="选择文件" />
      <el-step title="预览校验" />
      <el-step title="导入结果" />
    </el-steps>

    <!-- 步骤 1：选择文件 -->
    <div v-if="step === 0" class="csv-body">
      <button type="button" class="csv-drop" :class="{ 'is-busy': previewing }" :disabled="previewing" @click="pickFile">
        <el-icon class="csv-drop__icon"><UploadFilled /></el-icon>
        <div class="csv-drop__title">点击选择 CSV 或 Excel 文件</div>
        <div class="csv-drop__tip">
          支持本应用导出的流水 CSV，以及微信、支付宝「用于个人对账」的 csv、xlsx。请先自行解压官方压缩包。
        </div>
        <div v-if="previewing" class="csv-drop__loading">正在解析…</div>
      </button>
      <input
        ref="fileInput"
        type="file"
        accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        class="csv-file"
        @change="onFileChange"
      />
      <el-alert
        class="csv-alert"
        type="info"
        :closable="false"
        show-icon
        title="导入只新增、不覆盖。下一步为文件中的每个账户选择记入的已有账户，或按原名新建。分类对不上时记入「待整理」或「待整理收入」。交易关闭、已全额退款和信用卡还款会跳过，同一交易单号不会重复入账。"
      />
    </div>

    <!-- 步骤 2：预览 -->
    <div v-else-if="step === 1 && preview" class="csv-body">
      <div class="csv-summary">
        <span class="csv-summary__file">{{ file?.name }}</span>
        <el-tag type="info" effect="plain">共 {{ preview.total }} 行</el-tag>
        <el-tag type="success" effect="plain">可导入 {{ preview.validCount }}</el-tag>
        <el-tag type="warning" effect="plain">重复 {{ preview.duplicateCount }}</el-tag>
        <el-tag type="danger" effect="plain">无法解析 {{ preview.invalidCount }}</el-tag>
        <el-tag type="info" effect="plain">已跳过 {{ preview.ignoredCount ?? 0 }}</el-tag>
      </div>

      <div class="csv-accounts">
        <div class="csv-accounts__head">
          <span class="csv-accounts__title">记入账户</span>
          <el-select
            v-model="bulkAccountId"
            class="csv-accounts__bulk"
            placeholder="全部记入同一账户"
            clearable
            :disabled="previewing || !accountNames.length"
            @change="onBulkAccount"
          >
            <el-option v-for="account in dict.activeAccounts" :key="account.id" :label="account.name" :value="account.id">
              <AccountOption :account="account" />
            </el-option>
          </el-select>
        </div>
        <p class="csv-accounts__tip">
          文件里的支付方式或账户名可以改记到已有账户。选「新建」会在导入时按该名称建账户。提现和充值按转出、转入分别记。
        </p>
        <div v-if="accountNames.length" class="csv-accounts__list">
          <div v-for="name in accountNames" :key="name" class="csv-account-row">
            <span class="csv-account-row__name" :title="name">{{ name }}</span>
            <el-select
              v-model="accountChoices[name]"
              class="csv-account-row__select"
              placeholder="选择账户"
              :disabled="previewing"
              @change="onAccountChoice"
            >
              <el-option v-for="account in dict.activeAccounts" :key="account.id" :label="account.name" :value="account.id">
                <AccountOption :account="account" />
              </el-option>
              <el-option :value="CREATE_ACCOUNT" :label="`新建「${name}」`" />
            </el-select>
          </div>
        </div>
        <p v-else class="csv-accounts__tip">这份文件里没有可选择的账户。</p>
      </div>

      <div class="csv-toolbar">
        <el-radio-group v-model="statusFilter" class="segment" aria-label="校验结果筛选" size="small">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="valid">可导入</el-radio-button>
          <el-radio-button value="duplicate">重复</el-radio-button>
          <el-radio-button value="invalid">无法解析</el-radio-button>
          <el-radio-button value="ignored">已跳过</el-radio-button>
        </el-radio-group>
        <el-checkbox v-model="skipDuplicates">跳过重复行</el-checkbox>
      </div>

      <el-table :data="filteredRows" size="small" height="240" class="csv-table">
        <el-table-column prop="line" label="行" width="56" />
        <el-table-column prop="date" label="日期" width="150" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="72" />
        <el-table-column prop="amount" label="金额" width="90" align="right" />
        <el-table-column prop="accountName" label="账户" width="90" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="90" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="说明" min-width="140" show-overflow-tooltip />
        <template #empty><EmptyState description="该筛选下暂无数据" :size="64" /></template>
      </el-table>
      <div v-if="preview.rows.length < preview.total" class="csv-note">
        仅展示前 {{ preview.rows.length }} 行明细，统计数字已覆盖全部 {{ preview.total }} 行。
      </div>
    </div>

    <!-- 步骤 3：结果 -->
    <div v-else-if="step === 2 && result" class="csv-body">
      <el-result
        :icon="result.imported > 0 ? 'success' : 'info'"
        :title="`导入完成：新增 ${result.imported} 条`"
        :sub-title="`共 ${result.total} 行 · 跳过 ${result.skipped} 条 · 重复 ${result.duplicates} 条`"
      />
      <div v-if="result.failures.length" class="csv-failures">
        <div class="csv-failures__title">跳过 / 失败明细（{{ result.failures.length }}）</div>
        <el-scrollbar max-height="200px">
          <ul class="csv-failures__list">
            <li v-for="(f, i) in result.failures" :key="i">
              <span class="csv-failures__line">第 {{ f.line }} 行</span>
              <span class="csv-failures__reason">{{ f.reason }}</span>
            </li>
          </ul>
        </el-scrollbar>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer csv-footer">
        <template v-if="step === 0">
          <el-button @click="close">取消</el-button>
        </template>
        <template v-else-if="step === 1">
          <el-button :disabled="importing" @click="backToPick">上一步</el-button>
          <el-button
            type="primary"
            :loading="importing"
            :disabled="importableCount === 0 || !accountsReady || previewing"
            @click="doImport"
          >
            {{ accountsReady ? `开始导入（${importableCount}）` : '请选择账户' }}
          </el-button>
        </template>
        <template v-else>
          <el-button @click="backToPick">再导入一个</el-button>
          <el-button type="primary" @click="close">完成</el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.csv-steps {
  margin-bottom: var(--bk-row-padding);
}

.csv-body {
  min-height: 240px;
}

.csv-drop {
  width: 100%;
  background: var(--bk-surface-2);
  color: var(--bk-text);
  border: 1px dashed var(--bk-border);
  border-radius: var(--bk-radius-lg, 12px);
  padding: 32px 16px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.16s ease, background-color 0.16s ease;
}

.csv-drop:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-fill-color-light);
}

.csv-drop.is-busy {
  pointer-events: none;
  opacity: 0.7;
}

.csv-drop__icon {
  font-size: 40px;
  color: var(--el-color-primary-light-3);
}

.csv-drop__title {
  font-size: 15px;
  font-weight: 600;
  margin-top: 8px;
}

.csv-drop__tip {
  font-size: 13px;
  color: var(--bk-text-secondary, #909399);
  margin-top: 6px;
}

.csv-drop__loading {
  margin-top: 10px;
  font-size: 13px;
  color: var(--el-color-primary);
}

.csv-file {
  display: none;
}

.csv-alert {
  margin-top: 14px;
}

.csv-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.csv-summary__file {
  font-weight: 600;
  margin-right: 4px;
  width: 100%;
  overflow-wrap: anywhere;
}

.csv-accounts {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--bk-radius-lg, 12px);
  background: var(--bk-surface-2);
}

.csv-accounts__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
}

.csv-accounts__title {
  font-weight: 600;
}

.csv-accounts__bulk {
  width: min(100%, 240px);
}

.csv-accounts__tip {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--bk-text-secondary, #909399);
}

.csv-accounts__list {
  max-height: 148px;
  margin-top: 10px;
  overflow: auto;
}

.csv-account-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.csv-account-row__name {
  width: 180px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.csv-account-row__select {
  flex: 1;
  min-width: 0;
}

.csv-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: var(--bk-gap);
}

.csv-table {
  width: 100%;
}

.csv-note {
  margin-top: 8px;
  font-size: 13px;
  color: var(--bk-text-secondary, #909399);
}

.csv-failures {
  margin-top: 8px;
}

.csv-failures__title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 6px;
}

.csv-failures__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.csv-failures__list li {
  display: flex;
  gap: 10px;
  font-size: 13px;
  padding: 3px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.csv-failures__line {
  flex-shrink: 0;
  width: 60px;
  color: var(--bk-text-secondary, #909399);
}

.csv-failures__reason {
  color: var(--bk-expense-text);
  overflow-wrap: anywhere;
}

.csv-footer { gap: var(--bk-action-gap); }
</style>
