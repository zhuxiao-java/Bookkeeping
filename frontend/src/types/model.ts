/**
 * 数据模型与后端 DTO 契约对应
 * 详见 docs/frontend-requirements.md 第 6 章
 */

// —— 通用响应 ——

export interface BaseResponse {
  code: string
  msg: string
}

export interface DataResponse<T> extends BaseResponse {
  data: T
}

export interface PageResponse<T> extends DataResponse<T[]> {
  total: number
  pageNum: number
  pageSize: number
}

// —— 查询 ——

export type QueryOp =
  | 'eq'
  | 'nt_eq'
  | 'like'
  | 'nt_like'
  | 'in'
  | 'nt_in'
  | 'is_null'
  | 'is_nt_null'
  | 'l_like'
  | 'r_like'
  // 区间操作符（>= / <=）：待后端扩展 Query 枚举后生效，契约见 docs/frontend-requirements.md 第 12 章（GAP-04）
  | 'ge'
  | 'le'

export interface SearchQuery {
  /** DTO 属性名（驼峰，如 type / accountId / note），框架自动转换为数据库列名 */
  key: string
  value?: unknown
  query: QueryOp
}

/** 排序方向（后端 Sort 枚举的 JSON 值） */
export type SortOrder = 'asc' | 'desc'

export interface SortQuery {
  /** DTO 属性名（驼峰，如 createTime），框架自动转换为 f_ 下划线列名 */
  field: string
  sort: SortOrder
}

export interface PageRequest {
  pageNum: number
  pageSize: number
  queryList: SearchQuery[]
  /** 排序（可选），不传则不附加 ORDER BY */
  sortList?: SortQuery[]
}

export interface PageResult<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}

// —— 枚举（后端 JSON 序列化值） ——

export type AccountType = 'cash' | 'bank' | 'ali_pay' | 'wechat_pay'
export type CategoryType = 'income' | 'expense'
export type TransactionType = 'income' | 'expense' | 'transfer'
export type CurrencyCode = 'CNY' | 'DOLLAR'
export type ArchivedFlag = 0 | 1

// —— 实体 ——

export interface BaseEntity {
  id: number
  createTime?: string
  updateTime?: string
}

/** 账户 */
export interface Account extends BaseEntity {
  name: string
  type: AccountType
  initialBalance: number
  currentBalance: number
  currency: CurrencyCode
  archived: ArchivedFlag
}

/** 收支分类（支持父子层级，最多三层） */
export interface Category extends BaseEntity {
  name: string
  /** null 表示一级分类 */
  parentId: number | null
  icon: string
  color: string
  type: CategoryType
  sortOrder: number
  archived: ArchivedFlag
}

/** 分类树节点（级联选择用；children 仅在确有子分类时存在） */
export interface CategoryNode extends Category {
  children?: CategoryNode[]
}

/** 交易流水 */
export interface Transaction extends BaseEntity {
  type: TransactionType
  amount: number
  /** 手续费，仅转账使用，默认 0 */
  fee: number
  accountId: number
  /** 转入账户，非转账为 null */
  toAccountId: number | null
  /** 分类，转账可为 null */
  categoryId: number | null
  /** ISO 格式 yyyy-MM-ddTHH:mm:ss */
  transactionDate: string
  note: string
  /** 标签 ID 的 JSON 数组字符串，如 "[1,3]" */
  tags: string
}

/** 预算（categoryId 为 null 表示月度总预算） */
export interface Budget extends BaseEntity {
  categoryId: number | null
  amount: number
  month: number
  year: number
}

/**
 * 预算信息（POST /budget/searchBudget 返回；amountUsed 为后端算好的已用金额）。
 * 后端已补 categoryId、且取数只含 EXPENSE（总预算 amountUsed 正确）；待后端把分类支出
 * 按一级分类上卷后，前端即可全量切换、移除本地聚合。
 * 注：categoryName/main 前端不直接用（名称走 dict.categoryById(categoryId)、主预算以 categoryId==null 判定）。
 */
export interface BudgetInfo {
  id: number
  /** 分类 id；总预算为 null */
  categoryId: number | null
  /** 是否为月度总预算（后端按 categoryName 推导，前端以 categoryId==null 为准） */
  main: boolean
  /** 分类预算名称；总预算为 null（前端改用 dict 取名，不依赖此字段） */
  categoryName: string | null
  /** 预算金额 */
  amount: number
  /** 已用金额 */
  amountUsed: number
}

/** 标签（名称唯一） */
export interface Tag extends BaseEntity {
  name: string
  color: string
}

// —— 交易统计聚合（POST /transaction/stats/*）——
// 金额字段后端为 BigDecimal，JSON 序列化为数字；前端一律 Number() 兜底再用。

/** 区间收支汇总（stats/summary；后端已排除 transfer） */
export interface TransactionSummary {
  income: number
  expense: number
  /** 结余 = 收入 − 支出 */
  balance: number
  /** 计入的流水笔数 */
  count: number
}

/**
 * 趋势分桶项（stats/trend）。后端按 f_date 升序返回，key 未补零
 * （year="2026" / month="2026-9" / day="2026-9-14" / week="2026-9-1"，week 第三段为 ISO 星期值 1~7），
 * 故前端保留后端顺序、不再按字符串排序；展示标签由 key + 粒度推导。
 */
export interface TransactionTrendItem {
  key: string
  income: number
  expense: number
}

/** 分类聚合项（stats/category；后端 HashMap 分组无序，前端按金额降序） */
export interface TransactionCategoryStat {
  categoryId: number
  name: string
  icon: string
  color: string
  amount: number
  count: number
}

/** 月度盈亏单月（stats/monthly 的 rows 项；仅含有交易的月，稀疏） */
export interface TransactionMonthlyInfo {
  key: string
  year: number
  month: number
  income: number
  expense: number
  balance: number
  /** 截至该月的累计结余 */
  cumulative: number
}

/** 月度盈亏序列（stats/monthly） */
export interface TransactionMonthly {
  rows: TransactionMonthlyInfo[]
  surplusMonths: number
  deficitMonths: number
  cumulative: number
}

// —— 用户等级（契约详见 docs/frontend-requirements.md 等级章节） ——

/** 当前等级状态（GET /level/currentLevel） */
export interface LevelInfo {
  level: number
  /** 等级名称（后端字段名即 leveName） */
  leveName: string
  description: string
  icon: string | null
  /** 当前经验值 */
  experience: number
  /** 累计获得经验 */
  totalEarned: number
  /** 累计扣除经验 */
  totalSpent: number
  /** 当前等级起始阈值 */
  currentThreshold: number
  /** 下一级等级号；满级为 null */
  nextLevel: number | null
  nextLevelName: string | null
  /** 下一级阈值；满级为 null */
  nextThreshold: number | null
  /** 生日（yyyy-MM-dd 或 MM-dd），用于生日贺卡；未设置为 null */
  birthday: string | null
}

/** 等级配置（GET /level/configs，按 level 升序） */
export interface LevelConfig extends BaseEntity {
  level: number
  name: string
  expThreshold: number
  icon: string | null
  description: string | null
}

/** 月度经验日志（GET /level/logs，按年月倒序） */
export interface ExperienceLog extends BaseEntity {
  year: number
  month: number
  budgetAmount: number
  actualAmount: number
  /** 正为节省，负为超支 */
  diffAmount: number
  /** 正为加经验，负为扣经验 */
  expChange: number
}

/** 签到记录（/check_in 资源，一天仅一条；后端启动时自动签到） */
export interface CheckIn extends BaseEntity {
  /** 签到日期 yyyy-MM-dd */
  checkDate: string
  /** 本次签到获得总经验（基础+额外） */
  expReward: number
  /** 基础经验 */
  baseExp: number
  /** 连续签到额外奖励经验 */
  bonusExp: number
  /** 连续签到天数（含本次） */
  streakDays: number
}

// —— 站内信（契约详见 docs/frontend-requirements.md 第 13 章） ——

/** 站内信消息类型（后端 MessageType 枚举值；weather 为每日天气，content 存的是 Weather JSON） */
export type MessageType = 'system' | 'budget' | 'level' | 'check_in' | 'greeting' | 'weather' | 'monthly_report' | 'weekly_report' | 'yearly_report'

/** 关联业务类型（后端 MessageBizType 枚举值，用于消息跳转） */
export type MessageBizType = 'transaction' | 'budget' | 'level' | 'monthly_report' | 'weekly_report' | 'yearly_report'

/** 消息状态：0 未读 / 1 已读（DTO 属性 status → 列 f_status） */
export type MessageStatus = 0 | 1

/** 站内信（/message 资源；前端只读 + 标记已读 / 删除，不负责生产消息） */
export interface Message extends BaseEntity {
  title: string
  content: string
  type: MessageType
  /** 关联业务类型，无关联为 null */
  bizType: MessageBizType | null
  /** 关联业务 ID（如超支的预算 ID），无关联为 null */
  bizId: number | null
  status: MessageStatus
  /** 贺卡封面图（预留，无值不渲染）；约定为可直接作 <img src> 的完整 URL */
  cardImage?: string | null
}

/** 解析交易 tags 字段为标签 ID 列表（兼容脏数据，失败返回空数组） */
export function parseTagIds(tags?: string | null): number[] {
  if (!tags) return []
  try {
    const parsed = JSON.parse(tags)
    return Array.isArray(parsed) ? parsed.filter((v) => typeof v === 'number') : []
  } catch {
    return []
  }
}
