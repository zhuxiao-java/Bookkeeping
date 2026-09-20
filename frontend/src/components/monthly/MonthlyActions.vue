<script setup lang="ts">
import { computed } from 'vue'
import type { MonthlyFact } from '@/types/monthlyReport'
import { currencyName, factEvidence } from '@/utils/monthlyReport'
const props = defineProps<{ facts: MonthlyFact[] }>()
const emit = defineEmits<{ drill: [currency: string, categoryId: number | null] }>()
const actions = computed(() => props.facts.filter(f => f.kind !== 'overview').filter((f, i, items) => items.findIndex(x => x.currency === f.currency && x.categoryId === f.categoryId) === i).slice(0, 3))
</script>
<template>
  <section><h2>下月行动建议 · 本地规则</h2>
    <p v-if="!actions.length">没有足够消费依据，不生成削减建议。可先检查记录是否完整。</p>
    <article v-for="(fact, index) in actions" :key="fact.id">
      <h3>{{ index + 1 }}. {{ fact.title }} · {{ currencyName(fact.currency) }}</h3>
      <p class="evidence">{{ factEvidence(fact) }}</p><p>{{ fact.suggestion }}</p>
      <el-button size="small" text @click="emit('drill', fact.currency, fact.categoryId)">查看对应流水</el-button>
      <span class="muted">可选目标：在下方自行试算，不自动修改预算。</span>
    </article>
  </section>
</template>
<style scoped>
h2 { font-size: 18px; } h3 { font-size: 15px; } p { line-height: 1.8; }
article { border-top: 1px solid var(--bk-border-light); padding: 10px 0; }
.evidence, .muted { color: var(--bk-text-secondary); font-size: 13px; }
</style>
