# Codex 会话列表按项目分组 显示

> **For Hermes:** 可用 subagent-driven-development 按任务执行；也可直接实现（改动面小）。

**Goal:** Codex 会话列表像 Codex 桌面端一样按项目分组显示：项目名做节标题、组内按最后活跃倒序、组可折叠、搜索时保持平铺。

**Architecture:** 服务端在会话行上算好 `group_name`（cwd 匹配 app-server 项目 roots → 项目名；未命中 → cwd 叶子目录名），App 端做纯函数分组 + 分组渲染。列表分页/排序/懒加载机制全部不动。

**Tech Stack:** ASP.NET 8（`CodexAgent.cs` / `CodexProjects.cs`）+ Kotlin Compose（`HermesScreen.kt` / `HermesModel.kt`）。

---

## 调研结论（2026-10-15 实测）

1. **不能用 project_id 分组**：`/api/codex/sessions` 50 行里只有 2 行有 `project_id`（手机新建的会话才有）。
2. **cwd 100% 存在**：50/50。示例 `D:\工作目录\QIC NEW\Code\QIC.Sport.Member\...`。桌面端实际就是按工作目录/项目根聚合。
3. **App 已有项目数据源**：`GET /api/codex/projects`（转发 app-server `projects`），App 端 `HermesModel.fetchProjects()` + `model.projects`（含 id/name/roots）已存在，新建会话对话框在用。
4. **服务端已有匹配基建**：`CodexProjects.RootSignature`（路径归一化签名）、`CodexProjects.List(rpc)`（project/list 调用）。
5. **列表行已带 `last_active`（消息时间倒序已修）**；上下文进度条与本次改动正交。
6. App 会话卡片为 codex/hermes 共用（`HermesScreen.kt:389-505`）；`orderedSessions` = pinned 优先平铺（`HermesModel.kt:182`）。

**方案选择：服务端算组名（方案 A）**。理由：匹配逻辑（大小写不敏感路径前缀）一处实现，web 端将来要用零成本；App 已有 projects 数据但那样匹配逻辑要在 Kotlin 重写一遍，且 `CodexProjects.List(rpc)` 在服务端是现成的。服务端每列表请求多一次 `project/list` rpc（毫秒级，加 TTL 缓存后趋零）。

## 设计细节

- **组名规则**（服务端，`group_name` 字段）：
  1. cwd 精确等于某 project root 或位于其下（`Path.GetFullPath` + OrdinalIgnoreCase 前缀 + 目录边界）→ 该项目 `name`（多项目命中取 roots 最长者）
  2. 未命中 → cwd 最后一个非空路径段（如 `GIT`、`QIC.Sport.Member.Site`）
  3. cwd 为空 → `"未命名会话"`（现状 0 行，防御性）
- **分组顺序**：置顶会话单独一节最上（沿用 pinned-first 语义）；其余组按组内最新 `last_active` 倒序；组内 `last_active` 倒序（服务端现有排序产物直接分组即可，组间序在 App 算）。
- **折叠**：每节可折叠，状态存 SharedPreferences（key `codex_group_collapsed:<group_name>`），默认展开。
- **搜索**：`searchingSessions == true` 时平铺（跨项目搜索结果，分节无意义）。
- **UI 形态**：节标题行 = 项目名 + 会话数 + 折叠箭头，样式沿用现有 labelSmall/onSurfaceVariant；卡片本体（含上下文进度条）不动。
- **分页**：每页内分组（现有 50/页 + moreSessions 不变）。跨页同组会在后续页再现同名节——桌面端同样按页聚合，接受；节折叠状态按组名共享所以跨页折叠体验一致。

## 任务分解

### Task 1: 服务端 group_name
**Files:** `WebApplication/Services/Codex/CodexAgent.cs`（list handler ~L1100-1128 + `Session()` L570-581）、`WebApplication/Services/Codex/CodexProjects.cs`

- `CodexProjects` 加静态 TTL 缓存（5 分钟）包住 `List(rpc)`，失败返回空数组（退化路径 2）
- list handler 在构造行前取一次 projects；把 projects 传给 `Session(thread, projects)`（重载，旧 `Session(thread)` 委托新方法传 null）
- 新增纯静态匹配函数（放 `CodexProjects`）：
  ```csharp
  public static string GroupName(string cwd, JsonArray? projects) {
      // 归一化：GetFullPath、TrimEndDirectorySeparator、OrdinalIgnoreCase
      // 1) projects 中 roots 最长前缀命中（root==cwd 或 cwd.StartsWith(root+Path.DirectorySeparatorChar)）→ project name
      // 2) 否则 cwd 叶子段；cwd 空返回 null（调用方跳过）
  }
  ```
- `Session()` 行上加：`result["group_name"] = groupName;`（命中项目时同时加 `project_name`）

**验证:** `dotnet build` 0 error；部署后 `GET /api/codex/sessions` 抽查：`ASC.Sport.PlatformManage` 组 12 行同名、`E:\GIT` → `GIT`、project 命中的行有 `project_name`。

### Task 2: App 分组纯函数 + 单测
**Files:** `app/mobile/src/main/java/com/chuckiehelper/mobile/nativeui/HermesModel.kt`（或新文件 `SessionGroups.kt`）、`app/mobile/src/test/java/...`（现有测试目录）

- 纯函数：
  ```kotlin
  data class SessionGroup(val name: String, val sessions: List<JSONObject>, val pinned: Boolean = false)
  fun groupCodexSessions(rows: List<JSONObject>): List<SessionGroup>
  // pinned 组（isSessionPinned 逻辑同 orderedSessions）→ 其余按 group_name 聚合，
  // 组间按组内最大 last_active 倒序（parseMessageTimestamp 复用），组内已有序
  ```
- 单测：平铺输入→期望分组（组序、置顶节、group_name 缺失行归"其他"、空列表）。
- 运行：`gradlew.bat :mobile:testReleaseUnitTest`（在 `app/`，rtk proxy cmd），期望 0 failed。

### Task 3: App 分组渲染
**Files:** `app/mobile/src/main/java/com/chuckiehelper/mobile/nativeui/HermesScreen.kt`（L389-505 列表区）

- `agent == "codex" && !searchingSessions` 时用 `groupCodexSessions(orderedSessions)` 渲染：
  ```kotlin
  grouped.forEach { group ->
      item(key = "group:" + group.name) { SectionHeader(...) }
      if (!collapsed(group.name)) items(group.sessions, key = { it.getString("id") }) { SessionCard }
  }
  ```
- 折叠状态：`HermesModel` 加 `groupCollapsed: MutableSet<String>`（prefs 持久化）+ `toggleGroup(name)`；注意 LazyColumn key 唯一性（组 key 前缀区分）。
- hermes agent / 搜索模式：走现有平铺分支，零改动。
- **验证:** 编译 0 error；真机：默认展开、点标题折叠/展开、重启 App 折叠状态保留、搜索仍平铺。

### Task 4: 部署 + 发版
- 服务端部署（`remotetool-deploy.ps1`，UAC 单弹窗）
- APK versionCode 47 / 0.3.44，`assembleRelease`，交付 outbox + commit/push（socks5h 代理）

## Files 汇总
- `WebApplication/Services/Codex/CodexAgent.cs`（行加 group_name）
- `WebApplication/Services/Codex/CodexProjects.cs`（TTL 缓存 + GroupName 匹配）
- `app/mobile/.../HermesModel.kt`（groupCodexSessions / 折叠状态）
- `app/mobile/.../HermesScreen.kt`（分组渲染）
- `app/mobile/build.gradle`（45→47 bump 时同步）

## 风险 / 取舍
- **跨页分组**：组名节可能在相邻页重复出现——按页聚合，折叠状态按组名共享，视觉上可接受；若实际体验差，后续可一次拉 100/页（改 limit，代价是列表变慢，暂不做）。
- **E:\GIT 叶子组**：8 个会话聚成一节叫 `GIT`——与桌面端目录聚合一致，可接受；若用户想显示完整路径，改 Task 1 的兜底段为倒数两级（一行改动）。
- **project/list 失败**：rpc 未就绪时退化为纯 cwd 叶子分组（不阻塞列表）。
- **web 端未做**：agent-workspace 的 codex 列表本次不动（服务端字段已就绪，后续想加是纯前端工作）。
