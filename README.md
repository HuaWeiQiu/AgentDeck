# AgentDeck

AgentDeck 是 Android 上聊天优先的本地 Codex 客户端。`0.2` 测试版可以在 App 私有目录中准备经过校验的 Ubuntu/Codex Runtime，不要求新用户安装 Termux；App 内呈现真实消息、工具活动、审批和停止。AgentDeck 不重写 Codex 的 Agent 循环，也不解析终端屏幕伪造消息。

最新测试版为 [`v0.2.0-beta.1`](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.2.0-beta.1)。测试 APK 使用 debug 签名，仅用于 ARM64 真机验收，不是正式签名的稳定版。`v0.1.0` 是早期骨架。

## 当前能力

- 对话作为首屏，整行进入由 Codex thread 支撑的原生聊天；思考和工具活动默认折叠，输入框跟随键盘，回复自动保持在最新位置。
- 通过官方 `codex app-server` WebSocket，支持历史恢复、Markdown、流式回复、停止、命令/文件审批和结构化工具活动。
- app-server 仅监听回环地址，每次启动生成一次性 capability token，并限制消息大小与连接生命周期。
- ARM64 新安装可一键下载并校验 Ubuntu Base 24.04.4 与 Codex CLI 0.147.0，安全解包、验证后原子启用。
- 内嵌 PRoot/loader 随 APK 打包并校验来源与许可证；运行进程由前台服务和精确 instance lease 管理。
- 设置默认只显示客户状态与一个主操作；高级设置可切换内嵌 Runtime 或 Termux 兼容模式。
- 会话卡片支持新建、编辑、启停和删除；原生聊天可以继承 Codex CLI 配置，也可以绑定受管 Sub2API/OpenAI Responses 兼容服务。
- 模型服务支持验证 API Key、获取上游 `/v1/models` 并为对话选择模型；已有 Termux/Ubuntu CLI Provider 可一键导入并绑定默认 Codex 对话。
- 导入或主动添加的第三方 API Key 使用 Android Keystore 加密，不写入 Room、Codex 配置、Intent、argv 或日志。

Claude Code 目前只是 P1 规划项，不提供安装或启动入口。受管 Profile 绑定会成为当前 app-server 进程的配置覆盖，但不会改写全局 Codex `config.toml`。

## 使用体验

- 默认只有“对话”和“设置”两个主入口；安装与修复按需出现，不长期占用导航。
- 首次打开自动检查设备、准备本机运行环境并复用已有认证；确实缺少认证时才要求连接模型服务。
- 标准模式不暴露 Termux、PRoot、Ubuntu、端口、PATH 或退出码，只给出客户可理解的状态和一个主操作。
- 思考和工具过程默认显示简洁摘要；原始命令、协议事件和日志进入高级或开发者设置。
- 高级设置提供 Provider、Endpoint、模型、工作区、权限和 Runtime 控制；开发者模式提供脱敏诊断与测试工具，但不能绕过安全边界。

完整决策见 [三级体验](docs/ADR-0008-CUSTOMER-EXPERIENCE-MODES.md) 和 [内嵌本地 Runtime](docs/ADR-0009-EMBEDDED-LOCAL-RUNTIME.md)。`0.2.0-beta.1` 已通过一台 Android 16 ARM64 真机关键路径验收，稳定版仍需更多 OEM、异常恢复、审批和正式签名覆盖。

## 运行边界

```text
AgentDeck
  -> native chat UI
  -> authenticated 127.0.0.1 WebSocket
  -> EmbeddedProotRuntime
     -> verified private Ubuntu rootfs
     -> codex app-server --listen ws://127.0.0.1:0

Advanced compatibility
  -> TermuxRuntime
  -> proot-distro Ubuntu
```

受管第三方 API Key 只以 Keystore 密文存在，并由鉴权回环 broker 按需提供给 Codex 固定 `auth.command` helper。导入现有 CLI Provider 时只读取当前服务、模型与凭据，成功加密保存后由 App 管理；原 Termux 配置不会被修改。桥 token 只在一次连接中短暂存在，动态工作区和 CLI 参数不会拼入 shell 源码。详细决策见 [Termux 兼容边界](docs/ADR-0001-TERMUX-EXECUTION-BOUNDARY.md)、[受管模型服务](docs/ADR-0007-MANAGED-MODEL-PROVIDERS.md)、[原生聊天桥](docs/ADR-0005-NATIVE-CHAT-BRIDGE.md) 和 [一步设置](docs/ADR-0006-ONE-STEP-SETUP.md)。

## 0.2 测试版真机使用

1. 在 ARM64 Android 8.0 或更高版本安装测试 APK。
2. 首次启动点击“安装或修复”；准备过程会下载约 122 MB，安装后私有 Runtime 约占 640 MB。
3. 在“模型服务”添加 Sub2API/Responses 服务并获取模型。已有 Termux/Ubuntu CLI Provider 的用户可点“当前 Codex 配置”并选择“导入并用于 Codex”。
4. 回到“对话”，点整行进入原生聊天。标准模式不需要打开终端或理解 Linux 配置。
5. 需要保留旧环境时，在“设置 → 高级设置 → Codex 运行方式”选择 Termux 兼容模式。

发布前仍需按 [真机发布清单](docs/RELEASE_CHECKLIST.md) 完成完整验收；自动化测试不能替代 Android、Termux、proot 和真实网络的端到端验证。

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
  wrappers/         固定 Termux 启动入口
  docs/              设计、ADR、发布清单
  scripts/           本地发布前验证
  .github/           Android CI 与依赖更新
```

## 发布状态

- 早期骨架：[v0.1.0](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.0)
- 当前测试预发布：[v0.2.0-beta.1](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.2.0-beta.1)（ARM64、debug 签名）
- 历史测试预发布：[v0.1.4](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.4)、[v0.1.3](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.3)、[v0.1.2](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.2)
- 已知损坏版本：[v0.1.1](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.1)（新安装首次启动会崩溃）
- 转为稳定版前的阻塞项：更多 OEM/Android 版本、首次完整安装、审批、终端兜底和历史迁移验收；正式签名配置。

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
