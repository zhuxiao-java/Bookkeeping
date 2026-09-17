<script setup lang="ts">
import { computed } from 'vue'
import { toDateStr, todayStr } from '@/stores/checkin'

/** 签到月历（纯展示）：周一首列，已签到打点、今日环标、未来淡化 */
const props = defineProps<{
  year: number
  /** 1-12 */
  month: number
  checked: ReadonlySet<string>
  /** 紧凑模式（弹层等窄容器） */
  compact?: boolean
}>()

type CellState = 'other' | 'past' | 'done' | 'today' | 'today-miss' | 'future'
interface Cell {
  key: string
  label: number
  state: CellState
}

const cells = computed<Cell[]>(() => {
  const first = new Date(props.year, props.month - 1, 1)
  const lead = (first.getDay() + 6) % 7 // 周一首列的前月补位天数
  const days = new Date(props.year, props.month, 0).getDate()
  const today = todayStr()
  const out: Cell[] = []
  for (let i = lead; i > 0; i--) {
    const d = new Date(props.year, props.month - 1, 1 - i)
    out.push({ key: toDateStr(d), label: d.getDate(), state: 'other' })
  }
  for (let day = 1; day <= days; day++) {
    const key = toDateStr(new Date(props.year, props.month - 1, day))
    const done = props.checked.has(key)
    if (key === today) out.push({ key, label: day, state: done ? 'today' : 'today-miss' })
    else if (key > today) out.push({ key, label: day, state: 'future' })
    else out.push({ key, label: day, state: done ? 'done' : 'past' })
  }
  return out
})
</script>

<template>
  <div class="cal" :class="{ 'is-compact': compact }">
    <div class="cal__wd"><span>一</span><span>二</span><span>三</span><span>四</span><span>五</span><span>六</span><span>日</span></div>
    <div class="cal__grid">
      <div v-for="c in cells" :key="c.key" class="cal__cell" :class="`is-${c.state}`" :aria-current="c.state.startsWith('today') ? 'date' : undefined" :aria-label="`${c.key}，${checked.has(c.key) ? '已签到' : '未签到'}`">
        {{ c.label }}
        <span v-if="c.state === 'done' || c.state === 'today'" class="tick">✓</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cal__wd,
.cal__grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}

.cal__wd span {
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding-bottom: 4px;
}

.cal__grid {
  margin-top: 6px;
}

.cal__cell {
  height: 42px;
  border-radius: 9px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  gap: 2px;
  font-variant-numeric: tabular-nums;
}

.cal__cell .tick {
  font-size: 9px;
  line-height: 9px;
  color: var(--el-color-primary);
}

.cal__cell.is-done {
  background: var(--bk-primary-soft);
  color: var(--bk-button-primary);
  font-weight: 600;
}

.cal__cell.is-other {
  background: transparent;
  color: var(--el-text-color-placeholder);
}

.cal__cell.is-future {
  background: transparent;
  color: var(--bk-text-secondary);
}

.cal__cell.is-today {
  background: var(--bk-button-primary);
  color: var(--bk-surface);
  font-weight: 700;
  box-shadow: 0 0 0 3px var(--el-color-primary-light-7);
}

.cal__cell.is-today .tick {
  color: inherit;
}

.cal__cell.is-today-miss {
  background: transparent;
  color: var(--el-color-primary);
  font-weight: 600;
  box-shadow: inset 0 0 0 1.5px var(--el-color-primary);
}

/* 紧凑模式 */
.is-compact .cal__cell {
  height: 30px;
  border-radius: 7px;
  font-size: 12px;
}

.is-compact .cal__wd span {
  font-size: 10px;
}
</style>
