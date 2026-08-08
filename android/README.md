# AgentDeck Android

Kotlin + Jetpack Compose 客户端，负责卡片/配置管理、环境 Doctor、受验证配方安装和 Termux 会话启动。

## 工具链

- JDK 17
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- `compileSdk 36` / `targetSdk 36` / `minSdk 26`

Gradle 8.11.1 是 AGP 8.10.1 的已验证组合。Core 1.18 / Lifecycle 2.10 是 SDK 36 兼容线。Lint 会提示更新 Gradle 及升级到 Core 1.19 / Lifecycle 2.11，但后一组依赖要求 `compileSdk 37` 和 Android Gradle Plugin 9.1；整套工具链迁移不属于 0.1.x。

在未配置全局 Android SDK 时，将本机路径写入未跟踪的 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

## 构建

建议从仓库根目录运行完整验证：

```bash
./scripts/verify-release.sh
```

或只运行 Android 任务：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

| 项目 | 值 |
|---|---|
| Debug application ID | `com.agentdeck.app.debug` |
| 版本 | `0.1.2-debug`（`versionCode=3`） |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Lint | `app/build/reports/lint-results-debug.html` |

## 模块

| 模块 | 责任 |
|---|---|
| `data/termux` | `RUN_COMMAND` Intent、命名会话和后台结果回调 |
| `domain/env` | Doctor 固定探测协议与状态映射 |
| `domain/recipe` + `domain/install` | 严格配方、依赖排序、安装后验证 |
| `domain/launch` | CLI adapter、固定 executable 与 argv 生成 |
| `domain/cards` | 卡片编辑规则和 Profile 类型约束 |
| `data/db` | Room v3、Profile 外键和一次性初始化状态 |
| `ui` | 会话、工具、CLI 配置、设置四个工作面 |

## 数据与凭据

Room 只保存卡片和非敏感 Profile 元数据（名称、Provider 类型、Base URL、默认模型）。AgentDeck 不保存 API Key 或 OAuth token；登录由 Codex 等 CLI 自己完成。

数据库升级链为 v1→v2→v3：v2 移除旧 `keyRef`，v3 增加 Profile 外键与一次性播种标记。禁止添加 `fallbackToDestructiveMigration()`。

## 真机限制

JVM 测试可以验证 Intent 契约、解析、命令边界和迁移 SQL，但不能证明 OEM Android、F-Droid Termux、proot 网络下载或 Codex TUI 的实际行为。发布前必须执行 [RELEASE_CHECKLIST.md](../docs/RELEASE_CHECKLIST.md)。
