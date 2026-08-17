# 计划：pi / dsh（DeepSeek Harness）Runtime 接入

- 状态（2026-08-17）：**D1 dsh 安装 + WebView 已实现**；**D2/D3 pi 安装 + 原生 RPC 聊天已实现**（非终端壳）。
  代码主要在 **beta.10 标签之后的本地工作区**（见 `docs/HANDOFF.md`），勿假设 GitHub tag 已含。
  启动加速 / warm：`docs/plans/agent-startup-acceleration.md`。
- 通道：Secure 按需下载；宿主 L3/L4 仍只 Lab。
- 依据（官方）：
  - [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)：`npx @deepseek-ai/dsh web`，默认 `http://127.0.0.1:3080`
  - [dsh 中文 Web 指南](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/index.zh.md) / [模型配置](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/providers.zh.md)
  - [earendil-works/pi](https://github.com/earendil-works/pi) · npm `@earendil-works/pi-coding-agent`：interactive / print·JSON / **RPC** / SDK

## 先读结论

AgentDeck **Codex** 原生聊天栈 = **app-server WebSocket（Responses）**。  
**pi 和 dsh 都不是 Codex 协议**，不能塞进 `CodexRpcClient` 假装成第二条模型。

| CLI | 官方主形态 | 在 AgentDeck 里的产品形状（当前） |
| --- | --- | --- |
| **Codex** | `codex app-server` + 本机 WS | 原生时间线；仅 **Responses** 模型服务 |
| **dsh** | Node harness + **Web UI**（`dsh web`） | 按需装 Node 树 → loopback Web → **WebView** |
| **pi** | coding agent + **RPC** | 按需装 → `pi --mode rpc` → **`PiChatScreen` 原生壳**；模型来自 AgentDeck **Chat Completions** 配置 |

因此 V4 不是「再装一份 rootfs 当 Codex 用」，而是：

1. **运行时包**：`runtimes/deepseek-harness/`、`runtimes/pi/` 各自目录、各自删除、不伤 `codex-home`。
2. **启动器**：按 CLI 起进程、健康检查、停进程。
3. **UI 壳**：dsh → WebView；pi → 终端壳或后续 RPC 消息面。
4. **模型密钥**：各自走自己的凭据体系，**不要**把 dsh/pi 密钥写进 Codex `config.toml`。

## 非目标（首版）

- 不把 dsh/pi 的消息正文并进 Codex rollout。
- 不在 Secure 里为 dsh/pi 打开 Lab 无障碍 / 本地 MCP L3+。
- 不把 Node 全家桶打进 APK；继续按需下载。
- 不要求用户装 Termux。
- 不做「用 Codex 去驱动 dsh/pi」的伪兼容层。

## dsh（Web 版）怎么做

### 官方行为（锁定）

```sh
npx @deepseek-ai/dsh web
# 默认 Web UI：http://127.0.0.1:3080
```

- 开发者预览，**会有破坏性变更**。
- 模型在 Web **设置 → 模型**；密钥进 `$DSH_HOME/.credentials.yaml`，settings 只留引用。
- 自定义 OpenAI 兼容端点走 dsh 自己的 Provider 表单 / `settings.yaml`（`api: openai-completions` 等），**不是** Codex `wire_api`。

### AgentDeck 分层

```text
用户点「准备 DeepSeek Harness」
  → 下载/解压到 runtimes/deepseek-harness/  (Node + dsh 发行物，校验 sha256)
  → 状态：已就绪 / 可删除

用户点「打开 dsh」
  → 绑定 DSH_HOME 到共享用户侧目录（建议 runtime 根下 dsh-home，勿放进 Codex rootfs）
  → 在 cli 树内启动：node ... dsh web --host 127.0.0.1 --port <分配端口>
  → 健康检查 GET http://127.0.0.1:<port>/
  → Compose WebView（仅 loopback）加载 UI
  → 进程挂在前台服务；离开页可保留或按策略停
```

### 安装包形态（建议）

| 项 | 建议 |
| --- | --- |
| 制品 | 官方 npm 包 `@deepseek-ai/dsh` + 固定 Node 运行时（与 ABI 匹配的预编译 Node，或复用 Ubuntu rootfs 内 node——二选一，**首版更稳的是独立 Node 二进制进 `runtimes/deepseek-harness/`**） |
| 校验 | releaseId、sha256、size；安装后 `dsh --version` 或等价 |
| 磁盘 | 单独 `usedBytes()`；删除只清 `runtimes/deepseek-harness` + 可选询问是否清 `dsh-home` |
| 网络 | 首次 `npx`/拉包需 HTTPS；离线包可后做 |

### WebView 安全

- 只允许 `http://127.0.0.1` / `http://localhost`。
- 禁止 file/content 任意跳转；不注入用户密钥到 URL。
- 不把 WebView Cookie 当 AgentDeck 备份内容。

### 与 Codex 的关系

- 会话列表里 dsh 任务 **独立卡片类型**（`recipe_deepseek_harness`），不进 `CodexRpcClient`。
- 模型连接页可以提示：「dsh 在 Web 内配置密钥」，避免用户把 dsh Key 误存成 Codex Provider。

## pi 怎么做

### 官方行为（锁定）

```bash
npm install -g --ignore-scripts @earendil-works/pi-coding-agent
export ANTHROPIC_API_KEY=...   # 或 /login
pi
```

模式：interactive · print/JSON · **RPC（进程集成）** · SDK。  
官方明确：**无内置权限沙箱**，与启动用户同权；需要边界就 containerize。  
有 [Termux 文档](https://github.com/earendil-works/pi/tree/main/packages/coding-agent/docs)——说明 Android 侧有社区路径，但 AgentDeck 仍应用 **私有 Runtime**，不依赖用户 Termux。

### AgentDeck 分层（MVP → 加分）

**MVP（推荐先做）**

```text
准备 pi → runtimes/pi/ 放 Node 或独立 pi 二进制 + 依赖
打开 pi → PTY/伪终端 Activity（或复用现有 console 能力）跑 `pi`
密钥：环境变量由 AgentDeck Keystore → 仅注入该进程 env（不写进日志）
```

**加分（真·App 集成）**

```text
pi RPC 模式 ←→ Android 侧薄客户端
  会话状态在 pi 进程
  UI 只做输入/工具审批展示
```

不要第一天就做「把 pi 事件映射成 Codex timeline」——协议不同，成本高、易假完成。

### 与 dsh 的共同点

- 都要 Node 系运行时 → **可抽共享 `runtimes/_node/<version>/`**，但 **cli 包仍分目录**，删除策略清晰。
- 都不要占用 Codex 首次 116MB 下载路径。

## 目录与 Catalog 改动清单

### 已实现（对照代码，非仅 placeholder）

- `RuntimeCliCatalog`：`deepseek-harness` / `pi` + `RuntimeLayoutContract`
- `DshRuntimeInstaller` / `PiRuntimeInstaller`（版本钉死在各 `*RuntimeManifest`）
- `DshRuntimePaths` / `PiRuntimePaths`（含 `.node-compile-cache`）
- `RuntimeInventory`：状态 / 删除 / 打开 dsh
- `DshWebSupervisor`、`PiRpcSession`、`PiProviderConfig`
- UI：运行环境准备/删除；dsh WebView；pi 走会话 → `PiChatScreen`
- 模型：AgentDeck **Chat Completions** profile → pi `models.json`；密钥 vault + env
- 热态：`NativeRuntimeBudget`（离开聊天不杀；压力 reclaim）

### 仍待 / 可选

1. recipe `available` 与「新建会话」产品开关对齐（避免误导用户建不可用 recipe）。
2. 备份元数据「是否装过某 CLI」；**永不**导出 dsh/pi 密钥文件。
3. pi 磁盘 session（去掉或可选 `--no-session`）— 见启动加速 P1。
4. dsh 工作区与 `projects/` 深度对齐。
5. 正式 release 说明与卸载文案（D4）。

## 分期

| 阶段 | 交付 | 状态 |
| --- | --- | --- |
| D0 | catalog 文案 | **完成** |
| D1 | dsh 下载 + WebView | **完成**（真机冷装随用户设备） |
| D2 | pi 下载 + 冒烟 | **完成**（安装器 + `--help`） |
| D3 | pi RPC 原生壳 | **完成**（`PiChatScreen`，非终端） |
| D4 | 备份/卸载文案 | **部分** / 待打磨 |

## 风险

| 风险 | 处理 |
| --- | --- |
| dsh developer preview 破变更 | 钉 npm 版本 + releaseId；升级单独立项 |
| Node 体积与 Android ABI | 只 arm64 先；x86_64 后补 |
| 密钥散落 `DSH_HOME` / pi env | 文档写清；备份排除 credentials |
| 用户以为能在 Codex 聊天里选 dsh 模型 | UI 明确分产品面 |
| 把 chat-completions 网关配进 Codex | 见 `docs` 中 dots/400 说明；与本计划独立 |

## 和 dots 400 的关系

dots / 多数国产兼容网关是 **Chat Completions**。  
Codex 0.147 + AgentDeck 受管 **Responses** Provider **固定 `wire_api=responses`** → 硬配 dots 会 HTTP 400（见 `docs/plans/codex-dots-http-400.md`）。

**当前正确用法（已实现）：**

1. 设置 → 模型服务 → 新增 **Chat Completions**（不是 Responses）。
2. 新建/打开 **pi** 会话并绑定该配置 + 模型（如 `dots3-note-prev`）。
3. 或使用 **dsh Web** 在其自身设置里配 openai-completions。

**不要**为 dots 把 Codex Managed Provider 改成 `wire_api=chat`（与 ADR-0007 冲突）。
