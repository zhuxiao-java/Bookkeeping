import {
  Bell,
  Calendar,
  Drizzling,
  Lightning,
  Medal,
  MostlyCloudy,
  PartlyCloudy,
  PieChart,
  Pouring,
  Present,
  Sunny
} from '@element-plus/icons-vue'
import { weatherIconKey } from '@/utils/weather'
import type { Component } from 'vue'
import type { MessageType } from '@/types/model'
import type { WeatherInfo, WeatherIconKey } from '@/utils/weather'

/**
 * 站内信图标映射（消息抽屉与详情弹窗共用）。
 * 图标组件映射独立成文件，避免 utils/constants.ts 引入 Vue 组件。
 */

/** 消息类型 → 图标（与后端 MessageType 枚举对应） */
const TYPE_ICON: Partial<Record<MessageType, Component>> = {
  budget: PieChart,
  check_in: Calendar,
  greeting: Present,
  level: Medal,
  system: Bell,
  weather: Sunny
}

/** 天气细分图标；Element Plus 没有雪花图标，降雪暂用毛毛雨表意 */
const WEATHER_ICON: Record<WeatherIconKey, Component> = {
  sunny: Sunny,
  partly: PartlyCloudy,
  overcast: MostlyCloudy,
  thunder: Lightning,
  pouring: Pouring,
  rain: Drizzling,
  snow: Drizzling
}

/**
 * 取消息图标：天气消息按当天天气描述细分（晴/多云/阴/雷/雨/雪），
 * 其余按类型；后端新增未知类型时回退铃铛。
 */
export function messageIcon(type?: MessageType | string | null, weather?: WeatherInfo | null): Component {
  if (weather) return WEATHER_ICON[weatherIconKey(weather.description)]
  return TYPE_ICON[type as MessageType] ?? Bell
}
