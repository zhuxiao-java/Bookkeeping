<script setup lang="ts">
import type { Account } from '@/types/model'
import { accountTypeLabel } from '@/utils/constants'
import AccountTypeIcon from '@/components/AccountTypeIcon.vue'

/**
 * 账户展示单元：品牌 logo + 账户名（+ 可选的类型说明）。
 * 用于 el-option 默认插槽（el-option 的 label 属性仍需保留，决定选中后输入框的展示文本），
 * 也用于流水表格的账户列（此时 showType 传 false）。
 */
withDefaults(defineProps<{ account: Account; showType?: boolean }>(), { showType: true })
</script>

<template>
  <span class="acc-option">
    <AccountTypeIcon :type="account.type" :size="20" />
    <span class="acc-option__name">{{ account.name }}</span>
    <span v-if="showType" class="acc-option__type">{{ accountTypeLabel(account.type) }}</span>
  </span>
</template>

<style scoped>
.acc-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.acc-option__type {
  margin-left: auto;
  padding-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
