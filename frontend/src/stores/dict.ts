import { defineStore } from 'pinia'
import { accountApi, categoryApi, tagApi } from '@/api'
import type { Account, Category, CategoryNode, CategoryType, Tag } from '@/types/model'

/** 账户/分类/标签字典缓存：进入应用后一次性加载，增删改后定向刷新（需求文档 3.2） */
export const useDictStore = defineStore('dict', {
  state: () => ({
    accounts: [] as Account[],
    categories: [] as Category[],
    tags: [] as Tag[],
    loaded: false,
    loading: false
  }),
  getters: {
    /** 未归档账户（下拉选择用） */
    activeAccounts: (s) => s.accounts.filter((a) => a.archived === 0),
    /** 未归档分类 */
    activeCategories: (s) => s.categories.filter((c) => c.archived === 0),
    accountById: (s) => (id?: number | null) => s.accounts.find((a) => a.id === id),
    categoryById: (s) => (id?: number | null) => s.categories.find((c) => c.id === id),
    tagById: (s) => (id?: number | null) => s.tags.find((t) => t.id === id),
    /** 指定类型的一级分类（宫格/级联选择用） */
    rootCategoriesByType() {
      return (type: CategoryType) =>
        this.activeCategories.filter((c) => c.parentId == null && c.type === type)
    },
    /** 指定类型分类 + 其子分类的映射 */
    childrenByParentId() {
      const map = new Map<number, Category[]>()
      for (const c of this.activeCategories) {
        if (c.parentId != null) {
          const list = map.get(c.parentId) ?? []
          list.push(c)
          map.set(c.parentId, list)
        }
      }
      return map
    },
    /** 指定类型的分类树（一级→二级→三级，按 sortOrder 排序），供级联选择用 */
    categoryTreeByType() {
      return (type: CategoryType): CategoryNode[] => {
        const build = (parentId: number | null): CategoryNode[] =>
          this.activeCategories
            .filter((c) => (parentId == null ? c.parentId == null : c.parentId === parentId) && c.type === type)
            .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id)
            .map((c) => {
              const children = build(c.id)
              return children.length ? { ...c, children } : { ...c }
            })
        return build(null)
      }
    },
    /**
     * 指定分类 id 自身 + 全部后代 id（任意深度，含二级/三级），供流水筛选按分类聚合查询用。
     * 与录入端「选中父级即涵盖其下所有子级」的语义保持一致（NEW-01）。
     */
    descendantIds() {
      return (id: number): number[] => {
        const children = this.childrenByParentId
        const result: number[] = [id]
        const stack: number[] = [id]
        while (stack.length) {
          const current = stack.pop()!
          for (const child of children.get(current) ?? []) {
            result.push(child.id)
            stack.push(child.id)
          }
        }
        return result
      }
    }
  },
  actions: {
    /** 首次进入应用加载全部字典（已加载则跳过，force 强制刷新） */
    async loadAll(force = false) {
      if ((this.loaded && !force) || this.loading) return
      this.loading = true
      try {
        const [accounts, categories, tags] = await Promise.all([
          accountApi.selectAll(),
          categoryApi.selectAll(),
          tagApi.selectAll()
        ])
        this.accounts = accounts
        this.categories = categories
        this.tags = tags
        this.loaded = true
      } finally {
        this.loading = false
      }
    },
    async refreshAccounts() {
      this.accounts = await accountApi.selectAll()
    },
    async refreshCategories() {
      this.categories = await categoryApi.selectAll()
    },
    async refreshTags() {
      this.tags = await tagApi.selectAll()
    }
  }
})
