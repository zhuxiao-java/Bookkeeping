import type { App } from 'vue'

/**
 * 前端全局错误捕获与落盘（OPT-12）。
 *
 * 渲染进程的未捕获异常此前无任何兜底：既不进控制台聚合，也不落盘，用户报障时无日志可附
 * （后端已有 backend.log）。这里统一捕获三类来源，格式化后经 Electron IPC 落盘到
 * userData/frontend.log；纯浏览器（npm run dev）环境降级为 console.error。
 */

/** 错误来源分类：Vue 组件渲染/生命周期、window 运行时错误、未处理的 Promise rejection */
type ErrorKind = 'vue' | 'window' | 'promise'

interface ErrorPayload {
  kind: ErrorKind
  message: string
  stack?: string
  extra?: string
}

/** 格式化为单行文本并转发：开发环境保留完整堆栈到控制台，Electron 环境额外落盘 */
function forward(payload: ErrorPayload) {
  const parts = [`${payload.kind}: ${payload.message}`]
  if (payload.extra) parts.push(payload.extra)
  if (payload.stack) parts.push(payload.stack)
  const line = parts.join(' | ')
  // 开发时在控制台保留可读的错误，便于断点排查
  console.error('[frontend-error]', line)
  try {
    // 主进程落盘（存在才调用；纯浏览器环境 electronAPI 不存在）
    window.electronAPI?.logError?.(line)
  } catch {
    // 转发失败不影响主流程
  }
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

function stackOf(err: unknown): string | undefined {
  return err instanceof Error ? err.stack : undefined
}

/**
 * 安装全局错误捕获，应在 app.mount() 之前调用。
 * @param app Vue 应用实例（用于挂载 app.config.errorHandler）
 */
export function installErrorLogger(app: App): void {
  // Vue 组件内的异常（渲染、watcher、生命周期钩子）
  app.config.errorHandler = (err, _instance, info) => {
    forward({ kind: 'vue', message: messageOf(err), stack: stackOf(err), extra: `info: ${info}` })
  }

  // 运行时未捕获错误（含资源加载失败的 error 事件）
  window.addEventListener('error', (e) => {
    forward({
      kind: 'window',
      message: e.message || 'unknown error',
      stack: stackOf(e.error),
      extra: `at ${e.filename}:${e.lineno}:${e.colno}`
    })
  })

  // 未处理的 Promise rejection（如未 catch 的接口异常）
  window.addEventListener('unhandledrejection', (e) => {
    forward({ kind: 'promise', message: messageOf(e.reason), stack: stackOf(e.reason) })
  })
}
