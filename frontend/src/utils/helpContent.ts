/**
 * 使用说明内容（唯一数据源）。
 *
 * 抽屉版（HelpDrawer）与设置页版（SettingsView → help 面板）共用 HelpPanel 渲染本文件内容，
 * 文案与操作入口只在此处维护一份。新增/调整功能时同步更新对应 section 即可。
 */

import { SCOPE_LABEL, type GuideScope } from './guideSteps'

/** 说明项可携带的操作入口 */
export type HelpAction =
  /** 跳转到指定页面 */
  | { type: 'route'; path: string; query?: Record<string, string> }
  /** 打开快速记账弹窗 */
  | { type: 'quick-record' }
  /** 切换明暗主题 */
  | { type: 'theme' }
  /** 重看指定引导 */
  | { type: 'guide'; scope: GuideScope }
  /** 清空引导的已看记录 */
  | { type: 'guide-reset' }

export interface HelpLink {
  label: string
  action: HelpAction
}

/** 表格型内容（快捷键等） */
export interface HelpTable {
  columns: string[]
  rows: string[][]
}

export interface HelpBlock {
  /** 小标题：功能名或问题 */
  title?: string
  /** 要点行 */
  lines?: string[]
  /** 表格（与 lines 二选一即可，同时存在则先表格后要点） */
  table?: HelpTable
  /** 操作入口 */
  links?: HelpLink[]
}

export interface HelpSection {
  key: string
  title: string
  /** 章节一句话简介 */
  intro?: string
  blocks: HelpBlock[]
}

/** 八段引导的重放入口（全局 + 七个页面），标签与引导标题保持同步 */
const GUIDE_LINKS: HelpLink[] = (Object.keys(SCOPE_LABEL) as GuideScope[]).map(
  (scope): HelpLink => ({ label: SCOPE_LABEL[scope], action: { type: 'guide', scope } })
)

export const HELP_SECTIONS: HelpSection[] = [
  {
    key: 'quickstart',
    title: '快速上手',
    intro: '第一次使用只需三步，两分钟即可开始记账。',
    blocks: [
      {
        title: '第 1 步：创建账户',
        lines: [
          '在「账户管理」新增至少一个账户，填写名称、类型、初始余额与币种。',
          '支持现金、银行卡、支付宝、微信支付四种类型，账户卡片会展示对应图标与余额。'
        ],
        links: [{ label: '打开账户管理', action: { type: 'route', path: '/account' } }]
      },
      {
        title: '第 2 步：记下第一笔',
        lines: [
          '点顶栏「记一笔」按钮，或按 Cmd/Ctrl + N 打开快速记账弹窗。',
          '选择收支类型、账户、分类后输入金额即可保存；分类宫格支持逐级下钻，标签与备注可选填。',
          '需要连续录入时点「保存并记下一笔」，弹窗保留账户与分类，只清空金额、标签与备注。'
        ],
        links: [{ label: '立即记一笔', action: { type: 'quick-record' } }]
      },
      {
        title: '第 3 步：查看总览',
        lines: [
          '「总览」页汇总本月收入、支出、结余与总资产，并展示收支趋势、支出分类占比、最近流水与本月预算执行情况。'
        ],
        links: [{ label: '打开总览', action: { type: 'route', path: '/dashboard' } }]
      }
    ]
  },
  {
    key: 'map',
    title: '功能地图',
    intro: '各个页面的职责，点标题右侧按钮可直达。',
    blocks: [
      {
        title: '总览',
        lines: [
          '四张统计卡：本月收入、本月支出、本月结余、总资产（多币种分别列示）。',
          '本月收支趋势折线图、支出分类占比饼图（可切换父/子分类口径）、最近流水、本月预算进度。',
          '近 6 个月盈亏柱状图：按月显示收入减支出的净额，盈余绿色向上、亏损红色向下，可跳转报表看完整明细。'
        ],
        links: [{ label: '打开', action: { type: 'route', path: '/dashboard' } }]
      },
      {
        title: '账户管理',
        lines: [
          '卡片式账户列表，支持按默认顺序、名称、余额排序。',
          '可新增、编辑、归档、删除账户；不再使用的账户建议归档而非删除，历史流水得以保留。'
        ],
        links: [{ label: '打开', action: { type: 'route', path: '/account' } }]
      },
      {
        title: '交易流水',
        lines: [
          '按类型、账户、分类、标签、关键词与日期范围组合筛选，并提供本周/本月/今年三个快捷日期。',
          '表格分页展示，支持新增、编辑、删除单条流水；收入与支出金额分色显示。'
        ],
        links: [{ label: '打开', action: { type: 'route', path: '/transaction' } }]
      },
      {
        title: '统计报表',
        lines: [
          '日/周/月/年四种时间维度，柱状与折线两种图形样式。',
          '收支趋势、分类占比饼图（支出/收入 + 父/子分类口径）与分类明细表，明细可导出 CSV。',
          '月度盈亏：按月汇总收入减支出的净额，正负分色柱状图 + 明细表（收入/支出/结余/累计结余），可导出 CSV。'
        ],
        links: [{ label: '打开', action: { type: 'route', path: '/report' } }]
      },
      {
        title: 'AI 报告与省钱建议',
        lines: [
          '支持周报、月报、年报。周一至周日为一周，年报按自然年；仅支持已结束周期，默认最近已结束周期。',
          '在「设置 → AI 报告」配置模型，选择周期后先预览实际摘要，再单次确认生成；可能产生供应商费用，不会自动发送或重试。',
          '省钱建议最多三条，展开数据依据可查看对应流水。建议仅供参考，不承诺节省金额，不自动修改预算；实时流水可能与旧报告不同。',
          '周报不折算月预算；年报直接汇总全年流水、展示十二个月趋势，并逐月核对已设置的预算。无记录月份不代表实际零消费。',
          '历史月报和消息继续可读。不同币种独立统计，转账本金不计收支，手续费单列。'
        ],
        links: [{ label: '打开 AI 报告', action: { type: 'route', path: '/ai-report' } }]
      },
      {
        title: '预算管理',
        lines: [
          '按月设置一个总预算与多个分类预算，进度条按阈值变色（未超支绿色、临近橙色、超支红色）。',
          '点击预算卡片可跳转到流水页，查看该预算下的明细记录。'
        ],
        links: [{ label: '打开', action: { type: 'route', path: '/budget' } }]
      },
      {
        title: '等级',
        lines: [
          '共 20 个等级（小白 → 财务之神），页面顶部显示当前等级、进度百分比与升级提示。',
          '「等级阶梯」列出每级 Logo、名称与经验门槛；「月度经验明细」按年月列出各项经验数值，可刷新。',
          '「签到日历」以月历热力展示签到情况，并汇总累计签到天数、签到经验与最近签到明细，可切换月份回看。'
        ],
        links: [{ label: '打开', action: { type: 'route', path: '/level' } }]
      },
      {
        title: '设置',
        lines: [
          '个性化：主题模式、金额小数位、背景图与遮罩透明度、息屏等待时长。',
          '分类管理、标签管理、数据管理、关于，以及本页「使用说明」。'
        ],
        links: [{ label: '打开', action: { type: 'route', path: '/settings' } }]
      }
    ]
  },
  {
    key: 'shortcuts',
    title: '快捷键',
    blocks: [
      {
        table: {
          columns: ['按键', '作用'],
          rows: [
            ['Cmd / Ctrl + N', '打开快速记账弹窗（应用窗口内）'],
            ['Cmd / Ctrl + Shift + B', '全局唤起快速记账（窗口未聚焦时也可用，桌面版）'],
            ['↑ ↓ ← →', '快速记账分类宫格中移动选择'],
            ['Enter', '快速记账金额输入框中直接保存'],
            ['Esc', '关闭当前弹窗、退出新手引导'],
            ['点击任意处 / 任意键', '退出息屏屏保']
          ]
        }
      }
    ]
  },
  {
    key: 'rules',
    title: '记账口径',
    intro: '这些规则决定了图表里的数字是怎么算出来的。',
    blocks: [
      {
        title: '收入、支出与转账',
        lines: [
          '收入与支出按分类参与统计；转账只在账户之间移动资金，不计入收支。',
          '转账需选择转出账户与转入账户，可选填手续费。'
        ]
      },
      {
        title: '父分类与子分类口径',
        lines: [
          '「父分类」口径把子分类的金额归并到其一级分类上，适合看大类结构。',
          '「子分类」口径按流水实际记录的分类统计，适合看明细构成。',
          '两种口径的总额完全一致，只是分组粒度不同；总览饼图与报表页均可切换。'
        ]
      },
      {
        title: '分类层级与命名',
        lines: [
          '分类最多三层：一级、二级可继续添加子分类，第三层为末级。',
          '分类名称全局唯一（不限于同一父级下），重名会被拒绝。',
          '分类分为支出与收入两套，互不混用；已归档分类不再出现在选择器中。'
        ],
        links: [{ label: '管理分类', action: { type: 'route', path: '/settings', query: { menu: 'category' } } }]
      },
      {
        title: '金额与精度',
        lines: [
          '金额需大于 0，最多保留两位小数，负数与多余小数位会被输入校验拦下。',
          '展示精度由「设置 → 个性化 → 金额小数位」控制，默认保留 2 位小数。'
        ]
      },
      {
        title: '账户删除与归档',
        lines: [
          '存在流水的账户无法删除，系统会给出提示；请先归档，或删除其名下流水后再删账户。',
          '归档账户不再出现在记账选择列表中，但余额与历史数据保留。'
        ]
      }
    ]
  },
  {
    key: 'level',
    title: '等级与签到',
    blocks: [
      {
        title: '等级体系',
        lines: [
          '20 个等级按累计经验值逐级解锁，每级都有专属 Logo 与名称，从「小白」到「财务之神」。',
          '达到最高等级后进度条保持满值，页面会给出保持记录的提示。'
        ],
        links: [{ label: '查看等级', action: { type: 'route', path: '/level' } }]
      },
      {
        title: '经验与签到',
        lines: [
          '「月度经验明细」按年月列出各项经验来源与数值，可点刷新获取最新数据。',
          '签到无需手动操作：应用启动时由后端自动完成当天签到，并累计连续天数与经验奖励。',
          '「签到日历」以热力格展示每天是否签到，右侧汇总累计签到天数与签到经验，下方列出最近签到记录。',
          '签到面板在后端签到接口可用时才会展示。'
        ]
      }
    ]
  },
  {
    key: 'guide',
    title: '新手引导',
    intro: '首次启动与首次进入各页面时会给出引导，看过一次后不再自动出现。',
    blocks: [
      {
        title: '重看引导',
        lines: [
          '「界面总览」介绍侧边导航、记一笔、消息中心等公共入口；其余七段分别讲解对应页面的主要区块。',
          '播放过程中可按 Esc 或点右上角关闭，中途退出同样记为已看过。'
        ],
        links: GUIDE_LINKS
      },
      {
        title: '恢复首次使用状态',
        lines: ['清空全部引导记录，之后进入各页面时会重新出现引导提示。'],
        links: [{ label: '重置引导记录', action: { type: 'guide-reset' } }]
      }
    ]
  },
  {
    key: 'faq',
    title: '常见问题',
    blocks: [
      {
        title: '总览页没有数据？',
        lines: [
          '请先在「账户管理」创建账户，再记录至少一笔流水；总览统计以本月数据为主，跨月记录不会计入本月卡片。'
        ],
        links: [{ label: '去记账', action: { type: 'quick-record' } }]
      },
      {
        title: '找不到想要的分类？',
        lines: [
          '到「设置 → 分类管理」按收支类型新增分类，可为一二级分类继续添加子分类（最多三层）。'
        ],
        links: [{ label: '管理分类', action: { type: 'route', path: '/settings', query: { menu: 'category' } } }]
      },
      {
        title: '想换深色模式或背景图？',
        lines: [
          '「设置 → 个性化」可切换明暗主题、选择内置背景或上传自定义背景图，并调节背景遮罩透明度；也可点顶栏月亮/太阳图标快速切换主题。'
        ],
        links: [
          { label: '打开个性化设置', action: { type: 'route', path: '/settings', query: { menu: 'preference' } } },
          { label: '切换主题', action: { type: 'theme' } }
        ]
      },
      {
        title: '屏保出现得太频繁？',
        lines: [
          '「设置 → 个性化 → 息屏等待时长」可调整无操作多久进入屏保，设为 0 即关闭；默认 5 分钟。'
        ],
        links: [{ label: '打开个性化设置', action: { type: 'route', path: '/settings', query: { menu: 'preference' } } }]
      },
      {
        title: '顶栏的消息铃铛不见了？',
        lines: [
          '消息中心依赖后端消息接口，接口不可用时入口会自动隐藏，不影响记账等其他功能。'
        ]
      },
      {
        title: '数据存在哪里，会上传吗？',
        lines: [
          '账单保存在本地 SQLite 数据库；主动确认 AI 报告时，仅把预览中的汇总摘要发给你配置的供应商，默认匿名化分类名，不发送逐笔流水、备注或账户信息。',
          '可在「设置 → 数据管理」备份与恢复数据，备份包含三类报告但会清除 AI 密钥；历史备份可能含旧密钥，请勿外传。'
        ],
        links: [{ label: '打开报表', action: { type: 'route', path: '/report' } }]
      }
    ]
  }
]

/** 单个条目的检索文本（小标题 + 要点 + 表格单元格） */
export function blockSearchText(block: HelpBlock): string {
  return [block.title ?? '', ...(block.lines ?? []), ...(block.table?.rows.flat() ?? [])].join(' ').toLowerCase()
}

/** 章节的检索文本（标题 + 简介 + 全部条目） */
export function sectionSearchText(section: HelpSection): string {
  return [section.title, section.intro ?? '', ...section.blocks.map(blockSearchText)].join(' ').toLowerCase()
}
