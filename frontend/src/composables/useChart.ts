import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import echarts from '@/utils/echarts'
import type { EChartsOption } from '@/utils/echarts'

/** 图表实例管理：渲染、自适应窗口、自动销毁、点击钻取 */
export function useChart() {
  const elRef: Ref<HTMLElement | undefined> = ref()
  let chart: echarts.ECharts | null = null
  let clickHandler: ((params: any) => void) | null = null
  let resizeObserver: ResizeObserver | null = null

  function ensureChart(): echarts.ECharts | null {
    if (!elRef.value) return null
    if (!chart) {
      chart = echarts.init(elRef.value)
      // 统一在 init 时绑定 click，转发到当前 clickHandler（可后续更新，用于钻取跳转）
      chart.on('click', (params: any) => clickHandler?.(params))
      // 容器级尺寸监听：侧栏折叠、面板伸缩等只改容器宽而不触发 window.resize 的场景也能自适应
      if (typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => chart?.resize())
        resizeObserver.observe(elRef.value)
      }
    }
    return chart
  }

  function render(next: EChartsOption) {
    const c = ensureChart()
    if (!c) return
    c.setOption(next, true)
  }

  /** 注册图表点击回调（钻取用）；传 null 取消。回调在点击时读取最新数据 */
  function onClick(cb: ((params: any) => void) | null) {
    clickHandler = cb
  }

  function onResize() {
    chart?.resize()
  }

  onMounted(() => {
    window.addEventListener('resize', onResize)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', onResize)
    resizeObserver?.disconnect()
    resizeObserver = null
    chart?.dispose()
    chart = null
    clickHandler = null
  })

  return { elRef, render, resize: onResize, onClick }
}
