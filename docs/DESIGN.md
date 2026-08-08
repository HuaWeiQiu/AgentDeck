# AgentDeck 设计文档（0.1.1 基线）

> 状态：P0 Android 实现、自动化验证和发布文档已落地；等待真实 Android + F-Droid Termux 验收
> 平台：Android
> 形态：**A. 轻量启动器**（App 管配置/安装/卡片；会话跑在 Termux）
> 运行时：**必须安装 Termux**
> 交付顺序：文档 ✅ → P0 实现 ✅ → 自动化验证 ✅ → 真机验收 → P1

---

## 1. 已确认的产品决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 产品形态 | **A 轻量启动器**：不自建完整 Agent 推理循环；用 Termux 跑真 CLI |
| 2 | 运行时 | **必须 Termux**（推荐 F-Droid 签名版） |
| 3 | 首批 CLI | **以 Codex 为 P0**；Claude Code / Kimi CLI 同模型扩展（P1） |
| 4 | 主交互 | 首页 **Agent 卡片** → 点击进入 **CLI 聊天界面**（Codex TUI 本身） |
| 4b | 启动链 | 不是单条命令：需 **先进入 Ubuntu（proot-distro）再执行 `codex`** |
| 5 | 认证与模型 | 凭据由各 CLI 官方认证流程管理；App 只保存非敏感配置选择 |
| 6 | 交付 | P0 Android 客户端 + 可重复验证 + 真机发布清单 |

### 1.1 「聊天界面」的精确定义

用户要的不是 App 自研的大输入框 Chat Bot，而是：

1. 首页是 **卡片/会话管理界面**（产品 UI）
2. 点卡片后进入 **Codex CLI 的交互界面**（在 Termux 会话里）
3. 因为 Codex 的使用方式是「聊天式 TUI」，所以体感是「进聊天」，底层仍是终端会话

App 自己 **不做** 第二套消息气泡主界面（MVP）。

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

- 用卡片管理多个 Agent 启动配置
- 一键启动：**按预设链路进入 Codex（及后续其它 CLI）聊天会话**
- 在 App 内选择 CLI 的非敏感配置（Provider / Base URL / Model 等元数据）
- API Key、OAuth token 等凭据由各 CLI 官方认证流程管理
- 能检测/引导安装：Termux、proot-distro、Ubuntu、Codex CLI 和 wrapper
- 为每个卡片配置：工作目录、启动脚本、绑定的模型 Profile

### 2.2 非目标（MVP 不做）

- 不在 App 内重写 Codex/Claude/Kimi 的 Agent 逻辑
- 不做免 Termux 的完整 Linux 发行版一体机（可后期 P2）
- 不做复杂 IM/多端同步
- 不做「假聊天框转发 prompt 到 HTTP」替代 CLI
- 不强制 Root

---

## 3. 用户流程

### 3.1 首次使用

```text
安装 AgentDeck APK
  → 检测 Termux 是否安装
  → 引导：安装 Termux（F-Droid）→ 开启 allow-external-apps
  → 授予 RUN_COMMAND 相关权限
  → 运行「环境向导」：
        [ ] Termux 可用
        [ ] proot-distro 已装
        [ ] ubuntu 发行版已装
        [ ] ubuntu:24.04 可用
        [ ] codex 0.147.0 已安装且在 PATH
        [ ] codex login status 就绪
        [ ] 固定 wrapper 已安装
  → 在对应 CLI 内完成官方登录或认证
  → 创建默认卡片「Codex」
  → 点击卡片 → 进入 Codex 聊天会话
```

### 3.2 日常使用

```text
打开 App 首页
  → 看到 Codex 卡片（未开放的旧卡片不可启动）
  → 点「Codex」
  → App 通过 Termux 启动命名会话，执行 wrapper
  → 用户落在 Codex TUI（聊天界面）
  → 返回 App 可再开另一会话 / 改配置 / 装其它 CLI
```

### 3.3 安装 CLI

```text
工具 Tab
  → 选 Codex
  → 查看依赖图与安装脚本
  → 一键执行（在 Termux 后台任务中安装）
  → 安装后重新运行版本与 wrapper 验证
```

---

## 4. 信息架构（UI）

本节以产品目标态描述信息架构。0.1.1 已实现四个 Tab、卡片/Profile CRUD、配方安装结果和 Doctor；会话历史、安装日志、导入导出等明确列在 P1，不应按当前能力理解。

### 4.1 底部导航（4 Tab）

| Tab | 名称 | 作用 |
|---|---|---|
| 1 | **会话** | 卡片列表、一键进入 CLI 聊天、最近会话 |
| 2 | **工具** | 安装/修复并验证 CLI 与依赖 |
| 3 | **配置** | CLI 配置选择与认证状态入口（不保存凭据） |
| 4 | **设置** | Termux 集成、环境向导、备份、关于 |

### 4.2 会话 Tab（主界面）

**卡片字段**

- 标题（如 `Codex · 默认项目`）
- 副标题（绑定配置 / 工作区路径）
- 状态：`尚未开放` / `已停用` / `可启动`
- 主按钮：**进入**
- 次级：编辑、删除

**点击「进入」后**

- MVP：拉起 Termux，附着到命名 session，执行启动包装脚本
- 用户看到的是 **Codex 聊天 TUI**（不是 App 自绘 Chat）
- App 用短提示确认启动请求已交给 Termux；0.1.1 不追踪会话运行状态

**编辑卡片页**

- 名称、CLI 类型、启停状态
- 工作区目录（`cwd`，在 Ubuntu 内路径或 Termux 路径，需标明命名空间）
- 绑定模型 Profile
- 启动模板由所选 CLI adapter 固定；0.1.1 不开放自定义脚本、env 或额外参数编辑

### 4.3 工具 Tab

配方范围：

1. **环境基础**：proot-distro + ubuntu（P0，可用）
2. **Codex CLI**（P0，可用）
3. Claude Code（P1，占位配方，不可用）
4. Kimi CLI（P1 方向，尚无配方）

每张配方卡显示描述、固定版本、优先级和依赖，提供安装/修复、重新验证与失败重试。可取消/导出的安装日志和卸载操作属于 P1。

### 4.4 配置 Tab

- Profile 列表
- 类型：`openai_compatible` | `anthropic`
- 字段：名称、Base URL、默认 Model
- 认证由对应 CLI 管理；App 不接收、不保存、不传递 API Key 或 OAuth token

### 4.5 设置 Tab

- Termux 安装、权限与运行环境 Doctor
- 打开 Termux / 复制 `allow-external-apps` 修复命令
- 复制 Codex wrapper 安装脚本
- 显示当前 AgentDeck 版本

默认发行版、默认用户、导入导出和可复制诊断包属于后续能力。

---

## 5. 关键概念与数据模型

### 5.1 实体

```text
ProviderProfile   模型配置（OpenAI 兼容 / Anthropic）
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
timeout_minutes: 15
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
| `termux_run_command` | Android | 权限/Intent 可达 | 设置说明 |
| `allow_external_apps` | Termux | 读 properties 或试跑 | 一键复制修复命令 |
| `proot_distro` | Termux | `command -v proot-distro` | 工具页安装基础环境 |
| `ubuntu_installed` | Termux | `proot-distro list` 含 ubuntu | 安装 ubuntu |
| `codex_installed` | Ubuntu | `command -v codex` | 安装 Codex 配方 |
| `codex_authenticated` | Ubuntu | `codex login status` | 在 Codex CLI 内完成登录 |
| `profile_bound` | App | 卡片绑定有效 Profile | 去模型页 |

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

### 6.2 App 侧启动步骤

```text
1. 校验卡片 → 模板 → CLI 配置 → Termux 安装与权限
2. 由适配器选择固定 wrapper 和结构化参数
3. 发送 Termux Intent:
   - session name = card.termux_session_name
   - command = ~/.agentdeck/wrappers/codex-ubuntu.sh
   - arguments = `[--distro, ..., --cwd, ..., --bin, ..., --, ...]`
   - working dir = Termux home
   - background = false（前台会话）
   - shell create mode = `no-shell-with-name`
4. Termux 创建或切换到同名会话，并打开 UI
5. 0.1.1 不记录会话状态；会话历史与运行状态属于 P1
```

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

> 常量与 termux-app 当前 `termux-shared` 契约保持一致；发布前仍需真机验证会话复用行为。

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

### 8.1 关键边界（0.1.1）

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

### P0（0.1.1 已实现）

1. Compose 四 Tab、卡片 CRUD、Profile CRUD 与底部导航
2. Room v3、非破坏迁移、Profile 外键与一次性初始化
3. Termux 命名会话、后台结果回调与真实 Doctor
4. 严格配方 schema、依赖排序、版本/摘要固定和安装后验证
5. Codex adapter、固定 wrapper 和 argv 安全边界
6. CI、Apache-2.0、安全策略、变更日志和发布清单

### P1

1. Claude Code adapter 与经过固定版本验证的配方
2. 有界、可取消、可导出的安装日志 UI
3. 会话记录与运行状态
4. Codex 非敏感配置映射（以稳定官方契约为前提）
5. 配置导入导出

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
| Codex 配置键变更 | Profile 误导用户 | 当前不注入配置；稳定后只在 Codex adapter 内实现映射 |
| 后台被杀 | 会话断 | 依赖 Termux 自身会话；App 不保活 CLI |

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
    codex-ubuntu.sh        # 模板，安装时写入 Termux
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

1. 打开 AgentDeck，模型页保存一个 OpenAI 兼容 Profile
2. 会话页中已启用且配方开放的 Codex 卡片显示「可启动」，Doctor 另行确认真实环境状态
3. 点击 **进入** → 自动打开 Termux 会话
4. 会话内自动完成 ubuntu 登录并进入 **Codex 聊天界面**
5. Doctor 能用 `codex login status` 识别 CLI 自己管理的认证状态
6. 未装 Termux 或命令失败时，向导给出明确阻塞原因而非假成功
7. 删除 Profile 后卡片保留并解除绑定；删除最后一项后重启不复活

---

## 13. 对你回复的逐条落地

| 你的决定 | 文档落点 |
|---|---|
| 1 → A 轻量启动器 | §1 / §2 / §8 TermuxGateway |
| 2 → 必须 Termux | §1 / §5.6 / §10 |
| 3 → 以 Codex 为主（P0） | §1 / §9；Claude/Kimi 同模式 P1 |
| 4 → 卡片进入聊天界面 | §1.1：进的是 **Codex TUI**；App 不自绘 Chat |
| 4 → 先 Ubuntu 再 codex | §1.2 / §5.3 / §6 wrapper |
| 5 → 仅 OpenAI 兼容 + Anthropic | §4.4 / §7 |
| 6 → 先文档再实现 | 本文、四个 ADR、Android 工程和发布清单 |

> Codex 是当前唯一开放的 P0 adapter；Claude Code 保留为 P1 规划项，但在配方与真机验证完成前不可安装或启动。

---

## 14. 发布前剩余工作

1. 在真实 Android + F-Droid Termux 上完成 `docs/RELEASE_CHECKLIST.md`
2. 验证 v0.1.0 APK 数据升级到 Room v3
3. 验证 arm64 Ubuntu/Codex 下载、SHA-256、登录和 TUI
4. 配置正式 release 签名并发布 `v0.1.1`

---

## 15. 已固定默认值

1. 第二个 CLI 预留 Claude Code，但默认不可用
2. distro 名固定 `ubuntu`，新安装固定 `ubuntu:24.04`
3. 默认工作区 `/root/projects/default`
4. 应用名 AgentDeck
