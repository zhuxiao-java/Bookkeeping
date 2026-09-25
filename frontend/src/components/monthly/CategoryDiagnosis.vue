<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import type { CurrencySummary, ReportType } from '@/types/monthlyReport'
import { previousLabels, historyLabels } from '@/utils/reportPeriod'
import { comparison } from '@/utils/monthlyReport'
withDefaults(defineProps<{ summary: CurrencySummary; periodType?: ReportType }>(), { periodType: 'month' })
const emit = defineEmits<{ drill: [currency: string, categoryId: number] }>()
const router = useRouter()
const all = ref(false)
</script>
<template>
  <section>
    <h2>钱花在哪里 · {{ all ? '全部分类' : 'Top 5' }}</h2>
    <p class="muted">一级分类不重复累加子分类。高占比仅表示主要开支，不代表浪费。</p>
    <el-empty v-if="!summary.categories.length" description="本期没有消费记录，不生成削减建议" :image-size="72" />
    <el-collapse v-else>
      <el-collapse-item v-for="c in all ? summary.categories : summary.categories.slice(0, 5)" :key="c.categoryId" :name="c.categoryId">
        <template #title><div class="category-title"><span>{{ c.name }}</span><strong>{{ c.amount }}</strong><span>{{ c.share ?? '0' }}% · {{ c.count }} 笔</span></div></template>
        <p>平均单笔 {{ c.average }} · {{ comparison(c.amount, c.previousAmount, c.growth, periodType) }}</p>
        <p>{{ previousLabels[periodType] }} {{ c.previousAmount ?? '无记录' }} / {{ c.previousCount ?? '—' }} 笔 / 平均 {{ c.previousAverage ?? '—' }}<template v-if="periodType !== 'year'"> · {{ historyLabels[periodType] }}均值 {{ c.historyAverage ?? '历史不足' }}</template></p>
        <el-button size="small" @click="emit('drill', summary.currency, c.categoryId)">查看对应流水</el-button>
        <ul><li v-for="child in c.children" :key="child.categoryId">{{ child.name }}：{{ child.amount }} · {{ child.count }} 笔（直接记在该分类）</li></ul>
        <template v-if="c.largeExpenses.length"><h3>大额记录贡献（最多三笔）</h3><p class="muted">每笔至少占该一级分类的 20%；请核对是否为一次性或计划内开支。</p>
          <div v-for="tx in c.largeExpenses" :key="tx.id" class="large"><span>{{ tx.date.replace('T', ' ') }} · {{ tx.amount }}</span><el-button size="small" text @click="router.push({ path: '/transaction', query: { bizId: tx.id } })">查看流水</el-button></div>
        </template>
      </el-collapse-item>
    </el-collapse>
    <el-button v-if="summary.categories.length > 5" text @click="all = !all">{{ all ? '收起' : '查看所有分类及变化' }}</el-button>
  </section>
</template>
<style scoped>
h2 { font-size: 18px; } h3 { font-size: 14px; margin-top: 18px; }
.muted { color: var(--bk-text-secondary); font-size: 13px; }
.category-title { display: flex; gap: 18px; flex: 1; flex-wrap: wrap; padding: 8px 16px 8px 0; line-height: 1.5; }
.category-title span:first-child { flex: 1; min-width: 80px; overflow-wrap: anywhere; }
.category-title strong { font-variant-numeric: tabular-nums; }
.large { display: flex; justify-content: space-between; gap: 10px; }
li { margin: 6px 0; }
</style>
