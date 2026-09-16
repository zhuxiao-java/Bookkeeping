/**
 * 每日天气消息的正文解析。
 * 后端 WeatherServiceImpl 在启动时推送 MessageType.WEATHER 消息，
 * content 存的是 Weather record 序列化后的 JSON（不是给人看的文本），
 * 因此前端需要解析后做结构化渲染；解析失败一律回退纯文本，不影响其他消息。
 */

/** 天气消息正文结构（字段与后端 Weather record 一致） */
export interface WeatherInfo {
  city: string
  description: string
  tempC: string
  feelsLikeC: string
  minTempC: string
  maxTempC: string
  humidity: string
  windKmph: string
}

/**
 * 解析天气消息正文；非天气 JSON（后端改了格式、其他类型的消息）返回 null。
 * 至少要有 description 或 tempC 之一才认作天气正文，避免把别的 JSON 误判进来。
 */
export function parseWeather(content?: string | null): WeatherInfo | null {
  if (!content) return null
  let raw: Record<string, unknown>
  try {
    const parsed = JSON.parse(content) as unknown
    if (typeof parsed !== 'object' || parsed === null) return null
    raw = parsed as Record<string, unknown>
  } catch {
    return null
  }
  if (typeof raw.description !== 'string' && typeof raw.tempC !== 'string') return null
  const text = (key: string) => (typeof raw[key] === 'string' || typeof raw[key] === 'number' ? String(raw[key]) : '')
  return {
    city: text('city'),
    description: text('description'),
    tempC: text('tempC'),
    feelsLikeC: text('feelsLikeC'),
    minTempC: text('minTempC'),
    maxTempC: text('maxTempC'),
    humidity: text('humidity'),
    windKmph: text('windKmph')
  }
}

/** 城市名美化：后端存的是 wttr.in 的查询名（如 beijing），展示时首字母大写 */
export function weatherCity(w: WeatherInfo): string {
  return w.city ? w.city.charAt(0).toUpperCase() + w.city.slice(1) : '当前城市'
}

/** 列表预览摘要，替代原始 JSON：Beijing · 晴 · 25°C · 18~28°C */
export function weatherSummary(w: WeatherInfo): string {
  const parts = [weatherCity(w), w.description].filter(Boolean)
  if (w.tempC) parts.push(`${w.tempC}°C`)
  if (w.minTempC && w.maxTempC) parts.push(`${w.minTempC}~${w.maxTempC}°C`)
  return parts.join(' · ')
}

/** 天气描述 → 图标语义 key（组件内再映射到 Element Plus 图标） */
export type WeatherIconKey = 'sunny' | 'partly' | 'overcast' | 'thunder' | 'pouring' | 'rain' | 'snow'

/**
 * 关键词按优先级匹配：雷 > 大雨 > 雨 > 雪 > 阴/雾 > 多云 > 晴（覆盖“雨夹雪”“雷阵雪”“多云转阴”等组合）。
 * 同时兼容英文描述：wttr.in 的 lang_zh 缺失时后端会回落原文（实测出现过 "Smoky haze"）。
 */
const ICON_RULES: [RegExp, WeatherIconKey][] = [
  [/雷|thunder/i, 'thunder'],
  [/大雨|暴雨|大阵雨|heavy rain|torrential/i, 'pouring'],
  [/雨|rain|shower|drizzle/i, 'rain'],
  [/雪|冰粒|霰|snow|sleet|blizzard/i, 'snow'],
  [/阴|雾|霾|overcast|fog|mist|haze|smoke/i, 'overcast'],
  [/多云|cloud/i, 'partly'],
  [/晴|sun|clear/i, 'sunny']
]

export function weatherIconKey(description?: string | null): WeatherIconKey {
  const text = description ?? ''
  return ICON_RULES.find(([reg]) => reg.test(text))?.[1] ?? 'partly'
}
