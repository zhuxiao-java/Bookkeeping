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
  ai: null | { id: string; snapshotVersion: number; configVersion: string; attempt: number; status: string; createdAt: string; errorCode: string | null; result: AiResult | null }
  successfulAi: MonthlyDetail['ai']
}
export interface MonthEntry { month: string; id: number | null; version: number | null; generatedAt: string | null; status: string }
export interface AiConfig { baseUrl: string; model: string; includeNames: boolean; apiKey?: string }
export interface AiSettings extends Omit<AiConfig, 'apiKey'> {
  configVersion: string; automatic: boolean; hasKey: boolean; persistentKey: boolean; available: boolean
}
export interface AiPreview { id: number; snapshotVersion: number; stale: boolean; configVersion: string; summary: unknown }
export type AiReply<T> = { ok: true; value: T } | { ok: false; errorCode: string }
export interface MonthlyAiAPI {
  settings(): Promise<AiReply<AiSettings>>
  save(config: AiConfig): Promise<AiReply<AiSettings>>
  authorize(consent: { configVersion: string; confirmed: true }): Promise<AiReply<AiSettings>>
  revoke(): Promise<AiReply<AiSettings>>
  clear(): Promise<AiReply<AiSettings>>
  test(): Promise<AiReply<boolean>>
  preview(reportId?: number): Promise<AiReply<AiPreview | null>>
  generate(request: { reportId: number; snapshotVersion: number; configVersion: string; confirmed: true; retry: boolean }): Promise<AiReply<boolean>>
  onUpdated(callback: (id: number) => void): () => void
}
