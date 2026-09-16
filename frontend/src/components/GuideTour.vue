<script setup lang="ts">
import { ref, watch } from 'vue'
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
