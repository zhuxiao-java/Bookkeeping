import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

export interface CountUpOptions {
  /** 动画时长（毫秒），默认 700 */
  duration?: number
}

/**
 * 数字滚动动画：把 source 的变化以 rAF 缓动过渡到返回值，用于金额等关键数值（克制微交互）。
 * 尊重 prefers-reduced-motion：开启减少动效时直接跳到目标值，不做动画。
 * 返回值为 number ref，动画过程中可能为小数，交由调用方按需格式化取整。
 */
export function useCountUp(source: Ref<number>, options: CountUpOptions = {}): Ref<number> {
  const duration = options.duration ?? 700
  const display = ref(source.value)
  let raf = 0

  const reduce =
    typeof window !== 'undefined' &&
    !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

  function animate(to: number) {
    cancelAnimationFrame(raf)
    if (reduce) {
      display.value = to
      return
    }
    const from = display.value
    const delta = to - from
    if (delta === 0) return
    const start = performance.now()
    const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)
    const step = (now: number) => {
      const p = Math.min((now - start) / duration, 1)
      display.value = from + delta * easeOut(p)
      if (p < 1) {
        raf = requestAnimationFrame(step)
      } else {
        display.value = to
      }
    }
    raf = requestAnimationFrame(step)
  }

  watch(source, (v) => animate(v), { immediate: true })

  onBeforeUnmount(() => cancelAnimationFrame(raf))

  return display
}
