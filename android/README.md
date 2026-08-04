# AgentDeck Android 工程

轻量 Termux 启动器（Kotlin + Jetpack Compose）。

## 环境要求

- JDK 17+
- Android SDK（`compileSdk 36` / `minSdk 26`）
- 在 `local.properties` 中设置 `sdk.dir=...`（该文件已 gitignore，勿提交）

## 构建与安装

```bash
export JAVA_HOME="/path/to/jdk-17"
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

| 项 | 值 |
|---|---|
| Debug 包名 | `com.agentdeck.app.debug` |
| 版本名 | `0.1.0-skeleton` |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |

## 已实现模块

| 模块 | 说明 |
|---|---|
| 会话 | Codex / Claude 种子卡片；「进入」→ Termux `RUN_COMMAND` |
| 商店 | 读取 assets 配方 YAML；在 Termux 执行安装脚本 |
| 模型 | OpenAI 兼容 + Anthropic；Key 加密存储 |
| 设置 | Termux 检测、复制 allow-external-apps / wrapper 脚本 |
| 启动链 | Codex：`proot-distro login ubuntu` → `codex` |

## 真机前置

1. 安装 **F-Droid** 版 Termux（`com.termux`）  
2. 开启 `allow-external-apps=true`  
3. 授予 App 的 `RUN_COMMAND` 权限  
4. 商店安装 Ubuntu 与 Codex  
5. 模型页配置 API  

## 代码包结构

```text
com.agentdeck.app
  ui/        Compose 四 Tab
  domain/    启动、环境探测、安装
  data/      Room、KeyStore、TermuxGateway
  di/        ServiceLocator
```
