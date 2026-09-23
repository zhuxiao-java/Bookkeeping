<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Coin, DataAnalysis, Moon, Plus, PriceTag, Reading, Setting, Wallet } from '@element-plus/icons-vue'
import { useSettingsStore, type ThemeMode } from '@/stores/settings'
import { formatAmount } from '@/utils/format'
import { PRESET_BACKGROUNDS, isPresetBackground } from '@/utils/constants'
import { compressImage, resolveBackgroundUrl, toRemoteBackground } from '@/utils/background'
import { imageApi, levelApi, backupApi, ApiError } from '@/api'
import { bus, TRANSACTION_CHANGED, ACCOUNT_CHANGED } from '@/utils/bus'
import CategoryManage from './settings/CategoryManage.vue'
import TagManage from './settings/TagManage.vue'
import AiReportSettings from './settings/AiReportSettings.vue'
import HelpPanel from '@/components/HelpPanel.vue'
import CsvImportDialog from '@/components/CsvImportDialog.vue'
import type { CsvImportResult } from '@/api'

/** 设置中心（需求文档 4.7）：个性化 / 分类管理 / 标签管理 / 数据管理 / 使用说明 / 关于 */
const settings = useSettingsStore()
const themeOptions: { value: ThemeMode; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'auto', label: '跟随系统' }
]

type MenuKey = 'preference' | 'category' | 'tag' | 'data' | 'help' | 'about' | 'ai'
const MENU_KEYS: MenuKey[] = ['preference', 'category', 'tag', 'data', 'help', 'about', 'ai']

const route = useRoute()
/** 支持 ?menu=xxx 直达（记账弹窗空分类引导、使用说明跳转用） */
const queryMenu = route.query.menu as MenuKey | undefined
const activeMenu = ref<MenuKey>(queryMenu && MENU_KEYS.includes(queryMenu) ? queryMenu : 'category')

// 已在本页时再点 ?menu=xxx（如说明面板的「管理分类」）只会变 query，需同步二级菜单
watch(
  () => route.query.menu,
  (value) => {
    if (typeof value === 'string' && MENU_KEYS.includes(value as MenuKey)) {
      activeMenu.value = value as MenuKey
    }
  }
)

const menus: { key: MenuKey; label: string; icon: typeof Setting }[] = [
  { key: 'preference', label: '个性化', icon: Moon },
  { key: 'category', label: '分类管理', icon: PriceTag },
  { key: 'tag', label: '标签管理', icon: Coin },
  { key: 'data', label: '数据管理', icon: DataAnalysis },
  { key: 'ai', label: 'AI 月报', icon: Reading },
  { key: 'help', label: '使用说明', icon: Reading },
  { key: 'about', label: '关于', icon: Wallet }
]

// —— 快速记账全局快捷键自定义（EL-06）——
const DEFAULT_SHORTCUT = 'CommandOrControl+Shift+B'
/** 捕捉模式：监听一次按键组合并转成 accelerator */
const capturing = ref(false)
let captureCleanup: (() => void) | undefined

/** accelerator → 人类可读（⌘/Ctrl + Shift + B） */
const shortcutDisplay = computed(() =>
  settings.quickRecordShortcut
    .split('+')
    .map((p) => (p === 'CommandOrControl' ? '⌘/Ctrl' : p))
    .join(' + ')
)

/**
 * 键盘事件 → Electron accelerator。要求含 Cmd/Ctrl 或 Alt 修饰键，
 * 避免把普通字母/功能键注册成全局快捷键而劫持系统输入；不支持的组合返回 null。
 */
function eventToAccelerator(e: KeyboardEvent): string | null {
  const key = e.key
  // 仅按下修饰键本身：等待主键
  if (['Shift', 'Alt', 'Control', 'Meta', 'OS'].includes(key)) return null
  const mods: string[] = []
  if (e.metaKey || e.ctrlKey) mods.push('CommandOrControl')
  if (e.altKey) mods.push('Alt')
  if (e.shiftKey) mods.push('Shift')
  // 必须含 CommandOrControl 或 Alt；Shift 不能作为唯一修饰键
  if (!mods.includes('CommandOrControl') && !mods.includes('Alt')) return null
  let main: string | null = null
  if (/^[a-zA-Z]$/.test(key)) main = key.toUpperCase()
  else if (/^[0-9]$/.test(key)) main = key
  else if (/^F([1-9]|1[0-9]|2[0-4])$/.test(key)) main = key.toUpperCase()
  else {
    const named: Record<string, string> = {
      ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right',
      ' ': 'Space', Enter: 'Enter', Tab: 'Tab',
      '+': 'Plus', '=': 'Plus', '-': '-', ',': ',', '.': '.', '/': '/', '\\': '\\'
    }
    main = named[key] ?? null
  }
  if (!main) return null
  return [...mods, main].join('+')
}

function stopCapture() {
  capturing.value = false
  captureCleanup?.()
  captureCleanup = undefined
}

/** 应用新快捷键：先让主进程重注册，成功才落库；失败提示并保留原值 */
async function applyShortcut(accel: string): Promise<boolean> {
  const api = window.electronAPI
  // Electron 环境：全局快捷键需主进程注册；注册失败（被占用）则不落库
  if (api?.setQuickRecordShortcut) {
    const res = await api
      .setQuickRecordShortcut(accel)
      .catch(() => ({ ok: false, reason: 'ipc-error' }))
    if (!res.ok) {
      ElMessage.error('该快捷键注册失败（可能已被系统或其他应用占用），请换一个组合')
      return false
    }
  }
  // 非 Electron（浏览器 dev）无法注册全局快捷键，仅本地保存
  settings.update({ quickRecordShortcut: accel })
  ElMessage.success('快捷键已更新')
  return true
}

function startCapture() {
  if (capturing.value) return
  capturing.value = true
  const onKey = (e: KeyboardEvent) => {
    // 捕捉期拦截全部按键，避免误触其他快捷键（如顶栏 Cmd/Ctrl+N）或输入
    e.preventDefault()
    e.stopPropagation()
    if (e.key === 'Escape') {
      stopCapture()
      return
    }
    const accel = eventToAccelerator(e)
    if (!accel) return // 仅按下修饰键或不支持的键，继续等待
    stopCapture()
    void applyShortcut(accel)
  }
  window.addEventListener('keydown', onKey, { capture: true })
  captureCleanup = () => window.removeEventListener('keydown', onKey, { capture: true })
}

async function resetShortcut() {
  if (settings.quickRecordShortcut === DEFAULT_SHORTCUT) return
  await applyShortcut(DEFAULT_SHORTCUT)
}

onBeforeUnmount(() => stopCapture())
watch(activeMenu, () => stopCapture())

/** 当前背景是否为自定义图（非内置预设） */
const isCustomBg = computed(
  () => !!settings.backgroundImage && !isPresetBackground(settings.backgroundImage)
)

/** 金额小数位实时示例（随所选位数变化，直观展示效果） */
const decimalExample = computed(() => `¥${formatAmount(1234.5, settings.decimalPlaces)}`)

const fileInput = ref<HTMLInputElement>()
const compressing = ref(false)

/** 选中背景（'' 表示恢复纯色），写入失败时提示用户 */
function selectBg(value: string) {
  if (!settings.update({ backgroundImage: value })) {
    ElMessage.warning('本地存储已满，背景设置仅本次生效')
  }
}

async function onPickFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 立即清空，保证再次选择同一文件仍能触发 change
  input.value = ''
  if (!file) return

  compressing.value = true
  try {
    const blob = await compressImage(file)
    const filename = await imageApi.uploadBackground(blob)
    selectBg(toRemoteBackground(filename))
  } catch (err) {
    // 接口失败已由 axios 拦截器统一提示，这里只处理压缩等非接口错误
    if (!(err instanceof ApiError)) {
      ElMessage.error(err instanceof Error ? err.message : '图片处理失败')
    }
  } finally {
    compressing.value = false
  }
}

/** 生日（存于后端单用户行，用于生日贺卡）；等级接口未就绪时静默降级 */
const birthday = ref<string>('')
onMounted(async () => {
  try {
    const info = await levelApi.current()
    birthday.value = info?.birthday ?? ''
  } catch {
    // silent：接口不可用时保留空值
  }
})

async function onBirthdayChange(value: string | null) {
  const next = value ?? ''
  try {
    await levelApi.setBirthday(next)
    birthday.value = next
    ElMessage.success(next ? '生日已保存，届时会送上贺卡' : '已清除生日')
  } catch {
    // 失败已由拦截器提示
  }
}

/** 数据管理：整库备份/恢复 + CSV 导入导出（后端 /backup/**，GAP-09） */
const dbFileInput = ref<HTMLInputElement>()
const backuping = ref(false)
const restoring = ref(false)
const exportingTx = ref(false)
const exportingAcc = ref(false)
/** CSV 导入向导（NEW-11）：选择/预览/结果三步在弹窗内完成 */
const csvDialogVisible = ref(false)

/** 由后端解析数据目录，避免将开发目录或 Electron 配置目录误当作账本位置。 */
const storagePath = ref('')
const storagePathLoading = ref(false)
const storagePathError = ref(false)
const copyingStoragePath = ref(false)

async function loadStoragePath() {
  if (storagePathLoading.value) return
  storagePathLoading.value = true
  storagePathError.value = false
  storagePath.value = ''
  try {
    storagePath.value = await backupApi.storagePath()
  } catch {
    storagePathError.value = true
  } finally {
    storagePathLoading.value = false
  }
}

async function copyStoragePath() {
  if (!storagePath.value || copyingStoragePath.value) return
  copyingStoragePath.value = true
  try {
    await navigator.clipboard.writeText(storagePath.value)
    ElMessage.success('存储路径已复制')
  } catch {
    ElMessage.warning('无法自动复制，请选中路径后手动复制')
  } finally {
    copyingStoragePath.value = false
  }
}

watch(activeMenu, (menu) => {
  if (menu === 'data') void loadStoragePath()
}, { immediate: true })

/** 导出类：走裸 axios blob 下载，失败不经过拦截器，需自行提示 */
async function runExport(flag: typeof backuping, fn: () => Promise<void>, okText: string) {
  flag.value = true
  try {
    await fn()
    ElMessage.success(okText)
  } catch {
    ElMessage.error('导出失败，请确认后端服务正常')
  } finally {
    flag.value = false
  }
}

function onExportDb() {
  return runExport(backuping, () => backupApi.exportDb(), '完整备份已导出（不含 AI 密钥，旧发送确认失效）')
}
function onExportTx() {
  return runExport(exportingTx, () => backupApi.exportTransactionsCsv(), '流水 CSV 已导出')
}
function onExportAcc() {
  return runExport(exportingAcc, () => backupApi.exportAccountsCsv(), '账户 CSV 已导出')
}

/** 从 .db 备份恢复：二次确认 → 上传覆盖 → 触发 Electron 重启后端 → 刷新前端 */
async function onPickDb(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    await ElMessageBox.confirm(
      '恢复将覆盖当前全部数据并停止当前 AI 调用，完成后需重启后端。AI 密钥会清除，旧发送确认失效，需重新配置。建议先导出当前数据作为备份。确定继续？',
      '确认恢复',
      { type: 'warning', confirmButtonText: '覆盖并恢复', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  restoring.value = true
  try {
    const res = await backupApi.importDb(file)
    if (res?.restartRequired && window.electronAPI?.restartBackend) {
      ElMessage.info('数据已恢复，正在重启后端…')
      const r = await window.electronAPI.restartBackend()
      if (r.ok) {
        ElMessage.success('后端已重启，正在刷新界面')
        setTimeout(() => window.location.reload(), 800)
      } else {
        ElMessage.warning('后端自动重启失败，请手动重启应用以加载恢复的数据')
      }
    } else {
      ElMessage.success('数据已恢复，请手动重启应用以加载新数据')
    }
  } catch {
    // 失败已由拦截器提示
  } finally {
    restoring.value = false
  }
}

/** CSV 导入向导完成后：有新增则广播变更事件刷新各页（NEW-11） */
function onCsvImported(res: CsvImportResult) {
  if (res.imported > 0) {
    bus.emit(TRANSACTION_CHANGED)
    bus.emit(ACCOUNT_CHANGED)
  }
}
</script>

<template>
  <div class="settings">
    <!-- 二级菜单 -->
    <aside class="settings__menu" data-guide="st-menu">
      <div class="settings__menu-title">设置</div>
      <button
        v-for="m in menus"
        type="button"
        :key="m.key"
        class="settings__menu-item"
        :class="{ 'is-active': activeMenu === m.key }"
        :aria-current="activeMenu === m.key ? 'page' : undefined"
        :data-guide="'st-menu-' + m.key"
        @click="activeMenu = m.key"
      >
        <el-icon><component :is="m.icon" /></el-icon>
        <span>{{ m.label }}</span>
      </button>
    </aside>

    <!-- 内容区 -->
    <div class="settings__body">
      <CategoryManage v-show="activeMenu === 'category'" />
      <TagManage v-show="activeMenu === 'tag'" />
      <AiReportSettings v-if="activeMenu === 'ai'" />

      <div v-show="activeMenu === 'preference'" class="page page--comfortable page--settings quiet-controls preferences">
        <header class="page-head">
          <h1 class="page-head__title">个性化</h1>
          <p class="page-head__sub">选一个喜欢的外观，按自己的习惯记账。</p>
        </header>

        <section class="preference-section" aria-labelledby="appearance-heading">
          <h2 id="appearance-heading">外观与显示</h2>
          <div class="surface preference-surface">
            <fieldset class="theme-picker">
              <legend class="preference-label">界面主题</legend>
              <p id="theme-hint" class="preference-hint">喜欢明亮，或偏爱深色，也可以跟随系统。</p>
              <div class="theme-options">
                <label v-for="option in themeOptions" :key="option.value" class="theme-option">
                  <input
                    class="preference-radio"
                    type="radio"
                    name="appearance-theme"
                    :value="option.value"
                    :checked="settings.theme === option.value"
                    aria-describedby="theme-hint"
                    @change="settings.update({ theme: option.value })"
                  />
                  <span class="theme-option__body">
                    <span class="theme-preview" aria-hidden="true">
                      <span
                        v-for="tone in option.value === 'auto' ? ['light', 'dark'] : [option.value]"
                        :key="tone"
                        class="theme-preview__scene"
                        :class="[`theme-preview__scene--${tone}`, { 'is-split': option.value === 'auto' && tone === 'dark' }]"
                      >
                        <span class="theme-preview__sidebar"><i /><i /><i /></span>
                        <span class="theme-preview__content">
                          <i class="theme-preview__heading" />
                          <i class="theme-preview__balance" />
                          <span class="theme-preview__tiles"><i /><i /></span>
                          <i class="theme-preview__line" />
                        </span>
                      </span>
                    </span>
                    <span class="theme-option__caption">
                      {{ option.label }}
                      <span class="selection-mark" aria-hidden="true"><el-icon><Check /></el-icon></span>
                    </span>
                  </span>
                </label>
              </div>
            </fieldset>

            <div class="split-row preference-row">
              <div class="preference-copy">
                <h3 id="decimal-label">金额显示</h3>
                <p>选择金额保留的小数位数</p>
              </div>
              <div class="preference-control">
                <div class="decimal-options" role="radiogroup" aria-labelledby="decimal-label">
                  <label v-for="digits in [0, 1, 2]" :key="digits" class="decimal-option">
                    <input
                      class="preference-radio"
                      type="radio"
                      name="amount-decimals"
                      :value="digits"
                      :checked="settings.decimalPlaces === digits"
                      @change="settings.update({ decimalPlaces: digits })"
                    />
                    <span>{{ digits }} 位</span>
                  </label>
                </div>
                <span class="amount-example" aria-live="polite">示例 {{ decimalExample }}</span>
              </div>
            </div>

            <div class="preference-block" role="group" aria-labelledby="background-label">
              <div class="preference-copy">
                <h3 id="background-label">背景图片</h3>
                <p>给记账空间添一点自己的色彩</p>
              </div>
              <div class="bg-picker">
                <button type="button" class="bg-opt" :class="{ 'is-active': !settings.backgroundImage }" :aria-pressed="!settings.backgroundImage" @click="selectBg('')">
                  <span class="bg-opt__thumb bg-opt__thumb--plain"><span /></span>
                  <span class="bg-opt__name">纯色</span>
                  <span class="selection-mark" aria-hidden="true"><el-icon><Check /></el-icon></span>
                </button>
                <button
                  v-for="p in PRESET_BACKGROUNDS"
                  :key="p.id"
                  type="button"
                  class="bg-opt"
                  :class="{ 'is-active': settings.backgroundImage === p.url }"
                  :aria-pressed="settings.backgroundImage === p.url"
                  :title="`${p.name}（${p.hint}主题推荐）`"
                  @click="selectBg(p.url)"
                >
                  <span class="bg-opt__thumb"><img :src="resolveBackgroundUrl(p.url)" alt="" /></span>
                  <span class="bg-opt__name">{{ p.name }}</span>
                  <span class="selection-mark" aria-hidden="true"><el-icon><Check /></el-icon></span>
                </button>
                <button
                  type="button"
                  class="bg-opt"
                  :class="{ 'is-active': isCustomBg, 'is-busy': compressing }"
                  :aria-pressed="isCustomBg"
                  :aria-busy="compressing"
                  :disabled="compressing"
                  @click="fileInput?.click()"
                >
                  <span class="bg-opt__thumb bg-opt__thumb--custom">
                    <img v-if="isCustomBg" :src="resolveBackgroundUrl(settings.backgroundImage)" alt="" />
                    <el-icon v-else><Plus /></el-icon>
                  </span>
                  <span class="bg-opt__name">{{ compressing ? '处理中…' : '自定义' }}</span>
                  <span class="selection-mark" aria-hidden="true"><el-icon><Check /></el-icon></span>
                </button>
                <input ref="fileInput" type="file" accept="image/*" class="bg-picker__file" @change="onPickFile" />
              </div>
              <p class="preference-hint">自定义图片会自动压缩，保存在本机。</p>
            </div>

            <div v-if="settings.backgroundImage" class="split-row preference-row">
              <div class="preference-copy">
                <h3 id="mask-label">背景淡化</h3>
                <p>数值越高，内容越清晰</p>
              </div>
              <div class="mask-control">
                <el-slider
                  class="bg-mask"
                  aria-label="背景淡化"
                  :model-value="settings.backgroundMask"
                  :min="0"
                  :max="90"
                  :step="5"
                  @update:model-value="settings.update({ backgroundMask: Number($event) })"
                />
                <span>{{ settings.backgroundMask }}%</span>
              </div>
            </div>
          </div>
        </section>

        <section class="preference-section" aria-labelledby="interaction-heading">
          <h2 id="interaction-heading">操作习惯</h2>
          <div class="surface preference-surface">
            <div class="split-row preference-row">
              <div class="preference-copy">
                <h3 id="screensaver-label">自动屏保</h3>
                <p>闲置时展示背景，点击或按键即可返回</p>
              </div>
              <el-select
                class="preference-select"
                aria-label="自动屏保"
                :model-value="settings.screensaverMinutes"
                @update:model-value="settings.update({ screensaverMinutes: Number($event) })"
              >
                <el-option v-for="minutes in [0, 1, 3, 5, 10]" :key="minutes" :value="minutes" :label="minutes === 0 ? '关闭' : `${minutes} 分钟后`" />
              </el-select>
            </div>
            <div class="split-row preference-row preference-row--shortcut">
              <div class="preference-copy">
                <h3>记账快捷键</h3>
                <p>桌面端可随时唤起快速记账，即使应用在后台</p>
              </div>
              <div class="preference-control shortcut-control">
                <div class="shortcut-row">
                  <div class="shortcut-display" :class="{ 'is-capturing': capturing }" aria-live="polite">
                    <span v-if="capturing">按下新组合，Esc 取消</span>
                    <template v-else>
                      <template v-for="(key, index) in shortcutDisplay.split(' + ')" :key="index">
                        <span v-if="index" class="shortcut-plus" aria-hidden="true">+</span>
                        <kbd>{{ key }}</kbd>
                      </template>
                    </template>
                  </div>
                  <el-button v-if="!capturing" text @click="startCapture">修改</el-button>
                  <el-button v-else text @click="stopCapture">取消</el-button>
                </div>
                <el-button v-if="!capturing && settings.quickRecordShortcut !== DEFAULT_SHORTCUT" text size="small" @click="resetShortcut">恢复默认</el-button>
                <span v-if="capturing" class="preference-hint">需包含 ⌘/Ctrl 或 Alt</span>
              </div>
            </div>
          </div>
        </section>

        <section class="preference-section" aria-labelledby="personal-heading">
          <h2 id="personal-heading">关于你</h2>
          <div class="surface preference-surface">
            <div class="split-row preference-row">
              <div class="preference-copy">
                <h3>生日</h3>
                <p>留一个日期，每年送你一张生日贺卡</p>
              </div>
              <el-date-picker
                class="preference-birthday"
                aria-label="生日"
                :model-value="birthday || undefined"
                type="date"
                placeholder="选择生日（可选）"
                value-format="YYYY-MM-DD"
                :clearable="true"
                @update:model-value="onBirthdayChange($event as string | null)"
              />
            </div>
          </div>
        </section>
      </div>

      <div v-show="activeMenu === 'data'" class="page page--comfortable page--settings">
        <header class="page-head">
          <h1 class="page-head__title">数据管理</h1>
          <p class="page-head__sub">给账本留一份备份，让每一笔记录都安心。</p>
        </header>
        <section class="page-section" aria-labelledby="data-storage-heading">
          <h2 id="data-storage-heading" class="section-heading">存储位置</h2>
          <div class="surface">
            <div class="split-row data-row" :aria-busy="storagePathLoading">
              <div class="row-copy">
                <h3 class="row-copy__title">数据存储路径</h3>
                <p class="row-copy__desc">账本数据库、自动备份和上传图片的保存目录。</p>
                <p v-if="storagePathLoading" class="row-copy__desc" role="status">正在读取存储路径…</p>
                <p v-else-if="storagePathError" class="row-copy__desc" role="status">暂时无法读取存储路径，请确认后端服务已启动后重试。</p>
                <code v-else-if="storagePath" class="storage-path">{{ storagePath }}</code>
              </div>
              <el-button v-if="storagePathError" @click="loadStoragePath">重新读取</el-button>
              <el-button v-else :disabled="!storagePath || storagePathLoading" :loading="copyingStoragePath" @click="copyStoragePath">复制路径</el-button>
            </div>
          </div>
        </section>
        <section class="page-section" aria-labelledby="data-backup-heading">
          <h2 id="data-backup-heading" class="section-heading">备份与恢复</h2>
          <div class="surface">
            <div class="split-row data-row">
              <div class="row-copy">
                <h3 class="row-copy__title">完整备份</h3>
                <p class="row-copy__desc">将账户、流水、分类、预算和月报保存为 SQLite 快照（.db）。新导出与自动备份不含 AI 密钥，旧发送确认失效；历史备份可能仍含旧密钥，请勿外传，必要时到供应商轮换密钥。</p>
              </div>
              <el-button :loading="backuping" @click="onExportDb">导出完整备份</el-button>
            </div>
            <div class="surface-block">
              <div class="data-row">
                <div class="row-copy">
                  <h3 class="row-copy__title">从备份恢复</h3>
                  <p class="row-copy__desc">选择已有的 .db 备份，恢复到当时的账本。</p>
                </div>
                <el-button :loading="restoring" @click="dbFileInput?.click()">选择备份文件</el-button>
                <input ref="dbFileInput" type="file" accept=".db" class="bg-picker__file" @change="onPickDb" />
              </div>
              <el-alert
                class="data-alert"
                type="warning"
                :closable="false"
                show-icon
                title="恢复会覆盖当前数据并停止当前 AI 调用，需重启后端、重新配置 AI 密钥。恢复前安全备份同样清除密钥并使旧发送确认失效；已生成报告会保留，已有历史备份不会自动清理。"
              />
            </div>
          </div>
        </section>
        <section class="page-section" aria-labelledby="data-csv-heading">
          <h2 id="data-csv-heading" class="section-heading">CSV 导入与导出</h2>
          <div class="surface">
            <div class="split-row data-row">
              <div class="row-copy">
                <h3 class="row-copy__title">导出为表格</h3>
                <p class="row-copy__desc">带 BOM 的 CSV 格式，可直接使用 Excel 打开。</p>
              </div>
              <div class="toolbar">
                <el-button :loading="exportingTx" @click="onExportTx">导出流水</el-button>
                <el-button :loading="exportingAcc" @click="onExportAcc">导出账户</el-button>
              </div>
            </div>
            <div class="split-row data-row">
              <div class="row-copy">
                <h3 class="row-copy__title">导入流水</h3>
                <p class="row-copy__desc">按账户名与分类名匹配已有数据，仅新增、不覆盖；导入前可预览校验结果。</p>
              </div>
              <el-button type="primary" @click="csvDialogVisible = true">导入流水 CSV</el-button>
            </div>
          </div>
          <CsvImportDialog v-model="csvDialogVisible" @imported="onCsvImported" />
        </section>
      </div>

      <!-- 使用说明：与顶栏「?」抽屉共用同一面板组件 -->
      <div v-show="activeMenu === 'help'" class="page page--comfortable page--settings">
        <header class="page-head">
          <h1 class="page-head__title">使用说明</h1>
          <p class="page-head__sub">从第一笔记账开始，慢慢熟悉你的账本。</p>
        </header>
        <HelpPanel />
      </div>

      <div v-show="activeMenu === 'about'" class="page page--comfortable page--settings">
        <header class="page-head">
          <h1 class="page-head__title">关于</h1>
          <p class="page-head__sub">温暖理财，从一笔开始。</p>
        </header>
        <section class="page-section" aria-labelledby="about-app-heading">
          <h2 id="about-app-heading" class="section-heading">你的本地账本</h2>
          <dl class="surface about-info">
            <div class="split-row"><dt>应用名称</dt><dd>记账本</dd></div>
            <div class="split-row"><dt>版本</dt><dd>1.0.0</dd></div>
            <div class="split-row"><dt>数据存储</dt><dd>本地 SQLite（离线可用）</dd></div>
          </dl>
        </section>
        <section class="page-section" aria-labelledby="about-tech-heading">
          <h2 id="about-tech-heading" class="section-heading">技术信息</h2>
          <dl class="surface about-info">
            <div class="split-row"><dt>前端技术</dt><dd>Electron + Vue 3 + Element Plus + ECharts</dd></div>
            <div class="split-row"><dt>后端技术</dt><dd>Java 17 + Spring Boot + SQLite</dd></div>
          </dl>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  gap: 24px;
  height: 100%;
  min-height: 0;
}

.settings__menu {
  width: 156px;
  flex-shrink: 0;
  background: transparent;
  border-radius: var(--bk-radius-lg);
  padding: 6px 0;
  align-self: flex-start;
}

.settings__menu-title {
  color: var(--bk-text-secondary);
  font-size: 13px;
  padding: 4px 12px 10px;
}

.settings__menu-item {
  display: flex;
  width: 100%;
  border: 0;
  background: transparent;
  text-align: left;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--bk-radius-md);
  cursor: pointer;
  color: var(--bk-text-regular);
  font-size: 14px;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.settings__menu-item:hover {
  background: var(--el-fill-color);
}

.settings__menu-item.is-active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.settings__body {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  container: settings-body / inline-size;
}

.data-row { display: flex; align-items: center; flex-wrap: wrap; gap: 16px 24px; }
.data-row > .row-copy { flex: 1 1 260px; }
.data-row > .el-button { flex-shrink: 0; }
.storage-path {
  display: block;
  margin-top: 12px;
  padding: 12px 16px;
  border-radius: var(--bk-control-radius);
  background: var(--bk-surface-2);
  color: var(--bk-text-regular);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  user-select: text;
  cursor: text;
}
.about-info { margin: 0; }
.about-info dt { font-weight: 550; color: var(--bk-text); }
.about-info dd { margin: 0; color: var(--bk-text-secondary); overflow-wrap: anywhere; }

/* 个性化以设置列表呈现，独立于其他管理页的表单样式。 */
.preferences {
  gap: var(--bk-page-gap);
}

.preference-hint,
.preference-copy p {
  margin: 5px 0 0;
  color: var(--bk-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.preference-section h2 {
  margin: 0 0 var(--bk-section-gap) 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--bk-text-secondary);
}

.preference-copy { min-width: 0; }
.preference-copy h3,
.preference-label {
  margin: 0;
  color: var(--bk-text);
  font-size: 14px;
  font-weight: 550;
}

.preference-control {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  flex-shrink: 0;
}

.preference-block { padding: var(--bk-row-padding) 0; }

.theme-picker {
  min-width: 0;
  margin: 0;
  padding: var(--bk-row-padding) 0;
  border: 0;
}

.theme-picker legend {
  float: left;
  width: 100%;
  padding: 0;
  margin-bottom: 4px;
}

.theme-picker > p { clear: both; }
.theme-options {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 16px;
}

.theme-option,
.decimal-option { position: relative; cursor: pointer; min-width: 0; }

/* 保留原生单选语义与方向键操作，仅隐藏输入框外观。 */
.preference-radio {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  clip-path: inset(50%);
  overflow: hidden;
  white-space: nowrap;
}

.theme-option__body {
  display: block;
  padding: 6px;
  border: 1px solid transparent;
  border-radius: 14px;
  transition: border-color 0.16s ease, background-color 0.16s ease;
}

.theme-option:hover .theme-option__body { background: var(--bk-surface-2); }
.theme-option input:checked + .theme-option__body { border-color: var(--bk-button-primary); }
.theme-option input:checked + .theme-option__body .selection-mark { opacity: 1; }
.theme-option input:focus-visible + .theme-option__body,
.decimal-option input:focus-visible + span,
.bg-opt:focus-visible {
  outline: 2px solid var(--bk-button-primary);
  outline-offset: 3px;
}

.theme-option__caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  padding: 10px 4px 3px;
  font-size: 13px;
  color: var(--bk-text-regular);
}

.selection-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--bk-primary-soft);
  color: var(--bk-button-primary);
  opacity: 0;
}

/* 缩略预览使用固定配色，在任何当前主题下都展示目标外观。 */
.theme-preview {
  display: block;
  position: relative;
  height: 102px;
  overflow: hidden;
  border-radius: 9px;
}

.theme-preview__scene {
  --preview-bg: #f6f4ef;
  --preview-panel: #ffffff;
  --preview-line: #e6e1d8;
  --preview-accent: #bcd7ce;
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-columns: 24% 1fr;
  gap: 10px;
  padding: 12px;
  background: var(--preview-bg);
}

.theme-preview__scene--dark {
  --preview-bg: #14181a;
  --preview-panel: #262e30;
  --preview-line: #414c4a;
  --preview-accent: #3b8066;
}

.theme-preview__scene.is-split { clip-path: inset(0 0 0 50%); }
.theme-preview__sidebar {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 12px 6px;
  border-radius: 5px;
  background: var(--preview-panel);
}

.theme-preview i { display: block; border-radius: 3px; }
.theme-preview__sidebar i { height: 4px; background: var(--preview-line); }
.theme-preview__sidebar i:first-child { background: var(--preview-accent); }
.theme-preview__content { display: flex; flex-direction: column; gap: 7px; padding-top: 4px; }
.theme-preview__heading { width: 38%; height: 5px; background: var(--preview-line); }
.theme-preview__balance { width: 60%; height: 11px; background: var(--preview-accent); }
.theme-preview__tiles { display: flex; gap: 6px; }
.theme-preview__tiles i { flex: 1; height: 24px; background: var(--preview-panel); }
.theme-preview__line { width: 80%; height: 4px; background: var(--preview-line); }

.decimal-options {
  display: inline-flex;
  gap: 3px;
  padding: 3px;
  border-radius: 10px;
  background: var(--bk-surface-2);
}

.decimal-option span {
  display: block;
  min-width: 50px;
  min-height: calc(var(--bk-control-height) - 6px);
  box-sizing: border-box;
  padding: 4px;
  line-height: 22px;
  border-radius: 7px;
  text-align: center;
  color: var(--bk-text-secondary);
  font-size: 13px;
}

.decimal-option:hover span { color: var(--bk-text); }
.decimal-option input:checked + span {
  background: var(--bk-surface);
  color: var(--bk-text);
  box-shadow: var(--bk-shadow-sm);
}

.amount-example { font-size: 12px; color: var(--bk-text-secondary); font-variant-numeric: tabular-nums; }
.preference-select { width: 156px; flex-shrink: 0; }
.preferences :deep(.preference-birthday) { width: 190px; flex-shrink: 0; }
.preferences :deep(.el-input__wrapper) { min-width: 0; }

/* 背景图选择器 */
.bg-picker {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: 12px;
  margin: 16px 0 10px;
}

.bg-opt {
  position: relative;
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.bg-opt .selection-mark { position: absolute; top: 6px; right: 6px; }
.bg-opt.is-active .selection-mark { opacity: 1; }

.bg-opt__thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 68px;
  border-radius: var(--bk-radius-md);
  border: 1px solid var(--bk-border-light);
  transition: border-color 0.16s ease;
  background: var(--el-fill-color-light);
  overflow: hidden;
  color: var(--bk-text-secondary);
}

.bg-opt__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.bg-opt__thumb--plain { background: var(--bk-bg); }
.bg-opt__thumb--plain span {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--bk-surface);
  box-shadow: var(--bk-shadow-sm);
}
.bg-opt__thumb--custom { border-style: dashed; }
.bg-opt__thumb--custom .el-icon { font-size: 20px; }

.bg-opt__name {
  display: block;
  margin-top: 8px;
  text-align: center;
  font-size: 12px;
  color: var(--bk-text-secondary);
}

.bg-opt:hover .bg-opt__thumb {
  border-color: var(--el-color-primary-light-5);
}

.bg-opt.is-active .bg-opt__thumb {
  border-color: var(--bk-button-primary);
  border-style: solid;
}

.bg-opt.is-active .bg-opt__name {
  color: var(--el-color-primary);
  font-weight: 600;
}

.bg-opt.is-busy {
  opacity: 0.6;
  pointer-events: none;
}

.bg-picker__file {
  display: none;
}

.mask-control {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 240px;
  max-width: 100%;
  flex-shrink: 0;
}
.mask-control > span { min-width: 36px; font-size: 12px; color: var(--bk-text-secondary); font-variant-numeric: tabular-nums; }
.bg-mask { flex: 1; min-width: 0; }

/* 数据管理：备份/恢复 + CSV 导入导出 */
.data-alert {
  margin-top: 18px;
  line-height: 1.7;
}

/* 快捷键自定义（EL-06） */
.shortcut-row,
.shortcut-display {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.shortcut-control { flex-shrink: 1; }
.shortcut-display { min-height: 36px; }
.shortcut-display kbd {
  padding: 5px 8px;
  border: 1px solid var(--bk-border);
  border-bottom-width: 2px;
  border-radius: 6px;
  background: var(--bk-surface-2);
  color: var(--bk-text-regular);
  font-family: inherit;
  font-size: 12px;
  line-height: 1.2;
  white-space: nowrap;
}
.shortcut-plus { font-size: 11px; color: var(--bk-text-secondary); }
.shortcut-display.is-capturing { font-size: 13px; color: var(--bk-button-primary); }

@container content (max-width: 860px) {
  .settings { flex-direction: column; height: auto; gap: 22px; }
  .settings__menu { display: flex; flex-wrap: wrap; gap: 4px; width: 100%; padding: 0; }
  .settings__menu-title { display: none; }
  .settings__menu-item { width: auto; padding: 9px 12px; font-size: 13px; }
  .settings__body { overflow: visible; flex: none; }
}

@container settings-body (max-width: 640px) {
  .preference-row { align-items: flex-start; flex-direction: column; gap: 12px; }
  .preference-control { align-items: flex-start; }
  .theme-options { gap: 8px; }
  .theme-preview { height: 84px; }
  .theme-preview__scene { gap: 6px; padding: 8px; }
  .theme-preview__content { gap: 5px; }
  .theme-preview__tiles i { height: 20px; }
}

@container settings-body (max-width: 380px) {
  .theme-options { gap: 4px; }
  .theme-option__body { padding: 4px; }
  .theme-option__caption { font-size: 12px; padding-inline: 0; }
  .theme-preview__scene { grid-template-columns: 20% 1fr; padding: 6px; gap: 4px; }
  .theme-preview__sidebar { padding-inline: 3px; }
  .bg-picker { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (prefers-reduced-motion: reduce) {
  .theme-option__body,
  .bg-opt__thumb { transition: none; }
}
</style>
