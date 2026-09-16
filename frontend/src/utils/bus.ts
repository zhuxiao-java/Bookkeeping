/** 极简事件总线：跨页面通知数据变更（如快速记账后刷新列表） */
type Handler = (...args: unknown[]) => void

const listeners = new Map<string, Set<Handler>>()

export const bus = {
  on(event: string, fn: Handler) {
    if (!listeners.has(event)) listeners.set(event, new Set())
    listeners.get(event)!.add(fn)
  },
  off(event: string, fn: Handler) {
    listeners.get(event)?.delete(fn)
  },
  emit(event: string, ...args: unknown[]) {
    listeners.get(event)?.forEach((fn) => fn(...args))
  }
}

/** 交易数据发生变更（新增/编辑/删除） */
export const TRANSACTION_CHANGED = 'transaction-changed'
/** 账户数据发生变更 */
export const ACCOUNT_CHANGED = 'account-changed'
/** 分类数据发生变更 */
export const CATEGORY_CHANGED = 'category-changed'
/** 标签数据发生变更 */
export const TAG_CHANGED = 'tag-changed'
/** 预算数据发生变更 */
export const BUDGET_CHANGED = 'budget-changed'

/** 打开快速记账弹窗（使用说明的「立即记一笔」等跨组件入口） */
export const OPEN_QUICK_RECORD = 'open-quick-record'
