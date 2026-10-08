<script setup lang="ts">
import type { PetMood, PetSpecies } from '@/types/model'

withDefaults(defineProps<{
  species?: PetSpecies | null
  mood?: PetMood | null
  stage?: number
  /** 人的互动：摸摸、逗一逗、叫一声。空着时只做待机动作。 */
  action?: 'idle' | 'pet' | 'play' | 'call' | null
}>(), {
  species: 'cat',
  mood: 'calm',
  stage: 1,
  action: 'idle'
})
</script>

<template>
  <svg
    class="pet-sprite"
    :class="[`is-${species}`, `is-${mood}`, `is-${action}`, `is-stage-${stage}`]"
    viewBox="0 0 140 120"
    aria-hidden="true"
  >
    <ellipse class="shadow" cx="72" cy="110" rx="28" ry="4.5" />

    <g v-if="action === 'pet'" class="fx">
      <path class="heart h1" d="M108 28c0-4 6-4 6 0 0 4-6 8-6 8s-6-4-6-8c0-4 6-4 6 0z" />
      <path class="heart h2" d="M120 18c0-3 4.5-3 4.5 0 0 3-4.5 6-4.5 6s-4.5-3-4.5-6c0-3 4.5-3 4.5 0z" />
    </g>
    <g v-else-if="action === 'play'" class="fx">
      <g class="toy">
        <circle cx="24" cy="96" r="7" />
        <path v-if="species === 'cat'" class="line" d="M20 94c3 2 6-2 8 1" />
      </g>
    </g>
    <g v-else-if="action === 'call'" class="fx notes">
      <path d="M112 34c6-2 8 6 2 8" />
      <path d="M122 24c7-2 9 7 2 9" />
    </g>

    <g class="actor">
      <!-- 鸟 -->
      <g v-if="species === 'bird'">
        <path class="tail line" d="M58 78 L34 70 L40 86 L58 84" />
        <g class="torso">
          <ellipse class="body" cx="74" cy="78" rx="24" ry="18" />
          <g class="wing">
            <path d="M70 74 C46 58 42 90 72 88" />
          </g>
          <g class="feet line">
            <path d="M66 94 L62 106 M66 106" />
            <path d="M82 94 L86 106 M78 106" />
          </g>
          <g class="head">
            <path class="crest" d="M78 40 L84 24 L90 40" />
            <circle cx="88" cy="50" r="15" />
            <path class="beak" d="M100 48 L116 53 L100 58 Z" />
            <g class="eye" transform="translate(92 48)">
              <ellipse class="pupil" cx="0" cy="0" rx="2.3" ry="2.6" />
              <ellipse class="lid" cx="0" cy="0" rx="4.2" ry="3.4" />
            </g>
          </g>
          <path v-if="stage >= 2" class="scarf" d="M64 66h22l-2 7H66z" />
        </g>
      </g>

      <!-- 狗 -->
      <g v-else-if="species === 'dog'">
        <path class="tail line" d="M98 76 C118 62 124 90 106 98" />
        <g class="torso">
          <ellipse class="body" cx="70" cy="86" rx="30" ry="18" />
          <g class="head">
            <ellipse class="ear ear-l" cx="46" cy="46" rx="9" ry="15" />
            <ellipse class="ear ear-r" cx="94" cy="46" rx="9" ry="15" />
            <circle cx="70" cy="50" r="24" />
            <ellipse class="muzzle" cx="70" cy="62" rx="11" ry="8" />
            <ellipse class="nose" cx="70" cy="56" rx="3.2" ry="2.4" />
            <path v-if="mood === 'energetic' || action === 'play'" class="tongue" d="M66 66h8v6a4 4 0 0 1-8 0z" />
            <path v-else class="mouth line" d="M62 66 Q70 72 78 66" />
            <g class="eye" transform="translate(58 46)">
              <ellipse class="pupil" cx="0" cy="0" rx="2.4" ry="2.8" />
              <ellipse class="lid" cx="0" cy="0" rx="4.4" ry="3.6" />
            </g>
            <g class="eye" transform="translate(82 46)">
              <ellipse class="pupil" cx="0" cy="0" rx="2.4" ry="2.8" />
              <ellipse class="lid" cx="0" cy="0" rx="4.4" ry="3.6" />
            </g>
          </g>
          <path v-if="stage >= 2" class="scarf" d="M50 70h40l-4 8H54z" />
        </g>
      </g>

      <!-- 猫 -->
      <g v-else>
        <path class="tail line" d="M46 84 C26 92 16 68 30 54" />
        <g class="torso">
          <ellipse class="body" cx="72" cy="86" rx="28" ry="18" />
          <g class="head">
            <path class="ear ear-l" d="M52 38 L42 12 L68 34 Z" />
            <path class="ear ear-r" d="M92 38 L102 12 L76 34 Z" />
            <circle cx="72" cy="50" r="23" />
            <g class="eye" transform="translate(60 48)">
              <ellipse class="pupil" cx="0" cy="0" rx="2.4" ry="3" />
              <ellipse class="lid" cx="0" cy="0" rx="4.6" ry="3.8" />
            </g>
            <g class="eye" transform="translate(84 48)">
              <ellipse class="pupil" cx="0" cy="0" rx="2.4" ry="3" />
              <ellipse class="lid" cx="0" cy="0" rx="4.6" ry="3.8" />
            </g>
            <path class="nose" d="M72 56 l-3.2 4 h6.4 z" />
            <path class="mouth line" d="M64 62 Q72 68 80 62" />
            <path class="whisker line" d="M48 56 H34 M48 62 H32 M96 56 H110 M96 62 H112" />
          </g>
          <path v-if="stage >= 2" class="scarf" d="M52 70h40l-4 8H56z" />
        </g>
      </g>

      <path v-if="stage >= 3" class="star" d="M72 8l2.2 5.2h5.4l-4.4 3.4 1.6 5.2L72 18.6 67.2 22l1.6-5.2-4.4-3.4h5.4z" />
    </g>
  </svg>
</template>

<style scoped>
.pet-sprite {
  width: 128px;
  height: 110px;
  color: var(--bk-button-primary);
  fill: var(--pet-fill, var(--bk-primary-soft));
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linejoin: round;
  stroke-linecap: round;
  flex: none;
  overflow: visible;
  --pet-pace: 1;
}
.is-energetic { --pet-pace: 0.62; }
.is-sleepy { --pet-pace: 1.65; }

.line { fill: none; }
.shadow {
  fill: currentColor;
  stroke: none;
  opacity: 0.14;
  transform-box: fill-box;
  transform-origin: center;
}
.pupil, .nose, .beak, .star, .heart, .tongue { fill: currentColor; stroke: none; }
.tongue { opacity: 0.85; }
.muzzle { fill: var(--pet-muzzle, var(--bk-surface)); }
.scarf { fill: none; }
.lid {
  fill: var(--pet-fill, var(--bk-primary-soft));
  stroke: none;
  transform-box: fill-box;
  transform-origin: center;
  animation: blink calc(4.6s * var(--pet-pace)) infinite;
}
.ear, .wing, .tail, .torso, .actor, .toy, .heart, .notes, .star, .crest {
  transform-box: fill-box;
  transform-origin: center;
}

.torso {
  transform-origin: 50% 100%;
  animation: breathe calc(3.4s * var(--pet-pace)) ease-in-out infinite;
}
.tail {
  transform-origin: 80% 70%;
  animation: swish calc(1.8s * var(--pet-pace)) ease-in-out infinite;
}
.is-dog .tail { transform-origin: 10% 40%; animation-duration: calc(0.7s * var(--pet-pace)); }
.is-bird .tail { animation: none; }
.ear {
  transform-origin: center bottom;
  animation: twitch calc(5.2s * var(--pet-pace)) ease-in-out infinite;
}
.ear-r { animation-delay: 0.2s; }
.is-dog .ear {
  transform-origin: center top;
  animation-name: flop;
  animation-duration: calc(1.15s * var(--pet-pace));
}
.wing {
  transform-origin: 80% 60%;
  animation: flap calc(0.9s * var(--pet-pace)) ease-in-out infinite;
}
.crest { transform-origin: center bottom; animation: twitch calc(3s * var(--pet-pace)) ease-in-out infinite; }
.star { animation: twinkle 2.6s ease-in-out infinite; }
.is-bird .actor { animation: hop-idle calc(1.6s * var(--pet-pace)) ease-in-out infinite; }
.is-energetic .actor { animation: bob calc(1.5s * var(--pet-pace)) ease-in-out infinite; }
.is-sleepy .actor { animation: nod calc(4.2s * var(--pet-pace)) ease-in-out infinite; }
.is-sleepy .lid { animation: none; transform: scaleY(1); }
.is-sleepy .tail, .is-sleepy .wing, .is-sleepy .ear { animation-duration: calc(3.4s * var(--pet-pace)); }

.is-pet .torso { animation: nuzzle 0.7s ease-in-out 3; }
.is-pet .lid { animation: squint 0.7s ease-in-out 3; }
.is-play .actor { animation: hop 0.42s ease-in-out 4; }
.is-play .shadow { animation: squash 0.42s ease-in-out 4; }
.is-play .toy { animation: roll 0.42s ease-in-out 4; }
.is-sleepy.is-play .actor { animation-name: hop-small; }
.is-call .ear, .is-call .crest { animation: perk 0.45s ease-out 3; }
.is-call .notes { animation: rise 1.4s ease-out 2; }
.heart { animation: rise 1.5s ease-out 2; }
.h2 { animation-delay: 0.25s; }

@keyframes breathe {
  0%, 100% { transform: translateY(0) scale(1, 1); }
  50% { transform: translateY(1.5px) scale(1.03, 0.97); }
}
@keyframes swish {
  0%, 100% { transform: rotate(-16deg); }
  50% { transform: rotate(18deg); }
}
@keyframes twitch {
  0%, 72%, 100% { transform: rotate(0deg); }
  80% { transform: rotate(-10deg); }
  90% { transform: rotate(8deg); }
}
@keyframes flop {
  0%, 100% { transform: rotate(-10deg); }
  50% { transform: rotate(12deg); }
}
@keyframes flap {
  0%, 100% { transform: rotate(-8deg); }
  50% { transform: rotate(26deg); }
}
@keyframes bob {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-7px); }
}
@keyframes hop-idle {
  0%, 100% { transform: translateY(0); }
  45% { transform: translateY(-5px); }
}
@keyframes nod {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(4deg); }
}
@keyframes hop {
  0%, 100% { transform: translateY(0); }
  45% { transform: translateY(-16px); }
}
@keyframes hop-small {
  0%, 100% { transform: translateY(0); }
  45% { transform: translateY(-6px); }
}
@keyframes nuzzle {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(-7deg) translateX(3px); }
}
@keyframes squint {
  0%, 100% { transform: scaleY(0.2); }
  50% { transform: scaleY(0.75); }
}
@keyframes blink {
  0%, 40%, 48%, 100% { transform: scaleY(0); }
  44% { transform: scaleY(1); }
}
@keyframes squash {
  0%, 100% { transform: scaleX(1); opacity: 0.14; }
  45% { transform: scaleX(0.62); opacity: 0.06; }
}
@keyframes roll {
  0%, 100% { transform: translate(0, 0); }
  45% { transform: translate(10px, -4px); }
}
@keyframes perk {
  0%, 100% { transform: rotate(0deg); }
  40% { transform: rotate(-16deg) translateY(-2px); }
}
@keyframes rise {
  0% { transform: translateY(6px); opacity: 0; }
  30% { opacity: 1; }
  100% { transform: translateY(-16px); opacity: 0; }
}
@keyframes twinkle {
  0%, 100% { transform: scale(1) rotate(0deg); opacity: 1; }
  50% { transform: scale(0.72) rotate(16deg); opacity: 0.45; }
}

@media (prefers-reduced-motion: reduce) {
  .pet-sprite * { animation: none !important; }
  .is-sleepy .lid { transform: scaleY(1); }
}
</style>
