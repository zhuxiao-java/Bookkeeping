import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { AxiosRequestConfig } from 'axios'
import type { BaseResponse } from '@/types/model'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 静默请求：失败时不弹全局提示，由调用方自行降级（用于可选能力探测） */
    silent?: boolean
    /** 写请求标记：连接级失败时不自动重试，避免重复提交造成重复记账/重复扣减（NEW-04） */
    noRetry?: boolean
  }
}

/**
 * 成功响应码集合（契约详见 docs/frontend-requirements.md 5.5）：
 * S0806 成功 / S0808 保存成功 / S0810 修改成功 / S0812 删除成功
 */
const SUCCESS_CODES = new Set(['S0806', 'S0808', 'S0810', 'S0812'])

/** 业务错误：携带后端响应码，便于调用方按码处理 */
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 运行时解析 API 基础地址（OPT-08）：
 * Electron 生产环境下后端端口可能因 8080 被占用而降级，主进程探测到实际端口后
 * 经 preload 注入 window.electronAPI.apiBase，优先采用；其次用构建期注入的 VITE_API_BASE；
 * 开发环境（浏览器 / Vite Dev Server）回退到 /api 走代理。
 */
export function resolveApiBase(): string {
  return window.electronAPI?.apiBase || import.meta.env.VITE_API_BASE || '/api'
}

const http = axios.create({
  // 端口运行时解析（OPT-08）：生产走 preload 注入的实际后端地址，开发走 Vite 代理
  baseURL: resolveApiBase(),
  timeout: 10000
})

/**
 * 连接级失败（ERR_NETWORK：后端未就绪/短暂不可用，请求未到达服务端）的静默重试参数。
 * 桌面端内嵌后端自启动，开窗到后端就绪存在几秒窗口期，首屏请求靠重试等待而非误报「无法连接」；
 * 重试上限需覆盖后端正常冷启动耗时（实测 2~4s，首次建库/外网天气推送时更长）
 */
const NETWORK_RETRY_LIMIT = 15
const NETWORK_RETRY_INTERVAL = 1000

http.interceptors.response.use(
  (response) => {
    const body = response.data as BaseResponse
    if (body && SUCCESS_CODES.has(body.code)) {
      return response
    }
    // B 前缀为业务校验失败（warning），S 前缀为系统失败（error）
    const isBiz = !!body?.code?.startsWith('B')
    const msg = body?.msg || '操作失败'
    // silent 请求（如可选能力探测）不弹提示，由调用方自行降级
    if (!response.config.silent) {
      ElMessage({ type: isBiz ? 'warning' : 'error', message: msg })
    }
    return Promise.reject(new ApiError(body?.code ?? 'UNKNOWN', msg))
  },
  async (error) => {
    const config = error.config as (AxiosRequestConfig & { _networkRetries?: number }) | undefined
    // 仅重试连接级失败：请求未送达服务端，重试无副作用；
    // 超时（ECONNABORTED）可能已被服务端处理，不重试
    if (config && error.code === 'ERR_NETWORK') {
      // 写请求（noRetry）不自动重试：连接级失败下请求可能已送达并落库，
      // 盲目重试会造成重复记账/重复扣减；改为提示用户核对后再手动重试（NEW-04）
      if (config.noRetry) {
        if (!config.silent) {
          ElMessage.warning('网络异常，本次操作可能未提交，请核对数据后重试（切勿重复提交）')
        }
        return Promise.reject(error)
      }
      config._networkRetries = (config._networkRetries ?? 0) + 1
      if (config._networkRetries <= NETWORK_RETRY_LIMIT) {
        await new Promise((resolve) => setTimeout(resolve, NETWORK_RETRY_INTERVAL))
        return http.request(config)
      }
    }
    if (!config?.silent) {
      ElMessage.error('无法连接后端服务，请确认 Java 服务已启动（默认端口 8080）')
    }
    return Promise.reject(error)
  }
)

/** 通用请求：校验通过后返回完整响应体 */
export async function request<T extends BaseResponse>(config: AxiosRequestConfig): Promise<T> {
  const resp = await http.request<T>(config)
  return resp.data
}
