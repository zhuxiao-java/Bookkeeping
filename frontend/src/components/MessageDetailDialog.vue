<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Right } from '@element-plus/icons-vue'
import EmptyState from './EmptyState.vue'
import { messageApi } from '@/api'
import { messageTypeColor, messageTypeLabel } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'
import { parseWeather, weatherCity } from '@/utils/weather'
import { messageIcon } from '@/components/messageIcons'
import type { Message, MessageBizType } from '@/types/model'

/**
 * 站内信详情弹窗（叠在消息抽屉之上）。
 * 正文按纯文本渲染并保留换行；两类消息改用专属版式：
 * type=greeting（贺卡）为渐变卡片 + 可选封面图（cardImage）+ 居中大字；
 * type=weather（每日天气）的 content 是后端 Weather JSON，解析后渲染成天气卡片，
 * 解析失败（后端改了格式）则回退纯文本。
 * 打开时再拉一次 detail 接口补全内容（贺卡正文/封面较长，列表接口未来可能截断），
 * 失败静默降级为列表已有数据，不弹提示。
 */
const props = defineProps<{
  modelValue: boolean
  message: Message | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 跳转前通知父组件关闭抽屉 */
  (e: 'navigate'): void
}>()

const router = useRouter()

/** detail 接口返回的完整消息；未返回时回退列表数据，避免打开瞬间空白 */
const detail = ref<Message | null>(null)

/** 贺卡封面加载失败标记：后端返回的 cardImage URL 可能暂无对应文件，隐藏破图静默降级 */
const coverBroken = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const current = computed<Message | null>(() => detail.value ?? props.message)
const themeColor = computed(() => messageTypeColor(current.value?.type))
const isCard = computed(() => current.value?.type === 'greeting')

/**
 * 贺卡封面地址归一化：后端存根为根相对路径（如 /greeting/birthday.svg）。
 * Electron 打包后走 file:// 加载，前导斜杠会解析到文件系统根（file:///greeting/birthday.svg）而 404 破图，
 * 触发 coverBroken 静默降级、封面消失。去掉前导斜杠改为文档相对路径后，
 * dev（http 基址 /）与 file://（基址 dist/）都能命中 dist/greeting/birthday.svg（与 PRESET_BACKGROUNDS 同策略，hash 路由保证基址恒定）。
 * 绝对地址（http/https、协议相对 //、data:、blob:、file:）原样保留，兼容后端改返回完整下载 URL 的情形。
 */
const coverSrc = computed(() => {
  const raw = current.value?.cardImage ?? ''
  if (!raw) return ''
  if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(raw)) return raw
  return raw.replace(/^\/+/, '')
})

/** 每日天气：正文解析结果，为 null 时按普通文本渲染 */
const weather = computed(() => (current.value?.type === 'weather' ? parseWeather(current.value.content) : null))

/** bizType → 跳转目标 */
const JUMP_TARGET: Record<MessageBizType, { path: string; label: string }> = {
  transaction: { path: '/transaction', label: '交易流水' },
  budget: { path: '/budget', label: '预算管理' },
  level: { path: '/level', label: '等级页面' }
}

/**
 * 支持按 bizId 定位到具体记录的目标页；
 * level 只做页面级跳转（bizId 为经验日志 id，定位价值低），不往 URL 上挂无人消费的参数。
 */
const LOCATABLE: MessageBizType[] = ['transaction', 'budget']

const jumpTarget = computed(() => {
  const bizType = current.value?.bizType
  return bizType ? JUMP_TARGET[bizType] ?? null : null
})

watch(
  () => [props.modelValue, props.message?.id] as const,
  async ([open, id]) => {
    detail.value = null
    coverBroken.value = false
    if (!open || id == null) return
    try {
      detail.value = await messageApi.detail(id, { silent: true })
    } catch {
      // 详情接口不可用：继续用列表数据展示
    }
  },
  { immediate: true }
)

function jump() {
  const target = jumpTarget.value
  const msg = current.value
  if (!target || !msg?.bizType) return
  visible.value = false
  emit('navigate')
  router.push({
    path: target.path,
    query: LOCATABLE.includes(msg.bizType) && msg.bizId != null ? { bizId: String(msg.bizId) } : undefined
  })
}
</script>

<template>
  <el-dialog v-model="visible" width="560px" class="bk-dialog quiet-controls" :title="current?.title || '消息详情'" append-to-body>
    <template #header>
      <div v-if="current" class="msg-detail__head">
        <span class="msg-detail__icon" :style="{ '--message-color': themeColor }">
          <el-icon><component :is="messageIcon(current.type, weather)" /></el-icon>
        </span>
        <div class="msg-detail__heading">
          <div class="msg-detail__title">{{ current.title }}</div>
          <div class="msg-detail__meta">
            <span :style="{ color: themeColor }">{{ messageTypeLabel(current.type) }}</span>
            <el-divider direction="vertical" />
            <span>{{ formatDateTime(current.createTime) }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- 贺卡：渐变卡片 + 封面图 + 居中大字 -->
    <div v-if="current && isCard" class="greeting">
      <img
        v-if="coverSrc && !coverBroken"
        :src="coverSrc"
        class="greeting__cover"
        alt="贺卡封面"
        @error="coverBroken = true"
      />
      <div class="greeting__text">{{ current.content }}</div>
    </div>

    <!-- 天气：后端 content 存 Weather JSON，解析成卡片展示 -->
    <div v-else-if="current && weather" class="weather">
      <div class="weather__head">
        <el-icon class="weather__icon"><component :is="messageIcon(current.type, weather)" /></el-icon>
        <div class="weather__place">
          <div class="weather__city">{{ weatherCity(weather) }}</div>
          <div class="weather__desc">{{ weather.description || '天气未知' }}</div>
        </div>
        <div class="weather__temp">
          <template v-if="weather.tempC">{{ weather.tempC }}<span class="weather__unit">°C</span></template>
          <template v-else>--</template>
        </div>
      </div>
      <div class="weather__grid">
        <div class="weather__cell">
          <span class="weather__label">体感温度</span>
          <span class="weather__value">{{ weather.feelsLikeC ? weather.feelsLikeC + '°C' : '-' }}</span>
        </div>
        <div class="weather__cell">
          <span class="weather__label">今日气温</span>
          <span class="weather__value">
            {{ weather.minTempC && weather.maxTempC ? weather.minTempC + ' ~ ' + weather.maxTempC + '°C' : '-' }}
          </span>
        </div>
        <div class="weather__cell">
          <span class="weather__label">相对湿度</span>
          <span class="weather__value">{{ weather.humidity ? weather.humidity + '%' : '-' }}</span>
        </div>
        <div class="weather__cell">
          <span class="weather__label">风速</span>
          <span class="weather__value">{{ weather.windKmph ? weather.windKmph + ' km/h' : '-' }}</span>
        </div>
      </div>
    </div>

    <!-- 普通消息：纯文本，保留换行与空格排版 -->
    <div v-else-if="current" class="msg-detail__content">{{ current.content }}</div>

    <EmptyState v-else description="消息不存在或已被删除" :size="88" />

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button v-if="jumpTarget" type="primary" @click="jump">
          查看{{ jumpTarget.label }}
          <el-icon class="el-icon--right"><Right /></el-icon>
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.msg-detail__head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  /* 抵消 el-dialog 头部右侧关闭按钮的占位，标题不被挤压 */
  padding-right: 0;
}

.msg-detail__icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--message-color);
  background: color-mix(in srgb, var(--message-color) 14%, var(--bk-surface));
  font-size: 17px;
}

.msg-detail__heading {
  flex: 1;
  min-width: 0;
}

.msg-detail__title {
  font-size: 16px;
  font-weight: 600;
  line-height: 22px;
  /* 长标题最多两行 */
  overflow-wrap: anywhere;
}

.msg-detail__meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.msg-detail__content {
  font-size: 14px;
  line-height: 24px;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 贺卡文案可能较长，正文在弹窗内滚动，避免超出视口 */
.msg-detail__content,
.greeting,
.weather {
  margin-bottom: 16px;
  overflow-wrap: anywhere;
}

/* 贺卡版式：底色与文字色均写死，深色主题下同样可读 */
.greeting {
  padding: 22px 20px;
  border-radius: 14px;
  background: color-mix(in srgb, var(--bk-primary-soft) 45%, var(--bk-surface-2));
  text-align: center;
}

.greeting__cover {
  display: block;
  width: 100%;
  max-height: 220px;
  margin-bottom: 14px;
  border-radius: 10px;
  object-fit: cover;
}

.greeting__text {
  font-size: 16px;
  line-height: 30px;
  color: var(--bk-text);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 天气卡片：底色与文字色均写死，深色主题下同样可读 */
.weather {
  padding: 18px;
  border-radius: 14px;
  background: var(--bk-primary-soft);
  color: var(--bk-text);
}

.weather__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.weather__icon {
  font-size: 32px;
}

.weather__place {
  flex: 1;
  min-width: 0;
}

.weather__city {
  font-size: 15px;
  font-weight: 600;
}

.weather__desc {
  margin-top: 2px;
  font-size: 13px;
  opacity: 0.9;
}

.weather__temp {
  font-size: 32px;
  font-weight: 600;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.weather__unit {
  margin-left: 2px;
  font-size: 15px;
  font-weight: 400;
}

.weather__grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}

.weather__cell {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--bk-surface);
  flex-wrap: wrap;
}

.weather__label {
  font-size: 12px;
  opacity: 0.85;
}

.weather__value {
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.weather__head { flex-wrap: wrap; }
.weather__label { color: var(--bk-text-secondary); }
.msg-detail__meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
</style>
