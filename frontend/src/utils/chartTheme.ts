/**
 * 图表统一主题：调色板与坐标轴样式，替代各页硬编码颜色。
 * 颜色从设计 token（CSS 变量）实时解析，随浅色/深色主题变化；
 * 每次 render 调用 chartPalette() 取当前主题值，故主题切换后下一次重渲染即生效。
 */

import { DISTINCT_COLORS } from './constants'

export interface ChartPalette {
  income: string
  expense: string
  transfer: string
  primary: string
  accent: string
  axisText: string
  axisLine: string
  splitLine: string
}

/** 读取根元素上的 CSS 变量当前值（解析不到时回退给定默认色） */
function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined' || typeof getComputedStyle !== 'function') return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** 当前主题下的图表调色板 */
export function chartPalette(): ChartPalette {
  return {
    income: cssVar('--bk-income', '#2fb57c'),
    expense: cssVar('--bk-expense', '#f0625d'),
    transfer: cssVar('--bk-transfer', '#3e8fa8'),
    primary: cssVar('--bk-primary', '#1f7a5c'),
    accent: cssVar('--bk-accent', '#ee8b3c'),
    axisText: cssVar('--bk-text-secondary', '#7c837f'),
    axisLine: cssVar('--bk-border', '#e6e1d8'),
    splitLine: cssVar('--bk-border-light', '#efece5')
  }
}

/** 分类轴（xAxis type=category）通用样式 */
export function categoryAxisStyle(p: ChartPalette = chartPalette()) {
  return {
    axisLabel: { color: p.axisText },
    axisLine: { lineStyle: { color: p.axisLine } },
    axisTick: { show: false }
  }
}

/** 数值轴（yAxis type=value）通用样式：极浅分隔线 */
export function valueAxisStyle(p: ChartPalette = chartPalette()) {
  return {
    axisLabel: { color: p.axisText },
    splitLine: { lineStyle: { color: p.splitLine } }
  }
}

// —— 颜色工具：供饼图渲染期去重分配、子分类同族微调复用 ——

type Rgb = [number, number, number]

/** hex（#RGB / #RRGGBB）→ RGB 三元组；无法解析时返回 null */
export function hexToRgb(hex: string): Rgb | null {
  const h = (hex || '').replace('#', '').trim()
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  if (full.length !== 6) return null
  const n = parseInt(full, 16)
  if (Number.isNaN(n)) return null
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/**
 * 感知加权 RGB 距离（redmean 近似，0~约 765）：比纯欧氏距离更贴近人眼对色差的判断，
 * 用于识别「hex 不同但看起来太像」的近似色。
 */
export function colorDistance(a: Rgb, b: Rgb): number {
  const rmean = (a[0] + b[0]) / 2
  const dr = a[0] - b[0]
  const dg = a[1] - b[1]
  const db = a[2] - b[2]
  return Math.sqrt((2 + rmean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rmean) / 256) * db * db)
}

/**
 * 按百分比调整明度：percent<0 变暗、>0 变亮（-100~100）。用于子分类在父色基础上
 * 派生「同色系但可区分」的变体色，保留家族关联又避免同色。
 */
export function shadeColor(hex: string, percent: number): string {
  const rgb = hexToRgb(hex)
  if (!rgb) return hex
  const target = percent < 0 ? 0 : 255
  const p = Math.min(100, Math.abs(percent)) / 100
  const mix = (v: number) => Math.round((target - v) * p + v)
  return (
    '#' +
    [mix(rgb[0]), mix(rgb[1]), mix(rgb[2])]
      .map((v) => v.toString(16).padStart(2, '0'))
      .join('')
  )
}

/** 判定「太像」的距离阈值：低于此值视为撞色，需要重新分配 */
const MIN_COLOR_DISTANCE = 90

/**
 * 渲染期为饼图扇区分配「互不撞色」的颜色，不改动存库数据。
 * 规则：① 优先沿用每项的存库颜色（保留分类身份色）；② 若与已分配色相同或过于接近，
 * 从 DISTINCT_COLORS 里按序取第一个「未用且与已用色都足够远」的色替换；
 * ③ 扇区极多、色池全部过近时，退而取任意未用色，最后用存库色兜底。
 * 返回与入参等长的颜色数组。
 */
export function resolveDistinctColors(items: { color?: string }[]): string[] {
  const usedHex = new Set<string>()
  const usedRgb: Rgb[] = []
  const farFromUsed = (rgb: Rgb) =>
    usedRgb.every((u) => colorDistance(u, rgb) >= MIN_COLOR_DISTANCE)

  const result: string[] = []
  for (const it of items) {
    let chosen: string | null = null
    let chosenRgb: Rgb | null = null

    const preferredRgb = it.color ? hexToRgb(it.color) : null
    if (it.color && preferredRgb && !usedHex.has(it.color.toLowerCase()) && farFromUsed(preferredRgb)) {
      chosen = it.color
      chosenRgb = preferredRgb
    }

    if (!chosen) {
      // 从高区分度色池取第一个未用且足够远的色
      for (const c of DISTINCT_COLORS) {
        if (usedHex.has(c.toLowerCase())) continue
        const rgb = hexToRgb(c)
        if (rgb && farFromUsed(rgb)) {
          chosen = c
          chosenRgb = rgb
          break
        }
      }
    }

    if (!chosen) {
      // 色池全部与已用色过近：退而取任意未用色（优先距离最大者）
      let best: string | null = null
      let bestRgb: Rgb | null = null
      let bestDist = -1
      for (const c of DISTINCT_COLORS) {
        if (usedHex.has(c.toLowerCase())) continue
        const rgb = hexToRgb(c)
        if (!rgb) continue
        const d = usedRgb.length ? Math.min(...usedRgb.map((u) => colorDistance(u, rgb))) : Infinity
        if (d > bestDist) {
          bestDist = d
          best = c
          bestRgb = rgb
        }
      }
      chosen = best
      chosenRgb = bestRgb
    }

    if (!chosen) {
      // 扇区数超过色池容量：用存库色或按序兜底
      chosen = it.color || DISTINCT_COLORS[result.length % DISTINCT_COLORS.length]
      chosenRgb = hexToRgb(chosen)
    }

    result.push(chosen)
    usedHex.add(chosen.toLowerCase())
    if (chosenRgb) usedRgb.push(chosenRgb)
  }
  return result
}
