import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/** 视为「用户活动」的事件：任一触发即复位空闲计时 */
const ACTIVITY_EVENTS: (keyof WindowEventMap)[] = [
  'mousemove',
  'mousedown',
  'keydown',
  'wheel',
  'touchstart',
  'scroll'
]

/** 空闲判定轮询间隔（毫秒） */
const TICK_INTERVAL = 5000

/**
 * 空闲检测：无操作超过 getMinutes() 分钟时 active 置为 true，
 * 期间任意用户活动立即复位。getMinutes() 返回 0（或负数）表示禁用，
 * 禁用时若正处于空闲态也会立即退出。阈值动态读取，改配置后下次轮询即生效。
 */
export function useIdle(getMinutes: () => number): { active: Ref<boolean> } {
  const active = ref(false)
  let lastActiveAt = Date.now()
  let timer: number | undefined

  function markActive() {
    lastActiveAt = Date.now()
    if (active.value) active.value = false
  }

  function tick() {
    const minutes = getMinutes()
    if (minutes <= 0) {
      if (active.value) active.value = false
      return
    }
    if (!active.value && Date.now() - lastActiveAt >= minutes * 60000) {
      active.value = true
    }
  }

  onMounted(() => {
    lastActiveAt = Date.now()
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, markActive, { passive: true }))
    timer = window.setInterval(tick, TICK_INTERVAL)
  })

  onBeforeUnmount(() => {
    ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, markActive))
    if (timer !== undefined) window.clearInterval(timer)
    timer = undefined
  })

  return { active }
}
