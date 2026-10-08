<script setup lang="ts">
import type { PetMood, PetSpecies } from '@/types/model'

withDefaults(defineProps<{
  species?: PetSpecies | null
  mood?: PetMood | null
  stage?: number
}>(), {
  species: 'cat',
  mood: 'calm',
  stage: 1
})
</script>

<template>
  <svg class="pet-sprite" :class="[`is-${mood}`, `is-stage-${stage}`]" viewBox="0 0 80 80" aria-hidden="true">
    <g v-if="species === 'bird'">
      <ellipse cx="40" cy="46" rx="18" ry="16" />
      <path class="wing" d="M24 46c8-10 18-8 22 2" />
      <path class="beak" d="M56 44l10 3-10 3z" />
      <path v-if="stage >= 2" class="scarf" d="M30 56h20l-2 8H32z" />
    </g>
    <g v-else-if="species === 'dog'">
      <ellipse cx="22" cy="30" rx="8" ry="12" />
      <ellipse cx="58" cy="30" rx="8" ry="12" />
      <ellipse cx="40" cy="46" rx="20" ry="18" />
      <ellipse cx="40" cy="50" rx="8" ry="6" class="muzzle" />
      <path v-if="stage >= 2" class="scarf" d="M26 58h28l-3 8H29z" />
    </g>
    <g v-else>
      <path d="M24 28l-6-14 12 8z" />
      <path d="M56 28l6-14-12 8z" />
      <ellipse cx="40" cy="46" rx="20" ry="18" />
      <path v-if="stage >= 2" class="scarf" d="M26 58h28l-3 8H29z" />
    </g>
    <g v-if="mood === 'sleepy'" class="face">
      <path d="M30 44h8M42 44h8" />
    </g>
    <g v-else class="face">
      <circle cx="33" cy="44" r="2.2" />
      <circle cx="47" cy="44" r="2.2" />
      <path v-if="mood === 'energetic'" d="M34 52c3 3 9 3 12 0" />
    </g>
    <path v-if="stage >= 3" class="star" d="M40 12l2 5h5l-4 3 1.5 5L40 22l-4.5 3 1.5-5-4-3h5z" />
  </svg>
</template>

<style scoped>
.pet-sprite {
  width: 72px;
  height: 72px;
  color: var(--bk-button-primary);
  fill: var(--bk-primary-soft);
  stroke: currentColor;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
  flex: none;
}
.face { fill: none; }
.face circle { fill: currentColor; stroke: none; }
.beak, .star { fill: currentColor; }
.muzzle { fill: var(--bk-surface); }
.scarf { fill: none; }
.is-energetic { animation: pet-bob 2.4s ease-in-out infinite; }
@media (prefers-reduced-motion: reduce) {
  .is-energetic { animation: none; }
}
@keyframes pet-bob {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-3px); }
}
</style>
