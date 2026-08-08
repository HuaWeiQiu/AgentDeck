# 参考项目与差距分析

AgentDeck 的产品目标是移动端 Agent 对话入口，不只是安装器或命令快捷方式。2026-08-09 的本轮评估阅读了下列项目的会话、消息、输入器、流式传输和 Codex adapter 源码；结论用于约束实现，不只用于视觉模仿。

## 参考触发规则

以下任一条件出现时，暂停继续试错，先完成外部实现对照：

1. 同一问题连续两轮修复后仍可复现，或第三次修改仍缺少新的证据。
2. 依赖的协议、平台行为、安装命令或生命周期没有可靠来源支撑。
3. 当前架构无法解释故障，或只能靠扩大权限、强杀进程、解析 UI 文本等高风险方式绕过。

对照顺序是官方源码/文档、与当前版本匹配的官方测试、同类开源产品。每次结论必须记录核对的实现、AgentDeck 吸收的部分、不照搬的部分和本地验证证据；不能只复制界面，也不能因为找到一个仓库就跳过安全边界与真机测试。

## 源码对照

| 项目 | 已核对的实现 | AgentDeck 吸收 | 不直接照搬 |
|---|---|---|---|
| [OpenAI Codex app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server) | 官方以 Thread / Turn / Item 建模，通过双向 JSON-RPC 提供历史、流式 delta、工具事件、审批和中断；固定版本支持 capability-token WebSocket | 原生聊天以 app-server 为唯一 Codex 语义来源，直接使用回环 WebSocket，不解析 TUI 文本 | 不把无鉴权端口直接暴露给其它 Android App；升级 CLI 前必须重新验证 schema 与 transport |
| [codex-app-mobile](https://github.com/shuto-S/codex-app-mobile) | 移动端直接消费 app-server，将 workspace、thread、turn、审批和终端兜底分层；只有用户接近底部时才自动跟随流式内容；断开和 interrupt 都主动清理 active turn | 采用真实 turn 生命周期而非定时假进度；恢复历史与当前执行状态分开处理 | 其桌面/移动共享结构不是 Android + Termux 的现成 transport，不能直接复制运行层 |
| [oc-remote](https://github.com/crim50n/oc-remote) | Android 通过 Termux 本地服务连接 Codex，并用前台服务维持运行 | 验证了“Termux 负责 runtime、App 负责会话 UI”的本地路线 | 在本轮 iQOO/Android 16 真机上，第三方拉起 Termux foreground service 受到系统限制，因此没有照搬该生命周期 |
| [Happy](https://github.com/slopus/happy) | 会话、消息持久化、Agent 活动和移动输入器分层，强调恢复后继续同一任务 | 对话是持久实体，运行态必须从 Agent runtime 校正 | 不引入其远端同步、账号和 relay 基础设施 |
| [Happier](https://github.com/happier-dev/happier) | 原生移动端把 SessionView、消息时间线、AgentInput、审批、终端和文件表面拆开；会话列表按“需关注/工作中”等状态分组 | 对话优先、整行进入、明确活动状态；消息、工具、审批和终端是同一会话的不同表面 | 不引入其云 relay、账号体系和跨设备协作；AgentDeck P0 仍是单机 Android + Termux |
| [CloudCLI](https://github.com/siteboon/claudecodeui) | ChatInterface 将会话、实时事件和输入器分层；服务端使用稳定 App Session ID 映射 Codex thread，WebSocket 订阅携带序号并在重连后补事件 | 本地会话 ID 与 Codex thread ID 分离；发送、运行、重连、待审批都必须是显式状态 | 不依赖浏览器栈；不把读取 Codex JSONL 当实时协议，也不复用其无边界的 Web 服务形态 |
| [cdesktop](https://github.com/cdesktop-ai/cdesktop) | Codex executor 直接实现 app-server 双向 JSON-RPC，处理 client request、server request、审批和取消；UI 将 transcript、diff、计划和输入器分层 | 双向请求必须一等处理，审批不能从 stdout 文本猜测；工具事件用结构化行展示 | 桌面三栏密度不适合手机；不在 P0 加入完整 IDE、Git 和预览工作台 |
| [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) | 提供 executable、argv、workdir、命名终端会话和任务结果契约 | 保留为安装任务与真实终端兜底；同一对话复用命名 Termux session | RUN_COMMAND 的前台 Intent 不能提供持续双向消息流，不能作为原生聊天 transport |
| [Termux terminal-view](https://github.com/termux/termux-app/wiki/Termux-Libraries) | 可复用 Termux 的终端渲染与 emulator library | 后续可用于 App 内终端表面评估 | library 本身不会让 AgentDeck 跨沙箱附着到 Termux App 已有进程，不能替代桥接层 |

## 设计结论

1. 首屏是对话列表，不是 Doctor、工具商店或配置表单。环境尚未就绪时可以引导修复，但不能让“未登录”永久隐藏对话入口。
2. 对话列表采用整行进入、标题、工作区、CLI 和状态；技术性的 Termux session name 不占据主要视觉层级。
3. 原生聊天必须同时呈现用户消息、Agent Markdown、思考/活动状态、工具调用、diff、审批和失败，不把所有内容压成同一种气泡。
4. 输入器固定在底部，发送/停止是同一主操作；模型、权限、附件和终端属于紧邻输入器的次级控制。
5. 会话必须区分 AgentDeck 本地 ID、Codex thread ID 和 Termux fallback session name。重连按事件序号或官方 thread history 恢复，不能靠“页面仍开着”维持状态。
6. Codex TUI 保留为可用兜底，但不再定义产品最终形态。原生聊天通过官方 app-server 协议实现，不做 prompt 到 HTTP 的假聊天代理。

## 落地状态

### 0.1.4 已实现

- 未就绪时先进入统一设置，环境完成后以“对话”为首屏；设置页只给出一个上下文主动作。
- 会话采用聊天线程式整行入口，进入 app-server 原生 transcript，Termux/Codex TUI 作为明确的备用动作。
- Termux supervisor 直接启动官方 app-server WebSocket，仅监听回环地址并使用一次性高熵 capability token；Android 端限制消息大小和队列，离开页面精确清理该实例进程树。
- Android 端实现初始化、thread start/resume、turn start/interrupt、历史恢复、残留 turn 清理、事件 delta 和 command/file/permissions approval。
- Doctor 用真实后台命令结果识别 OEM 对 Termux 的冻结，并提供系统设置入口；聊天页以 app-server 返回值显示实际 Provider 与模型。

### 后续差距

- thread 搜索、归档、重命名、附件、完整 diff 和文件定位。
- MCP elicitation、工具用户输入等更多 server request 类型与断线诊断。

详细协议边界见 [ADR-0005：聊天优先会话与 Codex app-server 桥接](ADR-0005-NATIVE-CHAT-BRIDGE.md)。
