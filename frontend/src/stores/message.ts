import { defineStore } from 'pinia'
import { messageApi } from '@/api'
import { BUDGET_CHANGED, TRANSACTION_CHANGED, bus } from '@/utils/bus'
import type { Message } from '@/types/model'

/** 未读数轮询间隔：单用户桌面场景取宽松值，窗口隐藏时跳过 */
const POLL_INTERVAL = 60_000
/** 抽屉列表每页条数（NEW-12：服务端分页，点击「加载更多」逐页追加） */
const PAGE_SIZE = 30
/** 业务变更后延迟刷新未读数，给后端生成消息留出提交时间 */
const REFRESH_DELAY = 800

/** 并发去重：抽屉打开与角标刷新同时触发时复用同一 promise */
let loadPromise: Promise<void> | null = null
let pollTimer: number | undefined
let busHandler: (() => void) | undefined

/**
 * 站内信 store。
 * 消息由后端生产（预算超支 / 签到 / 等级变动等），前端只做展示与已读维护。
 * 接口为可选能力：读取失败时 available=false，顶栏铃铛整体降级隐藏，不弹错误提示；
 * 写入失败由拦截器提示，随后重新对齐后端状态。
 */
export const useMessageStore = defineStore('message', {
  state: () => ({
    list: [] as Message[],
    /** 未读条数（角标） */
    unreadCount: 0,
    /** 后端站内信接口是否可用 */
    available: false,
    loading: false,
    /** 加载更多进行中（NEW-12） */
    loadingMore: false,
    /** 服务端未读筛选（NEW-12）：true 只拉未读 */
    onlyUnread: false,
    /** 已加载到的页码（NEW-12） */
    pageNum: 1,
    /** 当前筛选下的消息总数（NEW-12，用于判断是否还有更多） */
    total: 0
  }),

  getters: {
    unreadList(): Message[] {
      return this.list.filter((m) => m.status === 0)
    },
    /** 已读条数：为 0 时禁用「清空已读」 */
    readCount(): number {
      return this.list.length - this.unreadList.length
    },
    /** 是否还有更多分页（NEW-12） */
    hasMore(): boolean {
      return this.list.length < this.total
    }
  },

  actions: {
    /** 拉取首页 + 未读数（已有数据且非强制时直接返回） */
    load(force = false): Promise<void> {
      if (!force && this.list.length) return Promise.resolve()
      if (!loadPromise) {
        loadPromise = this.fetchFirstPage().finally(() => {
          loadPromise = null
        })
      }
      return loadPromise
    },

    /** 拉取第一页（重置分页游标），同时刷新未读角标（NEW-12） */
    async fetchFirstPage() {
      this.loading = true
      try {
        const [page, unread] = await Promise.all([
          messageApi.list(1, PAGE_SIZE, this.onlyUnread),
          messageApi.unreadCount()
        ])
        this.list = page.list
        this.total = page.total
        this.pageNum = 1
        this.unreadCount = unread
        this.available = true
      } catch {
        // 接口未就绪：铃铛降级隐藏
        this.available = false
      } finally {
        this.loading = false
      }
    },

    /** 加载下一页并去重追加（NEW-12）；轮询插入的新消息可能造成页边界偏移，故按 id 去重 */
    async loadMore() {
      if (!this.hasMore || this.loadingMore) return
      this.loadingMore = true
      try {
        const next = this.pageNum + 1
        const page = await messageApi.list(next, PAGE_SIZE, this.onlyUnread)
        const seen = new Set(this.list.map((m) => m.id))
        this.list = [...this.list, ...page.list.filter((m) => !seen.has(m.id))]
        this.total = page.total
        this.pageNum = next
      } catch {
        // 加载更多失败：保留已加载内容，不弹错（读取类为 silent）
      } finally {
        this.loadingMore = false
      }
    },

    /** 切换服务端未读筛选并回到第一页（NEW-12） */
    async setOnlyUnread(v: boolean) {
      if (this.onlyUnread === v) return
      this.onlyUnread = v
      await this.fetchFirstPage()
    },

    /** 仅刷新未读数（角标轮询 / 业务动作后），不触碰已加载的列表 */
    async refreshUnread() {
      try {
        this.unreadCount = await messageApi.unreadCount()
        this.available = true
      } catch {
        this.available = false
      }
    },

    /** 单条已读：成功后本地更新，避免整表重拉 */
    async markRead(msg: Message) {
      if (msg.status === 1) return
      try {
        await messageApi.read(msg.id)
        msg.status = 1
        this.unreadCount = Math.max(0, this.unreadCount - 1)
        // 未读筛选下，已读的消息应从列表移除（NEW-12）
        if (this.onlyUnread) {
          this.list = this.list.filter((m) => m.id !== msg.id)
          this.total = Math.max(0, this.total - 1)
        }
      } catch {
        this.refreshUnread()
      }
    },

    /**
     * 全部已读（NEW-12）：调用服务端 markAllRead 覆盖真实全部未读（不限于已加载的），
     * 成功后本地同步为已读并清零角标；未读筛选下列表已无未读，重新拉取对齐。
     */
    async markAllRead() {
      try {
        await messageApi.readAllUnread()
        this.list.forEach((m) => {
          if (m.status === 0) m.status = 1
        })
        this.unreadCount = 0
        if (this.onlyUnread) await this.fetchFirstPage()
      } catch {
        this.refreshUnread()
      }
    },

    async remove(id: number) {
      const target = this.list.find((m) => m.id === id)
      try {
        await messageApi.remove(id)
        this.list = this.list.filter((m) => m.id !== id)
        this.total = Math.max(0, this.total - 1)
        if (target?.status === 0) this.unreadCount = Math.max(0, this.unreadCount - 1)
      } catch {
        this.refreshUnread()
      }
    },

    async clearRead() {
      if (!this.readCount) return
      try {
        await messageApi.clearRead()
        // 清空已读会删除全部已读消息，分页总数变化，重新拉取首页对齐（NEW-12）
        await this.fetchFirstPage()
      } catch {
        this.load(true)
      }
    },

    /**
     * 启动角标轮询与业务事件联动（由消息中心组件挂载时调用）。
     * 轮询只打未读数接口，开销极小；窗口隐藏（最小化 / 屏保）时跳过。
     */
    startAutoRefresh() {
      this.stopAutoRefresh()
      pollTimer = window.setInterval(() => {
        if (document.hidden) return
        this.refreshUnread()
      }, POLL_INTERVAL)
      // 记账 / 预算变更后后端可能生成新消息（超支提醒、经验与等级变动）
      const handler = () => {
        window.setTimeout(() => this.refreshUnread(), REFRESH_DELAY)
      }
      busHandler = handler
      bus.on(TRANSACTION_CHANGED, handler)
      bus.on(BUDGET_CHANGED, handler)
    },

    stopAutoRefresh() {
      if (pollTimer !== undefined) {
        window.clearInterval(pollTimer)
        pollTimer = undefined
      }
      const handler = busHandler
      busHandler = undefined
      if (handler) {
        bus.off(TRANSACTION_CHANGED, handler)
        bus.off(BUDGET_CHANGED, handler)
      }
    }
  }
})
