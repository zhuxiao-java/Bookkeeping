import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { GuideScope } from '@/utils/guideSteps'

const STORAGE_KEY = 'bookkeeping-guide'

/**
 * 引导内容版本：步骤文案或锚点有实质调整时 +1。
 * 版本不匹配时已看记录整体失效，用户会重新走一遍引导。
 */
export const GUIDE_VERSION = 1

interface PersistedGuide {
  version?: number
  done?: Partial<Record<GuideScope, boolean>>
}

function loadFromStorage(): PersistedGuide {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '') as PersistedGuide
  } catch {
    return {}
  }
}

/**
 * 新手引导进度：记录每个 scope（全局 + 各页面）是否已看过。
 * 只存进度不存步骤内容，文案与锚点全部在 utils/guideSteps.ts 中维护。
 */
export const useGuideStore = defineStore('guide', () => {
  const persisted = loadFromStorage()
  /** 版本升级：丢弃旧记录，重新引导一次 */
  const upgraded = persisted.version !== GUIDE_VERSION

  const done = ref<Partial<Record<GuideScope, boolean>>>(upgraded ? {} : persisted.done ?? {})
  /** 正在播放的引导；null 表示当前没有引导 */
  const activeScope = ref<GuideScope | null>(null)

  function isDone(scope: GuideScope): boolean {
    return !!done.value[scope]
  }

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: GUIDE_VERSION, done: done.value }))
    } catch {
      // 本地存储已满：进度仅本次会话生效，不影响功能
    }
  }

  /** 开始播放某个引导（首次自动播放与说明页重看共用） */
  function start(scope: GuideScope) {
    activeScope.value = scope
  }

  /** 播放结束或中途退出：都视为已看过，不再自动打扰 */
  function finish() {
    if (activeScope.value) {
      done.value[activeScope.value] = true
      persist()
    }
    activeScope.value = null
  }

  /** 放弃本次播放（锚点未就绪）：不标记已看过，下次进页会重试 */
  function cancel() {
    activeScope.value = null
  }

  /** 重看全部：清空进度，下次进入各页面时重新引导 */
  function resetAll() {
    done.value = {}
    persist()
  }

  return { done, activeScope, upgraded, isDone, start, finish, cancel, resetAll }
})
