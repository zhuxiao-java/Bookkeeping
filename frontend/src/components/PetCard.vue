<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { usePetStore } from '@/stores/pet'
import type { PetSpecies } from '@/types/model'
import PetSprite from '@/components/PetSprite.vue'

const pet = usePetStore()
const name = ref('')
const species = ref<PetSpecies>('cat')
const renaming = ref(false)
const nextName = ref('')

const speciesOptions: { id: PetSpecies; label: string }[] = [
  { id: 'cat', label: '猫' },
  { id: 'dog', label: '狗' },
  { id: 'bird', label: '鸟' }
]

const stageText = computed(() => {
  const stage = pet.view?.stage ?? 1
  if (stage >= 3) return '相伴'
  if (stage >= 2) return '熟悉'
  return '初识'
})

onMounted(() => {
  void pet.load()
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
</script>

<template>
  <section v-if="pet.available" class="surface pet-card" aria-label="陪伴宠物">
    <div v-if="!pet.adopted" class="pet-row">
      <PetSprite :species="species" mood="calm" :stage="1" />
      <div class="pet-main">
        <h2 class="pet-title">领养一只陪伴宠物</h2>
        <p class="pet-sub">它跟着签到和记账变化心情，不另算经验，也不改账本。</p>
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
      </div>
    </div>
    <div v-else class="pet-row">
      <PetSprite :species="pet.view?.species" :mood="pet.view?.moodKey" :stage="pet.view?.stage" />
      <div class="pet-main">
        <h2 class="pet-title">
          {{ pet.view?.name }}
          <span class="pet-mood">心情 {{ pet.view?.mood }} · {{ pet.view?.moodLabel }}</span>
        </h2>
        <p class="pet-sub">
          {{ stageText }} · 连续签到 {{ pet.view?.streakDays ?? 0 }} 天
          <template v-if="pet.view?.recordedToday"> · 今天已经陪它记过一笔</template>
          <template v-else> · 今天记一笔，它会更有精神</template>
        </p>
        <p class="pet-sub">几天不打开，它会犯困，但不会离开。</p>
        <div v-if="renaming" class="pet-form">
          <el-input v-model="nextName" maxlength="12" aria-label="新的名字" @keyup.enter="saveName" />
          <el-button type="primary" :disabled="!nextName.trim()" :loading="pet.busy" @click="saveName">保存</el-button>
          <el-button text @click="renaming = false">取消</el-button>
        </div>
        <el-button v-else text @click="startRename">改名</el-button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.pet-card { padding: var(--bk-row-padding) var(--bk-panel-padding); }
.pet-row { display: flex; align-items: center; gap: 16px; }
.pet-main { min-width: 0; flex: 1; }
.pet-title { margin: 0; font-size: 16px; font-weight: 600; display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: baseline; }
.pet-mood { font-size: 13px; font-weight: 500; color: var(--bk-button-primary); }
.pet-sub { margin: 4px 0 0; font-size: 12px; color: var(--bk-text-secondary); }
.pet-form { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 10px; }
.pet-form .el-input { width: 180px; }
.pet-species { display: flex; gap: 6px; }
.pet-species button {
  border: 1px solid var(--bk-border-light);
  background: var(--bk-surface);
  color: var(--bk-text);
  border-radius: var(--bk-radius-pill);
  padding: 4px 12px;
  cursor: pointer;
}
.pet-species button.is-on {
  border-color: var(--bk-button-primary);
  background: var(--bk-primary-soft);
  color: var(--bk-button-primary);
}
</style>
