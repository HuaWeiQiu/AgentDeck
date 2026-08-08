# AgentDeck

AgentDeck 是 Android 上聊天优先的本地 Codex 客户端。当前开发版本负责检查环境、一键安装受验证的 CLI，并通过官方 `codex app-server` 在 App 内呈现真实消息、工具活动、审批和停止；Termux/Ubuntu 承载运行时与官方认证，Codex TUI 保留为终端兜底。AgentDeck 不重写 Codex 的 Agent 循环，也不解析终端屏幕伪造消息。

最新测试版为 [`v0.1.4`](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.4)。测试 APK 使用 debug 签名，不是正式签名的稳定版。`v0.1.0` 是早期骨架。

## 当前能力

- 对话作为首屏，整行进入由 Codex thread 支撑的原生聊天；右上角可打开同一工作区的 Termux TUI。
- 通过官方 `codex app-server` WebSocket，支持历史恢复、Markdown、流式回复、停止、命令/文件审批和结构化工具活动。
- app-server 仅监听回环地址，每次启动生成一次性 capability token，并限制消息大小与连接生命周期。
- 单一设置状态检测 Termux、权限、`allow-external-apps`、proot、Ubuntu、Codex、认证状态和全部 wrapper，并给出唯一下一步动作。
- 严格解析 APK 内 YAML 配方，按依赖执行“探测、安装、再验证”。
- 新安装固定使用 `ubuntu:24.04` 和 Codex CLI `0.147.0`。
- Codex arm64/x86_64 官方二进制使用固定 SHA-256 校验。
- 会话卡片支持新建、编辑、启停和删除；原生聊天直接继承 Codex CLI 的本地 Provider 与模型配置。
- 旧 Profile 数据继续兼容保留，数据库迁移不使用破坏性回退，但当前不会把它注入 Codex 配置。
- API Key、OAuth token 和 CLI 登录信息始终由 CLI 自己管理。

Claude Code 目前只是 P1 规划项，不提供安装或启动入口。Profile 绑定当前用于类型约束和本地引用，不会改写 Codex `config.toml`。

## 运行边界

```text
AgentDeck
  -> native chat UI
  -> authenticated 127.0.0.1 WebSocket
F-Droid Termux
  -> fixed supervisor + codex-app-server-start.sh
proot-distro ubuntu
  -> codex app-server --listen ws://127.0.0.1:0

Terminal fallback
  -> codex-ubuntu.sh
  -> codex TUI
```

AgentDeck 不接收、存储或传递 CLI 登录密钥；桥 token 只在一次连接的内存与 Termux 结果回调中短暂存在。动态工作区和 CLI 参数不会拼入 shell 源码。详细决策见 [Termux 执行边界](docs/ADR-0001-TERMUX-EXECUTION-BOUNDARY.md)、[受验证配方](docs/ADR-0003-VERIFIED-RECIPES.md)、[原生聊天桥](docs/ADR-0005-NATIVE-CHAT-BRIDGE.md) 和 [一步设置](docs/ADR-0006-ONE-STEP-SETUP.md)。

## 真机使用

1. 安装 [F-Droid Termux](https://f-droid.org/packages/com.termux/)（包名 `com.termux`）。
2. 下载测试 APK，或自行构建 debug APK。
3. 首次启动在“准备 Codex”中授予 `RUN_COMMAND` 权限。
4. 在 Termux 启用外部调用：

```bash
mkdir -p ~/.termux
grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || \
  printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
termux-reload-settings
```

5. 回到“准备 Codex”，点击一个主按钮自动检测并安装 Ubuntu、Codex 和聊天桥。
6. AgentDeck 会自动复用已有 ChatGPT 登录、API Key 环境变量或 Provider 凭据；缺失时按认证助手选择 ChatGPT 设备登录或隐藏输入 API Key。
7. 在“对话”中点整行进入原生聊天；连接失败或需要完整 TUI 时点右上角终端按钮。

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
- 当前测试预发布：[v0.1.4](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.4)（debug 签名）
- 历史测试预发布：[v0.1.3](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.3)、[v0.1.2](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.2)
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
- [OpenAI Codex app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server)
- [Happier](https://github.com/happier-dev/happier)
- [CloudCLI](https://github.com/siteboon/claudecodeui)
- [cdesktop](https://github.com/cdesktop-ai/cdesktop)
