import { request } from './http'
import type {
  BaseEntity,
  DataResponse,
  PageRequest,
  PageResponse,
  PageResult,
  SearchQuery,
  SortQuery
} from '@/types/model'

export interface CrudApi<T extends BaseEntity> {
  /**
   * 分页条件查询；opts.silent=true 时失败不弹全局提示（供调用方探测/降级），
   * opts.sortList 传排序（field 为 DTO 驼峰属性名，sort 为 asc/desc）
   */
  page(
    pageNum: number,
    pageSize: number,
    queryList?: SearchQuery[],
    opts?: { silent?: boolean; sortList?: SortQuery[] }
  ): Promise<PageResult<T>>
  /** 查询全部；opts.silent=true 时失败不弹全局提示（供可选能力降级） */
  selectAll(opts?: { silent?: boolean }): Promise<T[]>
  /** 新增 */
  save(dto: Partial<T>): Promise<void>
  /** 修改（按 dto.id） */
  update(dto: T): Promise<void>
  /** 详情；opts.silent=true 时失败不弹全局提示（供调用方降级到已有数据） */
  detail(id: number, opts?: { silent?: boolean }): Promise<T>
  /** 删除 */
  remove(id: number): Promise<void>
}

/**
 * 按资源创建标准 CRUD API。
 * 五个资源端点完全一致（IBaseController 契约）：
 * POST /{res}/page、GET /{res}/selectAll、POST /{res}/save、
 * POST /{res}/update、GET /{res}/detail/{id}、POST /{res}/delete?id=
 */
export function createCrudApi<T extends BaseEntity>(resource: string): CrudApi<T> {
  const base = `/${resource}`
  return {
    async page(pageNum, pageSize, queryList = [], opts) {
      // queryList 必须传数组：后端 bindQueryWrapper 直接遍历，null 会 NPE
      const data: PageRequest = { pageNum, pageSize, queryList }
      // sortList 仅在需要时传：后端 bindSortQueryWrapper 对空集合直接跳过
      if (opts?.sortList?.length) data.sortList = opts.sortList
      const resp = await request<PageResponse<T>>({ url: `${base}/page`, method: 'post', data, silent: opts?.silent })
      return {
        list: resp.data ?? [],
        total: resp.total ?? 0,
        pageNum: resp.pageNum ?? pageNum,
        pageSize: resp.pageSize ?? pageSize
      }
    },
    async selectAll(opts) {
      const resp = await request<DataResponse<T[]>>({ url: `${base}/selectAll`, method: 'get', silent: opts?.silent })
      return resp.data ?? []
    },
    async save(dto) {
      await request({ url: `${base}/save`, method: 'post', data: dto, noRetry: true })
    },
    async update(dto) {
      await request({ url: `${base}/update`, method: 'post', data: dto, noRetry: true })
    },
    async detail(id, opts) {
      const resp = await request<DataResponse<T>>({ url: `${base}/detail/${id}`, method: 'get', silent: opts?.silent })
      return resp.data
    },
    async remove(id) {
      await request({ url: `${base}/delete`, method: 'post', params: { id }, noRetry: true })
    }
  }
}
