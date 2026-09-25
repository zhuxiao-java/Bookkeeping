import { beforeEach, describe, expect, it, vi } from 'vitest'
import { monthlyAiApi, monthlyReportApi, periodReportApi } from './monthlyReport'
const request = vi.hoisted(() => vi.fn())
vi.mock('./http', () => ({ request }))
beforeEach(() => { request.mockReset(); request.mockResolvedValue({ data: true }) })
describe('AI HTTP 契约', () => {
  it.each(['week', 'month', 'year'] as const)('%s 统一接口携带类型和周期且不重试', async type => {
    const periodKey = type === 'week' ? '2024-12-30' : type === 'month' ? '2024-12' : '2024'
    await periodReportApi.list(type, 2024)
    expect(request).toHaveBeenLastCalledWith({ url: '/ai-report', params: { type, year: 2024 }, silent: true, noRetry: true })
    await periodReportApi.detail(type, 1)
    expect(request).toHaveBeenLastCalledWith({ url: `/ai-report/${type}/1`, silent: true, noRetry: true })
    await periodReportApi.preview(type, periodKey)
    expect(request).toHaveBeenLastCalledWith({ url: '/ai-report/ai/preview', params: { type, periodKey }, silent: true, noRetry: true })
    const data = { type, periodKey, sourceHash: 'hash', baseVersion: 1, configVersion: 'v1', confirmed: true as const }
    const signal = new AbortController().signal
    await periodReportApi.generate(data, signal)
    expect(request).toHaveBeenLastCalledWith({ url: '/ai-report/ai/generate', method: 'POST', data, signal, timeout: 150000, silent: true, noRetry: true })
  })
  it('连接测试携带明确确认版本，禁止自动重试并留足超时', async () => {
    const consent = { configVersion: 'v1', confirmed: true as const }
    expect(await monthlyAiApi.test(consent)).toBe(true)
    expect(request).toHaveBeenCalledWith({ url: '/monthly-report/ai/test', method: 'POST', data: consent, timeout: 30000, noRetry: true, silent: true })
  })
  it('预览接收方完整返回，所有 AI 请求均局部报错且不重试', async () => {
    const preview = { configVersion: 'v2', baseUrl: 'https://example.com/v1', model: 'test', summary: { count: 1 } }
    request.mockResolvedValueOnce({ data: preview })
    expect(await monthlyAiApi.preview('2024-01')).toEqual(preview)
    await monthlyAiApi.settings()
    await monthlyAiApi.save({ baseUrl: 'https://example.com/v1', model: 'test', includeNames: false })
    await monthlyAiApi.revoke(true)
    const payload = { month: '2024-01', sourceHash: 'hash', baseVersion: 0, configVersion: 'v2', confirmed: true as const }
    const controller = new AbortController()
    const report = { id: 7, result: { summary: '同步生成完成' } }
    request.mockResolvedValueOnce({ data: report })
    expect(await monthlyAiApi.generate(payload, controller.signal)).toEqual(report)
    expect(request).toHaveBeenLastCalledWith({ url: '/monthly-report/ai/generate', method: 'POST', data: payload, signal: controller.signal, timeout: 150000, silent: true, noRetry: true })
    for (const [config] of request.mock.calls) expect(config).toMatchObject({ noRetry: true, silent: true })
    expect(request.mock.calls[0][0].params).toEqual({ month: '2024-01' })
    expect(request.mock.calls[3][0].params).toEqual({ clear: true })
    expect(monthlyReportApi).not.toHaveProperty('generate')
    expect(monthlyAiApi).not.toHaveProperty('authorize')
  })
})
