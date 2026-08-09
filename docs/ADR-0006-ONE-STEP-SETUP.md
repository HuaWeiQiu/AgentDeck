# ADR-0006：单路径环境准备与可恢复安装

- 状态：已接受
- 日期：2026-08-09

## 背景

AgentDeck 已能检测 Termux、RUN_COMMAND、`allow-external-apps`、proot-distro、Ubuntu、Codex、认证和 wrapper，也能按配方依赖执行“探测、安装、再验证”。但检测在设置页、安装在工具页、认证在 Termux，用户需要自己判断下一步。长安装只返回最终结果，离开页面后缺少稳定的任务状态，重复点击还可能启动并发安装。

## 决策

1. 以一份应用级内部 `SetupState` 统一环境报告、扫描状态、安装阶段、错误和下一步动作。首次准备页、设置页和对话页只消费这份状态，不各自维护安装真相。
2. `SetupActionResolver` 按当前 `AgentRuntime` 的固定依赖顺序给出唯一主动作：准备运行环境、授予必要系统权限、安装或修复 Codex、检测或配置认证、开始对话。认证探针优先复用已有账号、API Key 或 Provider `env_key`，不会默认要求重复登录。Termux、PRoot、recipe ID、优先级和命令日志不进入标准流程。
3. Codex 环境安装由当前 Runtime 的受校验安装清单驱动。`TermuxRuntime` 继续使用 APK 内配方；`EmbeddedProotRuntime` 使用 ADR-0009 定义的签名 Runtime manifest 和版本化安装器。两者都逐项报告探测、安装、验证和完成阶段，不伪造下载百分比。
4. `TermuxRuntime` 的安装命令使用 Termux `$HOME/.agentdeck` 下的原子目录锁；`EmbeddedProotRuntime` 使用应用私有目录中的原子 staging、lease 和切换指针。活动安装拒绝第二个安装任务，异常退出后只回收可验证的陈旧锁或 lease，固定路径不接受用户输入。
5. `SetupCoordinator` 使用应用生命周期作用域，页面切换不取消安装。进程重建后不信任旧的“安装中”UI，而是通过当前 `AgentRuntime.inspect()` 重新探测；兼容后端的 Doctor 或内嵌后端的 manifest 状态决定继续、跳过、回滚或重试。
6. Android 权限、较大下载、危险操作和缺失时的 Codex 认证仍保留必要的用户确认。AgentDeck 负责把当前动作合并成一个按钮，并在用户返回前台时自动复检。Termux 专用确认只在用户选择兼容后端时出现。
7. 安装成功必须以当前 Runtime 的最终功能探测为准；进程请求被系统接受、脚本退出或下载完成均不能单独代表环境可用。

## 后果

- 已安装环境直接显示就绪，不再出现误导性的“安装 / 修复”。
- 用户不需要理解 proot、recipe 依赖或手工输入 `apt`/下载命令。
- `TermuxRuntime` 的后台任务无法被 Android 进程可靠取消，因此兼容安装必须保持幂等并用锁防止重复；`EmbeddedProotRuntime` 必须由前台服务持有可取消的进程句柄。
- 真机仍需覆盖首次安装、返回前台、网络失败、App 进程重建和陈旧锁恢复。
- `SetupState` 的技术详情必须映射为 ADR-0008 定义的有限客户状态；标准模式不能直接渲染 shell 或协议错误。
