<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type CSSProperties } from 'vue'
import { resolveSteps, type GuideStep } from '@/utils/guideSteps'
import { useGuideStore } from '@/stores/guide'

/**
 * 全局唯一的新手引导实例（挂在 MainLayout）。
 * 播哪个 scope 由 guide store 的 activeScope 决定，本组件只负责解析锚点与渲染 el-tour。
 * 锚点未就绪（可选能力未渲染）的步骤自动跳过；全部缺失则放弃本次播放且不标记已看过。
 */
const guide = useGuideStore()

const visible = ref(false)
const current = ref(0)
const steps = ref<GuideStep[]>([])
const viewport = ref({ width: window.innerWidth, height: window.innerHeight })

// 大区域两侧没有足够空间时居中显示说明，保留原锚点与高亮区域。
const centered = computed(() => {
  const { width, height } = viewport.value
  const step = steps.value[current.value]
  const target = step?.target ? document.querySelector(step.target) : null
  if (!target) return false
  const rect = target.getBoundingClientRect()
  return step.placement?.startsWith('left') || step.placement?.startsWith('right')
    ? rect.width > width * 0.55
    : rect.height > height * 0.55
})
const centeredStyle: CSSProperties = {
  position: 'fixed', top: '50%', left: '50%', transform: 'translate(-50%, -50%)'
}
function updateViewport() {
  viewport.value = { width: window.innerWidth, height: window.innerHeight }
}
onMounted(() => window.addEventListener('resize', updateViewport))
onBeforeUnmount(() => window.removeEventListener('resize', updateViewport))

/** 播放完成或中途退出：都记为已看过，不再自动打扰 */
function onDone() {
  guide.finish()
}

watch(
  () => guide.activeScope,
  async (scope) => {
    if (!scope) {
      visible.value = false
      return
    }
    const resolved = await resolveSteps(scope)
    // 等待锚点期间用户已退出或切到了别的引导
    if (guide.activeScope !== scope) return
    if (!resolved.length) {
      guide.cancel()
      return
    }
    steps.value = resolved
    current.value = 0
    visible.value = true
  },
  { immediate: true }
)
</script>

<template>
  <!-- key 绑定 scope：切换引导时重建实例，避免上一次的步骤索引与遮罩状态残留 -->
  <el-tour
    :key="guide.activeScope ?? 'idle'"
    v-model="visible"
    :current="current"
    :z-index="3000"
    :target-area-clickable="false"
    :show-arrow="!centered"
    :content-style="centered ? centeredStyle : undefined"
    @update:current="current = $event"
    @close="onDone"
    @finish="onDone"
  >
    <el-tour-step
      v-for="step in steps"
      :key="step.title"
      :target="step.target"
      :title="step.title"
      :description="step.description"
      :placement="step.placement"
    />
  </el-tour>
</template>
