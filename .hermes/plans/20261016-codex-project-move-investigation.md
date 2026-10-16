# Codex 会话项目归属/存档：官方机制调查与可靠实施方案

> 2026-10-16 调查（基于本机 codex v0.162.1 实测 + openai/codex 开源码 codex-rs 对照）。
> 结论：**用官方接口即可双端（App + 官方桌面端）生效**，无需自建存储；之前的"会话消失"根因是列表语义误解，不是数据丢失。

## 一、官方机制（源码 + 实测确认）

### 数据模型
- state db（`~/.codex/state_5.sqlite`）`threads` 表有正式列 `project_id`、`archived`、`archived_at`。
- 项目定义在 `projects` / `project_roots` 表（与 `project/list` RPC 同源）。
- 官方桌面端与任何 app-server 客户端读写**同一个 state db**——这是双端天然互通的根基。

### 关键 RPC
| 方法 | 作用 | 实测 |
|---|---|---|
| `thread/metadata/update` | 改归属/元数据。字段仅三个：`projectId`（"" = 清空归属）、`gitInfo{sha,branch,originUrl}`、`daybreakEnabled`。**不支持 cwd** | 归属/清空均真实落库 ✓ |
| `thread/archive` / `thread/unarchive` | 存档/恢复（含子线程级联） | 往返 ✓ |
| `thread/list` | 分页查询，参数见下 | 见下 |
| `thread/delete` | 删除 | ✓ |

### thread/list 的官方语义（桌面端就是这些查询的组合）
- **默认列表只返回未归属项目的会话**（project_id IS NULL）——移动到项目后从默认列表"消失"是官方设计，不是丢数据。
- `{"projectId": "<id>"}` 返回归属该项目的会话（Option<Option<String>>，可过滤）。
- `{"archived": true}` 返回存档会话。
- 其他参数：`cursor/limit/sortKey(createdAt|updatedAt|recencyAt|sectionPosition)/sortDirection/sourceKinds/modelProviders/excludedThreadIds/cwd/searchTerm/parentThreadId/ancestorThreadId/useStateDbOnly/sectionId`。
- `project/move` 与本需求无关（实验性且对本场景无效果）；桌面端实际用的是 `thread/metadata/update`。

### 时序特性（需要防御的点）
1. **刚创建、还没有第一轮回复的会话不在 thread/list**（rollout 未落地、未入 state db）——官方注释明示 "Fresh threads have no rollout until their first turn"。
2. **同一 app-server 实例内**，metadata/update 走 `pending_thread_metadata` 暂存 + 异步物化：清空归属后，同实例的项目过滤可能短暂返回旧归属（实测复现）；跨实例/新实例读 state db 立即正确（实测 ✓）。
3. list 行的 `projectId` 是创建时写入的索引值；metadata 变更后以 state db / thread/read 为准。

## 二、可靠实施方案（待批准后实施，预计一次改动）

### 服务端
1. 列表合并：默认查询（无 projectId）+ 对 `project/list` 里每个项目各查一次 `{"projectId": id}` + `{"archived": true}` 查已存档 → 按 id 去重合并（项目数 ~4，全部本地 sqlite 查询，毫秒级）。
2. 分组（group_name）：行内 `projectId` 能匹配到项目 → 项目名（**权威，优先**）；未归属 → 现有 cwd 前缀匹配；都不中 → cwd 叶子名。
3. 端点（CodexController 显式路由 + CodexAgent 实现）：
   - `POST sessions/{id}/move {projectId}` → `thread/metadata/update`（空串移出）；响应带合并后的会话行。
   - `POST sessions/{id}/archive {archived}` → `thread/archive`/`thread/unarchive`。
   - CodexRpc initialize 已带 `capabilities.experimentalApi: true`（现成）。
4. 不需要任何自建持久化（之前的 session-projects/archive json 方案作废）；新建会话暂不在列表的问题用现有回填思路即可（新会话 id 暂存直到出现在 list，或直接在响应里已带行）。

### App
- 菜单"移动到项目"：项目列表对话框（`model.projects` 现成），选中 → POST move → 用响应行替换本地行。
- "存档会话/取消存档"：POST archive；已存档会话沉底"已存档"节（默认折叠）、卡片淡化。
- 顶部进度条、分组渲染均不受影响。

### 桌面端兼容性
- 归属/存档写在官方 state db，桌面端项目视图/存档视图原生读取——App 里移动后桌面端立即可见，反之亦然（跨实例写后读实测 ✓）。

### 已验证清单（本次调查实测）
- [x] 归属 → 默认列表不含、项目过滤含（真实会话）
- [x] 清空（""）→ state db 立即 NULL（sqlite 直查）；跨实例读正确
- [x] archive/unarchive 往返；archived 过滤参数有效
- [x] 临时/误操作数据已全部还原清理
