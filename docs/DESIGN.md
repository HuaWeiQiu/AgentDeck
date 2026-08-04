# AgentDeck 设计文档（MVP）

> 状态：设计已定稿；**Android 骨架已落地**（`android/`，debug 可编译）  
> 平台：Android  
> 形态：**A. 轻量启动器**（App 管配置/安装/卡片；会话跑在 Termux）  
> 运行时：**必须安装 Termux**  
> 交付顺序：文档 ✅ → 工程骨架 ✅ → 真机联调 / P1 功能  

---

## 1. 已确认的产品决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 产品形态 | **A 轻量启动器**：不自建完整 Agent 推理循环；用 Termux 跑真 CLI |
| 2 | 运行时 | **必须 Termux**（推荐 F-Droid 签名版） |
| 3 | 首批 CLI | **以 Codex 为 P0**；Claude Code / Kimi CLI 同模型扩展（P1） |
| 4 | 主交互 | 首页 **Agent 卡片** → 点击进入 **CLI 聊天界面**（Codex TUI 本身） |
| 4b | 启动链 | 不是单条命令：需 **先进入 Ubuntu（proot-distro）再执行 `codex`** |
| 5 | 模型 | 暂只做 **OpenAI 兼容** + **Anthropic** |
| 6 | 交付 | 本文档 → 下一步 Android 工程骨架 |

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
      → 注入 API 环境变量 / 配置
      → codex                    # 进入 Codex 聊天 TUI
```

因此「预设命令」必须支持 **多步 / 包装脚本**，不能只有一个 `codex` 字符串。

---

## 2. 产品目标与非目标

### 2.1 目标（MVP）

- 用卡片管理多个 Agent 启动配置  
- 一键启动：**按预设链路进入 Codex（及后续其它 CLI）聊天会话**  
- 在 App 内配置：  
  - OpenAI 兼容（Base URL + API Key + Model）  
  - Anthropic（API Key + 可选 Base URL + Model）  
- 能检测/引导安装：Termux、proot-distro、Ubuntu、Node、Codex CLI 等依赖  
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
        [ ] ubuntu 内 node/npm 可用（若 Codex 需要）
        [ ] codex 已安装且在 PATH
  → 配置模型 Profile（OpenAI 兼容 / Anthropic）
  → 创建默认卡片「Codex」
  → 点击卡片 → 进入 Codex 聊天会话
```

### 3.2 日常使用

```text
打开 App 首页
  → 看到卡片：Codex / Claude / Kimi / 自定义
  → 点「Codex」
  → App 通过 Termux 启动命名会话，执行 wrapper
  → 用户落在 Codex TUI（聊天界面）
  → 返回 App 可再开另一会话 / 改配置 / 装其它 CLI
```

### 3.3 安装 CLI

```text
商店 Tab
  → 选 Codex
  → 查看依赖图与安装脚本
  → 一键执行（在 Termux 中跑安装会话，展示日志）
  → 标记已安装版本
```

---

## 4. 信息架构（UI）

### 4.1 底部导航（4 Tab）

| Tab | 名称 | 作用 |
|---|---|---|
| 1 | **会话** | 卡片列表、一键进入 CLI 聊天、最近会话 |
| 2 | **商店** | 安装/更新/卸载 CLI 与依赖 |
| 3 | **模型** | OpenAI 兼容 + Anthropic Profile |
| 4 | **设置** | Termux 集成、环境向导、备份、关于 |

### 4.2 会话 Tab（主界面）

**卡片字段**

- 标题（如 `Codex · 默认项目`）  
- 副标题（绑定模型 / 工作区路径）  
- 状态徽章：`未就绪` / `可启动` / `运行中` / `安装中`  
- 主按钮：**进入**  
- 次级：编辑、复制、删除  

**点击「进入」后**

- MVP：拉起 Termux，附着到命名 session，执行启动包装脚本  
- 用户看到的是 **Codex 聊天 TUI**（不是 App 自绘 Chat）  
- 可选通知：「已启动 Codex 会话」  

**编辑卡片页**

- 名称、图标  
- 工作区目录（`cwd`，在 Ubuntu 内路径或 Termux 路径，需标明命名空间）  
- 绑定模型 Profile  
- 启动模板：`codex-ubuntu`（内置）或自定义  
- 高级：自定义 env、额外 args（如 `codex resume`）  

### 4.3 商店 Tab

内置配方（MVP）：

1. **环境基础**：proot-distro + ubuntu  
2. **Codex CLI**（P0）  
3. Claude Code（P1）  
4. Kimi CLI（P1）  

每张配方卡：描述、体积预估、依赖、安装/修复/卸载按钮、日志入口。

### 4.4 模型 Tab

- Profile 列表  
- 类型：`openai_compatible` | `anthropic`  
- 字段：名称、Base URL、API Key、默认 Model、测试连通  
- Key 仅存 Android Keystore 加密存储，不进明文备份（默认）  

### 4.5 设置 Tab

- Termux 安装/权限检测  
- 打开 Termux / 复制 `allow-external-apps` 配置命令  
- 默认 distro 名（`ubuntu`）  
- 默认登录用户  
- 导出/导入卡片与 Profile 元数据  
- 诊断信息（路径、会话名、最后错误）  

---

## 5. 关键概念与数据模型

### 5.1 实体

```text
ProviderProfile   模型配置（OpenAI 兼容 / Anthropic）
AgentRecipe       可安装的 CLI 配方（商店）
AgentCard         用户的一键启动卡片（会话入口）
LaunchTemplate    启动链模板（多步命令 / wrapper）
SessionRecord     本地记录：上次启动时间、session 名、状态
EnvironmentReport 环境检测结果
```

### 5.2 ProviderProfile

```yaml
id: prof_openai_main
name: OpenAI Compatible Main
type: openai_compatible   # or anthropic
base_url: "https://api.example.com/v1"
# api_key 不进 yaml；仅存安全存储，用 key_ref 关联
key_ref: "keystore:prof_openai_main"
default_model: "gpt-5"
headers: {}
created_at: "2026-08-04T00:00:00Z"
```

Anthropic 示例：

```yaml
id: prof_anthropic_main
name: Anthropic Main
type: anthropic
base_url: "https://api.anthropic.com"   # 可改中转
key_ref: "keystore:prof_anthropic_main"
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

**原则**：App 永远只触发 **一个** Termux `RUN_COMMAND` 入口；多步逻辑放在 **wrapper 脚本** 里，避免 Intent 传超长命令、引号地狱、状态难恢复。

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
  extra_env: {}
enabled: true
```

### 5.5 AgentRecipe（商店）

```yaml
id: recipe_codex
name: Codex CLI
description: OpenAI Codex CLI，运行在 proot Ubuntu 内
detect:
  # 在 ubuntu 内检测
  runtime: ubuntu
  commands:
    - "command -v codex"
version_command: "codex --version"
depends_on:
  - recipe_proot_ubuntu
  - recipe_ubuntu_node   # 若需要
install:
  runtime: ubuntu
  steps:
    - name: install_codex
      shell: |
        set -euo pipefail
        # 具体命令以官方当时文档为准，配方可热更新
        npm install -g @openai/codex || npm install -g codex
post_install:
  - write_wrapper: tpl_codex_ubuntu
uninstall:
  runtime: ubuntu
  shell: "npm uninstall -g @openai/codex 2>/dev/null || true"
```

### 5.6 环境检测项（EnvironmentReport）

| 检查 ID | 层级 | 命令/条件 | 失败时引导 |
|---|---|---|---|
| `termux_installed` | Android | 包名 `com.termux` 可解析 | 打开 F-Droid/下载页 |
| `termux_run_command` | Android | 权限/Intent 可达 | 设置说明 |
| `allow_external_apps` | Termux | 读 properties 或试跑 | 一键复制修复命令 |
| `proot_distro` | Termux | `command -v proot-distro` | 商店安装基础环境 |
| `ubuntu_installed` | Termux | `proot-distro list` 含 ubuntu | 安装 ubuntu |
| `codex_installed` | Ubuntu | `command -v codex` | 安装 Codex 配方 |
| `profile_bound` | App | 卡片绑定有效 Profile | 去模型页 |

---

## 6. 启动链路（详细）

### 6.1 Wrapper 脚本（安装配方时写入 Termux）

路径：`$PREFIX/../home/.agentdeck/wrappers/codex-ubuntu.sh`  
（即 Termux `~/.agentdeck/wrappers/codex-ubuntu.sh`）

```bash
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# 由 App 在启动前通过 env 或同目录 .env 文件注入
# AGENTDECK_DISTRO, AGENTDECK_INNER_CWD, AGENTDECK_INNER_BIN
# AGENTDECK_OPENAI_API_KEY, AGENTDECK_OPENAI_BASE_URL, AGENTDECK_MODEL ...

DISTRO="${AGENTDECK_DISTRO:-ubuntu}"
INNER_CWD="${AGENTDECK_INNER_CWD:-/root}"
INNER_BIN="${AGENTDECK_INNER_BIN:-codex}"

# 把密钥文件打进 distro 临时 env（避免出现在 ps 参数里的可选优化）
ENV_EXPORTS=()
if [[ -n "${AGENTDECK_OPENAI_API_KEY:-}" ]]; then
  ENV_EXPORTS+=("export OPENAI_API_KEY=$(printf %q "$AGENTDECK_OPENAI_API_KEY")")
fi
if [[ -n "${AGENTDECK_OPENAI_BASE_URL:-}" ]]; then
  ENV_EXPORTS+=("export OPENAI_BASE_URL=$(printf %q "$AGENTDECK_OPENAI_BASE_URL")")
fi
if [[ -n "${AGENTDECK_MODEL:-}" ]]; then
  # 具体变量名按 Codex 实际支持再映射
  ENV_EXPORTS+=("export OPENAI_MODEL=$(printf %q "$AGENTDECK_MODEL")")
fi

INNER_SCRIPT=$(cat <<EOF
set -euo pipefail
$(printf '%s\n' "${ENV_EXPORTS[@]}")
cd $(printf %q "$INNER_CWD")
exec $(printf %q "$INNER_BIN") "\$@"
EOF
)

exec proot-distro login "$DISTRO" -- bash -lc "$INNER_SCRIPT" -- "$@"
```

### 6.2 App 侧启动步骤

```text
1. 校验卡片 → 模板 → Profile → 环境检测
2. 从 Keystore 取 api key（仅内存）
3. 组装 env map（OPENAI_* / ANTHROPIC_*）
4. 写临时 env 文件到 Termux 可读位置（可选）或通过 RUN_COMMAND 传环境
5. 发送 Termux Intent:
   - session name = card.termux_session_name
   - command = bash ~/.agentdeck/wrappers/codex-ubuntu.sh
   - working dir = Termux home
   - background = false（前台会话）
6. 拉起 Termux UI（用户进入 Codex 聊天界面）
7. 本地 SessionRecord 记一次启动
```

### 6.3 Termux Intent 约定（实现时核对官方文档）

- Action：`com.termux.RUN_COMMAND`  
- Extra：  
  - `com.termux.RUN_COMMAND_PATH`  
  - `com.termux.RUN_COMMAND_ARGUMENTS`  
  - `com.termux.RUN_COMMAND_WORKDIR`  
  - `com.termux.RUN_COMMAND_SESSION_ACTION` / session 相关 extra（以 termux-app 当前版为准）  
- 权限：`com.termux.permission.RUN_COMMAND`  
- 前置：`~/.termux/termux.properties` 中 `allow-external-apps=true`  

> 实现骨架阶段用真机对一下 extra 名称与 session 复用行为。

### 6.4 为什么必须 wrapper

| 问题 | 直接拼长命令 | wrapper |
|---|---|---|
| `proot-distro login` + 内层 `codex` | 引号/换行易炸 | 稳定 |
| 注入 API Key | 易进进程列表/日志 | 可改读文件 |
| 多发行版/多项目 | 难复用 | 模板参数化 |
| 安装后修复 | 难 | 重写 wrapper 即可 |

---

## 7. 模型配置如何作用到 CLI

MVP 策略：**启动时注入环境变量 + 可选写 CLI 配置文件**。

### 7.1 OpenAI 兼容 → Codex

| Profile 字段 | 注入 |
|---|---|
| api_key | `OPENAI_API_KEY` |
| base_url | `OPENAI_BASE_URL` |
| default_model | 按 Codex 支持的环境变量/config 映射（实现时查当前 Codex 文档） |

可选：维护 `~/.codex/config.toml` 片段（在 ubuntu 内）。

### 7.2 Anthropic → Claude Code（P1）

| Profile 字段 | 注入 |
|---|---|
| api_key | `ANTHROPIC_API_KEY` |
| base_url | `ANTHROPIC_BASE_URL` |
| default_model | Claude 配置文件或参数 |

### 7.3 OpenAI 兼容 / 官方 → Kimi CLI（P1）

按 Kimi Code 的 `config.toml` / 环境变量映射（实现时以官方文档为准，不在此臆造键名）。

### 7.4 安全

- Key 存 `EncryptedSharedPreferences` 或等价，主密钥在 Android Keystore  
- 日志默认脱敏  
- 导出配置默认不含 secret  
- 临时 env 文件权限 `600`，会话结束可删（P1）  

---

## 8. 模块架构（App）

```text
app/
  ui/
    sessions/     卡片列表、编辑、启动
    store/        配方安装与日志
    models/       ProviderProfile CRUD + 测试
    settings/     环境向导、权限
  domain/
    model/        数据类
    repo/         CardRepo / ProfileRepo / RecipeRepo
    launch/       LaunchInteractor（检测→注入→Intent）
    env/          EnvironmentProbe
    install/      RecipeInstaller
  data/
    db/           Room
    secure/       Key storage
    termux/       TermuxGateway（Intent / 探测）
    fs/           通过 Termux 写 wrapper（RUN_COMMAND 或 shared）
  recipes/        assets 内置 YAML
```

### 8.1 关键接口（骨架期）

```kotlin
interface TermuxGateway {
  fun isTermuxInstalled(): Boolean
  fun hasRunCommandPermission(): Boolean
  suspend fun runCommand(
    sessionName: String,
    executable: String,
    args: List<String>,
    workDir: String?,
    env: Map<String, String>,
  ): Result<Unit>
  suspend fun ensureAgentDeckHome(): Result<Unit>
}

interface EnvironmentProbe {
  suspend fun scan(): EnvironmentReport
}

interface LaunchInteractor {
  suspend fun launch(cardId: String): Result<Unit>
}

interface RecipeInstaller {
  suspend fun install(recipeId: String, onLog: (String) -> Unit): Result<Unit>
}
```

---

## 9. MVP 范围拆分

### P0（第一期骨架 + 可演示）

1. Compose 四 Tab 空壳 + 导航  
2. Room：Card / Profile  
3. 模型页：OpenAI 兼容 + Anthropic 的增删改、Key 安全存储  
4. 会话页：默认 Codex 卡片、编辑 cwd/profile  
5. TermuxGateway：检测 + `RUN_COMMAND` 启动 wrapper  
6. 内置 `codex-ubuntu` 模板与示例 wrapper 内容  
7. 环境向导页（检查清单 + 复制修复命令）  
8. 设计文档与配方 YAML 入库  

### P1

1. 商店：真实安装 Codex / Ubuntu / Node 脚本  
2. Claude Code、Kimi CLI 卡片与配方  
3. 安装日志 UI  
4. 会话记录与「再次进入」  
5. 连通性测试  
6. 配置导入导出  

### P2

1. 内嵌终端（形态 B 能力）  
2. 桌面 Widget  
3. 远程配方订阅  
4. 更稳的 daemon 通信（替代纯 Intent）  

---

## 10. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| Termux 签名不一致（GitHub vs F-Droid） | RUN_COMMAND 失败 | 文档强制 F-Droid；检测包签名 |
| `allow-external-apps` 未开 | 无法启动 | 向导一键说明 + 复制命令 |
| proot Ubuntu 与 Termux 路径混淆 | cd 失败 | 路径命名空间 `ubuntu` / `termux` |
| CLI 安装包名/参数变更 | 配方失效 | YAML 配方可更新，不写死在 Kotlin |
| API Key 出现在进程参数 | 泄露 | wrapper 读文件；减少 extras 明文 |
| Codex 配置键变更 | 模型不生效 | ConfigMapper 隔离 + 版本备注 |
| 后台被杀 | 会话断 | 依赖 Termux 自身会话；App 不保活 CLI |

---

## 11. 目录规划（仓库）

```text
AgentDeck/
  docs/
    DESIGN.md              # 本文
    TERMUX_INTEGRATION.md  # Intent/权限细节（骨架时补）
    ROADMAP.md             # 可选
  recipes/
    base-ubuntu.yaml
    codex.yaml
    claude-code.yaml       # P1
    kimi-cli.yaml          # P1
  schemas/
    provider-profile.schema.json
    agent-card.schema.json
    agent-recipe.schema.json
  wrappers/
    codex-ubuntu.sh        # 模板，安装时写入 Termux
  android/                 # 下一步工程骨架
    app/
    ...
  README.md
```

---

## 12. 验收标准（MVP Demo）

在已安装 Termux + ubuntu + codex 的真机上：

1. 打开 AgentDeck，模型页保存一个 OpenAI 兼容 Profile  
2. 会话页 Codex 卡片显示「可启动」  
3. 点击 **进入** → 自动打开 Termux 会话  
4. 会话内自动完成 ubuntu 登录并进入 **Codex 聊天界面**  
5. Codex 能读到注入的 Base URL / Key（可完成一次最小请求或 `/status` 类自检）  
6. 未装 Termux 时，向导给出明确阻塞原因而非崩溃  

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
| 6 → 先文档再骨架 | 本文；下一步 `android/` 工程 |

> 关于「3.2」：按你后文只展开 Codex + Ubuntu 链路，将 **Codex 定为 P0**；若你本意是「只要 2 个 CLI」，默认 P1 只加 **Claude Code**（或你指定的第二个）。骨架阶段可再改配方列表。

---

## 14. 下一步（骨架阶段将做的事）

1. 初始化 Android 工程（Kotlin + Compose + Navigation + Room）  
2. 落地四 Tab 与假数据卡片  
3. 实现 `TermuxGateway` 与环境检测  
4. 把 `wrappers/codex-ubuntu.sh` 与 `recipes/*.yaml` 接到安装/启动流程  
5. 补 `docs/TERMUX_INTEGRATION.md`（对照真机 Intent 行为）  

---

## 15. 待你骨架前可再确认的小项（不挡写骨架）

1. 第二个 CLI 优先 Claude 还是 Kimi？  
2. Ubuntu distro 名是否固定 `ubuntu`？  
3. 工作区默认路径是否用 `/root/projects`？  
4. App 显示名是否就用 **AgentDeck**？  

无回复则骨架采用：第二个预留 Claude；distro=`ubuntu`；cwd=`/root/projects/default`；应用名 AgentDeck。
