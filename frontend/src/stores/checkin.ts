import { defineStore } from 'pinia'
import { checkInApi } from '@/api'
import type { CheckIn } from '@/types/model'

/** 并发去重：总览卡片 / 等级页专区同时触发加载时复用同一 promise */
let loadPromise: Promise<void> | null = null

const pad = (n: number) => String(n).padStart(2, '0')

/** 本地时区日期 → yyyy-MM-dd（与后端 checkDate 序列化格式一致） */
export function toDateStr(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 今天日期串 */
export function todayStr(): string {
  return toDateStr(new Date())
}

/**
 * 签到 store。
 * 签到在后端启动时自动完成，前端纯展示；接口为可选能力：
 * 请求失败时 available=false，签到卡片/专区降级隐藏，不弹错误提示。
 */
export const useCheckInStore = defineStore('checkIn', {
  state: () => ({
    records: [] as CheckIn[],
    /** 后端签到接口是否可用 */
    available: false,
    loading: false
  }),

  getters: {
    /** checkDate → 记录 查找表 */
    byDate(): Map<string, CheckIn> {
      return new Map(this.records.map((r) => [r.checkDate, r]))
    },
    /** 已签到日期集合（日历打点用） */
    checkedDates(): Set<string> {
      return new Set(this.records.map((r) => r.checkDate))
    },
    /** 今日签到记录；未签到为 null */
    todayRecord(): CheckIn | null {
      return this.byDate.get(todayStr()) ?? null
    },
    /** 当前连续天数：今日已签到取今日值，否则延续到昨天为止的连续天数 */
    currentStreak(): number {
      const today = this.todayRecord as CheckIn | null
      if (today) return today.streakDays
      const yesterday = new Date()
      yesterday.setDate(yesterday.getDate() - 1)
      return this.byDate.get(toDateStr(yesterday))?.streakDays ?? 0
    },
    /** 累计签到天数 */
    totalDays(): number {
      return this.records.length
    },
    /** 签到累计经验 */
    totalExp(): number {
      return this.records.reduce((sum, r) => sum + Number(r.expReward), 0)
    },
    /** 按日期倒序的签到记录 */
    sortedDesc(): CheckIn[] {
      return [...this.records].sort((a, b) => (a.checkDate < b.checkDate ? 1 : -1))
    }
  },

  actions: {
    load(force = false): Promise<void> {
      if (!force && this.records.length) return Promise.resolve()
      if (!loadPromise) {
        loadPromise = this.fetch().finally(() => {
          loadPromise = null
        })
      }
      return loadPromise
    },

    async fetch() {
      this.loading = true
      try {
        // silent：签到为可选展示能力，接口不可用时静默降级隐藏
        this.records = await checkInApi.selectAll({ silent: true })
        this.available = true
      } catch {
        this.available = false
      } finally {
        this.loading = false
      }
    }
  }
})
