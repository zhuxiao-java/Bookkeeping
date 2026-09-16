import axios from 'axios'
import { createCrudApi } from './crud'
import { request, resolveApiBase } from './http'
import { IMAGE_TYPE_BACKGROUND } from '@/utils/constants'
import type {
  Account,
  Budget,
  BudgetInfo,
  Category,
  CheckIn,
  DataResponse,
  ExperienceLog,
  LevelConfig,
  LevelInfo,
  Message,
  SearchQuery,
  Tag,
  Transaction,
  TransactionCategoryStat,
  TransactionMonthly,
  TransactionSummary,
  TransactionTrendItem
} from '@/types/model'

export const accountApi = createCrudApi<Account>('account')
export const categoryApi = createCrudApi<Category>('category')
export const tagApi = createCrudApi<Tag>('tag')
export const checkInApi = createCrudApi<CheckIn>('check_in')

/**
 * 交易 API：标准 CRUD + 后端统计聚合端点（stats/*，GAP-05）。
 * 统计端点均为 silent：失败时调用方静默降级到本地聚合（aggregate.ts），不弹错。
 * summary/trend/category 后端收 LocalDateTime，故 start/end 补 T00:00:00 / T23:59:59（含当日全部）；
 * monthly 收 LocalDate，直接传 yyyy-MM-dd。
 */
const transactionCrud = createCrudApi<Transaction>('transaction')
export const transactionApi = {
  ...transactionCrud,

  /**
   * 记账（新增流水）并返回本次获得经验，用于记账成功后的即时激励反馈。
   * 失败由响应拦截器统一提示并抛出（与通用 save 一致）；exp 为 0 表示已封顶/满级。
   */
  async saveWithReward(dto: Partial<Transaction>): Promise<number> {
    const resp = await request<DataResponse<number>>({
      url: '/transaction/saveWithReward',
      method: 'post',
      data: dto,
      noRetry: true
    })
    return Number(resp.data ?? 0)
  },

  /**
   * 最近流水（后端固定按 f_date 倒序取前 5 条，无 limit 参数）。
   * silent：失败时调用方降级为 selectAll 结果本地按日期倒序取前 5（GAP 14.5）。
   */
  async recent(opts?: { silent?: boolean }): Promise<Transaction[]> {
    const resp = await request<DataResponse<Transaction[]>>({
      url: '/transaction/recent',
      method: 'get',
      silent: opts?.silent
    })
    return resp.data ?? []
  },

  /** 区间收支汇总（后端已排除 transfer）；start/end 为 yyyy-MM-dd */
  async summary(start: string, end: string, opts?: { silent?: boolean }): Promise<TransactionSummary> {
    const resp = await request<DataResponse<TransactionSummary>>({
      url: '/transaction/stats/summary',
      method: 'post',
      data: { start: `${start}T00:00:00`, end: `${end}T23:59:59` },
      silent: opts?.silent
    })
    return resp.data
  },

  /** 收支趋势分桶；granularity: day/week/month/year。后端按时间升序返回，key 未补零，勿再字符串排序 */
  async trend(
    start: string,
    end: string,
    granularity: 'day' | 'week' | 'month' | 'year',
    opts?: { silent?: boolean }
  ): Promise<TransactionTrendItem[]> {
    const resp = await request<DataResponse<TransactionTrendItem[]>>({
      url: '/transaction/stats/trend',
      method: 'post',
      data: { start: `${start}T00:00:00`, end: `${end}T23:59:59`, granularity },
      silent: opts?.silent
    })
    return resp.data ?? []
  },

  /** 分类聚合；type: income/expense，groupBy: root（归并到一级）/self（按实际分类）。返回无序，调用方按金额降序 */
  async category(
    start: string,
    end: string,
    type: 'income' | 'expense',
    groupBy: 'root' | 'self',
    opts?: { silent?: boolean }
  ): Promise<TransactionCategoryStat[]> {
    const resp = await request<DataResponse<TransactionCategoryStat[]>>({
      url: '/transaction/stats/category',
      method: 'post',
      data: { start: `${start}T00:00:00`, end: `${end}T23:59:59`, type, groupBy },
      silent: opts?.silent
    })
    return resp.data ?? []
  },

  /** 月度盈亏序列（稀疏，仅含有交易的月）；start/end 为 yyyy-MM-dd，调用方补零为连续月份 */
  async monthly(start: string, end: string, opts?: { silent?: boolean }): Promise<TransactionMonthly> {
    const resp = await request<DataResponse<TransactionMonthly>>({
      url: '/transaction/stats/monthly',
      method: 'post',
      data: { start, end },
      silent: opts?.silent
    })
    return resp.data
  },

  /**
   * 批量删除流水（后端逐条复用单删的余额冲销，整体事务原子）。
   * ids 走逗号拼接的 @RequestParam（同 messageApi.readAll）；返回实际删除条数。
   */
  async batchDelete(ids: number[]): Promise<number> {
    const resp = await request<DataResponse<number>>({
      url: '/transaction/batchDelete',
      method: 'post',
      params: { ids: ids.join(',') },
      noRetry: true
    })
    return Number(resp.data ?? 0)
  },

  /**
   * 批量修改分类（后端整体事务，改后重估受影响月份的预算超支）；返回受影响条数。
   */
  async batchUpdateCategory(ids: number[], categoryId: number): Promise<number> {
    const resp = await request<DataResponse<number>>({
      url: '/transaction/batchUpdateCategory',
      method: 'post',
      params: { ids: ids.join(','), categoryId },
      noRetry: true
    })
    return Number(resp.data ?? 0)
  },

  /**
   * 账户对账：以各账户期初余额为基准按时间重放全部流水，重算当前余额（修复历史漂移）；
   * 返回重放的流水条数。写操作，不自动重试（NEW-07）。
   */
  async reconcile(): Promise<number> {
    const resp = await request<DataResponse<number>>({
      url: '/transaction/reconcile',
      method: 'post',
      noRetry: true
    })
    return Number(resp.data ?? 0)
  }
}

/**
 * 预算 API：标准 CRUD + 后端新增的 searchBudget。
 * searchBudget 按年月返回该月全部预算（总预算 + 分类预算），amountUsed 为后端计算的已用金额。
 */
const budgetCrud = createCrudApi<Budget>('budget')
export const budgetApi = {
  ...budgetCrud,
  /**
   * 按年月查询该月全部预算。
   * year/month 为普通请求参数（后端 searchBudget(int year, int month) 非 @RequestBody，用 params 传）。
   */
  async searchBudget(
    year: number,
    month: number,
    opts?: { silent?: boolean }
  ): Promise<BudgetInfo[]> {
    const resp = await request<DataResponse<BudgetInfo[]>>({
      url: '/budget/searchBudget',
      method: 'post',
      params: { year, month },
      silent: opts?.silent
    })
    return resp.data ?? []
  }
}

/**
 * 用户等级 API（只读）。
 * 三个接口均为 silent：后端未实现时静默失败，界面自行降级隐藏。
 */
export const levelApi = {
  /** 当前等级状态 */
  async current(): Promise<LevelInfo> {
    const resp = await request<DataResponse<LevelInfo>>({
      url: '/level/currentLevel',
      method: 'get',
      silent: true
    })
    return resp.data
  },
  /** 全部等级配置（level 升序） */
  async configs(): Promise<LevelConfig[]> {
    const resp = await request<DataResponse<LevelConfig[]>>({
      url: '/level/configs',
      method: 'get',
      silent: true
    })
    return resp.data ?? []
  },
  /** 月度经验日志（年月倒序） */
  async logs(): Promise<ExperienceLog[]> {
    const resp = await request<DataResponse<ExperienceLog[]>>({
      url: '/level/logs',
      method: 'get',
      silent: true
    })
    return resp.data ?? []
  },
  /** 设置生日（yyyy-MM-dd）；传空串则清除。写入类接口，失败由拦截器提示 */
  async setBirthday(birthday: string): Promise<void> {
    await request({ url: '/level/birthday', method: 'post', params: { birthday }, noRetry: true })
  }
}

/** 标准 CRUD 端点（IBaseController）：list 复用其 page 接口，remove 用于删除单条消息 */
const messageCrud = createCrudApi<Message>('message')

/**
 * 站内信 API（契约详见 docs/frontend-requirements.md 第 13 章）。
 * 消息由后端生产，前端只做展示与已读维护：
 * - 读取类（unreadCount / list）为 silent：接口未就绪时静默失败，顶栏铃铛整体降级隐藏；
 * - 写入类（read / readAll / clearRead / remove）走全局拦截器提示。
 * 同时展开标准 CRUD（IBaseController 的6个端点），其中 remove 用于删除单条消息。
 */
export const messageApi = {
  ...messageCrud,

  /** 未读条数（角标轻量轮询用，避免拉全量） */
  async unreadCount(): Promise<number> {
    const resp = await request<DataResponse<number>>({
      url: '/message/unreadCount',
      method: 'get',
      silent: true
    })
    return Number(resp.data ?? 0)
  },

  /**
   * 分页拉取消息（NEW-12）：走统一 page 接口，按创建时间倒序；
   * onlyUnread=true 时服务端按 f_status=0 过滤（未读筛选下沉后端，不再只在前端已加载范围内过滤）。
   * GAP-12 分页拦截器已生效，page 真正拼 LIMIT，返回当页 list 与全量 total。
   */
  async list(
    pageNum = 1,
    pageSize = 30,
    onlyUnread = false
  ): Promise<{ list: Message[]; total: number }> {
    const queryList: SearchQuery[] = onlyUnread ? [{ key: 'status', value: 0, query: 'eq' }] : []
    const result = await messageCrud.page(pageNum, pageSize, queryList, {
      silent: true,
      // createTime → f_create_time（SortQuery.field() 自动转列名）
      sortList: [{ field: 'createTime', sort: 'desc' }]
    })
    return { list: result.list, total: result.total }
  },

  /** 单条标记已读 */
  async read(id: number): Promise<void> {
    await request({ url: '/message/read', method: 'post', params: { id }, noRetry: true })
  },

  /**
   * 批量标记已读：后端 `readAll(@RequestParam("ids") List<Integer>)` 按 id 逐条置已读，
   * 因此必须传未读消息 id。Spring 支持逗号分隔绑定 List，axios 默认不转义逗号，直接 join 即可。
   */
  async readAll(ids: number[]): Promise<void> {
    await request({ url: '/message/readAll', method: 'post', params: { ids: ids.join(',') }, noRetry: true })
  },

  /**
   * 全部已读（NEW-12）：服务端直接将所有未读置为已读，覆盖真实全部未读（不限于已加载的），
   * 返回实际标记条数。写操作，不自动重试。
   */
  async readAllUnread(): Promise<number> {
    const resp = await request<DataResponse<number>>({
      url: '/message/markAllRead',
      method: 'post',
      noRetry: true
    })
    return Number(resp.data ?? 0)
  },

  /** 清空已读消息 */
  async clearRead(): Promise<void> {
    await request({ url: '/message/clearRead', method: 'post', noRetry: true })
  }
}

/**
 * 图片 API：背景图上传（走后端 image 接口，替代旧的 dataURL 存 localStorage 方案）
 */
export const imageApi = {
  /** 上传背景图，返回后端存储的文件名（供拼下载地址） */
  async uploadBackground(blob: Blob, filename = 'background.jpg'): Promise<string> {
    const form = new FormData()
    form.append('file', blob, filename)
    form.append('imageType', String(IMAGE_TYPE_BACKGROUND))
    const resp = await request<DataResponse<string>>({
      url: '/image/uploadImage',
      method: 'post',
      data: form,
      noRetry: true
    })
    return resp.data
  }
}

/**
 * 备份 / 恢复 / CSV 导入导出 API（后端 /backup/**，GAP-09）。
 * 附件下载走裸 axios + responseType:blob，避开 http.ts 的 JSON 拦截器（附件响应体非 DataResponse）；
 * 上传类（恢复 .db / 导入 CSV）走统一 request（FormData），失败由拦截器提示。
 */
const API_BASE = resolveApiBase()

function fileStamp(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

/** 触发下载一个 blob（对齐 utils/csv.ts 的 anchor 下载方式，Electron 渲染进程同样适用） */
function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

async function downloadAttachment(path: string, fallbackName: string): Promise<void> {
  const resp = await axios.get(`${API_BASE}${path}`, { responseType: 'blob', timeout: 60000 })
  triggerBlobDownload(resp.data as Blob, fallbackName)
}

export interface CsvImportResult {
  total: number
  imported: number
  skipped: number
  duplicates: number
  failures: CsvFailure[]
}

export interface CsvFailure {
  line: number
  reason: string
}

/** CSV 预览行状态：valid 可导入 / invalid 无法解析 / duplicate 重复 */
export type CsvRowStatus = 'valid' | 'invalid' | 'duplicate'

export interface CsvPreviewRow {
  line: number
  date: string
  type: string
  amount: string
  accountName: string
  toAccountName: string
  categoryName: string
  note: string
  status: CsvRowStatus
  reason: string
}

export interface CsvPreviewResult {
  total: number
  validCount: number
  invalidCount: number
  duplicateCount: number
  rows: CsvPreviewRow[]
}

export const backupApi = {
  /** 导出整库 .db 快照 */
  async exportDb(): Promise<void> {
    await downloadAttachment('/backup/export', `bookkeeping-backup-${fileStamp()}.db`)
  },
  /** 导出全部流水 CSV */
  async exportTransactionsCsv(): Promise<void> {
    await downloadAttachment('/backup/csv/transactions', `transactions-${fileStamp()}.csv`)
  },
  /** 导出全部账户 CSV */
  async exportAccountsCsv(): Promise<void> {
    await downloadAttachment('/backup/csv/accounts', `accounts-${fileStamp()}.csv`)
  },
  /** 从 .db 备份恢复；成功后返回 restartRequired（需重启后端加载新库） */
  async importDb(file: File): Promise<{ restartRequired: boolean }> {
    const form = new FormData()
    form.append('file', file, file.name || 'backup.db')
    const resp = await request<DataResponse<{ restartRequired: boolean }>>({
      url: '/backup/import',
      method: 'post',
      data: form,
      timeout: 60000,
      noRetry: true
    })
    return resp.data
  },
  /** 预览流水 CSV 导入结果（不落库），返回逐行 valid/invalid/duplicate 标注 */
  async previewTransactionsCsv(file: File): Promise<CsvPreviewResult> {
    const form = new FormData()
    form.append('file', file, file.name || 'transactions.csv')
    const resp = await request<DataResponse<CsvPreviewResult>>({
      url: '/backup/csv/transactions/preview',
      method: 'post',
      data: form,
      timeout: 60000,
      noRetry: true
    })
    return resp.data
  },
  /** 导入流水 CSV（仅新增，按账户名/分类名解析），skipDuplicates 控制是否跳过重复行 */
  async importTransactionsCsv(file: File, skipDuplicates = true): Promise<CsvImportResult> {
    const form = new FormData()
    form.append('file', file, file.name || 'transactions.csv')
    const resp = await request<DataResponse<CsvImportResult>>({
      url: '/backup/csv/transactions',
      method: 'post',
      data: form,
      timeout: 60000,
      noRetry: true,
      params: { skipDuplicates }
    })
    return resp.data
  }
}

export { ApiError } from './http'
