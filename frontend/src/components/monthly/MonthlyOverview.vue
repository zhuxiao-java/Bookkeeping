<script setup lang="ts">
import { computed, nextTick, onMounted, watch } from 'vue'
import { useChart } from '@/composables/useChart'
import type { CurrencySummary } from '@/types/monthlyReport'
import { comparison, currencyName } from '@/utils/monthlyReport'
const props = withDefaults(defineProps<{ summary: CurrencySummary; compact?: boolean }>(), { compact: false })
const { elRef, render } = useChart()
const cards = computed(() => [
  ['收入', props.summary.income], ['消费支出', props.summary.expense],
  ['转账手续费', props.summary.fees], ['结余', props.summary.balance]
])
async function draw() {
  if (props.compact) return
  await nextTick()
  const categories = props.summary.categories.filter(c => Number(c.amount) > 0).slice(0, 5)
  render({ tooltip: { trigger: 'item', renderMode: 'richText' }, grid: { left: 12, right: 20, top: 10, bottom: 8, containLabel: true },
    xAxis: { type: 'value' }, yAxis: { type: 'category', inverse: true, data: categories.map(c => c.name), axisLabel: { width: 120, overflow: 'truncate' } },
    series: [{ type: 'bar', data: categories.map(c => Number(c.amount)), barMaxWidth: 22, itemStyle: { borderRadius: [0, 5, 5, 0] } }]
  })
}
onMounted(draw)
watch(() => props.summary, draw)
</script>
<template>
  <section>
    <div class="split-row"><h2>{{ currencyName(summary.currency) }} · 本月概览</h2><span>{{ summary.count }} 笔记账</span></div>
    <div class="metrics"><div v-for="[label, value] in cards" :key="label" class="metric"><span>{{ label }}</span><strong>{{ value }}</strong></div></div>
    <p class="muted">{{ comparison(summary.expense, summary.previousExpense, summary.growth) }} · 前三个自然月消费均值：{{ summary.historyAverage ?? '历史不足' }}（仅已记录账单）</p>
    <div v-if="!compact" v-show="Number(summary.expense) > 0" ref="elRef" class="chart" aria-label="一级分类支出 Top 5" />
  </section>
</template>
<style scoped>
h2 { font-size: 18px; }
.metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.metric { padding: 18px 12px; background: var(--bk-surface-2); border-radius: var(--bk-radius-md); }
.metric span { font-size: 13px; color: var(--bk-text-secondary); }
.metric strong { display: block; margin-top: 10px; font-size: 23px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.muted { color: var(--bk-text-secondary); font-size: 13px; line-height: 1.7; }
.chart { height: 220px; }
@media (max-width: 1100px) { .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
