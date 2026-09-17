# 记账桌面应用（Bookkeeping）前端需求文档

| 项目 | 内容 |
| ---- | ---- |
| 文档版本 | v1.0 |
| 编写日期 | 2026-09-03 |
| 上游文档 | [overview.md](./overview.md)（总体需求与架构文档） |
| 后端基线 | Bookkeeping 1.0-SNAPSHOT（Spring Boot + SQLite + self-framework） |
| 下游文档 | [desktop-roadmap.md](./desktop-roadmap.md)（桌面端待开发功能与优化清单 / Backlog） |
| 读者 | 前端开发、UI 设计、测试、后端对接人员 |

---

## 1. 文档说明

本文档基于 [overview.md](./overview.md) 的总体需求，结合后端 Bookkeeping 服务**当前已实现的接口能力**，细化为可直接指导前端开发的需求文档。

后端现状已逐项核对：5 个资源（账户、交易、分类、预算、标签）均通过自研框架 `IBaseController` 暴露统一的 6 个 REST 端点，统一响应结构、枚举序列化值、业务错误码均已确认。统计聚合已由后端补齐、前端已接入（交易 `stats/*` 见第 14 章、预算 `searchBudget` 返回 `amountUsed`）；仍暂不具备的能力（区间筛选、余额联动等）在第 8 章"后端依赖与差距清单"中集中列出，并给出前端过渡方案。

## 2. 产品概述

### 2.1 产品定位与目标用户

（继承 overview.md）跨平台离线桌面记账应用，数据存储在本地 SQLite。

- 个人用户：日常收支记录、消费分析、预算控制；
- 家庭用户：多人共同记账，共享账户与分类；
- 小型工作室/自由职业者：项目收支跟踪、简易财务报表。

### 2.2 前端技术选型

| 分类 | 技术 | 说明 |
| ---- | ---- | ---- |
| 桌面壳 | Electron | 主进程负责窗口、托盘、快捷键、Java 子进程生命周期管理 |
| 前端框架 | Vue 3 + TypeScript + Vite | 组合式 API，类型安全 |
| UI 组件库 | **Element Plus** | overview.md 中写的是 Element UI，其仅支持 Vue 2 且已停止维护；Vue 3 对应版本为 Element Plus，组件 API 高度相似，此处按 Element Plus 执行 |
| 图表 | ECharts 5 | 趋势图、饼图/环形图、余额变化图 |
| 状态管理 | Pinia | 账户/分类/标签字典缓存、用户偏好设置 |
| 路由 | Vue Router 4 | 侧边导航 + 内容区布局 |
| HTTP | Axios | 统一封装 BaseURL、超时、响应码拦截 |
| 国际化 | vue-i18n | 中文（默认）/英文 |
| 构建/打包 | electron-builder | 配合后端 jpackage 产物生成安装包 |

### 2.3 运行架构

```
┌─────────────────────────── Electron 应用 ───────────────────────────┐
│  主进程（Node.js）                                                   │
│   ├─ 窗口/托盘/全局快捷键管理                                         │
│   ├─ Java 后端子进程管理（启动 jpackage 产物、健康检查、退出回收）      │
│   └─ 本地用户偏好持久化（窗口位置、主题等，electron-store）             │
│                                                                    │
│  渲染进程（Vue 3 应用）                                              │
│   └─ Axios ──HTTP(127.0.0.1:8080)──▶ Java 后端 ──▶ SQLite(./data)  │
└────────────────────────────────────────────────────────────────────┘
```

- 生产环境：渲染页面由 `file://` 协议加载，访问 `http://127.0.0.1:8080/api` 无跨域问题（后端未配置 CORS）。
- 开发环境：Vite Dev Server（默认 5173）访问 8080 存在跨域，**必须配置 Vite proxy** 将 `/api` 代理到 `http://127.0.0.1:8080`。
- 后端 BaseURL 必须做成可配置项（默认 `http://127.0.0.1:8080/api`），为后续端口冲突时动态分配端口预留能力。

### 2.4 后端进程管理（前端职责，P0）

| 编号 | 需求 | 说明 |
| ---- | ---- | ---- |
| RUN-01 | 启动 Java 子进程 | 应用启动时拉起后端进程；启动期间展示启动页（Logo + 进度指示） |
| RUN-02 | 健康检查 | 轮询任一轻量接口（如 `GET /api/tag/selectAll`，间隔 500ms，最长等待 30s），成功后进入主界面 |
| RUN-03 | 启动失败处理 | 超时仍不可达时展示错误页，提供"重试"与"查看日志"入口 |
| RUN-04 | 异常退出守护 | 运行中检测后端进程退出，弹窗提示并尝试自动重启（最多 3 次） |
| RUN-05 | 退出回收 | 应用退出时终止 Java 子进程，避免残留进程占用数据库文件 |

## 3. 总体设计

### 3.1 布局与导航

遵循 overview.md 第 5 节界面要求：主界面 = 左侧固定导航栏 + 右侧内容区。导航项如下：

| 菜单 | 图标建议 | 路由 | 说明 |
| ---- | ---- | ---- | ---- |
| 总览 | Odometer | `/dashboard` | 增强项：本月收支概览、总资产、最近流水、预算概况（overview.md 未列，建议增加） |
| 账户 | Wallet | `/account` | 账户管理（overview.md 原有） |
| 流水 | Tickets | `/transaction` | 收支记录（overview.md 原有） |
| 报表 | DataAnalysis | `/report` | 统计报表（overview.md 原有） |
| 预算 | PieChart | `/budget` | 预算管理（overview.md 原有） |
| 设置 | Setting | `/settings` | 分类/标签管理、个性化、数据管理、使用说明（见 4.10） |

- 顶栏：当前页面标题 + 全局"记一笔"按钮（醒目色，任意页面可达）+ 消息中心铃铛（未读角标，见 4.9）+ 主题切换按钮 + 使用说明入口（见 4.10）。
- 支持窗口最小宽度 1024px，导航栏可折叠。
- 快捷记账弹窗（全局）：点击"记一笔"或托盘/全局快捷键唤出，紧凑表单，保存后 toast 提示并可连续记账。

### 3.2 全局交互规范

| 规范 | 内容 |
| ---- | ---- |
| 金额输入 | 仅允许正数，最多两位小数；输入框前缀显示货币符号；禁止科学计数法；提交时以字符串或数字传输，**前端内部计算（报表/预算已用）统一转"分"整数运算，避免浮点误差** |
| 金额展示 | 按设置中的小数位数（默认 2 位）格式化；收入绿色 `+`，支出红色 `-`，转账中性色；千分位分隔 |
| 日期 | 传输格式 ISO `yyyy-MM-ddTHH:mm:ss`；展示格式 `yyyy-MM-dd HH:mm`（按语言环境） |
| 空状态 | 列表/图表/预算无数据时展示插画 + 引导文案 + 主操作按钮（如"创建第一个账户"） |
| 加载态 | 表格 skeleton；按钮提交中 loading 并防重复提交 |
| 错误提示 | 业务错误（B 码）ElMessage warning 展示后端 msg；系统错误（S0814 等）ElMessage error |
| 二次确认 | 删除账户、删除流水、删除预算、清空数据、删除有子分类的分类等场景必须二次确认（危险操作用红色确认按钮） |
| 字典缓存 | 账户/分类/标签进入应用后一次性加载至 Pinia，增删改后同步刷新，供各页面下拉与报表展示名称/图标/颜色 |

## 4. 功能模块需求

功能编号规则：`FR-{模块}-{序号}`。优先级：P0 阻断核心流程，P1 核心体验，P2 增强，P3 远期。

### 4.1 账户管理（/account）

#### 4.1.1 功能清单

| 编号 | 功能 | 优先级 | 依赖接口 |
| ---- | ---- | ---- | ---- |
| FR-ACC-01 | 账户列表展示 | P0 | `POST /api/account/page` |
| FR-ACC-02 | 新增账户 | P0 | `POST /api/account/save` |
| FR-ACC-03 | 编辑账户 | P0 | `POST /api/account/update` |
| FR-ACC-04 | 删除账户 | P0 | `POST /api/account/delete?id=` |
| FR-ACC-05 | 归档/取消归档（对应 overview.md"隐藏"） | P1 | `POST /api/account/update` |
| FR-ACC-06 | 列表排序（名称/余额/创建时间） | P1 | 前端本地排序 |
| FR-ACC-07 | 归档账户过滤开关 | P1 | 前端过滤 |
| FR-ACC-08 | 账户间转账入口 | P1 | 跳转流水页转账表单 |

#### 4.1.2 账户列表

- 表格列：账户名称、类型（图标 + 文案）、币种、初始余额、当前余额（重点色）、状态（正常/已归档）、操作（编辑/归档/删除）。
- 顶部汇总条：账户总数、各币种总资产（按币种分组汇总 currentBalance，不做汇率换算）。
- 排序说明：后端分页接口不支持排序参数，排序在前端当前页数据上本地执行；数据量增长后需后端支持（见第 8 章 GAP-07）。

#### 4.1.3 新增/编辑账户（对话框）

| 字段 | 控件 | 必填 | 校验/说明 |
| ---- | ---- | ---- | ---- |
| 账户名称 name | 文本框 | 是 | 1~20 字符；重名时后端返回 B001，前端 toast 提示 |
| 账户类型 type | 单选（带图标） | 是 | cash 现金 / bank 银行卡 / ali_pay 支付宝 / wechat_pay 微信支付 |
| 初始余额 initialBalance | 数字输入 | 是 | ≥ 0，两位小数，默认 0 |
| 币种 currency | 下拉 | 是 | CNY 人民币（默认）/ DOLLAR 美元 |
| 归档状态 archived | 开关 | 否 | 正常 0（默认）/ 已归档 1；编辑时可见 |

- 新建成功后当前余额显示初始值；编辑初始余额导致的余额重算依赖后端联动逻辑（当前后端未实现，见 GAP-02）。
- 编辑表单回显：`GET /api/account/detail/{id}`。

#### 4.1.4 删除账户

- 二次确认文案需明示风险："删除后该账户的历史流水将无法关联"。
- 后端为物理删除且数据库外键 `f_account_id REFERENCES t_account` 无 ON DELETE 策略（SQLite 默认限制删除），存在流水的账户删除会失败，前端对删除失败（S0813）场景给出引导："请先处理该账户下的交易记录"。

### 4.2 收支流水（/transaction）

#### 4.2.1 功能清单

| 编号 | 功能 | 优先级 | 依赖接口 |
| ---- | ---- | ---- | ---- |
| FR-TRX-01 | 流水分页列表 | P0 | `POST /api/transaction/page` |
| FR-TRX-02 | 新增支出/收入记录 | P0 | `POST /api/transaction/save` |
| FR-TRX-03 | 新增转账记录（含手续费） | P0 | `POST /api/transaction/save` |
| FR-TRX-04 | 编辑记录 | P0 | `POST /api/transaction/update` |
| FR-TRX-05 | 删除记录 | P0 | `POST /api/transaction/delete?id=` |
| FR-TRX-06 | 复制记录 | P1 | 前端填充新增表单后调 save |
| FR-TRX-07 | 按账户/类型/分类/关键词筛选 | P1 | `POST /api/transaction/page`（queryList） |
| FR-TRX-08 | 按日期范围筛选 | P1 | 过渡方案：前端本地过滤（见 GAP-04） |
| FR-TRX-09 | 快速记账模板 | P2 | **已实现**：`QuickRecordDialog` 存为模板 / 一键回填 / 删除，localStorage `bookkeeping-quick-templates` |
| FR-TRX-10 | 批量删除、批量改分类 | P2 | 循环调用单条接口（见 GAP-08） |
| FR-TRX-11 | 金额区间筛选 | P2 | 依赖后端区间查询（GAP-04） |

#### 4.2.2 流水列表

- 筛选栏：类型（全部/收入/支出/转账）、账户（下拉，字典）、分类（级联下拉）、关键词（备注模糊搜索）、日期范围（快捷项：本周/本月/今年/自定义）。
- 表格列：日期时间、类型徽标（收入绿/支出红/转账蓝）、金额（转账显示"金额 + 手续费"）、账户、转入账户（仅转账）、分类（图标 + 名称）、标签（彩色小标签）、备注、操作（编辑/复制/删除）。
- 分页：默认每页 20 条，可选 10/20/50/100；分页参数 `pageNum`/`pageSize`。

#### 4.2.3 新增/编辑记录（对话框或右侧抽屉）

类型切换（支出 / 收入 / 转账）驱动表单字段变化：

| 字段 | 支出 | 收入 | 转账 | 控件与校验 |
| ---- | ---- | ---- | ---- | ---- |
| 金额 amount | ✓ | ✓ | ✓ | 必填，> 0，两位小数（**注意：后端 TransactionDTO 当前缺失该字段，见 GAP-01**） |
| 账户 accountId | ✓ | ✓ | ✓（转出） | 必填，下拉（过滤已归档） |
| 转入账户 toAccountId | — | — | ✓ | 必填，不得等于转出账户 |
| 手续费 fee | — | — | 可选 | ≥ 0，两位小数，默认 0 |
| 分类 categoryId | ✓ | ✓ | —（隐藏） | 级联选择，仅展示与类型匹配的分类（支出→expense，收入→income） |
| 日期 transactionDate | ✓ | ✓ | ✓ | 日期时间选择器，默认当前时间 |
| 标签 tags | 可选 | 可选 | 可选 | 多选下拉（字典），支持输入新建标签（见 4.6）；序列化为 JSON 数组字符串，如 `"[1,3]"`（存标签 ID） |
| 备注 note | 可选 | 可选 | 可选 | ≤ 200 字符 |

- 校验失败（如转入账户 = 转出账户）前端拦截，不发请求。
- 保存成功：toast + 刷新列表；失败按响应码提示（见 5.5）。

#### 4.2.4 快速记账弹窗（全局）

- 字段精简：类型 + 金额 + 账户 + 分类（+ 备注）；默认支出、默认上次使用的账户与分类。
- 保存成功后表单重置金额与备注，可连续录入；Esc 关闭。
- 模板（P2）：将当前表单保存为模板（命名），从模板列表一键填充。

### 4.3 统计报表（/report）

#### 4.3.1 功能清单

| 编号 | 功能 | 优先级 | 数据来源 |
| ---- | ---- | ---- | ---- |
| FR-RPT-01 | 收支趋势图（折线/柱状切换） | P0 | 后端 `POST /api/transaction/stats/trend`（见 14.2）；不可用时降级 `selectAll` + 前端聚合 |
| FR-RPT-02 | 分类占比环形图（收入/支出切换） | P0 | 后端 `POST /api/transaction/stats/category`（见 14.3）；降级同上 |
| FR-RPT-03 | 账户余额变化图 | P1 | **已实现**：纯前端按流水推演（`aggregate.ts` 的 `accountBalanceSeries`），无后端端点 |
| FR-RPT-04 | 自定义时间范围对比分析 | P2 | **已实现**：环比上期 / 同比去年，前端在全量流水上本地聚合两区间对比 |
| FR-RPT-05 | 报表导出 CSV | P2 | 前端生成（BOM 头，Excel 兼容） |
| FR-RPT-06 | 报表导出 PDF | P3 | **已实现**：`window.print()` + 全局 `@media print` 打印样式（隐藏侧边栏/顶栏/筛选控件），用户选“另存为 PDF” |

#### 4.3.2 页面设计

- 顶部：时间维度切换（日/周/月/年）+ 自定义范围（起止日期）+ 收入/支出维度切换。
- 趋势图：X 轴按所选维度分桶，双系列（收入/支出），tooltip 显示明细；支持折线/柱状切换。
- 分类占比：环形图，点击扇区联动下方明细表（分类、金额、笔数、占比）；父子分类按一级分类汇总，展开查看二级。
- 空范围无数据时展示空状态。
- 性能要求：万条记录下图表渲染 ≤ 2s（overview.md 4.1）；聚合计算放 Web Worker，避免阻塞 UI。

### 4.4 预算管理（/budget）

#### 4.4.1 功能清单

| 编号 | 功能 | 优先级 | 依赖接口 |
| ---- | ---- | ---- | ---- |
| FR-BGT-01 | 月度总预算卡片（进度条 + 超支提醒） | P0 | `POST /api/budget/searchBudget?year=&month=`（返回含 `amountUsed` 的 `BudgetInfo`） |
| FR-BGT-02 | 分类预算列表（进度条） | P0 | 同上 |
| FR-BGT-03 | 新增/编辑预算 | P0 | save / update |
| FR-BGT-04 | 删除预算 | P0 | delete |
| FR-BGT-05 | 月份切换（查看历史月份） | P1 | page（按 year+month 查询） |
| FR-BGT-06 | 预算执行历史对比 | P2 | **已实现**：`BudgetView` 近 6 个月总预算 vs 已用分组柱状图，逐月 `searchBudget`（silent） |

#### 4.4.2 页面与规则

- 月份选择器（默认当月）→ 查询该年月预算：queryList 为 `[{"key":"f_year","value":2026,"query":"eq"},{"key":"f_month","value":9,"query":"eq"}]`。
- 总预算卡片：`categoryId` 为空的预算；已用金额直接取后端 `searchBudget` 返回的 `amountUsed`（后端按年月 + 分类聚合支出流水，前端不再本地计算，GAP-05 已闭环）；进度条 < 80% 绿色、80%~100% 橙色、≥ 100% 红色 + 超支警示文案。
- 分类预算列表：仅支出类一级分类可设预算；行内展示分类图标/名称、已用/预算、进度条（颜色规则同上）。
- 新增/编辑表单：年月（月份选择器，默认当月）、预算类型（总预算/分类预算）、分类（分类预算时必选，仅支出一级分类）、金额（> 0）。
- 唯一性冲突：同月总预算重复返回 B003、同月分类预算重复返回 B004，前端 toast 提示。
- 预算不足提醒：当月支出使某预算使用率首次越过 80%/100% 时，应用内通知（P2 可接系统通知）。

### 4.5 分类管理（/settings/category，设置子页）

| 编号 | 功能 | 优先级 | 依赖接口 |
| ---- | ---- | ---- | ---- |
| FR-CAT-01 | 分类树形展示（收入/支出 Tab） | P0 | `GET /api/category/selectAll` |
| FR-CAT-02 | 新增/编辑分类 | P0 | save / update |
| FR-CAT-03 | 删除分类 | P0 | delete |
| FR-CAT-04 | 归档/取消归档 | P1 | update |
| FR-CAT-05 | 排序（sortOrder 调整） | P1 | update（拖拽或上下移按钮） |
| FR-CAT-06 | 首次使用引导批量创建默认分类 | P1 | 前端引导 + 循环 save（见 GAP-06） |

- 树形结构：最多两级（parentId 为空是一级）；一级分类行可展开子分类。
- 新增/编辑字段：名称（必填，重名返回 B002）、类型（income/expense，编辑时如存在子分类或关联流水则不可改类型）、父分类（仅新增二级时选择）、图标（内置图标库选择器）、颜色（色板选择器）、排序号、归档开关。
- 删除规则：含子分类的分类不可删（前端拦截提示）；已归档分类在下拉选择中默认隐藏。

### 4.6 标签管理（/settings/tag，设置子页）

| 编号 | 功能 | 优先级 | 依赖接口 |
| ---- | ---- | ---- | ---- |
| FR-TAG-01 | 标签列表（名称、颜色、使用次数） | P0 | `GET /api/tag/selectAll` |
| FR-TAG-02 | 新增/编辑标签 | P0 | save / update |
| FR-TAG-03 | 删除标签 | P0 | delete |
| FR-TAG-04 | 流水录入时快速新建标签 | P1 | save |

- 使用次数：前端基于流水 tags 字段统计（过渡方案）。
- 重名冲突返回 B005，前端提示。
- 颜色：预设色板（8~12 色）。

### 4.7 设置（/settings）

| 分组 | 功能 | 优先级 | 实现方式 |
| ---- | ---- | ---- | ---- |
| 个性化 | 主题切换（浅色/深色） | P1 | Element Plus dark 主题 + CSS 变量；本地持久化 |
| 个性化 | 货币符号与小数位数（0~2 位） | P1 | 本地持久化，全局格式化函数读取 |
| 个性化 | 启动时恢复上次窗口位置与尺寸 | P1 | 主进程 electron-store |
| 个性化 | 快捷键自定义（含全局快捷键） | P2 | 主进程 globalShortcut |
| 语言 | 中文/英文切换 | P2 | vue-i18n，本地持久化 |
| 数据管理 | 手动备份/恢复（导出/导入数据库文件） | P3 | 依赖后端或主进程文件复制（GAP-09） |
| 数据管理 | 流水导出 CSV/Excel | P2 | 前端导出 CSV（Excel 格式 P3） |
| 数据管理 | 流水导入（CSV/Excel） | P3 | 依赖后端批量接口（GAP-09） |
| 数据管理 | 清空数据 | P3 | 二次确认 + 输入确认文本，依赖后端 |
| 使用说明 | 内置说明文档（快速上手 / 功能地图 / 快捷键 / 记账口径 / 常见问题） | P2 | 前端静态内容 `utils/helpContent.ts`；设置二级菜单与顶栏「?」抽屉共用面板（见 4.10） |
| 使用说明 | 新手引导（全局 + 分页面） | P2 | Element Plus `el-tour`，锚点为各页面 `data-guide` 属性（见 4.10） |
| 关于 | 版本信息、检查更新 | P3 | electron-updater（可选） |

### 4.8 桌面端能力（Electron）

| 编号 | 功能 | 优先级 |
| ---- | ---- | ---- |
| FR-DT-01 | 系统托盘（图标 + 菜单：显示主窗口/快速记账/退出） | P2 |
| FR-DT-02 | 全局快捷键调出快速记账窗口（默认 `Cmd/Ctrl+Shift+B`，可自定义） | P2 |
| FR-DT-03 | 关闭窗口最小化到托盘（可选项） | P2 |
| FR-DT-04 | 多语言跟随（overview.md 3.8） | P2 |

### 4.9 站内信（顶栏消息中心）

| 编号 | 功能 | 优先级 | 依赖接口 |
| ---- | ---- | ---- | ---- |
| FR-MSG-01 | 顶栏铃铛 + 未读角标 | P1 | `GET /api/message/unreadCount` |
| FR-MSG-02 | 消息列表（时间倒序，最多 50 条） | P1 | `POST /api/message/page`（sortList：createTime desc） |
| FR-MSG-03 | 点击消息标记已读 | P1 | `POST /api/message/read?id=` |
| FR-MSG-04 | 全部已读 | P1 | `POST /api/message/readAll?ids=` |
| FR-MSG-05 | 删除单条（二次确认） | P2 | `POST /api/message/delete?id=` |
| FR-MSG-06 | 清空已读 | P2 | `POST /api/message/clearRead` |
| FR-MSG-07 | 只看未读 / 按类型筛选 | P2 | 前端本地过滤 |
| FR-MSG-08 | 未读数自动刷新（60s 轮询 + 业务事件联动） | P2 | `GET /api/message/unreadCount` |
| FR-MSG-09 | 消息详情弹窗（完整正文；贺卡类走贺卡版式、天气类走天气卡片） | P1 | `GET /api/message/detail/{id}` |
| FR-MSG-10 | 详情内按 bizType/bizId 跳转业务页并定位记录 | P2 | 前端路由 + 目标页消费 `?bizId=` |
| FR-MSG-11 | 每日天气消息（`type=weather`）结构化展示：列表摘要 + 详情天气卡片 | P2 | 后端 `WeatherServiceImpl` 启动时推送（见 13.9） |

- 展示形态：右侧抽屉（宽 420px），**不新增路由、不占用侧边导航**。
- 列表项：类型图标（按 `type` 着色）+ 标题 + 正文（最多 3 行截断）+ 类型标签 + 创建时间；未读项标题加重并带红点。天气消息的图标按当天天气描述细分（晴/多云/阴/雷/雨/雪），正文预览用摘要替代 JSON。
- 详情：点击列表项即标记已读并弹出详情（宽 520px，叠在抽屉之上），正文完整展示并保留换行；`type=greeting`（贺卡）改用渐变卡片版式，带 `cardImage` 时显示封面图（见 13.7）；`type=weather`（每日天气）改用天气卡片版式（见 13.9）。
- 跳转：详情底部按 `bizType` 出现「查看交易流水 / 查看预算管理 / 查看等级页面」按钮；`transaction` / `budget` 携带 `?bizId=` 让目标页定位到具体记录（见 13.6）。
- 筛选："只看未读"开关 + 类型下拉，均在已拉取的 50 条上本地过滤。
- 消息由**后端生产**（预算超支 / 签到 / 等级变动等，见 13.4），前端不提供"发信"入口。
- 降级：接口未就绪时铃铛整体隐藏（详见第 13 章），不弹错误提示。

### 4.10 使用说明与新手引导

| 编号 | 功能 | 优先级 | 实现方式 |
| ---- | ---- | ---- | ---- |
| FR-HELP-01 | 顶栏「?」入口，右侧抽屉展示使用说明（宽 560px） | P2 | `components/HelpDrawer.vue`，不新增路由 |
| FR-HELP-02 | 设置 → 使用说明（二级菜单，完整文档形态） | P2 | `SettingsView` 新增 `help` 菜单项，支持 `?menu=help` 直达 |
| FR-HELP-03 | 说明内容：快速上手 / 功能地图 / 快捷键 / 记账口径 / 等级与签到 / 常见问题 | P2 | 静态数据 `utils/helpContent.ts`（内容唯一源） |
| FR-HELP-04 | 说明内关键词检索，命中章节自动展开 | P2 | `components/HelpPanel.vue` 本地过滤 |
| FR-HELP-05 | 「立即体验」直达：跳转页面 / 打开快速记账 / 切换主题 | P2 | `HelpAction`（`route` / `quick-record` / `theme`） |
| FR-HELP-06 | 新手引导：首次启动播放全局引导（7 步） | P2 | `components/GuideTour.vue`（el-tour）+ `stores/guide.ts`（localStorage `bookkeeping-guide`） |
| FR-HELP-07 | 分页面引导：进入未看过的页面时右下角通知提示，点「开始」播放 | P2 | `utils/guideSteps.ts` 按 scope 分组，锚点为 `[data-guide]` 属性 |
| FR-HELP-08 | 引导重放（说明页按钮）与版本升级重播 | P3 | `GUIDE_VERSION` 提升即清空已看记录重播一次 |

**入口与快捷键：**

| 入口 | 位置 | 说明 |
| ---- | ---- | ---- |
| 「?」圆形按钮 | 顶栏最右（主题切换之后） | 打开说明抽屉，任意页面可用 |
| 使用说明菜单 | 设置 → 二级菜单（数据管理与关于之间） | 完整文档形态 |
| `Cmd/Ctrl + N` | 应用内全局 | 打开快速记账弹窗 |
| `Cmd/Ctrl + Shift + B` | 系统全局（Electron） | 窗口未聚焦时唤起快速记账（见 FR-DT-02） |

- 抽屉与设置页共用 `HelpPanel`，文案与操作入口只在 `utils/helpContent.ts` 维护一份，避免两处描述不一致。
- 说明中的功能描述以现有代码行为为准（如账户类型仅 4 种、金额必须大于 0、流水快捷日期为本周/本月/今年、签到由后端启动时自动完成）；功能变更时同步修改该文件。
- 抽屉内点直达按钮先关抽屉再执行动作（`HelpPanel` emit `action`）；「切换主题」不关抽屉，便于立即看到效果。
- 快速记账入口通过事件总线 `OPEN_QUICK_RECORD`（`utils/bus.ts`）通知 `MainLayout` 打开弹窗，说明面板不持有弹窗状态。
- 引导触发规则：首次启动延迟 600ms 播放全局引导；全局引导结束后、以及每次进入未看过的页面时，右下角通知给出「开始」入口（8s 自动消失），不自动播放、不打断操作。
- 锚点约定：各页面用 `data-guide="xxx"` 声明锚点；可选能力（消息中心、等级入口、签到面板、总预算卡）未渲染时对应步骤自动跳过，全部缺失则放弃播放且不标记已看过（下次进入重试）。
- 进度持久化：`localStorage` 的 `bookkeeping-guide`，结构为 `{ version, done }`；播放完成或中途退出都记为已看过；`GUIDE_VERSION` 提升时清空重播。
- 遮罩期间禁止点穿（`target-area-clickable=false`），Esc 与右上角 X 均可退出；引导 z-index 3000，高于普通弹窗。
- 顶栏「?」抽屉在路由变化时自动收起，避免遮挡新页面与引导。
- 引导复用 Element Plus 2.14 内置的 `el-tour`，零新增依赖、零后端改动。

## 5. API 对接规范

### 5.1 通用约定

- BaseURL：`http://127.0.0.1:8080/api`（可配置；开发环境经 Vite proxy `/api → 127.0.0.1:8080`）。
- 资源路径：`/account`、`/transaction`、`/category`、`/budget`、`/tag`、`/check_in`；站内信 `/message`（见第 13 章）。
- Content-Type：`application/json`（delete 接口为查询参数）。
- 超时：10s；统一由 Axios 拦截器处理响应。

### 5.2 统一端点（五个资源完全一致）

| 方法 | 路径 | 用途 | 请求 | 响应 |
| ---- | ---- | ---- | ---- | ---- |
| POST | `/{res}/page` | 分页条件查询 | Body：PageRequest | PageResponse\<DTO\> |
| GET | `/{res}/selectAll` | 查询全部 | — | DataResponse\<DTO[]\> |
| POST | `/{res}/save` | 新增 | Body：DTO | BaseResponse |
| POST | `/{res}/update` | 修改（按 body 中 id） | Body：DTO | BaseResponse |
| GET | `/{res}/detail/{id}` | 详情 | 路径参数 id | DataResponse\<DTO\> |
| POST | `/{res}/delete?id={id}` | 删除 | 查询参数 id | BaseResponse |

### 5.3 分页查询请求体

```json
{
  "pageNum": 1,
  "pageSize": 20,
  "queryList": [
    { "key": "type", "value": "expense", "query": "eq" },
    { "key": "accountId", "value": 3, "query": "eq" },
    { "key": "note", "value": "午餐", "query": "like" }
  ]
}
```

**重要约定（源自框架实现 `SearchQuery.field()`）：**

1. `key` 传 **DTO 驼峰属性名**（如 `type`、`accountId`、`note`），框架按 `"f_" + 驼峰转下划线` 自动生成列名（`type`→`f_type`、`accountId`→`f_account_id`）。**不要**自己加 `f_` 前缀（否则变成 `f_f_type`，查不到列）。
2. `query` 操作符取值：`eq`、`nt_eq`、`like`（全模糊）、`nt_like`、`in`、`nt_in`、`is_null`、`is_nt_null`、`l_like`（左模糊）、`r_like`（右模糊）；区间操作符 `ge`/`le` 待后端扩展（见第 12 章、GAP-04）。
3. **暂不支持排序参数**（见 GAP-07），排序由前端在当前页数据上本地完成。
4. 日期列特例：`t_transaction` 日期列是 `f_date`，而 DTO 属性名 `transactionDate` 会转出 `f_transaction_date`（不存在），故日期条件 `key` 传 `date`。日期区间查询完整契约见第 12 章。

### 5.4 响应结构

```jsonc
// BaseResponse（save/update/delete）
{ "code": "S0808", "msg": "保存成功" }

// DataResponse<T>（detail/selectAll）
{ "code": "S0806", "msg": "成功", "data": { } }

// PageResponse<T>（page）
{
  "code": "S0806", "msg": "成功",
  "data": [ { } ],
  "total": 128, "pageNum": 1, "pageSize": 20
}
```

### 5.5 响应码与前端处理策略

**通用响应码（CommonResp）：**

| code | msg | 前端处理 |
| ---- | ---- | ---- |
| S0806 | 成功 | 正常取 data |
| S0807 | 失败 | error toast |
| S0808 / S0809 | 保存成功 / 保存失败 | success / error toast，刷新列表 |
| S0810 / S0811 | 修改成功 / 修改失败 | 同上 |
| S0812 / S0813 | 删除成功 / 删除失败 | 同上；删除失败需结合业务场景给引导文案 |
| S0814 | 未知异常 | error toast + 记录到前端日志 |

**业务响应码（BookkeepingResp）：**

| code | msg | 触发场景 |
| ---- | ---- | ---- |
| B001 | 账户名称已存在 | 新增账户重名 |
| B002 | 此收支分类已存在 | 新增分类重名 |
| B003 | 总预算已存在 | 同月重复创建总预算 |
| B004 | 此收支分类预算已存在 | 同月同分类重复创建预算 |
| B005 | 此标签已存在 | 新增标签重名 |

**统一处理规则：** `code` 以 `S` 开头视为成功（S0807/S0809/S0811/S0813/S0814 视为失败）；以 `B` 开头为业务校验失败，直接 toast `msg`；HTTP 非 200 或网络错误走统一网络异常分支（提示后端服务不可用，联动 RUN-03）。

### 5.6 枚举字典（JSON 序列化值）

| 枚举 | 取值（传输值） | 展示 |
| ---- | ---- | ---- |
| AccountType | `cash` / `bank` / `ali_pay` / `wechat_pay` | 现金 / 银行卡 / 支付宝 / 微信支付 |
| CategoryType | `income` / `expense` | 收入 / 支出 |
| TransactionType | `income` / `expense` / `transfer` | 收入 / 支出 / 转账 |
| Currency | `CNY` / `DOLLAR` | ¥ 人民币 / $ 美元 |
| Archived | `0` / `1` | 正常 / 已归档 |
| MessageType | `system` / `budget` / `level` / `check_in` / `greeting` / `weather` | 系统 / 预算 / 等级 / 签到 / 贺卡 / 天气（站内信，见第 13 章）；六个值后端枚举均已落地，前端全部收录 |
| MessageBizType | `transaction` / `budget` / `level` | 站内信关联业务类型（可空），详情弹窗据此跳转 `/transaction`、`/budget`、`/level` |
| MessageStatus | `0` / `1` | 未读 / 已读 |

### 5.7 各资源 DTO 字段（对接契约）

通用字段（所有 DTO 继承 BaseDTO）：`id`（number，只读）、`createTime`、`updateTime`（`yyyy-MM-ddTHH:mm:ss`，只读）。

**AccountDTO：**

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| name | string | 账户名称 |
| type | string | AccountType 值 |
| initialBalance | number | 初始余额 |
| currentBalance | number | 当前余额（后端联动，暂不可靠，见 GAP-02） |
| currency | string | CNY / DOLLAR |
| archived | number | 0 / 1 |

**TransactionDTO（对应列名 t_transaction.f_xxx）：**

| 字段 | 类型 | 数据库列 | 说明 |
| ---- | ---- | ---- | ---- |
| type | string | f_type | income / expense / transfer |
| **amount** | number | f_amount | **金额；后端 DTO 当前缺失此字段（GAP-01），修复后按此契约对接** |
| fee | number | f_fee | 手续费，仅转账，默认 0 |
| accountId | number | f_account_id | 源账户 |
| toAccountId | number | f_to_account_id | 转入账户，非转账为 null |
| categoryId | number | f_category_id | 分类，转账可为 null |
| transactionDate | string | f_date | ISO 日期时间 |
| note | string | f_note | 备注 |
| tags | string | f_tags | 标签 ID 的 JSON 数组字符串，如 `"[1,3]"` |

**CategoryDTO：** `name`、`parentId`（null 为一级）、`icon`、`color`、`type`（income/expense）、`sortOrder`、`archived`（列：f_name、f_parent_id、f_icon、f_color、f_type、f_sort_order、f_is_archived）。

**BudgetDTO：** `categoryId`（null 为总预算）、`amount`、`month`（1-12）、`year`（列：f_category_id、f_amount、f_month、f_year；唯一约束 f_category_id+f_year+f_month）。

**TagDTO：** `name`（唯一）、`color`（列：f_name、f_color）。

**CheckInDTO（/check_in 资源，签到体系）：** `checkDate`（`yyyy-MM-dd`，一天仅一条）、`expReward`（当日总经验）、`baseExp`（基础 10）、`bonusExp`（连续奖励 `min((streak-1)×2, 50)`）、`streakDays`（连续签到天数）（列：f_check_date、f_exp_reward、f_base_exp、f_bonus_exp、f_streak_days）。签到在后端启动时自动完成（重复报 B006），前端纯展示，仅调用 `selectAll`；经验发放记入 t_exp_transaction，该表不对外提供接口。

**MessageDTO（/message 资源，站内信）：** `title`、`content`、`type`（MessageType 值）、`bizType`（MessageBizType，可空）、`bizId`（可空）、`status`（0 未读 / 1 已读）（列：f_title、f_content、f_type、f_biz_type、f_biz_id、f_status）；贺卡封面 `cardImage` 为前端预留字段（后端待补，见 13.7）。表结构、端点清单与消息生产时机详见第 13 章。

### 5.8 数据处理约定

- 金额在 JSON 中为数字（后端 BigDecimal）；前端展示前格式化，参与统计计算时转为"分"整数，避免浮点误差。
- `tags` 解析：`JSON.parse` 失败时按空处理（兼容脏数据），异常需静默降级不阻断列表渲染。
- `transactionDate` 提交时由日期时间选择器值格式化为 ISO 字符串。

## 6. 前端数据模型（TypeScript）

```typescript
// —— 通用 ——
interface BaseResponse { code: string; msg: string }
interface DataResponse<T> extends BaseResponse { data: T }
interface PageResponse<T> extends DataResponse<T[]> {
  total: number; pageNum: number; pageSize: number
}
type QueryOp = 'eq' | 'nt_eq' | 'like' | 'nt_like' | 'in' | 'nt_in'
             | 'is_null' | 'is_nt_null' | 'l_like' | 'r_like' | 'ge' | 'le' // ge/le 待后端扩展
interface SearchQuery { key: string; value: unknown; query: QueryOp } // key 为 DTO 驼峰属性名，框架自动转 f_xxx 列名
interface SortQuery { field: string; sort: 'asc' | 'desc' } // field 同为 DTO 驼峰属性名，自动转 f_xxx 列名
interface PageRequest { pageNum: number; pageSize: number; queryList: SearchQuery[]; sortList?: SortQuery[] }

// —— 枚举 ——
type AccountType = 'cash' | 'bank' | 'ali_pay' | 'wechat_pay'
type CategoryType = 'income' | 'expense'
type TransactionType = 'income' | 'expense' | 'transfer'
type CurrencyCode = 'CNY' | 'DOLLAR'

// —— 实体 ——
interface BaseEntity { id: number; createTime: string; updateTime?: string }
interface Account extends BaseEntity {
  name: string; type: AccountType
  initialBalance: number; currentBalance: number
  currency: CurrencyCode; archived: 0 | 1
}
interface Category extends BaseEntity {
  name: string; parentId: number | null
  icon: string; color: string
  type: CategoryType; sortOrder: number; archived: 0 | 1
}
interface Transaction extends BaseEntity {
  type: TransactionType
  amount: number            // 依赖 GAP-01 修复
  fee: number
  accountId: number; toAccountId: number | null; categoryId: number | null
  transactionDate: string   // yyyy-MM-ddTHH:mm:ss
  note: string; tags: string // JSON 数组字符串，如 "[1,3]"
}
interface Budget extends BaseEntity {
  categoryId: number | null // null = 总预算
  amount: number; month: number; year: number
}
interface Tag extends BaseEntity { name: string; color: string }

// —— 站内信（第 13 章） ——
type MessageType = 'system' | 'budget' | 'level' | 'check_in' | 'greeting' | 'weather' // weather 的 content 存 Weather JSON
type MessageBizType = 'transaction' | 'budget' | 'level'
interface Message extends BaseEntity {
  title: string; content: string
  type: MessageType
  bizType: MessageBizType | null  // 关联业务，详情弹窗据此跳转
  bizId: number | null
  status: 0 | 1                   // 消息状态：0 未读 / 1 已读
  cardImage?: string | null       // 贺卡封面（预留，无值不渲染）
}
```

## 7. 非功能需求

| 类别 | 需求 | 来源 |
| ---- | ---- | ---- |
| 性能 | 应用冷启动 ≤ 3s（含 Java 子进程拉起，启动页过渡）；列表滚动流畅，万条记录下操作响应 ≤ 500ms；报表生成 ≤ 2s（聚合计算放 Web Worker） | overview.md 4.1 |
| 性能 | 长列表（流水）启用虚拟滚动或严格分页，避免一次性渲染 | 补充 |
| 兼容性 | Windows 10+ / macOS 11+ / Ubuntu 20.04+；分辨率适配 1024×768 以上；跟随系统缩放（Electron zoomFactor） | overview.md 4.2 |
| 安全 | 敏感操作（删除账户、清空数据）二次确认；输入前后端双重校验；不在前端日志打印全量数据 | overview.md 4.3 |
| 可维护性 | ESLint + Prettier + TypeScript strict；组件/页面/状态/store 分层清晰；API 层统一封装（一个资源一个模块） | overview.md 4.4 |
| 可用性 | 所有请求可重试幂等性考虑（save 失败不自动重发，避免重复记账） | 补充 |

## 8. 后端依赖与差距清单

以下为前端所需、后端当前**不具备或存在问题**的能力，需与后端协同排期。前端按"过渡方案"先行开发，不阻塞。

> 📌 各差距项 **2026-09-09 的最新核实状态**（哪些已闭环、哪些仍未修）见 [desktop-roadmap.md 第 5 章](./desktop-roadmap.md)。

| 编号 | 差距项 | 现状 | 影响 | 优先级 | 过渡方案 / 建议 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| GAP-01 | TransactionDTO 缺少 `amount` 字段 | 数据库 `f_amount NOT NULL`，但 DTO 无该字段 | 流水录入无法传金额，**阻断核心流程** | **P0** | 后端补充 `private BigDecimal amount;`；前端按 5.7 契约先行开发 |
| GAP-02 | 交易与账户余额联动 | 保存/删除/修改交易不更新 `currentBalance` | 账户余额不准确 | **P0** | 后端在 TransactionService 实现联动（支出减、收入加、转账双向、编辑时差额修正）；过渡期前端可展示"初始余额+流水推算"值并标注 |
| GAP-03 | 框架 `insert` 实现疑似缺陷 | `IBaseCrudServiceImpl.insert` 中 `entity = baseMapper.selectById(dto.getId())` 后直接 save，疑似应为 `mapping.toEntity(dto)` | 新增接口可能返回未知异常（S0814） | **P0** | 与后端确认并修复 self-framework；联调时优先验证五个 save 接口 |
| GAP-04 | 查询不支持区间操作符 | Query 枚举无 GE/LE/GT/LT/BETWEEN | 日期范围、金额区间筛选无法走后端 | P1 | 后端为 Query 枚举加 `ge`/`le` + bindQueryWrapper 分支（契约见第 12 章）；前端已按契约改造，并对日期区间静默降级（未就绪时自动回退 selectAll 本地过滤），后端补齐即自动生效 |
| GAP-05 | 统计聚合接口（**已实现**） | `TransactionController` 提供 `stats/{summary,trend,category,monthly}` 与 `recent`，`BudgetController` 提供 `searchBudget`（按年月返回 `amountUsed`） | 报表趋势/占比/月度盈亏、总览、预算已用、最近流水均改走后端聚合，不再全量拉流水前端计算 | 已闭环 | 契约见第 14 章；前端 `Promise.allSettled` 各路 `silent`，不可用时降级 `selectAll` + `utils/aggregate.ts`。账户余额变化图改为纯前端推演（见 14.6），无需后端余额序列端点 |
| GAP-06 | 无内置默认分类 | init.sql 未插入默认分类 | 新用户冷启动无分类可用 | P1 | 建议后端 seed（餐饮、交通、购物、娱乐、工资、奖金等）；过渡期前端首启引导批量创建 |
| GAP-07 | 分页查询排序（**已解决**） | 框架 `PageRequest` 已支持 `sortList: [{ field, sort }]`（`SortQuery.field()` 自动转 f_ 列名，`sort` 取 asc/desc） | — | 已闭环 | 站内信已接入（createTime desc）；`api/crud.ts` 的 page 已支持 `opts.sortList`，账户/流水等页如需服务端排序可直接传入 |
| GAP-08 | 无批量操作接口 | 仅单条 delete/update | 批量删除/改分类需 N 次请求 | P2 | 后端提供 `batchDelete`/`batchUpdate`；过渡期前端串行调用 + 进度提示 |
| GAP-09 | 无备份/恢复/导入导出接口 | 未实现 | 数据管理功能无法落地 | P3 | M3 阶段设计（后端文件复制备份 + CSV 导入导出端点） |
| GAP-10 | 未配置 CORS | 后端无 CORS 配置 | 仅影响开发环境（Vite 5173 → 8080） | P0（开发期） | 开发环境用 Vite proxy 解决；生产环境 Electron `file://` 加载无跨域问题 |
| GAP-11 | 站内信资源（**已实现**） | `t_message` + `MessageController`（page / unreadCount / read / readAll / clearRead / delete）已落地 | 前端消息中心（列表 + 详情 + 跳转）已对接 | 已闭环 | 剩余待办见 13.8：init.sql 索引列名错误（P0）、业务侧尚未生产消息、readAll 循环 update、贺卡字段待补 |
| GAP-12 | **分页未生效**（缺 `PaginationInnerInterceptor`） | 全仓无 `MybatisPlusInterceptor` / `PaginationInnerInterceptor` bean，MP 3.5.15 的 starter 也不会自动注册；而 `selectPageList` 靠 `baseMapper.selectList(PageDTO.of(pageNum, pageSize), qw)` 分页 | **所有 `/page` 接口不加 LIMIT、返回全量记录**（`total` 由 count 单独统计故正确，`sortList` 的 ORDER BY 也生效）；数据量增长后响应体与渲染压力不可控 | P1 | 后端加 `@Bean MybatisPlusInterceptor`（内含 `PaginationInnerInterceptor(DbType.SQLITE)`），一处修复全部资源受益；前端已在 `messageApi.list` 内按 limit 截断兜底，流水/账户等页可按需同样处理 |

## 9. 里程碑规划（对齐 overview.md 交付节奏）

| 阶段 | 前端交付物 | 对应后端 |
| ---- | ---- | ---- |
| FE-M1 | 工程搭建（Electron + Vue3 + Vite + Element Plus + TS）；Java 子进程管理与启动流程；Axios 封装、统一响应/错误处理、字典 Store；账户模块（列表/新增/编辑/删除/归档）；流水模块（列表/筛选/三类记录录入/编辑/删除/复制）；快捷记账弹窗 | M1（GAP-01/02/03 需在此阶段修复） |
| FE-M2 | 分类管理（树形/图标/颜色/排序/归档/首启引导）；标签管理；报表模块（趋势/占比/月度盈亏接后端 stats、余额变化未实现）；预算模块（总预算/分类预算/进度条/超支提醒，已用走 searchBudget） | M2（GAP-05 已闭环） |
| FE-M3 | 设置中心（主题/货币格式/窗口记忆/快捷键）；多语言；快速记账模板与托盘、全局快捷键；CSV 导出 | M3（GAP-09 对接） |
| FE-M4 | 性能优化（虚拟滚动/Web Worker/缓存）；electron-builder 三平台打包（捆绑 jpackage 后端产物）；整体验收 | M4 |

## 10. 关键验收场景（摘要）

1. 冷启动：双击应用 → 启动页 → 30s 内进入主界面（后端正常时通常 ≤ 5s）。
2. 记一笔：任意页面点"记一笔"→ 填金额/账户/分类 → 保存 → toast 成功 → 流水列表可见新记录，账户余额按 GAP-02 修复后联动更新。
3. 转账：从 A 转 B 100 元、手续费 1 元 → A 减 101、B 加 100；A=B 时前端拦截。
4. 重名校验：新增同名账户 → toast "账户名称已存在"（B001）。
5. 预算超支：设置月度总预算后录入支出越线 → 进度条变红并出现超支警示。
6. 报表：录入本月多笔流水后，趋势图与分类占比与流水明细手工核对一致。
7. 筛选：按"本月 + 支出 + 某账户"组合筛选，结果与手工筛选一致（过渡方案允许本地过滤）。
8. 空状态：全新数据库首启 → 各页面空状态引导；分类引导批量创建默认分类。
9. 异常恢复：手工杀掉 Java 进程 → 前端弹窗提示并自动重启后端，恢复后可继续记账。
10. 退出清理：退出应用后系统无残留 Java 进程。

## 11. 用户等级接口契约（待后端实现）

前端等级界面（侧边栏徽章 / 总览横幅 / 等级页）已按本章契约完成，后端补齐以下三个只读接口即可自动生效。
三个请求均带 `silent` 标记：**接口缺失或报错时前端不弹提示、界面自行降级隐藏**，因此后端可分批实现。

### 11.1 GET /level/currentLevel（扩展已有接口）

在现有 `LevelInfo` 基础上补充经验与阈值字段（响应仍为 `DataResponse<LevelInfo>`，成功码 S0806）：

```json
{
  "code": "S0806",
  "msg": "success",
  "data": {
    "level": 5,
    "leveName": "省钱能手",
    "description": "节省开支有妙招",
    "icon": null,
    "experience": 1240,
    "totalEarned": 1390,
    "totalSpent": 150,
    "currentThreshold": 1000,
    "nextLevel": 6,
    "nextLevelName": "精明消费者",
    "nextThreshold": 1500
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| level / leveName / description / icon | 同现状 | 保持现有字段名（含 `leveName`）不变 |
| experience | number | 当前经验值（t_user_level.f_experience） |
| totalEarned / totalSpent | number | 累计获得 / 累计扣除经验 |
| currentThreshold | number | 当前等级起始阈值（t_level_config 中本级 exp_threshold） |
| nextLevel / nextLevelName / nextThreshold | number / string / number | 下一级信息；**满级时三者均为 null** |

进度由前端计算：`(experience - currentThreshold) / (nextThreshold - currentThreshold)`，满级显示 100%。

### 11.2 GET /level/configs

返回全部等级配置，`DataResponse<LevelConfig[]>`，**按 level 升序**：

```json
{ "code": "S0806", "msg": "success", "data": [
  { "id": 1, "level": 1, "name": "理财小白", "expThreshold": 0, "icon": null, "description": "迈出记账第一步" }
] }
```

### 11.3 GET /level/logs

返回月度经验日志，`DataResponse<ExperienceLog[]>`，**按年、月倒序**：

```json
{ "code": "S0806", "msg": "success", "data": [
  { "id": 3, "year": 2026, "month": 8, "budgetAmount": 3000, "actualAmount": 2460, "diffAmount": 540, "expChange": 30 }
] }
```

`diffAmount` 正为节省、负为超支；`expChange` 正为加经验、负为扣经验。

### 11.4 序列化约定

- BigDecimal 序列化为 JSON number 或字符串均可（前端统一 `Number()` 转换）；经验建议 scale 0。
- 未结算月份不产生 log 记录；前端空表展示引导文案。

## 12. 流水日期区间分页契约（待后端实现）

前端「收支流水」页已改为**统一走 `POST /transaction/page`**（后端分页 + 条件查询）：类型 / 账户 / 分类 / 关键词条件现已生效；**日期区间**需后端为 self-framework 补两个区间操作符后即可自动生效。在后端就绪前，前端对日期区间查询做**静默降级**（自动回退 `selectAll` + 本地过滤 + 本地分页），不影响使用。

### 12.1 需后端补充的能力（self-framework）

1. `Query` 枚举新增两个操作符：

```java
GE("ge"),   // >=
LE("le");   // <=
```

2. `IBaseCrudServiceImpl.bindQueryWrapper` 的 switch 增加两个分支：

```java
case GE -> queryWrapper.ge(query.field(), query.value());
case LE -> queryWrapper.le(query.field(), query.value());
```

> 无需改动列名映射逻辑；下方 12.2 说明前端如何命中 `f_date` 列。

### 12.2 日期区间的 queryList 契约

关键：`SearchQuery.field()` = `"f_" + 驼峰转下划线(key)`。`t_transaction` 的日期列是 `f_date`，而 DTO 属性名是 `transactionDate`（会转出 `f_transaction_date`，不存在）。因此日期条件 **`key` 固定传 `date`**（→ `f_date`）。

请求体示例（查询 2026-09-01 ~ 2026-09-30 的支出）：

```json
{
  "pageNum": 1,
  "pageSize": 20,
  "queryList": [
    { "key": "type", "value": "expense", "query": "eq" },
    { "key": "date", "value": "2026-09-01T00:00:00", "query": "ge" },
    { "key": "date", "value": "2026-09-30T23:59:59", "query": "le" }
  ]
}
```

- 值格式与 `f_date` 存储格式一致（ISO 文本 `yyyy-MM-ddTHH:mm:ss`）；SQLite 下文本按字典序比较即时间序，`ge`/`le` 可正确圈定闭区间。
- 起始补 `T00:00:00`、结束补 `T23:59:59`，以包含首尾整天。
- 其余条件 `key` 仍传 DTO 驼峰属性名（`type`/`accountId`/`categoryId`/`note`），框架自动转列名；仅日期因列名与属性名不一致而特例传 `date`。

### 12.3 前端降级行为

- 首次带日期区间的查询以 `silent` 方式请求 `page`：成功则本会话后续日期区间均走 `page`。
- 若后端尚未支持 `ge`/`le`（`Query.fromValue` 抛错 → 返回错误码），前端静默捕获并回退 `selectAll` + 本地过滤 + 本地分页，**本会话不再重复探测**、不弹错误提示。
- 因此后端可在任意时间补齐 12.1，前端**无需改动**即自动切换到后端分页。

## 13. 站内信接口契约（后端已实现，前后端已对齐）

后端 `MessageController` / `MessageServiceImpl` / `t_message` 已落地，本章为**对齐后的最终契约**。前端顶栏「消息中心」（铃铛 + 未读角标 + 右侧抽屉列表 + 详情弹窗 + 业务跳转 + 已读/删除维护）按此实现；读取类请求均带 `silent`，接口异常时不弹提示、铃铛整体隐藏。

### 13.1 表结构（init.sql 现状）

```sql
CREATE TABLE IF NOT EXISTS t_message (
     f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
     f_title         TEXT    NOT NULL,                   -- 消息标题
     f_content       TEXT    NOT NULL,                   -- 消息内容（建议纯文本）
     f_type          TEXT    NOT NULL,                   -- 消息类型：system / budget / level / check_in / greeting / weather
     f_biz_type      TEXT,                               -- 关联业务类型（可选）：transaction / budget / level，便于跳转
     f_biz_id        INTEGER,                            -- 关联业务ID（可选），如超支的预算ID
     f_status        INTEGER NOT NULL DEFAULT 0,         -- 是否已读：0 未读 / 1 已读
     f_create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     f_update_time   DATETIME DEFAULT CURRENT_TIMESTAMP  -- 也可理解为阅读时间
);
```

列名与 DTO 属性严格对应（`SearchQuery.field()` / `SortQuery.field()` 同规则，`"f_" + 驼峰转下划线`）：`status` → `f_status`、`bizType` → `f_biz_type`、`bizId` → `f_biz_id`。`status` 采用 `MessageStatus` 枚举（`IEnum<Integer>` + `@JsonValue` / `@JsonCreator`，`UN_READ(0)` / `READ(1)`），JSON 传输值为 0/1 —— 刻意避开 `boolean isRead`（Lombok 生成 `isRead()`，Jackson 会序列化成 `read`）。

> ✅ **已修（后端）：** 索引已改为 `CREATE INDEX IF NOT EXISTS idx_message_read ON t_message(f_status, f_create_time DESC)`，与实际列名一致（原 MSG-01 已闭环）。
>
> ⚠️ **待修（后端，P0）：** 实测 `t_message.f_type` 存在**大写枚举名**的历史数据（如 `CHECK_IN`、`GREETING`），而 `MessageType.fromValue` 只匹配小写传输值，读取这类记录会抛 `IllegalArgumentException`，导致整个查询失败（详见 MSG-07）。

### 13.2 MessageDTO 字段

| 字段 | 类型 | 数据库列 | 说明 |
| ---- | ---- | ---- | ---- |
| title | string | f_title | 标题，列表单行省略、详情最多两行 |
| content | string | f_content | 正文，列表最多 3 行截断，**详情弹窗完整展示**（纯文本，`\n` 换行） |
| type | string | f_type | MessageType：`system` / `budget` / `level` / `check_in` / `greeting` / `weather`（见 13.5）；未知值前端降级为「系统」，`greeting` 见 13.7、`weather` 见 13.9 |
| bizType | string \| null | f_biz_type | MessageBizType：`transaction` / `budget` / `level`；**详情弹窗据此展示跳转按钮** |
| bizId | number \| null | f_biz_id | 关联业务 ID，跳转时作为 `?bizId=` 传给目标页定位记录 |
| status | number | f_status | 0 未读 / 1 已读（MessageStatus 枚举） |
| cardImage | string \| null | —（待补 f_card_image） | 贺卡封面图 URL，**前端预留字段**，后端未返回时不渲染（见 13.7） |
| id / createTime / updateTime | — | — | BaseDTO 通用字段；`createTime` 用于时间展示与倒序排序，`updateTime` 即阅读时间 |

### 13.3 端点清单（均已实现）

`MessageController extends IBaseController<MessageDTO, MessageService>`，6 个标准端点直接继承，另有 4 个自定义端点（下表为前端实际使用的 7 个）：

| 方法 | 路径 | 用途 | 请求 | 响应 |
| ---- | ---- | ---- | ---- | ---- |
| POST | `/message/page` | **最近消息列表**（标准端点 + `sortList` 倒序） | Body：PageRequest（见下） | `PageResponse<MessageDTO>` |
| GET | `/message/detail/{id}` | **消息详情**（标准端点；详情弹窗打开时补全正文与贺卡字段） | 路径参数 id | `DataResponse<MessageDTO>` |
| GET | `/message/unreadCount` | 未读条数（角标轮询） | — | `DataResponse<Long>` |
| POST | `/message/read?id=` | 单条标记已读（幂等） | query `id` | `BaseResponse` |
| POST | `/message/readAll?ids=` | 批量标记已读 | query `ids`：**逗号分隔的 id 列表**，Spring 绑定 `List<Integer>` | `BaseResponse` |
| POST | `/message/clearRead` | 删除全部已读（`delete from t_message where f_status = 1`） | — | `BaseResponse` |
| POST | `/message/delete?id=` | 删除单条（标准端点） | query `id` | `BaseResponse` |

最近消息的请求体（前端 `messageApi.list(50)` 实际发送）：

```json
{
  "pageNum": 1,
  "pageSize": 50,
  "queryList": [],
  "sortList": [{ "field": "createTime", "sort": "desc" }]
}
```

- `SortQuery.field()` 同样按 `"f_" + 驼峰转下划线` 转换：`createTime` → `ORDER BY f_create_time DESC`；`sort` 取值为 `asc` / `desc`（`Sort` 枚举的 `@JsonValue`）。
- `queryList` 必须传数组（`bindQueryWrapper` 直接遍历，null 会 NPE）；`sortList` 可不传（`bindSortQueryWrapper` 对空集合直接跳过）。
- 需服务端筛选时可加条件，如只看未读：`{ "key": "status", "value": 0, "query": "eq" }`（→ `f_status = 0`）。**当前前端未用**，只在已拉取的 50 条上本地筛选。
- **`readAll` 必须传 ids**：后端按 id 逐条置已读，前端传当前已加载的未读消息 id；因此「全部已读」只覆盖最近 50 条内的未读，超出部分的角标数会保留（再次拉取后可继续点）。
- ⚠️ **分页当前未生效（GAP-12）：** 项目未配置 `PaginationInnerInterceptor`，`page` 不会拼 LIMIT，实际返回全量消息（排序仍生效）。前端已在 `messageApi.list` 内 `slice(0, limit)` 截断兜底；后端补上拦截器后无需改前端。

### 13.4 消息生产时机（后端职责，前端不生产消息）

| type | bizType | 触发时机 | title / content 建议 |
| ---- | ---- | ---- | ---- |
| budget | budget | 记账后当月总预算或分类预算超支（**建议仅首次越线时发一条**，避免每笔都发） | 「预算超支提醒」/「9 月餐饮预算已超支 ¥120.00」 |
| budget | budget | 月度经验结算完成 | 「8 月预算结算」/「节省 ¥540.00，经验 +30」 |
| check_in | — | 启动自动签到成功 | 「签到成功」/「已连续签到 7 天，经验 +22」 |
| weather | — | **已实现**：`WeatherServiceImpl.init()`（`@PostConstruct`）拉取 wttr.in 当日天气，当日尚无天气消息时推送一条 | 「每日天气」/ Weather JSON（见 13.9） |
| level | level | 等级提升 / 下降 | 「等级提升」/「恭喜升至「省钱能手」（Lv.5）」 |
| system | transaction | 可选：大额支出提醒（阈值由后端定义） | 「大额支出」/「单笔支出 ¥3,200.00（房租）」 |
| system | — | 数据备份完成、版本升级说明、异常告知等 | 自定义 |

> 注意：**流水类提醒用 `type=system` + `bizType=transaction`**，因为 `MessageType` 枚举无 `transaction` 值（它属于 `MessageBizType`）。

- **当前进度：** 已接入**签到**（`CheckInService`）与**每日天气**（`WeatherServiceImpl`，见 13.9）两类消息生产；预算超支 / 等级变动节点仍待接入，可按上表补齐。
- **事务时序要求：** 消息需在同一次请求处理内落库（业务事务提交后）。前端在 `TRANSACTION_CHANGED` / `BUDGET_CHANGED` 事件后延迟 800ms 拉未读数，异步生产会错过这次刷新（靠 60s 轮询兜底）。
- **幂等与去重：** 同一预算同月建议只发一次超支提醒（可在生成前查同类型未读消息，或加唯一约束）。
- **保留策略：** 本地单用户场景，建议后端定期清理（保留最近 200 条或 90 天）；前端只拉最近 50 条。
- **多用户预留：** 当前无需 receiverId；若后续多用户，请补 `f_user_id` 并在所有端点按用户过滤（前端契约不变）。

### 13.5 枚举与前端展示映射

**MessageType（`type`，决定图标与色彩）：**

| 值 | 展示 | 图标（@element-plus/icons-vue） | 色值 |
| ---- | ---- | ---- | ---- |
| budget | 预算 | PieChart | #E6A23C |
| check_in | 签到 | Calendar | #67C23A |
| level | 等级 | Medal | #2f7fe0 |
| system | 系统 | Bell | #909399 |
| greeting | 贺卡 | Present | #E91E63 |
| weather | 天气 | Sunny（列表与详情再按当天描述细分：PartlyCloudy / MostlyCloudy / Lightning / Pouring / Drizzling） | #00B8A9 |

**MessageBizType（`bizType`，决定详情弹窗的跳转目标）：** `transaction`（→ `/transaction?bizId=`，流水页定位模式）、`budget`（→ `/budget?bizId=`，预算卡片高亮）、`level`（→ `/level`，仅页面级跳转）；`bizType` 为 null 时详情底部不出现跳转按钮。具体行为见 13.6。

前端对未知 `type` 降级为「系统」+ 铃铛图标 + 中性色，不会报错；后端新增类型时同步补 5.6 与本表即可（文案与色值维护在 `utils/constants.ts` 的 `MESSAGE_TYPE_OPTIONS` / `MESSAGE_TYPE_COLOR`，图标映射统一收拢在 `components/messageIcons.ts` 供抽屉与详情弹窗共用，天气正文解析在 `utils/weather.ts`）。

### 13.6 前端实现与降级行为

- 组件挂载时以 `silent` 探测 `GET /message/unreadCount`：失败 → `available=false`，顶栏铃铛整体不渲染，不弹提示（与等级、签到一致）。
- 未读数每 60s 轮询一次，`document.hidden`（最小化 / 屏保）时跳过；抽屉每次打开强制重拉 `page`（sortList 倒序，pageSize=50）+ `unreadCount`。
- 「全部已读」收集本地未读 id 调 `readAll?ids=1,2,3`；本地无未读时不发请求（避免 `ids=` 空值绑定异常），改为重拉未读数对齐。
- 已读 / 删除成功后只改本地状态，不重拉列表；失败时按 5.5 统一 toast，随后重拉未读数对齐状态。
- 筛选（只看未读 / 按类型）均在已拉取的 50 条上本地完成；若后续未读量大，可改为带 `queryList` 的服务端筛选（契约见 13.3）。

**详情查看（`components/MessageDetailDialog.vue`）：**

- 点击列表项：先调 `read?id=` 标记已读，再打开详情弹窗（宽 520px，`append-to-body` 叠在抽屉之上）。
- 弹窗打开时以 `silent` 调 `GET /message/detail/{id}` 补全内容；返回前先用列表已有数据渲染（无空白闪烁），详情接口失败则继续用列表数据，不弹提示。
- 正文按**纯文本**渲染（`white-space: pre-wrap`，保留换行与空格），**不使用 `v-html`**（避免 XSS）；`type=greeting` 走贺卡版式（见 13.7）、`type=weather` 走天气卡片版式（见 13.9），两类解析/渲染失败均回退纯文本。

**业务跳转（详情底部「查看 XX」按钮）：**

- `bizType` → 路由：`transaction` → `/transaction`、`budget` → `/budget`、`level` → `/level`；前两者额外携带 `?bizId={bizId}`，跳转前先关闭详情弹窗与抽屉。
- `/transaction?bizId=`：流水页进入**定位模式** —— 忽略筛选栏、改按 `{ key: 'id', value: bizId, query: 'eq' }` 精确查询（→ `f_id = ?`），命中行高亮，顶部提示条可「查看全部」退出；查不到（已删除）时 toast 提示并自动回到完整列表。「重置」按钮也会退出定位模式。
- `/budget?bizId=`：预算页先 `silent` 调 `GET /budget/detail/{id}` 拿到该预算的年月并切到对应月份（预算不一定在当月），再高亮对应卡片（总预算或分类预算）并滚动到可视区；查不到时 toast 提示。手动切换月份会清除高亮。
- 两个页面消费完 `bizId` 后立即 `router.replace` 抹除该参数，保证同一条消息可重复跳转（组件已挂载时靠 `watch(route.query.bizId)` 触发，未挂载时走 `onMounted`）。
- `level` 只做页面级跳转（`bizId` 为经验日志 id，定位价值低），**不往 URL 上挂无人消费的参数**。

### 13.7 贺卡消息（版式已就绪，封面图待后端补）

目标：可通过站内信给用户发**贺卡**（节日 / 生日 / 等级达成纪念等）。后端已补 `GREETING` 枚举（实测库中已有贺卡消息），封面图相关字段仍待补：

| 项 | 前端现状 | 后端 |
| ---- | ---- | ---- |
| 消息类型 | `MessageType` 已含 `greeting`：展示「贺卡」、图标 `Present`、色值 `#E91E63`，类型筛选下拉已包含 | ✅ 已补 `GREETING("greeting")`（注意落库值必须是小写 `greeting`，见 MSG-07） |
| 版式 | `type=greeting` 时详情走贺卡版式：渐变卡片（`#fbc2eb → #a6c1ee`）+ 居中大字（16px / 行高 30px），深色主题下同样可读 | `content` 写多行文案即可（`\n` 分行，前端 `pre-wrap` 保留）；`f_content` 为 TEXT，长度足够 |
| 封面图 | `cardImage` 有值时在贺卡正文上方渲染 `<img>`（最大高 220px，`object-fit: cover`），无值不渲染 | `MessageDTO` 加 `private String cardImage;` + 表加列 `f_card_image TEXT`；返回**可直接作 `<img src>` 的完整 URL**（如 `http://127.0.0.1:8080/api/image/download/{filename}/{imageType}`） |
| 图片存储 | 复用现有 `/image` 能力即可 | `ImageType` 加 `GREETING_CARD(3, "greetingCard")`（`ImageController.init()` 会自动建目录），前端 `constants.ts` 再补 `IMAGE_TYPE_GREETING_CARD = 3` |

- **不引入富文本：** 详情正文按纯文本渲染，`content` 里放 HTML 会原样显示为字符（这是有意的，避免 XSS）。若贺卡确需富文本，请先约定标签白名单，前端再接 `v-html` + 消毒库（需新增依赖，届时再评估）。
- **发送入口：** 贺卡由后端生产（如节日定时任务 / 等级达成时），前端不提供“发信”界面，与 13.4 一致。

### 13.8 待修 / 待办（后端）

| 编号 | 问题 | 影响 | 建议 |
| ---- | ---- | ---- | ---- |
| MSG-01 | init.sql 索引列名错误（**已修复**） | 索引现为 `ON t_message(f_status, f_create_time DESC)`，与实际列名一致 | 已闭环 |
| MSG-02 | 业务侧消息生产（**部分完成**） | 已接入签到（`CheckInService`）与每日天气（`WeatherServiceImpl`）；预算超支 / 等级变动仍缺 | 按 13.4 在预算超支 / 等级变动节点写入消息 |
| MSG-03 | `markAllMessageRead` 循环调 `markMessageRead`，N 条 = N 次 update SQL | 大量未读时性能下降（功能无影响） | 改为单条 `update t_message set f_status = 1, f_update_time = ... where f_id in (...)`；或额外提供无参「全部已读」（`where f_status = 0`），前端可免收集 id、也不再受 50 条窗口限制 |
| MSG-04 | 无消息保留/清理策略 | t_message 无限增长 | 定期清理（保留最近 200 条或 90 天）；前端只拉最近 50 条 |
| MSG-05 | **贺卡封面未落地**：`MessageType.GREETING` 已补（✅），`MessageDTO.cardImage`、表列 `f_card_image`、`ImageType.GREETING_CARD` 仍缺 | 贺卡版式与文案已可用，仅封面图不显示 | 见 13.7，补齐后前端零改动生效 |
| MSG-06 | `page` 返回全量且带完整 `content` | 消息量大或正文（贺卡）很长时，列表响应体偏大 | 补分页拦截器（GAP-12）；如需瘦身可让 `page` 只返回标题 + 摘要，完整正文走 `detail/{id}`（前端详情已按此模式取数，无需改动） |
| MSG-07 | **枚举以 `name()` 落库产生脏数据（P0）**：实测 `t_message.f_type` 存在 `CHECK_IN`、`GREETING`（大写枚举名）与 `weather`（小写传输值）混杂；`MessageType` 只有 `@JsonValue`/`@JsonCreator`（仅影响 JSON 层），MyBatis-Plus 需 `@EnumValue` 或全局 `MybatisEnumTypeHandler` 才会用 `getValue()` 存取 | `fromValue` 严格匹配小写 → 读取含大写值的记录抛 `IllegalArgumentException`，**`page` / `selectAll` / `detail` 整表查询失败**，前端铃铛降级隐藏（看不到任何消息） | ① 枚举的 `type` 字段加 `@EnumValue`（或配 `mybatis-plus.configuration.default-enum-type-handler`）；② 清洗历史数据 `update t_message set f_type = lower(f_type)`；③ `fromValue` 改 `equalsIgnoreCase` 容错，避免单条脏数据打挂整表查询 |
| MSG-08 | **WeatherServiceImpl 实现细节**（描述链已修 ✅ 2026-09-07，剩余项待修）：`BodyHandlers.ofString(Charset.defaultCharset())` 用平台默认字符集；`@PostConstruct` 内同步调外网（wttr.in）且无超时；`title` 固定「每日天气」不含日期 | 非 UTF-8 默认字符集下中文描述可能乱码；断网/慢网时启动被拖住 | 字符集固定 `StandardCharsets.UTF_8`；`HttpClient.connectTimeout` + `HttpRequest.timeout`，或把推送改为异步/定时任务；`title` 带日期。✅ 已修部分：请求加 `lang=zh`；中文解析链改为 `lang_zh（仅认含汉字）→ CODE_TEXT（补 149→霾，修正 350/362/365/374/377 等语义）→ KEYWORD_TEXT 英文关键词中文化 → 英文原文`；city 改用 city.json（pinyin→中文名，38 组重复 pinyin 已用 merge 函数处理，未命中回退原名），端到端实测 `Weather[city=北京, description=霾, …]` |

> `unreadCount` 返回 `DataResponse<Long>`，前端已统一 `Number()` 转换，无需调整。

### 13.9 每日天气消息（后端已实现，前端已适配）

后端 `WeatherServiceImpl.init()`（`@PostConstruct`）在应用启动时拉取 wttr.in 当日天气，当日尚无 `weather` 消息时推送一条（靠 `messageService.exists(WEATHER, 今日 00:00, 次日 00:00)` 去重，同一天多次重启不会重复推）。

**契约：`content` 存 `Weather` record 序列化后的 JSON（不是给人读的文本）**

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| city | string | 查询城市名（wttr.in 入参，当前硬编码 `beijing`） |
| description | string | 天气描述；`CODE_TEXT` 命中 weatherCode 时为中文，未命中回落 `lang_zh` / `weatherDesc`（**实测出现过英文 `Smoky haze`**） |
| tempC / feelsLikeC | string | 当前气温 / 体感温度（℃） |
| minTempC / maxTempC | string | 当日最低 / 最高气温（℃） |
| humidity | string | 相对湿度（%） |
| windKmph | string | 风速（km/h） |

`title` = 「每日天气」，`bizType` / `bizId` = null（详情底部无跳转按钮），无 `cardImage`。实测正文示例：

```json
{"city":"beijing","description":"Smoky haze","tempC":"26","feelsLikeC":"27","minTempC":"18","maxTempC":"31","humidity":"53","windKmph":"6"}
```

**前端适配（`utils/weather.ts` + `components/messageIcons.ts`）：**

- 解析：`parseWeather(content)` 严格校验（必须是 JSON 对象且含 `description` 或 `tempC`），失败返回 null → 详情回退纯文本、列表回退原始正文，**不报错不弹提示**（后端日后改格式也不会白屏）。
- 列表：预览用 `weatherSummary` 生成摘要（后端中文化后如 `北京 · 霾 · 27°C · 19~30°C`）替代 JSON，缺失字段自动省略。
- 详情：天气卡片版式（渐变蓝绿底 `#2f9fd6 → #6fd3c7` + 白字，深浅主题均可读）——头部为图标 + 城市 + 描述 + 大字号当前温度，下方 2×2 网格展示体感温度 / 今日气温 / 相对湿度 / 风速，空值显示 `-`。
- 图标：`weatherIconKey(description)` 按关键词优先级（雷 > 大雨 > 雨 > 雪 > 阴/雾/霾 > 多云 > 晴）映射到 Element Plus 图标，**中英文描述均兼容**（thunder / heavy rain / shower / snow / fog / haze / cloud / sun …）；EP 无雪花图标，降雪暂用 `Drizzling`。
- **CODE_TEXT 审计与修复（2026-09-07 实测，已落地）**：原键集合与经典 WWO 47 码表完全一致但码空间不封闭（北京实测表外码 `149` Smoky haze）；且 wttr.in 的 `lang_zh` 即使带 `lang=zh` 对常见码也返回英文回声（8/8 样本无汉字）。后端已改为四级解析链：`lang_zh（仅认含汉字，未来上游补齐翻译自动生效）→ CODE_TEXT（补 149→霾，修正 350/374/377→冰粒、362/365→雨夹雪阵等语义）→ KEYWORD_TEXT 英文关键词中文化（族级文案，与前端 weatherIconKey 同思路）→ 英文原文`；city 改用 city.json pinyin→中文名映射（38 组重复 pinyin 用 merge 函数，未命中回退原名）。端到端探针实测：`Weather[city=北京, description=霾, tempC=27, …]`，兜底链 14/14 断言通过。前端同步在 `ICON_RULES` 雪类规则补 `冰粒|霰` 关键词，其余零改动。
- 色值 `#00B8A9`、类型标签「天气」，类型筛选下拉自动包含（FR-MSG-07）。

**给后端的可选优化（不阻塞前端，详见 MSG-08）：** 若不想维护 JSON 契约，`content` 也可以直接写人类可读文本（如「北京 晴 26°C，体感 27°C，今日 18~31°C，湿度 53%，风速 6 km/h」），前端解析失败会自动回退纯文本展示（仅失去卡片版式）；若保留 JSON，建议 `city` 用中文（wttr.in 支持中文城市名）、`title` 带上日期（如「9 月 7 日天气」）。

## 14. 统计聚合接口契约（后端已实现、前端已接入，OPT-03 / GAP-05）

> ⚠️ **本章记录后端「实际实现」口径，与最初草案有出入（请求字段名、trend 的 key 格式与 week 语义、monthly 是否补零）。前端已按实际后端适配，请勿再照旧草案改后端，否则会破坏对接。**

`TransactionController` 已提供 4 个聚合端点（`stats/summary|trend|category|monthly`）。报表页（`views/ReportView.vue`）与总览页（`views/DashboardView.vue`）已改走这些端点，并保留「不可用回退 `selectAll` + `utils/aggregate.ts` 前端聚合」的静默降级（与 12.3 同模式）。`selectAll` 仍保留：最近流水、空态判断、CSV 导出、降级聚合都要用。

**通用约定：**

- 端点挂在 `TransactionController` 自定义端点，前缀 `/api/transaction/stats/`；响应统一 `DataResponse<T>`。
- 金额单位为元；BigDecimal 序列化为 number（前端仍统一 `Number()` 兜底）。
- 收支口径：仅 `income` / `expense` 计入，`transfer` **一律不计入**。
- **请求体统一用 `RangeRequest` 的 `start` / `end` 两字段**（非 `startDate/endDate`），后端按 `f_date` 做 `between(start, end)` 闭区间过滤：
  - `summary` / `trend` / `category` 为 `RangeRequest<LocalDateTime>`，须传 `yyyy-MM-ddTHH:mm:ss`；前端按闭区间补 `T00:00:00` / `T23:59:59`。
  - `monthly` 为 `RangeRequest<LocalDate>`，传 `yyyy-MM-dd`。

### 14.1 POST /transaction/stats/summary（区间收支汇总）

请求（`RangeRequest<LocalDateTime>`）：

```json
{ "start": "2026-01-01T00:00:00", "end": "2026-09-11T23:59:59" }
```

响应 `data`：

```json
{ "income": 12000.00, "expense": 8000.00, "balance": 4000.00, "count": 42 }
```

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| income / expense | number | 区间收入 / 支出合计（元） |
| balance | number | 结余 = income − expense |
| count | number | 收支笔数（不含 transfer） |

用途：报表页「范围内收入 / 支出」；总览页「本月收入 / 支出 / 结余」（传当月区间）。

### 14.2 POST /transaction/stats/trend（按时间分桶趋势）

请求（`TrendRequest extends RangeRequest<LocalDateTime>`）：

```json
{ "start": "2026-01-01T00:00:00", "end": "2026-09-11T23:59:59", "granularity": "month" }
```

`granularity` 取 `day` / `week` / `month` / `year`。响应 `data`（数组，**仅含有数据的桶**，后端按 `f_date` 升序返回）：

```json
[ { "key": "2026-9", "income": 1000.00, "expense": 600.00 } ]
```

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| key | string | 分桶键（**实际后端未补零**）：year=`2026`；month=`2026-9`；day=`2026-9-14`；**week=`2026-9-1`（年-月-`dayOfWeek`，1=周一…7=周日，故同月所有周一会并成一桶，与「按周一起始日分桶」的直觉不同）** |
| income / expense | number | 该桶收入 / 支出合计（元） |

> 展示用 label 由前端按 key + 粒度生成（`utils/aggregate.ts` 的 `trendLabelFromKey`：year→「2026年」、month→「9月」、day→「09-14」、week→「周一…周日」），后端不返回 label。因 key 未补零，前端**保留后端返回顺序、不再按字符串排序**（字符串序≠时间序）；趋势图不补空桶。

用途：报表趋势图四档粒度切换；总览本月按日趋势。

### 14.3 POST /transaction/stats/category（分类汇总）

请求（`TransactionCategoryRequest extends RangeRequest<LocalDateTime>`）：

```json
{ "start": "2026-01-01T00:00:00", "end": "2026-09-11T23:59:59", "type": "expense", "groupBy": "root" }
```

`type` 取 `income` / `expense`；`groupBy` 取 `root`（父分类）/ `self`（子分类）。响应 `data`（数组，**后端 HashMap 分组无序**，前端 `adaptCategory` 按 `amount` 降序）：

```json
[ { "categoryId": 3, "name": "餐饮", "icon": "food", "color": "#F56C6C", "amount": 1234.50, "count": 7 } ]
```

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| categoryId / name / icon / color | number / string | 分类标识与展示信息（`root` 时为一级分类，`self` 时为交易实际分类） |
| amount | number | 该分类金额合计（元） |
| count | number | 笔数 |

> `groupBy=root`：子 / 孙分类金额沿 `parentId` 逐级归并到其一级分类；已删除分类的交易不计入。返回已带 name/icon/color，前端可直接渲染，无需再 join 字典。

用途：报表 / 总览分类占比环形图 + 分类明细表（含 CSV 导出）。

### 14.4 POST /transaction/stats/monthly（月度盈亏序列）

请求（`RangeRequest<LocalDate>`，传 `yyyy-MM-dd`）：

```json
{ "start": "2026-01-01", "end": "2026-09-30" }
```

响应 `data`：

```json
{
  "rows": [
    { "key": "2026-1", "year": 2026, "month": 1, "income": 0.00, "expense": 0.00, "balance": 0.00, "cumulative": 0.00 }
  ],
  "surplusMonths": 3,
  "deficitMonths": 2,
  "cumulative": 4000.00
}
```

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| rows | array | **实际后端只返回有交易的月（稀疏，未按区间补零）**，按 `f_date` 升序；查询区间为 `between(start, end.plusMonths(1))`，会多带结束月之后的一个月 |
| rows[].key / year / month | string / number | 月份键（**未补零**，如 `2026-1`）与年、月；前端用 `year`/`month` 整型字段，不依赖 key |
| rows[].income / expense / balance | number | 该月收入 / 支出 / 结余（= income − expense） |
| rows[].cumulative | number | 自区间起点起的累计结余（running sum）；前端会在补零后的连续序列上重算，不直接用此值 |
| surplusMonths / deficitMonths | number | `balance > 0` / `< 0` 的月份数 |
| cumulative | number | 末月累计结余（= 区间总收支差） |

> 前端 `adaptMonthly` 会把稀疏 rows 按 `[起始月, 结束月]` **补零为连续月份**（保证图表月份轴不断档、并裁掉后端 `end+1` 月溢出），并在连续序列上重算 `cumulative` 与盈余/亏损月数；跨年展示标签（`M月` / `yy/MM`）由前端按 year/month 生成。后端返回的 `surplusMonths`/`deficitMonths`/`cumulative` 前端不直接用。

用途：报表月度盈亏图 + 明细表 + 概览；总览近 6 个月盈亏。

### 14.5 GET /transaction/recent（最近流水）——**已实现并接入**

响应 `data`：`TransactionDTO[]`，后端固定 `order by f_date desc limit 5`（**无 `limit` 查询参数**，SQL 硬编码取前 5 条）。用途：总览「最近流水」，避免为 5 条记录拉全量。

> 前端已接入：`api/index.ts` 新增 `transactionApi.recent({silent})`（GET `/transaction/recent`）；`DashboardView` 的 `recentTransactions` 优先取后端结果（`beRecent`），作为独立 `silent` 路**不纳入四路聚合的 `statsDead` 判定**；失败时降级为 `selectAll` 结果本地按日期倒序取前 5。

### 14.6 前端接入与降级（已落地）

- 报表 / 总览用 `Promise.allSettled` 并发调四端点，**每路各自 `silent`**：某路失败则该项回退本地 `aggregate.ts` 聚合，其余路不受影响；四路全失败置 `statsDead`，本会话不再重复探测（与 12.3 同模式，不弹提示）。
- 影响聚合口径的控件（报表：范围 / 粒度 / 收支维度 / 分类口径；总览：父/子分类切换）变化时重拉对应端点。
- `selectAll` 全量拉取**仍保留**（最近流水、空态、CSV 导出、降级聚合），未删除；若后续要彻底去除本地聚合，需先保证四端点稳定。
- 「账户余额变化图」（FR-RPT-03）**前端已实现**：纯本地按流水推演（`aggregate.ts` 的 `accountBalanceSeries`，期初余额 = initialBalance + start 之前净变动，区间内按变动日阶梯取点），**不依赖后端余额序列端点**；同理 RP-02 区间对比、RP-03 预算历史对比、RP-01 PDF 导出均为纯前端能力。
