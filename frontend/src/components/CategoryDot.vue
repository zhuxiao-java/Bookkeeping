<script setup lang="ts">
import { computed } from 'vue'
import { CATEGORY_ICON_EMOJI } from '@/utils/constants'

/** 分类图标展示：彩色圆角方块 + emoji（无 icon 时回退名称首字） */
const props = defineProps<{
  name: string
  icon?: string | null
  color?: string | null
  size?: number
}>()

const display = computed(() => CATEGORY_ICON_EMOJI[props.icon ?? ''] ?? props.name.slice(0, 1))
const boxSize = computed(() => props.size ?? 32)
</script>

<template>
  <span
    class="cat-dot"
    :style="{
      background: color || '#909399',
      width: `${boxSize}px`,
      height: `${boxSize}px`,
      fontSize: `${Math.round(boxSize * 0.55)}px`
    }"
  >
    {{ display }}
  </span>
</template>

<style scoped>
.cat-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: #fff;
  flex-shrink: 0;
  line-height: 1;
  user-select: none;
}
</style>
