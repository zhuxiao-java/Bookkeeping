<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Bell, Delete, Refresh } from '@element-plus/icons-vue'
import { useMessageStore } from '@/stores/message'
import { MESSAGE_TYPE_OPTIONS, messageTypeColor, messageTypeLabel } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'
import { parseWeather, weatherSummary } from '@/utils/weather'
import { messageIcon } from '@/components/messageIcons'
import MessageDetailDialog from '@/components/MessageDetailDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { Message, MessageType } from '@/types/model'

/**
 * 顶栏消息中心：铃铛 + 未读角标 + 抽屉列表 + 详情弹窗。
 * 消息由后端生产，此处只负责展示与已读 / 删除维护；
 * 后端接口未就绪时（store.available=false）整体不渲染，不弹错误提示。
 */
const message = useMessageStore()

const visible = ref(false)
const typeFilter = ref<MessageType | ''>('')
/** 详情弹窗：当前查看的消息 */
const detailVisible = ref(false)
const detailMsg = ref<Message | null>(null)

/** 天气消息的正文是 JSON，解析后供图标与预览使用；其余类型返回 null */
const weatherOf = (item: Message) => (item.type === 'weather' ? parseWeather(item.content) : null)

/** 列表预览：天气消息用摘要替代 JSON 原文，其余直接展示正文 */
function previewOf(item: Message): string {
  const weather = weatherOf(item)
  return weather ? weatherSummary(weather) : item.content
}

/** 列表在后端已按时间倒序 + 未读筛选返回，此处仅做类型本地筛选（NEW-12） */
const filtered = computed(() =>
  message.list.filter((m) => !typeFilter.value || m.type === typeFilter.value)
)

function open() {
  visible.value = true
  // 每次打开强制拉取，保证看到最新消息
  message.load(true)
}

/** 点击列表项：标为已读并打开详情（贺卡 / 长正文在详情里完整展示） */
function onItemClick(item: Message) {
  message.markRead(item)
  detailMsg.value = item
  detailVisible.value = true
}

onMounted(() => {
  // 先探测一次未读数：决定铃铛是否展示，并点亮角标
  message.refreshUnread()
  message.startAutoRefresh()
})

onUnmounted(() => {
  message.stopAutoRefresh()
})
</script>

<template>
  <div v-if="message.available" class="msg-center" data-guide="message">
    <el-tooltip content="消息中心" placement="bottom">
      <el-badge
        class="msg-center__badge"
        :value="message.unreadCount"
        :max="99"
        :hidden="!message.unreadCount"
      >
        <el-button circle aria-label="打开消息中心" @click="open">
          <el-icon><Bell /></el-icon>
        </el-button>
      </el-badge>
    </el-tooltip>

    <el-drawer v-model="visible" size="460px" class="bk-drawer quiet-controls">
      <template #header>
        <div class="msg-head">
          <span class="msg-head__title">消息中心</span>
          <span v-if="message.unreadCount" class="msg-head__unread">{{ message.unreadCount }} 条未读</span>
          <span v-else class="msg-head__read">全部已读</span>
        </div>
      </template>

      <div class="msg-panel">
        <div class="msg-toolbar">
          <el-checkbox
            :model-value="message.onlyUnread"
            size="small"
            @change="message.setOnlyUnread($event as boolean)"
          >
            只看未读
          </el-checkbox>
          <el-select
            v-model="typeFilter"
            class="msg-toolbar__type"
            size="small"
            clearable
            placeholder="全部类型"
            aria-label="消息类型"
          >
            <el-option v-for="o in MESSAGE_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <div class="msg-toolbar__ops">
            <el-tooltip content="刷新" placement="bottom">
              <el-button link size="small" :icon="Refresh" aria-label="刷新消息" @click="message.load(true)" />
            </el-tooltip>
            <el-button
              link
              type="primary"
              size="small"
              :disabled="!message.unreadCount"
              @click="message.markAllRead()"
            >
              全部已读
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              :disabled="!message.readCount"
              @click="message.clearRead()"
            >
              清空已读
            </el-button>
          </div>
        </div>

        <el-scrollbar v-loading="message.loading" class="msg-list">
          <EmptyState v-if="!message.loading && !filtered.length" description="暂无消息" :size="96" />

          <div
            v-for="item in filtered"
            :key="item.id"
            class="msg-item"
            :class="{ 'is-unread': item.status === 0 }"
          >
            <button type="button" class="msg-item__open" :aria-label="`查看消息：${item.title}`" @click="onItemClick(item)">
            <span class="msg-item__icon" :style="{ '--message-color': messageTypeColor(item.type) }">
              <el-icon><component :is="messageIcon(item.type, weatherOf(item))" /></el-icon>
            </span>

            <div class="msg-item__body">
              <div class="msg-item__head">
                <span class="msg-item__title">{{ item.title }}</span>
                <span class="msg-item__type">
                  {{ messageTypeLabel(item.type) }}
                </span>
              </div>
              <div class="msg-item__content">{{ previewOf(item) }}</div>
              <div class="msg-item__time">{{ formatDateTime(item.createTime) }}</div>
            </div>
            </button>

            <el-popconfirm title="删除这条消息？" width="200" @confirm="message.remove(item.id)">
              <template #reference>
                <el-button
                  class="msg-item__del"
                  link
                  type="danger"
                  size="small"
                  :icon="Delete"
                  :aria-label="`删除消息：${item.title}`"
                  @click.stop
                />
              </template>
            </el-popconfirm>
          </div>

          <!-- 分页加载更多（NEW-12） -->
          <div v-if="message.hasMore" class="msg-more">
            <el-button link type="primary" size="small" :loading="message.loadingMore" @click="message.loadMore()">
              加载更多（{{ message.list.length }}/{{ message.total }}）
            </el-button>
          </div>
        </el-scrollbar>
      </div>
    </el-drawer>

    <!-- 详情弹窗：跳转时一并关闭抽屉 -->
    <MessageDetailDialog v-model="detailVisible" :message="detailMsg" @navigate="visible = false" />
  </div>
</template>

<style scoped>
.msg-center {
  display: inline-flex;
  align-items: center;
}

/* 角标不被按钮圆形边界裁切 */
.msg-center__badge :deep(.el-badge__content) {
  z-index: 1;
}

.msg-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.msg-head__title {
  font-size: 16px;
  font-weight: 600;
}

.msg-head__unread {
  font-size: 12px;
  color: var(--el-color-danger);
}

.msg-head__read {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.msg-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.msg-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding-bottom: 12px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.msg-toolbar__type {
  width: 150px;
  margin-left: auto;
}

.msg-toolbar__ops {
  width: 100%;
  justify-content: flex-end;
  flex-wrap: wrap;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.msg-list {
  flex: 1;
  min-height: 0;
}

.msg-more {
  display: flex;
  justify-content: center;
  padding: 10px 0;
}

.msg-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  transition: background-color 0.2s;
}

.msg-item:hover,
.msg-item:focus-within {
  background: var(--bk-surface);
}

.msg-item__open {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex: 1;
  min-width: 0;
  border: 0;
  padding: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.msg-item__icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: color-mix(in srgb, var(--message-color) 14%, var(--bk-surface));
  color: var(--message-color);
  font-size: 15px;
}

.msg-item__body {
  flex: 1;
  min-width: 0;
}

.msg-item__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.msg-item__title {
  font-size: 14px;
  font-weight: 500;
  overflow-wrap: anywhere;
}

/* 未读标题加重，配合左侧圆点区分状态 */
.msg-item.is-unread .msg-item__title {
  font-weight: 700;
}

.msg-item.is-unread .msg-item__title::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--el-color-danger);
  vertical-align: middle;
}

.msg-item__type {
  flex: none;
  font-size: 12px;
}

.msg-item__content {
  margin-top: 4px;
  font-size: 13px;
  line-height: 19px;
  color: var(--el-text-color-regular);
  /* 正文最多三行，避免长文案把列表撑开 */
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.msg-item__time {
  margin-top: 5px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.msg-item__del {
  flex: none;
  opacity: 0;
  transition: opacity 0.2s;
}

.msg-item:hover .msg-item__del,
.msg-item:focus-within .msg-item__del {
  opacity: 1;
}
</style>
