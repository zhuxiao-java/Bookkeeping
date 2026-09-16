<script setup lang="ts">
import sprite from './icons/level-logos.svg?raw'

/**
 * 等级 logo：引用 level-logos.svg 中对应等级的 symbol（lvl-1 .. lvl-20）。
 * sprite 仅注入 document 一次，避免多实例产生重复 id。
 */
const SPRITE_HOST_ID = 'bk-level-logos'

if (typeof document !== 'undefined' && !document.getElementById(SPRITE_HOST_ID)) {
  const host = document.createElement('div')
  host.id = SPRITE_HOST_ID
  host.style.display = 'none'
  host.innerHTML = sprite
  document.body.appendChild(host)
}

withDefaults(defineProps<{ level: number; size?: number }>(), { size: 32 })
</script>

<template>
  <svg class="level-logo" :width="size" :height="size" :aria-label="`等级${level}徽章`">
    <use :href="`#lvl-${level}`" />
  </svg>
</template>

<style scoped>
.level-logo {
  display: block;
  flex-shrink: 0;
}
</style>
