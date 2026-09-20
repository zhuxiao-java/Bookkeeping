import { request } from './http'
import type { DataResponse, Transaction } from '@/types/model'
export type MonthlyRangeTransaction = Omit<Transaction, 'amount' | 'fee'> & { amount: string; fee: string; monthlyRootId: number; categoryPath: number[] }
import type { MonthlyDetail, MonthEntry } from '@/types/monthlyReport'

export const monthlyReportApi = {
  async transactions(start: string, end: string, currency: string) {
    return (await request<DataResponse<MonthlyRangeTransaction[]>>({ url: '/transaction/range', params: { start, end, currency } })).data
  },
  async list(year: number) {
    return (await request<DataResponse<MonthEntry[]>>({ url: '/monthly-report', params: { year } })).data
  },
  async detail(id: number) {
    return (await request<DataResponse<MonthlyDetail>>({ url: `/monthly-report/${id}` })).data
  },
  async generate(month: string, refresh = false) {
    return (await request<DataResponse<MonthlyDetail>>({ url: '/monthly-report/generate', method: 'POST', data: { month, refresh }, noRetry: true })).data
  }
}
