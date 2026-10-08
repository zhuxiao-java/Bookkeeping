<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, nextTick } from 'vue'
import { usePetStore } from '@/stores/pet'
import type { PetMood, PetSpecies } from '@/types/model'
import PetSprite from '@/components/PetSprite.vue'

type Gesture = 'pet' | 'play' | 'call'

const pet = usePetStore()
const name = ref('')
const species = ref<PetSpecies>('cat')
const renaming = ref(false)
const nextName = ref('')
const action = ref<'idle' | Gesture>('idle')
const line = ref('')
let timer: number | undefined

const speciesOptions: { id: PetSpecies; label: string }[] = [
  { id: 'cat', label: '猫' },
  { id: 'dog', label: '狗' },
  { id: 'bird', label: '鸟' }
]

const gestures: { id: Gesture; label: string }[] = [
  { id: 'pet', label: '摸摸' },
  { id: 'play', label: '逗一逗' },
  { id: 'call', label: '叫一声' }
]

const shownSpecies = computed(() => (pet.adopted ? pet.view?.species : species.value) ?? 'cat')
const shownMood = computed<PetMood>(() => (pet.adopted ? pet.view?.moodKey : 'calm') ?? 'calm')
const shownStage = computed(() => (pet.adopted ? pet.view?.stage : 1) ?? 1)

const stageText = computed(() => {
  const stage = pet.view?.stage ?? 1
  if (stage >= 3) return '相伴'
  if (stage >= 2) return '熟悉'
  return '初识'
})

onMounted(() => {
  void pet.load()
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearTimeout(timer)
})

async function adopt() {
  const trimmed = name.value.trim()
  if (!trimmed || pet.busy) return
  await pet.adopt(trimmed, species.value)
}

function startRename() {
  nextName.value = pet.view?.name ?? ''
  renaming.value = true
}

async function saveName() {
  const trimmed = nextName.value.trim()
  if (!trimmed || pet.busy) return
  await pet.rename(trimmed)
  renaming.value = false
}

async function interact(gesture: Gesture) {
  if (timer !== undefined) window.clearTimeout(timer)
  action.value = 'idle'
  await nextTick()
  action.value = gesture
  line.value = reply(shownSpecies.value, shownMood.value, gesture)
  timer = window.setTimeout(() => {
    action.value = 'idle'
    line.value = ''
  }, 2800)
}

function reply(kind: PetSpecies, mood: PetMood, gesture: Gesture): string {
  const sleepy: Record<PetSpecies, Record<Gesture, string>> = {
    cat: { pet: '它把脑袋搁过来，眼睛还是闭着。', play: '它抬了抬爪子，又趴了回去。', call: '唔……喵。' },
    dog: { pet: '尾巴轻轻拍了一下，人还是迷糊的。', play: '它想追，脚没迈出去。', call: '嗯汪。' },
    bird: { pet: '羽毛蓬了一下，又缩回翅膀里。', play: '跳了半下，站不稳。', call: '叽……' }
  }
  const calm: Record<PetSpecies, Record<Gesture, string>> = {
    cat: { pet: '呼噜声响起来了。', play: '它扑向那团线。', call: '喵。' },
    dog: { pet: '它把脑袋往你手里送。', play: '它追着球转了一圈。', call: '汪！' },
    bird: { pet: '它用喙轻轻碰了碰你的手指。', play: '它扑棱着跳上跳下。', call: '叽叽！' }
  }
  const bright: Record<PetSpecies, Record<Gesture, string>> = {
    cat: { pet: '再摸一会儿，它把肚子翻开了。', play: '它来回冲刺，线团都跟不上。', call: '喵——！' },
    dog: { pet: '整条尾巴都在摇。', play: '它叼着球跑回来，又放下。', call: '汪汪！' },
    bird: { pet: '它站上你的指节，歪头看你。', play: '翅膀张开，在原地转了个圈。', call: '叽叽叽！' }
  }
  const table = mood === 'sleepy' ? sleepy : mood === 'energetic' ? bright : calm
  return table[kind][gesture]
}
</script>

<template>
  <section v-if="pet.available" class="surface pet-card" aria-label="陪伴宠物">
    <div class="pet-row">
      <div class="pet-side">
        <button type="button" class="pet-tap" :aria-label="pet.adopted ? `摸摸${pet.view?.name}` : '摸摸这只宠物'" @click="interact('pet')">
          <PetSprite :species="shownSpecies" :mood="shownMood" :stage="shownStage" :action="action" />
        </button>
      </div>
      <div class="pet-main">
        <template v-if="!pet.adopted">
          <h2 class="pet-title">领养一只陪伴宠物</h2>
          <p class="pet-sub">它跟着签到和记账变化心情，不另算经验，也不改账本。</p>
          <p v-if="line" class="pet-line" role="status">{{ line }}</p>
          <p v-else class="pet-sub">先点一点，看看它怎么回应。</p>
          <div class="pet-actions" role="group" aria-label="和宠物互动">
            <button
              v-for="item in gestures"
              :key="item.id"
              type="button"
              :class="{ 'is-on': action === item.id }"
              @click="interact(item.id)"
            >{{ item.label }}</button>
          </div>
          <div class="pet-form">
            <el-input v-model="name" maxlength="12" placeholder="给它起个名字" aria-label="宠物名字" />
            <div class="pet-species" role="radiogroup" aria-label="外形">
              <button
                v-for="item in speciesOptions"
                :key="item.id"
                type="button"
                role="radio"
                :aria-checked="species === item.id"
                :class="{ 'is-on': species === item.id }"
                @click="species = item.id"
              >{{ item.label }}</button>
            </div>
            <el-button type="primary" :disabled="!name.trim()" :loading="pet.busy" @click="adopt">领养</el-button>
          </div>
        </template>
        <template v-else>
          <h2 class="pet-title">
            {{ pet.view?.name }}
            <span class="pet-mood">心情 {{ pet.view?.mood }} · {{ pet.view?.moodLabel }}</span>
          </h2>
          <p class="pet-sub">
            {{ stageText }} · 连续签到 {{ pet.view?.streakDays ?? 0 }} 天
            <template v-if="pet.view?.recordedToday"> · 今天已经陪它记过一笔</template>
            <template v-else> · 今天记一笔，它会更有精神</template>
          </p>
          <p v-if="line" class="pet-line" role="status">{{ line }}</p>
          <p v-else class="pet-sub">点它，或者摸摸、逗一逗、叫一声。互动不改变心情。</p>
          <div class="pet-actions" role="group" aria-label="和宠物互动">
            <button
              v-for="item in gestures"
              :key="item.id"
              type="button"
              :class="{ 'is-on': action === item.id }"
              @click="interact(item.id)"
            >{{ item.label }}</button>
          </div>
          <p class="pet-sub">几天不打开，它会犯困，但不会离开。</p>
          <div v-if="renaming" class="pet-form">
            <el-input v-model="nextName" maxlength="12" aria-label="新的名字" @keyup.enter="saveName" />
            <el-button type="primary" :disabled="!nextName.trim()" :loading="pet.busy" @click="saveName">保存</el-button>
            <el-button text @click="renaming = false">取消</el-button>
          </div>
          <el-button v-else text @click="startRename">改名</el-button>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.pet-card { padding: var(--bk-row-padding) var(--bk-panel-padding); }
.pet-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.pet-side { width: 148px; flex: none; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.pet-tap {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
  border-radius: 18px;
  line-height: 0;
}
.pet-tap:focus-visible { outline: 2px solid var(--bk-button-primary); outline-offset: 2px; }
.pet-main { min-width: 0; flex: 1; }
.pet-title { margin: 0; font-size: 16px; font-weight: 600; display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: baseline; }
.pet-mood { font-size: 13px; font-weight: 500; color: var(--bk-button-primary); }
.pet-sub { margin: 4px 0 0; font-size: 12px; color: var(--bk-text-secondary); }
.pet-line { margin: 6px 0 0; font-size: 13px; font-weight: 600; color: var(--bk-button-primary); }
.pet-form { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 10px; }
.pet-form .el-input { width: 180px; }
.pet-species, .pet-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.pet-actions { margin-top: 8px; justify-content: flex-start; }
.pet-species button, .pet-actions button {
  border: 1px solid var(--bk-border-light);
  background: var(--bk-surface);
  color: var(--bk-text);
  border-radius: var(--bk-radius-pill);
  padding: 4px 12px;
  cursor: pointer;
}
.pet-species button.is-on, .pet-actions button.is-on {
  border-color: var(--bk-button-primary);
  background: var(--bk-primary-soft);
  color: var(--bk-button-primary);
}
</style>
