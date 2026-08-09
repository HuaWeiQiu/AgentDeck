# ADR-0001：Termux 兼容后端的执行与认证边界

- 状态：已接受
- 日期：2026-08-08

## 背景

AgentDeck 通过 Termux 的 `RUN_COMMAND` Intent 启动真实 Agent CLI。早期骨架把工作目录、CLI 参数和 API Key 拼进 `bash -lc` 字符串，再作为 Intent 参数发送。这会带来三个问题：

1. API Key 可能出现在 Intent、进程参数或 Termux 调试日志中。
2. 用户可编辑路径和参数需要经过多层 shell 引号，容易产生错误或命令注入。
3. 会话名称使用了过期字段，重复点击卡片会创建多个会话。

Android 应用也不能直接写入 Termux 的私有目录，因此不存在一个既简单又可靠的跨应用密钥文件通道。

ADR-0009 接受后，本 ADR 只约束 `TermuxRuntime` 兼容实现。新的 UI、领域逻辑和安装状态不得继续直接依赖 Termux Intent 或路径。

## 决策

AgentDeck 是控制平面，TermuxRuntime 和 CLI 是兼容执行平面。两者遵循以下边界：

1. AgentDeck 只向 Termux 发送固定、绝对的可执行文件路径和结构化参数数组。
2. 多步启动逻辑放在 AgentDeck 安装的受控 wrapper 中；动态值通过位置参数传入，不拼接成 shell 源码。
3. Codex/Claude 等 CLI 的既有 API Key、OAuth token 和登录状态仍由 CLI 自己管理。用户主动添加的第三方 Provider 密钥可以由 AgentDeck Keystore 管理，但不得通过 Intent、argv、stdin、生成的脚本或 Termux 持久文件传递；只允许按 ADR-0007 通过鉴权回环 broker 提供给固定 `auth.command` helper。
4. 前台会话使用 `RUN_COMMAND_SHELL_NAME` 和 `RUN_COMMAND_SHELL_CREATE_MODE=no-shell-with-name`。同一张卡片重复进入时，切换到既有会话；不存在时才创建。
5. `RUN_COMMAND_SESSION_ACTION` 必须使用 Termux 约定的字符串值。
6. 后台探测和安装命令必须使用 Termux 结果回调确认退出码，不能以“服务成功启动”代表命令成功。

## 后果

- Room 中的模型 Profile 只保存非敏感配置；受管密钥的密文与引用按 ADR-0007 分离保存。
- 用户可以继续在对应 CLI 内完成官方登录，也可以为支持的第三方 Provider 单独添加受管密钥。
- 自定义启动命令必须经过受控适配器或 wrapper，不能直接接受任意 shell 文本。
- Recipe 必须安装固定 wrapper，并对安装和探测结果进行验证。
- 标准模式不得显示本 ADR 中的 Termux、Intent、wrapper 或路径细节；客户状态映射遵循 ADR-0008。
