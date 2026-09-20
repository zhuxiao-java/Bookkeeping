/**
 * Electron preload 通过 contextBridge 暴露的 API 类型声明。
 * 纯浏览器（npm run dev）环境下 window.electronAPI 不存在，使用时需判空。
 */
interface ElectronAPI {
  monthlyAI?: import('./types/monthlyReport').MonthlyAiAPI
  isElectron: boolean
  platform: string
  /**
   * 后端 API 基础地址（OPT-08）：主进程探测到实际监听端口（8080 被占用时自动降级）
   * 后经 preload 注入，形如 http://127.0.0.1:{port}/api；开发环境或非 Electron 下为 undefined。
   */
  apiBase?: string
  /** 订阅主进程“打开快速记账”事件，返回取消订阅函数 */
  onOpenQuickRecord(callback: () => void): () => void
  /** 请求主进程重启 Java 后端子进程（备份恢复后重新加载新库） */
  restartBackend(): Promise<{ ok: boolean; reason?: string }>
  /** 上报前端全局错误供主进程落盘（userData/frontend.log，OPT-12） */
  logError?(message: string): void
  /** 发一条系统（OS 级）通知：窗口失焦/收进托盘时应用内通知看不到的兜底（EL-04） */
  showNotification?(payload: { title?: string; body: string }): void
  /** 自定义快速记账全局快捷键（Electron accelerator 字符串），主进程重注册（EL-06） */
  setQuickRecordShortcut?(accelerator: string): Promise<{ ok: boolean; reason?: string }>
}

interface Window {
  electronAPI?: ElectronAPI
}
