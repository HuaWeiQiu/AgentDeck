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
| [CC Pocket](https://github.com/K9i-0/ccpocket) | Flutter 移动端实现底部锚定消息列表、IME 高度变化补偿、思考折叠、工具结果三级详情、审批、草稿与离线队列 | 标准聊天采用“用户气泡 + 无边框 Agent 回复 + 一行活动摘要”；键盘和流式输出保持阅读位置 | 输入器按钮和会话卡片功能过多，不照搬其视觉密度；不引入远端 Bridge |
| [codex-app-mobile](https://github.com/shuto-S/codex-app-mobile) | 移动端直接消费 app-server，将 workspace、thread、turn、审批和终端兜底分层；只有用户接近底部时才自动跟随流式内容；断开和 interrupt 都主动清理 active turn | 采用真实 turn 生命周期而非定时假进度；恢复历史与当前执行状态分开处理 | 其桌面/移动共享结构不是 Android + Termux 的现成 transport，不能直接复制运行层 |
| [oc-remote](https://github.com/crim50n/oc-remote) | 原生 Compose 客户端通过 Termux 在设备上运行 OpenCode，并实现前台连接服务、草稿、通知、重试和终端 PTY | 借鉴 Android 生命周期、客户/诊断状态分离和本地 Runtime 控制面 | 它不是 Codex app-server 客户端；当前界面卡片和按钮过重，且仍要求 Termux，不作为视觉或目标 Runtime 基线 |
| [Happy](https://github.com/slopus/happy) | 会话、消息持久化、Agent 活动和移动输入器分层，强调恢复后继续同一任务 | 对话是持久实体，运行态必须从 Agent runtime 校正 | 不引入其远端同步、账号和 relay 基础设施 |
| [Happier](https://github.com/happier-dev/happier) | 原生移动端把 SessionView、消息时间线、AgentInput、审批、终端和文件表面拆开；键盘位移由独立 Scaffold 管理；权限用单选模式、当前生效策略和说明呈现 | 对话优先、整行进入、明确活动状态；展示策略独立于协议模型；权限预设同时解释结果和风险 | 不引入其云 relay、账号体系和跨设备协作；不复制其完整工作台范围 |
| [CloudCLI](https://github.com/siteboon/claudecodeui) | ChatInterface 将会话、实时事件和输入器分层；服务端使用稳定 App Session ID 映射 Codex thread，WebSocket 订阅携带序号并在重连后补事件 | 本地会话 ID 与 Codex thread ID 分离；发送、运行、重连、待审批都必须是显式状态 | 不依赖浏览器栈；不把读取 Codex JSONL 当实时协议，也不复用其无边界的 Web 服务形态 |
| [cdesktop](https://github.com/cdesktop-ai/cdesktop) | Codex executor 直接实现 app-server 双向 JSON-RPC，处理 client request、server request、审批和取消；UI 将 transcript、diff、计划和输入器分层 | 双向请求必须一等处理，审批不能从 stdout 文本猜测；工具事件用结构化行展示 | 桌面三栏密度不适合手机；不在 P0 加入完整 IDE、Git 和预览工作台 |
| [Cherry Studio](https://github.com/CherryHQ/cherry-studio) | Provider 是独立配置实体；用户输入 Key/Endpoint 后主动获取模型，再从服务对应的模型集合中启用或选择模型 | 模型服务与对话分离；验证连接、发现模型、选择默认模型是一个连续流程；Provider adapter 隔离服务差异 | 不照搬其桌面端多栏设置和直接 SDK 推理链路；AgentDeck 仍由 Codex app-server 执行 Agent turn |
| [Sub2API](https://github.com/Wei-Shaw/sub2api) | 一个 API Key 经统一 `/v1` 入口访问多种上游，`/v1/models` 返回该 Key/分组可用的模型集合 | 提供 Sub2API 预设 adapter，按已验证 Key 获取真实可用模型，不维护容易过期的静态清单 | 不假定任意 OpenAI 兼容网关都完整支持 Codex Responses；连接验证成功不替代实际 turn 验收 |
| [Agora](https://github.com/newo-ether/Agora) | Android 原生 BYOK 客户端支持自定义 Base URL、多 Provider 和按会话/消息选择模型 | 移动端把 Provider 与模型选择放在会话创建路径，并让 API Key 输入界面防截屏 | 不把 Key 直接交给通用推理 SDK，也不允许对话在恢复时静默换上游；密钥通过 Keystore 与按需 broker 交给 Codex |
| [Android Action Kernel](https://github.com/ethanjlimgit/android-action-kernel) | Python 通过 ADB 获取 `uiautomator` XML，把控件树压缩成文本、角色、边界和动作，再执行观察/推理/动作循环 | Lab UI Agent 采用有界语义树、少量节点动作、每次动作后重新观察和无进展检测 | 不嵌入 ADB/Python 或第二套模型循环，不以坐标为首选，不把原始 XML/敏感输入交给模型；完整方案见 [Lab UI Agent 计划](plans/lab-ui-agent.md) |
| [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) | 提供 executable、argv、workdir、命名终端会话和任务结果契约 | 保留为安装任务与真实终端兜底；同一对话复用命名 Termux session | RUN_COMMAND 的前台 Intent 不能提供持续双向消息流，不能作为原生聊天 transport |
| [Termux terminal-view](https://github.com/termux/termux-app/wiki/Termux-Libraries) | 可复用 Termux 的终端渲染与 emulator library | 后续可用于 App 内终端表面评估 | library 本身不会让 AgentDeck 跨沙箱附着到 Termux App 已有进程，不能替代桥接层 |
| [Termux app](https://github.com/termux/termux-app) | APK 内置分架构 Bootstrap，先解压到 staging、设置权限/链接，再原子提升为 prefix；Terminal Emulator/View 为 Apache-2.0 | 借鉴版本化 Bootstrap、临时安装、原子切换和可选终端组件 | 主 App 为 GPLv3、软件包路径与包名绑定且稳定分支 targetSdk 28；不完整嵌入、不自建 Termux 包仓库 |
| [proot-distro](https://github.com/termux/proot-distro) | 用发行版插件描述 rootfs、登录、bind、备份和恢复 | 借鉴 rootfs manifest、功能探测与可重复登录参数 | GPLv3 且依赖 Termux prefix；不把脚本作为 AgentDeck 公共 API |
| [UserLAnd](https://github.com/CypherpunkArmory/UserLAnd) | 使用显式状态机处理空间检查、rootfs 下载、缓存复用、复制、解压、验证和会话启动 | Runtime installer 持久化真实阶段，离线时可复用已验证缓存，更新不重复下载 rootfs | 项目较老且 GPLv3；不复制 UI、服务或完整发行版管理代码 |
| [Local Desktop](https://github.com/localdesktop/localdesktop.github.io) | 在现代 target SDK 下把 PRoot/loader 打包为 APK native library，从 App 私有目录挂载 ARM64 rootfs | 作为 EmbeddedProotRuntime 的首个宿主执行技术验证 | GPLv3 且产品目标是 Linux 桌面；不复制桌面、VNC/X11 或完整实现 |
| [Kai](https://github.com/SimonSchubert/Kai) | 固定 Termux PRoot 提交和 talloc 版本交叉构建多 ABI native library；处理 Android 对 `libtalloc.so.2` 文件名、rootfs 解包、APT 与进程清理的限制 | 采用其可复现 ARM64 构建产物、来源/许可证记录和 PRoot 参数作为首个技术验证基线；文件哈希在发布脚本中锁定 | 不复制其 Agent、PTY、Debian/Alpine 产品层；运行时编排、Codex app-server 与安全边界由 AgentDeck 自行实现 |
| [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE) | 自有包名的 Termux prefix、Bootstrap、终端会话和前台服务证明独立 APK 可承载开发工具链 | 借鉴 Bootstrap 生命周期和 Android 服务边界 | 已于 2024 年停止维护且为 GPLv3；自建 prefix 需要重编全部包，不选择该维护模型 |
| [openclaude-android](https://github.com/friuns2/openclaude-android) | README 宣称把 Agent 与 Linux 内嵌 APK，但 2026-08-09 核对的源码树没有 Android Gradle 工程、APK 构建或 PRoot 集成 | 无 | 宣传不能作为实现证据；除非出现可重复构建的 Android 源码和真机验证，否则排除 |

## 设计结论

1. 首屏是对话列表，不是 Doctor、工具商店或配置表单。环境尚未就绪时可以引导修复，但不能让“未登录”永久隐藏对话入口。
2. 对话列表采用整行进入、标题、工作区、CLI 和状态；技术性的 Termux session name 不占据主要视觉层级。
3. 原生聊天必须同时呈现用户消息、Agent Markdown、思考/活动状态、工具调用、diff、审批和失败，不把所有内容压成同一种气泡。
4. 输入器固定在底部，发送/停止是同一主操作；模型、权限、附件和终端属于紧邻输入器的次级控制。
5. 会话必须区分 AgentDeck 本地 ID、Codex thread ID 和 Termux fallback session name。重连按事件序号或官方 thread history 恢复，不能靠“页面仍开着”维持状态。
6. Codex TUI 保留为可用兜底，但不再定义产品最终形态。原生聊天通过官方 app-server 协议实现，不做 prompt 到 HTTP 的假聊天代理。
7. 标准模式只有对话和设置；准备、修复、终端和日志按上下文或体验层级出现，内部技术名不进入客户主流程。
8. 运行时经 `AgentRuntime` 隔离；EmbeddedProotRuntime 是目标默认值，TermuxRuntime 是迁移期兼容实现。
9. 开源实现只有在源码、许可证、可重复构建和目标 Android 版本均可核实时才进入技术选型。
10. Lab 手机 UI Agent 保留 Codex app-server 作为唯一 Agent loop，通过 Host Broker 调用
    AccessibilityService；密码、验证码、银行卡、支付和生物认证在 Lab 也必须由用户亲自完成。

## 落地状态

### 0.1.4 已实现

- 未就绪时先进入统一设置，环境完成后以“对话”为首屏；设置页只给出一个上下文主动作。
- 会话采用聊天线程式整行入口，进入 app-server 原生 transcript，Termux/Codex TUI 作为明确的备用动作。
- Termux supervisor 直接启动官方 app-server WebSocket，仅监听回环地址并使用一次性高熵 capability token；Android 端限制消息大小和队列，离开页面精确清理该实例进程树。
- Android 端实现初始化、thread start/resume、turn start/interrupt、历史恢复、残留 turn 清理、事件 delta 和 command/file/permissions approval。
- Doctor 用真实后台命令结果识别 OEM 对 Termux 的冻结，并提供系统设置入口；聊天页以 app-server 返回值显示实际 Provider 与模型。

### Unreleased 已实现

- “当前 Codex 配置”继续作为无迁移默认项；用户也可新增 Sub2API 或通用 Responses 兼容模型服务。
- 模型服务页面按“输入 Endpoint/Key -> 验证并获取模型 -> 搜索选择模型 -> 保存”组织；对话创建时再选择服务与模型。
- API Key 由 Android Keystore 加密，Room 只保存引用；固定 `auth.command` helper 经实例级回环能力令牌按需取 Key，Key 不进入 Termux 配置、argv 或日志。
- Codex thread 映射按 Provider/模型隔离，并核对 app-server 返回的实际 Provider/模型，防止界面选择与真实调用不一致。
- 标准模式收敛为“对话 / 设置”两项导航，高级设置按需展示环境组件、重扫和终端入口。
- 聊天页采用无头像 Agent 正文、内容宽度用户气泡、默认折叠活动、可恢复的底部审批面板、近底自动跟随和键盘避让输入器。
- 设置提供“只读 / 推荐 / 完全访问”三档 Codex 默认权限，每个对话可继承或覆盖；固定版本协议分别映射到 `untrusted/never`，只读会自动拒绝非安全操作且不能通过终端兜底绕过。

### 后续差距

- thread 搜索、归档、重命名、附件、完整 diff 和文件定位。
- MCP elicitation、工具用户输入等更多 server request 类型与断线诊断。
- 开发者日志与协议事件诊断。
- `AgentRuntime` 抽象、EmbeddedProotRuntime、版本化安装、更新/回滚和 ARM64 真机门禁。

详细协议边界见 [ADR-0005](ADR-0005-NATIVE-CHAT-BRIDGE.md)，体验和 Runtime 决策见 [ADR-0008](ADR-0008-CUSTOMER-EXPERIENCE-MODES.md) 与 [ADR-0009](ADR-0009-EMBEDDED-LOCAL-RUNTIME.md)。
