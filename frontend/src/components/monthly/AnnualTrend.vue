<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { TrendMonth } from '@/types/monthlyReport'
import { useChart } from '@/composables/useChart'
import { useSettingsStore } from '@/stores/settings'
import { currencyName } from '@/utils/monthlyReport'
import { chartPalette, categoryAxisStyle, valueAxisStyle } from '@/utils/chartTheme'

const props = defineProps<{ rows: TrendMonth[] }>()
const settings = useSettingsStore()
const currency = ref(props.rows[0]?.currency ?? 'CNY')
const currencies = computed(() => [...new Set(props.rows.map(row => row.currency))])
const months = computed(() => props.rows.filter(row => row.currency === currency.value))
const { elRef, render } = useChart()
function draw() {
  const palette = chartPalette()
  render({
    color: [palette.income, palette.expense, palette.transfer],
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入', '支出', '结余'] },
    grid: { left: 16, right: 16, bottom: 16, top: 48, containLabel: true },
    xAxis: { type: 'category', data: months.value.map(row => `${Number(row.month.slice(5))} 月`), ...categoryAxisStyle(palette) },
    yAxis: { type: 'value', ...valueAxisStyle(palette) },
    series: (['income', 'expense', 'balance'] as const).map((key, i) => ({
      name: ['收入', '支出', '结余'][i], type: 'line', connectNulls: false,
      data: months.value.map(row => row.count ? Number(row[key]) : null)
    }))
  })
}
watch(currencies, values => { if (!values.includes(currency.value)) currency.value = values[0] ?? 'CNY' })
watch([months, () => settings.isDark], draw, { flush: 'post' })
onMounted(draw)
</script>

<template>
  <section class="surface annual-trend">
    <header><h2>全年收支趋势</h2><el-select v-model="currency" aria-label="年度趋势币种"><el-option v-for="value in currencies" :key="value" :value="value" :label="currencyName(value)" /></el-select></header>
    <p class="muted">各币种独立统计；无记录月份显示断点，不代表实际零消费。结余已扣除手续费。</p>
    <div ref="elRef" class="chart" role="img" :aria-label="`${currencyName(currency)}全年收支趋势，详细金额见下方表格`" />
    <details><summary>查看十二个月明细 · {{ currencyName(currency) }}</summary>
      <div class="table-scroll"><table><thead><tr><th>月份</th><th>收入</th><th>支出</th><th>手续费</th><th>结余</th><th>流水</th></tr></thead><tbody><tr v-for="row in months" :key="row.month"><td>{{ row.month }}</td><template v-if="row.count"><td>{{ row.income }}</td><td>{{ row.expense }}</td><td>{{ row.fees }}</td><td>{{ row.balance }}</td><td>{{ row.count }} 笔</td></template><td v-else colspan="5" class="muted">无记录</td></tr></tbody></table></div>
    </details>
  </section>
</template>

<style scoped>
.annual-trend { padding: 24px; min-width: 0; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
h2 { font-size: 19px; margin: 0; } .el-select { width: 150px; }
.muted { color: var(--bk-text-secondary); font-size: 13px; line-height: 1.7; }
.chart { width: 100%; height: 280px; } summary { cursor: pointer; font-size: 13px; }
.table-scroll { overflow-x: auto; } table { width: 100%; border-collapse: collapse; font-size: 13px; font-variant-numeric: tabular-nums; }
th, td { padding: 12px 8px; text-align: right; white-space: nowrap; border-bottom: 1px solid var(--bk-border); } th:first-child, td:first-child { text-align: left; }
@media (max-width: 640px) { .annual-trend { padding: 16px; } .chart { height: 240px; } }
</style>
