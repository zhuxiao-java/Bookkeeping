import { IMAGE_TYPE_BACKGROUND } from './constants'

/** 自定义背景图处理：压缩后上传后端存储（不再存 localStorage） */

/** 最长边像素上限（超出则等比缩小） */
const MAX_EDGE = 1600
/** JPEG 压缩质量 */
const QUALITY = 0.85
/** 可接受的原始文件大小上限 */
const MAX_FILE_SIZE = 20 * 1024 * 1024

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('图片解码失败，请更换文件'))
    img.src = src
  })
}

/**
 * 读取图片文件并压缩为 JPEG Blob（用于上传后端存储，不再存 localStorage）
 * - 最长边超过 1600px 时等比缩小，压缩体积、适配后端上传大小限制
 * - 透明 PNG 会先垫白底再转 JPEG
 */
export async function compressImage(file: File): Promise<Blob> {
  if (!file.type.startsWith('image/')) {
    throw new Error('请选择图片文件')
  }
  if (file.size > MAX_FILE_SIZE) {
    throw new Error('图片过大（超过 20MB），请更换')
  }

  const objectUrl = URL.createObjectURL(file)
  try {
    const img = await loadImage(objectUrl)
    const scale = Math.min(1, MAX_EDGE / Math.max(img.width, img.height))
    const width = Math.max(1, Math.round(img.width * scale))
    const height = Math.max(1, Math.round(img.height * scale))

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')
    if (!ctx) {
      throw new Error('当前环境不支持 Canvas')
    }
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, width, height)
    ctx.drawImage(img, 0, 0, width, height)

    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', QUALITY))
    if (!blob) {
      throw new Error('图片压缩失败，请更换文件')
    }
    return blob
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

/** 后端图片接口基准（与 http.ts 一致：dev 走 /api 代理，prod 用 VITE_API_BASE） */
const API_BASE = import.meta.env.VITE_API_BASE || '/api'

/** 远程背景值前缀：settings.backgroundImage 以此开头表示后端托管的自定义图 */
const REMOTE_PREFIX = 'remote:'

/** 是否为后端托管的自定义背景 */
export function isRemoteBackground(value: string): boolean {
  return value.startsWith(REMOTE_PREFIX)
}

/**
 * 上传成功后生成可持久化的背景值。
 * 附带上传时间戳作为版本号：后端同类型图片按固定名覆盖存储，
 * 版本号让下载地址变化，避免更换背景时命中浏览器旧缓存。
 */
export function toRemoteBackground(filename: string): string {
  return `${REMOTE_PREFIX}${Date.now()}:${filename}`
}

/**
 * 把 settings.backgroundImage 解析为可用于 CSS url() / img src 的地址：
 * - 远程背景（remote: 前缀）→ 后端下载地址（含版本号）
 * - 预设相对 url / 旧版 dataURL / '' → 原样返回（向后兼容）
 */
export function resolveBackgroundUrl(value: string): string {
  if (!value) return ''
  if (!isRemoteBackground(value)) return value
  const body = value.slice(REMOTE_PREFIX.length)
  const sep = body.indexOf(':')
  const version = sep >= 0 ? body.slice(0, sep) : ''
  const filename = sep >= 0 ? body.slice(sep + 1) : body
  const url = `${API_BASE}/image/download/${encodeURIComponent(filename)}/${IMAGE_TYPE_BACKGROUND}`
  return version ? `${url}?v=${version}` : url
}
