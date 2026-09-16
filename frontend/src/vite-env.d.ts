/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  /** API 基础地址；开发环境留空走 Vite 代理（/api），Electron 生产环境打包时注入完整地址 */
  readonly VITE_API_BASE?: string
}
