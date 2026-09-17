# 桌面端功能路线图与优化清单（Roadmap / Backlog）

| 项目 | 内容 |
| ---- | ---- |
| 文档版本 | v1.0 |
| 编写日期 | 2026-09-09 |
| 上游文档 | [overview.md](./overview.md)（总体需求与架构）、[frontend-requirements.md](./frontend-requirements.md)（前端需求与接口契约）、[desktop-packaging.md](./desktop-packaging.md)（打包分发） |
| 核对基线 | 2026-09-09 对照 `frontend/` 与 `src/main/java` 源码逐项核实；2026-09-16 二次核实（余额联动/备份恢复/CSV/批量接口/统计接口已落地，新增优化项见 4.5） |
| 读者 | 产品、前端、后端、测试、发布 |

---

## 1. 文档说明

本文集中记录桌面端（Electron + Vue 前端 + jpackage 原生 Java 后端）**尚未开发的功能**与**可优化的地方**，作为可持续维护的待办清单（Backlog）。

- **不重复接口契约**：后端接口差距以 [frontend-requirements.md 第 8 章「后端依赖与差距清单」](./frontend-requirements.md) 为权威来源；本文只在第 5 章标注**本次核实的最新状态**（哪些已闭环、哪些仍未修）。
- **优先级定义**：P0 阻断核心流程 / P1 核心体验 / P2 增强 / P3 远期。
- **状态标记**：`未开发`（尚未动工）、`占位`（前端已留位、能力未接）、`部分`（已做一部分）、`已闭环`（完成，仅列于现状快照）。
- 每项尽量给出**现状证据**与**建议改动点**，便于直接排期。

---

## 2. 现状快照（已落地能力，避免重复开发）

以下能力已在代码中实现并通过核对，**不属于待开发范围**，列此仅作基线：

| 领域 | 已实现能力 | 证据 |
| ---- | ---- | ---- |
| 页面模块 | 总览 / 账户 / 流水 / 报表 / 预算 / 等级 / 设置 七大页面 + 路由 | `frontend/src/router/index.ts`、`views/*` |
| 快速记账 | 全局弹窗、应用内 `Cmd/Ctrl+N`、系统全局 `Cmd/Ctrl+Shift+B` | `layouts/MainLayout.vue`、`electron/main.cjs` |
| 系统集成 | 托盘菜单（显示/快速记账/退出）、关窗隐藏到托盘、窗口位置记忆 | `electron/main.cjs` |
| 后端进程 | 拉起 jpackage 子进程、健康检查轮询、异常自动重启（≤3 次）、退出回收 | `electron/main.cjs` |
| 启动过渡 | 冷启动无边框闪屏（Logo + 加载进度 + 实时状态文案），后端就绪后切主窗口；启动失败先收闪屏再弹错误框 | `electron/main.cjs`、`electron/splash.html` |
| 个性化 | 深浅色主题、金额小数位、背景图（预设+自定义上传+遮罩）、息屏屏保 | `views/SettingsView.vue`、`stores/settings.ts` |
| 站内信 | 铃铛+未读角标、列表、详情弹窗、业务跳转、已读/删除、贺卡/天气版式 | `components/MessageCenter.vue`、`MessageDetailDialog.vue` |
| 消息生产 | 启动自动签到消息、每日天气消息（wttr.in） | `service/impl/CheckInServiceImpl`、`WeatherServiceImpl` |
| 预算提醒 | 使用率首次越过 80%/100% 应用内通知（localStorage 去重，每月每阈值仅一次） | `composables/useBudgetAlert.ts`、`layouts/MainLayout.vue` |
| 等级/签到 | 等级页、经验进度、签到日历展示 | `views/LevelView.vue`、`components/CheckIn*` |
| 帮助体系 | 使用说明面板 + 新手引导（el-tour） | `components/HelpPanel.vue`、`GuideTour.vue` |
| 数据导出 | 流水/报表 CSV 导出（带 BOM，Excel 兼容） | `utils/csv.ts` |
| 报表增强 | 账户余额变化图、环比/同比区间对比、报表导出 PDF（打印样式） | `views/ReportView.vue`、`utils/aggregate.ts` 的 `accountBalanceSeries`、`styles/index.css` 的 `@media print` |
| 预算对比 | 预算执行历史对比（近 6 个月总预算 vs 已用柱状图） | `views/BudgetView.vue` |
| 记账模板 | 快速记账常用模板（存为模板/一键回填/删除） | `components/QuickRecordDialog.vue`、localStorage `bookkeeping-quick-templates` |
| 前端性能 | ECharts 按需引入（仅注册用到的图表/组件） | `utils/echarts.ts` |
| 交易↔余额联动 | 增/删/改冲销-重放，转账含手续费，另有对账重算端点 | `TransactionServiceImpl.update/delete/reconcileBalances`、`AccountServiceImpl.applyEffect` |
| 数据管理 | 整库备份(`VACUUM INTO`)/恢复(覆盖+重启后端)、流水与账户 CSV 导出、流水 CSV 导入 | `BackupController`、`BackupServiceImpl`、`views/SettingsView.vue` |
| 批量接口 | `POST /transaction/batchDelete`、`/batchUpdateCategory`（API 已就绪，前端 UI 未接入，见 NEW-06） | `TransactionController`、`api/index.ts` |
| 统计聚合 | `stats/{summary,trend,category,monthly}`、`recent`、`budget/searchBudget`（前端已接入并保留本地降级） | `TransactionController`、`BudgetController` |
| 健康探针 | 轻量 `GET /api/health`（`SELECT 1` 校验 DB），主进程探活已切换 | `HealthController`、`electron/main.cjs` |
| 消息生产补全 | 预算超支、等级变动、里程碑/节日/生日贺卡均已生产（含同月/同日去重） | `BudgetServiceImpl.evaluateOverspendAlerts`、`UserLevelServiceImpl`、`GreetingServiceImpl` |
| 默认分类 seed | `init.sql` 幂等写入常用一二级收支分类，改善新用户冷启动 | `resources/init.sql` |

---

## 3. 未开发功能清单

### 3.1 数据管理（**已落地**，仅剩自动备份/加密等增强）

> 现状（2026-09-16 核实）：`SettingsView.vue`「数据管理」已接入后端 `/backup/**`：整库备份/恢复、流水与账户 CSV 导出、流水 CSV 导入均已可用；`BackupController` + `BackupServiceImpl` 已实现。恢复安全性与自动备份的增强项见 NEW-03。

| 编号 | 功能 | 状态 | 优先级 | 现状 / 建议改动点 |
| ---- | ---- | ---- | ---- | ---- |
| DT-01 | 手动备份 / 恢复（导出、导入 SQLite 数据库文件） | **已实现** | P1 | `BackupServiceImpl.createSnapshot/restore` + 前端 `onExportDb/onPickDb`；恢复安全性增强转 NEW-03 |
| DT-02 | 自动备份（定时/启动时备份，保留 N 份） | 未开发 | P2 | 主进程或后端定时任务，备份到 `userData/backups/`，滚动保留最近 N 份 |
| DT-03 | 流水导入 CSV | **已实现（基础版）** | P3 | `importTransactionsCsv` 按账户名/分类名解析、仅新增、逐行容错；导入预览/字段映射/重复检测见 NEW-11 |
| DT-04 | 清空数据 | 未开发 | P3 | 二次确认 + 输入确认文本；依赖后端清库端点，注意 SQLite 外键约束顺序 |
| DT-05 | 数据加密（AES-256 / SQLCipher） | 未开发 | P3 | overview.md 3.6/4.3 提出的可选能力，未排期；需评估 SQLite 加密方案与密钥管理（本地口令 / 系统 Keychain） |

### 3.2 桌面端（Electron）原生能力

> 现状：`electron/main.cjs`、`preload.cjs` 已实现托盘、全局快捷键、窗口记忆、后端进程管理；下列能力经 grep 核实**均未实现**（`package.json` 无 `vue-i18n`、`electron-updater`、`electron-store` 依赖）。

| 编号 | 功能 | 状态 | 优先级 | 现状 / 建议改动点 |
| ---- | ---- | ---- | ---- | ---- |
| EL-01 | **单实例锁定** | 未开发 | **P1** | 无 `app.requestSingleInstanceLock()`。多开会争用 8080 端口与同一 SQLite 文件，导致后端启动失败或数据竞争。建议：主进程首行加单实例锁，二次启动时 `showWindow()` 聚焦已有窗口 |
| EL-03 | **渲染进程崩溃 / 白屏兜底** | 未开发 | P1 | 无 `render-process-gone` / `did-fail-load` / `unresponsive` 处理。建议：监听并提供「重新加载」引导，避免白屏死窗 |
| EL-04 | 系统通知（Notification） | 未开发 | P2 | 预算超支/大额支出目前只进站内信（FR-BGT-06、13.4 均提到「可接系统通知」）。建议：主进程 `new Notification()`，窗口失焦时推送 |
| EL-05 | 检查更新 / 自动更新 | 未开发 | P2 | 关于页无「检查更新」按钮，无 `electron-updater`。建议：接入 electron-updater + 更新源（见 4.3 分发） |
| EL-06 | 快捷键自定义 | 未开发 | P2 | `QUICK_RECORD_ACCELERATOR` 在 `main.cjs` 硬编码；设置页无自定义入口（4.7 列 P2）。建议：设置持久化 + IPC 通知主进程 `globalShortcut` 重注册 |
| EL-07 | 多语言 i18n（中/英） | 未开发 | P2 | 无 `vue-i18n` 依赖，全应用中文硬编码（FR-DT-04、4.7 列 P2）。改动面大，建议整体规划后再动 |
| EL-08 | 开机自启 | 未开发 | P3 | 无 `app.setLoginItemSettings()`。建议：设置页加开关（macOS 登录项 / Windows 注册表 Run） |
| EL-09 | 原生主题跟随系统 | 未开发 | P3 | 无 `nativeTheme`。当前深浅色仅手动切换，建议增「跟随系统」选项并监听 `nativeTheme.on('updated')` |
| EL-10 | macOS Dock 右键菜单 / 托盘 template 图标 | 未开发 | P3 | 无 `app.dock.setMenu()`；托盘图标非 template，深色菜单栏下可能对比度不佳。建议：Dock 菜单加「快速记账」，托盘图标按平台适配 |

### 3.3 报表与流水增强

> RP-01（PDF 导出）/ RP-02（区间对比）/ RP-03（预算历史对比）/ TR-03（快速记账模板）与 FR-RPT-03（账户余额变化图）**均已落地**，已移入第 2 章现状快照；本表仅保留仍未开发项。

| 编号 | 功能 | 状态 | 优先级 | 现状 / 建议改动点 |
| ---- | ---- | ---- | ---- | ---- |
| TR-01 | 批量删除 / 批量改分类 | **接口已实现，UI 未接** | P2 | `batchDelete/batchUpdateCategory` 后端与 `api/index.ts` 均就绪；`TransactionView` 仍只有单条操作，多选 UI 见 NEW-06 |
| TR-02 | 金额区间筛选 | 未开发 | P2 | FR-TRX-11，后端区间操作符 ge/le 已支持（GAP-04 已闭环）；前端日期区间已接，金额区间筛选尚未接 |

### 3.4 预算与站内信补全

| 编号 | 功能 | 状态 | 优先级 | 现状 / 建议改动点 |
| ---- | ---- | ---- | ---- | ---- |
| MS-01 | 消息生产补全（预算超支 / 等级变动） | **已实现** | P1 | MSG-02：预算超支（实时+月末）、等级升降均已推站内信（同月/同预算去重），见 `BudgetServiceImpl.evaluateOverspendAlerts`、`UserLevelServiceImpl` |
| MS-02 | 贺卡封面图落地 | **已实现** | P2 | MSG-05：`MessageDTO.cardImage` + `t_message.f_card_image` + `GreetingServiceImpl`（节日/生日/里程碑）已就绪，封面用内置 SVG 预设 |
| MS-03 | 消息保留 / 清理策略 | 未开发 | P2 | MSG-04：`t_message` 无限增长。建议后端定期清理（保留最近 200 条或 90 天）；前端消息中心分页见 NEW-12 |
| MS-04 | 预算不足应用内提醒 | 部分 | P2 | 应用内通知已落地（`composables/useBudgetAlert`：交易/预算变更后评估，首次越过 80%/100% 弹 ElNotification + localStorage 去重）；剩余「系统通知」依赖 EL-04、后端超支站内信依赖 MS-01 |

---

## 4. 可优化项

### 4.1 性能与正确性

| 编号 | 优化点 | 优先级 | 现状 / 建议 |
| ---- | ---- | ---- | ---- |
| OPT-01 | 分页拦截器（GAP-12） | ✅ 据 backend-todo 已闭环 | 据 [backend-todo.md](./backend-todo.md) 核实：self-framework `SelfDaoConfiguration.mybatisPlusInterceptor()` 已注册 `PaginationInnerInterceptor`（`@ConditionalOnMissingBean`）。建议实测确认 `/page` 已拼 LIMIT 后，移除前端消息列表的 `slice` 兜底 |
| OPT-02 | ~~交易↔账户余额联动（GAP-02）~~ | **✅ 已修** | `TransactionServiceImpl` 已重写 insert/update/delete，采用“冲销-重放”，转账含手续费；另有 `reconcileBalances` 对账重算（前端入口见 NEW-07） |
| OPT-03 | ~~无统计聚合接口（GAP-05）~~ | **✅ 已实现** | `stats/{summary,trend,category,monthly}` + `recent` + `budget/searchBudget` 均已提供；前端已接入并保留本地降级（报表首屏仍全量拉取，见 NEW-08） |
| OPT-04 | 报表聚合放 Web Worker | P2 | 需求 4.3.2 要求万条聚合不阻塞 UI；当前在主线程计算。建议迁移 Web Worker |
| OPT-05 | 流水长列表虚拟滚动 | P2 | 非功能需求提出；当前严格分页，若放开每页条数可引入虚拟滚动 |
| OPT-06 | ~~后端健康检查探针偏重~~ | **✅ 已修** | 已新增 `GET /api/health`（`SELECT 1`），`main.cjs` 的 `HEALTH_PATH` 已切换；主进程仅判 HTTP 200 未看 body 的 database 状态，见 NEW-05 |

### 4.2 体验

| 编号 | 优化点 | 优先级 | 现状 / 建议 |
| ---- | ---- | ---- | ---- |
| OPT-08 | 后端端口固定 8080，冲突无降级 | P2 | 需求 2.3 曾预留「动态端口」，未实现。端口被占用时后端起不来。建议：主进程探测空闲端口，通过启动参数/环境变量传给后端，前端 BaseURL 随之调整 |
| OPT-09 | 深浅色跟随系统（见 EL-09） | P3 | 目前仅手动切换 |

### 4.3 工程与分发

| 编号 | 优化点 | 优先级 | 现状 / 建议 |
| ---- | ---- | ---- | ---- |
| OPT-10 | **代码签名 + 公证** | **P1** | 当前为 **ad-hoc 签名、未公证**，用户安装需手动绕过 Gatekeeper（`xattr -cr` 或系统设置放行），分发体验差、易被判定为不安全。建议：申请 Apple Developer 证书做签名 + `notarytool` 公证；Windows 侧考虑代码签名证书 |
| OPT-11 | 自动更新通道 | P2 | 见 EL-05；配合 electron-builder `publish` + 更新服务器（GitHub Releases / 自建） |
| OPT-12 | 前端崩溃 / 错误日志 | P2 | 无全局错误捕获与落盘。建议：`electron-log` + 渲染进程 `errorHandler`，便于用户反馈时附日志（后端已有 `backend.log`） |
| OPT-13 | CI 三平台打包 | P3 | 目前主要 macOS 手工打包（见 desktop-packaging.md）。Win/Linux 产物**未在对应平台实测**（后端原生不可交叉编译）。建议：CI 矩阵（mac/win/linux）分别产出 |

### 4.4 安全与健壮性

| 编号 | 优化点 | 优先级 | 现状 / 建议 |
| ---- | ---- | ---- | ---- |
| OPT-14 | 本地 HTTP 服务无鉴权 | P2 | 后端 `127.0.0.1:8080` 无鉴权，本机任意进程可读写记账数据。建议：随机端口 + 启动时生成一次性 token，前端请求头携带 |
| OPT-15 | 数据加密（见 DT-05） | P3 | 本地明文存储 |
| OPT-16 | 危险操作强确认覆盖 | P2 | 删除类已有二次确认；「清空数据」（DT-04）落地时需输入确认文本 |

### 4.5 本次核实新增优化项（2026-09-16）

> 基于当前源码重新排查发现的新缺口，按优先级归档。旧的 P0 正确性项（GAP-02/MSG-07 等）已修复，本次未发现新的 P0 阻断项。

**P1 · 正确性与数据安全**

| 编号 | 优化点 | 层 | 现状 / 建议 |
| ---- | ---- | ---- | ---- |
| NEW-01 | 三级分类“录入—筛选”未打通 | 前端 | 快速记账可逐级下钻到三级，但 `TransactionView.categoryOptions` 只构建两层、`categoryIdsWithChildren` 仅含直属子级 → 筛一级会漏孙级流水、且无法直接筛三级。建议抽统一“全部后代 ID”工具，录入/筛选/预算跳转共用 |
| NEW-02 | 统计时间边界不一致 | 后端 | `selectListByYearMonth` 用 `gt(月初零点)` 会漏掉当天 `00:00:00` 的流水（CSV 仅日期导入正是零点）；`monthly` 用 `end.plusMonths(1)` 可能纳入截止日之后数据。建议统一为左闭右开区间并补月初/月末/跨年边界测试 |
| NEW-03 | 备份恢复安全性 | 后端 | `restore` 在活动连接下替换文件+删 sidecar，靠前端重启生效；仅校验 SQLite 文件头。建议校验完整性/表结构/版本、失败回滚；补自动备份与保留份数（DT-02）；明确图片/设置/模板不在 `.db` 内 |
| NEW-04 | 写请求网络重试幂等 | 前端 | `http.ts` 对所有 `ERR_NETWORK` 自动重试（含 `save/saveWithReward`），可能重复记账。建议读可重试、写加幂等标识或提示用户核对结果 |
| NEW-05 | Electron 健壮性 | 前端 | `main.cjs` 缺单实例锁（EL-01）、白屏/崩溃兜底（EL-03）；健康检查只判 HTTP 200 未看 body 的 `database` 状态。建议补单实例锁、崩溃重载、按 body 判定就绪 |

**P2 · 效率与体验**

| 编号 | 优化点 | 层 | 现状 / 建议 |
| ---- | ---- | ---- | ---- |
| NEW-06 | 流水批量管理 UI | 前端 | 后端/API 已有 `batchDelete/batchUpdateCategory`，`TransactionView` 仍只有单条操作。建议加多选+批量删除/改分类；补金额区间筛选（TR-02） |
| NEW-07 | 账户对账入口 | 前端 | 后端有 `reconcile` 端点，前端无入口。建议展示账面余额 vs 流水推算差额，确认后重算，操作前提示备份 |
| NEW-08 | 报表首屏全量加载 | 前端 | `ReportView` 仍先 `selectAll` 再打四个 stats 接口，聚合收益被抵消。建议首屏直接用 stats，余额曲线/对比/导出按需拉取；筛选请求加取消/序号防旧响应覆盖 |
| NEW-09 | 快速记账记忆与草稿 | 前端 | `QuickRecordDialog` 每次重置为支出+第一个账户。建议记住上次账户/分类、常用分类排序、未提交草稿、模板失效检查 |
| NEW-10 | 预算辅助决策 | 前端 | `BudgetView` 只展示额度/已用/剩余。建议加剩余日均可花、月底预测、复制上月预算、点预算看对应流水；历史结果按月缓存减少重复请求 |
| NEW-11 | CSV 导入向导 | 前后端 | 导入直接落库只反馈成功/跳过数。建议加预览、字段映射、重复检测、失败行原因与下载 |
| NEW-12 | 消息中心分页与全部已读 | 前后端 | 仅取最近 50 条，`markAllRead` 只处理已加载未读。建议分页加载、服务端未读筛选，全部已读覆盖真实全部未读 |

**P2 · 工程保障**

| 编号 | 优化点 | 层 | 现状 / 建议 |
| ---- | ---- | ---- | ---- |
| NEW-13 | 回归测试保障 | 工程 | 前端无自动化测试；后端测试未覆盖真实 DB 恢复与完整增删改余额链路。建议补：转账含手续费增删改余额、三级分类筛选/预算一致、CSV 重复/非法/恢复回滚、筛选竞态、打包 `file://` 冷启动 |

---

## 5. 后端支撑差距状态（本次核实更新）

> 契约细节见 [frontend-requirements.md 第 8 / 12 / 13 章](./frontend-requirements.md)；下表为 **2026-09-16 核实的最新状态**（后端修复证据见 [backend-todo.md](./backend-todo.md)）。

| 编号 | 差距项 | 文档原状态 | **2026-09-16 核实** |
| ---- | ---- | ---- | ---- |
| GAP-01 | TransactionDTO 缺 `amount` | P0 待修 | ✅ **已闭环**：`dao/dto/TransactionDTO.java` 已有 `private BigDecimal amount;` |
| GAP-02 | 交易↔余额联动 | P0 待修 | ✅ **已修**：`TransactionServiceImpl` 重写 insert/update/delete（冲销-重放）+ `reconcileBalances` 对账（见 OPT-02） |
| GAP-04 | 查询区间操作符 ge/le | P1 待修 | ✅ **已实现**：`Query` 枚举含 `GE/LE/BETWEEN`；前端日期区间已走 page，金额区间筛选未接（TR-02） |
| GAP-05 | 无统计聚合接口 | P1 待修 | ✅ **已实现**：`stats/*` + `recent` + `searchBudget`（见 OPT-03） |
| GAP-06 | 无内置默认分类 | P1 待修 | ✅ **已修**：`init.sql` 幂等写入 8 支出一级/5 收入一级及约 30 二级 |
| GAP-07 | 分页排序 | 已解决 | ✅ 已闭环（`sortList` 生效） |
| GAP-08 | 无批量操作接口 | P2 待修 | ✅ **已实现**：`batchDelete/batchUpdateCategory`（前端 UI 未接，见 NEW-06） |
| GAP-09 | 无备份/恢复/导入导出 | P3 待修 | ✅ **已实现**：`BackupController` + `BackupServiceImpl`（恢复安全增强见 NEW-03） |
| GAP-11 | 站内信资源 | 已实现 | ✅ 已闭环（含天气/签到/预算/等级/贺卡生产） |
| GAP-12 | 分页未生效 | P1 待修 | ✅ **据 backend-todo 已闭环**：self-framework 已注册 `PaginationInnerInterceptor`（见 OPT-01） |
| MSG-07 | 消息 `f_type` 大小写脏数据 | P0 待修 | ✅ **已修**：`MessageType.fromValue` 改 `equalsIgnoreCase` + 未命中降级 `SYSTEM` + warn 日志 |

---

## 6. 建议排期（2026-09-16 更新）

> 原则：先做「正确性与数据安全」的 P1，再推效率体验与工程保障。前端扩展遵循最小改动（优先复用现有设置体系与布局挂载点）。旧的 P0 正确性项（GAP-02 余额联动、MSG-07 枚举容错）与多项 P1 能力（统计接口、健康探针、默认分类、批量接口、备份恢复）已落地，无需再排期。

**第一批 · 正确性与数据安全（P1）**
- NEW-01 三级分类录入—筛选打通（前端，投入小收益明确）
- NEW-02 统计时间边界统一（后端，月初零点/月末越界）
- NEW-03 备份恢复安全性 + 校验回滚（后端）
- NEW-04 写请求网络重试幂等（前端，防重复记账）
- NEW-05 Electron 单实例锁 + 崩溃兜底 + 健康检查按 body 判定（与 EL-01/EL-03 同源，一并处理）

**第二批 · 效率与体验（P2）**
- NEW-06 流水批量管理 UI + TR-02 金额区间筛选
- NEW-07 账户对账入口（暴露已有 `reconcile` 端点）
- NEW-08 报表首屏改用 stats、请求防竞态
- NEW-09 快速记账记忆与草稿
- NEW-10 预算辅助决策（日均可花/预测/复制上月）
- NEW-11 CSV 导入向导、NEW-12 消息中心分页与全部已读

**第三批 · 工程保障与分发（P2/P3）**
- NEW-13 回归测试保障
- OPT-10 代码签名 + 公证、OPT-12 前端错误日志、EL-05/OPT-11 自动更新
- DT-02 自动备份、OPT-14 本地鉴权、EL-06 快捷键自定义、EL-07 多语言、DT-05 数据加密

> 已完成（无需再排期）：RP-01/02/03、TR-03、FR-RPT-03、OPT-02 余额联动、OPT-03 统计接口、OPT-06 健康探针、DT-01 备份恢复、DT-03 CSV 导入、GAP-06 默认分类、GAP-08 批量接口、MS-01/MS-02 消息生产与贺卡。

---

## 附：与现有文档的关系

- **需求与接口契约**：见 [frontend-requirements.md](./frontend-requirements.md)（GAP 清单、消息/等级/日期区间契约）。
- **总体需求与架构**：见 [overview.md](./overview.md)。
- **打包与分发操作**：见 [desktop-packaging.md](./desktop-packaging.md)（签名/公证优化项 OPT-10 在此文档落地）。
- 本文随功能推进持续更新：完成后将条目从第 3/4 章移入第 2 章现状快照，并同步第 5 章差距状态。
