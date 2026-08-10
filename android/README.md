# AgentDeck Android

Kotlin + Jetpack Compose 客户端，负责对话/Profile 管理、统一环境设置、受验证的内嵌 Runtime 安装和 Codex app-server 原生聊天。当前产品只使用 App 私有 Runtime；`data/termux` 仅保留用于旧版本升级兼容。

## 工具链

- JDK 17
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- `compileSdk 36` / `targetSdk 36` / `minSdk 26`

Gradle 8.11.1 是 AGP 8.10.1 的已验证组合。Core 1.18 / Lifecycle 2.10 是 SDK 36 兼容线。Markdown renderer 固定为 0.33.0；升级工具链和依赖时必须重新执行聊天滚动基准。

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
./gradlew :app:testDebugUnitTest :app:assembleBeta :app:lintBeta
```

| 项目 | 值 |
|---|---|
| Beta application ID | `com.agentdeck.app.debug` |
| 版本 | `0.2.0-beta.4`（`versionCode=9`，未发布候选） |
| ARM64 APK | `app/build/outputs/apk/beta/app-arm64-v8a-beta.apk` |
| x86_64 APK | `app/build/outputs/apk/beta/app-x86_64-beta.apk` |
| Lint | `app/build/reports/lint-results-beta.html` |

`beta` 使用测试签名以便覆盖现有测试安装，但继承 release 的 R8、资源压缩和依赖 Baseline Profile；`debug` 只用于开发，不再作为真机预发布产物。

## 模块

| 模块 | 责任 |
|---|---|
| `data/termux` | 0.1.x 历史兼容实现；当前产品不注入、不展示 |
| `data/chat` | 本地 app-server WebSocket 启动、一次性 token 鉴权、双向 JSON-RPC 与对话/thread 映射 |
| `domain/env` | Doctor 固定探测协议与状态映射 |
| `domain/setup` | 全局设置状态、唯一下一步动作和可恢复安装进度 |
| `domain/chat` | Thread/Turn/Item 到移动聊天时间线的协议映射 |
| `domain/recipe` + `domain/install` | 严格配方、依赖排序、安装后验证 |
| `domain/launch` | CLI adapter、固定 executable 与 argv 生成 |
| `domain/cards` | 卡片编辑规则和 Profile 类型约束 |
| `data/db` | Room v3、Profile 外键和一次性初始化状态 |
| `ui` | 对话列表、原生聊天、工具和设置工作面 |

## 数据与凭据

Room 只保存卡片和非敏感 Profile 元数据（名称、Provider adapter、Base URL、默认模型与 credential 引用）。CLI OAuth/ChatGPT 登录仍由 Codex 管理；用户主动添加的第三方 API Key 使用 Android Keystore 加密并保存在 `noBackupFilesDir`，不会进入 Room、Codex 配置、argv 或日志。

数据库升级链为 v1→v2→v3→v4→v5→v6→v7，历史数据按显式迁移保留。禁止添加 `fallbackToDestructiveMigration()`。

## 真机限制

JVM 测试可以验证协议解析、Runtime 下载与校验、文件适配边界、命令边界和迁移 SQL，但不能证明 OEM Android、PRoot 网络下载或回环桥在真机上的实际行为。发布前必须执行 [RELEASE_CHECKLIST.md](../docs/RELEASE_CHECKLIST.md)。
