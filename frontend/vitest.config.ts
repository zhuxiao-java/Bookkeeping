import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

// 单测配置（NEW-13 / 前端细化 #14）：独立于 vite.config.ts，仅覆盖纯函数逻辑，
// 用 node 环境即可（不加载 vue 插件、不依赖 DOM）。复用 '@' → src 别名。
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // 显式绑定回环 IP，不依赖 localhost 的 DNS 解析（部分环境 /etc/hosts 无 localhost 条目会报 ENOTFOUND）
  server: {
    host: '127.0.0.1'
  },
  test: {
    environment: 'node',
    include: ['src/**/*.{test,spec}.ts'],
    // 纯函数单测无需 API server；关闭并用 forks 池，避免某些环境下对 localhost 的 DNS 解析失败
    api: false,
    pool: 'forks'
  }
})
