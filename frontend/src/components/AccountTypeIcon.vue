<script setup lang="ts">
import { computed } from 'vue'
import { CreditCard, Wallet } from '@element-plus/icons-vue'
import type { AccountType } from '@/types/model'
import { ACCOUNT_TYPE_COLOR } from '@/utils/constants'
import AliPayLogo from '@/components/icons/AliPayLogo.vue'
import WechatPayLogo from '@/components/icons/WechatPayLogo.vue'

/**
 * 账户类型图标：
 * - 支付宝 / 微信支付使用官方品牌 logo（inline SVG，反白置于品牌色圆底）
 * - 现金 / 银行卡沿用 Element Plus 图标
 * 底色取自 ACCOUNT_TYPE_COLOR，便于与其他位置（类型选择器等）保持一致。
 */
const props = defineProps<{ type: AccountType; size?: number }>()

const px = computed(() => props.size ?? 40)

/** 支付类账户对应的品牌 logo 组件；非支付类返回 null，走 Element Plus 图标兜底 */
const brandLogo = computed(() => {
  switch (props.type) {
    case 'ali_pay':
      return AliPayLogo
    case 'wechat_pay':
      return WechatPayLogo
    default:
      return null
  }
})

const fallbackIcon = computed(() => (props.type === 'bank' ? CreditCard : Wallet))
</script>

<template>
  <span
    class="acc-icon"
    :style="{ width: `${px}px`, height: `${px}px`, background: ACCOUNT_TYPE_COLOR[type] }"
  >
    <!-- 品牌 logo 为实心图形，占比略小于线性图标以保持视觉重量一致 -->
    <component :is="brandLogo" v-if="brandLogo" :size="Math.round(px * 0.62)" />
    <el-icon v-else :size="Math.round(px * 0.55)"><component :is="fallbackIcon" /></el-icon>
  </span>
</template>

<style scoped>
.acc-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  flex-shrink: 0;
}

.acc-icon svg {
  display: block;
}
</style>
