// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App, type Component } from 'vue'
import type { PeriodPreview, AiSettings, PeriodEntry, PeriodDetail, ReportType, TrendMonth } from '@/types/monthlyReport'
import AnnualTrend from '@/components/monthly/AnnualTrend.vue'
import MessageDetailDialog from '@/components/MessageDetailDialog.vue'
import AiReportSettings from './settings/AiReportSettings.vue'
import MonthlyReportView from './MonthlyReportView.vue'
import { bus, TRANSACTION_CHANGED } from '@/utils/bus'

const api = vi.hoisted(() => ({
  settings: vi.fn(), save: vi.fn(), revoke: vi.fn(), test: vi.fn(), preview: vi.fn(), generate: vi.fn(),
  list: vi.fn(), detail: vi.fn(), confirm: vi.fn(), info: vi.fn(), leave: vi.fn(), update: vi.fn(), push: vi.fn(), replace: vi.fn(), render: vi.fn()
}))
vi.mock('@/api/monthlyReport', () => ({ monthlyAiApi: api, periodReportApi: api }))
vi.mock('@/api', () => ({ messageApi: { detail: vi.fn().mockRejectedValue(new Error('仅使用列表消息')) } }))
vi.mock('@/composables/useChart', () => ({ useChart: () => ({ elRef: ref(), render: api.render }) }))
const theme = reactive({ isDark: false })
vi.mock('@/stores/settings', () => ({ useSettingsStore: () => theme }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: api.confirm }, ElMessage: { info: api.info } }))
const route = reactive({ query: { month: '2024-01' } as Record<string, string> })
vi.mock('vue-router', async importOriginal => ({ ...await importOriginal<typeof import('vue-router')>(), useRoute: () => route, useRouter: () => ({ replace: api.replace, push: api.push }), onBeforeRouteLeave: api.leave, onBeforeRouteUpdate: api.update }))
vi.mock('@/components/monthly/MonthlyOverview.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/monthly/CategoryDiagnosis.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/monthly/MonthlyActions.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/monthly/MonthlyGoal.vue', () => ({ default: { render: () => null } }))

const settings: AiSettings = { baseUrl: 'https://example.com/v1', model: 'test', includeNames: false, hasKey: true, available: true, configVersion: 'v1' }
const preview: PeriodPreview = { type: 'month', periodKey: '2024-01', start: '2024-01-01', end: '2024-01-31', sourceHash: 'hash', baseVersion: 0, configVersion: 'v2', baseUrl: 'https://receiver.example/v1', model: 'preview-model', includeNames: false, summary: { count: 1 } }
function detail(id = 1): PeriodDetail {
  return { id, type: 'month', periodKey: id === 1 ? '2024-01' : '2024-02', start: '2024-01-01', end: '2024-01-31', version: 1, generatedAt: '2024-03-01T00:00:00', stale: false,
    snapshot: { month: '2024-01', ruleVersion: '1', count: 1, currencies: [], budgets: [], facts: [], limitations: [] },
    result: { summary: '上次成功的解读', limitations: '记录有限', provider: 'example', model: 'test', generatedAt: '', observations: [], actions: [] } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}
async function flush() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
let app: App | undefined, root: HTMLDivElement
// 使用 Vue 自带渲染器与轻量控件替身，测试事件和异步状态，而不依赖 Element Plus 布局。
async function mount(component: Component, props = {}) {
  root = document.createElement('div'); document.body.append(root)
  app = createApp(component, props)
  const box = defineComponent({ setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.footer?.()]) })
  for (const name of ['ElForm', 'ElFormItem', 'ElSelect', 'ElCollapse', 'ElCollapseItem', 'ElTag', 'ElIcon', 'ElSkeleton', 'ElDivider']) app.component(name, box)
  app.component('ElButton', defineComponent({ props: ['disabled', 'loading'], setup: (p, { slots, attrs }) => () => h('button', { ...attrs, disabled: p.disabled || p.loading }, slots.default?.()) }))
  app.component('ElInput', defineComponent({ props: ['modelValue', 'disabled'], emits: ['update:modelValue'], setup: (p, { emit, attrs }) => () => h('input', { ...attrs, value: p.modelValue, disabled: p.disabled, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) }) }))
  app.component('ElCheckbox', defineComponent({ props: ['modelValue', 'disabled'], emits: ['update:modelValue'], setup: (p, { emit, slots }) => () => h('label', [h('input', { type: 'checkbox', checked: p.modelValue, disabled: p.disabled, onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).checked) }), slots.default?.()]) }))
  app.component('ElSwitch', defineComponent({ props: ['modelValue', 'disabled'], emits: ['change'], setup: (p, { emit }) => () => h('button', { disabled: p.disabled, onClick: () => emit('change', !p.modelValue) }, p.modelValue ? '已授权' : '关闭') }))
  app.component('ElDialog', defineComponent({ props: ['modelValue'], setup: (p, { slots }) => () => p.modelValue ? h('section', { role: 'dialog' }, [slots.default?.(), slots.footer?.()]) : null }))
  for (const name of ['ElAlert', 'ElEmpty', 'ElOption']) app.component(name, defineComponent({ props: ['title', 'description', 'label'], setup: p => () => h('p', p.title || p.description || p.label) }))
  app.component('ElDatePicker', box)
  app.mount(root); await flush()
}
function button(text: string) {
  const node = [...root.querySelectorAll('button')].find(b => b.textContent?.includes(text))
  expect(node, text).toBeTruthy(); return node!
}
async function click(text: string) { button(text).click(); await flush() }
function input(selector: string, value: string) {
  const node = root.querySelector<HTMLInputElement>(selector)!
  node.value = value; node.dispatchEvent(new Event('input', { bubbles: true }))
}
beforeEach(() => {
  vi.resetAllMocks(); route.query = { month: '2024-01' }
  api.settings.mockResolvedValue({ ...settings }); api.save.mockResolvedValue({ ...settings }); api.revoke.mockResolvedValue({ ...settings, configVersion: 'revoked' })
  api.preview.mockResolvedValue({ ...preview }); api.confirm.mockResolvedValue('confirm'); api.test.mockResolvedValue(true)
  api.list.mockResolvedValue([{ id: null, periodKey: '2024-01', version: null, hasReport: false }, { id: 2, periodKey: '2024-02', version: 1, hasReport: true }])
  api.generate.mockResolvedValue(detail())
  api.detail.mockImplementation((_type: ReportType, id: number) => Promise.resolve(detail(id)))
  api.replace.mockImplementation((target: { query: Record<string, string> }) => { route.query = target.query })
})
afterEach(() => { app?.unmount(); app = undefined; root?.remove(); vi.useRealTimers() })

describe('AI 设置安全交互', () => {
  it('取消测试确认不发送也不显示错误', async () => {
    api.confirm.mockRejectedValue('cancel'); await mount(AiReportSettings); await click('测试连接')
    expect(api.test).not.toHaveBeenCalled(); expect(root.textContent).not.toContain('操作失败')
  })
  it('使用确认版本，false 不会显示连接成功', async () => {
    api.test.mockResolvedValue(false); await mount(AiReportSettings); await click('测试连接')
    expect(api.test).toHaveBeenCalledWith({ configVersion: 'v1', confirmed: true })
    expect(root.textContent).toContain('连接尚未验证'); expect(root.textContent).not.toContain('连接成功')
  })
  it('撤销在测试等待中仍可用，旧结果不能覆盖撤销状态', async () => {
    const pending = deferred<boolean>(); api.test.mockReturnValue(pending.promise)
    await mount(AiReportSettings); await click('测试连接')
    expect(button('保存配置').disabled).toBe(true)
    await click('停止当前发送'); expect(api.revoke).toHaveBeenCalledOnce()
    pending.resolve(true); await flush()
    expect(root.textContent).toContain('当前调用已停止'); expect(root.textContent).not.toContain('连接成功')
  })
  it('保存配置不触发模型且不回填密钥，不再提供自动授权', async () => {
    await mount(AiReportSettings); await click('保存配置')
    expect(root.textContent).toContain('预览并确认后生成'); expect(api.save.mock.calls[0][0]).not.toHaveProperty('apiKey')
    expect(root.querySelector<HTMLInputElement>('input[type=password]')!.value).toBe('')
    expect(root.textContent).not.toContain('自动授权')
    expect(api.generate).not.toHaveBeenCalled(); expect(api.preview).not.toHaveBeenCalled()
  })
  it('配置读取失败与未配置分开显示，允许重新读取', async () => {
    api.settings.mockRejectedValueOnce({ isAxiosError: true, code: 'ERR_NETWORK' })
    await mount(AiReportSettings); expect(root.textContent).toContain('无法连接本地后端')
    expect(root.textContent).not.toContain('尚未完成接口地址'); await click('重新读取配置')
    expect(button('测试连接').disabled).toBe(false)
  })
  it('改变地址提交时不携带旧密钥，提交后清空新密钥输入', async () => {
    await mount(AiReportSettings); input('input[placeholder="https://api.example.com/v1"]', 'https://new.example/v1'); await flush()
    await click('保存配置'); expect(api.save.mock.calls[0][0]).not.toHaveProperty('apiKey')
    input('input[type=password]', 'only-test-key'); await flush(); await click('保存配置')
    expect(root.querySelector<HTMLInputElement>('input[type=password]')!.value).toBe('')
  })
})

async function confirmGeneration() {
  root.querySelector<HTMLInputElement>('[role=dialog] input[type=checkbox]')!.click(); await flush()
  await click('确认生成')
}
function existingReport() {
  api.list.mockResolvedValue([{ id: 1, periodKey: '2024-01', version: 1, hasReport: true }])
}

describe('报告周期、证据与消息', () => {
  it('旧月报路由保留月份和消息定位，并固定月报类型', async () => {
    const { default: router } = await import('@/router')
    await router.push('/monthly-report?month=2024-02&bizId=7&type=year')
    expect(router.currentRoute.value.path).toBe('/ai-report')
    expect(router.currentRoute.value.query).toMatchObject({ type: 'month', periodKey: '2024-02', bizId: '7' })
  })
  it('概览证据下钻包含收入和转账，不只筛选支出', async () => {
    const saved = detail()
    saved.snapshot.facts = [{ id: 'overview', kind: 'overview', currency: 'CNY', categoryId: null, title: '收支概览', values: { income: '100', fees: '1' }, suggestion: '' }]
    saved.result!.observations = [{ text: '核对收入和手续费', factIds: ['overview'] }]
    existingReport(); api.detail.mockResolvedValue(saved); await mount(MonthlyReportView)
    await click('查看对应流水')
    expect(api.push).toHaveBeenCalledWith({ path: '/transaction', query: { start: '2024-01-01', end: '2024-01-31', currency: 'CNY' } })
  })
  it('切换类型丢弃旧预览，使用最近已结束周期', async () => {
    const pending = deferred<PeriodPreview>(); api.preview.mockReturnValue(pending.promise)
    await mount(MonthlyReportView); await click('生成 AI 月报'); await click('AI 周报')
    pending.resolve(preview); await flush()
    expect(api.list).toHaveBeenLastCalledWith('week', expect.any(Number))
    expect(root.querySelector('[role=dialog]')).toBeNull()
    expect(api.replace.mock.calls[0][0].query.type).toBe('week')
    expect(root.textContent).not.toContain('前三月')
  })
  it('跨年周按周一所属年加载，丢弃上一类型迟到详情', async () => {
    const pending = deferred<PeriodDetail>(); api.detail.mockReturnValueOnce(pending.promise)
    route.query = { type: 'month', bizId: '1' }; await mount(MonthlyReportView)
    route.query = { type: 'week', periodKey: '2024-12-30' }; api.list.mockResolvedValue([]); await flush()
    pending.resolve(detail()); await flush()
    expect(api.list).toHaveBeenCalledWith('week', 2024)
    expect(root.textContent).toContain('2024-12-30 ~ 2025-01-05')
    expect(root.textContent).not.toContain('上次成功的解读')
  })
  it('年度预算事实下钻对应月份，普通分类事实下钻全年', async () => {
    const year = { ...detail(), type: 'year' as const, periodKey: '2024', start: '2024-01-01', end: '2024-12-31' }
    year.snapshot.facts = [
      { id: 'budget', kind: 'budget', currency: 'CNY', categoryId: 10, title: '二月超预算', values: { used: '20' }, suggestion: '', start: '2024-02-01', end: '2024-02-29' },
      { id: 'category', kind: 'top', currency: 'CNY', categoryId: 10, title: '全年分类', values: { amount: '30' }, suggestion: '', start: '2024-01-01', end: '2024-12-31' }
    ]
    year.result!.actions = [{ text: '先核对计划内支出，再调整非必要消费', factIds: ['budget', 'category'] }]
    api.detail.mockResolvedValue(year); route.query = { type: 'year', bizId: '1' }
    await mount(MonthlyReportView)
    expect(api.detail).toHaveBeenCalledWith('year', 1)
    expect(root.textContent).toContain('省钱建议 · 为下一年')
    const buttons = [...root.querySelectorAll<HTMLButtonElement>('.fact-item button')]
    buttons[0].click(); expect(api.push).toHaveBeenLastCalledWith({ path: '/transaction', query: { start: '2024-02-01', end: '2024-02-29', currency: 'CNY', type: 'expense', categoryId: 10 } })
    buttons[1].click(); expect(api.push.mock.calls[1][0].query.end).toBe('2024-12-31')
    expect(root.textContent).not.toContain('上月')
  })
  it.each(['week', 'year'] as const)('%s 确认后按类型提交，失败保留旧报告', async type => {
    const key = type === 'week' ? '2024-12-30' : '2024'
    const saved = { ...detail(), type, periodKey: key }
    api.detail.mockResolvedValue(saved); api.preview.mockResolvedValue({ ...preview, type, periodKey: key })
    route.query = { type, bizId: '1' }; await mount(MonthlyReportView)
    api.generate.mockRejectedValue(new Error('模拟生成失败'))
    await click('重新生成'); await confirmGeneration()
    expect(api.generate.mock.calls[0][0]).toMatchObject({ type, periodKey: key, confirmed: true })
    expect(root.textContent).toContain('上次成功的解读'); expect(button('重新生成').disabled).toBe(true)
  })
  it('年度趋势标记缺失月份并在深色切换后重绘', async () => {
    const rows: TrendMonth[] = Array.from({ length: 12 }, (_, i) => ({ month: `2024-${String(i + 1).padStart(2, '0')}`, currency: 'CNY', income: '0.00', expense: i ? '0.00' : '12.34', fees: '0.00', balance: i ? '0.00' : '-12.34', count: i ? 0 : 1 }))
    await mount(AnnualTrend, { rows })
    expect(root.querySelectorAll('tbody tr')).toHaveLength(12)
    expect(root.textContent).toContain('无记录'); expect(root.textContent).toContain('12.34')
    expect(api.render.mock.calls[0][0].series[1].data).toEqual([12.34, ...Array(11).fill(null)])
    const calls = api.render.mock.calls.length; theme.isDark = !theme.isDark; await flush()
    expect(api.render.mock.calls.length).toBeGreaterThan(calls)
  })
  it.each([['monthly_report', 'month', '月报'], ['weekly_report', 'week', '周报'], ['yearly_report', 'year', '年报']])('%s 消息附带类型与 ID', async (bizType, type, label) => {
    await mount(MessageDetailDialog, { modelValue: true, message: { id: 1, bizId: 1, bizType, type: bizType, title: '已生成', content: '完成', createTime: '' } })
    await click(`查看AI ${label}`)
    expect(api.push).toHaveBeenCalledWith({ path: '/ai-report', query: { type, bizId: '1' } })
  })
})

describe('月报手动同步生成', () => {
  it('首次无需本地报告，同一次预览确认后只发送一次，直接展示成功响应', async () => {
    const pending = deferred<PeriodDetail>(); api.generate.mockReturnValue(pending.promise)
    await mount(MonthlyReportView)
    expect(api.detail).not.toHaveBeenCalled(); expect(api.generate).not.toHaveBeenCalled()
    await click('生成 AI 月报')
    expect(api.preview).toHaveBeenCalledWith('month', '2024-01')
    expect(root.querySelector('[role=dialog]')!.textContent).toContain('https://receiver.example/v1')
    expect(root.querySelector('[role=dialog]')!.textContent).toContain('preview-model')
    expect(button('确认生成').disabled).toBe(true)
    root.querySelector<HTMLInputElement>('input[type=checkbox]')!.click(); await flush()
    const confirm = button('确认生成'); confirm.click(); confirm.click(); await flush()
    expect(api.generate).toHaveBeenCalledOnce()
    expect(api.generate).toHaveBeenCalledWith({ type: 'month', periodKey: '2024-01', sourceHash: 'hash', baseVersion: 0, configVersion: 'v2', confirmed: true }, expect.any(AbortSignal))
    expect(root.textContent).toContain('正在整理你的月报')
    expect([...root.querySelectorAll<HTMLButtonElement>('.month-option')].every(b => b.disabled)).toBe(true)
    expect(api.update.mock.calls[0][0]()).toBe(false)
    pending.resolve(detail()); await flush()
    expect(root.textContent).toContain('上次成功的解读'); expect(button('重新生成').disabled).toBe(false)
    expect(root.querySelector('.month-option.selected')!.textContent).toContain('已生成')
  })
  it('月份切换丢弃迟到预览', async () => {
    const pending = deferred<PeriodPreview>(); api.preview.mockReturnValue(pending.promise)
    await mount(MonthlyReportView); await click('生成 AI 月报')
    route.query = { month: '2024-02' }; await flush(); pending.resolve(preview); await flush()
    expect(root.querySelector('[role=dialog]')).toBeNull(); expect(root.textContent).toContain('2024 年 2 月')
  })
  it('深链接加载失败仍显示所选月份，不误报未配置', async () => {
    api.list.mockRejectedValueOnce(new Error('模拟列表读取失败'))
    await mount(MonthlyReportView)
    expect(root.textContent).toContain('2024 年 1 月')
    expect(root.querySelector('.state-chip')!.textContent).toBe('读取失败')
    expect(root.textContent).toContain('模拟列表读取失败')
    expect(root.textContent).not.toContain('先连接你的 AI 服务')
    expect(api.settings).not.toHaveBeenCalled(); expect(button('生成 AI 月报').disabled).toBe(true)
    await click('重新读取')
    expect(root.querySelector('.state-chip')!.textContent).toBe('尚未生成')
    expect(button('生成 AI 月报').disabled).toBe(false)
  })
  it('读取期间明确显示加载状态，不提前显示未配置', async () => {
    const pending = deferred<PeriodEntry[]>()
    api.list.mockReturnValue(pending.promise); await mount(MonthlyReportView)
    expect(root.querySelector('.state-chip')!.textContent).toBe('正在读取')
    expect(root.textContent).not.toContain('先连接你的 AI 服务')
    expect(button('生成 AI 月报').disabled).toBe(true)
    pending.resolve([]); await flush()
    expect(root.querySelector('.state-chip')!.textContent).toBe('尚未生成')
  })
  it('配置读取失败仍能阅读旧结果，与未配置状态区分', async () => {
    existingReport(); api.settings.mockRejectedValue({ isAxiosError: true, code: 'ERR_NETWORK' })
    await mount(MonthlyReportView)
    expect(root.textContent).toContain('AI 配置读取失败'); expect(root.textContent).toContain('上次成功的解读')
    expect(root.textContent).not.toContain('先连接你的 AI 服务'); expect(button('重新生成').disabled).toBe(true)
  })
  it('取消预览不发送，卸载后不打开迟到弹窗', async () => {
    await mount(MonthlyReportView); await click('生成 AI 月报'); await click('取消')
    expect(api.generate).not.toHaveBeenCalled(); expect(root.querySelector('[role=dialog]')).toBeNull()
    const pending = deferred<PeriodPreview>(); api.preview.mockReturnValue(pending.promise)
    await click('生成 AI 月报'); app!.unmount(); app = undefined; pending.resolve(preview); await flush()
    expect(root.textContent).toBe('')
  })
  it('重生成期间保留旧结果、无轮询，超时后先查询再允许重试', async () => {
    vi.useFakeTimers(); existingReport()
    let reject!: (error: unknown) => void
    api.generate.mockReturnValue(new Promise((_, r) => { reject = r }))
    await mount(MonthlyReportView); await click('重新生成'); await confirmGeneration()
    expect(root.textContent).toContain('上次成功的解读')
    await vi.advanceTimersByTimeAsync(150000)
    expect(api.detail).toHaveBeenCalledOnce(); expect(api.generate).toHaveBeenCalledOnce()
    reject({ isAxiosError: true, code: 'ECONNABORTED' }); await flush()
    expect(root.textContent).toContain('上次成功的解读'); expect(root.textContent).toContain('勿重复发送')
    expect(button('重新生成').disabled).toBe(true)
    await click('重新读取已保存报告')
    expect(api.detail).toHaveBeenCalledTimes(2); expect(button('重新生成').disabled).toBe(false)
    expect(api.generate).toHaveBeenCalledOnce()
  })
  it('无数据不调用模型，纯本地历史快照不冒充 AI 报告', async () => {
    existingReport(); api.detail.mockResolvedValue({ ...detail(), result: null }); api.preview.mockResolvedValue(null)
    await mount(MonthlyReportView)
    expect(root.textContent).not.toContain('上次成功的解读'); expect(root.textContent).toContain('尚未生成')
    await click('生成 AI 月报')
    expect(root.textContent).toContain('这个周期还没有可分析的流水'); expect(api.generate).not.toHaveBeenCalled()
  })
  it('账单变更使确认失效，查询过期状态但不发起生成', async () => {
    existingReport(); await mount(MonthlyReportView); await click('重新生成')
    api.detail.mockResolvedValue({ ...detail(), stale: true }); bus.emit(TRANSACTION_CHANGED); await flush()
    expect(root.querySelector('[role=dialog]')).toBeNull(); expect(root.textContent).toContain('账单已变化')
    expect(api.generate).not.toHaveBeenCalled(); expect(api.detail).toHaveBeenCalledTimes(2)
  })
  it('发送前的详情刷新迟到不能覆盖新生成结果', async () => {
    existingReport(); await mount(MonthlyReportView)
    const pending = deferred<PeriodDetail>(); api.detail.mockReturnValueOnce(pending.promise)
    bus.emit(TRANSACTION_CHANGED); await flush()
    const generated = detail(); generated.version = 2; generated.result!.summary = '本次生成的新报告'
    api.generate.mockResolvedValueOnce(generated)
    await click('重新生成'); await confirmGeneration()
    expect(root.textContent).toContain('本次生成的新报告')
    pending.resolve({ ...detail(), stale: true }); await flush()
    expect(root.textContent).toContain('本次生成的新报告')
    expect(root.textContent).not.toContain('上次成功的解读')
    expect(root.textContent).not.toContain('账单已变化')
    expect(api.generate).toHaveBeenCalledOnce(); expect(api.detail).toHaveBeenCalledTimes(2)
  })
  it('离开前确认，取消继续等待，确认后中止前端等待并忽略迟到结果', async () => {
    const pending = deferred<PeriodDetail>(); api.generate.mockReturnValue(pending.promise)
    await mount(MonthlyReportView); await click('生成 AI 月报'); await confirmGeneration()
    const leave = api.leave.mock.calls[0][0], signal = api.generate.mock.calls[0][1] as AbortSignal
    api.confirm.mockRejectedValueOnce('cancel'); expect(await leave()).toBe(false); expect(signal.aborted).toBe(false)
    expect(await leave()).toBe(true); expect(signal.aborted).toBe(true)
    pending.resolve(detail()); await flush(); expect(root.textContent).not.toContain('上次成功的解读')
  })
  it('卸载终止等待，不渲染迟到结果', async () => {
    const pending = deferred<PeriodDetail>(); api.generate.mockReturnValue(pending.promise)
    await mount(MonthlyReportView); await click('生成 AI 月报'); await confirmGeneration()
    const signal = api.generate.mock.calls[0][1] as AbortSignal
    app!.unmount(); app = undefined; expect(signal.aborted).toBe(true)
    pending.resolve(detail()); await flush(); expect(root.textContent).toBe('')
  })
})
