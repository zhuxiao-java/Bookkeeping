import type {
  AccountType,
  ArchivedFlag,
  CategoryType,
  CurrencyCode,
  MessageType,
  TransactionType
} from '@/types/model'

export interface EnumOption<V> {
  value: V
  label: string
}

export const ACCOUNT_TYPE_OPTIONS: EnumOption<AccountType>[] = [
  { value: 'cash', label: '现金' },
  { value: 'bank', label: '银行卡' },
  { value: 'ali_pay', label: '支付宝' },
  { value: 'wechat_pay', label: '微信支付' }
]

/**
 * 账户类型品牌色（支付宝 / 微信支付采用官方色值）
 * 用于图标底色、类型选择器高亮等，避免颜色在各组件里重复硬编码。
 */
export const ACCOUNT_TYPE_COLOR: Record<AccountType, string> = {
  cash: '#67C23A',
  bank: '#409EFF',
  ali_pay: '#1677FF',
  wechat_pay: '#07C160'
}

export const CATEGORY_TYPE_OPTIONS: EnumOption<CategoryType>[] = [
  { value: 'income', label: '收入' },
  { value: 'expense', label: '支出' }
]

export const TRANSACTION_TYPE_OPTIONS: EnumOption<TransactionType>[] = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' }
]

export const CURRENCY_OPTIONS: EnumOption<CurrencyCode>[] = [
  { value: 'CNY', label: '人民币 ¥' },
  { value: 'DOLLAR', label: '美元 $' }
]

export const CURRENCY_SYMBOL: Record<CurrencyCode, string> = {
  CNY: '¥',
  DOLLAR: '$'
}

export const ARCHIVED_LABEL: Record<ArchivedFlag, string> = {
  0: '正常',
  1: '已归档'
}

/** 站内信消息类型（与后端 MessageType 枚举一致） */
export const MESSAGE_TYPE_OPTIONS: EnumOption<MessageType>[] = [
  { value: 'budget', label: '预算' },
  { value: 'check_in', label: '签到' },
  { value: 'greeting', label: '贺卡' },
  { value: 'level', label: '等级' },
  { value: 'system', label: '系统' },
  { value: 'weather', label: '天气' },
  { value: 'monthly_report', label: '月报' }
]

/**
 * 站内信类型主题色（图标底色 / 类型徽标）。
 * 图标组件映射放在 MessageCenter.vue，避免常量文件引入 Vue 组件。
 */
export const MESSAGE_TYPE_COLOR: Record<MessageType, string> = {
  budget: '#E6A23C',
  check_in: '#67C23A',
  greeting: '#E91E63',
  level: '#2f7fe0',
  system: '#909399',
  weather: '#00B8A9',
  monthly_report: '#1f7a5c'
}

function labelOf<V extends string | number>(
  options: EnumOption<V>[],
  value: V | null | undefined
): string {
  return options.find((o) => o.value === value)?.label ?? '-'
}

export const accountTypeLabel = (v?: AccountType | null) => labelOf(ACCOUNT_TYPE_OPTIONS, v)
export const categoryTypeLabel = (v?: CategoryType | null) => labelOf(CATEGORY_TYPE_OPTIONS, v)
export const transactionTypeLabel = (v?: TransactionType | null) => labelOf(TRANSACTION_TYPE_OPTIONS, v)
export const currencyLabel = (v?: CurrencyCode | null) => labelOf(CURRENCY_OPTIONS, v)

/** 站内信类型文案；后端新增未知类型时降级为“系统” */
export const messageTypeLabel = (v?: MessageType | string | null): string =>
  MESSAGE_TYPE_OPTIONS.find((o) => o.value === v)?.label ?? '系统'

/** 站内信类型主题色；未知类型用中性色 */
export const messageTypeColor = (v?: MessageType | string | null): string =>
  MESSAGE_TYPE_COLOR[(v ?? 'system') as MessageType] ?? '#909399'

/** 分类图标分组（后端 icon 字段存图标名，前端负责展示）；
 *  emoji 均取 Unicode 13 及以前，保证 macOS / Windows 10+ 渲染不缺字 */
export interface CategoryIconGroup {
  group: string
  icons: { name: string; emoji: string }[]
}

export const CATEGORY_ICON_GROUPS: CategoryIconGroup[] = [
  {
    group: '餐饮美食',
    icons: [
      { name: 'food', emoji: '🍜' },
      { name: 'takeout', emoji: '🛵' },
      { name: 'hotpot', emoji: '🍲' },
      { name: 'bbq', emoji: '🍢' },
      { name: 'burger', emoji: '🍔' },
      { name: 'fruit', emoji: '🍎' },
      { name: 'vegetable', emoji: '🥬' },
      { name: 'meat', emoji: '🥩' },
      { name: 'seafood', emoji: '🦐' },
      { name: 'rice', emoji: '🍚' },
      { name: 'snack', emoji: '🍟' },
      { name: 'cake', emoji: '🎂' },
      { name: 'icecream', emoji: '🍦' },
      { name: 'candy', emoji: '🍬' },
      { name: 'oil', emoji: '🫒' },
      { name: 'coffee', emoji: '☕' },
      { name: 'tea', emoji: '🍵' },
      { name: 'boba', emoji: '🧋' },
      { name: 'beer', emoji: '🍺' },
      { name: 'wine', emoji: '🍷' }
    ]
  },
  {
    group: '交通出行',
    icons: [
      { name: 'transport', emoji: '🚗' },
      { name: 'taxi', emoji: '🚕' },
      { name: 'bus', emoji: '🚌' },
      { name: 'subway', emoji: '🚇' },
      { name: 'train', emoji: '🚄' },
      { name: 'flight', emoji: '✈️' },
      { name: 'ship', emoji: '🚢' },
      { name: 'bike', emoji: '🚲' },
      { name: 'moto', emoji: '🏍️' },
      { name: 'fuel', emoji: '⛽' },
      { name: 'parking', emoji: '🅿️' },
      { name: 'toll', emoji: '🛣️' },
      { name: 'maintenance', emoji: '🔧' }
    ]
  },
  {
    group: '购物消费',
    icons: [
      { name: 'shopping', emoji: '🛍️' },
      { name: 'supermarket', emoji: '🛒' },
      { name: 'ecommerce', emoji: '📦' },
      { name: 'clothes', emoji: '👕' },
      { name: 'shoes', emoji: '👟' },
      { name: 'bag', emoji: '👜' },
      { name: 'cosmetics', emoji: '💄' },
      { name: 'digital', emoji: '📱' },
      { name: 'computer', emoji: '💻' },
      { name: 'appliance', emoji: '🔌' },
      { name: 'furniture', emoji: '🛋️' },
      { name: 'daily', emoji: '🧻' },
      { name: 'jewelry', emoji: '💍' },
      { name: 'toy', emoji: '🧸' },
      { name: 'stationery', emoji: '✏️' }
    ]
  },
  {
    group: '居住生活',
    icons: [
      { name: 'home', emoji: '🏠' },
      { name: 'rent', emoji: '🏘️' },
      { name: 'property', emoji: '🧾' },
      { name: 'electric', emoji: '💡' },
      { name: 'water', emoji: '💧' },
      { name: 'gas', emoji: '🔥' },
      { name: 'internet', emoji: '📶' },
      { name: 'clean', emoji: '🧹' },
      { name: 'plant', emoji: '🪴' },
      { name: 'repair-home', emoji: '🔨' }
    ]
  },
  {
    group: '娱乐休闲',
    icons: [
      { name: 'fun', emoji: '🎮' },
      { name: 'movie', emoji: '🎬' },
      { name: 'music', emoji: '🎵' },
      { name: 'karaoke', emoji: '🎤' },
      { name: 'travel', emoji: '🧳' },
      { name: 'scenic', emoji: '🏞️' },
      { name: 'hotel', emoji: '🏨' },
      { name: 'sport', emoji: '⚽' },
      { name: 'gym', emoji: '🏋️' },
      { name: 'swim', emoji: '🏊' },
      { name: 'photo', emoji: '📷' },
      { name: 'party', emoji: '🥳' },
      { name: 'pet', emoji: '🐱' },
      { name: 'book', emoji: '📖' }
    ]
  },
  {
    group: '医疗健康',
    icons: [
      { name: 'medical', emoji: '💊' },
      { name: 'hospital', emoji: '🏥' },
      { name: 'dental', emoji: '🦷' },
      { name: 'checkup', emoji: '🩺' },
      { name: 'vaccine', emoji: '💉' }
    ]
  },
  {
    group: '教育育儿',
    icons: [
      { name: 'edu', emoji: '📚' },
      { name: 'school', emoji: '🎓' },
      { name: 'course', emoji: '📝' },
      { name: 'baby', emoji: '👶' },
      { name: 'milk', emoji: '🍼' },
      { name: 'child', emoji: '🧒' }
    ]
  },
  {
    group: '人情往来',
    icons: [
      { name: 'redpacket', emoji: '🧧' },
      { name: 'gift', emoji: '🎉' },
      { name: 'wedding', emoji: '💒' },
      { name: 'donate', emoji: '❤️' },
      { name: 'friend', emoji: '🤝' },
      { name: 'parents', emoji: '👴' }
    ]
  },
  {
    group: '收入理财',
    icons: [
      { name: 'salary', emoji: '💼' },
      { name: 'bonus', emoji: '🎁' },
      { name: 'parttime', emoji: '💰' },
      { name: 'invest', emoji: '📈' },
      { name: 'interest', emoji: '🏦' },
      { name: 'rent-income', emoji: '🔑' },
      { name: 'refund', emoji: '💸' },
      { name: 'lottery', emoji: '🎰' }
    ]
  },
  {
    group: '其他',
    icons: [
      { name: 'other', emoji: '🗂️' },
      { name: 'insurance', emoji: '🛡️' },
      { name: 'loan', emoji: '💳' },
      { name: 'membership', emoji: '🎫' },
      { name: 'government', emoji: '🏛️' }
    ]
  }
]

/** 分类图标名 → emoji（由分组表派生，供 CategoryDot 等展示组件使用；旧库已存的图标名均保留） */
export const CATEGORY_ICON_EMOJI: Record<string, string> = Object.fromEntries(
  CATEGORY_ICON_GROUPS.flatMap((g) => g.icons.map((i) => [i.name, i.emoji]))
)

/** 分类/标签可选色板（前 12 色为历史默认顺序；新增色均为白字/emoji 友好的中深色调）。
 *  手动选色网格与默认取色已改用 COLOR_CHOICES（高区分度色优先），此处保留供历史兼容与账户余额折线取色 */
export const COLOR_PALETTE = [
  '#F56C6C',
  '#E6A23C',
  '#FA8C16',
  '#F4B860',
  '#67C23A',
  '#409EFF',
  '#3A8EE6',
  '#7B68EE',
  '#9B59B6',
  '#E91E63',
  '#909399',
  '#00B8A9',
  '#D46B08',
  '#DAA520',
  '#389E0D',
  '#5B8C00',
  '#13C2C2',
  '#096DD9',
  '#2F54EB',
  '#722ED1',
  '#EB2F96',
  '#8D6E63',
  '#606266',
  '#303133'
]

/**
 * 高区分度色池（Tableau 风格分类色，按色相/明度最大化间隔排列）。
 * 用途：① 饼图渲染期为撞色扇区补色的候选池；② 新建分类/标签的默认取色顺序，
 * 使自动分配的默认色天然拉开距离。相邻色即使在饼图小扇区里也能肉眼区分。
 */
export const DISTINCT_COLORS = [
  '#E15759', // 红
  '#4E79A7', // 蓝
  '#59A14F', // 绿
  '#F28E2B', // 橙
  '#B07AA1', // 紫
  '#76B7B2', // 青
  '#EDC948', // 黄
  '#9C755F', // 棕
  '#FF9DA7', // 粉
  '#86BCB6', // 灰青
  '#8CD17D', // 浅绿
  '#D37295', // 玫红
  '#A0CBE8', // 浅蓝
  '#F1CE63', // 米黄
  '#B6992D', // 橄榄
  '#499894' // 深青
]

/**
 * 手动选色网格：高区分度色在前，其后补充历史色板，按 hex 去重（大小写不敏感）。
 * 既保证默认/推荐色区分度，又保留 COLOR_PALETTE 的全部可选色。
 */
export const COLOR_CHOICES: string[] = (() => {
  const seen = new Set<string>()
  const out: string[] = []
  for (const c of [...DISTINCT_COLORS, ...COLOR_PALETTE]) {
    const k = c.toLowerCase()
    if (!seen.has(k)) {
      seen.add(k)
      out.push(c)
    }
  }
  return out
})()

/** 交易类型对应的金额颜色 class */
export const TRANSACTION_TYPE_COLOR_CLASS: Record<TransactionType, string> = {
  income: 'amount-income',
  expense: 'amount-expense',
  transfer: 'amount-transfer'
}

/**
 * 内置背景预设（frontend/public/backgrounds 下的矢量图）
 * url 使用相对路径，开发（http://127.0.0.1:5173）与打包后（file://…/dist/index.html）
 * 都能正确解析；矢量 SVG 在任意窗口尺寸下不失真。
 */
export interface PresetBackground {
  id: string
  name: string
  url: string
  /** 推荐搭配的主题 */
  hint: string
}

export const PRESET_BACKGROUNDS: PresetBackground[] = [
  { id: 'mist', name: '晨雾', url: 'backgrounds/preset-mist.svg', hint: '浅色' },
  { id: 'paper', name: '纸韵', url: 'backgrounds/preset-paper.svg', hint: '浅色' },
  { id: 'night', name: '夜航', url: 'backgrounds/preset-night.svg', hint: '深色' }
]

/** 判断当前背景值是否为内置预设 */
export const isPresetBackground = (value: string) =>
  PRESET_BACKGROUNDS.some((p) => p.url === value)

/** 后端图片类型（ImageType 枚举的 status 值）：背景图上传/下载用 */
export const IMAGE_TYPE_BACKGROUND = 2

/** 后端图片类型（ImageType 枚举的 status 值）：贺卡封面图（MS-02 / 13.7）。
 *  贺卡封面由后端生产并返回可直接作 <img src> 的完整 URL，前端仅渲染；
 *  此常量与后端 ImageType.GREETING_CARD(3) 对齐，供前端上传/拼接贺卡封面地址时复用。 */
export const IMAGE_TYPE_GREETING_CARD = 3
