# AgentDeck 设计文档（0.1.4 基线）

> 状态：0.1.4 Android 实现、自动化验证和 iQOO/Termux 真机主链路验收已完成；当前仍是 debug 预发布
> 平台：Android
> 形态：**聊天优先客户端**（App 承载原生 transcript；Termux/Ubuntu 运行 Codex app-server 与 TUI 兜底）
> 运行时：**必须安装 Termux**
> 交付顺序：参考研究 ✅ → 0.1.4 实现 ✅ → 自动化验证 ✅ → 主链路真机验收 ✅ → 预发布

---

## 1. 已确认的产品决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 产品形态 | **聊天优先客户端**：不自建 Agent 推理循环；App 管原生会话 UI，Termux 跑真 CLI/runtime |
| 2 | 运行时 | **必须 Termux**（推荐 F-Droid 签名版） |
| 3 | 首批 CLI | **以 Codex 为 P0**；Claude Code / Kimi CLI 同模型扩展（P1） |
| 4 | 主交互 | 首页 **对话列表** → app-server 原生 transcript；Termux TUI 是对话内备用动作 |
| 4b | 启动链 | 不是单条命令：需 **先进入 Ubuntu（proot-distro）再执行 `codex`** |
| 5 | 认证与模型 | 凭据、Provider 与模型由 CLI 官方配置管理；App 显示 app-server 返回的实际运行值 |
| 6 | 交付 | P0 Android 客户端 + 可重复验证 + 真机发布清单 |

### 1.1 「聊天界面」的精确定义

1. 首页是 **对话列表**，不是安装器或 Doctor；整行进入，展示标题、CLI、工作区和状态。
2. 点击对话后进入 App 内原生 transcript，提供底部输入器、工具活动、审批、停止与历史恢复；数据来自官方 `codex app-server` 双向 JSON-RPC。
3. 对话右上角保留“在 Termux 中打开”，用于登录、协议不兼容、诊断和完整官方 TUI。
4. 原生聊天不是把 prompt 转发给 HTTP，也不解析 ANSI/TUI 或 JSONL 猜消息。Codex 仍拥有 Agent loop、认证、配置和会话语义。

### 1.2 Codex 典型启动链（核心场景）

用户环境常见路径（可配置）：

```text
Termux shell
  → proot-distro login ubuntu   # 或 distro 名可配
    → 进入 Ubuntu 用户环境
      → cd <workspace>
      → 读取 CLI 自己的认证与配置
      → codex                    # 进入 Codex 聊天 TUI
```

因此「预设命令」必须支持 **多步 / 包装脚本**，不能只有一个 `codex` 字符串。

---

## 2. 产品目标与非目标

### 2.1 目标（MVP）

- 用对话列表管理多个 Agent 启动配置
- 一键进入同一 Codex thread 的原生聊天，并保留同一工作区的 TUI 兜底
- 在 App 内显示 CLI 实际使用的 Provider / Model，不维护与 CLI 脱节的第二套运行配置
- API Key、OAuth token 等凭据由各 CLI 官方认证流程管理
- 能检测/引导安装：Termux、proot-distro、Ubuntu、Codex CLI 和 wrapper
- 为每个对话配置：名称、工作目录和固定启动模板

### 2.2 非目标（MVP 不做）

- 不在 App 内重写 Codex/Claude/Kimi 的 Agent 逻辑
- 不做免 Termux 的完整 Linux 发行版一体机（可后期 P2）
- 不做复杂 IM/多端同步
- 不做「假聊天框转发 prompt 到 HTTP」、解析终端屏幕或绕过官方审批协议
- 不强制 Root

---

## 3. 用户流程

### 3.1 首次使用

```text
安装 AgentDeck APK
  → 检测 Termux 是否安装
  → 引导：安装 Termux（F-Droid）→ 开启 allow-external-apps
  → 授予 RUN_COMMAND 相关权限
  → 运行「准备 Codex」统一设置：
        [ ] Termux 可用
        [ ] proot-distro 已装
        [ ] ubuntu 发行版已装
        [ ] ubuntu:24.04 可用
        [ ] codex 0.147.0 已安装且在 PATH
        [ ] codex login status 就绪
        [ ] 终端与 app-server WebSocket wrapper 已安装
  → 在对应 CLI 内完成官方登录或认证
  → 创建默认对话「Codex」
  → 点击对话 → 进入 Codex 聊天会话
```

### 3.2 日常使用

```text
打开 App 首页
  → 看到 Codex 对话（未开放的旧入口不可启动）
  → 点整行「Codex」
  → App 通过 Termux 后台启动一次性鉴权 app-server
  → 原生 transcript 恢复或创建 Codex thread
  → 需要完整 TUI 时点击右上角终端按钮
```

### 3.3 安装 CLI

```text
准备 Codex
  → 根据当前状态显示唯一下一步动作
  → 一键执行 Ubuntu → Codex → wrapper 安装链
  → 显示探测、安装、验证阶段并自动复检
```

---

## 4. 信息架构（UI）

0.1.4 使用三个主 Tab；环境向导与安装合并为“工具”中的单一设置表面。当前 UI 不再展示旧 Profile，因为它不会改写 CLI 配置。

### 4.1 底部导航（3 Tab）

| Tab | 名称 | 作用 |
|---|---|---|
| 1 | **对话** | 对话列表与 app-server 原生 transcript |
| 2 | **工具** | 统一检测、安装/修复并验证 CLI、依赖与 app-server wrapper |
| 3 | **设置** | 版本、环境状态与 Termux 入口 |

### 4.2 对话 Tab（主界面）

**卡片字段**

- 标题（如 `Codex · 默认项目`）
- 副标题（绑定配置 / 工作区路径）
- 状态：`尚未开放` / `已停用` / `可启动`
- 整行主操作：**进入对话**
- 次级操作：更多菜单中的编辑、删除

**点击「进入」后**

- 后台启动官方回环 WebSocket，握手后恢复映射的 Codex thread；没有映射时创建并保存 thread ID
- 用户看到由真实 Thread / Turn / Item 驱动的原生 transcript；Agent Markdown 不使用统一气泡，工具与审批结构化展示
- “在终端中打开”启动或复用命名 Termux session，作为同一工作区的 TUI 兜底
- 运行、审批、失败和完成状态只来自 app-server 协议事件

**编辑卡片页**

- 名称、CLI 类型、启停状态
- 工作区目录（`cwd`，在 Ubuntu 内路径或 Termux 路径，需标明命名空间）
- 启动模板由所选 CLI adapter 固定；0.1.x 不开放自定义脚本、env 或额外参数编辑

### 4.3 工具 Tab

配方范围：

1. **环境基础**：proot-distro + ubuntu（P0，可用）
2. **Codex CLI**（P0，可用）
3. Claude Code（P1，占位配方，不可用）
4. Kimi CLI（P1 方向，尚无配方）

每张配方卡显示描述、固定版本、优先级和依赖，提供安装/修复、重新验证与失败重试。可取消/导出的安装日志和卸载操作属于 P1。

### 4.4 CLI 运行配置

- Provider、Base URL、模型和认证由 Codex CLI 的本地配置管理
- AgentDeck 不接收、不保存、不传递 API Key 或 OAuth token
- 聊天页只显示 app-server 返回的实际 Provider 与模型，不用本地占位值推断运行配置

### 4.5 设置 Tab

- 紧凑环境状态；完整修复跳转“工具”统一设置
- 打开 Termux / 复制 `allow-external-apps` 修复命令
- 重新检测环境
- 显示当前 AgentDeck 版本

默认发行版、默认用户、导入导出和可复制诊断包属于后续能力。

---

## 5. 关键概念与数据模型

### 5.1 实体

```text
ProviderProfile   历史兼容数据；当前不注入 CLI，也不在主 UI 展示
AgentRecipe       可安装的 CLI 配方（工具）
AgentCard         用户的一键启动卡片（会话入口）
CliAdapter        单个 CLI 的校验、默认值与固定启动命令
LaunchTemplate    启动链模板（多步命令 / wrapper）
SessionRecord     本地记录：上次启动时间、session 名、状态
EnvironmentReport 环境检测结果
```

其中 `LaunchTemplate` 当前由 `CliAdapter` 固定表达，`SessionRecord` 尚未持久化，属于 P1。

### 5.2 ProviderProfile

```yaml
id: prof_openai_main
name: OpenAI Compatible Main
type: openai_compatible   # or anthropic
base_url: "https://api.example.com/v1"
default_model: "gpt-5"
created_at: "2026-08-04T00:00:00Z"
```

Anthropic 示例：

```yaml
id: prof_anthropic_main
name: Anthropic Main
type: anthropic
base_url: "https://api.anthropic.com"   # 可改中转
default_model: "claude-sonnet-4-20250514"
```

### 5.3 LaunchTemplate（解决「先 Ubuntu 再 codex」）

```yaml
id: tpl_codex_ubuntu
name: Codex inside Ubuntu
runtime: termux
# 在 Termux 层执行的入口命令（单一可执行入口，内部多步）
entry: "~/.agentdeck/wrappers/codex-ubuntu.sh"
# 或直接用 proot-distro 一行式（备选）
# entry_inline: |
#   proot-distro login ubuntu -- bash -lc 'cd "$CWD" && exec codex "$@"' _
params:
  distro: ubuntu
  login_user: ""          # 空=默认
  inner_cwd: "/root/projects/default"
  inner_bin: "codex"
  inner_args: []
```

**原则**：App 永远只触发 **一个固定的** Termux `RUN_COMMAND` 入口；多步逻辑放在 **wrapper 脚本** 里。动态值作为参数数组传入，禁止生成包含用户输入或凭据的 shell 源码。

### 5.4 AgentCard

```yaml
id: card_codex_default
name: Codex
icon: codex
recipe_id: recipe_codex
template_id: tpl_codex_ubuntu
profile_id: prof_openai_main
termux_session_name: "agentdeck-codex-default"
workspace:
  # 路径语义：ubuntu = distro 内路径；termux = Termux $HOME 相对/绝对
  namespace: ubuntu
  path: "/root/projects/default"
launch:
  inner_args: []          # 追加到 codex，如 ["resume"]
enabled: true
```

卡片支持新建、编辑、启停和删除。`profile_id` 是可空外键，删除 Profile 时使用 `ON DELETE SET NULL`；adapter 在保存与启动时同时校验 recipe/template、Provider 类型、工作区命名空间和固定 CLI 可执行文件。

### 5.5 AgentRecipe（工具）

```yaml
schema_version: 1
id: recipe_codex
name: Codex CLI
description: OpenAI Codex CLI，运行在 proot Ubuntu 内
priority: p0
version: "0.147.0"
available: true
depends_on:
  - recipe_proot_ubuntu
timeout_minutes: 30
wrapper_asset: codex-ubuntu.sh
install:
  runtime: termux
  script: |
    # 固定官方 Release URL，并按架构校验固定 SHA-256 后安装
verify:
  runtime: termux
  script: |
    # 同时验证 wrapper、codex 命令和精确版本
```

配方只从 APK assets 加载并严格校验。安装器按依赖顺序执行“探测、安装、再验证”；不可用条目（如当前 Claude Code P1）不能携带或执行安装脚本。远程配方和热更新不属于当前信任边界。

### 5.6 环境检测项（EnvironmentReport）

| 检查 ID | 层级 | 命令/条件 | 失败时引导 |
|---|---|---|---|
| `termux_installed` | Android | 包名 `com.termux` 可解析 | 打开 F-Droid/下载页 |
| `termux_run_command_permission` | Android | 权限/Intent 可达 | 设置说明 |
| `allow_external_apps` | Termux | 读 properties 或试跑 | 一键复制修复命令 |
| `proot_distro` | Termux | `command -v proot-distro` | 工具页安装基础环境 |
| `ubuntu_installed` | Termux | `proot-distro list` 含 ubuntu | 安装 ubuntu |
| `codex_installed` | Ubuntu | `command -v codex` | 安装 Codex 配方 |
| `codex_authenticated` | Ubuntu | `codex login status`、官方认证环境变量、当前 Provider `env_key` | 复用已有认证；缺失时打开认证助手 |

每项检查使用 `UNKNOWN / CHECKING / READY / ACTION_REQUIRED / BLOCKED / ERROR` 状态。Android 本地条件同步判断；Termux、Ubuntu 和 Codex 条件通过带 `PendingIntent` 结果回调的后台命令判断。Intent 被系统接受不代表命令成功。

---

## 6. 启动链路（详细）

### 6.1 Wrapper 脚本（安装配方时写入 Termux）

路径：`$PREFIX/../home/.agentdeck/wrappers/codex-ubuntu.sh`
（即 Termux `~/.agentdeck/wrappers/codex-ubuntu.sh`）

```bash
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# 参数由 App 作为 argv 传入：
# codex-ubuntu.sh --distro ubuntu --cwd /root/project --bin codex -- <codex args>
# wrapper 负责逐项解析和校验，不执行 App 生成的 shell 源码。
```

同一配方还安装 `codex-app-server-start.sh`。它在 Termux 中作为稳定 supervisor，进入 Ubuntu 后直接启动官方 `codex app-server --listen ws://127.0.0.1:0 --ws-auth capability-token`。token 文件与 PID lease 位于 Termux 私有运行目录；supervisor 负责离开页面或异常退出后的精确进程树清理。

### 6.2 App 侧原生聊天步骤

```text
1. 校验卡片 → 模板 → CLI 配置 → Termux 安装与权限
2. 后台执行固定 codex-app-server-start.sh，动态 cwd/distro 只通过 argv 传入
3. Termux 回调只返回 port、一次性 token 与 instance key；Android 以 bearer token 连接 127.0.0.1 WebSocket
4. 发送 initialize / initialized，随后 thread/resume 或 thread/start
5. Android 按 Item 与 delta 更新时间线，按 server request 内联审批，turn/interrupt 停止回复
6. 离开页面关闭 socket并停止 lease；supervisor 终止其完整 PRoot/app-server 进程树。再次进入按本地 card ID → Codex thread ID 映射恢复历史与活动 turn
```

终端兜底继续使用 `codex-ubuntu.sh`、前台命名会话和 `no-shell-with-name`，不与 Codex thread ID 混用。

### 6.3 Termux Intent 约定

- Action：`com.termux.RUN_COMMAND`
- Extra：
  - `com.termux.RUN_COMMAND_PATH`
  - `com.termux.RUN_COMMAND_ARGUMENTS`
  - `com.termux.RUN_COMMAND_WORKDIR`
  - `com.termux.RUN_COMMAND_SESSION_ACTION`（字符串 `"0"`）
  - `com.termux.RUN_COMMAND_SHELL_NAME`
  - `com.termux.RUN_COMMAND_SHELL_CREATE_MODE`（`no-shell-with-name`）
- 权限：`com.termux.permission.RUN_COMMAND`
- 前置：`~/.termux/termux.properties` 中 `allow-external-apps=true`

> 常量与 termux-app 当前 `termux-shared` 契约保持一致；会话进入、恢复、新回复与退出清理已在 Android 16 / iQOO Neo8 上验证。

### 6.4 为什么必须 wrapper

| 问题 | 直接拼长命令 | wrapper |
|---|---|---|
| `proot-distro login` + 内层 `codex` | 引号/换行易炸 | 稳定 |
| 传递 API Key | 易进 Intent、进程列表或日志 | 不传递；由 CLI 自己管理认证 |
| 多发行版/多项目 | 难复用 | 模板参数化 |
| 安装后修复 | 难 | 重写 wrapper 即可 |

---

## 7. CLI 配置与认证

MVP 策略：**CLI 拥有认证，AgentDeck 只选择启动配置**。

### 7.1 Codex

- 用户在 Codex CLI 内使用官方登录或配置流程。
- AgentDeck 不读取 `~/.codex`，也不传递 `OPENAI_API_KEY`。
- 自定义 provider 配置由 Codex 自己的 `config.toml` 管理；AgentDeck 后续可以选择已存在的配置名，但不能生成包含 secret 的内容。

### 7.2 Claude Code / Kimi CLI（P1）

沿用相同边界：认证和 provider 配置留在对应 CLI 内，适配器只负责探测、安装、认证状态提示和启动参数。

### 7.3 安全

- Intent、argv、stdin 和日志中不得包含凭据。
- AgentDeck 数据库和备份不保存 API Key 或 OAuth token。
- UI 不提供凭据输入框，避免制造虚假的跨应用安全承诺。

---

## 8. 模块架构（App）

```text
app/
  ui/
    sessions/     卡片列表、编辑、启动
    store/        配方安装与结果
    models/       非敏感 CLI 配置 CRUD
    settings/     环境向导、权限
  domain/
    model/        数据类
    launch/       LaunchInteractor（校验→适配器→Intent）
    env/          EnvironmentProbe
    install/      RecipeInstaller
  data/
    db/           Room
    termux/       TermuxGateway（Intent / 探测）
    repo/         CardRepo / ProfileRepo / RecipeRepo
  main/assets/    内置配方和 wrapper
```

### 8.1 关键边界（0.1.x）

```kotlin
interface TermuxGateway {
  fun isTermuxInstalled(): Boolean
  fun hasRunCommandPermission(): Boolean
  fun runCommand(command: TermuxCommand): Result<Unit>
  suspend fun runCommandForResult(
    command: TermuxCommand,
    timeoutMillis: Long,
  ): Result<TermuxCommandResult>
}

interface CliAdapter {
  val descriptor: CliAdapterDescriptor
  fun validateCard(card: AgentCard): Result<Unit>
  fun createCommand(card: AgentCard): Result<TermuxCommand>
}
```

---

## 9. MVP 范围拆分

### P0（0.1.4 已实现）

1. Compose 三 Tab、卡片 CRUD、原生聊天与底部导航
2. Room v3、非破坏迁移、Profile 外键与一次性初始化
3. 统一设置状态、一步安装、Termux 命名兜底会话和后台结果回调
4. 严格配方 schema、依赖排序、版本/摘要固定和安装后验证
5. Codex adapter、固定 supervisor wrapper、鉴权回环 WebSocket 和 argv 安全边界
6. Thread 恢复、Item 时间线、Markdown、流式 delta、停止和 command/file approval
7. CI、Apache-2.0、安全策略、变更日志和发布清单

### P1

1. Claude Code adapter 与经过固定版本验证的配方
2. 有界、可取消、可导出的安装日志 UI
3. thread 搜索、归档、重命名、附件、完整 diff 与文件定位
4. 更多 app-server server request 类型和断线诊断
5. Codex 非敏感配置映射与配置导入导出

### P2

1. 内嵌终端（形态 B 能力）
2. 桌面 Widget
3. 远程配方订阅
4. 更稳的 daemon 通信（替代纯 Intent）

---

## 10. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| Termux 来源/版本不兼容 | RUN_COMMAND 失败 | 文档要求 F-Droid 版并由 Doctor 给出明确阻塞状态 |
| `allow-external-apps` 未开 | 无法启动 | 向导一键说明 + 复制命令 |
| proot Ubuntu 与 Termux 路径混淆 | cd 失败 | 路径命名空间 `ubuntu` / `termux` |
| CLI 资产/参数变更 | 配方失效 | 固定版本与 SHA-256；升级必须显式更新配方和测试 |
| API Key 出现在进程参数 | 泄露 | AgentDeck 不接收或传递凭据；认证留在 CLI |
| Codex 配置键变更 | App 显示错误模型 | 以 app-server 实际返回值为准；当前不注入或推断配置 |
| app-server 被系统杀死或协议变化 | 原生聊天断开 | 明确错误与重试；按 thread history 恢复；始终保留官方 TUI 兜底 |
| 回环端口被其它 App 尝试连接 | 控制 Codex | 仅 127.0.0.1、一次性高熵 token、单客户端、消息上限和空闲退出 |

---

## 11. 目录规划（仓库）

```text
AgentDeck/
  .github/                  # CI / Dependabot
  docs/
    DESIGN.md               # 本文
    ADR-000*.md             # 执行、结果、配方、数据决策
    RELEASE_CHECKLIST.md    # 真机与发布验收
  recipes/
    base-ubuntu.yaml
    codex.yaml
    claude-code.yaml       # P1
  wrappers/
    codex-ubuntu.sh              # TUI 兜底
    codex-app-server-start.sh    # Termux 后台启动入口
  android/                 # Kotlin + Compose 工程
    app/
    ...
  scripts/verify-release.sh
  README.md
  LICENSE
```

---

## 12. 验收标准（MVP Demo）

在已安装 Termux + ubuntu + codex 的真机上：

1. 首次进入“准备 Codex”，一个主动作按顺序处理 Termux 前置、Ubuntu、Codex 与 bridge，并显示真实阶段
2. 自动检测已有账号或 Provider 凭据；缺失时完成认证，然后点击 Codex 卡片进入 App 内原生 transcript
3. 发送消息可看到用户消息、流式 Markdown 回复和结构化工具活动
4. 命令/文件审批可允许、会话允许或拒绝；停止按钮能中断当前 turn
5. 离开后再次进入恢复同一 thread 的历史；活动 turn 仍显示运行并可停止
6. 右上角终端按钮进入同一工作区的官方 Codex TUI
7. 未装 Termux、bridge 失败或协议不兼容时给出明确错误与修复/终端兜底，不显示假成功
8. 历史 Profile 数据不被破坏，但当前聊天不把它误报为实际 CLI 配置

---

## 13. 对你回复的逐条落地

| 你的决定 | 文档落点 |
|---|---|
| 1 → A 轻量启动器 | §1 / §2 / §8 TermuxGateway |
| 2 → 必须 Termux | §1 / §5.6 / §10 |
| 3 → 以 Codex 为主（P0） | §1 / §9；Claude/Kimi 同模式 P1 |
| 4 → 卡片进入聊天界面 | §1.1 / §6：官方 app-server 驱动的原生 transcript；TUI 是备用入口 |
| 4 → 先 Ubuntu 再 codex | §1.2 / §5.3 / §6 wrapper |
| 5 → 仅 OpenAI 兼容 + Anthropic | §4.4 / §7 |
| 6 → 先文档再实现 | 本文、四个 ADR、Android 工程和发布清单 |

> Codex 是当前唯一开放的 P0 adapter；Claude Code 保留为 P1 规划项，但在配方与真机验证完成前不可安装或启动。

---

## 14. 发布前剩余工作

1. 在更多 OEM/Android 版本上完成 `docs/RELEASE_CHECKLIST.md` 的稳定版验收
2. 验证 v0.1.0 APK 数据升级到 Room v3
3. 继续验证首次 arm64 Ubuntu/Codex 下载、SHA-256、全部审批分支与 TUI 兜底
4. 配置正式 release 签名，并在真机清单通过后发布稳定版

---

## 15. 已固定默认值

1. 第二个 CLI 预留 Claude Code，但默认不可用
2. distro 名固定 `ubuntu`，新安装固定 `ubuntu:24.04`
3. 默认工作区 `/root/projects/default`
4. 应用名 AgentDeck
