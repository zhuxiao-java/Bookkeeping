<script setup lang="ts">
/**
 * 空状态：手写内联 SVG 手绘风插画 + 文案 + 操作插槽。
 * 取代默认的 el-empty，让"暂无数据"也有温度。
 * 插画描边色取自设计 token（--bk-*），随浅色/深色主题自动适配。
 */
withDefaults(
  defineProps<{
    /** 主文案 */
    description?: string
    /** 插画尺寸（正方形边长，px） */
    size?: number
  }>(),
  { description: '这里还空空的', size: 132 }
)
</script>

<template>
  <div class="empty-state">
    <svg
      class="empty-state__art"
      :width="size"
      :height="size"
      viewBox="0 0 120 120"
      fill="none"
      aria-hidden="true"
    >
      <!-- 存钱罐（手绘风：略微不齐的描边 + 圆头线帽） -->
      <g
        class="empty-state__stroke"
        stroke-linecap="round"
        stroke-linejoin="round"
        stroke-width="3"
      >
        <!-- 罐身 -->
        <path d="M30 58c0-13 12-22 30-22s30 9 30 22-12 24-30 24-30-11-30-24Z" />
        <!-- 投币口 -->
        <path d="M52 40h16" />
        <!-- 耳朵 -->
        <path d="M84 50c4-3 8-2 8 2s-3 6-6 6" />
        <!-- 前腿 -->
        <path d="M44 82v7M76 82v7" />
        <!-- 眼睛（实心点） -->
        <circle class="empty-state__dot" cx="50" cy="58" r="2.4" stroke="none" />
      </g>
      <!-- 飘落的硬币（独立分组，便于整体浮动） -->
      <g
        class="empty-state__coin empty-state__stroke"
        stroke-linecap="round"
        stroke-linejoin="round"
        stroke-width="3"
      >
        <circle cx="60" cy="20" r="7" />
        <path d="M60 16v8M57 18.5h6M57 21.5h6" stroke-width="2" />
      </g>
      <!-- 地面虚线 -->
      <path
        class="empty-state__ground"
        d="M24 100h72"
        stroke-linecap="round"
        stroke-width="3"
        stroke-dasharray="2 10"
      />
    </svg>

    <p class="empty-state__text">{{ description }}</p>
    <div v-if="$slots.default" class="empty-state__actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 28px 16px;
  text-align: center;
}

.empty-state__art {
  display: block;
  margin-bottom: 4px;
}

.empty-state__stroke {
  stroke: var(--bk-primary-light-5, #8fbdae);
}

.empty-state__dot {
  fill: var(--bk-primary-light-5, #8fbdae);
}

.empty-state__ground {
  stroke: var(--bk-border, #e6e1d8);
}

.empty-state__text {
  margin: 0;
  color: var(--bk-text-secondary);
  font-size: 14px;
}

.empty-state__actions {
  margin-top: 10px;
}

/* 克制微交互：硬币轻轻上下浮动，尊重减少动效偏好 */
@media (prefers-reduced-motion: no-preference) {
  .empty-state__coin {
    animation: empty-coin-float 2.8s ease-in-out infinite;
    transform-origin: center;
  }
}

@keyframes empty-coin-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-3px);
  }
}
</style>
