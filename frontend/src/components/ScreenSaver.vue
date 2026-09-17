<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { useIdle } from '@/composables/useIdle'
import { PRESET_BACKGROUNDS } from '@/utils/constants'
import { resolveBackgroundUrl } from '@/utils/background'

/**
 * 屏保：长时间无操作时全屏展示背景图 + 大时钟，任意操作（点击/按键/移动）退出。
 * 背景复用 settings.backgroundImage；未设置时回退内置「夜航」深色预设，保证始终有画面。
 */
const settings = useSettingsStore()
const { active } = useIdle(() => settings.screensaverMinutes)

/** 屏保背景：用户设置的背景图，未设置则回退内置深色预设 */
const bgUrl = computed(
  () => resolveBackgroundUrl(settings.backgroundImage) || PRESET_BACKGROUNDS.find((p) => p.id === 'night')?.url || ''
)

const now = ref(new Date())
let clockTimer: number | undefined

// 仅在屏保激活时每秒刷新时钟，退出即停，避免常驻定时器空转
watch(active, (on) => {
  if (on) {
    now.value = new Date()
    clockTimer = window.setInterval(() => (now.value = new Date()), 1000)
  } else if (clockTimer !== undefined) {
    window.clearInterval(clockTimer)
    clockTimer = undefined
  }
})

onBeforeUnmount(() => {
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})

const timeText = computed(() =>
  now.value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
)
const dateText = computed(() =>
  now.value.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long'
  })
)
</script>

<template>
  <Teleport to="body">
    <Transition name="ss-fade">
      <div v-if="active" class="screensaver" role="button" tabindex="0" aria-label="退出屏保" @click="active = false" @keydown="active = false">
        <div class="screensaver__bg" :style="{ backgroundImage: `url('${bgUrl}')` }" />
        <div class="screensaver__shade" />
        <div class="screensaver__clock">
          <div class="screensaver__time">{{ timeText }}</div>
          <div class="screensaver__date">{{ dateText }}</div>
        </div>
        <div class="screensaver__hint">点击任意处或按任意键退出</div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.screensaver {
  position: fixed;
  inset: 0;
  z-index: 3000;
  cursor: pointer;
  overflow: hidden;
  /* 图片加载前/透明区域的兜底底色 */
  background: #0b1020;
}

.screensaver__bg {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
}

/* 自上而下加深的遮罩，保障时钟与提示文字在任意图片上都清晰可读 */
.screensaver__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.3), rgba(0, 0, 0, 0.65));
}

.screensaver__clock {
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  transform: translateY(-58%);
  text-align: center;
  color: #fff;
  pointer-events: none;
}

.screensaver__time {
  font-size: clamp(56px, 8vw, 96px);
  font-weight: 200;
  line-height: 1;
  letter-spacing: 2px;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 2px 24px rgba(0, 0, 0, 0.45);
}

.screensaver__date {
  margin-top: 14px;
  font-size: clamp(16px, 2vw, 22px);
  color: rgba(255, 255, 255, 0.88);
  text-shadow: 0 1px 12px rgba(0, 0, 0, 0.45);
}

.screensaver__hint {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 40px;
  text-align: center;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
  pointer-events: none;
}

/* 淡入淡出 */
.ss-fade-enter-active,
.ss-fade-leave-active {
  transition: opacity 0.6s ease;
}

.ss-fade-enter-from,
.ss-fade-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .ss-fade-enter-active,
  .ss-fade-leave-active { transition: none; }
}
</style>
