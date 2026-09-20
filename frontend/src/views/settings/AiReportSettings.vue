<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { AiPreview, AiSettings } from '@/types/monthlyReport'
import { unwrapAi } from '@/utils/monthlyReport'
const api = window.electronAPI?.monthlyAI
const settings = ref<AiSettings | null>(null)
const form = reactive({ baseUrl: '', model: '', includeNames: false, apiKey: '' })
const busy = ref(false)
const error = ref('')
const notice = ref('')
const preview = ref<AiPreview | null>(null)
const previewVisible = ref(false)
const consentVisible = ref(false)
const consent = ref(false)
const dirty = computed(() => !settings.value || !!form.apiKey || form.baseUrl !== settings.value.baseUrl || form.model !== settings.value.model || form.includeNames !== settings.value.includeNames)
const host = computed(() => { try { return new URL(settings.value?.baseUrl ?? '').hostname } catch { return '尚未配置' } })
function apply(value: AiSettings) {
  settings.value = value
  Object.assign(form, { baseUrl: value.baseUrl, model: value.model, includeNames: value.includeNames, apiKey: '' })
}
async function perform(action: () => Promise<void>) {
  busy.value = true; error.value = ''; notice.value = ''
  try { await action() } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请重试' }
  finally { busy.value = false }
}
onMounted(() => { if (api) void perform(async () => apply(await unwrapAi(api.settings()))) })
onBeforeUnmount(() => { form.apiKey = '' })
function save() {
  if (!api) return
  const payload = { baseUrl: form.baseUrl, model: form.model, includeNames: form.includeNames, ...(form.apiKey ? { apiKey: form.apiKey } : {}) }
  form.apiKey = ''
  void perform(async () => {
    try { apply(await unwrapAi(api.save(payload))); preview.value = null; notice.value = '配置已保存，自动授权已关闭。' }
    finally { delete payload.apiKey }
  })
}
function showPreview() {
  if (api) void perform(async () => { preview.value = await unwrapAi(api.preview()); previewVisible.value = true })
}
function openConsent() { consent.value = false; consentVisible.value = true }
function toggleAuthorization(value: string | number | boolean) { if (value) openConsent(); else void revoke() }
function authorize() {
  if (!api || !settings.value || !consent.value || dirty.value) return
  const version = settings.value.configVersion
  void perform(async () => {
    apply(await unwrapAi(api.authorize({ configVersion: version, confirmed: true })))
    consentVisible.value = false
    notice.value = '已授权自动解读最近一个已结束月份；可随时关闭。'
  })
}
async function revoke(clear = false) {
  if (!api) return
  if (clear) {
    try { await ElMessageBox.confirm('清除已保存密钥并撤销自动授权？已发送请求无法保证从服务商撤回。', '清除密钥', { type: 'warning' }) }
    catch { return }
  }
  await perform(async () => {
    apply(await unwrapAi(clear ? api.clear() : api.revoke()))
    notice.value = '已停止后续发送；已发请求无法保证从服务商撤回。'
  })
}
function test() {
  if (!api) return
  void perform(async () => {
    await ElMessageBox.confirm('仅发送一条不含账单的测试消息，供应商仍可能收取费用。是否继续？', '测试连接')
    await unwrapAi(api.test()); notice.value = '连接成功。'
  })
}
</script>

<template>
  <section class="page page--comfortable page--settings quiet-controls ai-settings">
    <header><h1 class="page-head__title">AI 月报</h1><p class="page-head__sub">本地统计事实，由你决定是否发送给 AI。</p></header>
    <el-alert v-if="!api" title="请在 Electron 桌面端配置 AI；浏览器仍可查看本地月报。" type="info" :closable="false" />
    <template v-else>
      <el-alert v-if="settings && !settings.available" title="开发环境未配置内部任务通道，AI 作业已禁用。" type="warning" :closable="false" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
      <section class="surface panel">
        <h2>连接配置</h2>
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item label="OpenAI 兼容服务根地址（仅 HTTPS）"><el-input v-model="form.baseUrl" placeholder="https://api.example.com/v1" maxlength="500" autocomplete="off" /></el-form-item>
          <el-form-item label="模型"><el-input v-model="form.model" placeholder="供应商提供的模型名称" maxlength="200" autocomplete="off" /></el-form-item>
          <el-form-item :label="settings?.hasKey ? '替换 API Key（留空保留；更换服务地址后需重新输入）' : 'API Key'"><el-input v-model="form.apiKey" type="password" maxlength="4096" autocomplete="new-password" placeholder="密钥不会回显，提交后立即清空" /></el-form-item>
          <p class="muted">{{ settings?.persistentKey ? '密钥使用系统 safeStorage 加密，独立存放，不进入账本备份。' : '系统安全加密不可用：密钥仅限本次会话，退出后需重新输入。' }}</p>
          <el-checkbox v-model="form.includeNames">允许发送分类名称（默认关闭）</el-checkbox>
          <p class="muted">默认使用稳定分类代号；名称可能含隐私，开启后解读可能更具体。服务地址、模型或发送字段变化会撤销旧授权。</p>
          <div class="toolbar"><el-button type="primary" :loading="busy" @click="save">保存配置</el-button><el-button :disabled="busy || dirty || !settings?.hasKey" @click="test">测试连接（无账单）</el-button><el-button :disabled="!settings?.hasKey" @click="revoke(true)">清除密钥</el-button></div>
        </el-form>
      </section>
      <section class="surface panel">
        <div class="split-row"><h2>自动解读</h2><el-switch :model-value="settings?.automatic ?? false" :disabled="!settings?.automatic && (busy || dirty || !settings?.available || !settings?.hasKey)" active-text="已授权" inactive-text="关闭" @change="toggleAuthorization" /></div>
        <p>接收服务：{{ host }} · 模型：{{ settings?.model || '未配置' }}</p>
        <p>仅处理最近一个已结束月份；应用退出时不运行，下次启动补查。历史月份需手动操作。超时或中断不会自动重发，重试可能再次计费。</p>
        <p class="muted">发送月份、币种、收支汇总、分类汇总、笔数、比较值、预算与规则事实。不发送逐笔交易、明细日期、备注、账户名或余额、标签、生日、文件路径。</p>
        <el-button :disabled="busy || dirty || !settings?.available" @click="showPreview">预览最近月报的实际摘要</el-button>
        <el-button :disabled="!settings?.automatic" @click="revoke()">撤销授权并停止后续发送</el-button>
      </section>
    </template>
    <el-dialog v-model="previewVisible" title="实际发送摘要（仅本地预览）" width="min(720px, 90vw)"><pre v-if="preview">{{ JSON.stringify(preview.summary, null, 2) }}</pre><p v-else>最近已结束月份没有月报或流水；不会自动发送空月报。历史摘要可在月报页面预览。</p></el-dialog>
    <el-dialog v-model="consentVisible" title="授权自动发送月度摘要" width="560px">
      <p>接收服务：{{ host }} · 模型：{{ settings?.model }}</p>
      <p>发送收支、分类{{ settings?.includeNames ? '名称' : '代号' }}、频次、比较、预算及规则事实。供应商可能收取调用费用，数据将交由该供应商处理。</p>
      <p>可以随时撤销后续发送；已发送请求无法保证从供应商撤回。</p>
      <el-checkbox v-model="consent">我理解发送字段及可能的费用，授权自动解读</el-checkbox>
      <template #footer><el-button @click="consentVisible = false">取消</el-button><el-button type="primary" :disabled="!consent || dirty" :loading="busy" @click="authorize">确认授权</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.ai-settings { gap: 20px; }
.panel { padding: var(--bk-panel-padding); }
h2 { font-size: 18px; margin: 0 0 16px; }
p { line-height: 1.7; }
.muted { color: var(--bk-text-secondary); font-size: 13px; }
pre { max-height: 55vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
