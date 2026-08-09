# ADR-0005：聊天优先会话与 Codex app-server 桥接

- 状态：已接受
- 日期：2026-08-09

## 背景

0.1.2 的“会话”实际是启动卡片。点击后 AgentDeck 通过 Termux `RUN_COMMAND` 打开 Codex TUI，但 App 不拥有 stdin/stdout、消息历史、运行状态或审批请求。用户看到 Doctor 和工具页，却难以发现真正的会话入口；把卡片换成聊天样式也不能弥补 transport 缺失。

OpenAI 官方 `codex app-server` 已提供 Thread / Turn / Item、历史读取、流式事件、审批和中断的双向 JSON-RPC。Happier、CloudCLI 和 cdesktop 的共同做法也是把 Agent runtime、会话状态与聊天呈现分层，而不是解析终端屏幕。

## 决策

1. AgentDeck 的产品目标改为“聊天优先的本地 Codex 客户端”。对话列表和原生 transcript 是主表面，工具、配置和 Doctor 是支持表面；Termux TUI 是终端兜底。
2. Codex 语义只来自与已安装 CLI 匹配的 `codex app-server` schema。Android 端不解析 ANSI/TUI 屏幕，也不把 Codex rollout JSONL 作为实时 API。
3. Android 直接连接与已固定 CLI 版本匹配的官方 app-server WebSocket，不再维护 Python stdio/JSONL 转发层。Termux supervisor 启动 `codex app-server --listen ws://127.0.0.1:0` 并管理进程树生命周期。
4. app-server 只监听 Android 回环地址，使用每次启动生成的高熵 capability token，并限制消息大小和连接生命周期。token 文件位于 Termux 私有运行目录，权限为 `0600`，Android 只通过一次启动结果读取；token 不写入仓库、日志、Room 或 Codex 配置。
5. AgentDeck 本地 conversation ID、Codex thread ID 和 Termux fallback session name 分开保存。创建、恢复和迁移必须显式维护映射，不能假设三者相同。
6. Android 客户端实现完整握手和核心调用：`initialize` / `initialized`、`thread/start`、`thread/resume`、`turn/start`、`turn/interrupt`。
7. 客户端按 Item 类型渲染 Agent 消息、reasoning、命令、文件变更、MCP 调用和错误。server-initiated command、file change 和 permissions approval 在当前 turn 内联展示并回送各自的结构化响应。
8. 流式状态必须可重建。socket 断开会触发 supervisor 终止它拥有的完整 app-server/PRoot 进程树；重连后通过 `thread/resume` 恢复 history，并主动中断 rollout 中残留的 `inProgress` turn，避免旧状态阻塞新消息。若 Codex 明确返回 active-writer 错误，只废弃该 conversation 的旧 thread 映射并创建新 thread。页面内存不是唯一状态源。
9. “在终端中打开”始终可用，用于 Codex 登录、桥接不兼容、诊断和官方 TUI 新能力。原生聊天失败不得损坏或删除 Codex 的现有会话。
10. PRoot Ubuntu 是 Android 端的外层隔离边界。线程初始化保持 legacy `read-only`，避免仅打开会话就写入项目 trust；每个可执行 turn 原子覆盖为 `externalSandbox` 并声明网络可用，避免启动 PRoot 内不可用的嵌套 Linux sandbox。固定 Codex 0.147.0 的官方源码明确说明 `externalSandbox` 仍拥有完整磁盘访问，因此 AgentDeck 不把它描述为文件级隔离，实际限制由下列审批预设和客户端响应共同执行：
    - `只读`：`untrusted`，Codex 只自动运行官方识别的安全只读命令；AgentDeck 自动拒绝其余命令、文件修改和额外权限请求。
    - `推荐`：`untrusted`，非安全操作必须显示客户可理解的审批；标准模式只能单次允许或拒绝。
    - `完全访问`：`never`，不发起审批，仅供明确知晓风险的用户选择。
11. app-server 使用 `check_for_update_on_startup=false`，不允许后台进程弹出升级交互；兼容性检测和升级由 AgentDeck Doctor/配方显式完成。
12. 由 AgentDeck 打开的 Codex TUI 同样运行在 PRoot 外层边界内，因此 wrapper 固定使用 CLI 支持的 `danger-full-access` 绕开不可用的 bubblewrap，并把“推荐/完全访问”分别映射为 `untrusted/never`。TUI 无法由 Android 客户端强制回绝审批，所以“只读”禁止打开终端；手动在 Termux 中启动的 Codex 不受 AgentDeck 参数影响。
13. 每个 AgentDeck conversation 使用稳定的非敏感 instance key、唯一进程 marker 和私有 PID lease。启动新 app-server 前只匹配同一 marker 并递归终止其子进程树，防止 Android 进程被杀后残留 app-server 与新实例并发写同一 rollout，也避免通配杀死用户手动运行的 Codex。

## 分期

### 0.1.3

- 交付本地鉴权桥、app-server Kotlin client、thread 创建/恢复、消息时间线、底部输入器、Markdown、流式输出、停止和命令/文件审批。
- 对话采用移动聊天列表结构，整行进入原生 transcript；终端入口作为同一 conversation 的次级表面。
- 工具页以统一状态和一个上下文主动作负责 Ubuntu、Codex 与桥接资源的安装和修复。

### 0.1.4

- 删除 Python stdio relay，改为官方 capability-token WebSocket，减少一层协议与生命周期故障面。
- 增加稳定 supervisor、精确进程树清理、active-writer 恢复和 OEM 后台执行行为检查。
- 原生聊天显示 app-server 实际返回的 Provider/模型，当前 UI 不再把未注入 CLI 的本地 Profile 误报为运行配置。
- 已在 Android 16 / iQOO Neo8 + Termux 0.119.0 beta 上验证 Doctor 8/8、真实新回复、历史恢复和离开页面后的完整进程树清理。

### P1

- 增加 thread 搜索/归档/重命名、附件、完整 diff、文件定位、断线诊断和更多 app-server server request 类型。

### P1.1

- 增加附件、diff、文件定位、搜索、会话重命名/归档和断线恢复诊断。

## 后果

- AgentDeck 不再把“能拉起 Termux”当作完整会话体验；主入口是原生 transcript，终端承载官方 TUI 兜底。
- 原生聊天增加了本地长期进程、双向协议和审批安全面，必须独立威胁建模与真机测试。
- app-server 协议随 Codex 版本变化，adapter 必须按 CLI 版本生成/选择 schema，并在不兼容时回退终端。
- 参考项目只提供已验证的模式；AgentDeck 不引入其云 relay、账号、遥测或完整 IDE 范围。
