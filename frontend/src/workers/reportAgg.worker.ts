import { computeReportAgg, type ReportAggInput, type ReportAggResult } from '@/utils/reportAgg'

/**
 * 报表聚合 Web Worker（OPT-04）：把万条流水的趋势/分类/月度/账户余额推演等重计算
 * 移出主线程，避免阻塞 UI。主线程通过 postMessage 下发 ReportAggInput（带自增 id 防竞态），
 * Worker 回传 { id, result }；计算异常时回传 { id, error }，由主线程降级为同步聚合。
 */

interface AggJob {
  id: number
  input: ReportAggInput
}
interface AggDone {
  id: number
  result?: ReportAggResult
  error?: string
}

// 以 Worker 全局作用域运行；用 Worker 类型承接 self，避免依赖 webworker lib
const ctx = self as unknown as Worker

ctx.onmessage = (e: MessageEvent<AggJob>) => {
  const { id, input } = e.data
  try {
    const result = computeReportAgg(input)
    ctx.postMessage({ id, result } as AggDone)
  } catch (err) {
    ctx.postMessage({ id, error: err instanceof Error ? err.message : String(err) } as AggDone)
  }
}

export {}
