# 前端可优化点梳理与建议

> 生成日期：2026-09-19
> 范围：`frontend/`（Electron + Vue 3 + Element Plus + Pinia + ECharts + Vite）
> 说明：仅为分析结论，未修改任何代码。每条附代码证据（文件:行号）、影响与建议，并按优先级排序。
> 总体评价：代码卫生状况良好——无 console.log 残留、无 JSON 深拷贝、无空 catch、echarts 已按需引入、请求层有重试/静默/写保护设计（http.ts）、字典有集中缓存、报表已用 Web Worker。以下优化点多为「好 → 更好」的增量空间。

---

## 优先级 P0：收益明确，建议尽快处理

### 1. Element Plus 全量引入导致主包 1.1MB

- **证据**：
  - `src/main.ts:3-5`：`import ElementPlus from 'element-plus'` + `import 'element-plus/dist/index.css'` 全量注册与全量样式；
  - 实测构建产物：`dist/assets/index-*.js` **1.1MB**、`dist/assets/index-*.css` **384KB**，其中绝大部分为 Element Plus。
- **影响**：应用启动时主进程需解析近 1.5MB 的 JS/CSS；虽然 Electron 走本地 `file://` 无网络开销，但 JS 解析与样式规则构建直接影响开窗速度与常驻内存（大量未使用组件的定义与 CSS 选择器）。
- **建议**：改用 `unplugin-vue-components` + `unplugin-auto-import` 按需注册（Element Plus 官方推荐方案，resolver 已内置）；样式改为组件自带 css 自动引入（`element-plus/es/components/*/style/css`），去掉 `dist/index.css` 与 `dark/css-vars.css` 全量导入。
- **注意**：`ElMessage`/`ElMessageBox` 等函数式组件需确认自动导入的样式 side-effect 正常；改后需回归深色主题与弹层样式。

### 2. 流水页「金额/关键字/标签」过滤走全量拉取 + 循环内重复计算

- **证据**：
  - `src/views/TransactionView.vue:147-171`：`loadByLocalFilter()` 每次 `await transactionApi.selectAll()` 拉取**全部**流水，再在 JS 内 filter；
  - `src/views/TransactionView.vue:159`：`const catIds = categoryIdsWithChildren()` 写在了 `all.filter((t) => ...)` **回调内部**，对每一行都重新执行一次分类后代遍历（`descendantIds` 是无缓存 getter，见第 4 条），复杂度 O(流水数 × 分类数)；
  - 关键字匹配 `matchesKeyword`（L131-144）对每行做 3~5 次 `Array.find` 字典查找（见第 3 条），叠加后单次过滤为 O(n×m)。
- **影响**：数据积累到数万条后，用户每敲一个关键字/改一个金额区间都会全量拉取 + 高复杂度过滤，输入响应明显卡顿，且这类操作往往由 watch/按钮高频触发。
- **建议**（两步，可独立实施）：
  1. **低成本**：把 `catIds`、字典查找索引提升到 filter 回调之外；`all.filter` 前先构建一次性 Map；
  2. **根本**：与后端协商把 amount 区间、关键字（note/分类名/账户名/标签名多字段 LIKE）、tagId 并入 `page` 查询的 `queryList` 能力，删掉本地降级分支；至少给本地降级路径加"同参数结果缓存"。

### 3. 字典 getters 用 `Array.find` 线性查找，调用点 37 处

- **证据**：
  - `src/stores/dict.ts:19-21`：`accountById` / `categoryById` / `tagById` 每次调用 `Array.prototype.find` 遍历全量数组；
  - 全局检索共 37 处调用（views/components/composables），其中热点：`TransactionView.matchesKeyword`（每行 3~5 次）、`DashboardView.rowTags`（每行 tagById×标签数）、各表单的分类/账户回显。
- **影响**：单条 O(n) 看似不贵，但被行级循环放大后是 O(n×m)；字典本身已有 `childrenByParentId` 这类 Map 索引 getter（dict.ts:28-38），说明模式现成。
- **建议**：新增 `accountByIdMap` / `categoryByIdMap` / `tagByIdMap` 三个**带缓存的 computed getter**（返回 `Map`），`xxxById(id)` 改为查 Map；调用方签名不变，迁移成本低。

### 4. `categoryTreeByType` / `descendantIds` 返回函数的 getter 无任何缓存

- **证据**：
  - `src/stores/dict.ts:40-52`：`categoryTreeByType` 每次调用都递归重建整棵分类树并对每个节点 `{ ...c }` 浅拷贝，同一表达式内还会重复 filter+sort；
  - `src/stores/dict.ts:57-71`：`descendantIds(id)` 每次调用做一次 BFS；
  - 调用点：`TransactionView.vue:86`（一次 concat 两棵树的构建）、`TransactionFormDialog.vue:102`、`TransactionView.vue:92`（与第 2 条叠加成行级循环）。
- **影响**：Vue/Pinia 对「返回函数的 getter」不做 memo——函数体会在**每次调用时完整执行**。分类树在流水页每次筛选、录入弹窗每次打开都全量重建。
- **建议**：改为按类型缓存的 getter（如 `expenseCategoryTree` / `incomeCategoryTree` 直接返回数组），内部用一次性 `Map<parentId, children[]>` 分组代替每层 filter；`descendantIds` 用 `Map<id, ids[]>` 惰性缓存，字典刷新时整体失效。

---

## 优先级 P1：结构性优化，建议排期处理

### 5. 页面切换无 keep-alive，每次访问全部重拉数据

- **证据**：
  - `src/layouts/MainLayout.vue:280`：`<router-view />` 未包裹 `<keep-alive>`；
  - 各视图均在 `onMounted` 发起请求（如 `DashboardView.vue:396-404` 的 load + 等级 + 签到，`TransactionView.vue:458` 等），离开再返回即完整重放。
- **影响**：dashboard ↔ transaction ↔ report 之间来回切换时，每次都重复 selectAll/分页/stats 请求与图表重建；`MainLayout.vue:81` 的注释（"各页面通过路由 keep-alive / 自身刷新逻辑处理"）表明原本就设想了 keep-alive，但实际未启用。
- **建议**：`<router-view>` 包 `<keep-alive :include="[7 个视图名]">`，配合 `onActivated` 中按 `bus` 事件标记（`TRANSACTION_CHANGED` 等）做"脏则刷新"，数据不变时零请求。注意 `useIdle`/屏保、图表 resize 在 activated 时的恢复。

### 6. 超大单文件组件（5 个文件超过 800 行）

- **证据**：`views/SettingsView.vue` 1103 行、`views/TransactionView.vue` 1057 行、`views/DashboardView.vue` 1004 行、`views/ReportView.vue` 994 行、`components/QuickRecordDialog.vue` 849 行。
- **影响**：逻辑（数据加载/筛选/图表/表单/批量操作）与模板、样式混杂，改动牵连面大；上一轮 UI 统一任务需要触碰 24 个文件即是佐证。
- **建议**：不做激进重构，按"新改动经过即拆"的童子军原则：优先把 TransactionView 的筛选栏、批量操作条，DashboardView 的图表区块（三块图配置各 ~80 行）拆为子组件/composable（如 `useDashboardStats`），单文件目标 <500 行。

### 7. 构建配置：无 vendor 分包，chunk 警告阈值被调大掩盖问题

- **证据**：
  - `vite.config.ts:26-29`：`build` 仅有 `outDir` 与 `chunkSizeWarningLimit: 2048`，无 `rollupOptions.output.manualChunks`；
  - 实测产物：主 chunk 1.1MB 未触发警告，正是因为阈值调到了 2048。
- **影响**：任何业务代码改动都会使 1.1MB 主 chunk 哈希整体变化；告警阈值抬高后，体积回潮无人察觉。
- **建议**：完成第 1 条后按需引入已大幅减脂，届时把 `chunkSizeWarningLimit` 恢复默认（500）以保留告警；可增加 `manualChunks: { echarts: [...] }` 之类将 `useChart-*.js`（实测 524KB）独立成稳定 vendor chunk。不建议为本地应用引入压缩预检等重型工具链。

---

## 优先级 P2：低收益或需权衡，暂缓即可

### 8. 未读消息 60s 轮询

- **证据**：`src/stores/message.ts:7`（`POLL_INTERVAL = 60_000`）、`:185-198`（轮询 + bus 事件延迟刷新，`document.hidden` 已跳过）。
- **评估**：实现已较克制（只打未读数接口、隐藏时暂停、事件驱动补刷）。若追求即时性，可利用 Electron 主进程与后端的既有连接做 SSE/IPC 推送替代轮询；单机场景收益小，**暂缓**。

### 9. Electron 渲染进程未启用 sandbox

- **证据**：`electron/main.cjs:339-343、426-432`：`contextIsolation: true`、`nodeIntegration: false` 均已配置，但未显式 `sandbox: true`。
- **评估**：现有隔离配置已覆盖主要风险面，Electron 30 默认值也在收紧；显式加 `sandbox: true` 属纵深防御，一行改动，回归preload 能力即可。

### 10. `: any` / `as any` 共 12 处

- **证据**：全局检索 12 处，集中在 ECharts 回调参数（如 `DashboardView.vue:328、358、363` 的 `formatter: (pp: any)`、`onClick((p: any)`）与少量路由 meta。
- **建议**：ECharts 参数可换 `CallbackDataParams`（`echarts/types/dist/shared`）等官方类型；对 `echarts/core` 按需实例，类型可从 `echarts` 的 `ECElementEvent` 取。低优先，随手改。

### 11. 测试覆盖仅限 utils 纯函数

- **证据**：`src/utils/__tests__/` 共 3 个 spec（aggregate / format / reportAgg）；stores、composables、关键交互组件（TransactionFormDialog 的编辑回填、QuickRecordDialog）无测试。
- **背景**：上轮 UI 统一验证中已确认存在既有缺陷「收入记录编辑清空分类预填」——正是表单类逻辑无测试的漏网案例。
- **建议**：优先给 `stores/dict`（getter 语义在 P0 改造中被触碰，测试是重构安全网）与 `TransactionFormDialog` 回填/提交逻辑补 vitest + @vue/test-utils 用例；不必追求组件全覆盖。

---

## 已确认无需处理（避免误列）

| 观察点 | 结论 |
|---|---|
| `main.ts:33` 启动即 `dict.loadAll()` | 有 `loaded/loading` 去重（dict.ts:76），合理 |
| Dashboard/Report 首屏 `Promise.all` 并行 | 已做（DashboardView.vue:85-92 并有注释说明），无需再动 |
| `http.ts` 网络重试 | 写请求 `noRetry` 防重复记账，设计正确 |
| `utils/aggregate.ts` 多重遍历 | 已是 Map 索引 + 单次循环风格（L219/L291），无 O(n²) |
| 报表大计算 | 已有 `reportAgg.worker.ts` Web Worker 通道 |
| 路由懒加载 | 7 个视图全部 `() => import()`（router/index.ts），已达标 |
| 背景图 dataURL 超 localStorage 配额 | settings.ts:83-100 已有失败返回值与降级 |

---

## 建议实施顺序

1. **第一批（性能直给）**：#2 循环提升 → #3/#4 字典 Map 化与 getter 缓存 → 观察流水页/录入弹窗交互耗时；
2. **第二批（启动与产物）**：#1 Element Plus 按需 → #7 恢复 chunk 告警 + vendor 分包；
3. **第三批（体验与工程）**：#5 keep-alive → #11 关键路径测试（可在第一批前先补 #3/#4 的回归测试）→ #6 视图拆分（随后续功能改动进行）；
4. **随手项**：#9 sandbox、#10 any 清理；#8 暂缓。

每批完成后跑 `npm run type-check && npm run test && npm run build`，并用 `dist` 产物体积对比验证第 1、7 条收益。
