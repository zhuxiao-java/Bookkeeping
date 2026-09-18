<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { useCheckInStore, todayStr } from '@/stores/checkin'
import CheckInCalendar from './CheckInCalendar.vue'
import EmptyState from './EmptyState.vue'

/** 等级页签到专区（候选 B）：月历热力 + 三项统计 + 最近签到明细 */
const checkin = useCheckInStore()

const now = new Date()
const year = ref(now.getFullYear())
const month = ref(now.getMonth() + 1)

/** 不允许翻到未来月 */
const atCurrentMonth = computed(() => year.value === now.getFullYear() && month.value === now.getMonth() + 1)

function prevMonth() {
  if (month.value === 1) {
    year.value -= 1
    month.value = 12
  } else {
    month.value -= 1
  }
}

function nextMonth() {
  if (atCurrentMonth.value) return
  if (month.value === 12) {
    year.value += 1
    month.value = 1
  } else {
    month.value += 1
  }
}

/** 最近签到明细（最多 7 条） */
const recent = computed(() => checkin.sortedDesc.slice(0, 7))

function rowLabel(date: string): string {
  const md = date.slice(5).replace('-', '-')
  return date === todayStr() ? `${md} 今天` : md
}
</script>

<template>
  <el-card v-if="checkin.available" shadow="never" class="checkin-panel">
    <div class="ci__head">
      <span class="ci__title">签到日历</span>
      <span class="ci__nav">
        <el-button text circle :icon="ArrowLeft" aria-label="上个月" @click="prevMonth" />
        <span class="ci__month">{{ year }} 年 {{ month }} 月</span>
        <el-button text circle :icon="ArrowRight" aria-label="下个月" :disabled="atCurrentMonth" @click="nextMonth" />
      </span>
      <div class="ci__stats">
        <div>
          <div class="ci__statv">{{ checkin.totalDays }}</div>
          <div class="ci__statk">累计签到（天）</div>
        </div>
        <div>
          <div class="ci__statv is-fire">{{ checkin.currentStreak }}</div>
          <div class="ci__statk">当前连续（天）</div>
        </div>
        <div>
          <div class="ci__statv is-exp">{{ checkin.totalExp }}</div>
          <div class="ci__statk">签到经验（累计）</div>
        </div>
      </div>
    </div>

    <div class="ci__body">
      <div class="ci__cal">
        <CheckInCalendar :year="year" :month="month" :checked="checkin.checkedDates" />
      </div>
      <div class="ci__list">
        <div class="ci__listhead">最近签到</div>
        <template v-if="recent.length">
          <div v-for="r in recent" :key="r.id" class="ci__row">
            <span class="ci__date">{{ rowLabel(r.checkDate) }}</span>
            <span class="ci__streak">连续 <b>{{ r.streakDays }}</b> 天</span>
            <span class="ci__exp">
              +{{ r.expReward }}<small>{{ r.baseExp }} + {{ r.bonusExp }}</small>
            </span>
          </div>
        </template>
        <EmptyState v-else description="暂无签到记录" :size="80" />
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.checkin-panel {
  container: checkin-panel / inline-size;
}

.ci__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 24px;
}

.ci__title {
  font-size: 15px;
  font-weight: 600;
}

.ci__nav {
  display: flex;
  align-items: center;
  gap: var(--bk-action-gap);
  margin-left: 0;
}

.ci__month {
  font-size: 13px;
  color: var(--el-text-color-regular);
  min-width: 86px;
  text-align: center;
}

.ci__stats {
  margin-left: auto;
  display: flex;
  gap: 26px;
  text-align: right;
}

.ci__statv {
  font-size: 16px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.ci__statv.is-fire {
  color: var(--bk-button-primary);
}

.ci__statv.is-exp {
  color: var(--bk-income-text);
}

.ci__statk {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-top: 3px;
}

.ci__body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: var(--bk-gap-lg);
}

.ci__cal {
  min-width: 0;
}

.ci__list {
  flex: 1;
  min-width: 0;
}

.ci__listhead {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.ci__row {
  display: flex;
  align-items: center;
  padding: var(--bk-row-padding) 0;
  gap: 8px;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
}

.ci__row:last-child {
  border-bottom: none;
}

.ci__date {
  width: 92px;
  color: var(--el-text-color-regular);
  font-variant-numeric: tabular-nums;
}

.ci__streak {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ci__streak b {
  color: var(--bk-button-primary);
}

.ci__exp {
  margin-left: auto;
  font-weight: 600;
  color: var(--bk-income-text);
  font-variant-numeric: tabular-nums;
}

.ci__exp small {
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
@container checkin-panel (max-width: 760px) {
  .ci__body { grid-template-columns: 1fr; }
  .ci__stats { width: 100%; margin-left: 0; text-align: left; flex-wrap: wrap; }
  .ci__nav { margin-left: auto; }
}
</style>
