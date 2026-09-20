<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { MonthlySnapshot } from '@/types/monthlyReport'
import { currencyName, simulate } from '@/utils/monthlyReport'
const props = defineProps<{ snapshot: MonthlySnapshot }>()
const selected = ref('')
const input = ref('')
const mode = ref<'count' | 'percent'>('count')
const confirmed = ref(false)
const options = computed(() => props.snapshot.currencies.flatMap(c => c.categories.filter(v => v.count > 0 && Number(v.amount) > 0).map(v => ({ ...v, currency: c.currency, key: `${c.currency}:${v.categoryId}` }))))
const category = computed(() => options.value.find(c => c.key === selected.value))
const result = computed(() => category.value && confirmed.value ? simulate(category.value.amount, category.value.count, mode.value, input.value) : null)
watch([selected, mode], () => { input.value = ''; confirmed.value = false })
watch(() => props.snapshot, () => { selected.value = ''; input.value = ''; confirmed.value = false })
</script>
<template>
  <section><h2>可选目标 · 本地试算</h2><p>仅当你确认某类消费可调整时试算。不会保存预算或改动账单。</p>
    <div class="toolbar">
      <el-select v-model="selected" placeholder="选择分类与币种" style="width: 240px" aria-label="试算分类"><el-option v-for="c in options" :key="c.key" :value="c.key" :label="`${c.name} · ${currencyName(c.currency)}`" /></el-select>
      <el-select v-model="mode" style="width: 150px" aria-label="试算方式"><el-option label="减少记账次数" value="count" /><el-option label="减少支出比例" value="percent" /></el-select>
      <el-input v-model="input" style="width: 150px" :placeholder="mode === 'count' ? '减少次数（整数）' : '减少百分比（1–100）'" maxlength="9" aria-label="试算目标" />
    </div>
    <p v-if="category" class="muted">依据：{{ category.amount }} / {{ category.count }} 笔，平均单笔 {{ category.average }}（{{ currencyName(category.currency) }}）。一笔流水不等同一件商品。</p>
    <el-checkbox v-model="confirmed">我确认该分类有可调整部分，仅作情景参考</el-checkbox>
    <p v-if="result !== null && result !== undefined" class="result">该情景对应约 {{ result }} {{ currencyName(category!.currency) }}；不代表必然节省，必要消费无需削减。</p>
    <p v-else-if="confirmed && input" class="muted">请输入有效整数，减少次数不得超过本月笔数。</p>
  </section>
</template>
<style scoped>
h2 { font-size: 18px; } p { line-height: 1.7; }
.muted { color: var(--bk-text-secondary); font-size: 13px; }
.result { background: var(--bk-primary-soft); padding: 16px; border-radius: 12px; }
</style>
