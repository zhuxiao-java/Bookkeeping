import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { levelApi } from '@/api'
import { useLevelStore } from './level'
import type { ExperienceLog, LevelConfig, LevelInfo } from '@/types/model'

vi.mock('@/api', () => ({
  levelApi: { current: vi.fn(), configs: vi.fn(), logs: vi.fn() }
}))

function info(experience: number): LevelInfo {
  return {
    level: 1, leveName: '理财小白', description: '', icon: null,
    experience, totalEarned: experience, totalSpent: 0, currentThreshold: 0,
    nextLevel: 2, nextLevelName: '零钱管家', nextThreshold: 100, birthday: null
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const config: LevelConfig = { id: 1, level: 1, name: '理财小白', expThreshold: 0, icon: null, description: null }
const log: ExperienceLog = { id: 1, year: 2026, month: 8, budgetAmount: 1000, actualAmount: 900, diffAmount: 100, expChange: 500 }

beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
  vi.mocked(levelApi.current).mockResolvedValue(info(10))
  vi.mocked(levelApi.configs).mockResolvedValue([config])
  vi.mocked(levelApi.logs).mockResolvedValue([log])
})

describe('等级状态同步', () => {
  it('未加载时不是满级，进度与剩余经验随实际值变化', () => {
    const store = useLevelStore()
    expect(store.progress).toBe(0)
    store.info = info(28)
    expect(store.progress).toBe(28)
    expect(store.remainExp).toBe(72)
    store.info.nextThreshold = null
    expect(store.progress).toBe(100)
    expect(store.remainExp).toBe(0)
  })

  it('首次同时加载侧栏与等级页只请求一次当前等级', async () => {
    const request = deferred<LevelInfo>()
    vi.mocked(levelApi.current).mockReturnValueOnce(request.promise)
    const store = useLevelStore()
    const current = store.loadCurrent()
    const all = store.loadAll()
    expect(levelApi.current).toHaveBeenCalledTimes(1)
    request.resolve(info(10))
    await Promise.all([current, all])
    expect(store.info?.experience).toBe(10)
    expect(store.loading).toBe(false)
  })

  it('记账后的强制刷新不会复用旧请求，晚到旧响应不能覆盖新经验', async () => {
    const old = deferred<LevelInfo>()
    const latest = deferred<LevelInfo>()
    vi.mocked(levelApi.current).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const store = useLevelStore()
    const first = store.loadCurrent()
    const refresh = store.loadCurrent(true)
    expect(levelApi.current).toHaveBeenCalledTimes(2)
    latest.resolve(info(18))
    await refresh
    expect(store.info?.experience).toBe(18)
    expect(store.loading).toBe(true)
    old.resolve(info(10))
    await first
    expect(store.info?.experience).toBe(18)
    expect(store.loading).toBe(false)
  })

  it('旧请求失败不会隐藏已刷新的等级', async () => {
    const old = deferred<LevelInfo>()
    vi.mocked(levelApi.current).mockReturnValueOnce(old.promise).mockResolvedValueOnce(info(18))
    const store = useLevelStore()
    const first = store.loadCurrent()
    await store.loadCurrent(true)
    old.reject(new Error('旧连接断开'))
    await first
    expect(store.available).toBe(true)
    expect(store.info?.experience).toBe(18)
  })

  it('加载全量数据时不会用旧等级覆盖单独刷新后的等级', async () => {
    const old = deferred<LevelInfo>()
    const logs = deferred<ExperienceLog[]>()
    vi.mocked(levelApi.current).mockReturnValueOnce(old.promise).mockResolvedValueOnce(info(30))
    vi.mocked(levelApi.logs).mockReturnValueOnce(logs.promise)
    const store = useLevelStore()
    const all = store.loadAll()
    await store.loadCurrent(true)
    old.resolve(info(10))
    logs.resolve([log])
    await all
    expect(store.info?.experience).toBe(30)
    expect(store.logs).toEqual([log])
  })

  it('已有配置不会阻止再次进入等级页时刷新状态和月结日志', async () => {
    const store = useLevelStore()
    await store.loadAll()
    vi.mocked(levelApi.current).mockResolvedValueOnce(info(50))
    vi.mocked(levelApi.logs).mockResolvedValueOnce([{ ...log, month: 9 }, log])
    await store.loadAll()
    expect(levelApi.configs).toHaveBeenCalledTimes(1)
    expect(levelApi.current).toHaveBeenCalledTimes(2)
    expect(store.info?.experience).toBe(50)
    expect(store.logs[0].month).toBe(9)
  })

  it('最新请求失败清除旧数据，普通加载可重试恢复', async () => {
    const store = useLevelStore()
    await store.loadCurrent()
    vi.mocked(levelApi.current).mockRejectedValueOnce(new Error('网络断开'))
    await store.loadCurrent(true)
    expect(store.info).toBeNull()
    expect(store.available).toBe(false)
    await store.loadCurrent()
    expect(store.available).toBe(true)
  })

  it('历史接口失败不抹掉当前等级，下次加载可以恢复', async () => {
    const store = useLevelStore()
    vi.mocked(levelApi.logs).mockRejectedValueOnce(new Error('日志暂不可用'))
    await store.loadAll()
    expect(store.available).toBe(true)
    expect(store.info?.experience).toBe(10)
    await store.loadAll()
    expect(store.logs).toEqual([log])
  })

  it('不同 store 实例不能共享未完成请求', async () => {
    const old = deferred<LevelInfo>()
    vi.mocked(levelApi.current).mockReturnValueOnce(old.promise).mockResolvedValueOnce(info(50))
    const first = useLevelStore()
    const pending = first.loadCurrent()
    setActivePinia(createPinia())
    const second = useLevelStore()
    await second.loadCurrent()
    old.resolve(info(10))
    await pending
    expect(first.info?.experience).toBe(10)
    expect(second.info?.experience).toBe(50)
  })
})
