<script setup lang="ts">
import { computed } from 'vue'
import { useCheckInStore, toDateStr } from '@/stores/checkin'

/** 总览页签到卡片（候选 A）：连续天数 + 今日状态 + 近 7 日打点，点击进等级页 */
const checkin = useCheckInStore()

const today = computed(() => checkin.todayRecord)

const WEEK_LABEL = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 近 7 日（含今天）打点 */
const week = computed(() => {
  const checked = checkin.checkedDates
  const out: { key: string; label: string; day: number; checked: boolean; isToday: boolean }[] = []
  for (let i = 6; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    const key = toDateStr(d)
    out.push({
      key,
      label: i === 0 ? '今天' : WEEK_LABEL[d.getDay()],
      day: d.getDate(),
      checked: checked.has(key),
      isToday: i === 0
    })
  }
  return out
})

/** 今日是否已签到（用于火焰点亮态） */
const lit = computed(() => !!today.value || checkin.currentStreak > 0)
</script>

<template>
  <router-link v-if="checkin.available" to="/level" class="surface checkin-card" aria-label="查看签到日历与经验明细">
    <div class="cc__row">
      <div class="cc__flame" :class="{ 'is-idle': !lit }">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
          <path d="M12 2c1 4-4 5.5-4 10a4.5 4.5 0 0 0 9 0c0-2-1-3.5-1-3.5S15 10 13.5 10C12 10 12.8 6 12 2z" fill="currentColor" />
          <path d="M12 22a7 7 0 0 1-7-7c0-1.2.3-2.3.8-3.3" stroke="currentColor" stroke-opacity=".55" stroke-width="1.6" stroke-linecap="round" />
        </svg>
      </div>
      <div class="cc__main">
        <div class="cc__title">
          <template v-if="today">已连续签到 <b>{{ today.streakDays }}</b> 天</template>
          <template v-else-if="checkin.currentStreak">此前已连续 <b>{{ checkin.currentStreak }}</b> 天，今日待签到</template>
          <template v-else>今日尚未签到</template>
        </div>
        <div class="cc__sub">
          <template v-if="today">
            今日已签到 <span class="cc__plus">+{{ today.expReward }} 经验</span>（基础 {{ today.baseExp }} + 连续 {{ today.bonusExp }}）· 启动应用时自动完成
          </template>
          <template v-else>下次启动应用时自动完成签到并发放经验</template>
        </div>
      </div>
      <div class="cc__week">
        <div v-for="d in week" :key="d.key" class="cc__day" :class="{ 'is-today': d.isToday }">
          <span class="cc__wd">{{ d.label }}</span>
          <span class="cc__dot" :class="d.checked ? 'is-done' : 'is-miss'">{{ d.day }}</span>
        </div>
      </div>
      <div class="cc__link">查看签到日历<br />与经验明细 →</div>
    </div>
  </router-link>
</template>

<style scoped>
.checkin-card {
  display: block;
  color: var(--bk-text);
  text-decoration: none;
  padding: var(--bk-row-padding) var(--bk-panel-padding);
  container: checkin-card / inline-size;
}

.checkin-card:hover { background: color-mix(in srgb, var(--bk-primary-soft) 35%, var(--bk-surface)); }
.checkin-card:focus-visible { outline: 2px solid var(--bk-button-primary); outline-offset: 3px; }

.cc__row {
  display: flex;
  align-items: center;
  gap: var(--bk-gap);
}

.cc__flame {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  flex: none;
  background: var(--bk-primary-soft);
  color: var(--bk-button-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.cc__flame.is-idle {
  background: var(--el-fill-color-darker, #d0d4da);
}

.cc__main {
  min-width: 0;
}

.cc__title {
  font-size: 15px;
  font-weight: 600;
}

.cc__title b {
  color: var(--bk-button-primary);
  font-size: 20px;
  margin: 0 2px;
}

.cc__sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 5px;
}

.cc__plus {
  color: var(--bk-income-text);
  font-weight: 600;
}

.cc__week {
  display: flex;
  gap: 10px;
  margin-left: auto;
}

.cc__day {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  width: 34px;
}

.cc__wd {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cc__dot {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.cc__dot.is-done {
  background: var(--bk-primary-soft);
  color: var(--bk-button-primary);
}

.cc__dot.is-miss {
  background: transparent;
  border: 1px dashed var(--el-border-color);
}

.cc__day.is-today .cc__dot {
  box-shadow: 0 0 0 3px var(--el-color-primary-light-7);
}

.cc__link {
  flex: none;
  font-size: 13px;
  color: var(--el-color-primary);
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: var(--bk-gap);
  line-height: 20px;
}
@container checkin-card (max-width: 1000px) {
  .cc__row { flex-wrap: wrap; }
  .cc__main { flex: 1 1 300px; }
  .cc__week { margin-left: 0; }
  .cc__link { margin-left: auto; }
}
@container checkin-card (max-width: 440px) {
  .cc__main { flex-basis: calc(100% - 70px); }
  .cc__week { width: 100%; justify-content: space-between; gap: 4px; }
  .cc__link { margin-left: 0; border-left: 0; padding-left: 0; }
}
</style>
