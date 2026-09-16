import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// Element Plus 深色主题（html.dark 生效）
import 'element-plus/theme-chalk/dark/css-vars.css'
// 设计 token 与 EP 皮肤覆盖：必须在 EP 样式之后引入才能生效
import './styles/tokens.css'

import App from './App.vue'
import router from './router'
import { useSettingsStore } from './stores/settings'
import { useDictStore } from './stores/dict'
import { installErrorLogger } from './utils/errorLogger'
import './styles/index.css'

const app = createApp(App)

// 全局错误捕获与落盘（OPT-12）：须在 mount 前安装，尽早接管未捕获异常
installErrorLogger(app)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 初始化主题与字典缓存
const settings = useSettingsStore()
settings.applyTheme()
// 跟随系统主题：监听 OS 深/浅色偏好变化（仅 auto 模式生效）
settings.startAutoThemeWatch()
const dict = useDictStore()
dict.loadAll().catch(() => {
  // 请求失败已由拦截器统一提示
})

app.mount('#app')
