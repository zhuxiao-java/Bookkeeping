# 项目文档索引

本目录汇总「记账本（Bookkeeping）」桌面端应用的全部设计与工程文档。文档按「需求 → 设计 → 实现 → 打包 → 待办」的顺序组织，可按下表按需查阅。

## 文档清单

| 文档 | 定位 | 适合读者 |
| ---- | ---- | ---- |
| [overview.md](./overview.md) | **总体需求与架构**：项目概述、目标用户、功能/非功能需求、里程碑、技术选型总纲 | 所有人（先读） |
| [frontend-requirements.md](./frontend-requirements.md) | **前端需求与接口契约**：页面/交互规范、各功能模块 FR 编号需求、后端接口契约与差距（GAP）清单 | 前端、UI、后端对接、测试 |
| [desktop-roadmap.md](./desktop-roadmap.md) | **路线图与待办清单（Backlog）**：已落地能力快照、未开发功能、可优化项、后端差距最新状态、建议排期 | 产品、研发、发布 |
| [backend-todo.md](./backend-todo.md) | **后端实现记录**：按 P0~P3 优先级记录的后端功能核实与实现证据（截至 2026-09-15 全部清单已实现，含单测） | 后端 |
| [desktop-packaging.md](./desktop-packaging.md) | **打包与分发**：jpackage 原生后端 + electron-builder 安装包的完整打包流程、目录约定、验证清单、常见问题 | 发布、运维 |

## 文档关系

```
overview.md（总体需求与架构）
   └─▶ frontend-requirements.md（前端需求与接口契约）
          └─▶ desktop-roadmap.md（路线图 / Backlog）
                 ├─▶ backend-todo.md（后端实现记录，提供差距修复证据）
                 └─▶ desktop-packaging.md（打包分发，落地签名/公证等优化项）
```

## 阅读建议

- **初次了解项目**：先读 [overview.md](./overview.md)，再看仓库根目录的 [README](../README.md)。
- **参与前端开发**：以 [frontend-requirements.md](./frontend-requirements.md) 为接口与需求权威来源。
- **查看当前进度 / 排期**：看 [desktop-roadmap.md](./desktop-roadmap.md) 第 2 章（现状快照）与第 6 章（建议排期）。
- **打包发布**：按 [desktop-packaging.md](./desktop-packaging.md) 操作。

> 说明：`desktop-roadmap.md` 与 `backend-todo.md` 会随功能推进持续更新，完成项会从「待开发」移入「现状快照」，请以文档内标注的核实日期为准。
