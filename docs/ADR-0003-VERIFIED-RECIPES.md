# ADR-0003：受校验、可复现的内置配方

- 状态：已接受
- 日期：2026-08-08

## 背景

v0.1.0 虽然包含 YAML 配方，但应用只读取名称和描述，真正的安装脚本按配方 ID 硬编码在 Kotlin 中。YAML 中的 Codex 安装还包含错误包名回退和浮动版本，因此无法审计、复现或证明安装成功。

## 决策

1. APK 内配方使用 `schema_version: 1`，由 SnakeYAML `SafeConstructor` 解析，并拒绝重复键、别名、未知字段、非法 ID、超长脚本和错误类型。
2. 可安装配方必须声明固定版本、依赖、有限超时、Termux 安装脚本和验证脚本。不可安装配方不允许携带执行内容。
3. 安装器对依赖做拓扑排序并拒绝循环；每项依次执行“安装前探测 → 必要时安装 → 安装后验证”。只有目标及其依赖全部验证成功，UI 才显示成功。
4. 配方脚本只来自随 APK 发布且经过测试的 assets。当前不执行远程下载的 YAML，也不把任何用户输入拼入配方脚本。
5. Ubuntu 新安装固定为 `ubuntu:24.04`。如果本地同名容器版本不同，配方明确失败并要求用户迁移，不自动删除已有数据。基础环境在容器内通过 `apt-get update` 安装并验证 CA 证书、curl、git、tar/gzip 等必要工具，但不自动升级整个发行版。
6. Codex 安装先进入 proot Ubuntu，通过 `command -v codex` 和 `codex --version` 检测已有 CLI。`0.147.0` 或更高版本原样保留；缺失、损坏或低于兼容下限时安装 OpenAI 官方 `0.147.0` 独立 Linux 二进制。`~/.codex` 不在安装目标内，因此 Provider、登录和会话配置不会被覆盖。
7. 新安装的 arm64 和 x86_64 二进制分别校验 GitHub Release 记录的 SHA-256，再写入 `/usr/local/bin/codex`。
8. 大型 Release 资产使用定长分段下载，每段验证长度，最终验证固定摘要。下载优先使用 curl 默认双栈网络；若 Android/proot 的 IPv4/IPv6 路由无法连接 GitHub，则在有限等待后回退 IPv4。
9. Codex wrapper 由配方声明 assets 名称，安装器从 APK 读取后写入 Termux；文件名和 heredoc 边界均校验，权限固定为 `0700`。
10. Claude Code 继续显示为 P1 规划项，但在完成独立 adapter 和真实验证前不提供安装按钮。
11. 原生聊天由 AgentDeck 管理更新节奏，app-server 使用 Codex 官方 `check_for_update_on_startup=false` 配置覆盖，避免后台桥出现交互式升级提示；升级只通过可见的一键设置流程执行。

Codex 资产名与独立二进制安装方式来自 [OpenAI Codex 官方仓库](https://github.com/openai/codex#installing-and-running-codex-cli)，固定版本及摘要来自 [rust-v0.147.0 官方 Release](https://github.com/openai/codex/releases/tag/rust-v0.147.0)。Ubuntu 镜像语法来自 [proot-distro 官方文档](https://github.com/termux/proot-distro#install--install-a-container)。

## 后果

- 配方字段拼写错误会在开发测试或应用加载时立即暴露，不再静默隐藏条目。
- 已满足验证条件的依赖会被跳过，安装流程可重复执行。
- 上游升级必须显式修改版本和摘要，并经配方同步测试、单测、APK 构建和 Lint 后发布。
- 安装仍需要在真实 Android + F-Droid Termux 环境做发布前验证；JVM 测试不能替代 proot/网络/二进制兼容性验证。
