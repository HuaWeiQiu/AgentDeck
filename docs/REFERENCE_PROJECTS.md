# Reference projects and gap analysis

AgentDeck 的核心不是重写 Agent，而是把真实 CLI 的安装、环境验证和会话入口做成 Android 产品。最值得参考的是 Termux 官方生态及 CLI 上游，而不是另一套聊天 UI。

## 对照矩阵

| 项目 | 可借鉴 | AgentDeck 不照搬 |
|---|---|---|
| [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) | executable、argv、workdir、命名会话、结果回调和权限契约 | 不把“Intent 已发送”当成命令成功，不依赖未验证的私有行为 |
| [Termux:Widget](https://github.com/termux/termux-widget) | 轻量入口、前台/后台任务区分、由 Termux 承担真实终端 | 不扫描任意用户脚本充当可信“商店”，不在桌面入口暴露未校验命令 |
| [Termux:Tasker](https://github.com/termux/termux-tasker) | 结构化 executable/args/workdir、运行结果、错误展示和调试信息 | 不接受动态 shell 模板、任意 env 或凭据变量，不扩大 AgentDeck 的密钥边界 |
| [PRoot Distro](https://github.com/termux/proot-distro) | 明确的发行版生命周期、固定镜像版本、架构识别和备份/恢复语义 | 不自动 reset/remove 已有发行版；版本不匹配时保留用户数据并明确失败 |
| [OpenAI Codex](https://github.com/openai/codex) | 官方发布资产、CLI 登录状态、版本与参数契约 | 不复制 Agent loop，不读取或代理 Codex 凭据，不猜测不稳定配置键 |

## 已吸收的设计

- `TermuxCommand` 保持固定 executable 与独立 argv，前台命名会话和后台结果任务分开。
- Doctor 与安装器以真实 exit code/stdout/stderr 判定结果，并对回调设置超时和输出上限。
- 配方固定 Ubuntu、CLI 版本和官方资产 SHA-256；安装后必须重新验证。
- 卡片是产品入口，终端和 Agent TUI 继续由 Termux/Codex 负责。
- Profile 只保存非敏感元数据，不能成为跨 CLI 密钥仓库。

## 尚缺能力

### 发布前必须完成

1. 在 F-Droid Termux 0.118.3 真机上验证权限、回调、命名会话复用、Ubuntu 24.04、Codex 登录和 TUI。
2. 从 v0.1.0 APK 做真实升级，验证 Room v1 -> v2 -> v3、旧凭据清理和用户数据保留。
3. 配置正式签名，在 GitHub Actions 首次通过后再发布，不把 debug APK 当正式产物。

### P1 优先补齐

1. 有界且可取消的安装事件流：保存步骤、时间、exit code 和脱敏摘要，支持导出诊断包。
2. 会话历史：先记录“最近发起”，只有获得可靠生命周期信号后才显示“运行中”。
3. CLI adapter 合规清单：版本探测、认证探测、固定入口、Provider 类型、真机证据齐全后才设为可用。
4. 卡片/Profile 元数据导入导出：带 schema 版本、冲突预览和事务回滚，永不包含凭据。
5. 配方升级说明：记录资产来源、摘要变更、支持架构和迁移影响，便于审查供应链变化。

## 暂不扩展

- 不做 App 内聊天代理或 HTTP prompt 转发。
- 不做远程可执行配方市场；远程配方的签名、审核、撤销和回滚体系未建立前，继续只信任 APK 内资产。
- 不以“多 CLI”数量为目标。第二个 adapter 必须复用同一安全边界并通过真机验证。
