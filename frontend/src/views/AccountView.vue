<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import { accountApi, transactionApi, ApiError } from '@/api'
import { useSettingsStore } from '@/stores/settings'
import { useDictStore } from '@/stores/dict'
import type { Account, AccountType, ArchivedFlag, CurrencyCode } from '@/types/model'
import { formatAmount, sanitizeAmountInput } from '@/utils/format'
import { ACCOUNT_TYPE_OPTIONS, ACCOUNT_TYPE_COLOR, ARCHIVED_LABEL, CURRENCY_OPTIONS, CURRENCY_SYMBOL } from '@/utils/constants'
import { bus, ACCOUNT_CHANGED, TRANSACTION_CHANGED } from '@/utils/bus'
import AccountTypeIcon from '@/components/AccountTypeIcon.vue'
import EmptyState from '@/components/EmptyState.vue'

/** 账户管理页（需求文档 4.1）：卡片列表 + 新增/编辑/删除/归档 + 本地排序 */
const settings = useSettingsStore()
const dict = useDictStore()
const decimals = computed(() => settings.decimalPlaces)

const loading = ref(false)
const accounts = ref<Account[]>([])
const showArchived = ref(false)
const sortKey = ref<'default' | 'name' | 'balance' | 'createTime'>('default')

const dialogVisible = ref(false)
const editing = ref<Account | null>(null)
const saving = ref(false)
/** 对账进行中（重算全部账户余额） */
const reconciling = ref(false)
const form = ref({
  name: '',
  type: 'cash' as AccountType,
  initialBalance: '0',
  currency: 'CNY' as CurrencyCode,
  archived: 0 as ArchivedFlag
})

async function load() {
  loading.value = true
  try {
    accounts.value = await accountApi.selectAll()
    // 同步字典缓存：记账弹窗 / 流水筛选的账户下拉读的是 dict.activeAccounts，
    // 不同步会导致新建/归档/删除账户后下拉选项陈旧（选不到新账户）
    dict.accounts = accounts.value
  } finally {
    loading.value = false
  }
}

const visibleAccounts = computed(() => {
  let list = accounts.value.filter((a) => showArchived.value || a.archived === 0)
  if (sortKey.value === 'name') {
    list = [...list].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  } else if (sortKey.value === 'balance') {
    list = [...list].sort((a, b) => Number(b.currentBalance) - Number(a.currentBalance))
  }
  return list
})

/** 按币种汇总总资产（不做汇率换算） */
const totalByCurrency = computed(() => {
  const map = new Map<CurrencyCode, number>()
  for (const a of accounts.value) {
    if (a.archived !== 0) continue
    const c = a.currency || 'CNY'
    map.set(c, (map.get(c) ?? 0) + Number(a.currentBalance ?? 0))
  }
  return [...map.entries()]
})

function openCreate() {
  editing.value = null
  form.value = { name: '', type: 'cash', initialBalance: '0', currency: 'CNY', archived: 0 }
  dialogVisible.value = true
}

function openEdit(account: Account) {
  editing.value = account
  form.value = {
    name: account.name,
    type: account.type,
    initialBalance: String(account.initialBalance ?? '0'),
    currency: account.currency,
    archived: account.archived
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入账户名称')
    return
  }
  if (form.value.name.trim().length > 20) {
    ElMessage.warning('账户名称不能超过 20 个字符')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      type: form.value.type,
      initialBalance: Number(form.value.initialBalance || 0),
      currentBalance: editing.value ? editing.value.currentBalance : Number(form.value.initialBalance || 0),
      currency: form.value.currency,
      archived: form.value.archived
    }
    if (editing.value) {
      await accountApi.update({ ...payload, id: editing.value.id })
      ElMessage.success('修改成功')
    } else {
      await accountApi.save(payload)
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    bus.emit(ACCOUNT_CHANGED)
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'S0809') {
      ElMessage.info('请检查填写内容后重试')
    }
  } finally {
    saving.value = false
  }
}

async function toggleArchive(account: Account) {
  const toArchived: ArchivedFlag = account.archived === 0 ? 1 : 0
  await accountApi.update({ ...account, archived: toArchived })
  ElMessage.success(toArchived === 1 ? '已归档' : '已取消归档')
  bus.emit(ACCOUNT_CHANGED)
  await load()
}

async function remove(account: Account) {
  try {
    await ElMessageBox.confirm(
      `删除账户「${account.name}」后，其历史流水将无法关联账户，确定删除吗？`,
      '删除账户',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    await accountApi.remove(account.id)
    ElMessage.success('删除成功')
    bus.emit(ACCOUNT_CHANGED)
    await load()
  } catch (e) {
    // 外键约束：存在流水的账户删除失败（S0813），给出引导
    if (e instanceof ApiError && e.code === 'S0813') {
      ElMessage.info('该账户下存在交易记录，无法直接删除；可先将账户归档隐藏')
    }
  }
}

/** 账户对账（NEW-07）：以各账户期初余额为基准重放全部流水，重算当前余额，修复历史漂移 */
async function reconcile() {
  try {
    await ElMessageBox.confirm(
      '对账将以各账户期初余额为基准，按时间顺序重放全部流水，重算当前余额（用于修复历史漂移）。确定继续吗？',
      '账户对账',
      { type: 'warning', confirmButtonText: '开始对账', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  reconciling.value = true
  try {
    const count = await transactionApi.reconcile()
    ElMessage.success(`对账完成，已重放 ${count} 条流水`)
    bus.emit(ACCOUNT_CHANGED)
    await load()
  } catch {
    // 失败提示由拦截器统一处理
  } finally {
    reconciling.value = false
  }
}

function onAccountsChanged() {
  load()
}

onMounted(() => {
  load()
  bus.on(ACCOUNT_CHANGED, onAccountsChanged)
  bus.on(TRANSACTION_CHANGED, onAccountsChanged)
})

onBeforeUnmount(() => {
  bus.off(ACCOUNT_CHANGED, onAccountsChanged)
  bus.off(TRANSACTION_CHANGED, onAccountsChanged)
})
</script>

<template>
  <div class="page page--comfortable quiet-controls" v-loading="loading">
    <!-- 页头叙事（总资产）+ 共享工具条 -->
    <header class="page-head page-head--actions">
      <div class="row-copy">
        <h1 class="page-head__title">账户</h1>
        <p class="page-head__sub">管理你的钱包与银行卡</p>
      </div>
      <div class="toolbar page-head__actions">
        <el-button text :loading="reconciling" data-guide="account-reconcile" @click="reconcile">对账</el-button>
        <el-button type="primary" data-guide="account-create" @click="openCreate">+ 新增账户</el-button>
      </div>
    </header>
    <div class="surface bk-enter">
      <div class="split-row">
        <div class="row-copy">
          <div class="summary__label">总资产（{{ totalByCurrency.length > 1 ? '按币种' : CURRENCY_SYMBOL[totalByCurrency[0]?.[0] ?? 'CNY'] }}）</div>
          <div class="summary__value">
            <template v-if="totalByCurrency.length">
              <span v-for="([currency, total], i) in totalByCurrency" :key="currency" class="summary__amount">
                {{ i > 0 ? ' + ' : '' }}{{ CURRENCY_SYMBOL[currency] }}{{ formatAmount(total, decimals) }}
              </span>
            </template>
            <template v-else>¥0.00</template>
          </div>
        </div>
        <p class="card-hint">共 {{ accounts.filter((a) => a.archived === 0).length }} 个启用账户</p>
      </div>
    </div>
    <section class="page-section" aria-labelledby="accounts-heading">
      <div class="toolbar page-section__head">
        <h2 id="accounts-heading" class="section-heading">我的账户</h2>
        <div class="toolbar">
          <el-radio-group v-model="sortKey" class="segment" aria-label="账户排序" size="small" data-guide="account-sort">
            <el-radio-button value="default">默认</el-radio-button>
            <el-radio-button value="name">按名称</el-radio-button>
            <el-radio-button value="balance">按余额</el-radio-button>
          </el-radio-group>
          <el-checkbox v-model="showArchived" size="small">显示已归档</el-checkbox>
        </div>
      </div>

      <!-- 账户卡片网格 -->
      <div v-if="visibleAccounts.length" class="account-grid" data-guide="account-list">
        <el-card
          v-for="account in visibleAccounts"
          :key="account.id"
          shadow="hover"
          class="account-card"
          :class="{ 'is-archived': account.archived === 1 }"
          :style="{ '--acc': ACCOUNT_TYPE_COLOR[account.type] }"
        >
          <div class="account-card__header">
            <AccountTypeIcon :type="account.type" />
            <div class="account-card__meta">
              <div class="account-card__name">
                {{ account.name }}
                <el-tag v-if="account.archived === 1" size="small" type="info">{{ ARCHIVED_LABEL[account.archived] }}</el-tag>
              </div>
              <div class="account-card__sub">
                {{ ACCOUNT_TYPE_OPTIONS.find((o) => o.value === account.type)?.label }} · 初始余额
                {{ CURRENCY_SYMBOL[account.currency] }}{{ formatAmount(account.initialBalance, decimals) }}
              </div>
            </div>
            <el-dropdown trigger="click" @command="(cmd: string) => cmd === 'edit' ? openEdit(account) : cmd === 'archive' ? toggleArchive(account) : remove(account)">
              <el-button text circle :aria-label="`${account.name}的更多操作`">
                <el-icon><MoreFilled /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item command="archive">
                    {{ account.archived === 0 ? '归档' : '取消归档' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" class="danger-item">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div class="account-card__balance">
            {{ CURRENCY_SYMBOL[account.currency] }}
            <span class="amount-strong">{{ formatAmount(account.currentBalance, decimals) }}</span>
            <el-tag size="small" effect="plain" class="account-card__currency">{{ account.currency }}</el-tag>
          </div>
        </el-card>
      </div>

      <div v-else class="panel">
        <EmptyState description="还没有账户，创建一个开始记账吧">
          <el-button type="primary" @click="openCreate">创建第一个账户</el-button>
        </EmptyState>
      </div>
    </section>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑账户' : '新增账户'"
      width="460px"
      class="bk-dialog quiet-controls"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="账户名称" required>
          <el-input v-model="form.name" maxlength="20" show-word-limit placeholder="如：微信零钱、招商银行" />
        </el-form-item>
        <el-form-item label="账户类型" required>
          <!-- 不用 el-radio-button：图标+文字较宽时换行后边框互相压叠，改用自适应宫格 -->
          <div class="choice-grid" role="group" aria-label="账户类型">
            <button
              type="button"
              v-for="t in ACCOUNT_TYPE_OPTIONS"
              :key="t.value"
              class="choice-tile"
              :class="{ 'is-active': form.type === t.value }"
              :aria-pressed="form.type === t.value"
              @click="form.type = t.value"
            >
              <AccountTypeIcon :type="t.value" :size="20" />
              <span>{{ t.label }}</span>
            </button>
          </div>
        </el-form-item>
        <el-form-item label="初始余额" required>
          <el-input v-model="form.initialBalance" placeholder="0.00" @input="form.initialBalance = sanitizeAmountInput(form.initialBalance)">
            <template #prepend>¥</template>
          </el-input>
        </el-form-item>
        <el-form-item label="币种" required>
          <el-select v-model="form.currency" style="width: 100%">
            <el-option v-for="c in CURRENCY_OPTIONS" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editing" label="归档状态">
          <el-switch v-model="form.archived" :active-value="1" :inactive-value="0" active-text="已归档" inactive-text="正常" />
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
.summary__label {
  color: var(--bk-text-secondary);
  font-size: 13px;
}

.summary__value {
  overflow-wrap: anywhere;
  font-size: var(--bk-font-display, 32px);
  font-weight: 650;
  margin-top: 8px;
  letter-spacing: -0.5px;
}

.summary__amount {
  font-variant-numeric: tabular-nums;
}

.account-grid > * {
  min-width: 0;
}

.account-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 300px), 1fr));
  gap: var(--bk-gap);
}

/* 钱包风软卡：左侧类型强调色竖条 + 柔和暖阴影 */
.account-card {
  position: relative;
  overflow: hidden;
  border-radius: var(--bk-radius-lg);
}

.account-card::before {
  content: '';
  position: absolute;
  left: 24px;
  top: 0;
  height: 3px;
  width: 32px;
  border-radius: 0 0 3px 3px;
  opacity: 0.65;
  background: var(--acc, var(--bk-primary));
}

.account-card.is-archived {
  background: color-mix(in srgb, var(--bk-surface) 65%, var(--bk-surface-2));
}

.account-card__header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.account-card__meta {
  flex: 1;
  min-width: 0;
}

.account-card__name {
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.account-card__sub {
  color: var(--bk-text-secondary);
  font-size: 12px;
  margin-top: 5px;
  overflow-wrap: anywhere;
}

.account-card__balance {
  margin-top: 22px;
  font-size: 14px;
  color: var(--bk-text-secondary);
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px;
}

.account-card__balance .amount-strong {
  font-size: 26px;
  font-weight: 650;
  overflow-wrap: anywhere;
  min-width: 0;
  color: var(--bk-text);
  font-variant-numeric: tabular-nums;
}

.account-card__currency {
  margin-left: auto;
}

.danger-item {
  color: var(--el-color-danger);
}

/* 账户类型选择宫格：2 列自适应，避免段状单选换行压叠 */
.choice-grid .choice-tile { min-height: 46px; }
</style>
