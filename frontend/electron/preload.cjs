/**
 * Electron preload 脚本：
 * 通过 contextBridge 向渲染进程暴露最小化 API（contextIsolation 开启，
 * 渲染进程无法直接访问 Node / ipcRenderer）。
 */
const { contextBridge, ipcRenderer } = require('electron')

/**
 * 解析主进程通过 webPreferences.additionalArguments 注入的后端端口（OPT-08）。
 * 8080 被占用时主进程会降级到其他空闲端口，并把 --backend-port={port} 传入渲染进程 argv；
 * 未注入（开发环境 / 纯浏览器）时返回 undefined，前端回退到 VITE_API_BASE 或 /api 代理。
 * @returns {string | undefined} 形如 http://127.0.0.1:{port}/api
 */
function resolveApiBase() {
  const prefix = '--backend-port='
  const arg = process.argv.find((a) => typeof a === 'string' && a.startsWith(prefix))
  if (!arg) return undefined
  const port = arg.slice(prefix.length)
  if (!/^\d+$/.test(port)) return undefined
  return `http://127.0.0.1:${port}/api`
}

contextBridge.exposeInMainWorld('electronAPI', {
  isElectron: true,
  monthlyAI: {
    settings: () => ipcRenderer.invoke('monthly-ai-settings'),
    save: config => ipcRenderer.invoke('monthly-ai-save', config),
    authorize: consent => ipcRenderer.invoke('monthly-ai-authorize', consent),
    revoke: () => ipcRenderer.invoke('monthly-ai-revoke'),
    clear: () => ipcRenderer.invoke('monthly-ai-clear'),
    test: () => ipcRenderer.invoke('monthly-ai-test'),
    preview: reportId => ipcRenderer.invoke('monthly-ai-preview', { reportId }),
    generate: request => ipcRenderer.invoke('monthly-ai-generate', request),
    onUpdated(callback) {
      const listener = (_event, id) => callback(id)
      ipcRenderer.on('monthly-ai-updated', listener)
      return () => ipcRenderer.removeListener('monthly-ai-updated', listener)
    }
  },
  platform: process.platform,
  /** 后端 API 基础地址（动态端口降级时随之调整，OPT-08） */
  apiBase: resolveApiBase(),
  /**
   * 订阅主进程"打开快速记账"事件（托盘菜单 / 全局快捷键触发）
   * @param {() => void} callback
   * @returns {() => void} 取消订阅函数
   */
  onOpenQuickRecord(callback) {
    const listener = () => callback()
    ipcRenderer.on('open-quick-record', listener)
    return () => ipcRenderer.removeListener('open-quick-record', listener)
  },
  /**
   * 请求主进程重启 Java 后端子进程（备份恢复覆盖 accounts.db 后重新加载新库）。
   * @returns {Promise<{ok: boolean, reason?: string}>}
   */
  restartBackend() {
    return ipcRenderer.invoke('restart-backend')
  },
  /**
   * 上报前端全局错误供主进程落盘（userData/frontend.log，OPT-12）。
   * fire-and-forget，不阻塞渲染进程。
   * @param {string} message
   */
  logError(message) {
    ipcRenderer.send('log-error', message)
  },
  /**
   * 发一条系统通知（OS 级），用于窗口失焦/收进托盘时应用内通知看不到的场景（EL-04）。
   * fire-and-forget；主进程会在点击时唤起主窗口。
   * @param {{ title?: string, body: string }} payload
   */
  showNotification(payload) {
    ipcRenderer.send('show-notification', payload)
  },
  /**
   * 自定义快速记账全局快捷键（EL-06）：传入 Electron accelerator 字符串（如 'CommandOrControl+Shift+B'），
   * 主进程先注册新键再释放旧键。
   * @param {string} accelerator
   * @returns {Promise<{ok: boolean, reason?: string}>}
   */
  setQuickRecordShortcut(accelerator) {
    return ipcRenderer.invoke('set-quick-record-shortcut', accelerator)
  }
})
