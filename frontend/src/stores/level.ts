import { defineStore } from 'pinia'
import { levelApi } from '@/api'
import type { ExperienceLog, LevelConfig, LevelInfo } from '@/types/model'

/** 并发去重：侧边栏 / 总览 / 等级页同时触发加载时复用同一 promise */
let currentPromise: Promise<void> | null = null
let allPromise: Promise<void> | null = null

/**
 * 用户等级 store。
 * 等级接口为可选能力：请求失败（后端未实现）时 available=false，相关界面降级隐藏，不弹错误提示。
 */
export const useLevelStore = defineStore('level', {
  state: () => ({
    info: null as LevelInfo | null,
    configs: [] as LevelConfig[],
    logs: [] as ExperienceLog[],
    /** 后端等级接口是否可用 */
    available: false,
    loading: false
  }),

  getters: {
    /** 当前等级区间内的升级进度百分比；满级为 100 */
    progress(): number {
      const info = this.info as LevelInfo | null
      if (!info || info.nextThreshold == null) return 100
      const span = Number(info.nextThreshold) - Number(info.currentThreshold)
      if (span <= 0) return 100
      const done = Number(info.experience) - Number(info.currentThreshold)
      return Math.min(100, Math.max(0, Math.round((done / span) * 100)))
    },
    /** 距下一级还差的经验；满级为 0 */
    remainExp(): number {
      const info = this.info as LevelInfo | null
      if (!info || info.nextThreshold == null) return 0
      return Math.max(0, Math.round(Number(info.nextThreshold) - Number(info.experience)))
    }
  },

  actions: {
    /** 仅拉当前等级（侧边栏 / 总览横幅用） */
    loadCurrent(force = false): Promise<void> {
      if (!force && this.info) return Promise.resolve()
      if (!currentPromise) {
        currentPromise = this.fetchCurrent().finally(() => {
          currentPromise = null
        })
      }
      return currentPromise
    },

    /** 等级页：当前状态 + 等级配置 + 月度经验日志 */
    loadAll(force = false): Promise<void> {
      if (!force && this.configs.length) return Promise.resolve()
      if (!allPromise) {
        allPromise = this.fetchAll().finally(() => {
          allPromise = null
        })
      }
      return allPromise
    },

    async fetchCurrent() {
      this.loading = true
      try {
        this.info = await levelApi.current()
        this.available = true
      } catch {
        // 接口未就绪：降级隐藏
        this.available = false
      } finally {
        this.loading = false
      }
    },

    async fetchAll() {
      this.loading = true
      try {
        const [info, configs, logs] = await Promise.all([
          levelApi.current(),
          levelApi.configs(),
          levelApi.logs()
        ])
        this.info = info
        this.configs = configs
        this.logs = logs
        this.available = true
      } catch {
        this.available = false
      } finally {
        this.loading = false
      }
    }
  }
})
