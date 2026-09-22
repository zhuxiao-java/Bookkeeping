import { defineStore } from 'pinia'
import { levelApi } from '@/api'
import type { ExperienceLog, LevelConfig, LevelInfo } from '@/types/model'

/** 并发去重：侧边栏 / 总览 / 等级页同时触发加载时复用同一 promise */
interface Requests {
  current: Promise<void> | null
  all: Promise<void> | null
  currentVersion: number
  allVersion: number
  pending: number
}
const requests = new WeakMap<object, Requests>()
function requestsFor(store: object): Requests {
  let state = requests.get(store)
  if (!state) {
    state = { current: null, all: null, currentVersion: 0, allVersion: 0, pending: 0 }
    requests.set(store, state)
  }
  return state
}

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
      if (!info) return 0
      if (info.nextThreshold == null) return 100
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
      const state = requestsFor(this)
      if (!force && state.current) return state.current
      if (!force && this.info && this.available) return Promise.resolve()
      // 强制刷新必须在数据变更后重新发请求，不能复用变更前的旧请求。
      const request = this.fetchCurrent().finally(() => {
        if (state.current === request) state.current = null
      })
      state.current = request
      return request
    },

    /** 等级页：当前状态 + 等级配置 + 月度经验日志 */
    loadAll(force = false): Promise<void> {
      const state = requestsFor(this)
      if (!force && state.all) return state.all
      const request = this.fetchAll(force).finally(() => {
        if (state.all === request) state.all = null
      })
      state.all = request
      return request
    },

    async fetchCurrent() {
      const state = requestsFor(this)
      const version = ++state.currentVersion
      state.pending++
      this.loading = true
      try {
        const info = await levelApi.current()
        if (version === state.currentVersion) {
          this.info = info
          this.available = true
        }
      } catch {
        // 接口未就绪：降级隐藏
        if (version === state.currentVersion) {
          this.info = null
          this.available = false
        }
      } finally {
        this.loading = --state.pending > 0
      }
    },

    async fetchAll(force = false) {
      const state = requestsFor(this)
      const version = ++state.allVersion
      state.pending++
      this.loading = true
      try {
        const [, configs, logs] = await Promise.all([
          this.loadCurrent(force || this.info !== null),
          !force && this.configs.length ? Promise.resolve(this.configs) : levelApi.configs(),
          levelApi.logs()
        ])
        if (version === state.allVersion) {
          this.configs = [...configs].sort((a, b) => a.level - b.level)
          this.logs = [...logs].sort((a, b) => b.year - a.year || b.month - a.month)
        }
      } catch {
        // 配置或历史日志暂不可用，不覆盖独立加载的当前等级状态；下次进入页面会重试。
      } finally {
        this.loading = --state.pending > 0
      }
    }
  }
})
