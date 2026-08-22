# 计划：Agent 启动加速（不全靠常驻内存）

- 状态：**P0 + P1 已实现**（compile cache / prewarm / 探测退避 / warm keep-alive；列表 press 预热；Codex 磁盘 transcript 预览；pi UI 历史落盘 + set_model 热切换；无 PRoot 轻量 Chat Completions 试聊）。P2（erofs、多进程、largeHeap 评估）未开工。
- 适用：Secure 主路径（Codex 原生聊天 / pi RPC / dsh Web）；Lab 不单独分叉策略。
- 动机：用户明确要求——**同一 App 进程生命周期内，首次加载后再次进入聊天要快**；同时接受「不必永远占内存」，希望探索**磁盘缓存、预热、更轻路径**等技术，而不是只靠杀/不杀进程。
- 相关文档：
  - `docs/CHAT_PERFORMANCE.md` — 聊天 UI 时间线 / Markdown 基线
  - `docs/plans/runtime-footprint.md` — 磁盘与 CLI 拆分
  - `docs/plans/runtime-pi-dsh.md` — pi / dsh 接入
  - `docs/ADR-0005-NATIVE-CHAT-BRIDGE.md` — Codex bridge
  - `docs/ADR-0009-EMBEDDED-LOCAL-RUNTIME.md` — 内嵌 PRoot Runtime

## 产品原则

1. **首次打开允许慢**（PRoot + Node + app-server/RPC 冷启动成本客观存在）。
2. **App 未被系统杀死时**，再次进入已加载过的 agent 应尽量快（进程 warm 或磁盘温启动）。
3. **离开聊天页本身不是杀进程的理由**；回收优先发生在系统内存压力、用户强制停止、卸载。
4. **持久化事实源不变**：Codex 消息正文仍以 app-server rollout 为准，**禁止 Room 双写正文**。
5. **不把 CRIU 进程检查点当作手机 App 产品路径**（非 root、PRoot 嵌套下不可用）。

## 冷启动成本账本（当前架构）

| 阶段 | Codex | pi | 是否必须常驻内存 | 可否挪到磁盘/安装期 |
| --- | --- | --- | --- | --- |
| PRoot 起进程 + bind rootfs | 大 | 大 | 进程存活时是 | 难（每次起进程都要） |
| Node 加载 ESM / 大包 | 中–大 | **很大** | 否 | **是**（compile cache / prewarm） |
| app-server / RPC ready | 扫 log 轮询 | `get_state` 轮询 | 否 | 部分（更快探测、并行准备） |
| profile / models / token 写入 | 有 | 有 | 否 | **是**（content-hash skip） |
| 历史 / UI 首屏 | 中 | 小 | 否 | **是**（磁盘 preview） |
| 模型推理 | 网络 | 网络 | 否 | 否 |

实现入口（便于对照）：

- Codex：`CodexBridgeLauncher` → `EmbeddedProotRuntime.launchAppServer`
- pi：`PiRpcSession.ensureStarted`（当前 `--mode rpc`）
- 互斥/热态：`NativeRuntimeBudget`、`ChatSessionRegistry`
- Markdown 复用：`SharedChatMarkdown`（Codex / pi 共用 parser + LRU）

## 已落地

### 内存层（warm keep-alive）

| 项 | 行为 |
| --- | --- |
| 离开 pi / Codex 聊天 | **不**因导航杀掉 agent；Codex 健康连接交给 `ChatSessionRegistry.hold` |
| App `onStop` | **不再**无条件 `stopPi` / 停 dsh |
| `onTrimMemory` | 仅在真实压力档回收 idle hold + `NativeRuntimeBudget.reclaimForMemoryPressure` |
| Codex idle hold | 最长约 30 分钟量级；最多约 3 个 idle held；压力路径仍 `releaseAllIdleSessions` |
| pi 绑定复用 | 同 profile + model 且进程存活时 `ensureStarted` 直接成功 |
| 共享 Markdown | `SharedChatMarkdown` + `ChatMarkdownEnvironment` |

### 磁盘 / 探测（P0）

| 项 | 实现入口 |
| --- | --- |
| `NODE_COMPILE_CACHE` | `NodeStartupSupport`；pi/dsh 启动脚本与 install smoke 导出；目录 `runtimes/<cli>/.node-compile-cache` |
| 安装期 seed | `PiRuntimeInstaller.smokePiHelp`、`DshRuntimeInstaller.verifyNodePtyLoads` 带 cache |
| 已装用户后台 prewarm | `RuntimeNodePrewarm` ← `ServiceLocator.warmUp()`（cache 已有文件则跳过；进程退出） |
| 配置 skip-if-unchanged | `NodeStartupSupport.writeTextIfChanged` → pi `models.json` / `settings.json` |
| 就绪探测退避 | pi `get_state`：40→200ms；Codex app-server log 端口：40→120ms |

### 体验路径（P1）

| 项 | 实现入口 |
| --- | --- |
| 列表 press 预热 | `SessionAgentPrefetch` + `SessionsScreen` 行 `pointerInput`（~180ms 后 `ensureStarted` / codex held 探测） |
| Codex 磁盘 transcript 预览 | `DiskTranscriptPreviewStore`（`noBackup/chat-previews`）；`ChatViewModel` 内存空时回落磁盘；非权威，app-server 页替换 |
| pi UI 历史 | `PiChatHistoryStore`（`noBackup/pi-chat-history`）；`PiChatScreen` 进出落盘；去掉 `--no-session` |
| pi set_model 热切换 | `PiRpcSession.ensureStarted`：进程存活时优先 RPC `set_model`，失败再冷启 |
| 轻量试聊（无 PRoot） | `ChatCompletionsClient` + `LightChatScreen`；模型服务 Chat 配置行「聊天气泡」入口 |
| 会话模式：轻聊 / 开发 | `recipe_light`（无 runtime + 角色）；开发 = Codex/pi/dsh；狂暴 = Lab flavor only |

真机抽查（V2301A，Secure Beta，同一次 App 生命周期）：离开 Codex 后 `codex` PID 可保持；再进为 reattach 而非新进程。UI 自动化「到输入框可见」仍含 dump/导航噪声，**以进程是否拆掉为准**。

## 分层策略（内存 + 磁盘 + 路径）

```text
磁盘（可长期，进程可死）
  · NODE_COMPILE_CACHE / 安装后 prewarm
  · session / thread 元数据
  · transcript 首屏预览（有界）
  · 配置 content-hash 跳过重写
        ↓ 冷启动 → 温启动
短生命周期（按需）
  · 列表按压/悬停预热即将打开的 1 个 agent
        ↓ 内存仍充裕时
进程 warm（已落地）
  · 离开聊天不杀；系统 trim 再回收
        ↓ 纯聊天场景
轻客户端（可选产品档）
  · 无 PRoot 的 Chat Completions / SSE（无工具）
```

- **进程 warm** = 体感最快（已做）。
- **磁盘 cache** = 被杀或 trim 后仍明显快于「纯冷」。
- **轻客户端** = 根本不启 agent kernel，适合 dots 试聊。

## 技术选项与开源/业界对照

| 思路 | 技术 / 参考 | Android App 可行性 | 对 AgentDeck |
| --- | --- | --- | --- |
| JS 编译缓存 | Node 20+ `NODE_COMPILE_CACHE` / `module.enableCompileCache`；社区 bytenode | 高（rootfs 内 Node） | **P0** |
| 安装期预热 | 游戏 first-run optimize；Android ProfileInstaller 类比 | 高 | **P0**（装完跑一次空启动写 cache） |
| 会话落盘 | Codex/Claude CLI session 文件；VS Code workspaceStorage | 高 | **P1**（pi 勿总 `--no-session`；UI 磁盘 preview） |
| 按压预取 | Chrome prerender / speculation；列表 prefetch | 高 | **P1**（`ACTION_DOWN` 预 `ensureStarted` / bridge） |
| 单 daemon 多会话 | LSP 单 server 多文档 | 中（隔离/Skills 快照） | **P1** |
| 纯聊天轻客户端 | 官方 HTTP SDK 模式；Continue 等 pure chat | 高 | **P1**（无工具档） |
| 只读 rootfs 镜像 | OCI / erofs / squashfs | 中（工程量大） | **P2** |
| 独立 `:agent` 进程 | 常见多进程 App | 中高成本 | **P2**（稳与记账，不优先只为「快」） |
| CRIU dump/restore | 桌面/容器检查点 | **低**（非 root / PRoot） | **不做** |

## 建议落地顺序

### P0 — 磁盘与探测（优先开工）

1. **Node compile cache**  
   - pi / dsh / Codex 相关 Node 入口统一：  
     `export NODE_COMPILE_CACHE=<runtime 下专用目录>`（落在 `noBackup` 树内，随 CLI 删除策略与 `runtime-footprint` 一致）。  
   - 目录示例（可调，实现时写入 `RuntimeLayoutContract` 或各 `*RuntimePaths`）：  
     - `runtimes/pi/.node-compile-cache`  
     - `runtimes/deepseek-harness/.node-compile-cache`  
     - Codex rootfs 内或 `runtimes/codex/.node-compile-cache`（若 Node 在 guest 内跑）。

2. **Install-time prewarm**  
   - `PiRuntimeInstaller` / `DshRuntimeInstaller` / Codex runtime 就绪后，后台一次性：  
     空跑 `pi --help` 或等价 `node …/cli.js --help`、必要时极短 RPC/`get_state` 后退出。  
   - **预热进程必须退出**，不占 warm 名额；失败仅打日志，不挡「已就绪」。

3. **就绪探测与启动流水线**  
   - 缩短固定 `delay(400)` 类轮询；改为短间隔 + 上限，或 port/ready 文件事件。  
   - `mkdir cwd`、token 写盘、profile 同步等 **能并行则并行**。  
   - profile / `models.json`：**content-hash 相同则 skip 写盘**。

4. **测量**  
   - 指标：`ensureStarted` / `launchAppServer` 墙钟、到「可输入」时间、是否新 PID。  
   - 场景：冷启动 App → 首次进 agent → 回列表 → 再进；`force-stop` 后仅磁盘温启动。

### P1 — 体验与路径

5. **列表 touch / 可见预热**  
   - 用户按下会话行或悬停约 200ms：对**那一个** card 触发后台 `ensureStarted` / Codex hold 检查。  
   - 取消手势可取消预热 job；同时最多预热 1 个。

6. **pi 磁盘 session**  
   - 评估去掉或可选 `--no-session`，会话文件落在 `pi-home`。  
   - 与 UI 气泡/Markdown 缓存策略对齐（进程死了仍能先画历史）。

7. **Transcript 磁盘预览**  
   - 在现有 `ChatTranscriptPreviewCache`（内存）之上，增加**有界磁盘预览**（条数/字符上限与 `CHAT_PERFORMANCE` 同量级或更严）。  
   - 仍不得当权威历史；app-server 页到达后整体替换。

8. **轻量 Chat Completions 客户端（无 PRoot）**  
   - 产品档：快速试模型 / 无工具聊天（如 dots）。  
   - 实现：OkHttp + SSE/stream；密钥仍走 `ProviderCredentialVault`。  
   - **不**替代 Codex 工具环与 pi 编码 agent。

9. **单进程多会话 / 放宽复用**  
   - Codex：确认 app-server 多 thread 能力，避免「一切换卡片就新 proot」。  
   - pi：同进程内 `set_model` 切换优于整杀（已有 binding 判断，可扩展）。

### P2 — 结构

10. **erofs/squash 只读 runtime 镜像** — 改善安装与随机读；工作量大。  
11. **`:agent` 多进程 + 短时 FGS** — 崩溃隔离与系统杀进程边界；不作为第一加速手段。  
12. **评估去掉 `largeHeap`** — 仅在 warm + 磁盘方案稳定、真机 meminfo 对比后；过早去掉会增加 trim 抖动。

## 非目标

- 不在 Secure 引入 Lab 无障碍 / UI Agent。
- 不用 CRIU / 内核检查点「秒恢复」进程。
- 不为加速而把消息正文写入 Room。
- 不把多套 rootfs 预置进 APK（仍见 `runtime-footprint`）。
- 不在未测量时宣称「二次进入 &lt;1s」——以 PID 复用、墙钟分位数和用户手感共同验收。

## 验收建议（真机）

设备：优先 V2301A（或同档 ARM64）。构建：Secure Beta。

| 场景 | 期望 |
| --- | --- |
| 同进程：首次 Codex/pi → 回列表 → 再进 | 进程 PID 可保持或 reattach；体感明显快于首次 |
| `am force-stop` 后仅再进（P0 cache 落地后） | 仍慢于 warm，但应快于「无 compile cache 的纯冷」 |
| 系统内存压力 trim | 允许回收；再进走温/冷路径，无崩溃、无串会话 |
| 纯聊轻客户端（若做） | 无 `libproot`/`node` 子进程即可发流式回复 |

记录：`dumpsys meminfo`、相关 PID、`logcat` 中 budget/start 标签、墙钟。

## 实现时注意点

- compile cache 目录随对应 CLI **删除运行时**一并清理（与设置「删除 pi/dsh」一致）。  
- prewarm 使用 `renice` 低优先级，避免抢首屏。  
- 预热与用户手动打开共用 `ensureStarted` / launch 单飞锁，防止双开进程。  
- Skills / MCP 快照仍按会话隔离（ADR-0013）；多会话单 daemon 时不得串快照。  
- Secure 配方：`deepseek-harness` / `pi` 的 recipe `available` 策略不因本计划擅自改成默认可建（产品开关另议）。

## 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-08-17 | 初稿：产品原则、账本、已落地 warm、P0–P2、非目标、验收；从讨论收敛为可执行计划 |
| 2026-08-17 | P0 代码：`NodeStartupSupport`、`RuntimeNodePrewarm`、pi/dsh/Codex 探测与 cache 接线；文档状态更新 |
| 2026-08-17 | P1 代码：列表预热、磁盘 transcript、pi 历史与 set_model、轻量 Chat Completions 试聊 |
