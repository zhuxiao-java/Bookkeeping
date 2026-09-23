export interface CategoryStat {
  categoryId: number; name: string; amount: string; count: number; average: string
  share: string | null; previousAmount: string | null; previousCount: number | null
  previousAverage: string | null; growth: string | null; historyAverage: string | null
  children: { categoryId: number; name: string; amount: string; count: number }[]
  largeExpenses: { id: number; date: string; amount: string }[]
}
export interface CurrencySummary {
  currency: string; income: string; expense: string; fees: string; balance: string; count: number
  previousExpense: string | null; growth: string | null; historyAverage: string | null
  categories: CategoryStat[]
}
export interface MonthlyFact {
  id: string; kind: string; currency: string; categoryId: number | null
  title: string; values: Record<string, string | null>; suggestion: string
}
export interface MonthlySnapshot {
  month: string; ruleVersion: string; count: number; currencies: CurrencySummary[]
  budgets: { id: number; categoryId: number | null; name: string; amount: string; used: string; excess: string; percentage: string | null }[]
  facts: MonthlyFact[]; limitations: string[]
}
export interface AiResult {
  summary: string; limitations: string; provider: string; model: string; generatedAt: string
  observations: { text: string; factIds: string[] }[]
  actions: { text: string; factIds: string[] }[]
}
export interface MonthlyDetail {
  id: number; month: string; version: number; generatedAt: string; stale: boolean; snapshot: MonthlySnapshot
  result: AiResult | null
}
export interface MonthEntry { month: string; id: number | null; version: number | null; generatedAt: string | null; hasReport: boolean }
export interface AiConfig { baseUrl: string; model: string; includeNames: boolean; apiKey?: string }
/**
 * 后端 AI 配置回显（AiConfigView）：绝不含明文密钥。
 * hasKey 标识是否已配置密钥；available 表示后端是否具备发起单次调用的条件。
 */
export interface AiSettings {
  baseUrl: string; model: string; includeNames: boolean
  hasKey: boolean; available: boolean; configVersion: string
}
/** 授权前预览：展示后端实际会发送给模型的（可含脱敏）摘要。 */
export interface AiPreview { month: string; sourceHash: string; baseVersion: number; configVersion: string; baseUrl: string; model: string; includeNames: boolean; summary: unknown }
/** 手动触发某月报 AI 解读的入参。 */
export interface AiGenerateRequest { month: string; sourceHash: string; baseVersion: number; configVersion: string; confirmed: true }
