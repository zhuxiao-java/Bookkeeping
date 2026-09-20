<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { monthlyReportApi } from '@/api/monthlyReport'
import type { AiPreview, AiSettings, MonthEntry, MonthlyDetail } from '@/types/monthlyReport'
import { aiError, currencyName, factEvidence, lastClosedMonth, monthRange, unwrapAi } from '@/utils/monthlyReport'
import MonthlyOverview from '@/components/monthly/MonthlyOverview.vue'
import CategoryDiagnosis from '@/components/monthly/CategoryDiagnosis.vue'
import MonthlyActions from '@/components/monthly/MonthlyActions.vue'
import MonthlyGoal from '@/components/monthly/MonthlyGoal.vue'
import { bus, TRANSACTION_CHANGED, ACCOUNT_CHANGED, CATEGORY_CHANGED, BUDGET_CHANGED } from '@/utils/bus'
const router = useRouter(), route = useRoute()
const api = window.electronAPI?.monthlyAI
const month = ref(lastClosedMonth())
const entries = ref<MonthEntry[]>([])
const report = ref<MonthlyDetail | null>(null)
const settings = ref<AiSettings | null>(null)
const loading = ref(false), generating = ref(false), sending = ref(false)
const error = ref(''), aiMessage = ref('')
const preview = ref<AiPreview | null>(null)
const previewVisible = ref(false), consent = ref(false)
let loadVersion = 0
let unsubscribe: (() => void) | undefined
let poll: ReturnType<typeof setInterval> | undefined
const factMap = computed(() => new Map(report.value?.snapshot.facts.map(f => [f.id, f]) ?? []))
const successfulAi = computed(() => report.value?.successfulAi ?? (report.value?.ai?.result ? report.value.ai : null))
const signals = computed(() => report.value?.snapshot.facts.filter(f => ['budget', 'growth', 'frequency', 'large'].includes(f.kind)) ?? [])
const aiState = computed(() => {
  if (report.value?.stale) return '账单已变化，当前解读基于旧统计'
  if (report.value?.ai?.status === 'running' || sending.value) return '生成中'
  if (report.value?.ai?.status === 'succeeded') return '解读完成'
  if (report.value?.ai?.status === 'failed' || report.value?.ai?.status === 'interrupted') return '待手动重试'
  if (!api) return '请在 Electron 桌面端使用 AI'
  if (!settings.value?.hasKey) return '待配置'
  if (!settings.value.available) return '内部任务通道未启用'
  return settings.value.automatic ? '已授权自动解读；历史或更新版本需单次授权' : '待授权（默认不发送）'
})
const receiver = computed(() => { try { return new URL(settings.value?.baseUrl ?? '').hostname } catch { return '' } })
const disabledMonth = (date: Date) => date >= new Date(new Date().getFullYear(), new Date().getMonth(), 1) || date.getFullYear() < 1900
const stateLabels: Record<string, string> = { not_generated: '未生成', local: '本地统计', running: '生成中', succeeded: 'AI 完成', failed: '待重试', interrupted: '已中断' }
async function load() {
  const sequence = ++loadVersion
  loading.value = true; error.value = ''
  try {
    let selected = typeof route.query.month === 'string' ? route.query.month : month.value
    const id = Number(route.query.bizId)
    let detail: MonthlyDetail | null = null
    if (Number.isSafeInteger(id) && id > 0) { detail = await monthlyReportApi.detail(id); selected = detail.month }
    if (!/^\d{4}-\d{2}$/.test(selected) || selected > lastClosedMonth() || selected < '1900-01' || Number(selected.slice(5)) < 1 || Number(selected.slice(5)) > 12) throw new Error('只能查看已结束月份的正式月报')
    const list = await monthlyReportApi.list(Number(selected.slice(0, 4)))
    const entry = list.find(e => e.month === selected)
    if (!detail && entry?.id) detail = await monthlyReportApi.detail(entry.id)
    if (sequence !== loadVersion) return
    month.value = selected; entries.value = list; report.value = detail
    if (api) settings.value = await unwrapAi(api.settings())
  } catch (e) { if (sequence === loadVersion) error.value = e instanceof Error ? e.message : '月报加载失败，请重试' }
  finally { if (sequence === loadVersion) loading.value = false }
}
function selectMonth(value: string) {
  if (!value || (value === month.value && !route.query.bizId)) return
  report.value = null; previewVisible.value = false; aiMessage.value = ''
  void router.replace({ query: { month: value } })
}
async function generate(refresh = false) {
  if (refresh) {
    try { await ElMessageBox.confirm('刷新本地统计会替换此月快照，旧 AI 解读不再适用；不会自动再次调用 AI。', '刷新本地月报') }
    catch { return }
  }
  const selected = month.value
  generating.value = true; error.value = ''
  try { await monthlyReportApi.generate(selected, refresh); await load() }
  catch { error.value = '生成失败，原有快照保持不变，请稍后重试。' }
  finally { generating.value = false }
}
function drill(currency: string, categoryId: number | null) {
  const [start, end] = monthRange(report.value?.month ?? month.value)
  void router.push({ path: '/transaction', query: { start, end, currency, type: 'expense', ...(categoryId != null ? { categoryId } : {}) } })
}
async function showPreview() {
  if (!api || !report.value) return
  aiMessage.value = ''; consent.value = false
  try {
    settings.value = await unwrapAi(api.settings())
    preview.value = await unwrapAi(api.preview(report.value.id)); previewVisible.value = true
  } catch (e) { aiMessage.value = e instanceof Error ? e.message : '摘要加载失败' }
}
async function send() {
  if (!api || !preview.value || !consent.value) return
  const captured = preview.value
  sending.value = true; aiMessage.value = ''; previewVisible.value = false
  try {
    const submitted = await unwrapAi(api.generate({ reportId: captured.id, snapshotVersion: captured.snapshotVersion, configVersion: captured.configVersion, confirmed: true, retry: !!report.value?.ai }))
    if (!submitted) ElMessage.info('未提交新请求：该版本已处理、正在处理或无可分析记录。')
  } catch (e) { aiMessage.value = e instanceof Error ? e.message : 'AI 请求失败，基础月报仍可使用。' }
  finally { sending.value = false; await load() }
}
async function refreshDetail() {
  const current = report.value
  if (!current || loading.value || generating.value) return
  try {
    const next = await monthlyReportApi.detail(current.id)
    if (report.value?.id === current.id) report.value = next
  } catch { /* 基础快照保持可读；用户可以显式重载。 */ }
}
watch(() => route.query, load)
onMounted(() => {
  void load()
  unsubscribe = api?.onUpdated(id => { if (report.value?.id === id) void refreshDetail() })
  poll = setInterval(() => { if (report.value?.ai?.status === 'running') void refreshDetail() }, 5000)
  for (const event of [TRANSACTION_CHANGED, ACCOUNT_CHANGED, CATEGORY_CHANGED, BUDGET_CHANGED]) bus.on(event, refreshDetail)
})
onBeforeUnmount(() => {
  loadVersion++; unsubscribe?.(); clearInterval(poll)
  for (const event of [TRANSACTION_CHANGED, ACCOUNT_CHANGED, CATEGORY_CHANGED, BUDGET_CHANGED]) bus.off(event, refreshDetail)
})
</script>

<template>
  <div class="page page--comfortable quiet-controls monthly-page">
    <header class="page-head page-head--actions"><div><h1 class="page-head__title">每月回望</h1><p class="page-head__sub">看清已记录的收支，为下个月留一点余地。</p></div><el-button @click="router.push('/settings?menu=ai')">AI 设置</el-button></header>
    <div class="toolbar">
      <el-date-picker :model-value="month" type="month" value-format="YYYY-MM" :clearable="false" :disabled="generating" :disabled-date="disabledMonth" aria-label="月报月份" @update:model-value="selectMonth" />
      <el-select :model-value="month" style="width: 240px" aria-label="本年度月报状态" @update:model-value="selectMonth"><el-option v-for="entry in entries" :key="entry.month" :value="entry.month" :label="`${entry.month} · ${stateLabels[entry.status] ?? entry.status}`" /></el-select>
      <el-button :loading="loading" @click="load">重新检查</el-button>
    </div>
    <p class="muted">正式月报仅包含已结束月份；当月数据请使用原有报表页。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section v-if="!report && !loading" class="surface panel"><el-empty description="此月尚未生成本地月报" /><el-button type="primary" :loading="generating" @click="generate()">生成本地统计（不发送 AI）</el-button></section>
    <template v-if="report">
      <div class="split-row"><span class="muted">{{ report.month }} · 快照 v{{ report.version }} · 规则 v{{ report.snapshot.ruleVersion }} · {{ report.generatedAt.replace('T', ' ') }}</span><el-button :loading="generating" @click="generate(true)">刷新本地统计</el-button></div>
      <el-alert v-if="report.stale" title="账单已变化，可重新生成。当前仍展示已保存快照，流水下钻展示最新账单，合计可能不同。" type="warning" :closable="false" />
      <el-alert v-if="!report.snapshot.count" title="本月没有已记录流水，不推送提醒，也不调用 AI。" type="info" :closable="false" />
      <section v-for="currency in report.snapshot.currencies" :key="`${report.id}:${currency.currency}`" class="surface panel">
        <MonthlyOverview :summary="currency" /><CategoryDiagnosis :summary="currency" @drill="drill" />
      </section>
      <section class="surface panel"><div class="split-row"><h2>预算执行 · 仅人民币消费</h2><el-button @click="router.push('/budget')">前往预算管理</el-button></div><p class="muted">手续费不计入分类预算；总预算与分类预算独立比较，绝不相加。</p>
        <p v-if="!report.snapshot.budgets.length">此月未设置预算。</p>
        <div v-for="b in report.snapshot.budgets" :key="b.id" class="budget-row"><span>{{ b.name }}</span><span>{{ b.used }} / {{ b.amount }} · {{ b.percentage == null ? '零预算，不计算比例' : `已用 ${b.percentage}%` }}</span><span v-if="Number(b.excess) > 0" class="over">超额 {{ b.excess }}</span></div>
      </section>
      <section v-if="signals.length" class="surface panel"><h2>值得关注的事项</h2><el-collapse><el-collapse-item v-for="f in signals" :key="f.id" :title="`${f.title} · ${currencyName(f.currency)}`" :name="f.id"><p>{{ factEvidence(f) }}</p><p>{{ f.suggestion }}</p><el-button text @click="drill(f.currency, f.categoryId)">查看对应流水</el-button></el-collapse-item></el-collapse></section>
      <MonthlyActions class="surface panel" :facts="report.snapshot.facts" @drill="drill" />
      <MonthlyGoal class="surface panel" :key="`${report.id}:${report.version}`" :snapshot="report.snapshot" />
      <section class="surface panel ai-section">
        <div class="split-row"><h2>AI 解读</h2><el-tag>{{ aiState }}</el-tag></div>
        <p class="muted">AI 仅作参考。事实数字以下方本地依据为准，建议不会自动执行。</p>
        <el-alert v-if="aiMessage || report.ai?.errorCode" :title="aiMessage || aiError(report.ai?.errorCode)" type="warning" :closable="false" />
        <template v-if="successfulAi?.result">
          <p v-if="successfulAi.id !== report.ai?.id" class="muted">以下保留本统计版本最近一次成功解读，当前重试状态见上方。</p>
          <p class="muted">服务 {{ successfulAi.result.provider }} · 模型 {{ successfulAi.result.model }} · {{ successfulAi.result.generatedAt }} · 统计 v{{ successfulAi.snapshotVersion }}</p>
          <p class="ai-text">{{ successfulAi.result.summary }}</p>
          <article v-for="(item, i) in [...successfulAi.result.observations, ...successfulAi.result.actions]" :key="i"><p class="ai-text">{{ item.text }}</p><template v-for="id in item.factIds" :key="id"><p v-if="factMap.get(id)" class="evidence">{{ factMap.get(id)!.title }} · {{ currencyName(factMap.get(id)!.currency) }}：{{ factEvidence(factMap.get(id)!) }}</p></template></article>
          <p class="muted ai-text">数据局限：{{ successfulAi.result.limitations }}</p>
        </template>
        <div class="toolbar"><el-button :disabled="!api || !settings?.available || !settings?.hasKey || !report.snapshot.count || report.stale || sending || report.ai?.status === 'running'" :loading="sending" @click="showPreview">{{ report.ai ? '预览摘要并手动重试' : '预览摘要并授权本次解读' }}</el-button><el-button text @click="router.push('/settings?menu=ai')">连接与授权设置</el-button></div>
      </section>
      <section class="muted"><h2>统计口径与局限</h2><p v-for="text in report.snapshot.limitations" :key="text">{{ text }}</p></section>
    </template>
    <el-dialog v-model="previewVisible" title="预览并授权本次发送" width="min(760px, 90vw)">
      <p>接收服务：{{ receiver }} · 模型：{{ settings?.model }} · {{ settings?.includeNames ? '包含分类名称' : '分类匿名代号' }}</p>
      <p>仅发送以下统计摘要，不发送逐笔交易、明细日期、备注、账户名/余额、标签、生日或路径。调用可能计费；手动重试可能再次产生费用。</p>
      <pre>{{ JSON.stringify(preview?.summary, null, 2) }}</pre>
      <el-checkbox v-model="consent">我已检查摘要，授权本次发送并理解可能的费用</el-checkbox>
      <p class="muted">已发送的请求无法保证从供应商撤回，可在设置中撤销后续发送。</p>
      <template #footer><el-button @click="previewVisible = false">取消</el-button><el-button type="primary" :disabled="!consent || !preview || preview.stale" @click="send">确认发送</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.monthly-page { gap: 18px; }
.panel { padding: var(--bk-panel-padding); }
h2 { font-size: 18px; margin: 12px 0 16px; }
p { line-height: 1.75; }
.muted, .evidence { color: var(--bk-text-secondary); font-size: 13px; }
.budget-row { display: flex; gap: 18px; flex-wrap: wrap; padding: 12px 0; border-top: 1px solid var(--bk-border-light); }
.budget-row > span:first-child { flex: 1; }
.over { color: var(--bk-expense-text); }
.ai-section { border-top: 3px solid var(--bk-primary); }
.ai-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.evidence { background: var(--bk-surface-2); padding: 12px; border-radius: 8px; }
pre { max-height: 42vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
