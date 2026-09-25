<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { monthlyAiApi } from '@/api/monthlyReport'
import type { AiSettings } from '@/types/monthlyReport'
import { aiFail } from '@/utils/monthlyReport'
const router = useRouter()
const settings = ref<AiSettings | null>(null)
const form = reactive({ baseUrl: '', model: '', includeNames: false, apiKey: '' })
const busy = ref(false)
const error = ref('')
const notice = ref('')
const revoking = ref(false)
let operation = 0
let disposed = false
const dirty = computed(() => !settings.value || !!form.apiKey || form.baseUrl !== settings.value.baseUrl || form.model !== settings.value.model || form.includeNames !== settings.value.includeNames)
function apply(value: AiSettings) {
  settings.value = value
  Object.assign(form, { baseUrl: value.baseUrl, model: value.model, includeNames: value.includeNames, apiKey: '' })
}
async function perform(action: (current: () => boolean) => Promise<void>, interrupt = false) {
  if (disposed || (busy.value && !interrupt)) return
  const sequence = ++operation
  const current = () => !disposed && sequence === operation
  busy.value = true; error.value = ''; notice.value = ''
  try { await action(current) } catch (e) { if (current()) error.value = aiFail(e, '操作失败，请重试') }
  finally { if (current()) busy.value = false }
}
function reload() {
  void perform(async current => {
    settings.value = null
    const value = await monthlyAiApi.settings()
    if (current()) apply(value)
  })
}
onMounted(reload)
onBeforeUnmount(() => { disposed = true; operation++; form.apiKey = '' })
function save() {
  if (busy.value || revoking.value) return
  const payload = { baseUrl: form.baseUrl, model: form.model, includeNames: form.includeNames, ...(form.apiKey ? { apiKey: form.apiKey } : {}) }
  form.apiKey = ''
  void perform(async current => {
    const value = await monthlyAiApi.save(payload)
    if (!current()) return
    apply(value)
    notice.value = value.hasKey ? '配置已保存。请到 AI 报告页面选择周期，预览并确认后生成。' : '配置已保存，请输入此服务的 API Key 后再使用。'
  })
}
async function revoke(clear = false) {
  if (revoking.value) return
  revoking.value = true
  try {
    if (clear) {
      try { await ElMessageBox.confirm('清除已保存密钥并停止当前调用？已发送请求无法保证从服务商撤回。', '清除密钥', { type: 'warning' }) }
      catch { return }
    }
    await perform(async current => {
      const value = await monthlyAiApi.revoke(clear)
      if (!current()) return
      apply(value); notice.value = clear ? '密钥已清除，当前调用已停止。' : '当前调用已停止，旧预览已失效；重新确认后仍可手动生成。'
    }, true)
  } finally { if (!disposed) revoking.value = false }
}
function test() {
  if (dirty.value || !settings.value?.available) return
  const version = settings.value.configVersion
  void perform(async current => {
    try { await ElMessageBox.confirm('仅发送一条不含账单的测试消息，供应商仍可能收取费用。是否继续？', '测试连接') }
    catch { return }
    if (!current()) return
    const succeeded = await monthlyAiApi.test({ configVersion: version, confirmed: true })
    if (!current()) return
    if (!succeeded) throw new Error('服务未返回有效测试响应，连接尚未验证。')
    notice.value = '连接成功。'
  })
}
</script>

<template>
  <section class="page page--comfortable page--settings quiet-controls ai-settings">
    <header class="settings-heading"><div><h1 class="page-head__title">AI 报告设置</h1><p class="page-head__sub">连接你的模型，每次生成都由你亲自确认。</p></div><el-button @click="router.push('/ai-report')">前往 AI 报告 →</el-button></header>
      <el-alert v-if="settings && !settings.available" title="尚未完成接口地址、模型或密钥配置，暂时无法发起 AI 解读。" type="warning" :closable="false" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button v-if="!settings" :disabled="busy" @click="reload">重新读取配置</el-button>
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
      <section class="surface panel">
        <h2>连接配置</h2>
        <el-form label-position="top" @submit.prevent="save">
          <el-form-item label="OpenAI 兼容服务根地址（仅 HTTPS）"><el-input v-model="form.baseUrl" :disabled="busy" placeholder="https://api.example.com/v1" maxlength="500" autocomplete="off" /></el-form-item>
          <el-form-item label="模型"><el-input v-model="form.model" :disabled="busy" placeholder="供应商提供的模型名称" maxlength="200" autocomplete="off" /></el-form-item>
          <el-form-item :label="settings?.hasKey ? '替换 API Key（留空保留；更换服务地址后需重新输入）' : 'API Key'"><el-input v-model="form.apiKey" :disabled="busy" type="password" maxlength="4096" autocomplete="new-password" placeholder="密钥不会回显，提交后立即清空" /></el-form-item>
          <p class="muted">密钥明文保存在本机数据库，不会回显。新导出与自动备份会剔除密钥，恢复后需重新配置。历史备份可能仍含旧密钥，请勿外传，必要时到供应商轮换密钥。</p>
          <el-checkbox v-model="form.includeNames" :disabled="busy">允许发送分类名称（默认关闭）</el-checkbox>
          <p class="muted">默认使用稳定分类代号；名称可能含隐私。服务地址、模型或发送字段变化会使旧预览失效，并停止当前调用。</p>
          <div class="toolbar"><el-button type="primary" :loading="busy" @click="save">保存配置</el-button><el-button :disabled="busy || dirty || !settings?.hasKey" @click="test">测试连接（无账单）</el-button><el-button :disabled="revoking || !settings?.hasKey" @click="revoke(true)">清除密钥</el-button></div>
        </el-form>
      </section>
      <section class="surface panel privacy-panel">
        <h2>每次发送，都由你决定</h2>
        <p>报告提供有数据依据的省钱建议，仅供参考，不承诺节省金额，也不会自动修改预算。</p>
        <p>选择已结束的周、月或自然年 → 预览实际摘要 → 确认生成。三类报告共用此配置，没有自动发送、后台排队或启动补生成。</p>
        <p class="muted">仅发送周期边界、币种、收支汇总、分类汇总、笔数、比较值、适用预算与规则事实；年报包含逐月汇总。不发送逐笔交易、明细日期、备注、账户名或余额、标签、生日、文件路径。</p>
        <p class="muted">模型调用可能计费。超时后请先查看已保存报告，再决定是否重新生成。已发出的请求无法保证从供应商撤回。</p>
        <el-button :disabled="revoking || !settings?.hasKey" @click="revoke()">停止当前发送</el-button>
      </section>
  </section>
</template>
<style scoped>
.ai-settings { gap: 20px; }
.settings-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.privacy-panel { border-top: 3px solid var(--bk-primary); }
.panel { padding: var(--bk-panel-padding); }
h2 { font-size: 18px; margin: 0 0 16px; }
p { line-height: 1.7; }
.muted { color: var(--bk-text-secondary); font-size: 13px; }
pre { max-height: 55vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
