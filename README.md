# AgentDeck

安卓上的 **Agent CLI 启动台**（轻量 Termux 启动器）。

用卡片管理 Codex / Claude 等 CLI，一点就进会话；不是自研聊天输入框，而是拉起真 CLI（例如 Codex 聊天 TUI）。

## 能做什么

- **会话卡片**：一键进入预设 Agent 会话  
- **Codex 启动链**：Termux → `proot-distro login ubuntu` → 工作目录 → `codex`  
- **模型配置**：OpenAI 兼容（任意 Base URL）+ Anthropic；API Key 加密存储  
- **商店**：按配方安装 proot Ubuntu、Codex 等（在 Termux 里执行）  
- **设置**：检测 Termux、复制 `allow-external-apps` 与 wrapper 安装脚本  

## 截图 / 形态

当前为 **MVP 骨架**：

| 决策 | 说明 |
|---|---|
| 形态 | A · 轻量启动器（会话跑在 Termux，不自研 Agent 循环） |
| 运行时 | 必须安装 **Termux**（推荐 F-Droid 版） |
| P0 CLI | **Codex**（跑在 Ubuntu proot 内） |
| 交互 | 卡片 → 进入 CLI 聊天界面（非 App 自绘大输入框） |

## 真机使用（最短路径）

1. 安装 [F-Droid Termux](https://f-droid.org/packages/com.termux/)（包名 `com.termux`）  
2. 安装本仓库 [Release](https://github.com/HuaWeiQiu/AgentDeck/releases) 中的 APK  
3. 在 Termux 执行（设置页也可复制）：

```bash
mkdir -p ~/.termux
grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || \
  printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
termux-reload-settings
```

4. 打开 AgentDeck → **模型** 填写 API Key / Base URL  
5. **商店** 安装「proot-distro + Ubuntu」，再装 Codex  
6. **会话** 点 Codex → **进入**  

## 下载

- 最新安装包：[Releases](https://github.com/HuaWeiQiu/AgentDeck/releases)  
- 当前骨架版本：`v0.1.0`（debug 签名，包名 `com.agentdeck.app.debug`）  

> 仅用于开发/尝鲜；正式版需改为 release 签名与正式包名。

## 仓库结构

```text
AgentDeck/
  docs/DESIGN.md     # 完整设计文档
  recipes/           # CLI / 环境安装配方
  wrappers/          # Termux 启动包装脚本模板
  android/           # Kotlin + Jetpack Compose 工程
```

## 本地构建

依赖：JDK 17+、Android SDK。在 `android/local.properties` 配置：

```properties
sdk.dir=/你的/Android/sdk路径
```

```bash
export JAVA_HOME="/path/to/jdk-17"
cd android
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

更细的模块说明见 [android/README.md](android/README.md)。

## 文档

- [设计文档](docs/DESIGN.md) — 产品决策、数据模型、启动链、MVP 范围  

## 进度

| 阶段 | 状态 |
|---|---|
| 产品设计 | 已完成 |
| Android 骨架（四 Tab + Termux 启动） | 已完成，可编译 |
| 真机联调 / 商店完整日志 / 深度环境探测 | 进行中 |
| 内嵌终端（形态 B） | 未开始 |

## 许可

尚未单独声明许可证；默认保留作者权利。若要开源协议（MIT/Apache-2.0 等）可再补。

## 相关链接

- 仓库：https://github.com/HuaWeiQiu/AgentDeck  
- Termux：https://github.com/termux/termux-app  
