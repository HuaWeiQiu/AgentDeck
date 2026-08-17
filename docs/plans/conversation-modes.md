# 会话模式：轻聊 / 开发（Secure）

- 状态：**规划已定 · P0+P1 已落地**（顶栏一点切换模式、策略、列表严格过滤、设置按模式显隐、空状态、模型页文案；P2 未做）
- 日期：2026-08-18
- 相关：`ADR-0008`（标准/高级/开发者 **体验层级**）、`ADR-0004`（CLI / 卡片）、`ADR-0007`（模型服务）、`agent-startup-acceleration.md`

## 1. 问题

当前 Secure 把三类完全不同的能力挤在同一套「会话 + 设置」心智里：

| 能力 | 依赖 | 用户任务 |
| --- | --- | --- |
| 纯聊天 / 角色扮演 | 仅网关 API Key | 快聊、人设、无工具 |
| 本地 Agent 开发 | PRoot + Codex/pi/dsh | 写代码、审批、扩展 |
| 高风险宿主实验 | Lab flavor + 额外权限 | 屏幕/Intent 等 |

若只在「新建会话」里塞「轻聊/开发」芯片，会出现：

1. **设置仍堆满 Runtime / 扩展 / Codex 权限**，轻聊用户永远被装环境打扰。  
2. **空状态清单强制「准备聊天环境」**，与轻聊无关。  
3. **模式与会话 recipe 双轨**，用户不知道全局默认是什么。  
4. 与 **ADR-0008 体验层级**（标准/高级/开发者）混淆——那是「信息密度」，不是「产品业务模式」。

## 2. 决策（产品）

### 2.1 两个正交轴

| 轴 | 存储 | 控制什么 | 不控制什么 |
| --- | --- | --- | --- |
| **会话模式 `ConversationMode`** | `agentdeck_experience.conversation_mode` | **能创建/主推什么会话、设置里出现什么业务入口、空状态步骤** | Keystore、审批真实性、路径校验 |
| **体验层级 `ExperienceLevel`** | 既有 `level` | 高级字段、技术细节、Lab 开关可见性 | 是否允许轻聊会话存在 |

```text
ConversationMode.LIGHT | DEV     ← 业务模式（本文件）
ExperienceLevel.STANDARD|ADVANCED|DEVELOPER  ← 信息与危险控件密度（ADR-0008）
BuildConfig.HOST_LAB             ← 狂暴/Lab 包体，不是 Secure 内第三种会话模式
```

**Secure 内只有两种会话模式：轻聊、开发。**  
「狂暴」= Lab APK，不进 Secure 的 `ConversationMode` 枚举。

### 2.2 模式语义（用户语言）

#### 轻聊 `LIGHT`（默认）

- **一句话**：不装本地 Agent，连上模型就能聊，可以写角色。  
- **运行时**：禁止启动 Codex / pi / dsh / PRoot（本模式路径下）。  
- **模型**：仅 **Chat Completions** 类服务（dots 等）。  
- **会话 recipe**：仅 `recipe_light`（新建时）。  
- **角色**：一等公民（system prompt 注入轻聊客户端）。  
- **工具 / 扩展 / Codex 权限档 / 工作区 / 宿主工具**：不出现。  
- **历史**：App 侧气泡落盘即可；无 app-server thread。

#### 开发 `DEV`

- **一句话**：本机 Agent 干活——读改代码、审批、扩展。  
- **运行时**：Codex 为首要；pi / dsh 按需。  
- **模型**：Codex → Responses；pi → Chat Completions；dsh → 网页内配置。  
- **会话 recipe**：`recipe_codex` / `recipe_pi` / `recipe_deepseek_harness`（新建时）。  
- **角色**：仍可写（Codex 既有 identity）。  
- **扩展 / 权限 / 工作区 / 高级**：按 `ExperienceLevel` 逐步打开。  
- **历史**：Codex rollout / pi 历史 / dsh 网页会话。

### 2.3 模式放哪里

| 位置 | 行为 |
| --- | --- |
| **对话列表顶栏当前模式名** | 点击弹出可选模式列表（`ConversationMode.entries`，可扩展第 3 种） |
| 设置 | **不提供**独立模式设置；入口仍按当前模式显隐 |
| 新建会话对话框 | 无模式芯片；引擎列表 = 当前模式过滤结果 |
| 模型服务试聊气泡 | 保留为 **gateway smoke**，不创建会话卡片，与模式无关 |

### 2.4 切换模式时的规则

1. **不删除**另一模式已创建的会话；列表默认 **只显示当前模式会话**（见 §3.1）。  
2. **列表严格按模式隔离**：轻聊只显示轻聊会话，开发只显示开发会话；另一模式会话不出现在列表（数据仍保留，切回对应模式可见）。  
3. 从轻聊 → 开发：不自动装 Runtime；进入开发会话或点运行环境时再引导。  
4. 从开发 → 轻聊：**不杀**已 hold 的 agent（内存策略不变）；只是 UI 不再显示开发会话。  
5. 切换 **不**改 `ExperienceLevel`、不改 API Key、不改备份。

### 2.5 与 Setup / canStartChat

| 模式 | 「能否新建会话」 | Setup 横幅 / 空状态 |
| --- | --- | --- |
| 轻聊 | 有至少一个可用的 Chat Completions 服务即可 | 步骤：连接模型 → 新建；**无**「准备运行环境」 |
| 开发 | Codex runtime 就绪（既有 `canStartChat`）+ 认证/模型策略 | 步骤：运行环境 → 模型 → 新建（与现网一致） |

`SetupCoordinator` 仍服务 **开发** 路径；轻聊 **不**把 `!canStartChat` 当成全局阻断。

## 3. 界面显隐矩阵（必须实现）

图例：● 显示　○ 隐藏　◐ 仅高级/条件

### 3.1 对话 Tab

| 表面 | 轻聊 | 开发 |
| --- | --- | --- |
| 列表主区会话 | 仅 `recipe_light` | 仅开发 recipe |
| 另一模式会话 | 不显示（数据保留） | 不显示（数据保留） |
| 新建会话 | 仅轻聊草稿 | 仅开发引擎选择 |
| 空状态「准备运行环境」 | ○ | ● |
| 空状态「连接模型」 | ●（Chat Completions） | ●（Responses/既有） |
| 进会话路由 | `light-chat-session` | Codex / pi / dsh |
| 列表按压预热 agent | ○ | ●（Codex/pi） |
| Runtime 未就绪弹窗 | ○（新建时） | ● |

### 3.2 设置 Tab

| 入口 | 轻聊 | 开发 |
| --- | --- | --- |
| **模式**（轻聊/开发单选） | ● | ● |
| 模型服务 | ●（文案偏 Chat Completions；Responses 仍可管，供切开发） | ●（完整） |
| 运行环境 | ○（或「开发模式才需要」次要链到切换模式） | ● |
| 扩展 | ○ | ● |
| 会话高级设置（权限默认） | ○ | ● |
| Codex 配置文件 | ○ | ◐ 高级 |
| 备份与恢复 | ● | ● |
| 关于 / Lab | 既有 | 既有 |

**原则**：轻聊设置首页应像「聊天 App」——模式、模型、备份、关于；不要第一眼 Runtime。

### 3.3 新建 / 编辑会话

| 字段 | 轻聊 | 开发 Codex | 开发 pi | 开发 dsh |
| --- | --- | --- | --- | --- |
| 名称 | ● | ● | ● | ● |
| 模式芯片 | ○（全局已选） | ○ | ○ | ○ |
| 引擎下拉 | ○（仅一种） | ● 多引擎 | ● | ● |
| 模型服务 | Chat Completions 必选 | Responses 可选「当前 Codex」 | Chat Completions 必选 | ○ 网页内 |
| 模型 ID | ● | ● | ● | ○ |
| 角色 identity | ● 主推 | ● | ● | ○ |
| 扩展 | ○ | ● | ○ | ○ |
| Codex 权限 | ○ | ◐ 高级 | ○ | ○ |
| 工作区路径 | ○ 标准；高级可藏 | ◐ | ◐ | ○ |

### 3.4 聊天内

| 能力 | 轻聊 | 开发 Codex |
| --- | --- | --- |
| 附件 / 工具 / 审批 | ○ | ● |
| 语音输入 | P1 可共用 | ● 已有 |
| 技术细节 | ○ | 随 ExperienceLevel |
| 状态文案 | 「直连网关 · 无本地 Agent」 | 连接/hold/权限 |

## 4. 数据与领域

### 4.1 已有 / 新增

| 概念 | 说明 |
| --- | --- |
| `ConversationMode` | `LIGHT` / `DEV`，SharedPreferences |
| `recipe_light` + `LightChatAdapter` | 轻聊会话类型（已有雏形） |
| `AgentCard.recipeId` | **会话所属能力**；不因全局模式改变而改写已有卡片 |
| `ConversationModePolicy`（待加） | 纯函数：给定 mode → 可见 recipe、设置项、空状态、adapters |

### 4.2 列表过滤（推荐实现）

```text
primaryItems  = cards.filter { card matches currentMode }
otherItems    = cards.filter { card does not match currentMode }
```

- `matches LIGHT` ⇔ `usesLightChat(recipeId)`  
- `matches DEV` ⇔ `isDevMode(recipeId)`  
- 未知/legacy recipe：归入 other，可进但编辑受限。

### 4.3 备份

备份已含 `recipeId` + identity。恢复后：

- 全局模式不变；  
- 用户在对应模式下看到对应会话。

## 5. 信息架构文案（定稿方向）

| 场景 | 文案 |
| --- | --- |
| 设置分组标题 | **模式** |
| 轻聊选项副标题 | 不启动本机 Agent，适合日常对话与角色 |
| 开发选项副标题 | 使用 Codex / pi 等本机 Agent，适合写代码与工具 |
| 轻聊下点到「运行环境」残留入口 | 运行环境属于开发模式。切换到开发后再安装。 |
| 开发下无 Chat Completions | 轻聊与 pi 需要 Chat Completions 服务；Codex 用 Responses。 |

避免：「普通模式」「简单模式」「安全模式」——与 Secure flavor / 权限混淆。

## 6. 非目标

- Secure 内第三种「狂暴」会话模式。  
- 同一 `cardId` 热切换 light↔codex（历史与 runtime 语义不兼容；应 **新建** 另一模式会话）。  
- 轻聊内嵌假工具调用 UI。  
- 用 `ExperienceLevel.STANDARD` 冒充轻聊（层级与模式正交）。  
- 切换模式时卸载 Runtime 或清 Keystore。

## 7. 分阶段落地

### P0（本迭代必须）

1. `ConversationMode` 持久化 + `ExperienceSettingsRepository`（已起）。  
2. **对话顶栏点模式名 → 菜单选择**（设置无独立模式项；不靠两两互切）。  
3. `ConversationModePolicy`：adapters / 设置可见性 / 列表过滤谓词。  
4. `SessionsViewModel.availableAdapters` **随模式变化**；`newDraft` 跟模式。  
5. **去掉**新建对话框内模式芯片。  
6. 设置：轻聊隐藏运行环境 / 扩展 / 会话高级（及 Codex 配置）。  
7. 空状态：轻聊不要求 runtime。  
8. 列表**仅**当前模式会话，不混排另一模式。  
9. 文档：`HANDOFF` + 本文件状态。

### P1

- [x] 对话顶栏模式菜单选择（可扩展多模式）。  
- [x] 模型服务页按模式调整引导文案（不删 Responses 管理）。  
- [x] 切换模式后的教育 Toast。  
- [x] 轻聊隐藏 Runtime 未就绪横幅；列表严格按模式过滤。  
- [ ] 轻聊语音输入（可选，未做）。

### P2

- 分析：模式切换频率、轻聊转化开发。  
- 可选：轻聊默认模型偏好独立于开发。

## 8. 验收清单

- [ ] 设置可切换轻聊/开发，杀进程后保持。  
- [ ] 轻聊：设置无「运行环境」「扩展」「会话高级」；新建无 Codex；空状态无装环境。  
- [ ] 轻聊：仅 Chat Completions 可建会话并聊天，无 proot。  
- [ ] 开发：可建 Codex；运行环境可见；既有 hold 行为不变。  
- [ ] 两模式各建一条会话后互切：主列表只见当前模式会话，另一模式完全不出现。  
- [ ] 模型服务「轻量试聊」仍可用。  
- [ ] Lab 构建行为与 Secure 模式轴无关（Lab 额外入口仍受 ADR-0008）。

## 9. 当前代码债（对照）

| 债 | 处理 |
| --- | --- |
| 新建对话框内轻聊/开发芯片 | P0 删除，改设置 |
| `availableAdapters` 静态全量 | P0 按 mode 过滤 |
| 空状态强制 runtime | P0 分支 |
| 设置不读 `conversationMode` | P0 接入 |
| 无 `ConversationModePolicy` | P0 集中策略，避免 UI 散落 if |

## 10. 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-08-18 | 首版：与体验层级正交；设置全局模式；全表面矩阵；P0–P2 |
| 2026-08-18 | P0 代码：`ConversationMode` / Policy、设置单选、Sessions 过滤与空状态、单测 |
| 2026-08-18 | P1：顶栏模式、模型文案、切换 Toast、setup banner 按模式 |
| 2026-08-18 | 列表严格模式隔离：去掉「其他模式」混排/折叠区 |
| 2026-08-18 | 模式切换改对话顶栏一点切换；设置去掉独立模式分组 |
| 2026-08-18 | 顶栏模式改为下拉选择，不再两点互切 |
