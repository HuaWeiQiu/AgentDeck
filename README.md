# AgentDeck

Android 上的 **Agent CLI 启动台**（轻量 Termux 启动器）。

- 首页用 **卡片** 管理会话入口  
- 点击卡片 → 在 Termux 中进入 **CLI 聊天界面**（如 Codex TUI）  
- Codex 场景：**先 `proot-distro login ubuntu`，再运行 `codex`**  
- 模型：OpenAI 兼容 + Anthropic  
- 商店：安装 Codex / 基础 Ubuntu 环境等（配方 YAML）

## 当前进度

| 阶段 | 状态 |
|---|---|
| 产品设计文档 | ✅ `docs/DESIGN.md` |
| 启动 wrapper / 配方草稿 | ✅ `wrappers/` `recipes/` |
| Android 工程骨架 | ✅ `android/`（`assembleDebug` 已通过） |

## 已确认决策

1. 形态 **A**：轻量启动器（不自研 Agent 循环）  
2. 运行时：**必须 Termux**  
3. P0 CLI：**Codex**（Ubuntu 内）  
4. 交互：卡片 → Codex 聊天 TUI（非 App 自绘大输入框）  
5. 模型：OpenAI 兼容 + Anthropic  
6. 交付：先文档，再骨架  

## 文档

- [设计文档](docs/DESIGN.md)

## 本地目录

```text
AgentDeck/
  docs/DESIGN.md
  recipes/           # CLI/环境安装配方
  wrappers/          # Termux 启动包装脚本模板
  android/           # Kotlin/Compose App（见 android/README.md）
```

## 构建 Android

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd android && ./gradlew :app:assembleDebug
```
