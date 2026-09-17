# 后端待实现功能清单（Backend TODO）

> 生成时间：2026-09-15。基于对 Bookkeeping 后端源码 + self-framework 源码的逐项核实（非照搬旧文档）。
> 用途：供后端**逐一实现**。每项含【现状证据】【影响】【实现建议】【涉及文件】。
> 优先级：P0 数据正确性 / P1 核心体验缺失 / P2 运维健壮性 / P3 增强。

---

## 0. 已实现（明确标注，避免重复开发）

以下项经源码核实**已落地，无需再做**：

| 项 | 状态 | 证据 |
|----|------|------|
| GAP-12 分页拦截器 | ✅ 已实现 | `self-framework` `SelfDaoConfiguration.mybatisPlusInterceptor()` 注册 `MybatisPlusInterceptor` + `PaginationInnerInterceptor`（`@ConditionalOnMissingBean`） |
| GAP-04 查询区间操作符 | ✅ 已实现 | `Query` 枚举含 `GE("ge")/LE("le")/BETWEEN("between")`；`IBaseCrudServiceImpl.bindQueryWrapper` 已有对应 case 分支 |
| GAP-03 insert 语义 | ✅ 已正确 | `IBaseCrudServiceImpl.insert` = `mapping.toEntity(dto)` + `savePreCheck` + `save`（非旧文档所述 selectById） |
| GAP-05 统计聚合端点 | ✅ 已实现 | `TransactionController` 的 `stats/{summary,trend,category,monthly}` + `recent`；`BudgetController.searchBudget` |
| GAP-11 站内信资源 | ✅ 已实现 | `t_message` + `MessageController`（page/unreadCount/read/readAll/clearRead/delete） |
| 第 11 章 等级接口 | ✅ 已实现 | `LevelController` 的 `currentLevel`（全扩展字段）/`configs`/`logs` |
| 消息生产：签到 / 天气 | ✅ 已实现 | `CheckInServiceImpl`（CHECK_IN）、`WeatherServiceImpl`（WEATHER） |
| 月末结算调度 | ✅ 已修复（常量 + `gainExperience` 符号，含单测，见 P0-2） | `TaskServiceImpl.monthlyLevelChange`（cron）+ `UserLevelServiceImpl.init`（@PostConstruct） |
| init.sql 索引列名 | ✅ 已修正 | `idx_message_read(f_status, f_create_time DESC)`、`idx_transaction_*` 列名均正确 |
| 贺卡基础设施 | ✅ 已就绪并接入生产者（见 P1-3） | `MessageType.GREETING` + `MessageDTO.cardImage` + `MessageEntity.cardImage` + `t_message.f_card_image` |

---

## P0 · 数据正确性（必须优先修复）

### P0-1 · GAP-02 交易 ↔ 账户余额联动不完整

> **修复状态（2026-09-15）**：✅ **已修复**（冲销-重放模式）。① `AccountService` 接口由 `operateAccount/modifyAccount` 重构为余额影响原语 `applyEffect(TransactionDTO, boolean reverse)` + `setBalance(accountId, balance)`；② `applyEffect` 将余额影响抽象为纯函数，insert=施加、delete=冲销、update=冲销旧快照+施加新快照（读回落库后完整快照规避 `updateById` 只写非 null 字段），TRANSFER 转出方含 `amount+fee`、手续费不再凭空消失；③ `TransactionServiceImpl` 重写 `update/delete` 并新增 `@Transactional reconcileBalances()`（复位到 `f_initial_balance` + 按 `f_date,f_id` 重放全部流水），`TransactionController` 新增 `POST /transaction/reconcile` 对账端点。`mvn compile` BUILD SUCCESS。

**现状证据**
- `TransactionServiceImpl` **仅重写了 `insert()`**（[L41-49](file:///Users/zhuxiao/Desktop/fxXml/workSpace/Bookkeeping/src/main/java/com/bookkeeping/service/impl/TransactionServiceImpl.java#L41-L49)）调用 `accountService.operateAccount(...)`。
- `update()` / `delete()` **未重写** → 走框架默认实现，只改/删流水行，**不调整任何账户余额**。
- `AccountServiceImpl.operateAccount(accountId, toAccountId, amount, type)`（[L40-57](file:///Users/zhuxiao/Desktop/fxXml/workSpace/Bookkeeping/src/main/java/com/bookkeeping/service/impl/AccountServiceImpl.java#L40-L57)）**签名无 `fee` 参数**；`TransactionDTO/Entity` 有 `fee`（`f_fee`）字段但从未参与余额计算。转账时转出方仅 `setDecrBy(amount)`，手续费"凭空消失"。

**影响**
- 编辑一笔流水的金额/类型/账户/转账目标后，账户余额与流水不一致（余额停留在旧值）。
- 删除流水后余额不回滚，越删越偏。
- 转账手续费从不扣减转出方余额，账实不符。
- 前端 `AccountView` 余额、报表期初余额推演全部受污染。

**实现建议**
1. `operateAccount` 增加 `fee` 参数（或改传 `TransactionDTO`）：
   - `INCOME`：目标账户 `+amount`。
   - `EXPENSE`：账户 `-amount`。
   - `TRANSFER`：转出方 `-(amount+fee)`，转入方 `+amount`。
2. `TransactionServiceImpl` 重写 `update(dto)`：先 `detail(id)` 取旧流水 → 对旧流水做**反向** operateAccount（冲销）→ `super.update(dto)` → 对新值做正向 operateAccount。整体 `@Transactional`。
3. `TransactionServiceImpl` 重写 `delete(id)`：先 `detail(id)` 取流水 → `super.delete(id)` → 反向 operateAccount 冲销。整体 `@Transactional`。
4. 冲销即"取反"：把原类型按相反方向回滚（原 INCOME 则减、原 EXPENSE 则加、原 TRANSFER 则转出方加回 amount+fee、转入方减 amount）。建议抽 `reverseOperateAccount(...)` 复用。

**涉及文件**
- `service/impl/TransactionServiceImpl.java`（重写 update/delete）
- `service/impl/AccountServiceImpl.java`（operateAccount 加 fee）
- `service/AccountService.java`（接口签名同步）

---

### P0-2 · 等级经验结算符号颠倒（UserLevelServiceImpl）

> **修复状态（2026-09-15）**：✅ **已修复**。① 常量已改对（`DEDUCT = -200`、`INCREASE = 500`），`monthlyLevelChange` 方向正确；② `gainExperience()` `else` 分支改为 `setDecrBy("f_experience", -experience)`（传正数），判定改为 `experience >= 0`，超支路径不再二次反转符号；③ `f_total_earned` 仅 `experience>0` 累加，口径一致。单测 `UserLevelServiceImplTest`（2 例：超支 -200 扣经验不累加 total_earned / 节省 +500 加经验且累加 total_earned）通过。

**现状证据**（[UserLevelServiceImpl.java](file:///Users/zhuxiao/Desktop/fxXml/workSpace/Bookkeeping/src/main/java/com/bookkeeping/service/impl/UserLevelServiceImpl.java)）
```java
private static final int DEDUCT = 200;     // 注释"超支时扣除200经验值"，但值为正
private static final int INCREASE = -500;  // 注释"节省时增加500经验值"，但值为负
```
- `monthlyLevelChange()`：`diff<0`（超支）→ `levelDiff = DEDUCT = +200`（**加**经验）；`diff>0`（节省）→ `levelDiff = INCREASE = -500`（**扣**经验）。方向与业务预期完全相反。
- `gainExperience()` L85-89：`experience<0` 时走 `setDecrBy("f_experience", experience)`，`experience` 为负 → 减去负数 = **加**，符号二次错误。

**影响**
- 超支反而涨经验/升级，节省反而扣经验/降级 —— 等级体系激励方向错误。
- `f_experience` 增减与 `f_total_earned`（仅 `experience>0` 时累加）口径不一致。

**实现建议**
1. 修正常量语义：`DEDUCT = -200`（超支扣）、`INCREASE = 500`（节省加），或直接在 `monthlyLevelChange` 里按 `diff` 符号赋正确正负值。
2. `gainExperience` 里经验增减统一用带符号写法：`uw.setSql("f_experience = f_experience + (" + experience + ")")`，或 `experience>=0 ? setIncrBy(experience) : setDecrBy(-experience)`。
3. 补单元测试覆盖：超支月 / 节省月 / 持平月三种结算路径。

**涉及文件**
- `service/impl/UserLevelServiceImpl.java`

---

### P0-3 · 预算信息查询 NPE（selectBudgetInfoByYearMonth）

> **修复状态（2026-09-15）**：✅ **已修复**。`categoryExpenseMap` 改为显式循环 + `matchBudgetTree` 抽出谓词，命中 null（支出分类不属于任何预算分类）时**跳过**该笔而非取 categoryId；`treeMap` 用 `Collectors.toMap(..., (a,b)->a)` 加固去重。注：`getCategoryListByIds` 经 `CategoryMapper.xml` 核实为 `WITH RECURSIVE`，返回预算分类的**全部后代**，子分类支出不漏计，「自身」由谓词 `Objects.equals(categoryId, tree.categoryId())` 覆盖；总预算（`categoryId==null`）仍用全量 `expenseAmount`，不受影响。单测 `BudgetServiceImplTest`（2 例：含未预算分类 99 跳过不 NPE / 仅总预算且分类树为空不 NPE）通过。

**现状证据**（[BudgetServiceImpl.java L91-94](file:///Users/zhuxiao/Desktop/fxXml/workSpace/Bookkeeping/src/main/java/com/bookkeeping/service/impl/BudgetServiceImpl.java#L91-L94)）
```java
Map<Integer, BigDecimal> categoryExpenseMap = StreamUtil.toMap(dtoList, entity -> {
    CategoryTree any = StreamUtil.any(treeList, tree -> Objects.equals(entity.getCategoryId(), tree.categoryId()) || tree.isChild(entity.getCategoryId()));
    return any.categoryId();   // ← any 为 null 时 NPE
}, TransactionDTO::getAmount, BigDecimal::add);
```
- `treeList` 仅由**预算分类** id 构建（`getCategoryListByIds(categoryIdList)`），而 `dtoList` 是当月**全部支出**流水（`selectListByYearMonth`）。
- `StreamUtil.any(coll, predicate)` 契约是**无匹配返回 null**（`findAny().orElse(null)`）。当某笔支出的分类既不是任何预算分类、也不是其子分类时 → `any` 为 null → `any.categoryId()` 抛 NPE，整个 `/budget` 信息查询 500。
- 运行日志实证：`data/backend.log` 多条 `NullPointerException: ... "any" is null at BudgetServiceImpl.java:93`（2026-09-15 13:14~13:45 持续复现）。
- 附带：若某月**只有总预算、无分类预算**（`categoryIdList` 为空 → `treeList` 空），则每笔支出都命中 null，必崩。

**影响**：预算页（BudgetView / DashboardView 依赖 `selectBudgetInfoByYearMonth`）在存在「未预算分类的支出」时整页 500，不可用。

**实现建议**：`StreamUtil.any` 返回 null 时应**跳过**该支出（不归属任何分类预算），而非取 categoryId。改为显式循环 + null 判定，并抽出谓词复用：
```java
Map<Integer, BigDecimal> categoryExpenseMap = new HashMap<>();
for (TransactionDTO tx : dtoList) {
    CategoryTree tree = matchBudgetTree(treeList, tx.getCategoryId());
    if (Objects.nonNull(tree)) {
        categoryExpenseMap.merge(tree.categoryId(), tx.getAmount(), BigDecimal::add);
    }
}

private CategoryTree matchBudgetTree(List<CategoryTree> treeList, Integer categoryId) {
    return StreamUtil.any(treeList, t -> Objects.equals(categoryId, t.categoryId()) || t.isChild(categoryId));
}
```
总预算（`categoryId == null`）仍用全量 `expenseAmount`（L90/L95），不受影响；需 `import java.util.HashMap;`。

**涉及文件**：`service/impl/BudgetServiceImpl.java`

---

## P1 · 核心体验缺失

### P1-1 · GAP-06 默认分类 seed 缺失

> **实现状态（2026-09-15）**：✅ 已实现。`init.sql` 新增 `CREATE UNIQUE INDEX idx_category_name`（对齐 `savePreCheck` 的全局唯一名规则）+ 幂等 `INSERT OR IGNORE` seed：8 个支出一级（餐饮/交通/购物/居住/娱乐/医疗/教育/通讯）、5 个收入一级（工资/奖金/理财/兼职/红包）及约 30 个常用二级；图标名取自前端 `CATEGORY_ICON_GROUPS`、颜色取自 `COLOR_PALETTE`且同类型一级互不重复、子分类继承父色，`f_parent_id` 用子查询按父名解析。因 `spring.sql.init.mode=always` 每次启动执行，为容纳 `ALTER TABLE ADD COLUMN`（无 IF NOT EXISTS）已开 `continue-on-error=true`。

**现状证据**：[init.sql](file:///Users/zhuxiao/Desktop/fxXml/workSpace/Bookkeeping/src/main/resources/init.sql) 有 `t_category` 建表，但**无任何 `INSERT INTO t_category`**（`t_level_config` 有 20 级 seed，分类却空）。

**影响**：新用户首次启动无任何收支分类，记账前必须手动逐个创建；快速记账/表单的分类选择器为空，体验断层。

**实现建议**
- 在 init.sql 追加常用默认分类 `INSERT OR IGNORE`（一级 + 常见二级），支出类：餐饮/交通/购物/居住/娱乐/医疗/教育/通讯；收入类：工资/奖金/理财/兼职/红包。字段含 `f_name/f_type/f_icon/f_color/f_sort_order`，父子用 `f_parent_id`。
- 颜色需符合前端"分类颜色占用检测"约定（同类型内颜色不重复）。

**涉及文件**：`src/main/resources/init.sql`

---

### P1-2 · 预算超支 / 等级变动站内信未生产（MSG-02 / MS-01）

> **实现状态（2026-09-15）**：✅ 已实现。① **等级变动**：`UserLevelServiceImpl.gainExperience` 检测到 level 变化时推 LEVEL 站内信（升/降级文案含等级名），升到 5 的整数倍额外推里程碑贺卡（见 P1-3）；② **预算超支（实时）**：`BudgetService.evaluateOverspendAlerts(year,month)` 对达到/超出预算的项推 BUDGET 站内信，`TransactionServiceImpl` 在支出 insert/update 后按交易月份调用（`@Lazy` 注入 BudgetService 打破 Transaction↔Budget 循环），按 `existsBiz(BUDGET, budgetId, 月区间)` 同预算同月去重；③ **预算超支（月末结算）**：`monthlyLevelChange` 在整月 diff<0 时推汇总 BUDGET 信（受方法开头 experienceLog 去重保护，天然每月一次）。单测 `BudgetServiceImplTest` 新增 3 例（超支推送/未达不推/同月去重）通过。

**现状证据**：全仓 `pushMessage(...)` 调用仅 2 处 —— `CheckInServiceImpl`（CHECK_IN）、`WeatherServiceImpl`（WEATHER）。
- 无 `MessageType.BUDGET` 生产者：预算超支目前仅前端 `composables/useBudgetAlert` 本地弹 ElNotification，后端不落站内信。
- 无 `MessageType.LEVEL` 生产者：`gainExperience()` L78 已检测到 `configDTO.getLevel() != detail.getLevel()`（等级变化），但未 pushMessage。

**影响**：消息中心只有签到/天气两类，预算与等级这两条核心业务通知缺失；前端 `MESSAGE_TYPE_OPTIONS` 已收录 budget/level 但永远收不到。

**实现建议**
1. **等级变动**：在 `gainExperience()` 检测到 level 变化的分支内，`pushMessage("等级变动", "恭喜升级到 Lv.X XX / 很遗憾降级到…", expLogId, MessageType.LEVEL, MessageBizType.LEVEL)`。
2. **预算超支**：在月末结算或交易/预算变更评估点，当某分类（或总预算）`amountUsed >= amount` 时 `pushMessage("预算超支提醒", …, budgetId, MessageType.BUDGET, MessageBizType.BUDGET)`。需做**去重**（同预算同月只推一次，可复用 `MessageService.exists` 思路或加唯一约束）。

**涉及文件**：`service/impl/UserLevelServiceImpl.java`、`service/impl/BudgetServiceImpl.java`（或新增结算评估点）、`service/MessageService.java`

---

### P1-3 · 贺卡消息未生产（MSG-05 / MS-02）

> **实现状态（2026-09-15）**：✅ 已实现。① `MessageService.pushMessage` 新增带 `cardImage` 的重载（5 参版委派为 null）；② 新增 `GreetingService/GreetingServiceImpl`，`dailyGreeting()` 按公历固定节日表（元旦/情人节/妇女节/劳动节/儿童节/教师节/国庆节/平安夜/圣诞节）与用户生日推贺卡，`@PostConstruct` 启动即检查 + `TaskServiceImpl` 每日 9 点调度，按 `exists(GREETING, title, 当日区间)` 同日同类去重；③ 里程碑贺卡由 `UserLevelServiceImpl` 升级时推；④ 封面采用内置 SVG 预设 `frontend/public/greeting/{birthday,festival,milestone}.svg`，`GreetingCard` 常量存根相对路径（如 `/greeting/birthday.svg`），前端 `<img :src=cardImage>` 直接可用、零改动，缺失时已有 `coverBroken` 静默降级；⑤ 生日存于 `t_user_level.f_birthday`（`ALTER ADD COLUMN` + `continue-on-error`），`LevelController` 新增 `POST /level/birthday`、`currentLevel` 返回 `birthday`，前端设置页“个性化”新增生日选择器。单测 `GreetingServiceImplTest` 4 例（yyyy-MM-dd / MM-dd 命中、同日去重、无生日不推）通过。

**现状证据**：贺卡基础设施齐全（`MessageType.GREETING`、`MessageDTO.cardImage`、`MessageEntity.cardImage`、`t_message.f_card_image`、前端 `MessageDetailDialog` 已有 greeting 渐变贺卡版式），但**无任何 GREETING 类型的 pushMessage 生产者**；且 `pushMessage` 签名不含 `cardImage` 参数。

**影响**：前端贺卡版式永远不触发，`data/images/greetingCard/` 资产闲置。

**实现建议**
1. `pushMessage` 增加重载或 `cardImage` 参数（写入 `f_card_image`）。
2. 新增贺卡生产触发点（如节日/生日/达成里程碑），推送 `MessageType.GREETING` + 封面图路径（配合 `ImageController` 的 download 端点）。

**涉及文件**：`service/impl/MessageServiceImpl.java`、`service/MessageService.java`、新增贺卡触发逻辑

---

## P2 · 运维 / 健壮性

### P2-1 · MessageType.fromValue 无容错（MSG-07）

> **实现状态（2026-09-15）**：✅ 已实现。`fromValue` 早已用 `equalsIgnoreCase` 匹配；本次将未命中时的 `throw IllegalArgumentException` 改为 `@Slf4j` `log.warn` + 降级返回 `SYSTEM`，历史脏数据/未知类型不再中断整条消息反序列化。单测 `MessageTypeTest` 4 例（精确/大小写不敏感/未知值降级/ null 降级）通过。

**现状证据**：[MessageType.java](file:///Users/zhuxiao/Desktop/fxXml/workSpace/Bookkeeping/src/main/java/com/bookkeeping/constant/MessageType.java) `fromValue` 大小写敏感（`type.type.equals(value)`），未知值直接 `throw IllegalArgumentException`。

**影响**：历史脏数据或大小写不一致（如 `Budget` vs `budget`）会导致整条消息反序列化失败，进而 `page` 查询整体报错。

**实现建议**：`fromValue` 改为 `equalsIgnoreCase` 匹配；未命中时降级返回 `SYSTEM`（而非抛异常），并记录 warn 日志。`Query.fromValue` 可同样评估容错。

**涉及文件**：`constant/MessageType.java`

---

### P2-2 · OPT-06 轻量健康检查端点缺失

> **实现状态（2026-09-15）**：✅ 已实现。新增 `HealthController`，`GET /api/health`（经 WebConfig 加 `/api` 前缀）返回 `{status, database, time}`，用注入的 `DataSource` 执行 `SELECT 1` 轻量校验 DB 连通（连通=UP，异常=DOWN，HTTP 层恒 200 供探活命中）。前端 `electron/main.cjs` 的 `HEALTH_PATH` 由业务接口 `/api/tag/selectAll` 切换为 `/api/health`。

**现状证据**：10 个 Controller（account/budget/category/check_in/image/level/message/tag/transaction + advice）中**无 `/health`**；`WebConfig` 仅配置 `/api` 路径前缀。

**影响**：Electron 主进程探活后端只能依赖业务接口，缺少轻量、无副作用的就绪探针。

**实现建议**：新增 `GET /api/health`（或 actuator），返回 `{status:"UP", time:…}`，可选校验 DB 连通（`SELECT 1`）。主进程启动轮询此端点判定后端就绪。

**涉及文件**：新增 `controller/HealthController.java`

---

### P2-3 · GAP-08 批量操作缺失

> **实现状态（2026-09-15）**：✅ 已实现。`TransactionController` 新增 `POST /transaction/batchDelete`（ids）与 `POST /transaction/batchUpdateCategory`（ids + categoryId），均 `@RequestParam` 逗号拼接绑定 `List<Integer>`（同 `message/readAll`）。`batchDelete` 逐条复用单条 `delete`（内含 P0-1 的余额冲销）、`@Transactional` 原子；`batchUpdateCategory` 用单条 `UPDATE ... IN` 批量置 `f_category_id`（金额/类型/账户不变无余额影响），并对受影响支出流水所在月份重估预算超支（分类归属影响预算命中）。前端 `transactionApi` 同步补 `batchDelete/batchUpdateCategory`。单测 `TransactionServiceImplTest` 4 例（批删委派/空入参/批改分类重估超支/空入参）通过。

**现状证据**：仅框架 6 个单条端点（save/update/delete?id= 等），无批量端点。

**影响**：前端流水/账户多选批量删除、批量改分类只能逐条串行调用，慢且非原子。

**实现建议**：新增 `POST /transaction/batchDelete`（ids）、`POST /transaction/batchUpdateCategory`（ids + categoryId）等，服务端 `@Transactional` 批量处理；批量删除/改分类需同步走 P0-1 的余额联动。

**涉及文件**：`controller/TransactionController.java`、`service/impl/TransactionServiceImpl.java`

---

## P3 · 增强

### P3-1 · GAP-09 备份 / 恢复 / 导入导出缺失

> **实现状态（2026-09-15）**：✅ 已实现。新增 `BackupController`（`/api/backup/**`）+ `BackupService/BackupServiceImpl` + 无依赖的 `util/CsvUtil`。
> ① **整库备份**：`GET /backup/export` 用 SQLite `VACUUM INTO` 生成一致性单文件 .db 快照，以 attachment 下载；
> ② **恢复**：`POST /backup/import` 上传 .db，校验 SQLite 文件头后落暂存文件→备份现库为 `.bak`→`Files.move` 原子覆盖 `accounts.db` 并清理旧 sidecar（-journal/-wal/-shm）；因活动连接指向旧 inode，返回 `restartRequired=true`，由 Electron `ipcMain.handle('restart-backend')`（kill→重启子进程→健康探活）重新加载，前端恢复成功后自动重启并 reload；
> ③ **CSV 导出**：`GET /backup/csv/transactions`（日期/类型/金额/手续费/账户/转入账户/分类/备注/标签，账户与分类以名称呈现）与 `GET /backup/csv/accounts`，含 BOM、CRLF，转义规则与前端 `csv.ts` 对齐；
> ④ **CSV 导入**：`POST /backup/csv/transactions` 按账户名/分类名解析为 id，**仅新增**（走 `transactionService.insert` 余额联动），逐行容错——日期/类型/金额非法或账户名无法解析的行跳过，返回 `{total, imported, skipped}`；
> ⑤ 前端 `backupApi`（附件下载走裸 axios+blob 避开 JSON 拦截器，上传走 FormData）+ 设置页“数据管理”面板接入（备份/恢复二次确认 + CSV 导入导出 + 导入后广播 TRANSACTION_CHANGED/ACCOUNT_CHANGED）。
> 单测 `CsvUtilTest`（5 例：引号/转义/字段内换行/BOM/round-trip）+ `BackupServiceImplTest`（6 例：按名解析新增/仅日期格式/账户不可解析跳过/金额非法跳过/导出含 BOM 表头名称/空数据仅表头）通过（快照/恢复涉真实文件与 DataSource，属集成层，未入单测）。

**现状证据**：无数据库备份、恢复、CSV/JSON 导入导出端点（前端 `utils/csv.ts` 仅本地导出 CSV）。

**影响**：单机 SQLite 数据无官方备份/迁移通道，换机或损坏后无法恢复。

**实现建议**：
- 备份：`GET /backup/export` 打包 `accounts.db`（或 SQL dump）下载；`POST /backup/import` 上传恢复（需停机/事务保护）。
- 数据导入导出：流水/账户 CSV 导入导出端点，字段与前端 `csv.ts` 对齐。

**涉及文件**：新增 `controller/BackupController.java` + service

---

## 附：核实方法

- 逐项 grep + 源码阅读核实，未采信旧文档（`frontend-requirements.md` 第 8 章 GAP 清单、`desktop-roadmap.md` 第 5 章部分条目已过时）。
- self-framework 源码位置：`/Users/zhuxiao/Desktop/fxXml/workSpace/self-framework`。
- 建议实现顺序：~~P0-1 → P0-2 → P0-3~~ → ~~P1-1 → P1-2 → P1-3~~ → ~~P2-1 → P2-2 → P2-3~~ → ~~P3-1~~（**全部清单已于 2026-09-15 实现完成，含单测：共 30 个单测全绿**）。
