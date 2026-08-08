# AgentDeck

AgentDeck 是 Android 上的 Agent CLI 启动台。它负责检查环境、安装受验证的 CLI、管理启动卡片，并通过 Termux 打开真实交互会话；它不重写 Codex 的 Agent 循环，也不在 App 内伪造终端。

当前测试预发布版本为 [`v0.1.1`](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.1)。它用于真实 Android + F-Droid Termux 验收，附带 debug 签名 APK，不是正式签名的稳定版。`v0.1.0` 是早期骨架。

## 当前能力

- 通过 F-Droid Termux `RUN_COMMAND` 打开和复用命名会话。
- 真实 Doctor 检测 Termux、权限、`allow-external-apps`、proot、Ubuntu、Codex、登录状态和 wrapper。
- 严格解析 APK 内 YAML 配方，按依赖执行“探测、安装、再验证”。
- 新安装固定使用 `ubuntu:24.04` 和 Codex CLI `0.147.0`。
- Codex arm64/x86_64 官方二进制使用固定 SHA-256 校验。
- 会话卡片支持新建、编辑、启停、删除和兼容 Profile 选择。
- Profile 删除时保留卡片并自动解除绑定；数据库迁移不使用破坏性回退。
- API Key、OAuth token 和 CLI 登录信息始终由 CLI 自己管理。

Claude Code 目前只是 P1 规划项，不提供安装或启动入口。Profile 绑定当前用于类型约束和本地引用，不会改写 Codex `config.toml`。

## 运行边界

```text
AgentDeck
  -> explicit RUN_COMMAND Intent
F-Droid Termux
  -> fixed codex-ubuntu.sh + argv
proot-distro ubuntu
  -> codex TUI
```

AgentDeck 不接收、存储或传递密钥；动态工作区和 CLI 参数不会拼入 shell 源码。详细决策见 [Termux 执行边界](docs/ADR-0001-TERMUX-EXECUTION-BOUNDARY.md)、[结果与 Doctor](docs/ADR-0002-TERMUX-RESULTS-AND-DOCTOR.md)、[受验证配方](docs/ADR-0003-VERIFIED-RECIPES.md) 和 [CLI adapter/数据完整性](docs/ADR-0004-CLI-ADAPTERS-AND-USER-DATA.md)。与 Termux 官方生态的取舍及待补能力见 [参考项目与缺口分析](docs/REFERENCE_PROJECTS.md)。

## 真机使用

1. 安装 [F-Droid Termux](https://f-droid.org/packages/com.termux/)（包名 `com.termux`）。
2. 下载并安装 `v0.1.1` 的测试 APK，或自行构建 debug APK。
3. 首次启动在 Doctor 中授予 `RUN_COMMAND` 权限。
4. 在 Termux 启用外部调用：

```bash
mkdir -p ~/.termux
grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || \
  printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
termux-reload-settings
```

5. 在“工具”中安装 Ubuntu 基础环境和 Codex。
6. 在 Termux/Ubuntu 的 Codex 中完成官方登录，再运行 Doctor。
7. 在“会话”中创建或打开 Codex 卡片。

发布前仍需按 [真机发布清单](docs/RELEASE_CHECKLIST.md) 完成 F-Droid Termux 端到端验证。本轮本地开发环境没有连接 Android 设备，因此自动化通过不等同于真机验收。

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
- 测试预发布：[v0.1.1](https://github.com/HuaWeiQiu/AgentDeck/releases/tag/v0.1.1)（debug 签名）
- 转为稳定版前的阻塞项：真实 Android + F-Droid Termux 安装、回调、登录、命名会话和迁移验收；正式签名配置。

## 贡献与安全

贡献前运行 `./scripts/verify-release.sh`，并阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 私下报告，不要在公开 Issue 中粘贴凭据或私人日志。

## 许可证

Copyright 2026 HuaWeiQiu。按 [Apache License 2.0](LICENSE) 开源。

## 上游参考

- [Termux RUN_COMMAND Intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Termux:Widget](https://github.com/termux/termux-widget)
- [Termux:Tasker](https://github.com/termux/termux-tasker)
- [proot-distro](https://github.com/termux/proot-distro)
- [OpenAI Codex](https://github.com/openai/codex)
