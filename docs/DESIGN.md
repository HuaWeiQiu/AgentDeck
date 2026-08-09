# AgentDeck 设计文档（0.2 开发基线）

> 状态：0.2.0-beta.1 内嵌 Runtime 已通过单台 ARM64 真机关键路径；稳定门禁仍在继续
> 平台：Android
> 形态：**聊天优先的本地 Agent 客户端**
> 运行时：新测试安装默认 `EmbeddedProotRuntime`；`TermuxRuntime` 保留为高级兼容后端
> 产品原则：标准模式不要求用户理解 CLI、Linux、端口、协议或安装细节

---

## 1. 已确认的产品决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 产品形态 | **聊天优先客户端**：不自建 Agent 推理循环；App 管原生会话 UI，`AgentRuntime` 跑真实 CLI |
| 2 | 运行时 | 新安装目标默认内嵌 Linux Runtime；Termux 是完成验证前的当前后端和之后的高级兼容选项 |
| 3 | 首批 CLI | **以 Codex 为 P0**；Claude Code / Kimi CLI 同模型扩展（P1） |
| 4 | 主交互 | 首页 **对话列表** → app-server 原生 transcript；终端和 Runtime 诊断只在高级模式出现 |
| 4b | 启动链 | UI 只依赖 `AgentRuntime`；当前实现经 Termux/Ubuntu，目标实现经 App 私有 PRoot/rootfs |
| 5 | 认证与模型 | 默认复用 CLI 官方配置；用户可添加受管 Responses Provider，密钥由 Keystore 与鉴权 broker 管理；App 以 app-server 实际值校验结果 |
| 6 | 体验层级 | 标准模式面向客户；高级模式面向懂技术的用户；开发者模式提供脱敏诊断且不绕过安全边界 |
| 7 | 交付 | 每个阶段完成实现、测试、审查、修复、复测和本地提交；Runtime 默认切换必须通过真机门禁 |

### 1.1 「聊天界面」的精确定义

1. 首页是 **对话列表**，不是安装器或 Doctor；整行进入，标准模式只展示标题、客户状态和必要的一行上下文。
2. 点击对话后进入 App 内原生 transcript，提供底部输入器、工具活动、审批、停止与历史恢复；数据来自官方 `codex app-server` 双向 JSON-RPC。
3. Agent 回复使用无边框 Markdown；思考和工具默认折叠为一行摘要；只有审批、提问和错误使用强调表面。
4. 终端入口、Runtime 切换、完整命令和协议日志只在高级或开发者模式出现。
5. 原生聊天不是把 prompt 转发给 HTTP，也不解析 ANSI/TUI 或 JSONL 猜消息。Codex 仍拥有 Agent loop、认证、配置和会话语义。

### 1.2 Runtime 启动链

目标默认路径：

```text
AgentDeck
  -> EmbeddedProotRuntime
    -> App 私有 Linux rootfs
      -> codex app-server
```

迁移期兼容路径：

```text
AgentDeck -> TermuxRuntime -> proot-distro Ubuntu -> codex app-server
```

上层只提交结构化 `AgentProcessRequest`，不得判断 Termux Intent、PRoot 参数或 rootfs 路径。

---

## 2. 产品目标与非目标

### 2.1 目标（MVP）

- 用简洁对话列表管理多个本地 Agent 会话
- 一键进入同一 Codex thread 的原生聊天，并在高级模式保留终端诊断能力
- 在 App 内显示 CLI 实际使用的 Provider / Model，不维护与 CLI 脱节的第二套运行配置
- 默认登录与 OAuth token 由各 CLI 官方认证流程管理；用户主动添加的第三方 API Key 由 Keystore 加密管理
- 自动检测、安装、更新和修复本机 Runtime 与 Codex，不要求标准用户理解内部组件
- 标准模式只要求必要的项目和模型选择；工作目录、启动模板和 Runtime 参数进入高级设置
- 输入框随 IME 移动并保持阅读锚点；流式输出不抢走正在阅读历史的用户位置

### 2.2 非目标（MVP 不做）

- 不在 App 内重写 Codex/Claude/Kimi 的 Agent 逻辑
- 不做复杂 IM/多端同步
- 不做「假聊天框转发 prompt 到 HTTP」、解析终端屏幕或绕过官方审批协议
- 不强制 Root
- 不在标准模式暴露完整 Linux 桌面、包管理器或任意 shell 配置
- 不因开发者模式而关闭密钥保护、路径校验或危险操作审批

---

## 3. 用户流程

### 3.1 首次使用

```text
安装 AgentDeck APK
  -> 自动检查设备、空间和已有配置
  -> 必要时下载并准备本机运行环境
  -> 验证 Codex 与聊天服务
  -> 自动复用已有认证；确实缺少时连接模型服务
  -> 开始对话
```

标准模式只显示“检查设备、下载运行组件、初始化、验证”这些客户阶段。选择 Termux 兼容后端时，其专用权限和外部调用步骤只在高级流程出现。

### 3.2 日常使用

```text
打开 App 首页
  → 看到 Codex 对话（未开放的旧入口不可启动）
  → 点整行「Codex」
  → App 通过当前 AgentRuntime 启动一次性鉴权 app-server
  → 原生 transcript 恢复或创建 Codex thread
  → 需要终端或诊断时从高级菜单进入
```

### 3.3 安装 CLI

```text
准备本机 Agent
  → 根据当前状态显示唯一下一步动作
  → 下载、校验、安装并验证版本化 Runtime/Codex
  → 失败时自动恢复、重试或回滚上一可用版本
```

---

## 4. 信息架构（UI）

标准模式使用两个主 Tab。首次准备、自动修复和审批是上下文表面，不占用长期导航。高级模式在设置中增加模型服务和 Runtime 管理，开发者模式再增加诊断入口。

### 4.1 底部导航（2 Tab）

| Tab | 名称 | 作用 |
|---|---|---|
| 1 | **对话** | 对话列表与 app-server 原生 transcript |
| 2 | **设置** | 账号、模型、运行环境和关于；按体验层级逐级展示 |

### 4.2 对话 Tab（主界面）

**标准列表字段**

- 状态点、对话标题
- 一行实际 Agent / 模型 / 项目上下文
- 最近活动摘要和时间
- 整行主操作：进入对话；编辑和删除放在长按、滑动或更多菜单

**点击「进入」后**

- 后台启动官方回环 WebSocket，握手后恢复映射的 Codex thread；没有映射时创建并保存 thread ID
- 用户看到由真实 Thread / Turn / Item 驱动的原生 transcript；Agent Markdown 无外层气泡，活动采用三级详情策略
- 用户消息使用右侧浅色气泡；审批/提问使用底部 Sheet；错误使用可恢复提示
- 输入器默认只有附件、文本和发送/停止；模型与权限使用紧凑选择器或菜单
- 运行、审批、失败和完成状态只来自 app-server 协议事件

**编辑卡片页**

- 标准模式：名称、项目、推荐模型
- 高级模式：CLI、Provider、模型 ID、工作区、权限策略和 Runtime
- 启动模板由 adapter 固定；任何模式都不接受任意 shell 源码

### 4.3 准备与修复页面

页面由首次启动、客户状态或设置中的“本机运行环境”进入。标准模式显示真实阶段、必要下载大小、一个主动作和安全的自动修复；recipe、依赖、命令和完整日志仅在开发者模式展示。

### 4.4 模型服务

- “当前 Codex 配置”继续复用 Codex CLI 的 Provider、模型和官方认证
- Sub2API 与 OpenAI Responses 兼容服务可以保存名称、HTTPS Base URL、默认模型和独立 credential 引用
- 受管 API Key 只以 Android Keystore 密文存在；模型发现成功后显示可搜索列表，并保留手动模型 ID
- 聊天页以 app-server 返回的实际 Provider 与模型校验运行配置，不用本地占位值伪造成功

### 4.5 设置 Tab

- 标准：模型服务摘要、本机运行环境状态、隐私、安全和关于
- 高级：自定义 Endpoint、模型 ID、工作区、代理、权限策略、Runtime 后端和更新通道
- 开发者：Runtime/CLI 版本、脱敏协议与进程日志、重新验证/重建、诊断包和 Feature Flags
- 连续点击版本号开启开发者模式；关闭后隐藏入口但不改变安全策略

---

## 5. 关键概念与数据模型

### 5.1 实体

```text
ProviderProfile   模型服务的非敏感元数据与默认模型；密钥由独立 CredentialVault 管理
AgentRecipe       可安装的 CLI 配方（工具）
AgentCard         用户的对话入口
CliAdapter        单个 CLI 的校验、默认值与固定启动命令
LaunchTemplate    启动链模板（多步命令 / wrapper）
SessionRecord     本地记录：上次启动时间、session 名、状态
EnvironmentReport 环境检测结果
RuntimeReport     当前 AgentRuntime 的版本、能力和健康状态
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

### 5.3 LaunchTemplate（Runtime 无关）

```yaml
id: tpl_codex_ubuntu
name: Codex inside Ubuntu
runtime: auto
entry: codex
params:
  inner_cwd: "/root/projects/default"
  inner_bin: "codex"
  inner_args: []
```

**原则**：App 永远只向 `AgentRuntime` 发送结构化请求。具体后端只能触发固定入口；多步逻辑放在受版本控制的 Runtime wrapper 中。非敏感动态值作为参数数组传入，禁止生成包含用户输入或凭据的 shell 源码；受管密钥只通过鉴权回环 broker 提供给固定 token helper。

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
  # 标准模式只通过项目选择器产生；高级模式才显示命名空间
  namespace: runtime
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
| `runtime_supported` | Android | 架构、API 和 native loader 支持 | 说明不支持原因或高级兼容后端 |
| `runtime_storage` | Android | 安装与回滚所需空间 | 客户可理解的清理空间说明 |
| `runtime_ready` | Runtime | rootfs、wrapper 和基础工具功能探测 | 自动安装或修复 |
| `codex_installed` | Runtime | `command -v codex` 与固定版本 | 安装或更新 Agent |
| `codex_authenticated` | Runtime | `codex login status`、官方认证环境变量、当前 Provider `env_key` | 复用已有认证；缺失时连接模型服务 |
| `termux_compatibility` | Android/Termux | 仅选择兼容后端时检查包、权限和外部调用 | 高级设置中的兼容流程 |

每项内部检查使用 `UNKNOWN / CHECKING / READY / ACTION_REQUIRED / BLOCKED / ERROR` 状态，并映射为有限客户状态。任何后端都必须以功能探测和退出结果判断成功；进程被接受启动不代表环境可用。

---

## 6. 启动链路（当前 Termux 兼容实现）

本节记录 0.1.x 已验证实现，只允许存在于 `TermuxRuntime` 内。目标默认实现和迁移门禁见 ADR-0009。

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
| 传递 API Key | 易进 Intent、进程列表或日志 | CLI 密钥不传递；受管密钥只由鉴权 broker 提供给固定 helper |
| 多发行版/多项目 | 难复用 | 模板参数化 |
| 安装后修复 | 难 | 重写 wrapper 即可 |

---

## 7. CLI 配置与认证

0.1.5 策略：**既有 CLI 认证保持原样；受管第三方 Provider 使用 Keystore + Codex `auth.command`**。

### 7.1 Codex

- 用户可以继续在 Codex CLI 内使用官方登录或配置流程。
- AgentDeck 不导入或复制 `~/.codex/auth.json`，也不替换现有 ChatGPT 登录。
- 受管第三方 Provider 使用进程级配置覆盖和固定 `auth.command`，不会把 secret 写入 `config.toml`。

### 7.2 Claude Code / Kimi CLI（P1）

沿用相同边界：认证和 provider 配置留在对应 CLI 内，适配器只负责探测、安装、认证状态提示和启动参数。

### 7.3 安全

- Intent、argv、stdin、shell 源码、日志和 Termux 持久文件中不得包含凭据。
- Room 和备份不保存 API Key；受管密文放在 `noBackupFilesDir`，加密密钥由 Android Keystore 持有。
- 凭据输入使用密码字段并为窗口启用 `FLAG_SECURE`，离开即清空且默认不回显；回环 broker 只在对应聊天实例生命周期内可用。

---

## 8. 模块架构（App）

```text
app/
  ui/
    sessions/     轻量对话列表、标准编辑与启动
    setup/        首次准备与上下文自动修复
    chat/         transcript、活动策略、审批与输入器
    models/       高级模型服务配置
    settings/     标准/高级/开发者分层设置
  domain/
    model/        数据类
    runtime/      AgentRuntime 领域契约
    launch/       LaunchInteractor（校验→适配器→Runtime）
    env/          EnvironmentProbe
    install/      RecipeInstaller
  data/
    db/           Room
    runtime/      EmbeddedProotRuntime / Runtime installer
    termux/       TermuxRuntime 兼容实现
    repo/         CardRepo / ProfileRepo / RecipeRepo
  main/assets/    内置配方和 wrapper
```

### 8.1 目标关键边界

```kotlin
interface AgentRuntime {
  val kind: RuntimeKind
  suspend fun inspect(): RuntimeReport
  suspend fun prepare(request: RuntimePrepareRequest): RuntimePrepareResult
  suspend fun start(request: AgentProcessRequest): AgentProcessHandle
  suspend fun stop(instanceId: String): Result<Unit>
}

interface CliAdapter {
  val descriptor: CliAdapterDescriptor
  fun validateCard(card: AgentCard): Result<Unit>
  fun createProcessRequest(card: AgentCard): Result<AgentProcessRequest>
}
```

`TermuxGateway` 继续作为 `TermuxRuntime` 内部依赖，但不再是 UI、Doctor、安装器或 adapter 的公共边界。

---

## 9. MVP 范围拆分

### P0（0.1.4 已实现）

1. 0.1.4 的 Compose 三 Tab、卡片 CRUD、原生聊天与底部导航
2. Room v3、非破坏迁移、Profile 外键与一次性初始化
3. 统一设置状态、一步安装、Termux 命名兜底会话和后台结果回调
4. 严格配方 schema、依赖排序、版本/摘要固定和安装后验证
5. Codex adapter、固定 supervisor wrapper、鉴权回环 WebSocket 和 argv 安全边界
6. Thread 恢复、Item 时间线、Markdown、流式 delta、停止和 command/file approval
7. CI、Apache-2.0、安全策略、变更日志和发布清单

### 0.2 分阶段交付

1. 产品契约与架构基线：ADR-0008/0009、设计和参考研究一致。
2. 标准模式体验：两项主导航、轻量会话列表、客户状态和上下文准备/修复。
3. 移动聊天：无边框 Agent 回复、活动三级详情、审批 Sheet、IME 与阅读锚点。
4. 高级与开发者设置：Provider、模型、Runtime、脱敏日志和诊断分层。
5. 内嵌 Runtime：`AgentRuntime`、`TermuxRuntime` 适配、ARM64 PRoot/rootfs、版本化安装和 Codex。
6. 真机硬化与发布：首次安装、升级/回滚、断网、锁屏、OEM 后台、性能、安全和发布验收。

Claude Code、Kimi、桌面 Widget、远程配方和完整 Linux 桌面不进入上述主链路。

---

## 10. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| Runtime 体积或安装中断 | 首次体验失败 | 分架构包、断点缓存、真实阶段、原子切换和上一版本回滚 |
| Android 10+ 执行限制 | 下载的宿主二进制无法启动 | PRoot/loader 来自 APK native library，保持现代 target SDK 并做真机门禁 |
| GPL/第三方许可证遗漏 | 无法合规分发 | 独立二进制、来源与源码获取说明；复制实现前逐文件审查 |
| OEM 杀后台进程 | Agent 中断 | AgentDeck 前台服务、精确 lease、恢复提示和 thread history 恢复 |
| Termux 来源/版本不兼容 | 兼容后端失败 | 只在高级设置开放并由 Doctor 给出明确修复；不阻塞已就绪内嵌 Runtime |
| CLI 资产/参数变更 | 配方失效 | 固定版本与 SHA-256；升级必须显式更新配方和测试 |
| API Key 出现在进程参数 | 泄露 | 受管密钥只存 Keystore 密文，并通过鉴权 broker 按需返回给 `auth.command` |
| Codex 配置键变更 | App 显示错误模型 | 固定 0.147.0 配置契约，以 app-server 实际返回值校验请求配置 |
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
    codex-app-server-start.sh    # 当前 TermuxRuntime 启动入口
  android/                 # Kotlin + Compose 工程
    app/
    ...
  scripts/verify-release.sh
  README.md
  LICENSE
```

---

## 12. 验收标准（0.2）

1. 全新 ARM64 真机只安装 AgentDeck，即可从一个客户主动作完成 Runtime/Codex 准备并开始对话。
2. 自动检测已有账号或 Provider 凭据；缺失时才要求连接模型服务。
3. 标准模式只有“对话”和“设置”，不出现 Termux、PRoot、Ubuntu、端口、PATH 或退出码。
4. 用户消息、无边框 Agent Markdown、活动摘要、审批/提问和错误具有明确层级；思考默认折叠。
5. 输入法出现时输入器和最后一条消息可见；阅读历史时流式输出不抢滚动位置。
6. 服务端声明的 command/file/network/user-input 决定完整可用；标准文案说明目的和影响，技术详情可展开。
7. 离开、锁屏、杀进程和断网后有明确状态；能恢复同一 thread 或说明为何不能恢复。
8. Runtime 安装中断、校验失败和更新失败不会破坏上一可用版本或用户项目。
9. 高级模式可管理 Provider、模型、工作区和 Runtime；开发者模式可导出脱敏诊断但不能绕过安全策略。
10. 选择 TermuxRuntime 的既有用户仍能使用已验证的 0.1.x 链路，迁移不破坏 Room、Keystore 或 Codex thread 映射。

---

## 13. 决策追踪

| 决策 | 文档落点 |
|---|---|
| 客户优先、默认隐藏技术细节 | ADR-0008、§1、§3、§4、§12 |
| 高级/开发者保留完整控制 | ADR-0008、§4.5、§12 |
| 内嵌本地 Runtime，不强制独立 Termux | ADR-0009、§1.2、§5.6、§8、§10 |
| Codex app-server 驱动原生聊天 | ADR-0005、§1.1、§6 |
| Provider/API Key/模型发现 | ADR-0007、§4.4、§7 |
| 分阶段闭环交付 | §9、发布清单 |

> Codex 是当前唯一开放的 P0 adapter；Claude Code 和 Kimi 在各自配方、协议与真机验证完成前不可安装或启动。

---

## 14. 发布前剩余工作

1. 完成标准模式和移动聊天重构，并在小屏、深色、键盘和横屏截图下验收
2. 完成 `AgentRuntime` 零行为迁移和 EmbeddedProotRuntime ARM64 技术验证
3. 验证 v0.1.0 APK 数据升级、Termux 兼容用户迁移和 Runtime 更新/回滚
4. 在更多 OEM/Android 版本上完成 `docs/RELEASE_CHECKLIST.md` 的稳定版验收
5. 配置正式 release 签名，并在真机清单通过后发布稳定版

---

## 15. 已固定默认值

1. 第二个 CLI 预留 Claude Code，但默认不可用
2. 当前 TermuxRuntime 的 distro 名固定 `ubuntu`；EmbeddedProotRuntime 的 rootfs 版本由签名 manifest 固定
3. 默认工作区 `/root/projects/default`
4. 应用名 AgentDeck
