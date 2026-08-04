# AgentDeck Android

轻量 Termux 启动器骨架（Kotlin + Jetpack Compose）。

## 环境

- JDK 17+
- Android SDK（本机示例：`/opt/homebrew/share/android-commandlinetools`）
- 在 `local.properties` 中设置 `sdk.dir=...`

## 构建

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd android
./gradlew :app:assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> debug 包名：`com.agentdeck.app.debug`

## 已实现（骨架）

| 模块 | 说明 |
|---|---|
| 会话 Tab | Codex / Claude 种子卡片；进入 → Termux `RUN_COMMAND` |
| 商店 Tab | 读取 assets 配方 YAML；一键在 Termux 跑安装脚本 |
| 模型 Tab | OpenAI 兼容 + Anthropic；Key 加密存储 |
| 设置 Tab | Termux 检测、复制 allow-external-apps / wrapper 脚本 |
| 启动链 | Codex：`proot-distro login ubuntu` → `codex`（wrapper 或 inline） |

## 真机前置

1. 安装 **F-Droid** 版 Termux（`com.termux`）
2. Termux 中开启 `allow-external-apps=true`
3. 授予 App 的 `RUN_COMMAND` 权限
4. 商店安装 `proot-distro + Ubuntu`，再装 Codex
5. 模型页填入 API Key / Base URL

## 包结构

```text
com.agentdeck.app
  ui/           Compose 四 Tab
  domain/       启动、环境探测、安装
  data/         Room、KeyStore、TermuxGateway
  di/           ServiceLocator
```
