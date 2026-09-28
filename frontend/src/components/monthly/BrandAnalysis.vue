<script setup lang="ts">
import { computed } from 'vue'
import type { CurrencySummary, ReportType } from '@/types/monthlyReport'
import { currencyName } from '@/utils/monthlyReport'
import { currentLabels } from '@/utils/reportPeriod'

const props = withDefaults(defineProps<{ summary: CurrencySummary; periodType?: ReportType }>(), { periodType: 'month' })
// 仅展示有金额的品牌；柱宽以本期最大品牌金额为基准
const brands = computed(() => props.summary.brands.filter(b => Number(b.amount) > 0))
const max = computed(() => brands.value.reduce((m, b) => Math.max(m, Number(b.amount)), 0))
function width(amount: string): string {
  return max.value > 0 ? `${Math.max(4, (Number(amount) / max.value) * 100).toFixed(1)}%` : '0%'
}
</script>
<template>
  <section v-if="brands.length" class="brand-panel">
    <div class="split-row">
      <h2>{{ currencyName(summary.currency) }} · {{ currentLabels[periodType] }}品牌消费</h2>
      <span>{{ brands.length }} 个品牌</span>
    </div>
    <p class="muted">仅统计打了品牌标签的流水；一笔可同时计入多个品牌，占比相对本币种总支出，不代表品牌全部消费。</p>
    <ul class="brand-list">
      <li v-for="b in brands" :key="b.tagId" class="brand-row">
        <span class="brand-name">{{ b.name }}</span>
        <div class="brand-bar"><i :style="{ width: width(b.amount) }" /></div>
        <strong class="brand-amount">{{ b.amount }}</strong>
        <span class="brand-meta">{{ b.share ?? '0' }}% · {{ b.count }} 笔</span>
      </li>
    </ul>
  </section>
</template>
<style scoped>
.brand-panel { padding: 20px 24px; }
h2 { font-size: 18px; margin: 0; }
.split-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.split-row > span { font-size: 13px; color: var(--bk-text-secondary); }
.muted { color: var(--bk-text-secondary); font-size: 13px; line-height: 1.7; margin: 0 0 12px; }
.brand-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.brand-row { display: grid; grid-template-columns: minmax(72px, 108px) minmax(0, 1fr) auto minmax(96px, auto); align-items: center; gap: 12px; }
.brand-name { overflow-wrap: anywhere; }
.brand-bar { height: 8px; border-radius: 6px; background: var(--bk-surface-2); overflow: hidden; }
.brand-bar i { display: block; height: 100%; border-radius: 6px; background: var(--bk-primary); }
.brand-amount { font-variant-numeric: tabular-nums; font-weight: 600; }
.brand-meta { font-size: 12px; color: var(--bk-text-secondary); text-align: right; }
@media (max-width: 640px) {
  .brand-row { grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: 'name amount' 'bar meta'; }
  .brand-name { grid-area: name; } .brand-amount { grid-area: amount; text-align: right; }
  .brand-bar { grid-area: bar; } .brand-meta { grid-area: meta; }
}
</style>
