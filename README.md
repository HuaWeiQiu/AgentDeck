# AgentDeck

AgentDeck 是 Android 上聊天优先的本地 Codex 客户端。`0.2` 测试版可以在 App 私有目录中准备经过校验的 Ubuntu/Codex Runtime，不要求新用户安装 Termux；App 内呈现真实消息、工具活动、审批和停止。AgentDeck 不重写 Codex 的 Agent 循环，也不解析终端屏幕伪造消息。

最新测试版为 [`v0.2.0-beta.1`](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.2.0-beta.1)。测试 APK 使用 debug 签名，仅用于 ARM64 真机验收，不是正式签名的稳定版。`v0.1.0` 是早期骨架。

## 当前能力

- 对话作为首屏，整行进入由 Codex thread 支撑的原生聊天；思考和工具活动默认折叠，输入框跟随键盘，回复自动保持在最新位置。
- 通过官方 `codex app-server` WebSocket，支持历史恢复、Markdown、流式回复、停止、命令/文件审批和结构化工具活动。
- app-server 仅监听回环地址，每次启动生成一次性 capability token，并限制消息大小与连接生命周期。
- ARM64 新安装可一键下载并校验 Ubuntu Base 24.04.4 与 Codex CLI 0.147.0，安全解包、验证后原子启用。
- 内嵌 PRoot/loader 随 APK 打包并校验来源与许可证；运行进程由前台服务和精确 instance lease 管理。
- 设置首页按“模型连接、内嵌运行环境、对话默认值、Codex 参数、关于”分组，进入子页后才加载和编辑具体配置。
- 会话卡片支持新建、编辑、启停和删除；原生聊天可以继承 Codex CLI 配置，也可以绑定受管 Sub2API/OpenAI Responses 兼容服务。
- 模型服务支持验证 API Key、获取上游 `/v1/models` 并为对话选择模型；验证或导入成功后“模型连接”会自动刷新为就绪，不再要求重复进入终端操作。
- 高级设置可直接编辑经过 TOML 校验的 `agentdeck.config.toml`；它在每次原生会话启动前自动同步到当前 Runtime，内嵌 Runtime 升级不会丢失这份配置。
- 使用当前 Codex 配置的对话会从 app-server `model/list` 获取真实模型目录，并在对话内提供模型与权限的临时切换。
- 导入或主动添加的第三方 API Key 使用 Android Keystore 加密，不写入 Room、Codex 配置、Intent、argv 或日志。

Claude Code 目前只是 P1 规划项，不提供安装或启动入口。受管 Profile 绑定会成为当前 app-server 进程的配置覆盖，但不会改写全局 Codex `config.toml`。

## 使用体验

- 默认只有“对话”和“设置”两个主入口；安装与修复按需出现，不长期占用导航。
- 首次打开自动检查设备、准备本机运行环境，并复用已有 Codex 认证或已验证的 AgentDeck 模型服务；两者都没有时才要求连接模型服务。
- 标准模式不暴露 PRoot、Ubuntu、端口、PATH 或退出码，只给出客户可理解的状态和一个主操作。
- 思考和工具过程默认显示简洁摘要；原始命令、协议事件和日志进入高级或开发者设置。
- 高级设置提供 Provider、Endpoint、模型、工作区、权限和内嵌 Runtime 状态；开发者模式提供脱敏诊断与测试工具，但不能绕过安全边界。

完整决策见 [三级体验](docs/ADR-0008-CUSTOMER-EXPERIENCE-MODES.md) 和 [内嵌本地 Runtime](docs/ADR-0009-EMBEDDED-LOCAL-RUNTIME.md)。`0.2.0-beta.1` 已通过一台 Android 16 ARM64 真机关键路径验收，稳定版仍需更多 OEM、异常恢复、审批和正式签名覆盖。

## 运行边界

```text
AgentDeck
  -> native chat UI
  -> authenticated 127.0.0.1 WebSocket
  -> EmbeddedProotRuntime
     -> verified private Ubuntu rootfs
     -> codex app-server --listen ws://127.0.0.1:0
```

AgentDeck 在 App 私有 `noBackupFilesDir` 中持久保存 Codex home 和项目目录，并分别绑定到内嵌 Ubuntu 的 `/root/.codex` 与 `/root/projects`，Runtime 修复或替换不会把它们留在旧 rootfs 中。官方登录产生的认证状态由 Codex 自己维护；AgentDeck 另存不含凭据的 `agentdeck.config.toml`，在每次会话启动前重新校验并作为 `thread/start` / `thread/resume` 的配置层应用。配置编辑器内置必填/可选说明与注释示例，拒绝 TOML 语法错误和明文凭据；第三方 API Key 只进入 Android Keystore 与实例级鉴权 broker。详细决策见 [Codex 配置层](docs/ADR-0010-AGENTDECK-CODEX-PROFILE.md)、[受管模型服务](docs/ADR-0007-MANAGED-MODEL-PROVIDERS.md) 和 [原生聊天桥](docs/ADR-0005-NATIVE-CHAT-BRIDGE.md)。

## 0.2 测试版真机使用

1. 在 ARM64 Android 8.0 或更高版本安装测试 APK。
2. 首次启动点击“安装或修复”；准备过程会下载约 122 MB，安装后私有 Runtime 约占 800 MB，并会预留安装所需临时空间。
3. 在“设置 → 模型连接”选择 ChatGPT 登录、OpenAI API Key 或第三方 Responses 服务；Sub2API 是带推荐默认值的专用预设，通用 Responses 允许自定义 Endpoint 和模型。
4. 回到“对话”，点整行进入原生聊天。标准模式不需要打开终端或理解 Linux 配置。
5. 新建或编辑对话时可设置角色名称、自我定义、目标、表达方式和边界；这些内容以会话级开发者指令注入，不作为普通用户消息伪装角色。

发布前仍需按 [真机发布清单](docs/RELEASE_CHECKLIST.md) 完成完整验收；自动化测试不能替代 Android、内嵌 PRoot 和真实网络的端到端验证。

## 本地验证

依赖 JDK 17、Android SDK platform/build-tools 36：

```bash
export JAVA_HOME="/path/to/jdk-17"
./scripts/verify-release.sh
```

该命令会检查配方与 APK assets、wrapper 同步，运行 JVM 单测，构建 debug APK，并执行 Android Lint。

产物：

- `android/app/build/outputs/apk/debug/app-debug.apk`
- `android/app/build/reports/lint-results-debug.html`

## 仓库结构

```text
AgentDeck/
  android/          Kotlin + Jetpack Compose 应用
  recipes/          受版本控制的安装/验证配方
  wrappers/         0.1.x 历史启动资产与共享鉴权 helper
  docs/              设计、ADR、发布清单
  scripts/           本地发布前验证
  .github/           Android CI 与依赖更新
```

## 发布状态

- 早期骨架：[v0.1.0](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.0)
- 当前测试预发布：[v0.2.0-beta.1](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.2.0-beta.1)（ARM64、debug 签名）
- 历史测试预发布：[v0.1.4](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.4)、[v0.1.3](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.3)、[v0.1.2](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.2)
- 已知损坏版本：[v0.1.1](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.1)（新安装首次启动会崩溃）
- 转为稳定版前的阻塞项：更多 OEM/Android 版本、首次完整安装、审批、异常恢复和历史数据升级验收；正式签名配置。

## 贡献与安全

贡献前运行 `./scripts/verify-release.sh`，并阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 私下报告，不要在公开 Issue 中粘贴凭据或私人日志。

## 许可证

Copyright 2026 HuaWeiQiu。按 [Apache License 2.0](LICENSE) 开源。

## 上游参考

- [Termux RUN_COMMAND Intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Termux:Widget](https://github.com/termux/termux-widget)
- [Termux:Tasker](https://github.com/termux/termux-tasker)
- [proot-distro](https://github.com/termux/proot-distro)
- [Kai](https://github.com/SimonSchubert/Kai)
- [UserLAnd](https://github.com/CypherpunkArmory/UserLAnd)
- [Local Desktop](https://github.com/localdesktop/localdesktop.github.io)
- [OpenAI Codex app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server)
- [Happier](https://github.com/happier-dev/happier)
- [CloudCLI](https://github.com/siteboon/claudecodeui)
- [cdesktop](https://github.com/cdesktop-ai/cdesktop)
