<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useLevelStore } from '@/stores/level'
import { useCheckInStore } from '@/stores/checkin'
import { useSettingsStore } from '@/stores/settings'
import LevelLogo from '@/components/LevelLogo.vue'
import CheckInPanel from '@/components/CheckInPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import { formatAmount } from '@/utils/format'
import type { ExperienceLog } from '@/types/model'

/** 等级页：当前状态 + 升级进度 + 等级阶梯 + 月度经验明细 */
const level = useLevelStore()
const checkin = useCheckInStore()
const settings = useSettingsStore()
const decimals = computed(() => settings.decimalPlaces)

const loading = ref(false)

async function load(force = false) {
  loading.value = true
  try {
    await Promise.all([level.loadAll(force), checkin.load(force)])
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

const exp = computed(() => (level.info ? Math.round(Number(level.info.experience)) : 0))
const earned = computed(() => (level.info ? Math.round(Number(level.info.totalEarned)) : 0))
const spent = computed(() => (level.info ? Math.round(Number(level.info.totalSpent)) : 0))

/** 阶梯单元格状态：已达成 / 当前 / 未达成 */
function ladderState(lv: number): 'done' | 'now' | 'locked' {
  const cur = level.info?.level ?? 0
  if (lv === cur) return 'now'
  return lv < cur ? 'done' : 'locked'
}

/** 带符号整数展示：正数加 + */
function signed(value: number | string): string {
  const n = Math.round(Number(value))
  return n > 0 ? `+${n}` : `${n}`
}

function monthLabel(row: ExperienceLog): string {
  return `${row.year}年${row.month}月`
}
</script>

<template>
  <div class="page page--comfortable level-page" v-loading="loading">
    <!-- 页头叙事 -->
    <div class="page-head">
      <h1 class="page-head__title">等级</h1>
      <p class="page-head__sub">良好的记账习惯会换来经验与成长</p>
    </div>

    <!-- 后端等级接口不可用：给出契约提示与重试 -->
    <el-card v-if="!loading && !level.available" shadow="never">
      <EmptyState description="暂时无法获取等级信息">
        <p class="empty-tip">你的记账记录不受影响，稍后可以再试一次。</p>
        <el-button type="primary" @click="load(true)">重新加载</el-button>
      </EmptyState>
    </el-card>

    <template v-else-if="level.info">
      <!-- 页头：徽章 + 等级名 + 经验统计 + 升级进度 -->
      <el-card shadow="never" data-guide="lv-current">
        <div class="lv-head__row">
          <LevelLogo :level="level.info.level" :size="72" />
          <div class="lv-head__meta">
            <div class="lv-head__name">{{ level.info.leveName }}</div>
            <div class="lv-head__desc">{{ level.info.description }}</div>
          </div>
          <div class="lv-head__stats">
            <div class="lv-stat">
              <div class="lv-stat__v">{{ exp.toLocaleString('zh-CN') }}</div>
              <div class="lv-stat__k">当前经验</div>
            </div>
            <div class="lv-stat">
              <div class="lv-stat__v amount-income">+{{ earned.toLocaleString('zh-CN') }}</div>
              <div class="lv-stat__k">累计获得</div>
            </div>
            <div class="lv-stat">
              <div class="lv-stat__v amount-expense">-{{ spent.toLocaleString('zh-CN') }}</div>
              <div class="lv-stat__k">累计扣除</div>
            </div>
          </div>
        </div>
        <div class="lv-head__progress">
          <div class="lv-head__ptext">
            <span v-if="level.info.nextLevelName">
              距下一级 <b>Lv.{{ level.info.nextLevel }} {{ level.info.nextLevelName }}</b
              >（{{ Number(level.info.nextThreshold).toLocaleString('zh-CN') }}）还需
              <b class="amount-strong">{{ level.remainExp }}</b> 经验
            </span>
            <span v-else>已达到最高等级，继续保持良好的记账习惯</span>
            <span class="lv-head__pct">{{ level.progress }}%</span>
          </div>
          <el-progress :percentage="level.progress" :stroke-width="10" color="var(--bk-primary)" :show-text="false" />
        </div>
      </el-card>

      <!-- 签到专区（后端签到接口可用时展示） -->
      <CheckInPanel data-guide="lv-checkin" />

      <div class="lv-cols">
        <!-- 等级阶梯 -->
        <el-card shadow="never" class="lv-col" data-guide="lv-ladder">
          <template #header>
            <div class="card-head">
              <h2 class="card-head__title">等级阶梯</h2>
              <span class="card-hint">每一步，都算数</span>
            </div>
          </template>
          <div class="ladder">
            <div
              v-for="c in level.configs"
              :key="c.level"
              class="ladder__cell"
              :class="`is-${ladderState(c.level)}`"
              :title="c.description || c.name"
            >
              <span class="ladder__lv">
                <LevelLogo :level="c.level" :size="26" />
              </span>
              <span class="ladder__name">{{ c.name }}</span>
              <span class="ladder__th">{{ Number(c.expThreshold).toLocaleString('zh-CN') }}</span>
            </div>
          </div>
        </el-card>

        <!-- 月度经验明细 -->
        <el-card shadow="never" class="lv-col" data-guide="lv-logs">
          <template #header>
            <div class="card-head">
              <h2 class="card-head__title">月度经验明细</h2>
              <div class="card-head__ctrls">
                <el-button link type="primary" @click="load(true)">刷新</el-button>
              </div>
            </div>
          </template>
          <el-table v-if="level.logs.length" :data="level.logs" size="small">
            <el-table-column label="年月" min-width="90">
              <template #default="{ row }">{{ monthLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="预算" align="right" min-width="92">
              <template #default="{ row }">¥{{ formatAmount(row.budgetAmount, decimals) }}</template>
            </el-table-column>
            <el-table-column label="实际" align="right" min-width="92">
              <template #default="{ row }">¥{{ formatAmount(row.actualAmount, decimals) }}</template>
            </el-table-column>
            <el-table-column label="差额" align="right" min-width="76">
              <template #default="{ row }">
                <span :class="Number(row.diffAmount) >= 0 ? 'amount-income' : 'amount-expense'">
                  {{ signed(row.diffAmount) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="经验" align="right" min-width="66">
              <template #default="{ row }">
                <span
                  class="amount-strong"
                  :class="Number(row.expChange) >= 0 ? 'amount-income' : 'amount-expense'"
                >
                  {{ signed(row.expChange) }}
                </span>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState v-else :size="96" description="暂无月度结算记录" class="empty-block">
            <div class="empty-tip">每月预算结算后：未超支加经验、超支扣经验，并在此留痕。</div>
          </EmptyState>
        </el-card>
      </div>
    </template>
  </div>
</template>

<style scoped>
.lv-head__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 20px;
}

.lv-head__meta {
  min-width: 0;
}

.lv-head__name {
  font-size: 20px;
  font-weight: 600;
}

.lv-head__desc {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-top: 4px;
}

.lv-head__stats {
  margin-left: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 20px 28px;
  text-align: right;
}

.lv-stat__v {
  font-size: 22px;
  font-weight: 650;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.lv-stat__k {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-top: 2px;
}

.lv-head__progress {
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid var(--bk-border-light);
}

.lv-head__ptext {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 8px;
}

.lv-head__pct {
  font-variant-numeric: tabular-nums;
}

.lv-cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: var(--bk-gap);
}

.lv-col {
  min-width: 0;
}

.ladder {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 12px;
  max-height: 430px;
  overflow: auto;
  padding-right: 4px;
}

.ladder__cell {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 10px;
  border-bottom: 1px solid var(--bk-border-light);
  border-radius: var(--bk-radius-sm);
  font-size: 13px;
}

.ladder__cell.is-done {
  color: var(--bk-text-regular);
}

.ladder__cell.is-now {
  border-color: transparent;
  background: var(--bk-primary-soft);
  font-weight: 600;
}

.ladder__cell.is-locked {
  color: var(--bk-text-secondary);
}

.ladder__lv {
  flex-shrink: 0;
  display: inline-flex;
}

.ladder__name {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}

.ladder__th {
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.empty-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 12px;
}
@container content (max-width: 1040px) {
  .lv-cols {
    grid-template-columns: minmax(0, 1fr);
  }
}

@container content (max-width: 800px) {
  .lv-head__stats {
    width: 100%;
    justify-content: space-between;
    gap: 16px;
    text-align: left;
    margin-left: 0;
  }
}
@container content (max-width: 520px) {
  .ladder { grid-template-columns: 1fr; }
  .lv-head__ptext { align-items: flex-start; }
}
</style>
