import { beforeEach, describe, expect, it, vi } from 'vitest'
import { monthlyAiApi, monthlyReportApi } from './monthlyReport'
const request = vi.hoisted(() => vi.fn())
vi.mock('./http', () => ({ request }))
beforeEach(() => { request.mockReset(); request.mockResolvedValue({ data: true }) })
describe('AI HTTP 契约', () => {
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
