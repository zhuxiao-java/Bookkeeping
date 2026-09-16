import { onBeforeUnmount, onMounted } from 'vue'
import { ElNotification } from 'element-plus'
import { budgetApi } from '@/api'
import { useDictStore } from '@/stores/dict'
import { bus, BUDGET_CHANGED, TRANSACTION_CHANGED } from '@/utils/bus'
import type { BudgetInfo } from '@/types/model'

/**
 * 预算不足应用内提醒（需求 4.4.2 / 路线图 MS-04）：
 * 当月支出使某预算使用率「首次越过」80% / 100% 时弹出应用内通知。
 *
 * - 触发：交易变更（支出增减）与预算变更（金额调整）都会改变使用率，二者均触发评估；
 *   挂载时再补检一次，覆盖应用关闭期间已越线的情况。
 * - 去重：以 localStorage 记录每个预算「已提醒到的级别」，同一阈值每月只提醒一次，
 *   避免每次记账重复打扰；级别只升不降（回落后再越线不重复提醒，符合「首次」语义）。
 * - 降级：预算接口不可用时静默返回，不弹任何提示（与消息中心一致的可选能力策略）。
 * - 纯前端实现，零新增依赖、零后端改动。
 */

/** 提醒阈值（%） */
const WARN_PCT = 80
const OVER_PCT = 100
/** 交易/预算落库后延迟评估，确保后端 amountUsed 已更新 */
const EVALUATE_DELAY = 800
/** localStorage 持久化键 */
const STORAGE_KEY = 'bookkeeping-budget-alert'

/** 提醒级别：0 未达 80%、1 达 80%、2 达 100% */
type AlertLevel = 0 | 1 | 2

function readNotified(): Record<string, number> {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') ?? {}
  } catch {
    return {}
  }
}

function writeNotified(map: Record<string, number>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map))
  } catch {
    // 隐私模式等写入失败：忽略，仅失去「只提醒一次」的跨会话持久化
  }
}

function levelOf(pct: number): AlertLevel {
  if (pct >= OVER_PCT) return 2
  if (pct >= WARN_PCT) return 1
  return 0
}

export function useBudgetAlert(): void {
  const dict = useDictStore()

  const nameOf = (b: BudgetInfo) =>
    b.categoryId == null ? '月度总预算' : dict.categoryById(b.categoryId)?.name ?? '分类预算'

  function notify(name: string, pct: number, level: AlertLevel) {
    const title = level === 2 ? '预算超支' : '预算提醒'
    const message =
      level === 2
        ? `「${name}」已超支，使用率 ${pct}%，请注意控制支出`
        : `「${name}」使用率已达 ${pct}%，请注意控制支出`
    ElNotification({
      title,
      message,
      type: level === 2 ? 'error' : 'warning',
      duration: 6000
    })
    // 窗口失焦（最小化 / 收进托盘 / 切到其他应用）时应用内通知看不到，补发一条系统通知（EL-04）
    if (!document.hasFocus()) {
      window.electronAPI?.showNotification?.({ title, body: message })
    }
  }

  async function evaluate() {
    const now = new Date()
    let budgets: BudgetInfo[]
    try {
      budgets = await budgetApi.searchBudget(now.getFullYear(), now.getMonth() + 1, { silent: true })
    } catch {
      // 预算接口不可用：静默降级，不提醒
      return
    }

    const monthKey = `${now.getFullYear()}-${now.getMonth() + 1}`
    const notified = readNotified()
    // 只保留当月记录，避免历史月份键无限堆积
    const next: Record<string, number> = {}
    for (const b of budgets) {
      const amount = Number(b.amount)
      if (!amount || amount <= 0) continue
      const pct = Math.round((Number(b.amountUsed) / amount) * 100)
      const level = levelOf(pct)
      const key = `${monthKey}-${b.categoryId ?? 'total'}`
      const done = notified[key] ?? 0
      if (level > done) notify(nameOf(b), pct, level)
      // 记录已达到的最高级别，保证每个阈值只提醒一次
      next[key] = Math.max(done, level)
    }
    writeNotified(next)
  }

  let timer: number | undefined
  const handler = () => {
    window.clearTimeout(timer)
    timer = window.setTimeout(evaluate, EVALUATE_DELAY)
  }

  onMounted(() => {
    bus.on(TRANSACTION_CHANGED, handler)
    bus.on(BUDGET_CHANGED, handler)
    evaluate()
  })

  onBeforeUnmount(() => {
    bus.off(TRANSACTION_CHANGED, handler)
    bus.off(BUDGET_CHANGED, handler)
    window.clearTimeout(timer)
  })
}
