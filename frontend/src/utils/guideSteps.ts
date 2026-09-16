/**
 * 新手引导步骤（唯一数据源）。
 *
 * 步骤按 scope 分组：global 为首次启动播放的全局引导，其余与页面路由一一对应。
 * 锚点统一用 `[data-guide="xxx"]` 属性选择器，由各页面模板声明；
 * 可选能力（消息中心、等级入口等）未渲染时对应步骤会被自动跳过，不影响其余步骤。
 */

/** 引导分组：全局 + 七个页面 */
export type GuideScope =
  | 'global'
  | 'dashboard'
  | 'account'
  | 'transaction'
  | 'report'
  | 'budget'
  | 'level'
  | 'settings'

/** 引导标题（用于通知与说明面板） */
export const SCOPE_LABEL: Record<GuideScope, string> = {
  global: '界面总览',
  dashboard: '总览页',
  account: '账户页',
  transaction: '流水页',
  report: '报表页',
  budget: '预算页',
  level: '等级页',
  settings: '设置页'
}

/** 路由路径 → 引导分组（global 不绑路由，仅首次启动播放） */
const SCOPE_BY_PATH: Record<string, GuideScope> = {
  '/dashboard': 'dashboard',
  '/account': 'account',
  '/transaction': 'transaction',
  '/report': 'report',
  '/budget': 'budget',
  '/level': 'level',
  '/settings': 'settings'
}

export function scopeOfPath(path: string): GuideScope | undefined {
  return SCOPE_BY_PATH['/' + (path.split('/')[1] || '')]
}

type GuidePlacement =
  | 'top'
  | 'bottom'
  | 'left'
  | 'right'
  | 'top-start'
  | 'bottom-start'
  | 'bottom-end'
  | 'left-start'
  | 'right-start'

export interface GuideStep {
  /** 锚点选择器；留空则该步居中显示（欢迎语、收尾语） */
  target?: string
  title: string
  description: string
  placement?: GuidePlacement
}

/** 锚点简写：`g('nav')` → `[data-guide="nav"]` */
function g(name: string): string {
  return `[data-guide="${name}"]`
}

export const GUIDE_STEPS: Record<GuideScope, GuideStep[]> = {
  global: [
    {
      title: '欢迎使用记账本',
      description: '接下来 40 秒带你认识界面。随时可以按 Esc 或点右上角关闭，之后也能在使用说明里重看。'
    },
    {
      target: g('nav'),
      placement: 'right',
      title: '侧边导航',
      description: '总览、账户、流水、报表、预算、等级、设置七个页面都在这里；点底部「收起导航」可腾出更多内容空间。'
    },
    {
      target: g('quick-record'),
      placement: 'bottom',
      title: '记一笔',
      description: '任意页面都能快速记账，快捷键 Cmd/Ctrl + N；桌面端还支持 Cmd/Ctrl + Shift + B 全局唤起。'
    },
    {
      target: g('level-entry'),
      placement: 'right',
      title: '我的等级',
      description: '显示当前等级与升级进度，点击进入等级页查看等级阶梯、月度经验与签到日历。'
    },
    {
      target: g('message'),
      placement: 'bottom',
      title: '消息中心',
      description: '预算超支、等级变动等提醒会出现在这里；点消息可查看详情，并跳转到对应的流水或预算。'
    },
    {
      target: g('theme'),
      placement: 'bottom',
      title: '主题切换',
      description: '一键在浅色与深色之间切换，偏好会记住。'
    },
    {
      target: g('help'),
      placement: 'bottom-end',
      title: '使用说明',
      description: '快速上手、功能地图、记账口径、快捷键与常见问题都在这里，也可以从说明页重看引导。'
    }
  ],
  dashboard: [
    {
      target: g('stat-cards'),
      placement: 'bottom',
      title: '本月概览',
      description: '收入、支出、结余与总资产四张卡片；总资产按币种分别汇总。'
    },
    {
      target: g('trend'),
      placement: 'top',
      title: '收支趋势',
      description: '本月每日的收入与支出变化，一眼看出花钱的节奏。'
    },
    {
      target: g('pie'),
      placement: 'left',
      title: '支出分类占比',
      description: '可切换父分类（子分类金额归并到一级）与子分类两种口径，两者总额一致。'
    },
    {
      target: g('pl'),
      placement: 'top',
      title: '近半年盈亏',
      description: '按月展示收入减支出的净额：盈余绿色向上、亏损红色向下，点「查看报表」看完整月度明细。'
    },
    {
      target: g('recent'),
      placement: 'top',
      title: '最近流水与预算',
      description: '下方还会展示最近的几笔记录和当月预算执行情况。'
    }
  ],
  account: [
    {
      target: g('account-create'),
      placement: 'bottom',
      title: '新增账户',
      description: '填写名称、类型、初始余额与币种；类型支持现金、银行卡、支付宝、微信支付。'
    },
    {
      target: g('account-sort'),
      placement: 'bottom',
      title: '排序方式',
      description: '按默认顺序、名称或余额排列账户卡片。'
    },
    {
      target: g('account-list'),
      placement: 'top',
      title: '账户卡片',
      description: '展示余额与币种，可编辑、归档或删除；不常用的账户建议归档，历史流水得以保留。'
    }
  ],
  transaction: [
    {
      target: g('tx-filters'),
      placement: 'bottom',
      title: '组合筛选',
      description: '按收支类型、账户、分类与标签逐层缩小范围。'
    },
    {
      target: g('tx-date'),
      placement: 'bottom',
      title: '日期与关键词',
      description: '选择日期范围，或用本周、本月、今年一键定位；关键词按备注模糊搜索。'
    },
    {
      target: g('tx-create'),
      placement: 'bottom',
      title: '新增流水',
      description: '需要指定日期或补记历史记录时用完整表单，快速记账默认记当天。'
    },
    {
      target: g('tx-table'),
      placement: 'top',
      title: '流水列表',
      description: '收入与支出分色显示，每行可编辑或删除，数据分页展示。'
    }
  ],
  report: [
    {
      target: g('rp-range'),
      placement: 'bottom',
      title: '时间维度',
      description: '日、周、月、年四种粒度，右侧可切换柱状图与折线图。'
    },
    {
      target: g('rp-trend'),
      placement: 'top',
      title: '收支趋势',
      description: '所选范围内的收入与支出对比。'
    },
    {
      target: g('rp-pie'),
      placement: 'left',
      title: '分类占比',
      description: '可在支出与收入之间切换，并按父分类或子分类两种口径统计。'
    },
    {
      target: g('rp-pl'),
      placement: 'top',
      title: '月度盈亏',
      description: '按月汇总的收入减支出净额，盈余绿色向上、亏损红色向下；下方明细表列出每月收入、支出、结余与累计结余，可导出 CSV。'
    },
    {
      target: g('rp-export'),
      placement: 'bottom',
      title: '导出 CSV',
      description: '把当前范围的分类明细导出为表格文件，方便二次分析。'
    }
  ],
  budget: [
    {
      target: g('bd-month'),
      placement: 'bottom',
      title: '切换月份',
      description: '预算按月设置，点左右箭头查看其他月份。'
    },
    {
      target: g('bd-total'),
      placement: 'bottom',
      title: '总预算',
      description: '当月所有支出的上限，进度条会随使用比例变色提醒。'
    },
    {
      target: g('bd-list'),
      placement: 'top',
      title: '分类预算',
      description: '为单个分类设定额度，超支时会在消息中心收到提醒。'
    },
    {
      target: g('bd-create'),
      placement: 'bottom',
      title: '新增预算',
      description: '选择分类与金额；同月同分类只能有一条，重复创建会被拒绝。'
    }
  ],
  level: [
    {
      target: g('lv-current'),
      placement: 'bottom',
      title: '当前等级',
      description: '共 20 个等级，进度条显示距离下一级还差多少经验。'
    },
    {
      target: g('lv-ladder'),
      placement: 'top',
      title: '等级阶梯',
      description: '每个等级都有专属 Logo、名称与经验门槛。'
    },
    {
      target: g('lv-checkin'),
      placement: 'top',
      title: '签到日历',
      description: '签到无需手动操作，应用启动时由后端自动完成；这里查看累计天数、签到经验与最近记录。'
    },
    {
      target: g('lv-logs'),
      placement: 'top',
      title: '月度经验明细',
      description: '按年月列出各项经验来源与数值，可点刷新获取最新数据。'
    }
  ],
  settings: [
    {
      target: g('st-menu'),
      placement: 'right',
      title: '设置菜单',
      description: '个性化、分类管理、标签管理、数据管理、使用说明与关于。'
    },
    {
      target: g('st-menu-preference'),
      placement: 'right',
      title: '个性化',
      description: '主题、金额小数位、背景图与遮罩透明度、息屏屏保时长都在这里调整。'
    },
    {
      target: g('st-menu-category'),
      placement: 'right',
      title: '分类与标签',
      description: '分类最多三层、名称全局唯一；标签用于跨分类标记记录。'
    },
    {
      target: g('st-menu-help'),
      placement: 'right',
      title: '使用说明',
      description: '完整说明文档，也可以从这里重看各页面的引导。'
    }
  ]
}

const POLL_INTERVAL = 100

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 解析某个 scope 的可播放步骤：等待异步渲染的锚点出现，超时仍未出现的步骤直接丢弃。
 * 全部锚点都缺失时返回空数组，调用方据此放弃播放（且不标记为已看过）。
 */
export async function resolveSteps(scope: GuideScope, timeout = 1200): Promise<GuideStep[]> {
  const steps = GUIDE_STEPS[scope]
  const deadline = Date.now() + timeout
  const result: GuideStep[] = []

  for (const step of steps) {
    if (!step.target) {
      result.push(step)
      continue
    }
    let element = document.querySelector(step.target)
    // 共享同一个截止时间：可选能力（铃铛、等级入口）缺失时不会逐个等满 timeout
    while (!element && Date.now() < deadline) {
      await sleep(POLL_INTERVAL)
      element = document.querySelector(step.target)
    }
    if (element) result.push(step)
  }

  return result
}
