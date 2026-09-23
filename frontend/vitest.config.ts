import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 纯函数默认使用 node；组件测试通过文件注解启用 jsdom，不依赖真实后端。
export default defineConfig({
  plugins: [vue()],
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
