# AgentDeck 开发交接

- 更新时间：2026-08-17（Asia/Shanghai）— **第二轮**：pi/dsh 原生路径、模型服务 Chat Completions、warm keep-alive、启动加速 P0
- 主仓库：`HuaWeiQiu/AgentDeck`
- 主分支：`main`
- 已发布版本：`v0.2.0-beta.11`（`versionCode=16`，标签 `ba8067a`）
- 远端 `origin/main` 末条已知：`f50aa50`（Runtime 迁到 `runtimes/codex/`）
- **本地工作区**：在 `f50aa50` 之上有大量**未提交** Secure 改动（pi/dsh、Chat Completions、内存/启动优化等）。接手时先 `git status` / `git diff`，**不要**假设远端已包含下列能力。
- 上一发布：`v0.2.0-beta.9`，标签 `38b5965`（不要移动）
- 当前 Secure 包名仍为 `com.agentdeck.app.debug`；优先 **Secure only**，不主动扩 Lab。

## 先读结论

1. `main` 是唯一开发事实源。Secure/Lab 是同一工程 product flavor；不要从停在 beta.7 的
   `origin/channel/secure` / `channel/lab` 继续开发。
2. 已发布预发布目标 **beta.11**（`versionCode=16`）。不要移动 beta.8–10 标签或替换其资产。
3. **聊天正文唯一持久化事实源**：Codex = app-server rollout；**禁止 Room 双写消息正文**。
   pi 会话当前以进程/本地 pi-home 为主，**不要**并进 Codex rollout。
4. **Runtime 体积拆分 V1–V3 已在远端落地**；本地又推进了 **pi / dsh 真接入**（见下），不再是
   「设置里仅显示即将支持」。
5. **模型服务双协议**（本地未提交为主）：
   - **Responses** → 仅 Codex 原生聊天（ADR-0007 / `wire_api=responses`）。
   - **Chat Completions** → pi / dsh（如小红书 dots）；`ProviderAdapterId.OPENAI_CHAT_COMPLETIONS`。
   - 密钥统一 `ProviderCredentialVault`；**不要**把 dsh/pi 密钥写进 Codex `config.toml`。
6. **Agent 热态与启动加速**（见 `docs/plans/agent-startup-acceleration.md`）：
   - **P0+P1 已落地**：离开聊天不杀 agent；压力才 reclaim；Codex `hold`；pi binding /
     `set_model` 热切换；`NODE_COMPILE_CACHE` + prewarm；列表 press 预热；
     Codex/pi 磁盘预览与气泡历史；模型服务 **轻量试聊**（无 PRoot Chat Completions）。
   - **未做（P2）**：erofs 镜像、`:agent` 多进程、评估去掉 largeHeap。
7. **消费级 1–3 / Lab U0–U4 / Runtime 契约** 仍有效；阶段 4 正式签名、Lab U5 未做。
8. **禁止 kill 正在跑的 `connectedAndroidTest`/macrobenchmark**（会卸包装用户数据）。

## 文档索引（本轮对齐）

| 文档 | 用途 |
| --- | --- |
| `docs/plans/agent-startup-acceleration.md` | Agent 冷/温启动、warm、compile cache、P0–P2 |
| `docs/CHAT_PERFORMANCE.md` | 聊天 UI 时间线 / Markdown / 滑动基线 |
| `docs/plans/runtime-pi-dsh.md` | pi / dsh 接入状态（D1–D3 已实现） |
| `docs/plans/runtime-footprint.md` | Runtime 磁盘分目录 |
| `docs/plans/codex-dots-http-400.md` | dots 与 Responses 400；改走 Chat Completions + pi |
| `docs/ADR-0007-MANAGED-MODEL-PROVIDERS.md` | 受管密钥；Responses vs Chat Completions 边界 |
| `docs/plans/product-completion.md` | 消费级 1–4 |
| `docs/plans/lab-ui-agent.md` | Lab only |

## 当前产品形态

| 能力 | Secure 行为 |
| --- | --- |
| Codex | PRoot + `codex app-server` + 原生时间线；Responses 模型服务或 ChatGPT 登录 |
| pi | 设置安装 Node 树；**原生 `PiChatScreen` + `pi --mode rpc`**；绑定 **Chat Completions** 模型服务 |
| dsh | 设置安装；**WebView** 打开 loopback `dsh web` |
| 配方 | `recipes/deepseek-harness.yaml` / `pi`：产品上可安装，会话 recipe 是否 `available` 以仓库文件为准（常为 false，避免误新建） |
| Lab | 独立 flavor；UI Agent 等永不进 Secure APK |

| 通道 | applicationId | 当前验收 |
| --- | --- | --- |
| Secure Beta | `com.agentdeck.app.debug` | V2301A 等 ARM64 真机主路径 |
| Lab Beta | `com.agentdeck.app.lab.debug` | 构建/隔离；U5 真机未做 |

Beta：测试签名、R8、Baseline Profile；非正式商店包。

## 源码树（接手先认路）

```text
AgentDeck/
├── android/app/src/
│   ├── main/          # chat / runtime / extensions / backup / settings / pi·dsh
│   ├── secure/ · lab/
│   └── test/ · androidTest/
├── recipes/ · wrappers/ · scripts/
└── docs/              # HANDOFF、ADR、plans、releases
```

| 层 | 职责 |
| --- | --- |
| `ui/chat` | Codex 时间线、`PiChatScreen`、SharedChatMarkdown、ViewModel |
| `ui/sessions` · `ui/models` · `ui/settings` | 会话、模型服务（Responses / Chat 芯片）、运行环境 |
| `data/chat` | Codex RPC、`ChatSessionRegistry` hold/reattach |
| `data/runtime` | PRoot、Codex/pi/dsh 路径与安装、`NativeRuntimeBudget`、`NodeStartupSupport`、`RuntimeNodePrewarm` |
| `data/provider` | 模型发现；Chat Completions 与 Responses 分流 |
| `domain/runtime` | `RuntimeCliCatalog`、`RuntimeLayoutContract` |
| `domain/model` | `ProviderAdapterId`（含 `OPENAI_CHAT_COMPLETIONS`） |

## 最近完成的工作（相对 beta.10 / 旧 HANDOFF）

### A. 模型服务：Chat Completions 一等公民（本地）

- `OPENAI_CHAT_COMPLETIONS` adapter；UI 短标签「Chat」；dots 默认 URL/模型可填。
- Codex 启动路径**拒绝** Chat Completions 配置（避免再打 `/v1/responses` 400）。
- pi：`PiProviderConfig` 把 profile 投影到 `pi-home/.pi/agent/models.json`（`api: openai-completions`），
  密钥 env `AGENTDECK_PI_API_KEY`。
- 说明：历史「小红书」若仍存成 Responses，需新建 **Chat Completions** 配置给 pi。
- 相关：`docs/plans/codex-dots-http-400.md`、`docs/ADR-0007-MANAGED-MODEL-PROVIDERS.md`（Responses 仍只服务 Codex）。

### B. pi / dsh Runtime（本地，远超旧「V4 未做」表述）

- 安装：`PiRuntimeInstaller` / `DshRuntimeInstaller`（可复用 dsh Node 给 pi）。
- 路径单例：`EmbeddedRuntimePaths.shared` / `PiRuntimePaths.shared` / `DshRuntimePaths.shared`。
- pi：`PiRpcSession` + `PiChatScreen`（壳对齐 Codex：顶栏模型胶囊、底栏、Shared Markdown）。
- dsh：`DshWebSupervisor` + WebView；Node `--max-old-space-size=160`、`UV_THREADPOOL_SIZE=1`。
- 计划文档：`docs/plans/runtime-pi-dsh.md`（状态需按本交接理解，已超越 D0 占位）。

### C. 内存与 warm keep-alive（本地）

| 策略 | 行为 |
| --- | --- |
| 离开 pi/Codex 聊天 | **不**杀进程；Codex 健康连接 `ChatSessionRegistry.hold` |
| `MainActivity.onStop` | **不再**无条件停 pi/dsh |
| `onTrimMemory` | 压力档才 `releaseAllIdleSessions` + `NativeRuntimeBudget.reclaimForMemoryPressure` |
| Codex idle | 约 30 min 量级 teardown；最多约 3 个 idle held |
| hold 条件 | **已连接且安全**即可 keep（不限于 streaming） |
| 时间线窗口 | 约 5 页 / 2MB 字符；预览约 60 条 / 128 KiB |
| Markdown LRU | 约 10–18 条 / 4–12 MiB（随 memoryClass）；`SharedChatMarkdown` 进程级复用 |
| 后台 buffer | held session `bufferedItems`≤80、`bufferedTurns`≤4 |
| OkHttp | Codex WS / 模型发现小 `ConnectionPool` |

真机（V2301A）曾验证：离开 Codex 后 `codex` PID 可保持；再进 reattach。列表静置 PSS 约 90MB 量级、Codex 打开约 200MB PSS（含 app-server，属能力成本）。

### D. 启动加速 P0+P1（本地）

见 **`docs/plans/agent-startup-acceleration.md`**（权威）：

- `NODE_COMPILE_CACHE` → `runtimes/<cli>/.node-compile-cache`
- 安装 smoke + `RuntimeNodePrewarm`（`ServiceLocator.warmUp`）
- pi/Codex 就绪探测指数退避；pi 配置 skip-if-unchanged
- `SessionAgentPrefetch` 列表按压预热 pi / 探测 Codex hold
- `DiskTranscriptPreviewStore` + `PiChatHistoryStore`
- `ChatCompletionsClient` / `LightChatScreen`（设置 → 模型服务 → Chat 配置旁「轻量试聊」）
- **会话模式**：`ConversationMode` 轻聊 / 开发，见 **`docs/plans/conversation-modes.md`**。
  对话顶栏一点切换；设置无独立模式项；列表严格按模式；设置入口按模式显隐；
  模型页文案随模式。**狂暴**仅 Lab flavor。

P2 未做：erofs、多进程、largeHeap 评估。

### E. 仍有效的更早能力（已在 beta.10 及之前）

- Runtime V1–V3 + `RuntimeLayoutContract`（`f50aa50`）
- 消费级 1–3、备份（无密钥/无正文）
- Lab U0–U4、受管扩展 ADR-0013、聊天性能 Phase 2、handoff fence

## 性能与内存文档

| 文档 | 内容 |
| --- | --- |
| `docs/CHAT_PERFORMANCE.md` | UI 时间线 / 滑动 / gfx；**启动见 acceleration 计划** |
| `docs/plans/agent-startup-acceleration.md` | 冷/温启动、warm、compile cache、P0–P2 |
| `docs/plans/runtime-footprint.md` | 磁盘 CLI 拆分；cache 目录随 CLI 删除 |

剩余真机项：固定脚本采改造后基线；低内存 trim 行为回归；**勿 kill connectedAndroidTest**。

## 下一位接手者的第一批操作

```bash
cd /Users/tanye/AgentDeck   # 或你的 clone
git fetch origin
git status --short --branch
git log -8 --oneline --decorate
# 工作区很可能很脏：先分清「已推送 f50aa50」vs「本机未提交的 Secure 增强」

export JAVA_HOME="/path/to/jdk-17"   # 本机常用 Homebrew openjdk@17
cd android && ./gradlew :app:assembleSecureBeta
# 单元测试示例：
# ./gradlew :app:testSecureDebugUnitTest --tests 'com.agentdeck.app.data.runtime.NodeStartupSupportTest'
# ./gradlew :app:testSecureDebugUnitTest --tests 'com.agentdeck.app.ui.chat.ChatRecoveryTest'

# 有设备时：
# adb install -r -d -g app/build/outputs/apk/secure/beta/app-secure-arm64-v8a-beta.apk
# logcat 过滤：RuntimeNodePrewarm · NativeRuntimeBudget
```

建议优先级：

1. **提交或分拣本地改动**（模型服务 / pi·dsh / 内存·启动），避免交接丢失。
2. 真机装 Secure Beta：Codex/pi 二次进入、trim 后恢复、Chat Completions 绑 pi。
3. P1 启动加速或消费级阶段 4 方案（签名），二选一主线，勿并行发散 Lab。

## 关键文件

| 责任 | 文件 |
| --- | --- |
| Codex ViewModel / hold | `ui/chat/ChatViewModel.kt` · `data/chat/ChatSessionRegistry.kt` |
| pi 原生聊天 | `ui/chat/PiChatScreen.kt` · `data/runtime/PiRpcSession.kt` · `PiProviderConfig.kt` |
| 热态预算 | `data/runtime/NativeRuntimeBudget.kt` |
| Node 启动加速 | `data/runtime/NodeStartupSupport.kt` · `RuntimeNodePrewarm.kt` |
| 共享 Markdown | `ui/chat/SharedChatMarkdown.kt` · `ChatMarkdownUi.kt` |
| 模型服务 UI | `ui/models/ModelsScreen.kt` · `ModelsViewModel.kt` · `domain/model/Models.kt` |
| dsh Web | `data/runtime/DshWebSupervisor.kt` · 安装器 `DshRuntimeInstaller.kt` |
| 路径单例 | `EmbeddedRuntimePaths` · `PiRuntimePaths` · `DshRuntimePaths` |
| 启动加速计划 | `docs/plans/agent-startup-acceleration.md` |
| pi/dsh 计划 | `docs/plans/runtime-pi-dsh.md` |
| dots / Responses | `docs/plans/codex-dots-http-400.md` · ADR-0007 |
| 聊天 UI 性能 | `docs/CHAT_PERFORMANCE.md` |
| Runtime 体积 | `docs/plans/runtime-footprint.md` |
| 发布门禁 | `scripts/verify-release.sh` · `verify-stability-matrix.sh` |

## 明确的已知边界

- 本地增强**尚未**打进已发布 beta.10 tag；用户设备若只装商店/旧 beta，没有 pi Chat Completions。
- 正式签名 / 去 `.debug` 未做。
- x86_64 全路径未闭环；多 OEM / 低内存 soak 仍是稳定版阻塞。
- pi 仍 `--no-session`（进程级）；磁盘 session 在 acceleration P1。
- CRIU / 重度多进程 **不做**为当前加速主路径。
- dsh 为 developer preview，版本钉死在安装器 manifest。

## 不要破坏的约束

- 不回滚用户本地未提交工作；先理解 `git status`。
- 不让 Secure 带上 Lab L3/L4；不双写聊天正文到 Room。
- 不把 Key / Bearer / capability / 正文 / 附件路径打进日志与 CI Artifact。
- dsh/pi 密钥不进 Codex `config.toml`；Chat Completions 不用于 Codex bridge。
- 删除任一 CLI runtime **不得**删 `codex-home` / `projects` / 扩展快照 / Room 会话人设；
  Node compile cache 在对应 `runtimes/<cli>/` 下，随 CLI 删除一并清理即可。
- 不在新 owner 接管事件流时直接取消旧 Channel collector；必须 handoff fence。
- **禁止 kill `connectedAndroidTest`/macrobenchmark** gradle 任务。
