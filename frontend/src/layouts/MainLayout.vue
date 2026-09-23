<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElButton, ElNotification } from 'element-plus'
import {
  DataAnalysis,
  Calendar,
  Expand,
  Fold,
  Medal,
  Moon,
  Odometer,
  PieChart,
  Plus,
  Setting,
  Sunny,
  Tickets,
  Wallet
} from '@element-plus/icons-vue'
import { useSettingsStore } from '@/stores/settings'
import { useDictStore } from '@/stores/dict'
import { useLevelStore } from '@/stores/level'
import QuickRecordDialog from '@/components/QuickRecordDialog.vue'
import LevelLogo from '@/components/LevelLogo.vue'
import MessageCenter from '@/components/MessageCenter.vue'
import HelpDrawer from '@/components/HelpDrawer.vue'
import ScreenSaver from '@/components/ScreenSaver.vue'
import GuideTour from '@/components/GuideTour.vue'
import { resolveBackgroundUrl } from '@/utils/background'
import { bus, OPEN_QUICK_RECORD, TRANSACTION_CHANGED } from '@/utils/bus'
import { SCOPE_LABEL, scopeOfPath } from '@/utils/guideSteps'
import { useGuideStore } from '@/stores/guide'
import { useBudgetAlert } from '@/composables/useBudgetAlert'

const route = useRoute()
const settings = useSettingsStore()
const dict = useDictStore()
const level = useLevelStore()
const guide = useGuideStore()
// 预算不足应用内提醒：全局监听交易/预算变更，使用率首次越过 80%/100% 弹通知（MS-04）
useBudgetAlert()

const collapsed = ref(false)
const quickVisible = ref(false)

const activeMenu = computed(() => '/' + (route.path.split('/')[1] || 'dashboard'))
const pageTitle = computed(() => (route.meta.title as string) || '')

/** 时段问候语：让首屏有“人味”，弱化后台工具感 */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

/**
 * 背景图样式：铺在不滚动的外层容器上（避开滚动容器内背景随内容滚走的问题），
 * 并叠加一层主题感知的遮罩保障可读性；侧边栏与顶栏自带不透明底色，
 * 因此背景仅在内容区可见。
 */
const bgStyle = computed(() => {
  const img = resolveBackgroundUrl(settings.backgroundImage)
  if (!img) return undefined
  const mask = `rgb(var(--bk-bg-mask-rgb) / ${settings.backgroundMask / 100})`
  return { backgroundImage: `linear-gradient(${mask}, ${mask}), url("${img}")` }
})

const menus = [
  { path: '/dashboard', title: '总览', icon: Odometer },
  { path: '/account', title: '账户', icon: Wallet },
  { path: '/transaction', title: '流水', icon: Tickets },
  { path: '/report', title: '报表', icon: DataAnalysis },
  { path: '/monthly-report', title: 'AI 月报', icon: Calendar },
  { path: '/budget', title: '预算', icon: PieChart },
  { path: '/level', title: '等级', icon: Medal },
  { path: '/settings', title: '设置', icon: Setting }
]

function refreshLevel() {
  if (route.path === '/level') void level.loadAll(true)
  else void level.loadCurrent(true)
}

function onQuickSaved() {
  // 快速记账保存成功：各页面通过路由 keep-alive / 自身刷新逻辑处理数据更新
  // TODO: 引入事件总线后按当前页面精细刷新
}

/** 打开快速记账：顶栏按钮、Cmd/Ctrl+N、Electron 全局快捷键与事件总线共用 */
function openQuickRecord() {
  quickVisible.value = true
}

/** 全局快捷键 Cmd/Ctrl + N 唤出快速记账 */
function onKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'n') {
    e.preventDefault()
    openQuickRecord()
  }
}

/** Electron IPC 订阅的取消函数 */
let unsubscribeQuickRecord: (() => void) | undefined

/** 首启全局引导的延时器与页面引导通知（离开布局时需清理） */
let guideTimer: number | undefined
let guideNotice: { close: () => void } | null = null

/**
 * 页面引导不自动播放，只在右下角通知里给一个「开始」入口，避免打断操作。
 * 正在播其他引导、或本页已看过时不提示。
 */
function promptPageGuide(path: string) {
  const scope = scopeOfPath(path)
  if (!scope || guide.activeScope || guide.isDone(scope)) return

  guideNotice?.close()
  // 开始引导：关通知后播放本页引导
  const start = () => {
    guideNotice?.close()
    guideNotice = null
    guide.start(scope)
  }
  // 稍后再说：仅关闭本次提示（下次换页仍会再问）
  const dismiss = () => {
    guideNotice?.close()
    guideNotice = null
  }
  guideNotice = ElNotification({
    title: `${SCOPE_LABEL[scope]}指引`,
    // 通知挂在 body 下，scoped 样式作用不到：布局用内联样式，按钮视觉用全局类 .bk-guide-start
    message: h('div', { style: { display: 'flex', flexDirection: 'column', gap: '14px' } }, [
      h(
        'p',
        { style: { margin: '0', fontSize: '13px', lineHeight: '1.6' } },
        `花 20 秒了解${SCOPE_LABEL[scope]}的主要功能？`
      ),
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } }, [
        // 柔和填充主按钮（样式见全局 .bk-guide-start），轻量提示不喧宾夺主
        h(ElButton, { round: true, class: 'bk-guide-start', onClick: start }, () => '开始引导'),
        // 次要操作降为灰色文字按钮，层级更清晰、观感更松弛
        h(ElButton, { text: true, type: 'info', style: { marginLeft: '0' }, onClick: dismiss }, () => '稍后再说')
      ])
    ]),
    position: 'bottom-right',
    duration: 8000,
    onClose: () => {
      guideNotice = null
    }
  })
}

// 全局引导播完后顺势提示当前页；此后每次换页各自提示一次
watch(
  () => guide.activeScope,
  (scope, prev) => {
    if (prev === 'global' && !scope) promptPageGuide(route.path)
  }
)
watch(() => route.path, (path) => promptPageGuide(path))

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('focus', refreshLevel)
  bus.on(TRANSACTION_CHANGED, refreshLevel)
  // Electron 环境：订阅主进程事件（托盘菜单 / 全局快捷键 Cmd/Ctrl+Shift+B）
  unsubscribeQuickRecord = window.electronAPI?.onOpenQuickRecord(openQuickRecord)
  // 应用用户自定义的全局快捷键（EL-06）：主进程 bootstrap 注册的是默认值，这里覆盖为已存设置
  if (window.electronAPI?.setQuickRecordShortcut && settings.quickRecordShortcut) {
    window.electronAPI.setQuickRecordShortcut(settings.quickRecordShortcut).catch(() => {
      // 注册失败（被占用）时保留主进程默认快捷键，静默降级
    })
  }
  // 使用说明面板的「立即记一笔」等跨组件入口
  bus.on(OPEN_QUICK_RECORD, openQuickRecord)
  dict.loadAll().catch(() => {
    // 拦截器已统一提示
  })
  // 等级为可选能力：接口未就绪时静默降级，侧边栏不展示
  level.loadCurrent()
  // 首次启动播放全局引导：稍等一下，让布局与可选入口（铃铛、等级）先渲染出来
  if (!guide.isDone('global')) {
    guideTimer = window.setTimeout(() => {
      if (!guide.activeScope) guide.start('global')
    }, 600)
  }
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('focus', refreshLevel)
  bus.off(TRANSACTION_CHANGED, refreshLevel)
  bus.off(OPEN_QUICK_RECORD, openQuickRecord)
  unsubscribeQuickRecord?.()
  unsubscribeQuickRecord = undefined
  window.clearTimeout(guideTimer)
  guideNotice?.close()
  guideNotice = null
})
</script>

<template>
  <el-container
    class="main-layout"
    :class="{ 'has-bg': settings.backgroundImage }"
    :style="bgStyle"
  >
    <el-aside :width="collapsed ? '64px' : '200px'" class="main-layout__aside">
      <div class="brand">
        <span class="brand__logo">记</span>
        <div v-show="!collapsed" class="brand__text">
          <span class="brand__name">记账本</span>
          <span class="brand__tag">温暖理财，从一笔开始</span>
        </div>
      </div>

      <!-- 等级入口（后端等级接口可用时展示），点击进等级页 -->
      <router-link
        v-if="level.info"
        to="/level"
        class="level-entry"
        data-guide="level-entry"
        :class="{ 'level-entry--collapsed': collapsed }"
        :title="collapsed ? level.info.leveName : '查看我的等级'"
        aria-label="查看我的等级"
      >
        <LevelLogo :level="level.info.level" :size="collapsed ? 30 : 34" />
        <template v-if="!collapsed">
          <div class="level-entry__meta">
            <div class="level-entry__name">{{ level.info.leveName }}</div>
            <div class="level-entry__hint">
              {{ level.info.nextLevelName ? `距「${level.info.nextLevelName}」还差 ${level.remainExp} 经验` : '已达最高等级' }}
            </div>
          </div>
          <el-progress
            class="level-entry__bar"
            :percentage="level.progress"
            :show-text="false"
            :stroke-width="4"
            color="var(--bk-primary)"
          />
        </template>
      </router-link>

      <el-menu
        :default-active="activeMenu"
        router
        :collapse="collapsed"
        class="main-layout__menu"
        data-guide="nav"
      >
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>{{ m.title }}</template>
        </el-menu-item>
      </el-menu>

      <button type="button" class="aside-footer" :aria-label="collapsed ? '展开导航' : '收起导航'" :aria-expanded="!collapsed" @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        <span v-show="!collapsed">收起导航</span>
      </button>
    </el-aside>

    <el-container class="main-layout__body">
      <el-header class="main-layout__header" height="64px">
        <div class="header-title">
          <span class="header-title__greet">{{ greeting }}，</span>
          <span class="header-title__text">{{ pageTitle }}</span>
        </div>
        <div class="header-actions">
          <el-button class="cta-record" round data-guide="quick-record" @click="openQuickRecord">
            <el-icon class="btn-icon"><Plus /></el-icon>
            记一笔
          </el-button>
          <!-- 站内信：后端消息接口可用时展示铃铛与未读角标 -->
          <MessageCenter />
          <el-tooltip :content="settings.isDark ? '切换浅色主题' : '切换深色主题'">
            <el-button circle data-guide="theme" :aria-label="settings.isDark ? '切换浅色主题' : '切换深色主题'" @click="settings.toggleTheme()">
              <el-icon><component :is="settings.isDark ? Sunny : Moon" /></el-icon>
            </el-button>
          </el-tooltip>
          <!-- 使用说明：抽屉形式，不占路由 -->
          <HelpDrawer />
        </div>
      </el-header>

      <el-main class="main-layout__main">
        <router-view />
      </el-main>
    </el-container>

    <QuickRecordDialog v-model="quickVisible" @saved="onQuickSaved" />

    <!-- 屏保：长时间无操作时全屏展示背景图 -->
    <ScreenSaver />

    <!-- 新手引导：全局唯一实例，播什么由 guide store 决定 -->
    <GuideTour />
  </el-container>
</template>

<style scoped>
.main-layout {
  height: 100%;
  background: var(--bk-bg);
  /* 背景图铺设规则（未设置背景时不生效，仅保留基底暖白） */
  background-repeat: no-repeat;
  background-size: cover;
  background-position: center;
}

/* 启用背景后内容区改为透明，让外层背景图透出 */
.main-layout.has-bg .main-layout__main {
  background: transparent;
}

.main-layout__aside {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--bk-glass-border);
  background: var(--bk-glass);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  transition: width 0.2s;
  overflow: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 18px 16px 14px;
  white-space: nowrap;
}

.brand__logo {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: var(--bk-radius-md);
  background: var(--bk-primary-soft);
  color: var(--bk-button-primary);
  font-size: 19px;
  font-weight: 700;
  box-shadow: none;
}

.brand__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.brand__name {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.brand__tag {
  font-size: 11px;
  color: var(--bk-text-secondary);
  margin-top: 1px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.level-entry {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 4px 10px;
  margin: 0 12px 12px;
  padding: 11px;
  border-radius: var(--bk-radius-md);
  border: none;
  background: var(--bk-surface-2);
  box-shadow: none;
  color: var(--bk-text);
  text-decoration: none;
  cursor: pointer;
  transition: background-color 0.18s;
}

.level-entry:hover {
  background: var(--bk-primary-soft);
}

.level-entry--collapsed {
  display: flex;
  justify-content: center;
  padding: 8px 0;
}

.level-entry__meta {
  min-width: 0;
}

.level-entry__name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.level-entry__hint {
  color: var(--bk-text-secondary);
  font-size: 11px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.level-entry__bar {
  grid-column: 1 / -1;
}

.main-layout__menu {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border-right: none;
  padding: 6px 10px;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--bk-text-regular);
  --el-menu-hover-bg-color: transparent;
}

/* 导航胶囊化：去 admin 硬底色，激活项为品牌浅色 pill */
.main-layout__menu :deep(.el-menu-item) {
  height: 46px;
  line-height: 46px;
  margin-bottom: 6px;
  border-radius: var(--bk-radius-md);
  color: var(--bk-text-regular);
  font-weight: 500;
  transition: background-color 0.2s, color 0.2s;
}

.main-layout__menu :deep(.el-menu-item:hover) {
  background: var(--bk-surface-2);
  color: var(--bk-text);
}

.main-layout__menu :deep(.el-menu-item.is-active) {
  background: var(--bk-primary-soft);
  color: var(--bk-primary);
  font-weight: 600;
}

.main-layout__menu :deep(.el-menu-item.is-active .el-icon) {
  color: var(--bk-primary);
}

.main-layout__menu.el-menu--collapse {
  padding: 6px 8px;
}

.aside-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 10px 12px;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  text-align: left;
  border-radius: var(--bk-radius-md);
  color: var(--bk-text-secondary);
  cursor: pointer;
  white-space: nowrap;
  font-size: 13px;
  transition: background-color 0.2s, color 0.2s;
}

.aside-footer:hover {
  background: var(--bk-surface-2);
  color: var(--bk-primary);
}

.main-layout__body {
  min-width: 0;
  min-height: 0;
}

.main-layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: none;
  background: transparent;
  padding: 0 24px;
}

.header-title {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.header-title__greet {
  font-size: 14px;
  color: var(--bk-text-secondary);
  font-weight: 500;
}

.header-title__text {
  font-size: 14px;
  font-weight: 550;
  color: var(--bk-text-regular);
  letter-spacing: -0.01em;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.btn-icon {
  margin-right: 4px;
}

.main-layout__main {
  min-width: 0;
  min-height: 0;
  background: transparent;
  padding: 12px 24px 24px;
  overflow-y: auto;
  scrollbar-gutter: stable;
  container: content / inline-size;
}

@media (max-width: 1100px) {
  .main-layout__header { padding-inline: 20px; }
  .main-layout__main { padding-inline: 20px; }
  .header-actions { gap: 8px; }
}

@media (max-width: 720px) {
  .header-title__greet { display: none; }
  .main-layout__header { gap: 12px; padding-inline: 16px; }
  .main-layout__main { padding-inline: 16px; }
  .header-actions { flex-wrap: wrap; gap: 6px; }
}

@media (prefers-reduced-motion: reduce) {
  .main-layout__aside, .level-entry, .aside-footer { transition: none; }
}
</style>
