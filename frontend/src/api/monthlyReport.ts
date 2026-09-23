import { request } from './http'
import type { DataResponse, Transaction } from '@/types/model'
export type MonthlyRangeTransaction = Omit<Transaction, 'amount' | 'fee'> & { amount: string; fee: string; monthlyRootId: number; categoryPath: number[] }
import type { AiConfig, AiGenerateRequest, AiPreview, AiSettings, MonthlyDetail, MonthEntry } from '@/types/monthlyReport'

export const monthlyReportApi = {
  async transactions(start: string, end: string, currency: string) {
    return (await request<DataResponse<MonthlyRangeTransaction[]>>({ url: '/transaction/range', params: { start, end, currency } })).data
  },
  async list(year: number) {
    return (await request<DataResponse<MonthEntry[]>>({ url: '/monthly-report', params: { year }, silent: true, noRetry: true })).data
  },
  async detail(id: number) {
    return (await request<DataResponse<MonthlyDetail>>({ url: `/monthly-report/${id}`, silent: true, noRetry: true })).data
  }
}

/**
 * 月报 AI 解读对外接口（均由后端 AgentScope 直接发起，渲染进程只在输入提交时短暂持有密钥）。
 * 失败时 http 拦截器会抛 ApiError（携带后端响应码与中文 msg），调用方按码降级。
 */
export const monthlyAiApi = {
  async settings() {
    return (await request<DataResponse<AiSettings>>({ url: '/monthly-report/ai/config', silent: true, noRetry: true })).data
  },
  async save(config: AiConfig) {
    return (await request<DataResponse<AiSettings>>({ url: '/monthly-report/ai/config', method: 'PUT', data: config, noRetry: true, silent: true })).data
  },
  async revoke(clear = false) {
    return (await request<DataResponse<AiSettings>>({ url: '/monthly-report/ai/config/revoke', method: 'POST', params: { clear }, noRetry: true, silent: true })).data
  },
  async test(consent: { configVersion: string; confirmed: true }) {
    return (await request<DataResponse<boolean>>({ url: '/monthly-report/ai/test', method: 'POST', data: consent, timeout: 30000, noRetry: true, silent: true })).data
  },
  async preview(month: string) {
    return (await request<DataResponse<AiPreview | null>>({ url: '/monthly-report/ai/preview', params: { month }, silent: true, noRetry: true })).data
  },
  async generate(request_: AiGenerateRequest, signal?: AbortSignal) {
    return (await request<DataResponse<MonthlyDetail>>({ url: '/monthly-report/ai/generate', method: 'POST', data: request_, timeout: 150000, signal, noRetry: true, silent: true })).data
  }
}
