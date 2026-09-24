# 记账本 · Bookkeeping

> 一款跨平台的**离线桌面记账应用**：Electron + Vue 3 前端，Java（Spring Boot + SQLite）本地后端，数据全部存储在本机，无需联网、无需账号。

![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Windows%20%7C%20Linux-lightgrey.svg)
![Frontend](https://img.shields.io/badge/frontend-Vue%203%20%2B%20Electron-brightgreen.svg)
![Backend](https://img.shields.io/badge/backend-Spring%20Boot%20%2B%20SQLite-orange.svg)

---

## 项目简介

「记账本」是一款面向**个人 / 家庭 / 小型工作室**的桌面财务管理系统。它采用「**Electron 壳 + 内嵌原生 Java 后端**」的架构：应用启动时由 Electron 主进程拉起一个由 `jpackage` 打包的 Java 子进程，前后端通过本机 `127.0.0.1:8080` 的 REST API 通信，所有数据落在本地单文件 SQLite 数据库中。

- **完全离线**：无需服务端部署，无需注册登录，数据只存在你自己的电脑上。
- **开箱即用**：安装包内嵌后端运行时，双击即用，退出时自动回收后端进程。
- **功能完整**：账户、流水、分类、标签、预算、报表、等级激励、站内信、数据备份/恢复一应俱全。

---

## ✨ 功能特性

### 记账核心
- **账户管理**：现金 / 银行卡 / 支付宝 / 微信等账户，支持初始余额、当前余额自动计算、归档与排序。
- **收支流水**：收入 / 支出 / 转账（含手续费）记录，支持编辑、删除、复制、多条件筛选与分页。
- **交易 ↔ 余额联动**：增删改采用「冲销 - 重放」模型，转账含手续费，另提供对账重算端点，保证账实一致。
- **多级分类与标签**：支持父子（多级）分类、图标与颜色，内置常用默认分类；标签可自由创建。
- **快速记账**：全局弹窗，应用内 `Cmd/Ctrl+N`、系统全局 `Cmd/Ctrl+Shift+B` 一键唤起，支持常用模板。

### 统计与预算
- **统计报表**：收支趋势（折线/柱状）、分类占比（饼/环形）、账户余额变化图，支持环比/同比区间对比，可导出 CSV / PDF。
- **预算管理**：月度总预算与分类预算，使用进度条、超支提醒（应用内通知 + 站内信）、近 6 个月执行历史对比。

### 激励与通知
- **等级体系**：按月度预算执行情况增减经验、升降级，含经验流水与等级配置。
- **签到日历**：每日签到与日历展示。
- **站内信中心**：铃铛未读角标、列表、详情弹窗、业务跳转；消息类型覆盖签到、天气、预算超支、等级变动、节日/生日/里程碑贺卡。

### 桌面端体验
- **系统集成**：托盘菜单（显示 / 快速记账 / 退出）、关窗隐藏到托盘、窗口位置记忆、冷启动闪屏过渡。
- **个性化**：深浅色主题、金额小数位、背景图（预设 + 自定义上传 + 遮罩）、息屏屏保。
- **数据管理**：整库备份（SQLite `VACUUM INTO`）/ 恢复（覆盖后自动重启后端）、流水与账户 CSV 导出、流水 CSV 导入。
- **健康探针**：轻量 `GET /api/health`（`SELECT 1` 校验数据库连通），主进程据此判定后端就绪。

> 完整需求与已落地能力清单见 [docs/desktop-roadmap.md](docs/desktop-roadmap.md) 第 2 章「现状快照」。

---

## 🏗️ 技术架构

```
┌──────────────────────────── Electron 应用 ────────────────────────────┐
│  主进程（Node.js）                                                     │
│   ├─ 窗口 / 托盘 / 全局快捷键管理                                       │
│   ├─ Java 后端子进程管理（拉起 jpackage 产物、健康检查、异常重启、退出回收）│
│   └─ 本地用户偏好持久化（窗口位置、主题等）                              │
│                                                                       │
│  渲染进程（Vue 3 应用）                                                │
│   └─ Axios ──HTTP(127.0.0.1:8080/api)──▶ Java 后端 ──▶ SQLite(./data) │
└───────────────────────────────────────────────────────────────────────┘
```

| 层次 | 技术 | 说明 |
| ---- | ---- | ---- |
| 桌面壳 | **Electron 30** | 窗口、托盘、快捷键、Java 子进程生命周期管理 |
| 前端框架 | **Vue 3 + TypeScript + Vite 5** | 组合式 API，类型安全 |
| UI 组件库 | **Element Plus** | 桌面风格组件 |
| 图表 | **ECharts 5** | 按需引入，趋势图 / 饼图 / 余额变化图 |
| 状态管理 | **Pinia** | 字典缓存、用户偏好、消息、等级、签到 |
| 路由 | **Vue Router 4** | Hash 路由（适配 `file://` 生产加载） |
| 后端 | **Java 17 + Spring Boot** | 业务逻辑、数据访问、报表计算、定时任务 |
| ORM | **MyBatis-Plus**（经自研 `self-framework` 封装） | 统一 CRUD、分页、条件构造 |
| 对象映射 | **MapStruct + Lombok** | DTO ↔ Entity 映射 |
| 数据库 | **SQLite**（`sqlite-jdbc`） | 本地单文件数据库，无需独立安装 |
| 打包 | **electron-builder + jpackage + jlink** | 生成各平台安装包，内嵌精简 JRE |

> ⚠️ **依赖说明**：后端基于自研框架 [`org.sf:self-framework`](https://github.com/zhuxiao-java/self-framework)（BOM `import` 方式引入），构建前需先将其安装到本地 Maven 仓库，否则依赖解析会失败。

---

## 📁 项目结构

```
Bookkeeping/
├── src/main/java/com/bookkeeping/   # Java 后端
│   ├── config/                      # Web 配置（/api 路径前缀等）
│   ├── constant/                    # 枚举与常量（账户类型、币种、消息类型等）
│   ├── controller/                  # REST 控制器（账户/流水/分类/预算/标签/等级/消息/备份/健康）
│   ├── dao/                         # dto / entity / mapper / mapping（MapStruct）
│   ├── service/                     # 业务接口与 impl 实现
│   ├── exception/                   # 全局异常处理
│   └── BookkeepingApplication.java  # Spring Boot 启动类
├── src/main/resources/
│   ├── application.properties       # 数据源、日志、SQL 初始化配置
│   ├── init.sql                     # 建表 + 默认分类 / 等级 seed（幂等）
│   └── mapper/                      # MyBatis XML
├── src/test/java/                   # 后端单元测试（JUnit 5）
├── frontend/                        # Electron + Vue 前端
│   ├── electron/                    # 主进程 main.cjs / preload.cjs / 闪屏
│   ├── src/                         # api / components / views / stores / utils ...
│   ├── package.json
│   └── electron-builder.yml
├── docs/                            # 项目文档（见下方文档导航）
├── .github/workflows/               # CI：Windows 安装包自动构建
├── pom.xml
└── README.md
```

---

## 🚀 快速开始

### 环境要求

| 工具 | 版本 | 说明 |
| ---- | ---- | ---- |
| JDK | **17** | 后端编译与 `jpackage` 打包（`jpackage` 要求 JDK ≥ 14） |
| Maven | ≥ 3.6 | 后端构建 |
| Node.js | ≥ 18 | 前端构建（推荐 22） |
| self-framework | 1.0-SNAPSHOT | 需先安装到本地 Maven 仓库 |

### 1. 安装自研框架依赖

```bash
# 获取并安装 self-framework 到本地仓库（仅需一次）
git clone https://github.com/zhuxiao-java/self-framework.git
cd self-framework && mvn -B install -DskipTests
```

### 2. 启动后端

```bash
# 在项目根目录
mvn clean compile
# 方式一：在 IDE 中直接运行主类 com.bookkeeping.BookkeepingApplication
# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/bookkeeping-1.0-SNAPSHOT.jar
```

后端默认监听 `http://127.0.0.1:8080`，数据库与日志位于运行目录下 `./data/`（`accounts.db`、`backend.log`）。

### 3. 启动前端

```bash
cd frontend
npm install          # 网络受限时可先 export ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/

# 浏览器开发模式（Vite Dev Server，/api 自动代理到 8080）
npm run dev

# Electron 桌面开发模式（同时拉起 Vite 与 Electron）
npm run dev:electron
```

> 开发模式下前端通过 Vite proxy 将 `/api` 代理到 `http://127.0.0.1:8080`（后端未配置 CORS）。生产模式由 Electron 主进程自动拉起内嵌后端。

### 运行测试

```bash
mvn test                 # 后端单元测试（JUnit 5）
cd frontend && npm test  # 前端单元测试（Vitest）
```

---

## 📦 打包发布

安装包 = **Electron 壳 + Vue 静态资源 + jpackage 原生后端**（内嵌精简 JRE，作为 extraResources 打入）。

```bash
# 后端：jar → jlink 精简运行时 → jpackage 原生 app-image，产物放入 frontend/resources/java/
# 前端：vite build + electron-builder
cd frontend && npm run dist
```

产物输出于 `frontend/release/`：macOS 为 `.dmg` + `.zip`，Windows 为 NSIS 安装包，Linux 为 AppImage。由于后端为原生可执行文件**无法交叉编译**，各平台需在对应系统上打包。

- 完整步骤、目录约定、验证清单与常见问题见 **[docs/desktop-packaging.md](docs/desktop-packaging.md)**。
- 仓库已配置 GitHub Actions（[.github/workflows/build-windows-exe.yml](.github/workflows/build-windows-exe.yml)）：推送 `main` 或手动触发即自动构建 Windows x64 安装包并上传为 Actions 产物；推送 `v*` 标签（如 `v1.0.0`）会在构建完成后自动发布 GitHub Release 并附带安装包，可直接分发下载。

---

## 🗄️ 数据模型

数据库为本地 SQLite（`./data/accounts.db`），建表与种子数据见 [src/main/resources/init.sql](src/main/resources/init.sql)：

| 表 | 说明 |
| ---- | ---- |
| `t_account` | 账户 |
| `t_category` | 收支分类（多级） |
| `t_transaction` | 交易流水 |
| `t_budget` | 预算 |
| `t_tag` | 标签 |
| `t_user_level` | 用户等级 / 经验 / 生日 |
| `t_experience_log` | 经验变动流水 |
| `t_level_config` | 等级配置（20 级 seed） |
| `t_check_in` | 签到记录 |
| `t_exp_transaction` | 经验交易 |
| `t_message` | 站内信（含贺卡封面） |

---

## 📚 文档导航

全部文档位于 [docs/](docs/) 目录，索引见 [docs/README.md](docs/README.md)。

| 文档 | 内容 |
| ---- | ---- |
| [docs/overview.md](docs/overview.md) | 总体需求与架构（项目概述、功能/非功能需求、里程碑、技术选型总纲） |
| [docs/frontend-requirements.md](docs/frontend-requirements.md) | 前端需求与后端接口契约（页面/交互规范、FR 需求、GAP 差距清单） |
| [docs/desktop-roadmap.md](docs/desktop-roadmap.md) | 路线图与待办清单（现状快照、未开发功能、可优化项、建议排期） |
| [docs/backend-todo.md](docs/backend-todo.md) | 后端实现记录（按优先级核实与实现证据，含单测） |
| [docs/desktop-packaging.md](docs/desktop-packaging.md) | 打包与分发完整流程 |

---

## 🗺️ 路线图

核心的记账、预算、报表、等级、站内信、数据备份/恢复等能力均已落地。后续演进方向（详见 [docs/desktop-roadmap.md](docs/desktop-roadmap.md)）：

- **正确性与数据安全**：三级分类录入-筛选打通、统计时间边界统一、备份恢复完整性校验与回滚。
- **效率与体验**：流水批量管理 UI、账户对账入口、报表首屏改用聚合接口、CSV 导入向导。
- **工程与分发**：代码签名 + 公证、自动更新通道、多语言 i18n、数据加密、CI 三平台打包矩阵。

---

## 📄 License

本项目基于 [Apache License 2.0](LICENSE) 开源。
