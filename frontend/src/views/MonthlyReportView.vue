<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { MagicStick, Document, Lock, Refresh, Setting } from '@element-plus/icons-vue'
import { monthlyReportApi, monthlyAiApi } from '@/api/monthlyReport'
import type { AiPreview, AiSettings, MonthEntry, MonthlyDetail } from '@/types/monthlyReport'
import { aiFail, currencyName, factEvidence, lastClosedMonth, monthRange } from '@/utils/monthlyReport'
import MonthlyOverview from '@/components/monthly/MonthlyOverview.vue'
import CategoryDiagnosis from '@/components/monthly/CategoryDiagnosis.vue'
import { bus, TRANSACTION_CHANGED, ACCOUNT_CHANGED, CATEGORY_CHANGED, BUDGET_CHANGED } from '@/utils/bus'

const router = useRouter(), route = useRoute()
const month = ref(lastClosedMonth()), entries = ref<MonthEntry[]>([])
const report = ref<MonthlyDetail | null>(null), settings = ref<AiSettings | null>(null)
const loading = ref(true), sending = ref(false), previewLoading = ref(false)
const error = ref(''), settingsError = ref(''), aiMessage = ref('')
const noData = ref(false), needsCheck = ref(false)
const preview = ref<AiPreview | null>(null), previewVisible = ref(false), consent = ref(false)
let loadVersion = 0, previewVersion = 0, disposed = false, refreshing = false
let requestController: AbortController | undefined
const years = Array.from({ length: Number(lastClosedMonth().slice(0, 4)) - 1899 }, (_, i) => Number(lastClosedMonth().slice(0, 4)) - i)
const title = computed(() => `${month.value.slice(0, 4)} 年 ${Number(month.value.slice(5))} 月`)
const hasReport = computed(() => !!report.value?.result)
const factMap = computed(() => new Map(report.value?.snapshot.facts.map(f => [f.id, f]) ?? []))
const canGenerate = computed(() => !loading.value && !sending.value && !previewLoading.value && !error.value && !needsCheck.value && !!settings.value?.available && !!settings.value?.hasKey)
const sections = computed(() => [
  { key: 'observations', eyebrow: 'OBSERVATIONS', title: '这个月，值得留意的变化', empty: '现有数据不足以形成更多发现。', items: report.value?.result?.observations ?? [] },
  { key: 'actions', eyebrow: 'NEXT MONTH', title: '为下个月，留一点余地', empty: '暂无足够依据提出调整建议，保持记录就很好。', items: report.value?.result?.actions ?? [] }
])
const stateLabel = computed(() => sending.value ? '正在生成' : loading.value ? '正在读取' : error.value ? '读取失败' : report.value?.stale && hasReport.value ? '数据已变化' : hasReport.value ? '已生成' : '尚未生成')
const events = [TRANSACTION_CHANGED, ACCOUNT_CHANGED, CATEGORY_CHANGED, BUDGET_CHANGED]

function invalidatePreview() {
  previewVersion++; preview.value = null; previewVisible.value = false; consent.value = false; previewLoading.value = false
}
function validMonth(value: string) {
  return /^\d{4}-(0[1-9]|1[0-2])$/.test(value) && value >= '1900-01' && value <= lastClosedMonth()
}
async function load() {
  if (disposed || sending.value) return
  invalidatePreview()
  const sequence = ++loadVersion
  const current = () => !disposed && sequence === loadVersion
  loading.value = true; error.value = ''
  try {
    let selected = typeof route.query.month === 'string' ? route.query.month : month.value
    if (validMonth(selected)) month.value = selected
    let detail: MonthlyDetail | null = null
    const id = Number(route.query.bizId)
    if (Number.isSafeInteger(id) && id > 0) { detail = await monthlyReportApi.detail(id); selected = detail.month }
    if (!validMonth(selected)) throw new Error('只能查看已结束月份的月报。')
    const list = await monthlyReportApi.list(Number(selected.slice(0, 4)))
    if (!current()) return
    const entry = list.find(e => e.month === selected)
    if (!detail && entry?.id) detail = await monthlyReportApi.detail(entry.id)
    if (!current()) return
    month.value = selected; entries.value = list; report.value = detail; needsCheck.value = false
    try {
      const value = await monthlyAiApi.settings()
      if (current()) { settings.value = value; settingsError.value = '' }
    } catch (e) {
      if (current()) { settings.value = null; settingsError.value = aiFail(e, 'AI 配置读取失败') }
    }
  } catch (e) { if (current()) error.value = aiFail(e, '月报加载失败，请重新读取。') }
  finally { if (current()) loading.value = false }
}
function selectMonth(value: string) {
  if (sending.value || !validMonth(value) || (value === month.value && !route.query.bizId)) return
  loadVersion++; invalidatePreview(); report.value = null; aiMessage.value = ''; noData.value = false; needsCheck.value = false
  month.value = value
  void router.replace({ query: { month: value } })
}
function selectYear(value: number) {
  const candidate = `${value}-${month.value.slice(5)}`
  selectMonth(candidate > lastClosedMonth() ? lastClosedMonth() : candidate)
}
async function showPreview() {
  if (!canGenerate.value) return
  invalidatePreview()
  const selected = month.value, sequence = previewVersion
  const current = () => !disposed && sequence === previewVersion && month.value === selected
  aiMessage.value = ''; noData.value = false; previewLoading.value = true
  try {
    const value = await monthlyAiApi.preview(selected)
    if (!current()) return
    if (!value) { noData.value = true; return }
    if (value.month !== selected) throw new Error('月份已变化，请重新预览。')
    preview.value = value; consent.value = false; previewVisible.value = true
  } catch (e) { if (current()) aiMessage.value = aiFail(e, '摘要读取失败，请重试。') }
  finally { if (current()) previewLoading.value = false }
}
async function send() {
  if (!preview.value || !consent.value || sending.value || disposed || preview.value.month !== month.value) return
  const captured = preview.value, sequence = ++loadVersion
  const current = () => !disposed && sequence === loadVersion
  requestController = new AbortController()
  sending.value = true; aiMessage.value = ''; needsCheck.value = false
  invalidatePreview()
  try {
    const value = await monthlyAiApi.generate({ month: captured.month, sourceHash: captured.sourceHash, baseVersion: captured.baseVersion, configVersion: captured.configVersion, confirmed: true }, requestController.signal)
    if (!current()) return
    if (!value?.result || value.month !== captured.month) throw new Error('未收到完整月报，请先重新读取已保存报告。')
    report.value = value
    entries.value = entries.value.map(e => e.month === value.month ? { month: value.month, id: value.id, version: value.version, generatedAt: value.generatedAt, hasReport: true } : e)
  } catch (e) {
    if (current()) { aiMessage.value = aiFail(e); needsCheck.value = true }
  } finally { if (current()) { sending.value = false; requestController = undefined } }
}
function drill(currency: string, categoryId: number | null) {
  const [start, end] = monthRange(report.value?.month ?? month.value)
  void router.push({ path: '/transaction', query: { start, end, currency, type: 'expense', ...(categoryId != null ? { categoryId } : {}) } })
}
async function onDataChanged() {
  invalidatePreview(); noData.value = false
  if (report.value) report.value = { ...report.value, stale: true }
  if (!report.value || sending.value || loading.value || refreshing || disposed) return
  const selected = report.value, sequence = loadVersion
  refreshing = true
  try {
    const value = await monthlyReportApi.detail(selected.id)
    if (!disposed && sequence === loadVersion && report.value?.id === selected.id) report.value = value
  } catch { /* 事件查询失败不覆盖既有报告，保留过期提示。 */ }
  finally { refreshing = false }
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (sending.value) { event.preventDefault(); event.returnValue = '' }
}
onBeforeRouteUpdate(() => !sending.value)
onBeforeRouteLeave(async () => {
  if (!sending.value) return true
  try {
    await ElMessageBox.confirm('AI 仍在生成。离开只停止页面等待，请求可能已计费并继续完成；返回后请先查看已保存报告，勿直接重复生成。', '离开月报页面？', { confirmButtonText: '离开页面', cancelButtonText: '继续等待', type: 'warning' })
    loadVersion++; requestController?.abort(); sending.value = false
    return true
  } catch { return false }
})
watch(() => route.query, load)
onMounted(() => {
  void load()
  events.forEach(event => bus.on(event, onDataChanged))
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  disposed = true; loadVersion++; invalidatePreview(); requestController?.abort()
  events.forEach(event => bus.off(event, onDataChanged))
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div class="page quiet-controls monthly-page">
    <header class="report-header">
      <div><p class="eyebrow">YOUR MONTH IN REVIEW</p><h1>AI 月报<span class="header-dot">·</span><span class="month-title">{{ title }}</span></h1><p class="muted">让数字有一点温度，让每一次回望都成为新的开始。</p></div>
      <el-button :icon="Setting" :disabled="sending" @click="router.push('/settings?menu=ai')">AI 设置</el-button>
    </header>
    <div class="report-workspace">
      <aside class="archive surface" aria-label="月份归档">
        <div class="archive-heading"><span>月度归档</span><el-select :model-value="Number(month.slice(0, 4))" :disabled="sending" aria-label="月报年份" @update:model-value="selectYear"><el-option v-for="year in years" :key="year" :value="year" :label="`${year} 年`" /></el-select></div>
        <nav class="month-list" aria-label="选择月份"><button v-for="entry in entries" :key="entry.month" class="month-option" :class="{ selected: entry.month === month }" :aria-current="entry.month === month ? 'date' : undefined" :disabled="sending" @click="selectMonth(entry.month)"><span><strong>{{ entry.month.slice(5) }}</strong> 月</span><span class="month-status" :class="{ complete: entry.hasReport }"><i />{{ entry.hasReport ? '已生成' : '未生成' }}</span></button></nav>
        <p class="archive-note"><el-icon><Lock /></el-icon>仅在你确认后生成<br>不自动发送账单</p>
      </aside>
      <main class="report-main" :aria-busy="loading || sending">
        <section class="report-toolbar surface">
          <div><span class="state-chip" :class="{ complete: hasReport, pending: sending }">{{ stateLabel }}</span><p class="muted">{{ hasReport ? `更新于 ${report?.generatedAt.replace('T', ' ').slice(0, 16)}` : '选择一个已结束的月份，写下你的收支回望。' }}</p></div>
          <el-button type="primary" :icon="MagicStick" :disabled="!canGenerate" :loading="sending || previewLoading" @click="showPreview">{{ sending ? 'AI 正在生成' : hasReport ? '重新生成' : '生成 AI 月报' }}</el-button>
        </section>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <section v-if="settingsError" class="notice notice--warning" role="alert"><strong>AI 配置读取失败</strong><p>{{ settingsError }}</p><el-button text :disabled="sending || loading" @click="load">重新读取配置</el-button></section>
        <section v-else-if="!loading && !error && !settings?.available" class="notice" role="status"><strong>先连接你的 AI 服务</strong><p>配置接口地址、模型与密钥后，即可手动生成月报。已保存的报告仍可阅读。</p><el-button text @click="router.push('/settings?menu=ai')">前往 AI 设置 →</el-button></section>
        <section v-if="aiMessage" class="notice notice--warning" role="alert"><strong>本次生成未完成</strong><p>{{ aiMessage }}</p><p v-if="needsCheck">请先重新读取已保存报告，再决定是否重试；重复调用可能再次计费。</p><el-button :icon="Refresh" :disabled="sending || loading" @click="load">重新读取已保存报告</el-button></section>
        <el-button v-else-if="error" :icon="Refresh" :disabled="loading" @click="load">重新读取</el-button>
        <div v-if="noData" class="notice" role="status"><strong>这个月还没有可分析的流水</strong><p>没有向 AI 发送请求。可以换一个月份，或先补充记账。</p></div>
        <div v-if="sending" class="generation-note" role="status" aria-live="polite"><span class="pulse" /><div><strong>正在整理你的月度故事</strong><p>等待 AI 返回，通常需要一些时间，最多等待约两分钟。请勿重复提交。{{ hasReport ? '下方保留上一次成功报告。' : '' }}</p></div></div>
        <section v-if="loading || (sending && !hasReport)" class="surface skeleton-panel"><el-skeleton :rows="6" animated /></section>
        <template v-else-if="hasReport && report?.result">
          <div v-if="report.stale" class="notice notice--warning" role="status"><strong>账单已变化</strong><p>当前报告基于已保存的数据依据。重新生成会使用最新账单，成功后替换旧报告；下钻流水展示实时数据。</p></div>
          <section class="summary-card surface">
            <div class="summary-heading"><span class="eyebrow"><el-icon><MagicStick /></el-icon> MONTHLY LETTER</span><span class="muted">{{ report.snapshot.count }} 笔已记录流水</span></div>
            <h2>关于这个月，AI 想告诉你</h2><p class="summary-text">{{ report.result.summary }}</p>
            <div class="summary-meta"><span>{{ report.result.provider }} · {{ report.result.model }}</span><span>AI 生成 · 仅供参考</span></div>
          </section>
          <section v-for="currency in report.snapshot.currencies" :key="currency.currency" class="surface metrics-panel"><MonthlyOverview :summary="currency" compact /></section>
          <section v-for="section in sections" :key="section.key" class="insights-section">
            <div class="section-heading"><p class="eyebrow">{{ section.eyebrow }}</p><h2>{{ section.title }}</h2></div>
            <p v-if="!section.items.length" class="surface empty-insight muted">{{ section.empty }}</p>
            <div class="insights-grid" :class="{ actions: section.key === 'actions' }"><article v-for="(item, index) in section.items" :key="index" class="surface insight-card"><span class="insight-number">{{ String(index + 1).padStart(2, '0') }}</span><p class="ai-text">{{ item.text }}</p><details class="fact-details"><summary>查看 {{ item.factIds.length }} 项数据依据</summary><div v-for="id in item.factIds" :key="id" class="fact-item"><template v-if="factMap.get(id)"><strong>{{ factMap.get(id)!.title }} · {{ currencyName(factMap.get(id)!.currency) }}</strong><p>{{ factEvidence(factMap.get(id)!) }}</p><el-button text size="small" @click="drill(factMap.get(id)!.currency, factMap.get(id)!.categoryId)">查看对应流水 →</el-button></template></div></details></article></div>
          </section>
          <section class="surface evidence-panel"><details><summary class="evidence-heading"><span><el-icon><Document /></el-icon> 数据依据与统计口径</span><span class="muted">展开核对</span></summary><div class="evidence-content"><CategoryDiagnosis v-for="currency in report.snapshot.currencies" :key="currency.currency" :summary="currency" @drill="drill" /><h3>预算执行 · 人民币消费</h3><p class="muted">手续费不计入分类预算，总预算与分类预算独立比较。</p><p v-if="!report.snapshot.budgets.length" class="muted">本月未设置预算。</p><div v-for="budget in report.snapshot.budgets" :key="budget.id" class="budget-row"><span>{{ budget.name }}</span><strong>{{ budget.used }} / {{ budget.amount }}</strong><span>{{ budget.percentage == null ? '零预算，不计算比例' : `已用 ${budget.percentage}%` }}</span></div><h3>统计口径</h3><p v-for="text in report.snapshot.limitations" :key="text" class="muted">{{ text }}</p></div></details></section>
          <footer class="report-footnote"><el-icon><Lock /></el-icon><div><p>{{ report.result.limitations }}</p><p>数字以账单统计为准，AI 建议不会自动修改账单或预算。不同币种独立分析。</p></div></footer>
        </template>
        <section v-else-if="!loading && !error" class="surface empty-report"><div class="empty-illustration"><el-icon><Document /></el-icon><span><el-icon><MagicStick /></el-icon></span></div><p class="eyebrow">A MOMENT TO REFLECT</p><h2>{{ noData ? '等待一些生活的记录' : '你的这个月，值得一次回望' }}</h2><p>从收支中发现变化，把值得关注的事整理成建议。<br>点击上方“生成 AI 月报”，预览并确认后开始。</p><div class="empty-features"><span>收支总结</span><span>变化发现</span><span>下月建议</span></div><p class="muted small">仅支持已结束月份；当月数据可在报表页查看。</p></section>
        <div class="page-end"><span>每一笔记录，都是更了解自己的开始。</span><el-button v-if="!needsCheck" text size="small" :disabled="loading || sending" :icon="Refresh" @click="load">重新读取</el-button></div>
      </main>
    </div>
    <el-dialog v-model="previewVisible" title="确认生成 AI 月报" width="min(720px, 92vw)" @close="invalidatePreview">
      <div class="preview-recipient"><span class="eyebrow">本次发送至</span><strong>{{ preview?.baseUrl }}</strong><p>{{ preview?.month }} · {{ preview?.model }} · {{ preview?.includeNames ? '包含分类名称' : '分类已匿名化' }}</p></div>
      <p>范围为所选月份的收支、分类、预算和事实统计，比较值包含此前三个自然月的汇总；不发送逐笔交易、明细日期、备注、账户名或余额、标签、生日及文件路径。</p>
      <details class="preview-json"><summary>查看实际发送的完整摘要</summary><pre>{{ JSON.stringify(preview?.summary, null, 2) }}</pre></details>
      <p class="muted">供应商可能收取费用；重新生成可能再次计费。已发送的数据无法保证从供应商撤回。</p>
      <el-checkbox v-model="consent">我已检查摘要，确认本次发送并理解可能的费用</el-checkbox>
      <template #footer><el-button @click="invalidatePreview">取消</el-button><el-button type="primary" :disabled="sending || !consent || !preview" @click="send">确认生成</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.monthly-page { max-width: 1240px; width: 100%; margin-inline: auto; gap: 28px; }
.report-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
h1 { font-size: 26px; font-weight: 650; margin: 7px 0 8px; letter-spacing: -.5px; }
h2 { font-size: 19px; font-weight: 600; line-height: 1.5; margin: 8px 0 16px; }
h3 { font-size: 15px; margin: 24px 0 10px; }
p { margin: 8px 0; line-height: 1.75; }
.eyebrow { font-size: 10px; font-weight: 650; letter-spacing: 2px; color: var(--bk-primary); margin: 0; display: flex; align-items: center; gap: 8px; }
.header-dot { color: var(--bk-border); margin: 0 12px; }.month-title { font-size: 19px; font-weight: 400; color: var(--bk-text-secondary); }
.muted { color: var(--bk-text-secondary); font-size: 13px; }.small { font-size: 12px; }
.report-workspace { display: grid; grid-template-columns: 196px minmax(0, 1fr); gap: 24px; align-items: start; }
.archive { padding: 18px 12px; position: sticky; top: 20px; }.archive-heading { padding: 0 6px 16px; font-size: 12px; font-weight: 600; display: grid; gap: 12px; }
.month-list { display: grid; gap: 5px; }.month-option { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 12px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: var(--bk-text-secondary); cursor: pointer; font: inherit; font-size: 12px; }
.month-option strong { font-size: 17px; font-weight: 500; font-variant-numeric: tabular-nums; margin-right: 5px; }.month-option:hover { background: var(--bk-surface-2); }.month-option.selected { color: var(--bk-primary); background: var(--bk-primary-soft); border-color: var(--bk-border); }.month-option:focus-visible { outline: 2px solid var(--bk-primary); outline-offset: 2px; }.month-option:disabled { cursor: wait; }
.month-status { font-size: 11px; display: flex; align-items: center; gap: 5px; }.month-status i { width: 4px; height: 4px; border-radius: 50%; background: var(--bk-text-placeholder); }.month-status.complete i { background: var(--bk-primary); }
.archive-note { font-size: 11px; line-height: 1.8; text-align: center; color: var(--bk-text-secondary); padding-top: 16px; margin-top: 16px; border-top: 1px solid var(--bk-border-light); }.archive-note .el-icon { margin-right: 4px; }
.report-main { min-width: 0; display: flex; flex-direction: column; gap: 20px; }.report-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 20px 24px; }.report-toolbar p { margin-bottom: 0; }
.state-chip { font-size: 11px; border-radius: 6px; padding: 4px 8px; background: var(--bk-surface-2); color: var(--bk-text-secondary); }.state-chip.complete { background: var(--bk-primary-soft); color: var(--bk-primary); }.state-chip.pending { background: var(--bk-accent-soft); color: var(--bk-button-accent); }
.summary-card { padding: 30px; border-top: 3px solid var(--bk-primary); background: linear-gradient(130deg, var(--bk-primary-soft), var(--bk-surface) 62%); }.summary-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.summary-text { font-size: 17px; line-height: 1.95; white-space: pre-wrap; overflow-wrap: anywhere; margin: 18px 0 24px; }.summary-meta { border-top: 1px solid var(--bk-border); padding-top: 14px; display: flex; justify-content: space-between; gap: 12px; color: var(--bk-text-secondary); font-size: 11px; overflow-wrap: anywhere; }
.metrics-panel { padding: 20px 24px; }.section-heading { padding: 6px 2px 0; }.section-heading h2 { margin-bottom: 14px; }.insights-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }.insights-grid.actions { grid-template-columns: 1fr; }.insight-card { padding: 22px; min-width: 0; }.insight-number { font-size: 22px; color: var(--bk-primary); opacity: .7; font-weight: 500; }.ai-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; }.fact-details { margin-top: 18px; border-top: 1px solid var(--bk-border-light); padding-top: 12px; }.fact-details summary { font-size: 12px; color: var(--bk-primary); cursor: pointer; }.fact-item { margin-top: 12px; background: var(--bk-surface-2); border-radius: 8px; padding: 12px; font-size: 12px; color: var(--bk-text-secondary); overflow-wrap: anywhere; }.fact-item strong { font-weight: 500; }.empty-insight { padding: 24px; }
.evidence-panel { padding: 22px 24px; }.evidence-heading { cursor: pointer; display: flex; justify-content: space-between; gap: 12px; font-size: 14px; }.evidence-heading .el-icon { margin-right: 8px; vertical-align: middle; }.evidence-content { padding-top: 16px; }.budget-row { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--bk-border-light); font-size: 13px; }.budget-row strong { font-weight: 500; }.report-footnote { display: flex; gap: 10px; color: var(--bk-text-secondary); padding: 0 4px; font-size: 12px; }.report-footnote > .el-icon { margin-top: 13px; flex: 0 0 auto; }
.notice { border: 1px solid var(--bk-border); border-radius: 12px; padding: 18px 22px; background: var(--bk-primary-soft); font-size: 13px; overflow-wrap: anywhere; }.notice--warning { background: var(--bk-accent-soft); }.notice p { color: var(--bk-text-secondary); }.generation-note { display: flex; gap: 14px; padding: 18px 22px; border-radius: 12px; background: var(--bk-primary-soft); font-size: 13px; }.generation-note p { color: var(--bk-text-secondary); margin-bottom: 0; }.pulse { margin-top: 6px; width: 9px; height: 9px; flex: 0 0 auto; border-radius: 50%; background: var(--bk-primary); animation: breathe 1.5s ease-in-out infinite; }.skeleton-panel { padding: 36px; }
.empty-report { min-height: 420px; padding: 60px 24px 36px; display: flex; flex-direction: column; align-items: center; text-align: center; justify-content: center; }.empty-report > p:not(.eyebrow) { color: var(--bk-text-secondary); }.empty-report h2 { font-size: 23px; margin: 14px 0 6px; }.empty-illustration { width: 70px; height: 82px; display: grid; place-items: center; border: 1px solid var(--bk-border); background: var(--bk-surface-2); border-radius: 14px; margin-bottom: 26px; position: relative; transform: rotate(-7deg); font-size: 36px; color: var(--bk-primary); }.empty-illustration > span { position: absolute; right: -15px; bottom: -5px; width: 33px; height: 33px; border-radius: 50%; background: var(--bk-primary-soft); display: grid; place-items: center; font-size: 18px; border: 3px solid var(--bk-surface); }.empty-features { display: flex; gap: 10px; flex-wrap: wrap; justify-content: center; margin: 16px 0; }.empty-features span { padding: 5px 12px; background: var(--bk-surface-2); border-radius: 20px; font-size: 12px; color: var(--bk-text-secondary); }
.page-end { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-size: 11px; color: var(--bk-text-secondary); }.preview-recipient { display: grid; gap: 7px; background: var(--bk-primary-soft); padding: 18px; border-radius: 10px; overflow-wrap: anywhere; }.preview-recipient strong { font-weight: 500; }.preview-recipient p { margin: 0; }.preview-json { border: 1px solid var(--bk-border); padding: 14px; border-radius: 10px; margin: 16px 0; }.preview-json summary { cursor: pointer; font-size: 13px; }pre { max-height: 35vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; } :deep(.el-checkbox) { white-space: normal; height: auto; } :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
@keyframes breathe { 50% { opacity: .3; } }@media (prefers-reduced-motion: reduce) { .pulse { animation: none; } }
@media (max-width: 1000px) { .report-workspace { grid-template-columns: 1fr; gap: 16px; }.archive { position: static; padding: 14px; }.archive-heading { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; }.archive-heading .el-select { width: 120px; }.month-list { display: flex; overflow-x: auto; padding-bottom: 4px; }.month-option { flex: 0 0 auto; gap: 14px; padding: 10px 12px; }.archive-note { display: none; } }
@media (max-width: 640px) { .monthly-page { gap: 20px; }.report-header { align-items: flex-start; }h1 { font-size: 23px; }.header-dot { display: none; }.month-title { display: block; font-size: 15px; margin-top: 8px; }.report-header > .el-button { margin-top: 20px; }.report-header > div > .muted { max-width: 230px; }.report-toolbar { padding: 18px; flex-wrap: wrap; }.report-toolbar > .el-button { width: 100%; }.summary-card { padding: 24px 20px; }.summary-heading { align-items: flex-start; flex-direction: column; }.summary-text { font-size: 16px; }.summary-meta { flex-direction: column; }.insights-grid { grid-template-columns: 1fr; }.metrics-panel, .evidence-panel { padding: 18px; }.empty-report { padding-inline: 16px; }.empty-report h2 { font-size: 20px; }.page-end { flex-wrap: wrap; } }
</style>
