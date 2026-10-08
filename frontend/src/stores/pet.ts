import { defineStore } from 'pinia'
import { petApi } from '@/api'
import type { PetSpecies, PetView } from '@/types/model'

let loadPromise: Promise<void> | null = null

/**
 * 陪伴宠物。接口未就绪时 available=false，总览卡片和屏保都不展示。
 */
export const usePetStore = defineStore('pet', {
  state: () => ({
    view: null as PetView | null,
    available: false,
    loading: false,
    busy: false
  }),

  getters: {
    adopted(): boolean {
      return !!this.view?.adopted
    }
  },

  actions: {
    load(force = false): Promise<void> {
      if (!force && this.view) return Promise.resolve()
      if (!loadPromise) {
        loadPromise = this.fetch().finally(() => {
          loadPromise = null
        })
      }
      return loadPromise
    },

    async fetch() {
      this.loading = true
      try {
        this.view = await petApi.current()
        this.available = true
      } catch {
        this.available = false
      } finally {
        this.loading = false
      }
    },

    async adopt(name: string, species: PetSpecies) {
      this.busy = true
      try {
        this.view = await petApi.adopt(name, species)
        this.available = true
      } finally {
        this.busy = false
      }
    },

    async rename(name: string) {
      this.busy = true
      try {
        this.view = await petApi.rename(name)
        this.available = true
      } finally {
        this.busy = false
      }
    }
  }
})
