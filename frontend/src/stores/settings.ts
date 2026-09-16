import { defineStore } from 'pinia'

const STORAGE_KEY = 'bookkeeping-settings'

export type ThemeMode = 'light' | 'dark' | 'auto'

export interface AppSettings {
  theme: ThemeMode
  /** 金额小数位数（0~2） */
  decimalPlaces: number
  /** 背景图：''=纯色 / 内置预设相对 url / 自定义图片 dataURL */
  backgroundImage: string
  /** 背景遮罩强度（0~90 百分比），越高背景越淡、内容越清晰 */
  backgroundMask: number
  /** 屏保触发时长（分钟），0=关闭 */
  screensaverMinutes: number
  /** 快速记账全局快捷键（Electron accelerator 字符串），EL-06 */
  quickRecordShortcut: string
}

const DEFAULTS: AppSettings = {
  theme: 'light',
  decimalPlaces: 2,
  backgroundImage: '',
  backgroundMask: 55,
  screensaverMinutes: 5,
  quickRecordShortcut: 'CommandOrControl+Shift+B'
}

function loadFromStorage(): AppSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<AppSettings>
      return { ...DEFAULTS, ...parsed }
    }
  } catch {
    // 本地存储损坏时回退默认值
  }
  return { ...DEFAULTS }
}

/** 系统是否偏好深色（auto 主题下据此推导生效主题；无 matchMedia 时视为浅色） */
function prefersDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches
}

/** 用户偏好设置（主题、金额格式、背景图等），localStorage 持久化 */
export const useSettingsStore = defineStore('settings', {
  // effectiveDark 为当前生效的深色态（auto 主题下随系统推导），仅供 UI 响应式读取，不持久化
  state: (): AppSettings & { effectiveDark: boolean } => ({ ...loadFromStorage(), effectiveDark: prefersDark() }),
  getters: {
    /** 当前是否呈现深色（含 auto 推导结果），供顶栏图标/提示等响应式使用 */
    isDark: (s) => s.effectiveDark
  },
  actions: {
    /** 将主题应用到 document（Element Plus dark 模式依赖 html.dark）；auto 按系统偏好推导 */
    applyTheme() {
      const dark = this.theme === 'auto' ? prefersDark() : this.theme === 'dark'
      this.effectiveDark = dark
      document.documentElement.classList.toggle('dark', dark)
    },
    /** 顶栏一键切换：从当前生效主题取反，落为明确的 light/dark（退出 auto） */
    toggleTheme() {
      this.theme = this.effectiveDark ? 'light' : 'dark'
      this.applyTheme()
      this.persist()
    },
    /** 监听系统主题变化：仅在 auto 模式下跟随切换（应在应用启动时调用一次） */
    startAutoThemeWatch() {
      if (typeof window === 'undefined' || !window.matchMedia) return
      const mql = window.matchMedia('(prefers-color-scheme: dark)')
      mql.addEventListener('change', () => {
        if (this.theme === 'auto') this.applyTheme()
      })
    },
    /** 更新设置并持久化；返回 false 表示写入失败（通常是本地存储配额已满） */
    update(patch: Partial<AppSettings>): boolean {
      this.$patch(patch)
      this.applyTheme()
      return this.persist()
    },
    persist(): boolean {
      try {
        localStorage.setItem(
          STORAGE_KEY,
          JSON.stringify({
            theme: this.theme,
            decimalPlaces: this.decimalPlaces,
            backgroundImage: this.backgroundImage,
            backgroundMask: this.backgroundMask,
            screensaverMinutes: this.screensaverMinutes,
            quickRecordShortcut: this.quickRecordShortcut
          })
        )
        return true
      } catch {
        // 自定义背景 dataURL 过大时可能超出 localStorage 配额
        return false
      }
    }
  }
})
