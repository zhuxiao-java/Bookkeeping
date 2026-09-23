import { createRouter, createWebHashHistory } from 'vue-router'

// Electron 生产环境通过 file:// 协议加载，必须使用 hash 路由
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '总览' }
        },
        {
          path: 'account',
          name: 'account',
          component: () => import('@/views/AccountView.vue'),
          meta: { title: '账户' }
        },
        {
          path: 'transaction',
          name: 'transaction',
          component: () => import('@/views/TransactionView.vue'),
          meta: { title: '流水' }
        },
        {
          path: 'report',
          name: 'report',
          component: () => import('@/views/ReportView.vue'),
          meta: { title: '报表' }
        },
        {
          path: 'monthly-report',
          name: 'monthly-report',
          component: () => import('@/views/MonthlyReportView.vue'),
          meta: { title: 'AI 月报' }
        },
        {
          path: 'budget',
          name: 'budget',
          component: () => import('@/views/BudgetView.vue'),
          meta: { title: '预算' }
        },
        {
          path: 'level',
          name: 'level',
          component: () => import('@/views/LevelView.vue'),
          meta: { title: '等级' }
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: '设置' }
        }
      ]
    }
  ]
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || ''
  document.title = title ? `${title} · 记账本` : '记账本'
})

export default router
